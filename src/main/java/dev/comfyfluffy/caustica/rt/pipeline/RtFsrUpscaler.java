package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.fsr.FsrLibrary;
import dev.comfyfluffy.caustica.fsr.FsrRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;

import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * AMD FSR 3.1 upscaler backend for the RT renderer (EXPERIMENTAL). Upscales the path-traced color
 * (render res) to display res using hardware depth + motion vectors — the FidelityFX equivalent of
 * the DLSS-RR upscale slot, but WITHOUT denoising: FSR 3 is a pure upscaler, so until the NRD
 * denoiser lands the input is the raw trace (raise SPP to compensate). The UI labels this
 * experimental accordingly.
 *
 * <p>Parameter conventions follow AMD's fsrapi sample exactly: jitter offsets negated, motion
 * vectors in render-pixel units with motionVectorScale = render dimensions, reversed-Z depth mapped
 * to DEPTH_INVERTED + DEPTH_INFINITE with cameraNear=FLT_MAX / cameraFar=the real near plane.
 */
public final class RtFsrUpscaler {
    public static final RtFsrUpscaler INSTANCE = new RtFsrUpscaler();

    public static boolean enabled() {
        return CausticaConfig.Rt.Fsr.ENABLED.value();
    }

    // FfxApiCreateContextUpscaleFlags for this renderer: rgba16f linear-HDR color, reversed-Z
    // (infinite far) depth, exposure estimated from the color itself. MVs are render-res and
    // unjittered, so no DISPLAY_RESOLUTION / JITTER_CANCELLATION flags.
    private static final int FLAG_HIGH_DYNAMIC_RANGE = 1 << 0;
    private static final int FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int FLAG_DEPTH_INFINITE = 1 << 4;
    private static final int FLAG_AUTO_EXPOSURE = 1 << 5;
    private static final int CONTEXT_FLAGS = FLAG_HIGH_DYNAMIC_RANGE | FLAG_DEPTH_INVERTED
            | FLAG_DEPTH_INFINITE | FLAG_AUTO_EXPOSURE;

    // Minecraft's fixed near plane (vanilla builds the level projection with it); FSR only uses it
    // for its depth-linearization heuristic, paired with the reversed-Z mapping below.
    private static final float CAMERA_NEAR = 0.05f;

    /**
     * Map the stored DLSS-numbered quality value (0 Performance, 1 Balanced, 2 Quality, 3 Ultra
     * Performance, 5 native) onto FfxApiUpscaleQualityMode (0 NativeAA, 1 Quality, 2 Balanced,
     * 3 Performance, 4 Ultra Performance) — the two scales describe the same ratios with different
     * numbers, and the config surface stays one shared vocabulary.
     */
    public static int quality() {
        return switch (CausticaConfig.Rt.Fsr.QUALITY.value()) {
            case 5 -> 0; // DLAA -> Native AA
            case 2 -> 1; // Quality
            case 1 -> 2; // Balanced
            case 0 -> 3; // Performance
            case 3 -> 4; // Ultra Performance
            default -> 1;
        };
    }

    private FsrLibrary lib;
    private MemorySegment context = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean resetAccumulation;
    private long lastFrameNanos;

    /**
     * Discard FSR's internal temporal history on the next dispatch. Called by the composite on
     * camera discontinuities (teleport / respawn / world change): the reprojection history is
     * meaningless across a jump and smears until it decays on its own otherwise — the NRD and TAA
     * stages already reset on the same events, FSR was the only temporal stage that didn't.
     */
    public void requestReset() {
        resetAccumulation = true;
    }

    private int contextDisplayWidth = -1;
    private int contextDisplayHeight = -1;

    private RtFsrUpscaler() {
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(context);
    }

