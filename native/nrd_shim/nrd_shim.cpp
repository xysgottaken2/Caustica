// Flat C ABI shim over NVIDIA NRD (NVIDIA Real-time Denoisers) for the Caustica RT renderer.
//
// NRD is a C++/NRI library and Java's FFM can only bind a flat C ABI, so this DLL wraps NRD's
// REBLUR_DIFFUSE_SPECULAR denoiser behind a handful of primitive-argument entry points (the same
// trade-off as ngx_shim / fsr_shim / xess_shim). The heavy lifting is delegated to NRD's own
// Integration layer (NRDIntegration.hpp), which owns the NRI pipelines/descriptors and records the
// denoiser passes straight into the renderer's command buffer.
//
// ---------------------------------------------------------------------------------------------
// RESOURCE CONTRACT (all render resolution, all left in VK_IMAGE_LAYOUT_GENERAL by the renderer,
// which is exactly what nri::Layout::GENERAL describes):
//
//   IN_MV                     rg16f   screen-space motion, previous - current, in RENDER PIXELS
//                                     (converted to UV by CommonSettings::motionVectorScale)
//   IN_NORMAL_ROUGHNESS       rgba16f world-space normal xyz + LINEAR roughness in w. The library is
//                                     built with NRD_NORMAL_ENCODING=4 (RGBA16_SNORM) and
//                                     NRD_ROUGHNESS_ENCODING=1 (LINEAR), whose unpack is exactly
//                                     "xyz, w" — the tracer's existing guide needs no repack.
//   IN_VIEWZ                  r32f    positive linear view depth of the primary hit; sky is written
//                                     far beyond CommonSettings::denoisingRange (NRD's INF marker).
//   IN_DIFF_RADIANCE_HITDIST  rgba16f YCoCg DEMODULATED diffuse radiance + normalized hit distance
//   IN_SPEC_RADIANCE_HITDIST  rgba16f YCoCg DEMODULATED specular radiance + normalized hit distance
//   OUT_DIFF_RADIANCE_HITDIST rgba16f denoised (the renderer re-modulates + sums both)
//   OUT_SPEC_RADIANCE_HITDIST rgba16f denoised
//   OUT_VALIDATION            rgba8   optional diagnostic overlay
//
// ---------------------------------------------------------------------------------------------
// MATRIX / SETTINGS CONTRACT — the part the previous integration got wrong, and the reason REBLUR
// had to be disabled. NRD is strict about these and silently produces garbage reprojection when
// they are violated (verified against NRD v4.17.3's InstanceImpl.cpp):
//
//  * viewToClip / worldToView are COLUMN-MAJOR float[16] used with COLUMN vectors, and must be the
//    NON-JITTERED matrices. NRD calls DecomposeProjection(STYLE_D3D, ...) on viewToClip: it detects
//    handedness itself and converts a RH input to LH by negating the view-Z column/row of BOTH
//    matrices. So the renderer must pass its matrices UNMODIFIED rather than pre-flipping anything;
//    a pre-flip made NRD flip a second time, mirroring reconstructed positions about the camera.
//  * "worldToViewMatrixPrev" must describe the PREVIOUS frame's camera in the SAME world space as
//    the current one. NRD derives its camera delta from the two translations
//    (InstanceImpl::SetCommonSettings -> translationDelta), so a renderer whose world origin is
//    rebased between frames must express both matrices against the CURRENT anchor or NRD sees a
//    teleport and rejects all history.
//  * The projection's depth row matters: NRD derives every world-space scale (frustum size,
//    disocclusion thresholds, hit-distance factors, blur radii) from it via DecomposeProjection.
//    Minecraft's float-Z reverse-Z projection carries a degenerate effective near plane, which
//    shrinks every threshold by ~40x — the previous integration's "history only survives near the
//    screen centre" circle. The host sanitizes that row before calling; see RtNrdDenoiser.
//  * cameraJitter / cameraJitterPrev are in PIXEL units within [-0.5, 0.5]; NRD asserts on the
//    range, and cameraJitterPrev must be the jitter the HISTORY was rendered with, not this frame's.
//  * frameIndex must increment by exactly 1 per frame; NRD's Integration layer keys its constant
//    buffer ring and descriptor pools on it.
//
// Every one of those constraints is enforced or asserted on the Java side (RtNrdDenoiser) and
// re-validated here, so a violation fails loudly with a diagnostic instead of quietly producing
// the smeared/trembling image the previous integration shipped.

