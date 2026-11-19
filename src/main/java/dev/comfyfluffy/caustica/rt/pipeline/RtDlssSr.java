package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.ngx.NgxLibrary;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * DLSS Super Resolution (DLSS-SR) upscaler backend: the reconstruction slot for every denoiser that
 * is NOT DLSS Ray Reconstruction.
 *
 * <p>Ray Reconstruction denoises <em>and</em> upscales in one pass, so the renderer's own denoisers
 * (SVGF, A-SVGF, NRD) had no upscaler behind them at all: {@code RtComposite.ensureOutput} only asks
 * RR/FSR/XeSS for a render size, and with none of them running it traces at 1:1 with the display and
 * blits the result across. Picking a non-RR filter therefore cost the whole reconstruction budget —
 * DLSS Quality is 44% of the pixels — which reads as "the denoiser made my FPS collapse". This
 * backend gives that slot back to the same NGX feature RR already uses, minus the denoising: the
 * denoised render-res image is upscaled to display res with depth + motion vectors.
 *
 * <p>Slot priority in the composite is RR &gt; DLSS-SR &gt; FSR 3 &gt; XeSS, and DLSS-SR can never
 * run beside RR ({@link #enabled()} excludes the {@code dlss_rr} denoiser mode), so the two NGX
 * features stay mutually exclusive exactly like the other upscalers.
 *
 * <p>Parameter conventions match {@code RtDlssRr}: jitter is negated (NGX wants the offset that was
 * applied, ours is image-space y-down), motion vectors are render-pixel units at scale 1 and
 * unjittered, reversed-Z depth behind {@code DepthInverted}, linear HDR rgba16f color behind
 * {@code IsHDR}. No Ray Reconstruction model preset is passed: {@code Rt.DlssRr.PRESET} is the RR
 * hint enum (0/D/E/F), not Super Resolution's (A..K), so forwarding it would silently select a
 * different model than the UI promises — 0 lets the DLL pick its own per-quality default.
 */
public final class RtDlssSr {
    public static final RtDlssSr INSTANCE = new RtDlssSr();

    /**
     * Whether DLSS-SR should take this frame's reconstruction slot.
     *
     * <p>The DLSS switch ({@code dlss-rr.enabled}, which is what the upscaler selector's "DLSS" value
     * drives) says the player wants NGX reconstruction. {@code RtDlssRr.enabled()} then narrows that
     * to the {@code dlss_rr} denoiser mode; every other mode leaves the slot empty, which is exactly
     * when this backend runs. Keying it off the same two switches means flipping the denoiser never
     * leaves the trace sized for one reconstructor while the other is running.
     *
     * <p>The legacy {@code composite.denoiser} toggle is deliberately NOT part of this check: the
     * denoiser-mode selector already forces it on, and with a non-RR mode selected the renderer's own
     * filter keeps running regardless of it — gating here would silently drop back to a 1:1 trace for
     * a hand-edited config that turned the old toggle off. In {@code dlss_rr} mode with that toggle
     * off the trace is the documented raw reference view, and it stays at 1:1.
     */
    public static boolean enabled() {
        return CausticaConfig.Rt.DlssRr.ENABLED.value()
                && !CausticaConfig.Rt.Composite.isDenoiserMode(
                        CausticaConfig.Rt.Composite.DENOISER_MODE_DLSS_RR);
    }

    // NVSDK_NGX_DLSS_Feature_Flags, the same bits the RR path sets: linear HDR color, motion vectors
    // at render resolution (not display), HW reversed-Z depth, and auto-exposure estimation (in HDR
    // mode DLSS needs an exposure; this renderer meters its own average but hands NGX no exposure
    // texture, so let the model estimate it from the color, as RR does).
    private static final int FEATURE_FLAG_IS_HDR = 1 << 0;
    private static final int FEATURE_FLAG_MV_LOW_RES = 1 << 1;
    private static final int FEATURE_FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int FEATURE_FLAG_AUTO_EXPOSURE = 1 << 6;
    private static final int FEATURE_FLAGS = FEATURE_FLAG_IS_HDR | FEATURE_FLAG_MV_LOW_RES
            | FEATURE_FLAG_DEPTH_INVERTED | FEATURE_FLAG_AUTO_EXPOSURE;

    /**
     * The NVSDK_NGX_PerfQuality_Value to reconstruct with. Shared with RR on purpose: the Video
     * Settings selector shows one quality slider for the DLSS backend, and NGX numbers both features
     * with the same enum (0 Performance, 1 Balanced, 2 Quality, 3 Ultra Performance, 4 Ultra Quality,
     * 5 DLAA). Values outside the enum snap to Quality so a stale config cannot hand NGX a mode that
     * makes its optimal-settings query return a zeroed render size.
     */
    public static int quality() {
        int quality = CausticaConfig.Rt.DlssRr.QUALITY.value();
        return quality >= 0 && quality <= 5 ? quality : 2;
    }

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean loggedAvailable;
    private boolean resetHistory;
    private long lastFrameNanos;

    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;

    private RtDlssSr() {
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /**
     * Discard DLSS's internal temporal history on the next evaluation. Called by the composite on
     * camera discontinuities (teleport / respawn / world change): the reprojection history is
     * meaningless across a jump and smears until it decays on its own otherwise — the same rule the
     * FSR and XeSS backends follow.
     */
    public void requestReset() {
        resetHistory = true;
    }

    /**
     * The render resolution DLSS-SR's quality mode expects for a display size. Returns {@code null}
     * when the backend is off, has already failed, or NGX reports no DLSS support — the caller then
     * traces at full resolution, the same contract as
     * {@code RtDlssRr.queryOptimalRenderSize} / {@code RtFsrUpscaler.queryRenderSize} /
     * {@code RtXessUpscaler.queryRenderSize}.
     */
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return null;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        try {
            ensureInitialized(device);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment outWidth = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment outHeight = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment outSharpness = arena.allocate(ValueLayout.JAVA_FLOAT);
                int rc = lib.queryOptimal(displayWidth, displayHeight, quality(), outWidth, outHeight, outSharpness);
                if (NgxRuntime.ngxFailed(rc)) {
                    throw new IllegalStateException("ngxshim_query_optimal failed: 0x" + Integer.toHexString(rc));
                }
                int renderWidth = outWidth.get(ValueLayout.JAVA_INT, 0);
                int renderHeight = outHeight.get(ValueLayout.JAVA_INT, 0);
                if (renderWidth <= 0 || renderHeight <= 0) {
                    throw new IllegalStateException(
                            "ngxshim_query_optimal returned invalid render size " + renderWidth + "x" + renderHeight);
                }
                return new int[] { renderWidth, renderHeight };
            }
        } catch (Throwable t) {
            // Latch: a machine without DLSS support reaches this on the first frame, and re-querying
            // every frame would only repeat the same failure at log-spam rate.
            failed = true;
            CausticaMod.LOGGER.warn("DLSS Super Resolution unavailable; tracing at full resolution", t);
            return null;
        }
    }

    /**
     * Ensure an NGX Super Resolution feature exists for the given resolutions and quality. DLSS
     * captures its quality mode at creation time, so a quality change (or a resize) destroys and
     * recreates the feature — that also drops its history, which is correct for a new input size.
     */
    public boolean ensureFeature(long cmd, int renderWidth, int renderHeight, int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        try {
            ensureInitialized(device);
            int quality = quality();
            if (featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureDisplayWidth != displayWidth || featureDisplayHeight != displayHeight
                    || featureQuality != quality || isNull(feature)) {
                releaseFeature(device);
                feature = lib.createDlss(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
                        quality, FEATURE_FLAGS, 0);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlss failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureDisplayWidth = displayWidth;
                featureDisplayHeight = displayHeight;
                featureQuality = quality;
                resetHistory = true; // a fresh feature has no temporal history
                CausticaMod.LOGGER.info("DLSS-SR feature created: {}x{} -> {}x{} (quality {})",
                        renderWidth, renderHeight, displayWidth, displayHeight, quality);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("DLSS-SR setup failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Record one DLSS-SR evaluation into {@code cmd}: render-res color + reversed-Z depth + render-res
     * motion vectors -> display-res {@code out}. {@code jitterX/jitterY} is the sub-pixel offset that
     * was applied to this frame's trace in render pixels, already negated by the caller (NGX
     * convention, see {@code RtDlssRr.evaluate}); pass zero when a temporal denoiser upstream already
     * integrated the jitter sequence into the image. Returns false (disabling the backend) on failure.
     */
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            // Clamp like the RR path: a first frame or a hitch must not poison DLSS's temporal heuristics.
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc = lib.evaluate(cmd, feature,
                    color.view, color.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    depth.view, depth.image, VK10.VK_FORMAT_R32_SFLOAT,
                    motion.view, motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    out.view, out.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    jitterX, jitterY, 1.0f, 1.0f, resetHistory ? 1 : 0, frameMs);
            resetHistory = false;
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("DLSS-SR evaluate failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Release the feature if DLSS-SR has been switched off since it was created (the DLSS switch
     * going off for FSR 3 / XeSS / Off, or RR taking the slot back when the {@code dlss_rr} denoiser
     * is selected). Called from {@code RtComposite.ensureOutput} with the device idle, mirroring the
     * RR/FSR/XeSS release paths.
     */
    public boolean releaseIfDisabled() {
        if (enabled() || isNull(feature)) {
            return false;
        }
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseFeature(device);
            CausticaMod.LOGGER.info("DLSS-SR feature released: DLSS Super Resolution no longer selected");
            return true;
        }
        return false;
    }

    /**
     * Release the feature. Does NOT shut NGX down — that is the shared {@link NgxRuntime}'s job at
     * device teardown, so RR and Frame Generation keep their handles.
     */
    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseFeature(device);
        }
        initialized = false;
        lib = null;
    }

    private void ensureInitialized(VulkanDevice device) {
        if (initialized) {
            return;
        }
        lib = NgxRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            throw new IllegalStateException("NGX runtime unavailable; DLSS Super Resolution cannot initialize");
        }
        boolean available = lib.dlssAvailable();
        if (!loggedAvailable) {
            loggedAvailable = true;
            CausticaMod.LOGGER.info("DLSS Super Resolution available: {}", available);
        }
        if (!available) {
            throw new IllegalStateException("DLSS Super Resolution is not available on this system");
        }
        initialized = true;
    }

    private void releaseFeature(VulkanDevice device) {
        if (!isNull(feature)) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null && ctx.device() == device) {
                ctx.waitIdle();
            } else {
                VK10.vkDeviceWaitIdle(device.vkDevice());
            }
            lib.release(feature);
            feature = MemorySegment.NULL;
        }
        featureRenderWidth = -1;
        featureRenderHeight = -1;
        featureDisplayWidth = -1;
        featureDisplayHeight = -1;
        featureQuality = Integer.MIN_VALUE;
        lastFrameNanos = 0;
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }
}
