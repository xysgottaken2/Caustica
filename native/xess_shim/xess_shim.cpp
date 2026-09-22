// Flat C ABI shim over Intel XeSS (libxess.dll), Vulkan backend.
//
// XeSS ships only a prebuilt Windows runtime (libxess.dll) plus a plain-C API (xess.h / xess_vk.h).
// Java's FFM binds this flat C shim rather than reconstructing the XeSS structs byte-for-byte, the
// same trade-off as fsr_shim / ngx_shim / nrd_shim.
//
// The runtime is LOADED, not linked: xess.h declares its entry points dllexport under
// XESS_SHARED_LIB, which fights linking the import library. Instead xessshim_init receives the
// runtime path, LoadLibrary's it and resolves the handful of entry points we use.
//
// Resource contract with the renderer (mirrors what fsr_shim does for FSR): the renderer keeps its
// images in GENERAL layout. XeSS wants inputs in SHADER_READ_ONLY_OPTIMAL and the output in GENERAL,
// so the shim transitions the inputs down before xessVKExecute and restores them to GENERAL after.

#include <vulkan/vulkan.h>
#include <xess/xess.h>
#include <xess/xess_vk.h>

#if defined(_WIN32)
#include <windows.h>
#endif

#include <cstring>
#include <cstdlib>
#include <cstdio>

static const bool g_verbose = std::getenv("XESSSHIM_VERBOSE") != nullptr;
#define XESS_LOG(...)                                        \
    do {                                                     \
        if (g_verbose) {                                     \
            std::fprintf(stderr, "[xess_shim] " __VA_ARGS__); \
            std::fprintf(stderr, "\n");                      \
            std::fflush(stderr);                             \
        }                                                    \
    } while (0)

static VkDevice g_device = VK_NULL_HANDLE;
static PFN_vkGetDeviceProcAddr g_deviceProcAddr = nullptr;
static int g_lastResult = 0;

// Resolved libxess.dll entry points.
static HMODULE g_xessModule = nullptr;
typedef xess_result_t (*PFN_xessGetVersion)(xess_version_t*);
typedef xess_result_t (*PFN_xessVKCreateContext)(VkInstance, VkPhysicalDevice, VkDevice, xess_context_handle_t*);
typedef xess_result_t (*PFN_xessVKInit)(xess_context_handle_t, const xess_vk_init_params_t*);
typedef xess_result_t (*PFN_xessVKExecute)(xess_context_handle_t, VkCommandBuffer, const xess_vk_execute_params_t*);
typedef xess_result_t (*PFN_xessGetInputResolution)(xess_context_handle_t, const xess_2d_t*, xess_quality_settings_t, xess_2d_t*);
typedef xess_result_t (*PFN_xessDestroyContext)(xess_context_handle_t);
typedef xess_result_t (*PFN_xessSetLoggingCallback)(xess_context_handle_t, xess_logging_level_t, xess_app_log_callback_t);
static PFN_xessGetVersion p_xessGetVersion = nullptr;
static PFN_xessVKCreateContext p_xessVKCreateContext = nullptr;
static PFN_xessVKInit p_xessVKInit = nullptr;
static PFN_xessVKExecute p_xessVKExecute = nullptr;
static PFN_xessGetInputResolution p_xessGetInputResolution = nullptr;
static PFN_xessDestroyContext p_xessDestroyContext = nullptr;
static PFN_xessSetLoggingCallback p_xessSetLoggingCallback = nullptr;

static xess_context_handle_t g_context = nullptr;

static void xessLogCallback(const char* message, xess_logging_level_t level) {
    std::fprintf(stderr, "[xess %d] %s\n", (int) level, message ? message : "");
    std::fflush(stderr);
}