// Standard headers first: NRD's Integration layer uses snprintf/memcpy without including <cstdio>
// or <cstring> itself (it happens to compile on MSVC, where they arrive transitively), so pulling
// them in up front keeps the shim buildable with every toolchain rather than only the CI's.
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include <vulkan/vulkan.h>

#include "NRI.h"
#include "Extensions/NRIHelper.h"
// NRIWrapperVK.h's AccelerationStructureVKDesc references nri::AccelerationStructureBits, which
// lives in NRIRayTracing.h — a header NRIWrapperVK.h does not include itself (it expects the
// includer to have pulled it in), so it must come first.
#include "Extensions/NRIRayTracing.h"
#include "Extensions/NRIWrapperVK.h"

#include "NRD.h"
#include "NRDIntegration.h"
// Header-only implementation of the Integration layer (method bodies live in the .hpp).
#include "NRDIntegration.hpp"

// ---------------------------------------------------------------------------------------------
// Diagnostics. The shim runs inside the JVM through FFM, where a fault surfaces as a bare crash
// with no Java stack, so every failure path logs a specific message. Verbose tracing of the
// per-frame calls is opt-in through NRDSHIM_VERBOSE=1 to keep the hot path silent by default.
// ---------------------------------------------------------------------------------------------
static const bool g_verbose = std::getenv("NRDSHIM_VERBOSE") != nullptr;

static void nrdShimLog(const char* format, ...) {
    va_list args;
    va_start(args, format);
    std::fprintf(stderr, "[nrd_shim] ");
    std::vfprintf(stderr, format, args);
    std::fprintf(stderr, "\n");
    std::fflush(stderr);
    va_end(args);
}

#define NRD_SHIM_TRACE(...)          \
    do {                             \
        if (g_verbose) {             \
            nrdShimLog(__VA_ARGS__); \
        }                            \
    } while (0)

// Status codes returned to Java. Positive values are nrd::Result as-is (SUCCESS == 0); the shim's
// own failures are negative so the two can never be confused.
enum NrdShimStatus {
    NRDSHIM_OK = 0,
    NRDSHIM_ERR_NOT_INITIALIZED = -1,
    NRDSHIM_ERR_INVALID_ARGUMENT = -2,
    NRDSHIM_ERR_ALLOCATION = -3,
    NRDSHIM_ERR_EXCEPTION = -4,
};

static nrd::Integration* g_integration = nullptr;
static nri::DeviceCreationVKDesc g_deviceDesc = {};
static std::vector<nri::QueueFamilyVKDesc> g_queueFamilies;
static uint32_t g_width = 0;
static uint32_t g_height = 0;
static int g_lastResult = 0;
// Set once the very first denoise is recorded; used only to make the log say "REBLUR is running".
static bool g_loggedFirstDenoise = false;
// One stable denoiser id for the single REBLUR_DIFFUSE_SPECULAR instance.
static const nrd::Identifier DENOISER_ID = 0;

// ---------------------------------------------------------------------------------------------
// REBLUR tuning last sent by Java. A resize rebuilds the NRD instance with library defaults, so the
// stored values are re-applied at the end of every successful init. Sentinels (0 for the counts,
// <= 0 for the floats) mean "keep NRD's own default", which keeps the Java side free to send only
// what it actually wants to override.
// ---------------------------------------------------------------------------------------------
struct ReblurTuning {
    int hitDistanceReconstructionMode = 0; // nrd::HitDistanceReconstructionMode
    int maxAccumulatedFrameNum = 0;
    int maxFastAccumulatedFrameNum = 0;
    int maxStabilizedFrameNum = -1; // -1 = default; 0 legitimately means "no stabilization"
    int historyFixFrameNum = -1;
    float antilagLuminanceSigmaScale = 0.0f;
    float antilagLuminanceSensitivity = 0.0f;
    float fireflySuppressorMinRelativeScale = 0.0f;
    float diffusePrepassBlurRadius = -1.0f; // -1 = default; 0 legitimately means "disabled"
    float specularPrepassBlurRadius = -1.0f;
    float minBlurRadius = -1.0f;
    float maxBlurRadius = -1.0f;
    float lobeAngleFraction = 0.0f;
    float roughnessFraction = 0.0f;
    float planeDistanceSensitivity = 0.0f;
    float fastHistoryClampingSigmaScale = 0.0f;
    int enableAntiFirefly = 1;
    bool valid = false; // false until Java sends tuning at least once
};

static ReblurTuning g_tuning;

