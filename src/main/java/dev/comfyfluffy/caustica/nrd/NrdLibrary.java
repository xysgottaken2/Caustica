package dev.comfyfluffy.caustica.nrd;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * FFM bindings for the NRD shim ({@code nrdshim.dll}).
 *
 * <p>The shim wraps NVIDIA NRD's REBLUR_DIFFUSE_SPECULAR denoiser (through NRD's own NRI Integration
 * layer) behind a flat C ABI — see {@code native/nrd_shim/nrd_shim.cpp}, which also documents the
 * resource and matrix contract in full. Every NRD/NRI struct lives inside the shim, so Java only
 * ever passes primitives and raw Vulkan handles as {@code long} addresses, the same trade-off as
 * {@code NgxLibrary} and {@code FsrLibrary}.
 *
 * <p>Return codes: {@code 0} is success, positive values are {@code nrd::Result}, and negative
 * values are the shim's own validation failures (null handle, out-of-range jitter, bad resolution).
 */
public final class NrdLibrary {
    private static final Linker LINKER = Linker.nativeLinker();

    private final MethodHandle init;
    private final MethodHandle newFrame;
    private final MethodHandle setSettings;
    private final MethodHandle setReblurSettings;
    private final MethodHandle denoise;
    private final MethodHandle destroy;
    private final MethodHandle lastResult;
    private final MethodHandle version;