static void fillViewInfo(xess_vk_image_view_info* info, VkImageView view, VkImage image,
                         VkFormat format, unsigned int width, unsigned int height, bool isDepth) {
    info->imageView = view;
    info->image = image;
    info->subresourceRange.aspectMask = isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
    info->subresourceRange.baseMipLevel = 0;
    info->subresourceRange.levelCount = 1;
    info->subresourceRange.baseArrayLayer = 0;
    info->subresourceRange.layerCount = 1;
    info->format = format;
    info->width = width;
    info->height = height;
}

// Transition one image between layouts with a full pipeline barrier.
static void transitionImage(VkCommandBuffer cmd, VkImage image,
                           VkImageLayout oldLayout, VkImageLayout newLayout,
                           VkAccessFlags srcAccess, VkAccessFlags dstAccess,
                           VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage) {
    PFN_vkCmdPipelineBarrier cmdPipelineBarrier =
            (PFN_vkCmdPipelineBarrier) g_deviceProcAddr(g_device, "vkCmdPipelineBarrier");
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = VK_REMAINING_MIP_LEVELS;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = VK_REMAINING_ARRAY_LAYERS;
    cmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);
}

extern "C" {

#if defined(_WIN32)
#define XESS_SHIM_EXPORT __declspec(dllexport)
#else
#define XESS_SHIM_EXPORT __attribute__((visibility("default")))
#endif

XESS_SHIM_EXPORT int xessshim_last_result() {
    return g_lastResult;
}

// Loads libxess.dll from runtimePath and captures the device handles. Returns 0 on success.
XESS_SHIM_EXPORT int xessshim_init(unsigned long long vkDevice, unsigned long long vkDeviceProcAddr,
                                   const wchar_t* runtimePath) {
    XESS_LOG("init: device=%p gdpa=%p runtime=%ls", (void*) vkDevice, (void*) vkDeviceProcAddr, runtimePath);
    if (vkDevice == 0 || vkDeviceProcAddr == 0 || runtimePath == nullptr) {
        return 1;
    }
    g_xessModule = LoadLibraryW(runtimePath);
    if (!g_xessModule) {
        std::fprintf(stderr, "[xess_shim] LoadLibraryW(%ls) failed: error %lu\n",
                runtimePath, GetLastError());
        std::fflush(stderr);
        return 2;
    }
    p_xessGetVersion = (PFN_xessGetVersion) GetProcAddress(g_xessModule, "xessGetVersion");
    p_xessVKCreateContext = (PFN_xessVKCreateContext) GetProcAddress(g_xessModule, "xessVKCreateContext");
    p_xessVKInit = (PFN_xessVKInit) GetProcAddress(g_xessModule, "xessVKInit");
    p_xessVKExecute = (PFN_xessVKExecute) GetProcAddress(g_xessModule, "xessVKExecute");
    p_xessGetInputResolution = (PFN_xessGetInputResolution) GetProcAddress(g_xessModule, "xessGetInputResolution");
    p_xessDestroyContext = (PFN_xessDestroyContext) GetProcAddress(g_xessModule, "xessDestroyContext");
    p_xessSetLoggingCallback = (PFN_xessSetLoggingCallback) GetProcAddress(g_xessModule, "xessSetLoggingCallback");
    if (!p_xessVKCreateContext || !p_xessVKInit || !p_xessVKExecute || !p_xessGetInputResolution ||
        !p_xessDestroyContext) {
        std::fprintf(stderr, "[xess_shim] xess entry points missing (create=%p init=%p exec=%p res=%p destroy=%p)\n",
                (void*) p_xessVKCreateContext, (void*) p_xessVKInit, (void*) p_xessVKExecute,
                (void*) p_xessGetInputResolution, (void*) p_xessDestroyContext);
        std::fflush(stderr);
        return 3;
    }
    g_device = (VkDevice) vkDevice;
    g_deviceProcAddr = (PFN_vkGetDeviceProcAddr) vkDeviceProcAddr;
    if (p_xessGetVersion) {
        xess_version_t v = {};
        if (p_xessGetVersion(&v) == XESS_RESULT_SUCCESS) {
            XESS_LOG("libxess version %u.%u.%u", v.major, v.minor, v.patch);
        }
    }
    return 0;
}

// Creates the XeSS VK context. Must be called once after xessshim_init. The device must already have
// been created with XeSS's required features/extensions (injected at vkCreateDevice time by the mod).
XESS_SHIM_EXPORT int xessshim_create_context(unsigned long long vkInstance,
                                             unsigned long long vkPhysicalDevice) {
    if (!p_xessVKCreateContext) {
        return 1;
    }
    if (g_context) {
        return 0; // already created
    }
    xess_result_t r = p_xessVKCreateContext((VkInstance) vkInstance, (VkPhysicalDevice) vkPhysicalDevice,
                                            g_device, &g_context);
    g_lastResult = (int) r;
    XESS_LOG("create_context r=%d", (int) r);
    if (r != XESS_RESULT_SUCCESS) {
        g_context = nullptr;
        return (int) r;
    }
    if (p_xessSetLoggingCallback) {
        p_xessSetLoggingCallback(g_context, XESS_LOGGING_LEVEL_WARNING, xessLogCallback);
    }
    return 0;
}

// Initializes the upscaler for a given output resolution + quality. quality is a
// xess_quality_settings_t value (100..106). initFlags is a bitmask of xess_init_flags_t.
XESS_SHIM_EXPORT int xessshim_init_upscaler(unsigned int outputWidth, unsigned int outputHeight,
                                            int quality, unsigned int initFlags) {
    if (!g_context || !p_xessVKInit) {
        return 1;
    }
    xess_vk_init_params_t params = {};
    params.outputResolution.x = outputWidth;
    params.outputResolution.y = outputHeight;
    params.qualitySetting = (xess_quality_settings_t) quality;
    params.initFlags = initFlags;
    params.creationNodeMask = 1;
    params.visibleNodeMask = 1;
    params.tempBufferHeap = VK_NULL_HANDLE;
    params.bufferHeapOffset = 0;
    params.tempTextureHeap = VK_NULL_HANDLE;
    params.textureHeapOffset = 0;
    params.pipelineCache = VK_NULL_HANDLE;
    xess_result_t r = p_xessVKInit(g_context, &params);
    g_lastResult = (int) r;
    XESS_LOG("init_upscaler %ux%u q=%d flags=0x%x r=%d", outputWidth, outputHeight, quality, initFlags, (int) r);
    return (int) r;
}

// Returns the input resolution XeSS wants for a given output resolution + quality.
XESS_SHIM_EXPORT int xessshim_query_input_resolution(unsigned int outputWidth, unsigned int outputHeight,
                                                     int quality, unsigned int* outInputWidth,
                                                     unsigned int* outInputHeight) {
    if (!g_context || !p_xessGetInputResolution) {
        return 1;
    }
    xess_2d_t outputRes = { outputWidth, outputHeight };
    xess_2d_t inputRes = {};
    xess_result_t r = p_xessGetInputResolution(g_context, &outputRes,
                                               (xess_quality_settings_t) quality, &inputRes);
    g_lastResult = (int) r;
    if (r != XESS_RESULT_SUCCESS) {
        return (int) r;
    }
    if (outInputWidth) *outInputWidth = inputRes.x;
    if (outInputHeight) *outInputHeight = inputRes.y;
    return 0;
}

// Records one XeSS upscale into the (recording) command buffer. Inputs (color/velocity/depth) are
// transitioned GENERAL -> SHADER_READ_ONLY for the dispatch and restored to GENERAL after; the output
// is transitioned UNDEFINED -> GENERAL before and left GENERAL for the caller.
XESS_SHIM_EXPORT int xessshim_execute(unsigned long long cmd,
                                      unsigned long long colorImage, unsigned long long colorView,
                                      int colorFormat, unsigned int colorWidth, unsigned int colorHeight,
                                      unsigned long long velocityImage, unsigned long long velocityView,
                                      int velocityFormat, unsigned int velocityWidth, unsigned int velocityHeight,
                                      unsigned long long depthImage, unsigned long long depthView,
                                      int depthFormat,
                                      unsigned long long outputImage, unsigned long long outputView,
                                      int outputFormat, unsigned int outputWidth, unsigned int outputHeight,
                                      float jitterX, float jitterY, int resetHistory,
                                      int hasDepth) {
    if (!g_context || !p_xessVKExecute) {
        return 1;
    }
    VkCommandBuffer vkCmd = (VkCommandBuffer) cmd;

    // Inputs GENERAL -> SHADER_READ_ONLY (XeSS reads them).
    transitionImage(vkCmd, (VkImage) colorImage, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    transitionImage(vkCmd, (VkImage) velocityImage, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    if (hasDepth) {
        transitionImage(vkCmd, (VkImage) depthImage, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }
    // Output UNDEFINED -> GENERAL (XeSS writes it).
    transitionImage(vkCmd, (VkImage) outputImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    0, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    xess_vk_execute_params_t params = {};
    fillViewInfo(&params.colorTexture, (VkImageView) colorView, (VkImage) colorImage,
                 (VkFormat) colorFormat, colorWidth, colorHeight, false);
    fillViewInfo(&params.velocityTexture, (VkImageView) velocityView, (VkImage) velocityImage,
                 (VkFormat) velocityFormat, velocityWidth, velocityHeight, false);
    if (hasDepth) {
        fillViewInfo(&params.depthTexture, (VkImageView) depthView, (VkImage) depthImage,
                     (VkFormat) depthFormat, colorWidth, colorHeight, true);
    } else {
        params.depthTexture.image = VK_NULL_HANDLE;
        params.depthTexture.imageView = VK_NULL_HANDLE;
    }
    params.exposureScaleTexture.image = VK_NULL_HANDLE;
    params.exposureScaleTexture.imageView = VK_NULL_HANDLE;
    fillViewInfo(&params.outputTexture, (VkImageView) outputView, (VkImage) outputImage,
                 (VkFormat) outputFormat, outputWidth, outputHeight, false);
    params.jitterOffsetX = jitterX;
    params.jitterOffsetY = jitterY;
    params.exposureScale = 1.0f;
    params.resetHistory = resetHistory ? 1 : 0;
    params.inputWidth = colorWidth;
    params.inputHeight = colorHeight;
    params.inputColorBase = { 0, 0 };
    params.inputMotionVectorBase = { 0, 0 };
    params.inputDepthBase = { 0, 0 };
    params.inputResponsiveMaskBase = { 0, 0 };
    params.reserved0 = { 0, 0 };
    params.outputColorBase = { 0, 0 };

    xess_result_t r = p_xessVKExecute(g_context, vkCmd, &params);
    g_lastResult = (int) r;
    if (r != XESS_RESULT_SUCCESS) {
        XESS_LOG("execute FAILED r=%d", (int) r);
    }

    // Restore inputs back to GENERAL so the renderer's subsequent barriers stay valid.
    transitionImage(vkCmd, (VkImage) colorImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    transitionImage(vkCmd, (VkImage) velocityImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    if (hasDepth) {
        transitionImage(vkCmd, (VkImage) depthImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
    }
    return (int) r;
}

XESS_SHIM_EXPORT void xessshim_destroy_upscaler() {
    if (g_context && p_xessDestroyContext) {
        p_xessDestroyContext(g_context);
        g_context = nullptr;
    }
}

XESS_SHIM_EXPORT void xessshim_shutdown() {
    xessshim_destroy_upscaler();
    if (g_xessModule) {
        FreeLibrary(g_xessModule);
        g_xessModule = nullptr;
    }
    g_device = VK_NULL_HANDLE;
    g_deviceProcAddr = nullptr;
}

} // extern "C"