/** Push {@link g_tuning} into the live NRD instance. No-op before the instance exists. */
static int applyStoredReblurSettings() {
    if (!g_integration) {
        return NRDSHIM_ERR_NOT_INITIALIZED;
    }
    // Default-constructed: every field the host does not override keeps NRD's own tuned default.
    nrd::ReblurSettings settings = {};

    settings.hitDistanceReconstructionMode =
            (nrd::HitDistanceReconstructionMode) g_tuning.hitDistanceReconstructionMode;
    if (g_tuning.maxAccumulatedFrameNum > 0) {
        settings.maxAccumulatedFrameNum = (uint32_t) g_tuning.maxAccumulatedFrameNum;
    }
    if (g_tuning.maxFastAccumulatedFrameNum > 0) {
        settings.maxFastAccumulatedFrameNum = (uint32_t) g_tuning.maxFastAccumulatedFrameNum;
    }
    if (g_tuning.maxStabilizedFrameNum >= 0) {
        settings.maxStabilizedFrameNum = (uint32_t) g_tuning.maxStabilizedFrameNum;
    }
    if (g_tuning.historyFixFrameNum >= 0) {
        settings.historyFixFrameNum = (uint32_t) g_tuning.historyFixFrameNum;
    }
    if (g_tuning.antilagLuminanceSigmaScale > 0.0f) {
        settings.antilagSettings.luminanceSigmaScale = g_tuning.antilagLuminanceSigmaScale;
    }
    if (g_tuning.antilagLuminanceSensitivity > 0.0f) {
        settings.antilagSettings.luminanceSensitivity = g_tuning.antilagLuminanceSensitivity;
    }
    if (g_tuning.fireflySuppressorMinRelativeScale > 0.0f) {
        settings.fireflySuppressorMinRelativeScale = g_tuning.fireflySuppressorMinRelativeScale;
    }
    if (g_tuning.diffusePrepassBlurRadius >= 0.0f) {
        settings.diffusePrepassBlurRadius = g_tuning.diffusePrepassBlurRadius;
    }
    if (g_tuning.specularPrepassBlurRadius >= 0.0f) {
        settings.specularPrepassBlurRadius = g_tuning.specularPrepassBlurRadius;
    }
    if (g_tuning.minBlurRadius >= 0.0f) {
        settings.minBlurRadius = g_tuning.minBlurRadius;
    }
    if (g_tuning.maxBlurRadius >= 0.0f) {
        settings.maxBlurRadius = g_tuning.maxBlurRadius;
    }
    if (g_tuning.lobeAngleFraction > 0.0f) {
        settings.lobeAngleFraction = g_tuning.lobeAngleFraction;
    }
    if (g_tuning.roughnessFraction > 0.0f) {
        settings.roughnessFraction = g_tuning.roughnessFraction;
    }
    if (g_tuning.planeDistanceSensitivity > 0.0f) {
        settings.planeDistanceSensitivity = g_tuning.planeDistanceSensitivity;
    }
    if (g_tuning.fastHistoryClampingSigmaScale > 0.0f) {
        settings.fastHistoryClampingSigmaScale = g_tuning.fastHistoryClampingSigmaScale;
    }
    settings.enableAntiFirefly = g_tuning.enableAntiFirefly != 0;
    // "maxFastAccumulatedFrameNum >= maxAccumulatedFrameNum" disables the fast history entirely,
    // which is a legal but almost certainly unintended configuration; clamp instead of surprising
    // the caller with a silently different denoiser.
    if (settings.maxFastAccumulatedFrameNum >= settings.maxAccumulatedFrameNum
            && settings.maxAccumulatedFrameNum > 1) {
        settings.maxFastAccumulatedFrameNum = settings.maxAccumulatedFrameNum / 2;
    }

    nrd::Result result = g_integration->SetDenoiserSettings(DENOISER_ID, &settings);
    g_lastResult = (int) result;
    if (result != nrd::Result::SUCCESS) {
        nrdShimLog("SetDenoiserSettings failed: %d", (int) result);
        return (int) result;
    }
    NRD_SHIM_TRACE("settings applied: accum=%u fast=%u stab=%u historyFix=%u hitDistRecon=%d antiFirefly=%d",
            settings.maxAccumulatedFrameNum, settings.maxFastAccumulatedFrameNum,
            settings.maxStabilizedFrameNum, settings.historyFixFrameNum,
            (int) settings.hitDistanceReconstructionMode, settings.enableAntiFirefly ? 1 : 0);
    return NRDSHIM_OK;
}

/** Tear the integration down. Safe to call when nothing exists. */
static void destroyIntegration() {
    if (!g_integration) {
        return;
    }
    g_integration->Destroy();
    delete g_integration;
    g_integration = nullptr;
}

