// Flat C ABI shim over the AMD FidelityFX (ffx_api) Vulkan runtime, for FSR 3.1 upscaling.
//
// Unlike NGX (static library + macro helper layer), the FidelityFX SDK ships a signed, prebuilt
// shared runtime (amd_fidelityfx_vk.dll) with a deliberately flat C entry surface — but its
// descriptors are nested C structs (pNext-chained headers, resource descriptions) that Java's FFM
// would otherwise have to reconstruct byte-for-byte. Keeping those structs on the C++ side (same
// trade-off as ngx_shim) makes the Java bindings primitives-only and immune to struct-layout drift
// across SDK versions.
//
// The runtime is LOADED, not linked: ffx_api.h declares its entry points __declspec(dllexport)
// (AMD expects the API compiled into the consumer), so linking the import library would fight the
// header. Instead fsrshim_init receives the runtime's path, LoadLibrary's it and resolves the five
// ffx entry points — the same dynamic pattern ffx_api_loader.h recommends.
//
// Built as a SHARED library; every exported symbol is undecorated extern "C".

#include <vulkan/vulkan.h>

#include <ffx_api/ffx_api.h>
#include <ffx_api/ffx_api_types.h>
#include <ffx_api/ffx_framegeneration.h>
#include <ffx_api/ffx_upscale.h>
#include <ffx_api/vk/ffx_api_vk.h>

#if defined(_WIN32)
#include <windows.h>
#endif

#include <cstring>
#include <cstdlib>
#include <cstdio>

// Same diagnostic rationale as ngx_shim: the shim runs inside the JVM via FFM, so a fault here
// surfaces as a bare crash on the Java side. FSRSHIM_VERBOSE=1 enables per-call tracing.
static const bool g_verbose = std::getenv("FSRSHIM_VERBOSE") != nullptr;
#define FSR_LOG(...)                                       \
    do {                                                   \
        if (g_verbose) {                                   \
            std::fprintf(stderr, "[fsr_shim] " __VA_ARGS__); \
            std::fprintf(stderr, "\n");                    \
            std::fflush(stderr);                           \
        }                                                  \
    } while (0)

static VkDevice g_device = VK_NULL_HANDLE;
static VkPhysicalDevice g_physicalDevice = VK_NULL_HANDLE;
static PFN_vkGetDeviceProcAddr g_deviceProcAddr = nullptr;
static int g_lastResult = 0;

// Resolved ffx_api entry points (loaded from amd_fidelityfx_vk.dll at init).
static HMODULE g_ffxModule = nullptr;
static PfnFfxCreateContext p_ffxCreateContext = nullptr;
static PfnFfxDestroyContext p_ffxDestroyContext = nullptr;
static PfnFfxQuery p_ffxQuery = nullptr;
static PfnFfxDispatch p_ffxDispatch = nullptr;
static PfnFfxConfigure p_ffxConfigure = nullptr;

// Runtime messages (validation errors, warnings) straight to stderr — the ffx_api debug checker is
// off by default, so anything that does arrive is worth seeing.
static void messageCallback(uint32_t type, const wchar_t* message) {
    if (!message) {
        return;
    }
    std::fprintf(stderr, "[fsr_api %s] %ls\n", type == FFX_API_MESSAGE_TYPE_ERROR ? "error" : "warning", message);
    std::fflush(stderr);
}

// One upscale context + the descriptors it was created with. ffxCreateContext requires the desc
// memory to stay live until ffxDestroyContext, so both live in this heap block with the context.
struct FsrUpscaler {
    ffxContext context;
    ffxCreateContextDescUpscale createDesc;
    ffxCreateBackendVKDesc backendDesc;
};