    private NrdLibrary(SymbolLookup lookup) {
        // int nrdshim_init(u64 instance, u64 phys, u64 device, u64 gipa, u32 queueFamily,
        //                  u32 vkMinor, u32 w, u32 h)
        this.init = handle(lookup, "nrdshim_init",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        this.newFrame = handle(lookup, "nrdshim_new_frame", FunctionDescriptor.ofVoid());
        // int nrdshim_set_settings(f32[16] v2c, f32[16] v2cPrev, f32[16] w2v, f32[16] w2vPrev,
        //                          jx, jy, jpx, jpy, mvScaleX, mvScaleY, denoisingRange,
        //                          disocclusionThreshold, u32 frameIndex, i32 reset, i32 validation)
        this.setSettings = handle(lookup, "nrdshim_set_settings",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        // int nrdshim_set_reblur_settings(5 ints, 11 floats, 1 int) — see setReblurSettings below.
        this.setReblurSettings = handle(lookup, "nrdshim_set_reblur_settings",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
                        ValueLayout.JAVA_INT));
        // int nrdshim_denoise(u64 cmd, [image, fmt] x 8: mv, normalRough, viewZ, diffIn, specIn,
        //                     diffOut, specOut, validation)
        this.denoise = handle(lookup, "nrdshim_denoise",
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        this.destroy = handle(lookup, "nrdshim_destroy", FunctionDescriptor.ofVoid());
        this.lastResult = handle(lookup, "nrdshim_last_result",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        this.version = handle(lookup, "nrdshim_version",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
    }

    public static NrdLibrary load(Path dll) {
        return new NrdLibrary(SymbolLookup.libraryLookup(dll, Arena.global()));
    }

    private static MethodHandle handle(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(
                lookup.find(name).orElseThrow(() -> new IllegalStateException("nrdshim missing export " + name)),
                desc);
    }

    public int init(long vkInstance, long vkPhysicalDevice, long vkDevice, long vkGetInstanceProcAddr,
                    int queueFamilyIndex, int vkMinorVersion, int width, int height) {
        try {
            return (int) this.init.invokeExact(vkInstance, vkPhysicalDevice, vkDevice, vkGetInstanceProcAddr,
                    queueFamilyIndex, vkMinorVersion, width, height);
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_init failed", t);
        }
    }

    public void newFrame() {
        try {
            this.newFrame.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_new_frame failed", t);
        }
    }

    /**
     * Per-frame common settings. All matrices are column-major {@code float[16]} and NON-jittered,
     * passed exactly as the renderer built them (NRD detects handedness itself — pre-flipping is
     * what broke the previous integration). Jitter is in pixels and must lie in [-0.5, 0.5], with
     * {@code jitterPrev} being the jitter the HISTORY was rendered with.
     */
    public int setSettings(MemorySegment viewToClip, MemorySegment viewToClipPrev,
                           MemorySegment worldToView, MemorySegment worldToViewPrev,
                           float jitterX, float jitterY, float jitterPrevX, float jitterPrevY,
                           float mvScaleX, float mvScaleY,
                           float denoisingRange, float disocclusionThreshold,
                           int frameIndex, int reset, int enableValidation) {
        try {
            return (int) this.setSettings.invokeExact(viewToClip, viewToClipPrev, worldToView, worldToViewPrev,
                    jitterX, jitterY, jitterPrevX, jitterPrevY,
                    mvScaleX, mvScaleY, denoisingRange, disocclusionThreshold,
                    frameIndex, reset, enableValidation);
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_set_settings failed", t);
        }
    }

    /**
     * REBLUR tuning; the shim remembers it across resize re-inits.
     *
     * <p>{@code hitDistReconstructionMode}: 0 OFF, 1 AREA_3X3, 2 AREA_5X5. The frame counts and the
     * blur radii use a negative value for "keep NRD's default" (0 is meaningful for them: no
     * stabilization / disabled prepass); the remaining floats treat any value &lt;= 0 as "default".
     */
    public int setReblurSettings(int hitDistReconstructionMode, int maxAccumulatedFrameNum,
                                 int maxFastAccumulatedFrameNum, int maxStabilizedFrameNum,
                                 int historyFixFrameNum,
                                 float antilagLuminanceSigmaScale, float antilagLuminanceSensitivity,
                                 float fireflySuppressorMinRelativeScale,
                                 float diffusePrepassBlurRadius, float specularPrepassBlurRadius,
                                 float minBlurRadius, float maxBlurRadius,
                                 float lobeAngleFraction, float roughnessFraction,
                                 float planeDistanceSensitivity, float fastHistoryClampingSigmaScale,
                                 boolean enableAntiFirefly) {
        try {
            return (int) this.setReblurSettings.invokeExact(
                    hitDistReconstructionMode, maxAccumulatedFrameNum, maxFastAccumulatedFrameNum,
                    maxStabilizedFrameNum, historyFixFrameNum,
                    antilagLuminanceSigmaScale, antilagLuminanceSensitivity,
                    fireflySuppressorMinRelativeScale,
                    diffusePrepassBlurRadius, specularPrepassBlurRadius,
                    minBlurRadius, maxBlurRadius,
                    lobeAngleFraction, roughnessFraction,
                    planeDistanceSensitivity, fastHistoryClampingSigmaScale,
                    enableAntiFirefly ? 1 : 0);
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_set_reblur_settings failed", t);
        }
    }

    public int denoise(long cmd,
                       long mvImage, int mvFormat,
                       long normalRoughnessImage, int normalRoughnessFormat,
                       long viewZImage, int viewZFormat,
                       long diffInImage, int diffInFormat,
                       long specInImage, int specInFormat,
                       long diffOutImage, int diffOutFormat,
                       long specOutImage, int specOutFormat,
                       long validationImage, int validationFormat) {
        try {
            return (int) this.denoise.invokeExact(cmd,
                    mvImage, mvFormat, normalRoughnessImage, normalRoughnessFormat,
                    viewZImage, viewZFormat, diffInImage, diffInFormat,
                    specInImage, specInFormat, diffOutImage, diffOutFormat,
                    specOutImage, specOutFormat, validationImage, validationFormat);
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_denoise failed", t);
        }
    }

    public void destroy() {
        try {
            this.destroy.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_destroy failed", t);
        }
    }

    public int lastResult() {
        try {
            return (int) this.lastResult.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_last_result failed", t);
        }
    }

    /** NRD library version packed as {@code major << 16 | minor << 8 | build}. */
    public int version() {
        try {
            return (int) this.version.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("nrdshim_version failed", t);
        }
    }

    /** Human-readable form of {@link #version()}, for the startup log. */
    public String versionString() {
        int packed = version();
        return ((packed >> 16) & 0xFF) + "." + ((packed >> 8) & 0xFF) + "." + (packed & 0xFF);
    }
}