/** A finite float[16]: catches a NaN/garbage matrix before NRD builds its whole frame from it. */
static bool matrixIsFinite(const float* m) {
    if (!m) {
        return false;
    }
    for (int i = 0; i < 16; i++) {
        if (!std::isfinite(m[i])) {
            return false;
        }
    }
    return true;
}

extern "C" {

#if defined(_WIN32)
#define NRD_SHIM_EXPORT __declspec(dllexport)
#else
#define NRD_SHIM_EXPORT __attribute__((visibility("default")))
#endif

/** Last nrd::Result seen by any entry point, for error reporting on the Java side. */
NRD_SHIM_EXPORT int nrdshim_last_result() {
    return g_lastResult;
}

/** NRD library version, packed major.minor.build into one int (for logging / compatibility checks). */
NRD_SHIM_EXPORT int nrdshim_version() {
    const nrd::LibraryDesc& desc = *nrd::GetLibraryDesc();
    return (int) ((desc.versionMajor << 16) | (desc.versionMinor << 8) | desc.versionBuild);
}

/**
 * Wrap the renderer's existing Vulkan device into NRI and (re)create the REBLUR instance for the
 * given render resolution. Also the resize path: RecreateVK destroys and rebuilds internally.
 * queueFamilyIndex is the graphics queue family; vkMinorVersion is the Vulkan 1.x minor (NRD needs
 * >= 2). Returns 0 on success, otherwise nrd::Result or a negative NrdShimStatus.
 */
NRD_SHIM_EXPORT int nrdshim_init(unsigned long long vkInstance, unsigned long long vkPhysicalDevice,
                                 unsigned long long vkDevice, unsigned long long vkGetInstanceProcAddr,
                                 unsigned int queueFamilyIndex, unsigned int vkMinorVersion,
                                 unsigned int width, unsigned int height) {
    (void) vkGetInstanceProcAddr; // NRI resolves entry points from the wrapped device itself
    NRD_SHIM_TRACE("init: instance=%p phys=%p device=%p queueFamily=%u vk1.%u size=%ux%u",
            (void*) vkInstance, (void*) vkPhysicalDevice, (void*) vkDevice,
            queueFamilyIndex, vkMinorVersion, width, height);

    if (!vkInstance || !vkPhysicalDevice || !vkDevice) {
        nrdShimLog("init: null Vulkan handle");
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }
    // NRD stores resource dimensions as uint16_t, so an out-of-range size would silently truncate
    // into a mismatched-resolution denoiser.
    if (width == 0 || height == 0 || width > 0xFFFFu || height > 0xFFFFu) {
        nrdShimLog("init: unsupported resolution %ux%u", width, height);
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }
    if (vkMinorVersion < 2) {
        nrdShimLog("init: Vulkan 1.%u is below NRD's 1.2 minimum", vkMinorVersion);
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }

    destroyIntegration();

    const nrd::LibraryDesc& libraryDesc = *nrd::GetLibraryDesc();
    NRD_SHIM_TRACE("NRD v%u.%u.%u, %u denoisers, normalEncoding=%u roughnessEncoding=%u",
            libraryDesc.versionMajor, libraryDesc.versionMinor, libraryDesc.versionBuild,
            libraryDesc.supportedDenoisersNum,
            (unsigned) libraryDesc.normalEncoding, (unsigned) libraryDesc.roughnessEncoding);
    // The renderer's guide format is only compatible with this exact pair (see the contract above).
    // Building the library with different encodings would corrupt normals/roughness in a way that
    // looks like a denoiser bug, so refuse instead.
    if (libraryDesc.normalEncoding != nrd::NormalEncoding::RGBA16_SNORM
            || libraryDesc.roughnessEncoding != nrd::RoughnessEncoding::LINEAR) {
        nrdShimLog("init: NRD built with normalEncoding=%u roughnessEncoding=%u, expected RGBA16_SNORM + LINEAR",
                (unsigned) libraryDesc.normalEncoding, (unsigned) libraryDesc.roughnessEncoding);
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }

    g_queueFamilies.clear();
    nri::QueueFamilyVKDesc graphicsFamily = {};
    graphicsFamily.queueNum = 1;
    graphicsFamily.queueType = nri::QueueType::GRAPHICS;
    graphicsFamily.familyIndex = queueFamilyIndex;
    g_queueFamilies.push_back(graphicsFamily);

    g_deviceDesc = {};
    g_deviceDesc.vkInstance = (VKHandle) vkInstance;
    g_deviceDesc.vkDevice = (VKHandle) vkDevice;
    g_deviceDesc.vkPhysicalDevice = (VKHandle) vkPhysicalDevice;
    g_deviceDesc.queueFamilies = g_queueFamilies.data();
    g_deviceDesc.queueFamilyNum = (uint32_t) g_queueFamilies.size();
    g_deviceDesc.minorVersion = (uint8_t) vkMinorVersion;
    // NRI must remap SPIR-V descriptor bindings with the same offsets NRD's shaders were compiled
    // with; both sides read them from the library description, so they cannot drift.
    g_deviceDesc.vkBindingOffsets.sRegister = libraryDesc.spirvBindingOffsets.samplerOffset;
    g_deviceDesc.vkBindingOffsets.tRegister = libraryDesc.spirvBindingOffsets.textureOffset;
    g_deviceDesc.vkBindingOffsets.bRegister = libraryDesc.spirvBindingOffsets.constantBufferOffset;
    g_deviceDesc.vkBindingOffsets.uRegister = libraryDesc.spirvBindingOffsets.storageTextureAndBufferOffset;

    nrd::IntegrationCreationDesc integrationDesc = {};
    std::snprintf(integrationDesc.name, sizeof(integrationDesc.name), "Caustica REBLUR");
    integrationDesc.resourceWidth = (uint16_t) width;
    integrationDesc.resourceHeight = (uint16_t) height;
    // Caustica records the next frame while previous ones may still execute, so NRD's per-frame
    // constant data and descriptor sets must be separated across the same in-flight window.
    integrationDesc.queuedFrameNum = 3;
    // Descriptor caching only within a single Denoise call: the renderer owns every input/output
    // image and recreates them on resize (which triggers a full re-init here), so lifetime-wide
    // caching would pin descriptors to images that are about to be destroyed.
    integrationDesc.enableWholeLifetimeDescriptorCaching = false;
    // The renderer coordinates all barriers and submission around the denoise call; the shim must
    // never stall the device behind its back.
    integrationDesc.autoWaitForIdle = false;

    nrd::InstanceCreationDesc instanceDesc = {};
    nrd::DenoiserDesc denoisers[] = {{DENOISER_ID, nrd::Denoiser::REBLUR_DIFFUSE_SPECULAR}};
    instanceDesc.denoisers = denoisers;
    instanceDesc.denoisersNum = 1;

    g_integration = new (std::nothrow) nrd::Integration();
    if (!g_integration) {
        nrdShimLog("init: out of memory allocating the integration");
        return NRDSHIM_ERR_ALLOCATION;
    }

    nrd::Result result = g_integration->RecreateVK(integrationDesc, instanceDesc, g_deviceDesc);
    g_lastResult = (int) result;
    if (result != nrd::Result::SUCCESS) {
        nrdShimLog("RecreateVK failed: %d", (int) result);
        destroyIntegration();
        return (int) result;
    }

    g_width = width;
    g_height = height;
    g_loggedFirstDenoise = false;
    // The rebuilt instance starts from NRD defaults; re-apply whatever tuning Java last sent so a
    // resize cannot silently change the denoiser's behaviour.
    if (g_tuning.valid) {
        int rc = applyStoredReblurSettings();
        if (rc != NRDSHIM_OK) {
            nrdShimLog("init: re-applying stored REBLUR settings failed: %d", rc);
            destroyIntegration();
            return rc;
        }
    }
    nrdShimLog("initialized %ux%u (NRD v%u.%u.%u, %.1f Mb)", width, height,
            libraryDesc.versionMajor, libraryDesc.versionMinor, libraryDesc.versionBuild,
            g_integration->GetTotalMemoryUsageInMb());
    return NRDSHIM_OK;
}

/**
 * Store and apply REBLUR tuning. Safe to call before init: the values are remembered and applied
 * when the instance is created (and re-applied after every resize).
 *
 * Sentinels: the frame counts and blur radii use -1 for "keep NRD's default" because 0 is a
 * meaningful value for them; the remaining floats use <= 0, and hitDistReconstructionMode maps
 * directly onto nrd::HitDistanceReconstructionMode (0 OFF, 1 AREA_3X3, 2 AREA_5X5).
 */
NRD_SHIM_EXPORT int nrdshim_set_reblur_settings(int hitDistReconstructionMode,
                                                int maxAccumulatedFrameNum,
                                                int maxFastAccumulatedFrameNum,
                                                int maxStabilizedFrameNum,
                                                int historyFixFrameNum,
                                                float antilagLuminanceSigmaScale,
                                                float antilagLuminanceSensitivity,
                                                float fireflySuppressorMinRelativeScale,
                                                float diffusePrepassBlurRadius,
                                                float specularPrepassBlurRadius,
                                                float minBlurRadius,
                                                float maxBlurRadius,
                                                float lobeAngleFraction,
                                                float roughnessFraction,
                                                float planeDistanceSensitivity,
                                                float fastHistoryClampingSigmaScale,
                                                int enableAntiFirefly) {
    if (hitDistReconstructionMode < 0
            || hitDistReconstructionMode >= (int) nrd::HitDistanceReconstructionMode::MAX_NUM) {
        nrdShimLog("set_reblur_settings: invalid hitDistReconstructionMode %d", hitDistReconstructionMode);
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }
    if (maxAccumulatedFrameNum > (int) nrd::REBLUR_MAX_HISTORY_FRAME_NUM
            || maxFastAccumulatedFrameNum > (int) nrd::REBLUR_MAX_HISTORY_FRAME_NUM
            || maxStabilizedFrameNum > (int) nrd::REBLUR_MAX_HISTORY_FRAME_NUM) {
        nrdShimLog("set_reblur_settings: frame counts above REBLUR_MAX_HISTORY_FRAME_NUM (%u)",
                nrd::REBLUR_MAX_HISTORY_FRAME_NUM);
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }

    g_tuning.hitDistanceReconstructionMode = hitDistReconstructionMode;
    g_tuning.maxAccumulatedFrameNum = maxAccumulatedFrameNum;
    g_tuning.maxFastAccumulatedFrameNum = maxFastAccumulatedFrameNum;
    g_tuning.maxStabilizedFrameNum = maxStabilizedFrameNum;
    g_tuning.historyFixFrameNum = historyFixFrameNum;
    g_tuning.antilagLuminanceSigmaScale = antilagLuminanceSigmaScale;
    g_tuning.antilagLuminanceSensitivity = antilagLuminanceSensitivity;
    g_tuning.fireflySuppressorMinRelativeScale = fireflySuppressorMinRelativeScale;
    g_tuning.diffusePrepassBlurRadius = diffusePrepassBlurRadius;
    g_tuning.specularPrepassBlurRadius = specularPrepassBlurRadius;
    g_tuning.minBlurRadius = minBlurRadius;
    g_tuning.maxBlurRadius = maxBlurRadius;
    g_tuning.lobeAngleFraction = lobeAngleFraction;
    g_tuning.roughnessFraction = roughnessFraction;
    g_tuning.planeDistanceSensitivity = planeDistanceSensitivity;
    g_tuning.fastHistoryClampingSigmaScale = fastHistoryClampingSigmaScale;
    g_tuning.enableAntiFirefly = enableAntiFirefly;
    g_tuning.valid = true;

    if (!g_integration) {
        return NRDSHIM_OK; // remembered; applied by nrdshim_init
    }
    return applyStoredReblurSettings();
}

/** Frame start: advances NRD's internal frame index (its constant-buffer / descriptor ring slot). */
NRD_SHIM_EXPORT void nrdshim_new_frame() {
    if (g_integration) {
        g_integration->NewFrame();
    }
}

/**
 * Per-frame common settings. See the matrix contract at the top of this file: matrices are
 * column-major float[16], NON-jittered, and passed exactly as the renderer built them (NRD handles
 * handedness itself). Jitter is in pixels within [-0.5, 0.5]; mvScale converts the renderer's
 * render-pixel motion vectors into the UV space NRD reprojects with.
 *
 * `reset` != 0 clears the accumulation for this frame (first frame, resize, teleport, FOV change).
 */
NRD_SHIM_EXPORT int nrdshim_set_settings(const float* viewToClip, const float* viewToClipPrev,
                                         const float* worldToView, const float* worldToViewPrev,
                                         float jitterX, float jitterY,
                                         float jitterPrevX, float jitterPrevY,
                                         float mvScaleX, float mvScaleY,
                                         float denoisingRange, float disocclusionThreshold,
                                         unsigned int frameIndex, int reset, int enableValidation) {
    if (!g_integration) {
        return NRDSHIM_ERR_NOT_INITIALIZED;
    }
    {
        // Name the offending matrix AND dump it. "non-finite matrix" alone cost a debugging round
        // trip: the caller could not tell which of the four was bad, nor whether the value was NaN,
        // an infinity or an absurd-but-finite number produced upstream.
        const char* names[4] = {"viewToClip", "viewToClipPrev", "worldToView", "worldToViewPrev"};
        const float* mats[4] = {viewToClip, viewToClipPrev, worldToView, worldToViewPrev};
        for (int i = 0; i < 4; i++) {
            if (!matrixIsFinite(mats[i])) {
                const float* m = mats[i];
                if (m) {
                    nrdShimLog("set_settings: non-finite %s = "
                            "[%g %g %g %g | %g %g %g %g | %g %g %g %g | %g %g %g %g]",
                            names[i], m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7],
                            m[8], m[9], m[10], m[11], m[12], m[13], m[14], m[15]);
                } else {
                    nrdShimLog("set_settings: %s is null", names[i]);
                }
                return NRDSHIM_ERR_INVALID_ARGUMENT;
            }
        }
    }
    // NRD asserts these in debug builds and misbehaves silently in release; catch them here where
    // the message can name the offending value.
    if (!(jitterX >= -0.5f && jitterX <= 0.5f && jitterY >= -0.5f && jitterY <= 0.5f)
            || !(jitterPrevX >= -0.5f && jitterPrevX <= 0.5f
                 && jitterPrevY >= -0.5f && jitterPrevY <= 0.5f)) {
        nrdShimLog("set_settings: jitter out of [-0.5, 0.5]: cur=(%f, %f) prev=(%f, %f)",
                jitterX, jitterY, jitterPrevX, jitterPrevY);
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }
    if (!(mvScaleX != 0.0f && mvScaleY != 0.0f) || !std::isfinite(mvScaleX) || !std::isfinite(mvScaleY)) {
        nrdShimLog("set_settings: invalid motion vector scale (%f, %f)", mvScaleX, mvScaleY);
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }
    if (!(denoisingRange > 0.0f) || !(disocclusionThreshold > 0.0f)) {
        nrdShimLog("set_settings: denoisingRange/disocclusionThreshold must be > 0 (%f, %f)",
                denoisingRange, disocclusionThreshold);
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }

    nrd::CommonSettings settings = {};
    std::memcpy(settings.viewToClipMatrix, viewToClip, 16 * sizeof(float));
    std::memcpy(settings.viewToClipMatrixPrev, viewToClipPrev, 16 * sizeof(float));
    std::memcpy(settings.worldToViewMatrix, worldToView, 16 * sizeof(float));
    std::memcpy(settings.worldToViewMatrixPrev, worldToViewPrev, 16 * sizeof(float));
    settings.motionVectorScale[0] = mvScaleX;
    settings.motionVectorScale[1] = mvScaleY;
    settings.motionVectorScale[2] = 0.0f; // 2D screen-space motion
    settings.cameraJitter[0] = jitterX;
    settings.cameraJitter[1] = jitterY;
    settings.cameraJitterPrev[0] = jitterPrevX;
    settings.cameraJitterPrev[1] = jitterPrevY;
    settings.resourceSize[0] = (uint16_t) g_width;
    settings.resourceSize[1] = (uint16_t) g_height;
    settings.resourceSizePrev[0] = (uint16_t) g_width;
    settings.resourceSizePrev[1] = (uint16_t) g_height;
    settings.rectSize[0] = (uint16_t) g_width;
    settings.rectSize[1] = (uint16_t) g_height;
    settings.rectSizePrev[0] = (uint16_t) g_width;
    settings.rectSizePrev[1] = (uint16_t) g_height;
    settings.viewZScale = 1.0f;
    settings.denoisingRange = denoisingRange;
    settings.disocclusionThreshold = disocclusionThreshold;
    settings.frameIndex = frameIndex;
    // RESTART, not CLEAR_AND_RESTART: both drop the history, but CLEAR_AND_RESTART additionally
    // zero-fills every internal resource, which NRD itself documents as slow. The renderer resets on
    // teleports and FOV changes, i.e. often enough that the per-frame cost matters, and the extra
    // clearing buys nothing because the accumulation is restarted regardless.
    settings.accumulationMode = reset ? nrd::AccumulationMode::RESTART
                                      : nrd::AccumulationMode::CONTINUE;
    settings.isMotionVectorInWorldSpace = false;
    settings.enableValidation = enableValidation != 0;

    nrd::Result result = g_integration->SetCommonSettings(settings);
    g_lastResult = (int) result;
    if (result != nrd::Result::SUCCESS) {
        nrdShimLog("SetCommonSettings failed: %d (frame %u)", (int) result, frameIndex);
        return (int) result;
    }
    return NRDSHIM_OK;
}