    /**
     * The render resolution FSR's quality mode wants for a display size. Returns {@code null} when
     * FSR is off/failed/unavailable — the caller then traces at full resolution, same contract as
     * {@code RtDlssRr.queryOptimalRenderSize}.
     */
    public int[] queryRenderSize(int displayWidth, int displayHeight) {
        if (!enabled() || failed || !FsrRuntime.platformSupported()) {
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
                int rc = lib.queryRenderSize(quality(), displayWidth, displayHeight, outWidth, outHeight);
                if (rc != 0) {
                    throw new IllegalStateException("fsrshim_query_render_size failed: " + rc
                            + " last=" + lib.lastResult());
                }
                int renderWidth = outWidth.get(ValueLayout.JAVA_INT, 0);
                int renderHeight = outHeight.get(ValueLayout.JAVA_INT, 0);
                if (renderWidth <= 0 || renderHeight <= 0) {
                    throw new IllegalStateException(
                            "fsrshim_query_render_size returned invalid size " + renderWidth + "x" + renderHeight);
                }
                return new int[] { renderWidth, renderHeight };
            }
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("FSR render-size query failed; FSR upscaling disabled", t);
            return null;
        }
    }

    /**
     * Ensure an FSR context exists for the given display size (created with maxRenderSize =
     * display size per the AMD sample, so quality-mode changes never need a recreate — only the
     * dispatch parameters move). Context creation records nothing; it is plain device allocation.
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
            if (contextDisplayWidth != displayWidth || contextDisplayHeight != displayHeight || isNull(context)) {
                releaseContext(device);
                context = lib.createUpscaler(displayWidth, displayHeight, displayWidth, displayHeight,
                        CONTEXT_FLAGS);
                if (isNull(context)) {
                    throw new IllegalStateException("fsrshim_create_upscaler failed: last=" + lib.lastResult());
                }
                // NOTE: FSR 3.1's anti-ghosting tuning knobs (fShadingChangeScale,
                // fAccumulationAddedPerFrame) were tried here and reverted: amplifying the
                // shading-change signal made FSR reject history far too eagerly in bright scenes on
                // camera motion (the temporary black edges the user reported), and the defaults
                // strike the better stability/ghosting balance. The reactive mask (moving entities
                // only) is the remaining anti-ghosting lever — see guides.slang / fsr_shim.
                contextDisplayWidth = displayWidth;
                contextDisplayHeight = displayHeight;
                resetAccumulation = true; // fresh context has no temporal history
                CausticaMod.LOGGER.info("FSR 3 upscaler context created for {}x{}", displayWidth, displayHeight);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("FSR setup failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Record one FSR 3 upscale dispatch into {@code cmd}: jittered render-res color + reversed-Z
     * depth + render-res motion vectors -> display-res {@code out}. {@code jitterX/jitterY} are the
     * sub-pixel camera offsets applied this frame in render pixels (already negated by the caller,
     * matching how the RR path reports them), {@code fovY} the vertical field of view in radians.
     * Returns false (disabling FSR) on failure.
     */
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion, RtImage reactive, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY, float fovY) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            // Clamp like the RR path: a first frame or a hitch must not poison FSR's temporal heuristics.
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc = lib.dispatchUpscale(context, cmd,
                    color.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    depth.image, VK10.VK_FORMAT_R32_SFLOAT,
                    motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    reactive == null ? 0L : reactive.image, VK10.VK_FORMAT_R16_SFLOAT,
                    out.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    jitterX, jitterY, frameMs, resetAccumulation ? 1 : 0,
                    Float.MAX_VALUE, CAMERA_NEAR, fovY);
            resetAccumulation = false;
            if (rc != 0) {
                throw new IllegalStateException("fsrshim_dispatch_upscale failed: " + rc
                        + " last=" + lib.lastResult());
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("FSR dispatch failed; RT composite continues without it", t);
            return false;
        }
    }

    /**
     * Release the context if FSR has been switched off since it was created (its internal history
     * textures are not trivial). Called from {@code RtComposite.ensureOutput} with the device idle,
     * mirroring the RR release path.
     */
    public boolean releaseIfDisabled() {
        if (enabled() || isNull(context)) {
            return false;
        }
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseContext(device);
            CausticaMod.LOGGER.info("FSR upscaler context released: FSR turned off");
            return true;
        }
        return false;
    }

    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseContext(device);
        }
        initialized = false;
        lib = null;
    }

    private void ensureInitialized(VulkanDevice device) {
        if (initialized) {
            return;
        }
        lib = FsrRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            throw new IllegalStateException("FSR runtime unavailable; FSR upscaling cannot initialize");
        }
        initialized = true;
    }

    private void releaseContext(VulkanDevice device) {
        if (!isNull(context)) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null && ctx.device() == device) {
                ctx.waitIdle();
            } else {
                VK10.vkDeviceWaitIdle(device.vkDevice());
            }
            lib.destroyUpscaler(context);
            context = MemorySegment.NULL;
        }
        contextDisplayWidth = -1;
        contextDisplayHeight = -1;
        lastFrameNanos = 0;
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }
}