// Build the FfxApiResource Caustica's GENERAL-layout images need. The ffx_api VK backend treats the
// resource pointer as the VkImage (it creates its own views) and transitions it FROM the declared
// state — COMMON/UNORDERED_ACCESS both map to VK_IMAGE_LAYOUT_GENERAL in the backend, which is
// exactly the layout Caustica keeps every image in, so no declared-state transition ever starts
// from a wrong oldLayout.
static FfxApiResource makeImageResource(VkImage image, VkFormat format,
                                        unsigned int width, unsigned int height, uint32_t state) {
    FfxApiResource resource;
    std::memset(&resource, 0, sizeof(resource));
    resource.resource = (void*) image;
    resource.description.type = FFX_API_RESOURCE_TYPE_TEXTURE2D;
    resource.description.format = ffxApiGetSurfaceFormatVK(format);
    resource.description.width = width;
    resource.description.height = height;
    resource.description.depth = 1;
    resource.description.mipCount = 1;
    resource.description.flags = FFX_API_RESOURCE_FLAGS_NONE;
    resource.description.usage = FFX_API_RESOURCE_USAGE_UAV;
    resource.state = state;
    return resource;
}

extern "C" {

#if defined(_WIN32)
#define FSR_SHIM_EXPORT __declspec(dllexport)
#else
#define FSR_SHIM_EXPORT __attribute__((visibility("default")))
#endif

// Last ffxReturnCode_t observed, for diagnostics from the Java side.
FSR_SHIM_EXPORT int fsrshim_last_result() {
    return g_lastResult;
}

// Loads the FidelityFX runtime from runtimePath and captures the device handles the upscale
// contexts need (ffx_api has no global init step; the device is handed per-context via
// ffxCreateBackendVKDesc). Returns 0 on success.
FSR_SHIM_EXPORT int fsrshim_init(unsigned long long vkDevice, unsigned long long vkPhysicalDevice,
                                 unsigned long long vkDeviceProcAddr, const wchar_t* runtimePath) {
    FSR_LOG("init: device=%p physicalDevice=%p gdpa=%p runtime=%ls",
            (void*) vkDevice, (void*) vkPhysicalDevice, (void*) vkDeviceProcAddr, runtimePath);
    if (vkDevice == 0 || vkDeviceProcAddr == 0 || runtimePath == nullptr) {
        return 1;
    }
    g_ffxModule = LoadLibraryW(runtimePath);
    if (!g_ffxModule) {
        std::fprintf(stderr, "[fsr_shim] LoadLibraryW(%ls) failed: error %lu\n",
                runtimePath, GetLastError());
        std::fflush(stderr);
        return 2;
    }
    p_ffxCreateContext = (PfnFfxCreateContext) GetProcAddress(g_ffxModule, "ffxCreateContext");
    p_ffxDestroyContext = (PfnFfxDestroyContext) GetProcAddress(g_ffxModule, "ffxDestroyContext");
    p_ffxQuery = (PfnFfxQuery) GetProcAddress(g_ffxModule, "ffxQuery");
    p_ffxDispatch = (PfnFfxDispatch) GetProcAddress(g_ffxModule, "ffxDispatch");
    // Optional: FSR 3.1's anti-ghosting tuning knobs; an older runtime simply skips the configure.
    p_ffxConfigure = (PfnFfxConfigure) GetProcAddress(g_ffxModule, "ffxConfigure");
    if (!p_ffxCreateContext || !p_ffxDestroyContext || !p_ffxQuery || !p_ffxDispatch) {
        std::fprintf(stderr, "[fsr_shim] ffx entry points missing (create=%p destroy=%p query=%p dispatch=%p)\n",
                (void*) p_ffxCreateContext, (void*) p_ffxDestroyContext, (void*) p_ffxQuery, (void*) p_ffxDispatch);
        std::fflush(stderr);
        return 3;
    }
    g_device = (VkDevice) vkDevice;
    g_physicalDevice = (VkPhysicalDevice) vkPhysicalDevice;
    g_deviceProcAddr = (PFN_vkGetDeviceProcAddr) vkDeviceProcAddr;
    return 0;
}

// Creates an FSR 3 upscale context sized for the given maxima (creation is the only moment the
// backend allocates its internal history/persistent resources). flags is the FFX_UPSCALE_ENABLE_*
// set; Caustica passes HDR + DEPTH_INVERTED + DEPTH_INFINITE + AUTO_EXPOSURE (reversed-Z rgba16f
// trace, render-res unjittered MVs, exposure estimated from the color itself).
FSR_SHIM_EXPORT void* fsrshim_create_upscaler(unsigned int maxRenderWidth, unsigned int maxRenderHeight,
                                              unsigned int displayWidth, unsigned int displayHeight,
                                              unsigned int flags) {
    FSR_LOG("create_upscaler: maxRender=%ux%u display=%ux%u flags=0x%x",
            maxRenderWidth, maxRenderHeight, displayWidth, displayHeight, flags);
    if (!p_ffxCreateContext) {
        return nullptr;
    }
    FsrUpscaler* upscaler = (FsrUpscaler*) std::calloc(1, sizeof(FsrUpscaler));
    if (!upscaler) {
        return nullptr;
    }
    upscaler->backendDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    upscaler->backendDesc.header.pNext = nullptr;
    upscaler->backendDesc.vkDevice = g_device;
    upscaler->backendDesc.vkPhysicalDevice = g_physicalDevice;
    upscaler->backendDesc.vkDeviceProcAddr = g_deviceProcAddr;

    upscaler->createDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_UPSCALE;
    upscaler->createDesc.header.pNext = &upscaler->backendDesc.header;
    upscaler->createDesc.flags = flags;
    upscaler->createDesc.maxRenderSize = { maxRenderWidth, maxRenderHeight };
    upscaler->createDesc.maxUpscaleSize = { displayWidth, displayHeight };
    upscaler->createDesc.fpMessage = messageCallback;

    ffxReturnCode_t r = p_ffxCreateContext(&upscaler->context, &upscaler->createDesc.header, nullptr);
    g_lastResult = (int) r;
    FSR_LOG("create_upscaler: ffxCreateContext r=%u", (unsigned) r);
    if (r != FFX_API_RETURN_OK) {
        std::free(upscaler);
        return nullptr;
    }
    return upscaler;
}

FSR_SHIM_EXPORT int fsrshim_destroy_upscaler(void* handle) {
    FsrUpscaler* upscaler = (FsrUpscaler*) handle;
    if (!upscaler) {
        return 0;
    }
    ffxReturnCode_t r = p_ffxDestroyContext ? p_ffxDestroyContext(&upscaler->context, nullptr)
                                            : FFX_API_RETURN_OK;
    g_lastResult = (int) r;
    std::free(upscaler);
    return (int) r;
}

// Records one FSR 3 upscale dispatch: jittered render-res HDR color + reversed-Z depth + render-res
// motion vectors (+ optional reactive mask) -> display-res output. jitterX/jitterY are the sub-pixel
// offsets applied to the camera this frame, in render pixels (negated per the FFX sample's
// convention); motion vectors are render-res pixels, so motionVectorScale is the render dimensions
// (sample convention again). cameraNear/cameraFar/cameraFovY follow the sample's reversed-Z mapping
// (near=FLT_MAX, far=the real near plane) — FSR only uses them for the depth linearization
// heuristic. The reactive mask marks pixels FSR must not lock its temporal history on (dynamic
// entities + transmissive surfaces); pass 0 when unavailable.
FSR_SHIM_EXPORT int fsrshim_dispatch_upscale(void* handle, unsigned long long cmd,
                                             unsigned long long colorImage, unsigned int colorFormat,
                                             unsigned long long depthImage, unsigned int depthFormat,
                                             unsigned long long mvImage, unsigned int mvFormat,
                                             unsigned long long reactiveImage, unsigned int reactiveFormat,
                                             unsigned long long outImage, unsigned int outFormat,
                                             unsigned int renderWidth, unsigned int renderHeight,
                                             unsigned int upscaleWidth, unsigned int upscaleHeight,
                                             float jitterX, float jitterY,
                                             float frameTimeMs, int reset,
                                             float cameraNear, float cameraFar, float cameraFovY) {
    FsrUpscaler* upscaler = (FsrUpscaler*) handle;
    if (!upscaler || !p_ffxDispatch) {
        return (int) FFX_API_RETURN_ERROR_PARAMETER;
    }
    ffxDispatchDescUpscale dispatch;
    std::memset(&dispatch, 0, sizeof(dispatch));
    dispatch.header.type = FFX_API_DISPATCH_DESC_TYPE_UPSCALE;
    dispatch.commandList = (void*) cmd;
    dispatch.color = makeImageResource((VkImage) colorImage, (VkFormat) colorFormat,
            renderWidth, renderHeight, FFX_API_RESOURCE_STATE_COMMON);
    dispatch.depth = makeImageResource((VkImage) depthImage, (VkFormat) depthFormat,
            renderWidth, renderHeight, FFX_API_RESOURCE_STATE_COMMON);
    dispatch.motionVectors = makeImageResource((VkImage) mvImage, (VkFormat) mvFormat,
            renderWidth, renderHeight, FFX_API_RESOURCE_STATE_COMMON);
    // Optional inputs: NONE bound. The reactive-mask experiment (feeding the tracer's moving-entity
    // mask into dispatch.reactive / transparencyAndComposition) was reverted at the renderer level:
    // it did not reduce ghosting and introduced shimmer regressions. reactiveImage is still accepted
    // for ABI stability but ignored — FSR runs on color + depth + motion vectors only, which is the
    // stable combination. Exposure stays on AUTO_EXPOSURE.
    dispatch.exposure = makeImageResource(VK_NULL_HANDLE, VK_FORMAT_UNDEFINED, 0, 0, FFX_API_RESOURCE_STATE_COMMON);
    dispatch.reactive = makeImageResource(VK_NULL_HANDLE, VK_FORMAT_UNDEFINED, 0, 0, FFX_API_RESOURCE_STATE_COMMON);
    dispatch.transparencyAndComposition = makeImageResource(VK_NULL_HANDLE, VK_FORMAT_UNDEFINED, 0, 0,
            FFX_API_RESOURCE_STATE_COMMON);
    dispatch.output = makeImageResource((VkImage) outImage, (VkFormat) outFormat,
            upscaleWidth, upscaleHeight, FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
    dispatch.jitterOffset = { jitterX, jitterY };
    // FSR multiplies the raw MVs by this scale and adds the result straight to UV space
    // (prepare_inputs: reprojectedUv = pixelUv + raw*scale), so the scale must convert raw -> UV.
    // Caustica's gMotion is render-pixel deltas, hence 1/renderSize. NOTE: AMD's fsrapi sample
    // passes renderSize here, but only because Cauldron pre-divides its MVs by the render size —
    // copying that value with pixel-space MVs amplifies motion by renderSize^2, which invalidates
    // the temporal reprojection every camera move (the "screen shakes and noise bursts" symptom).
    dispatch.motionVectorScale = { 1.0f / renderWidth, 1.0f / renderHeight };
    dispatch.renderSize = { renderWidth, renderHeight };
    dispatch.upscaleSize = { upscaleWidth, upscaleHeight };
    dispatch.enableSharpening = false; // the display mapper owns the final look; no double sharpening
    dispatch.sharpness = 0.0f;
    dispatch.frameTimeDelta = frameTimeMs;
    dispatch.preExposure = 1.0f;
    dispatch.reset = reset != 0;
    dispatch.cameraNear = cameraNear;
    dispatch.cameraFar = cameraFar;
    dispatch.cameraFovAngleVertical = cameraFovY;
    dispatch.viewSpaceToMetersFactor = 1.0f; // a Minecraft block is a meter
    dispatch.flags = 0;

    // ffx_api takes a POINTER to the context handle on every entry point (create may rewrite it).
    ffxReturnCode_t r = p_ffxDispatch(&upscaler->context, &dispatch.header);
    g_lastResult = (int) r;
    if (r != FFX_API_RETURN_OK) {
        FSR_LOG("dispatch_upscale: FAILED r=%u", (unsigned) r);
        return (int) r;
    }
    // FFX left the inputs in SHADER_READ_ONLY_OPTIMAL (its COMPUTE_READ layout) and never restores
    // external resources; Caustica's next passes assume GENERAL, so restore it here while the
    // dispatch's own barriers are still fresh in this command buffer.
    VkCommandBuffer vkCmd = (VkCommandBuffer) cmd;
    PFN_vkCmdPipelineBarrier cmdPipelineBarrier =
            (PFN_vkCmdPipelineBarrier) g_deviceProcAddr(g_device, "vkCmdPipelineBarrier");
    VkImage inputs[4] = { (VkImage) colorImage, (VkImage) depthImage, (VkImage) mvImage, (VkImage) reactiveImage };
    int inputCount = reactiveImage != 0 ? 4 : 3;
    VkImageMemoryBarrier barriers[4];
    for (int i = 0; i < inputCount; i++) {
        std::memset(&barriers[i], 0, sizeof(VkImageMemoryBarrier));
        barriers[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barriers[i].srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barriers[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
        barriers[i].oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barriers[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        barriers[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barriers[i].image = inputs[i];
        barriers[i].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barriers[i].subresourceRange.baseMipLevel = 0;
        barriers[i].subresourceRange.levelCount = VK_REMAINING_MIP_LEVELS;
        barriers[i].subresourceRange.baseArrayLayer = 0;
        barriers[i].subresourceRange.layerCount = VK_REMAINING_ARRAY_LAYERS;
    }
    cmdPipelineBarrier(vkCmd,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            0, 0, nullptr, 0, nullptr, (uint32_t) inputCount, barriers);
    return (int) r;
}

// Override one of FSR 3.1's anti-ghosting constants on a live context (FfxApiConfigureUpscaleKey in
// key, e.g. FSHADINGCHANGESCALE / FACCUMULATIONADDEDPERFRAME). Applied right after context creation.
FSR_SHIM_EXPORT int fsrshim_configure_upscale(void* handle, unsigned int key, float value) {
    FsrUpscaler* upscaler = (FsrUpscaler*) handle;
    if (!upscaler || !p_ffxConfigure) {
        return (int) FFX_API_RETURN_ERROR_PARAMETER;
    }
    ffxConfigureDescUpscaleKeyValue configure;
    std::memset(&configure, 0, sizeof(configure));
    configure.header.type = FFX_API_CONFIGURE_DESC_TYPE_UPSCALE_KEYVALUE;
    configure.key = key;
    configure.u64 = 0;
    configure.ptr = &value;
    ffxReturnCode_t r = p_ffxConfigure(&upscaler->context, &configure.header);
    g_lastResult = (int) r;
    return (int) r;
}

// Render resolution a quality mode wants for a display size (pure function; safe pre-context, the
// same way the render path queries it before creating the feature). qualityMode is the FFX
// FfxApiUpscaleQualityMode value.
FSR_SHIM_EXPORT int fsrshim_query_render_size(unsigned int qualityMode,
                                              unsigned int displayWidth, unsigned int displayHeight,
                                              unsigned int* outRenderWidth, unsigned int* outRenderHeight) {
    if (!p_ffxQuery) {
        return (int) FFX_API_RETURN_NO_PROVIDER;
    }
    ffxQueryDescUpscaleGetRenderResolutionFromQualityMode query;
    std::memset(&query, 0, sizeof(query));
    query.header.type = FFX_API_QUERY_DESC_TYPE_UPSCALE_GETRENDERRESOLUTIONFROMQUALITYMODE;
    query.displayWidth = displayWidth;
    query.displayHeight = displayHeight;
    query.qualityMode = qualityMode;
    query.pOutRenderWidth = outRenderWidth;
    query.pOutRenderHeight = outRenderHeight;
    ffxReturnCode_t r = p_ffxQuery(nullptr, &query.header);
    g_lastResult = (int) r;
    return (int) r;
}

// FSR 3 jitter phase count for a scale factor (pure function; no context needed).
FSR_SHIM_EXPORT int fsrshim_query_jitter_phase_count(unsigned int renderWidth, unsigned int displayWidth,
                                                     int* outPhaseCount) {
    if (!p_ffxQuery) {
        return (int) FFX_API_RETURN_NO_PROVIDER;
    }
    ffxQueryDescUpscaleGetJitterPhaseCount query;
    std::memset(&query, 0, sizeof(query));
    query.header.type = FFX_API_QUERY_DESC_TYPE_UPSCALE_GETJITTERPHASECOUNT;
    query.renderWidth = renderWidth;
    query.displayWidth = displayWidth;
    query.pOutPhaseCount = outPhaseCount;
    ffxReturnCode_t r = p_ffxQuery(nullptr, &query.header);
    g_lastResult = (int) r;
    return (int) r;
}

// Sub-pixel jitter offset (render pixels, [-0.5, 0.5]) at sequence position index of phaseCount.
FSR_SHIM_EXPORT int fsrshim_query_jitter_offset(int index, int phaseCount, float* outX, float* outY) {
    if (!p_ffxQuery) {
        return (int) FFX_API_RETURN_NO_PROVIDER;
    }
    ffxQueryDescUpscaleGetJitterOffset query;
    std::memset(&query, 0, sizeof(query));
    query.header.type = FFX_API_QUERY_DESC_TYPE_UPSCALE_GETJITTEROFFSET;
    query.index = index;
    query.phaseCount = phaseCount;
    query.pOutX = outX;
    query.pOutY = outY;
    ffxReturnCode_t r = p_ffxQuery(nullptr, &query.header);
    g_lastResult = (int) r;
    return (int) r;
}

// ======================================================================================================================================
// Frame Generation (manual/swapchain-less integration)
//
// FSR 3.1 FG without the FFX swapchain wrapper: Caustica owns Minecraft's swapchain and presents the
// generated frames itself (RtFramePresenter), so the FG context is created WITHOUT a swapchain and only
// the two compute dispatches are used — PREPARE once per rendered frame (depth + dilated-MV inputs +
// camera info), then GENERATE producing the interpolated frame into outputs[0].
//
// NOTE: the descriptor ALLOWS numGeneratedFrames up to 4, but the FSR 3.1 provider in the signed
// runtime only ever writes outputs[0] (ffx-api/src/ffx_provider_framegeneration.cpp forwards
// desc->outputs[0] alone into ffxFrameInterpolationDispatch) — the ABI accepts 1..4 here for
// completeness, but Caustica must only present the frames the runtime actually wrote (see
// RtFsrFrameGen.MAX_GENERATED_FRAMES), or it presents never-written images (black/garbage frames).
// ======================================================================================================================================

struct FsrFrameGen {
    ffxContext context;
    ffxCreateContextDescFrameGeneration createDesc;
    ffxCreateBackendVKDesc backendDesc;
};

// Creates an FSR 3.1 frame-generation context (manual mode: no swapchain configured). backBufferFormat
// is the VkFormat of the frames fed to generate (RGBA8 SDR / RGBA16f PQ HDR). flags is the
// FFX_FRAMEGENERATION_ENABLE_* set.
FSR_SHIM_EXPORT void* fsrshim_create_fg(unsigned int maxRenderWidth, unsigned int maxRenderHeight,
                                        unsigned int displayWidth, unsigned int displayHeight,
                                        unsigned int backBufferFormat, unsigned int flags) {
    FSR_LOG("create_fg: maxRender=%ux%u display=%ux%u bbFormat=%u flags=0x%x",
            maxRenderWidth, maxRenderHeight, displayWidth, displayHeight, backBufferFormat, flags);
    if (!p_ffxCreateContext) {
        return nullptr;
    }
    FsrFrameGen* fg = (FsrFrameGen*) std::calloc(1, sizeof(FsrFrameGen));
    if (!fg) {
        return nullptr;
    }
    fg->backendDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_BACKEND_VK;
    fg->backendDesc.header.pNext = nullptr;
    fg->backendDesc.vkDevice = g_device;
    fg->backendDesc.vkPhysicalDevice = g_physicalDevice;
    fg->backendDesc.vkDeviceProcAddr = g_deviceProcAddr;

    fg->createDesc.header.type = FFX_API_CREATE_CONTEXT_DESC_TYPE_FRAMEGENERATION;
    fg->createDesc.header.pNext = &fg->backendDesc.header;
    fg->createDesc.flags = flags;
    fg->createDesc.displaySize = { displayWidth, displayHeight };
    fg->createDesc.maxRenderSize = { maxRenderWidth, maxRenderHeight };
    fg->createDesc.backBufferFormat = ffxApiGetSurfaceFormatVK((VkFormat) backBufferFormat);

    ffxReturnCode_t r = p_ffxCreateContext(&fg->context, &fg->createDesc.header, nullptr);
    g_lastResult = (int) r;
    FSR_LOG("create_fg: ffxCreateContext r=%u", (unsigned) r);
    if (r != FFX_API_RETURN_OK) {
        std::free(fg);
        return nullptr;
    }
    return fg;
}

FSR_SHIM_EXPORT int fsrshim_destroy_fg(void* handle) {
    FsrFrameGen* fg = (FsrFrameGen*) handle;
    if (!fg) {
        return 0;
    }
    ffxReturnCode_t r = p_ffxDestroyContext ? p_ffxDestroyContext(&fg->context, nullptr)
                                            : FFX_API_RETURN_OK;
    g_lastResult = (int) r;
    std::free(fg);
    return (int) r;
}

// Per-rendered-frame FG prepare: dilated depth + motion vectors + camera info at render resolution.
// camera vectors/position are world-space (MC blocks). frameID must increment by exactly one per
// rendered frame across prepare+generate.
FSR_SHIM_EXPORT int fsrshim_fg_prepare(void* handle, unsigned long long cmd,
                                       unsigned long long depthImage, unsigned int depthFormat,
                                       unsigned long long mvImage, unsigned int mvFormat,
                                       unsigned int renderWidth, unsigned int renderHeight,
                                       float jitterX, float jitterY, float frameTimeMs,
                                       float cameraNear, float cameraFar, float cameraFovY,
                                       unsigned long long frameId,
                                       const float* camPos3, const float* camUp3,
                                       const float* camRight3, const float* camForward3) {
    FsrFrameGen* fg = (FsrFrameGen*) handle;
    if (!fg || !p_ffxDispatch) {
        return (int) FFX_API_RETURN_ERROR_PARAMETER;
    }
    ffxDispatchDescFrameGenerationPrepare prepare;
    std::memset(&prepare, 0, sizeof(prepare));
    prepare.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE;
    prepare.frameID = frameId;
    prepare.flags = 0;
    prepare.commandList = (void*) cmd;
    prepare.renderSize = { renderWidth, renderHeight };
    prepare.jitterOffset = { jitterX, jitterY };
    // Same raw->UV convention as the upscale dispatch: gMotion is render-pixel deltas.
    prepare.motionVectorScale = { 1.0f / renderWidth, 1.0f / renderHeight };
    prepare.frameTimeDelta = frameTimeMs;
    prepare.unused_reset = false;
    prepare.cameraNear = cameraNear;
    prepare.cameraFar = cameraFar;
    prepare.cameraFovAngleVertical = cameraFovY;
    prepare.viewSpaceToMetersFactor = 1.0f;
    prepare.depth = makeImageResource((VkImage) depthImage, (VkFormat) depthFormat,
            renderWidth, renderHeight, FFX_API_RESOURCE_STATE_COMMON);
    prepare.motionVectors = makeImageResource((VkImage) mvImage, (VkFormat) mvFormat,
            renderWidth, renderHeight, FFX_API_RESOURCE_STATE_COMMON);

    // CameraInfo chain (required input from FSR 3.1.4 on "for best quality").
    ffxDispatchDescFrameGenerationPrepareCameraInfo cameraInfo;
    std::memset(&cameraInfo, 0, sizeof(cameraInfo));
    cameraInfo.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION_PREPARE_CAMERAINFO;
    for (int i = 0; i < 3; i++) {
        cameraInfo.cameraPosition[i] = camPos3[i];
        cameraInfo.cameraUp[i] = camUp3[i];
        cameraInfo.cameraRight[i] = camRight3[i];
        cameraInfo.cameraForward[i] = camForward3[i];
    }
    prepare.header.pNext = &cameraInfo.header;

    ffxReturnCode_t r = p_ffxDispatch(&fg->context, &prepare.header);
    g_lastResult = (int) r;
    if (r != FFX_API_RETURN_OK) {
        FSR_LOG("fg_prepare: FAILED r=%u", (unsigned) r);
    }
    return (int) r;
}

// Generates interpolated frame(s) from presentImage into outImages in one dispatch. The ABI accepts
// numGeneratedFrames 1..4, but see the section note above: the FSR 3.1 runtime only writes
// outImages[0] — requesting more silently leaves outImages[1..] untouched. transferFunction is an
// FfxApiBackbufferTransferFunction (SRGB for the RGBA8 path, PQ for the HDR path). frameID must
// match the prepare call for this rendered frame.
FSR_SHIM_EXPORT int fsrshim_fg_generate(void* handle, unsigned long long cmd,
                                        unsigned long long presentImage, unsigned int presentFormat,
                                        const unsigned long long* outImages, unsigned int outFormat,
                                        unsigned int numGeneratedFrames,
                                        unsigned int width, unsigned int height,
                                        unsigned long long frameId,
                                        unsigned int transferFunction, int reset) {
    FsrFrameGen* fg = (FsrFrameGen*) handle;
    if (!fg || !p_ffxDispatch || numGeneratedFrames < 1 || numGeneratedFrames > 4) {
        return (int) FFX_API_RETURN_ERROR_PARAMETER;
    }
    ffxDispatchDescFrameGeneration generate;
    std::memset(&generate, 0, sizeof(generate));
    generate.header.type = FFX_API_DISPATCH_DESC_TYPE_FRAMEGENERATION;
    generate.commandList = (void*) cmd;
    generate.presentColor = makeImageResource((VkImage) presentImage, (VkFormat) presentFormat,
            width, height, FFX_API_RESOURCE_STATE_COMMON);
    for (unsigned int i = 0; i < numGeneratedFrames; i++) {
        generate.outputs[i] = makeImageResource((VkImage) outImages[i], (VkFormat) outFormat,
                width, height, FFX_API_RESOURCE_STATE_UNORDERED_ACCESS);
    }
    generate.numGeneratedFrames = numGeneratedFrames;
    generate.reset = reset != 0;
    generate.backbufferTransferFunction = transferFunction;
    // PQ linearization range (HDR10-style); ignored on the SRGB transfer function.
    generate.minMaxLuminance[0] = 0.0f;
    generate.minMaxLuminance[1] = 10000.0f;
    generate.generationRect.left = 0;
    generate.generationRect.top = 0;
    generate.generationRect.width = width;
    generate.generationRect.height = height;
    generate.frameID = frameId;

    ffxReturnCode_t r = p_ffxDispatch(&fg->context, &generate.header);
    g_lastResult = (int) r;
    if (r != FFX_API_RETURN_OK) {
        FSR_LOG("fg_generate: FAILED r=%u", (unsigned) r);
    }
    return (int) r;
}

} // extern "C"