/** Describe one renderer-owned image to NRD in the state the renderer leaves it in. */
static nrd::Resource makeResource(unsigned long long image, int vkFormat) {
    nrd::Resource resource = {};
    resource.vk.image = (VKNonDispatchableHandle) image;
    resource.vk.format = (VKEnum) vkFormat;
    // Every image is left in GENERAL after the trace pass's storage writes. NRD inserts its own
    // transitions from this declared state and, with restoreInitialState, returns them to it.
    resource.state.access = nri::AccessBits::SHADER_RESOURCE_STORAGE;
    resource.state.layout = nri::Layout::GENERAL;
    resource.state.stages = nri::StageBits::COMPUTE_SHADER;
    return resource;
}

/**
 * Record the REBLUR_DIFFUSE_SPECULAR denoise into the renderer's currently-recording command
 * buffer. All textures are render resolution and the formats are raw VkFormat enums. When
 * validationImage != 0 it is bound as OUT_VALIDATION (which additionally requires enableValidation
 * in the common settings).
 */
NRD_SHIM_EXPORT int nrdshim_denoise(unsigned long long cmd,
                                    unsigned long long mvImage, int mvFormat,
                                    unsigned long long normalRoughnessImage, int normalRoughnessFormat,
                                    unsigned long long viewZImage, int viewZFormat,
                                    unsigned long long diffInImage, int diffInFormat,
                                    unsigned long long specInImage, int specInFormat,
                                    unsigned long long diffOutImage, int diffOutFormat,
                                    unsigned long long specOutImage, int specOutFormat,
                                    unsigned long long validationImage, int validationFormat) {
    if (!g_integration) {
        return NRDSHIM_ERR_NOT_INITIALIZED;
    }
    if (!cmd || !mvImage || !normalRoughnessImage || !viewZImage
            || !diffInImage || !specInImage || !diffOutImage || !specOutImage) {
        nrdShimLog("denoise: null command buffer or required image");
        return NRDSHIM_ERR_INVALID_ARGUMENT;
    }

    nrd::ResourceSnapshot snapshot;
    // Hand every image back in GENERAL so the renderer's own barriers around this call stay valid.
    snapshot.restoreInitialState = true;

    nrd::Resource mvResource = makeResource(mvImage, mvFormat);
    nrd::Resource normalResource = makeResource(normalRoughnessImage, normalRoughnessFormat);
    nrd::Resource viewZResource = makeResource(viewZImage, viewZFormat);
    nrd::Resource diffInResource = makeResource(diffInImage, diffInFormat);
    nrd::Resource specInResource = makeResource(specInImage, specInFormat);
    nrd::Resource diffOutResource = makeResource(diffOutImage, diffOutFormat);
    nrd::Resource specOutResource = makeResource(specOutImage, specOutFormat);
    snapshot.SetResource(nrd::ResourceType::IN_MV, mvResource);
    snapshot.SetResource(nrd::ResourceType::IN_NORMAL_ROUGHNESS, normalResource);
    snapshot.SetResource(nrd::ResourceType::IN_VIEWZ, viewZResource);
    snapshot.SetResource(nrd::ResourceType::IN_DIFF_RADIANCE_HITDIST, diffInResource);
    snapshot.SetResource(nrd::ResourceType::IN_SPEC_RADIANCE_HITDIST, specInResource);
    snapshot.SetResource(nrd::ResourceType::OUT_DIFF_RADIANCE_HITDIST, diffOutResource);
    snapshot.SetResource(nrd::ResourceType::OUT_SPEC_RADIANCE_HITDIST, specOutResource);
    if (validationImage != 0) {
        nrd::Resource validationResource = makeResource(validationImage, validationFormat);
        snapshot.SetResource(nrd::ResourceType::OUT_VALIDATION, validationResource);
    }

    nri::CommandBufferVKDesc cmdDesc = {};
    cmdDesc.vkCommandBuffer = (VKHandle) cmd;
    cmdDesc.queueType = nri::QueueType::GRAPHICS;

    const nrd::Identifier denoisers[] = {DENOISER_ID};
    g_integration->DenoiseVK(denoisers, 1, cmdDesc, snapshot);
    if (!g_loggedFirstDenoise) {
        g_loggedFirstDenoise = true;
        nrdShimLog("REBLUR denoising %ux%u", g_width, g_height);
    }
    return NRDSHIM_OK;
}

/** Destroy the integration. The renderer must have waited for idle first (autoWaitForIdle = false). */
NRD_SHIM_EXPORT void nrdshim_destroy() {
    destroyIntegration();
    g_width = 0;
    g_height = 0;
    g_loggedFirstDenoise = false;
}

} // extern "C"
