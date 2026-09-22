package dev.comfyfluffy.caustica.fsr;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings for the FSR shim ({@code fsrshim.dll}).
 *
 * <p>The shim wraps the AMD FidelityFX (ffx_api) Vulkan runtime behind a flat C ABI (see
 * {@code native/fsr_shim/fsr_shim.cpp}): every descriptor struct the ffx_api needs lives in the
 * shim, so Java only passes primitives and raw Vulkan handles (as {@code long} addresses), same
 * trade-off as {@code NgxLibrary}.
 */
public final class FsrLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle init;
    private final MethodHandle createUpscaler;
    private final MethodHandle destroyUpscaler;
    private final MethodHandle dispatchUpscale;
    private final MethodHandle configureUpscale;
    private final MethodHandle createFg;
    private final MethodHandle destroyFg;
    private final MethodHandle fgPrepare;
    private final MethodHandle fgGenerate;
    private final MethodHandle queryRenderSize;
    private final MethodHandle queryJitterPhaseCount;
    private final MethodHandle queryJitterOffset;
    private final MethodHandle lastResult;

    private FsrLibrary(SymbolLookup lookup) {
        // int fsrshim_init(u64 vkDevice, u64 vkPhysicalDevice, u64 vkGetDeviceProcAddr, wchar* runtimePath)
        this.init = handle(lookup, "fsrshim_init",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS));
        // void* fsrshim_create_upscaler(u32 maxRW, u32 maxRH, u32 dispW, u32 dispH, u32 flags)
        this.createUpscaler = handle(lookup, "fsrshim_create_upscaler",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // int fsrshim_destroy_upscaler(void* handle)
        this.destroyUpscaler = handle(lookup, "fsrshim_destroy_upscaler",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // int fsrshim_dispatch_upscale(handle, cmd, [color/depth/mv/reactive/out: image,fmt]*5,
        //                              rw,rh,uw,uh, jx, jy, frameMs, reset, cameraNear, cameraFar, cameraFovY)
        this.dispatchUpscale = handle(lookup, "fsrshim_dispatch_upscale",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT));
        // int fsrshim_configure_upscale(handle, u32 key, float value)
        this.configureUpscale = handle(lookup, "fsrshim_configure_upscale",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT));
        // void* fsrshim_create_fg(u32 maxRW, u32 maxRH, u32 dispW, u32 dispH, u32 bbFormat, u32 flags)
        this.createFg = handle(lookup, "fsrshim_create_fg",
                FunctionDescriptor.of(ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // int fsrshim_destroy_fg(void* handle)
        this.destroyFg = handle(lookup, "fsrshim_destroy_fg",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // int fsrshim_fg_prepare(handle, cmd, [depth,mv: image,fmt], rw, rh, jx, jy, frameMs,
        //                        camNear, camFar, fovY, u64 frameId, float*3 x4)
        this.fgPrepare = handle(lookup, "fsrshim_fg_prepare",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        // int fsrshim_fg_generate(handle, cmd, present[image,fmt], u64* outImages, outFmt, u32 numGen,
        //                         u32 w, u32 h, u64 frameId, u32 transferFn, int reset)
        this.fgGenerate = handle(lookup, "fsrshim_fg_generate",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // int fsrshim_query_render_size(u32 quality, u32 dispW, u32 dispH, u32* outRW, u32* outRH)
        this.queryRenderSize = handle(lookup, "fsrshim_query_render_size",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        // int fsrshim_query_jitter_phase_count(u32 renderW, u32 displayW, int* outCount)
        this.queryJitterPhaseCount = handle(lookup, "fsrshim_query_jitter_phase_count",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        // int fsrshim_query_jitter_offset(int index, int phaseCount, float* outX, float* outY)
        this.queryJitterOffset = handle(lookup, "fsrshim_query_jitter_offset",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.lastResult = handle(lookup, "fsrshim_last_result",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
    }

    public static FsrLibrary load(Path dll) {
        return new FsrLibrary(SymbolLookup.libraryLookup(dll, Arena.global()));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("fsrshim missing export " + name)),
                desc);
    }

    public int init(long vkDevice, long vkPhysicalDevice, long vkDeviceProcAddr, MemorySegment runtimePath) {
        try {
            return (int) this.init.invokeExact(vkDevice, vkPhysicalDevice, vkDeviceProcAddr, runtimePath);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_init failed", t);
        }
    }

    public MemorySegment createUpscaler(int maxRenderWidth, int maxRenderHeight,
                                        int displayWidth, int displayHeight, int flags) {
        try {
            return (MemorySegment) this.createUpscaler.invokeExact(
                    maxRenderWidth, maxRenderHeight, displayWidth, displayHeight, flags);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_create_upscaler failed", t);
        }
    }

    public int destroyUpscaler(MemorySegment handle) {
        try {
            return (int) this.destroyUpscaler.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_destroy_upscaler failed", t);
        }
    }

    public int dispatchUpscale(MemorySegment handle, long cmd,
                               long colorImage, int colorFormat,
                               long depthImage, int depthFormat,
                               long mvImage, int mvFormat,
                               long reactiveImage, int reactiveFormat,
                               long outImage, int outFormat,
                               int renderWidth, int renderHeight, int upscaleWidth, int upscaleHeight,
                               float jitterX, float jitterY, float frameTimeMs, int reset,
                               float cameraNear, float cameraFar, float cameraFovY) {
        try {
            return (int) this.dispatchUpscale.invokeExact(handle, cmd,
                    colorImage, colorFormat, depthImage, depthFormat, mvImage, mvFormat,
                    reactiveImage, reactiveFormat, outImage, outFormat,
                    renderWidth, renderHeight, upscaleWidth, upscaleHeight,
                    jitterX, jitterY, frameTimeMs, reset,
                    cameraNear, cameraFar, cameraFovY);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_dispatch_upscale failed", t);
        }
    }

    /** FSR 3.1 anti-ghosting tuning knob on a live context (FfxApiConfigureUpscaleKey). */
    public int configureUpscale(MemorySegment handle, int key, float value) {
        try {
            return (int) this.configureUpscale.invokeExact(handle, key, value);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_configure_upscale failed", t);
        }
    }

    public MemorySegment createFg(int maxRenderWidth, int maxRenderHeight,
                                  int displayWidth, int displayHeight, int backBufferFormat, int flags) {
        try {
            return (MemorySegment) this.createFg.invokeExact(
                    maxRenderWidth, maxRenderHeight, displayWidth, displayHeight, backBufferFormat, flags);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_create_fg failed", t);
        }
    }

    public int destroyFg(MemorySegment handle) {
        try {
            return (int) this.destroyFg.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_destroy_fg failed", t);
        }
    }

    public int fgPrepare(MemorySegment handle, long cmd,
                         long depthImage, int depthFormat, long mvImage, int mvFormat,
                         int renderWidth, int renderHeight, float jitterX, float jitterY,
                         float frameTimeMs, float cameraNear, float cameraFar, float cameraFovY,
                         long frameId, MemorySegment camPos3, MemorySegment camUp3,
                         MemorySegment camRight3, MemorySegment camForward3) {
        try {
            return (int) this.fgPrepare.invokeExact(handle, cmd,
                    depthImage, depthFormat, mvImage, mvFormat,
                    renderWidth, renderHeight, jitterX, jitterY,
                    frameTimeMs, cameraNear, cameraFar, cameraFovY,
                    frameId, camPos3, camUp3, camRight3, camForward3);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_fg_prepare failed", t);
        }
    }

    public int fgGenerate(MemorySegment handle, long cmd,
                          long presentImage, int presentFormat,
                          MemorySegment outImages, int outFormat, int numGeneratedFrames,
                          int width, int height, long frameId, int transferFunction, int reset) {
        try {
            return (int) this.fgGenerate.invokeExact(handle, cmd,
                    presentImage, presentFormat, outImages, outFormat, numGeneratedFrames,
                    width, height, frameId, transferFunction, reset);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_fg_generate failed", t);
        }
    }

    public int queryRenderSize(int qualityMode, int displayWidth, int displayHeight,
                               MemorySegment outRenderWidth, MemorySegment outRenderHeight) {
        try {
            return (int) this.queryRenderSize.invokeExact(
                    qualityMode, displayWidth, displayHeight, outRenderWidth, outRenderHeight);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_query_render_size failed", t);
        }
    }

    public int queryJitterPhaseCount(int renderWidth, int displayWidth, MemorySegment outPhaseCount) {
        try {
            return (int) this.queryJitterPhaseCount.invokeExact(renderWidth, displayWidth, outPhaseCount);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_query_jitter_phase_count failed", t);
        }
    }

    public int queryJitterOffset(int index, int phaseCount, MemorySegment outX, MemorySegment outY) {
        try {
            return (int) this.queryJitterOffset.invokeExact(index, phaseCount, outX, outY);
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_query_jitter_offset failed", t);
        }
    }

    public int lastResult() {
        try {
            return (int) this.lastResult.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("fsrshim_last_result failed", t);
        }
    }
}
