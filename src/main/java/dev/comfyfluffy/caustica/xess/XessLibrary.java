package dev.comfyfluffy.caustica.xess;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings for the XeSS shim ({@code xessshim.dll}).
 *
 * <p>The shim wraps the Intel XeSS Vulkan runtime behind a flat C ABI (see
 * {@code native/xess_shim/xess_shim.cpp}): the XeSS init/execute parameter structs live in the
 * shim, so Java only passes primitives and raw Vulkan handles (as {@code long} addresses) — same
 * trade-off as {@code FsrLibrary} / {@code NgxLibrary}. The runtime ({@code libxess.dll}) is
 * LoadLibrary'd by the shim at {@code xessshim_init} time, not linked.
 */
public final class XessLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle init;
    private final MethodHandle createContext;
    private final MethodHandle initUpscaler;
    private final MethodHandle queryInputResolution;
    private final MethodHandle execute;
    private final MethodHandle destroyUpscaler;
    private final MethodHandle shutdown;
    private final MethodHandle lastResult;

    private XessLibrary(SymbolLookup lookup) {
        // int xessshim_init(u64 vkDevice, u64 vkDeviceProcAddr, wchar* runtimePath)
        this.init = handle(lookup, "xessshim_init",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        // int xessshim_create_context(u64 vkInstance, u64 vkPhysicalDevice)
        this.createContext = handle(lookup, "xessshim_create_context",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        // int xessshim_init_upscaler(u32 outW, u32 outH, int quality, u32 initFlags)
        this.initUpscaler = handle(lookup, "xessshim_init_upscaler",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // int xessshim_query_input_resolution(u32 outW, u32 outH, int quality, u32* inW, u32* inH)
        this.queryInputResolution = handle(lookup, "xessshim_query_input_resolution",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        // int xessshim_execute(cmd, color[image,view,fmt,w,h], velocity[image,view,fmt,w,h],
        //                      depth[image,view,fmt], output[image,view,fmt,w,h], jx, jy, reset, hasDepth)
        this.execute = handle(lookup, "xessshim_execute",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // void xessshim_destroy_upscaler(void)
        this.destroyUpscaler = handle(lookup, "xessshim_destroy_upscaler",
                FunctionDescriptor.ofVoid());
        // void xessshim_shutdown(void)
        this.shutdown = handle(lookup, "xessshim_shutdown",
                FunctionDescriptor.ofVoid());
        this.lastResult = handle(lookup, "xessshim_last_result",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
    }

    public static XessLibrary load(Path dll) {
        return new XessLibrary(SymbolLookup.libraryLookup(dll, Arena.global()));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("xessshim missing export " + name)),
                desc);
    }

    public int init(long vkDevice, long vkDeviceProcAddr, MemorySegment runtimePath) {
        try {
            return (int) this.init.invokeExact(vkDevice, vkDeviceProcAddr, runtimePath);
        } catch (Throwable t) {
            throw new RuntimeException("xessshim_init failed", t);
        }
    }

    /** Idempotent in the shim: a second call with the context alive returns 0 without recreating. */
    public int createContext(long vkInstance, long vkPhysicalDevice) {
        try {
            return (int) this.createContext.invokeExact(vkInstance, vkPhysicalDevice);
        } catch (Throwable t) {
            throw new RuntimeException("xessshim_create_context failed", t);
        }
    }

    /** {@code quality} is a raw {@code xess_quality_settings_t} value (100..106). */
    public int initUpscaler(int outputWidth, int outputHeight, int quality, int initFlags) {
        try {
            return (int) this.initUpscaler.invokeExact(outputWidth, outputHeight, quality, initFlags);
        } catch (Throwable t) {
            throw new RuntimeException("xessshim_init_upscaler failed", t);
        }
    }

    public int queryInputResolution(int outputWidth, int outputHeight, int quality,
                                    MemorySegment outInputWidth, MemorySegment outInputHeight) {
        try {
            return (int) this.queryInputResolution.invokeExact(
                    outputWidth, outputHeight, quality, outInputWidth, outInputHeight);
        } catch (Throwable t) {
            throw new RuntimeException("xessshim_query_input_resolution failed", t);
        }
    }

    public int execute(long cmd,
                       long colorImage, long colorView, int colorFormat, int colorWidth, int colorHeight,
                       long velocityImage, long velocityView, int velocityFormat,
                       int velocityWidth, int velocityHeight,
                       long depthImage, long depthView, int depthFormat,
                       long outputImage, long outputView, int outputFormat,
                       int outputWidth, int outputHeight,
                       float jitterX, float jitterY, int resetHistory, int hasDepth) {
        try {
            return (int) this.execute.invokeExact(cmd,
                    colorImage, colorView, colorFormat, colorWidth, colorHeight,
                    velocityImage, velocityView, velocityFormat, velocityWidth, velocityHeight,
                    depthImage, depthView, depthFormat,
                    outputImage, outputView, outputFormat, outputWidth, outputHeight,
                    jitterX, jitterY, resetHistory, hasDepth);
        } catch (Throwable t) {
            throw new RuntimeException("xessshim_execute failed", t);
        }
    }

    public void destroyUpscaler() {
        try {
            this.destroyUpscaler.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("xessshim_destroy_upscaler failed", t);
        }
    }

    public void shutdown() {
        try {
            this.shutdown.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("xessshim_shutdown failed", t);
        }
    }

    public int lastResult() {
        try {
            return (int) this.lastResult.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("xessshim_last_result failed", t);
        }
    }
}
