package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.xess.XessLibrary;
import dev.comfyfluffy.caustica.xess.XessRuntime;

import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Intel XeSS Super Resolution upscaler backend for the RT renderer (EXPERIMENTAL). The ML-based
 * alternative to FSR 3: XeSS runs a trained neural network (quantized INT8 weights — DP4a on
 * GeForce RTX / Radeon, XMX matrix engines on Intel Arc) over the render-res trace + motion
 * vectors + reversed-Z depth and reconstructs the display-res image. Occupies the same upscale
 * slot as DLSS-RR / FSR 3 (mutually exclusive; if a hand-edit enables several, RR &gt; FSR &gt; XeSS).
 *
 * <p>Input conventions follow Intel's basic_sample_super_resolution_vk exactly: motion vectors in
 * render-pixel units (default mode, no HIGH_RES_MV), jitter passed as the image-space offset
 * actually applied to the trace (Halton(2,3), 32 phases — the sample's sequence), reversed-Z depth
 * behind XESS_INIT_FLAG_INVERTED_DEPTH, HDR linear rgba16f color (no LDR flag).
 */
public final class RtXessUpscaler {
    public static final RtXessUpscaler INSTANCE = new RtXessUpscaler();

    public static boolean enabled() {
        return CausticaConfig.Rt.Xess.ENABLED.value();
    }

    // xess_init_flags_t: reversed-Z depth (same buffer FSR gets with DEPTH_INVERTED). MVs are
    // render-res pixel-space, HDR linear color — the defaults, so no other flags.
    private static final int FLAG_INVERTED_DEPTH = 1 << 1;
    private static final int INIT_FLAGS = FLAG_INVERTED_DEPTH;

    /**
     * Map the stored DLSS-numbered quality value (0 Performance, 1 Balanced, 2 Quality, 3 Ultra
     * Performance, 5 native AA) onto xess_quality_settings_t (100 Ultra Performance .. 106 AA) —
     * the two scales describe the same ratios with different numbers, and the config surface stays
     * one shared vocabulary.
     */
    public static int quality() {
        return switch (CausticaConfig.Rt.Xess.QUALITY.value()) {
            case 3 -> 100; // Ultra Performance
            case 0 -> 101; // Performance
            case 1 -> 102; // Balanced
            case 2 -> 103; // Quality
            case 5 -> 106; // AA (native 1:1, the DLAA analogue)
            default -> 103;
        };
    }

    private XessLibrary lib;
    private boolean initialized;
    private boolean failed;
    private boolean upscalerActive;
    private boolean resetAccumulation;

    private int contextDisplayWidth = -1;
    private int contextDisplayHeight = -1;
    private int contextQuality = Integer.MIN_VALUE;

    private RtXessUpscaler() {
    }

    /**
     * Discard XeSS's internal temporal history on the next dispatch. Called by the composite on
     * camera discontinuities (teleport / respawn / world change) — the reprojection history is
     * meaningless across a jump and smears until it decays on its own otherwise.
     */
    public void requestReset() {
        resetAccumulation = true;
    }

    public boolean isReady() {
        return initialized && !failed && upscalerActive;
    }

    /**
     * The render resolution XeSS's quality mode wants for a display size. Returns {@code null}
     * when XeSS is off/failed/unavailable — the caller then traces at full resolution, same
     * contract as {@code RtDlssRr.queryOptimalRenderSize} / {@code RtFsrUpscaler.queryRenderSize}.
     */
    public int[] queryRenderSize(int displayWidth, int displayHeight) {
        if (!enabled() || failed || !XessRuntime.platformSupported()) {
            return null;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
        try {
            ensureInitialized(device);
            // xessGetInputResolution needs a live context, and this query runs BEFORE ensureFeature
            // (the render size it returns is what the trace targets are built with) — so the context
            // comes up here, not there.
            XessRuntime.INSTANCE.ensureContext(device);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment outWidth = arena.allocate(ValueLayout.JAVA_INT);
                MemorySegment outHeight = arena.allocate(ValueLayout.JAVA_INT);
                int rc = lib.queryInputResolution(displayWidth, displayHeight, quality(), outWidth, outHeight);
                if (rc != 0) {
                    throw new IllegalStateException("xessshim_query_input_resolution failed: " + rc
                            + " last=" + lib.lastResult());
                }
                int renderWidth = outWidth.get(ValueLayout.JAVA_INT, 0);
                int renderHeight = outHeight.get(ValueLayout.JAVA_INT, 0);
                if (renderWidth <= 0 || renderHeight <= 0) {
                    throw new IllegalStateException(
                            "xessshim_query_input_resolution returned invalid size " + renderWidth + "x" + renderHeight);
                }
                return new int[] { renderWidth, renderHeight };
            }
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("XeSS render-size query failed; XeSS upscaling disabled", t);
            return null;
        }
    }

    /**
     * Ensure XeSS is initialized for the given display size + current quality (xessVKInit rebuilds
     * its pipelines, so a display resize or quality change destroys + re-inits; a plain frame does
     * not). Initialization records no GPU commands — it is pipeline compilation on the device.
     */
    public boolean ensureFeature(int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        try {
            ensureInitialized(device);
            int quality = quality();
            if (!upscalerActive || contextDisplayWidth != displayWidth
                    || contextDisplayHeight != displayHeight || contextQuality != quality) {
                if (upscalerActive) {
                    waitIdle(device);
                    lib.destroyUpscaler();
                    upscalerActive = false;
                }
                // xessVKInit wants a live context; the shim's create is idempotent, so this also
                // recreates the context the destroy above dropped.
                XessRuntime.INSTANCE.ensureContext(device);
                int rc = lib.initUpscaler(displayWidth, displayHeight, quality, INIT_FLAGS);
                if (rc != 0) {
                    throw new IllegalStateException("xessshim_init_upscaler failed: " + rc
                            + " last=" + lib.lastResult());
                }
                upscalerActive = true;
                contextDisplayWidth = displayWidth;
                contextDisplayHeight = displayHeight;
                contextQuality = quality;
                resetAccumulation = true; // fresh upscaler has no temporal history
                CausticaMod.LOGGER.info("XeSS upscaler initialized for {}x{} (quality {})",
                        displayWidth, displayHeight, quality);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("XeSS setup failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Record one XeSS upscale dispatch into {@code cmd}: jittered render-res color + reversed-Z
     * depth + render-res motion vectors -&gt; display-res {@code out}. {@code jitterX/jitterY} are
     * the sub-pixel offsets actually applied to this frame's trace in render-pixel units — XeSS
     * wants the applied offset as-is (Intel's sample negates only because its jitter is expressed
     * in NDC y-up, ours is already image-space y-down). Returns false (disabling XeSS) on failure.
     */
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY) {
        if (!isReady()) {
            return false;
        }
        try {
            int rc = lib.execute(cmd,
                    color.image, color.view, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, renderWidth, renderHeight,
                    motion.image, motion.view, VK10.VK_FORMAT_R16G16_SFLOAT, renderWidth, renderHeight,
                    depth.image, depth.view, VK10.VK_FORMAT_R32_SFLOAT,
                    out.image, out.view, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, displayWidth, displayHeight,
                    jitterX, jitterY, resetAccumulation ? 1 : 0, 1);
            resetAccumulation = false;
            if (rc != 0) {
                throw new IllegalStateException("xessshim_execute failed: " + rc
                        + " last=" + lib.lastResult());
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("XeSS dispatch failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Release the upscaler if XeSS has been switched off since it was created (its internal history
     * textures and pipelines are not trivial). Called from {@code RtComposite.ensureOutput} with the
     * device idle, mirroring the FSR/RR release path.
     */
    public boolean releaseIfDisabled() {
        if (enabled() || !upscalerActive) {
            return false;
        }
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            waitIdle(device);
            lib.destroyUpscaler();
            upscalerActive = false;
            contextDisplayWidth = -1;
            contextDisplayHeight = -1;
            contextQuality = Integer.MIN_VALUE;
            CausticaMod.LOGGER.info("XeSS upscaler released: XeSS turned off");
            return true;
        }
        return false;
    }

    public void destroy() {
        if (upscalerActive
                && ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            waitIdle(device);
            lib.destroyUpscaler();
            upscalerActive = false;
        }
        contextDisplayWidth = -1;
        contextDisplayHeight = -1;
        contextQuality = Integer.MIN_VALUE;
        initialized = false;
        lib = null;
    }

    private void ensureInitialized(VulkanDevice device) {
        if (initialized) {
            return;
        }
        lib = XessRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            throw new IllegalStateException("XeSS runtime unavailable; XeSS upscaling cannot initialize");
        }
        initialized = true;
    }

    private static void waitIdle(VulkanDevice device) {
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null && ctx.device() == device) {
            ctx.waitIdle();
        } else {
            VK10.vkDeviceWaitIdle(device.vkDevice());
        }
    }
}
