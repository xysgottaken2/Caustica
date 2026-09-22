package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtUpscalerSupport;

/**
 * Caustica's OWN frame generation engine (default on the FSR 3 / XeSS paths). The vendor engines
 * have hard limits this one does not: the FSR 3.1 runtime only ever writes one interpolated frame
 * per dispatch (2x), and Intel's XeSS-FG is D3D12-only. This engine interpolates with the path
 * tracer's own motion vectors — exact, jitter-free, entities included — so it can generate as many
 * in-between frames as wanted (see {@link #MAX_GENERATED_FRAMES}); the actual work is a compute
 * pass in {@link RtNativeFrameGenPipeline} recorded by {@code RtComposite.fgInterpolateNative}.
 *
 * <p>No external runtime, no hardware gate, no DLL — works wherever the renderer runs.
 */
public final class RtNativeFrameGen {
    public static final RtNativeFrameGen INSTANCE = new RtNativeFrameGen();

    /**
     * Practical cap for now: 3 generated frames (= 4x multiplier). Each generated frame is one
     * display-res interpolation dispatch, so higher counts trade GPU time and latency for smoothness;
     * 4x leaves headroom on low-end GPUs while the confidence fallback keeps the image clean.
     */
    public static final int MAX_GENERATED_FRAMES = 3;

    private RtNativeFrameGen() {
    }

    /**
     * FG is opted into through the shared {@code caustica.rt.fg} toggle. Rides the FSR 3 / XeSS
     * upscaler paths (replacing the FSR runtime unless {@code frame-generation.native-engine} is
     * switched off for comparison). On the DLSS path it acts as the FALLBACK engine: GPUs the
     * driver reports as DLSS-G-capable (RTX 40/50 series) keep the vendor DLSS Frame Generation,
     * everyone else gets this engine instead of nothing — same contract either way, so the toggle
     * and multiplier work on any card.
     */
    public static boolean enabled() {
        if (!CausticaConfig.Rt.Fg.ENABLED.value() || !CausticaConfig.Rt.Fg.NATIVE_ENGINE.value()) {
            return false;
        }
        String mode = RtUpscalerSupport.currentUpscalerMode();
        if (RtUpscalerSupport.MODE_FSR3.equals(mode) || RtUpscalerSupport.MODE_XESS.equals(mode)) {
            return true;
        }
        if (RtUpscalerSupport.MODE_DLSS.equals(mode)) {
            // DLSS-G hardware (RTX 40/50) keeps the vendor engine; all other GPUs substitute this one.
            return !RtUpscalerSupport.dlssFrameGenerationSupported();
        }
        return false;
    }

    /** Generated-frame count the player asked for, clamped to this engine's cap. */
    public int effectiveGeneratedCount() {
        return Math.clamp(CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value(), 1, MAX_GENERATED_FRAMES);
    }
}
