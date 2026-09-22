package dev.comfyfluffy.caustica.rt.overlay;

import org.lwjgl.vulkan.VkCommandBuffer;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;

/**
 * One world-space overlay effect (glow outline today; block outline, nametags, leash planned) rendered by
 * {@link RtWorldOverlay} at the post-upscale seam. Implementations create their pipelines through
 * {@link RtOverlayPipelines} (reusing an existing vertex-format/blend combination where one fits) and take
 * per-frame vertex scratch from the shared {@link RtOverlayFramePool} — never own one-off pools.
 */
public interface RtOverlayFeature {
    /**
     * Gather this frame's CPU-side data, lazily create GPU resources, and upload vertex scratch via
     * {@code pool}. Runs before any command recording; return false to skip {@link #record} this frame.
     * {@code graphicsUse} is the exact completion token for resources referenced by the recorded commands.
     * {@code width}/{@code height} are the composite target's (display-res) extent.
     */
    boolean prepare(RtContext ctx, RtOverlayFramePool pool, RtGpuExecutor.GraphicsUse graphicsUse,
                    int width, int height);

    /**
     * Record this feature's passes. {@code targetView} is {@link RtWorldOverlay}'s shared, mod-owned world-
     * overlay buffer ({@link RtWorldOverlay#TARGET_FORMAT}, GENERAL layout, cleared to transparent black
     * once per frame before any feature runs) — NOT the presented image directly; composite onto it with
     * {@code loadOp = LOAD} dynamic rendering and {@code RtOverlayPipelines.Blend.ALPHA} (straight-alpha
     * "over" — see that enum constant's doc for why the shared buffer ends up premultiplied once more than
     * one feature has drawn into it). {@link RtWorldOverlay} blends the buffer onto the real presented image
     * (or dispatches an HDR-space composite) after every feature has drawn, so features never touch
     * vanilla's own texture (which really isn't storage-capable, unlike this shared buffer). Host vertex
     * writes are already visible; barriers between a feature's own passes are the feature's responsibility,
     * and {@link RtWorldOverlay} barriers between features.
     */
    void record(VkCommandBuffer cmd, long targetView, int width, int height);

    /** Destroy GPU resources (device is idle); must tolerate never-prepared and repeated calls. */
    void destroy();
}
