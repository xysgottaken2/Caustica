package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.ngx.NgxLibrary;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * DLSS Super Resolution backend for the RT renderer: the upscale-only half of the DLSS path.
 * Runs when the ray-reconstruction selector picks a classical denoiser (SVGF or NRD) instead of
 * DLSS Ray Reconstruction — the denoiser runs at render resolution and this pass upscales its
 * converged output to display resolution, exactly like the FSR 3 / XeSS upscalers do on their
 * paths. DLSS-SR never denoises, so it needs only color + depth + motion vectors (no albedo /
 * normal / specular guides).
 *
 * <p>Shares the NGX lifetime with {@link RtDlssRr} and {@code RtDlssFg} through
 * {@link NgxRuntime}; each feature owns only its own create/evaluate/release.
 */
public final class RtDlssSr {
    public static final RtDlssSr INSTANCE = new RtDlssSr();

    /**
     * Whether the DLSS-SR upscale pass should run this frame. Mirrors {@link RtDlssRr#enabled()}:
     * the DLSS backend switch and the denoising-filter toggle must both be on, and the
     * ray-reconstruction selector must name a classical denoiser (SVGF or NRD) rather than DLSS
     * Ray Reconstruction itself. Keeping the check here means the render-size query, the
     * per-frame evaluate and the resize path all see one consistent answer.
     */
    public static boolean enabled() {
        if (!CausticaConfig.Rt.DlssRr.ENABLED.value() || !CausticaConfig.Rt.Composite.DENOISER.value()) {
            return false;
        }
        String rayReconstruction = CausticaConfig.Rt.DlssRr.rayReconstruction();
        return CausticaConfig.Rt.DlssRr.RAY_RECONSTRUCTION_SVGF.equals(rayReconstruction)
                || CausticaConfig.Rt.DlssRr.RAY_RECONSTRUCTION_NRD.equals(rayReconstruction);
    }

    // DLSS feature flags. IsHDR (bit 0): color is linear HDR (rgba16f). MVLowRes (bit 1): motion
    // vectors are at render/input resolution, not display. DepthInverted (bit 3): the depth guide
    // is HW reversed-Z (near=1, far=0). AutoExposure (bit 6): in HDR mode DLSS needs the scene
    // exposure; without it the output is black, so let DLSS estimate exposure from the color
    // itself. MVs are unjittered, so no MV_JITTERED (bit 2). Same values as the RR feature.
    private static final int FEATURE_FLAG_IS_HDR = 1 << 0;
    private static final int FEATURE_FLAG_MV_LOW_RES = 1 << 1;
    private static final int FEATURE_FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int FEATURE_FLAG_AUTO_EXPOSURE = 1 << 6;
    private static final int FEATURE_FLAGS = FEATURE_FLAG_IS_HDR | FEATURE_FLAG_MV_LOW_RES
            | FEATURE_FLAG_DEPTH_INVERTED | FEATURE_FLAG_AUTO_EXPOSURE;
    // DLSS-SR model preset: 0 = let the DLL pick its per-mode default. The RR preset slider's
    // values (0/4/5/6) are Ray Reconstruction's own enum and do not apply to Super Resolution,
    // whose transformer preset is K (11); the driver default already selects it, so SR always
    // creates with 0 rather than guessing a letter.
    private static final int RENDER_PRESET_DEFAULT = 0;

    public static int quality() {
        return CausticaConfig.Rt.DlssRr.QUALITY.value();
    }

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean loggedAvailable;

    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureDisplayWidth = -1;
    private int featureDisplayHeight = -1;
    private int featureQuality = Integer.MIN_VALUE;

    private boolean resetHistory;
    private long lastFrameNanos;

    private RtDlssSr() {
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /**
     * Record a DLSS-SR evaluation: upscale the (already denoised, converged) render-res color
     * using depth + motion vectors, writing the display-res result into {@code out}. Returns
     * false (disabling SR) on failure. MVs are already in render-pixel space (scale 1).
     */
    public boolean evaluate(long cmd, RtImage color, RtImage depth, RtImage motion, RtImage out,
                            int renderWidth, int renderHeight, int displayWidth, int displayHeight,
                            float jitterX, float jitterY) {
        if (!isReady()) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float frameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;

            int rc = lib.evaluate(cmd, feature,
                    color.view, color.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    depth.view, depth.image, VK10.VK_FORMAT_R32_SFLOAT,
                    motion.view, motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    out.view, out.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    renderWidth, renderHeight, displayWidth, displayHeight,
                    // jitter in render pixels; MVs are already in render-pixel units, so MV scale = 1.
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
     * Release the SR feature if the DLSS-SR path has been switched off since it was created, and
     * report whether anything was released. Mirrors {@link RtDlssRr#releaseIfDisabled}: the
     * feature holds device memory for its internal history buffers, so it must not sit allocated
     * after the selector moves away. Idempotent.
     */
    public boolean releaseIfDisabled() {
        if (enabled() || isNull(feature)) {
            return false;
        }
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseFeature(device);
            CausticaMod.LOGGER.info("DLSS-SR feature released: DLSS path switched away");
            return true;
        }
        return false;
    }

    /**
     * Asks NGX what render resolution the current quality mode expects for the given display size.
     * Returns {@code null} only when the DLSS-SR path is off (or already disabled from an earlier
     * failure elsewhere) — in that state there is no feature to query and the caller should trace
     * at full resolution. Once SR is active, a failed query throws instead of silently falling
     * back, so a broken render/display sync is never masked.
     */
    public int[] queryOptimalRenderSize(int displayWidth, int displayHeight) {
        if (!enabled() || failed) {
            return null;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return null;
        }
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
    }

    /**
     * Ensure NGX is initialized and an SR feature exists for the given resolutions, creating it
     * into the supplied recording command buffer. Returns false (and disables itself) on any
     * failure so the caller falls back to the blit upscale.
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
                    || featureQuality != quality
                    || isNull(feature)) {
                releaseFeature(device);
                feature = lib.createDlss(cmd, renderWidth, renderHeight, displayWidth, displayHeight,
                        quality, FEATURE_FLAGS, RENDER_PRESET_DEFAULT);
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

    private void ensureInitialized(VulkanDevice device) {
        if (initialized) {
            return;
        }
        // NGX init/shutdown is owned by the shared NgxRuntime so RR, SR and Frame Generation can
        // coexist (releasing one feature must not tear NGX down while another still holds a handle).
        lib = NgxRuntime.INSTANCE.acquire(device);
        if (lib == null) {
            throw new IllegalStateException("NGX runtime unavailable; DLSS-SR cannot initialize");
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

    /**
     * Release the SR feature. Does NOT shut down NGX — that is the shared {@link NgxRuntime}'s job at device
     * teardown, so RR/FG can keep using NGX.
     */
    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseFeature(device);
        }
        initialized = false;
        lib = null;
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
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }
}
