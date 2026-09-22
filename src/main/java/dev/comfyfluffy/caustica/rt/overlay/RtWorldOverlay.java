package dev.comfyfluffy.caustica.rt.overlay;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkOffset2D;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.lwjgl.vulkan.VkViewport;

import java.util.ArrayList;
import java.util.List;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

/**
 * The world-space overlay seam: full-res raster content prepared after the RT world has been upscaled
 * (nothing thin/crisp survives DLSS-RR, so overlays must not be traced/rastered at render res) and folded
 * into the shared transparent UI image before the hand/screen-effects/GUI layers draw over it. Called once
 * per frame from {@code GameRendererMixin} at the before-hand seam.
 *
 * <p>This class owns the questions every overlay feature would otherwise re-answer: which image to
 * composite onto (a shared mod-owned overlay buffer — every feature draws into THAT, not the final UI
 * overlay directly, see {@link #overlayImage} below), the transient command buffer + inter-feature barriers,
 * per-frame vertex scratch ({@link RtOverlayFramePool}), and the failure latch. Features implement
 * {@link RtOverlayFeature}; pipelines come from {@link RtOverlayPipelines}.
 *
 * <p>Routing every feature through one shared buffer instead of blending straight onto vanilla's SDR
 * {@code main} is what keeps SDR/HDR presentation unified: {@link #record} now folds that buffer into
 * {@link RtUiOverlay}'s transparent overlay before the vanilla GUI renders, so the GUI remains topmost and
 * the final present path only has one UI image to blend. (Block outline's own private MSAA-mask-resolve path
 * predates this buffer and still runs before its result ever reaches {@code overlayImage} — an FXAA pass over
 * the shared buffer was tried and removed as looking worse than expected; MSAA remains the only edge-AA
 * mechanism today.)
 */
public final class RtWorldOverlay {
    public static final RtWorldOverlay INSTANCE = new RtWorldOverlay();

    /** The shared overlay buffer's + presented image's VkFormat ({@code GpuFormat.RGBA8_UNORM}). */
    public static final int TARGET_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

    private final RtOverlayFramePool framePool = new RtOverlayFramePool();
    private final List<RtOverlayFeature> features =
            List.of(new RtGlowOutlineFeature(), new RtNameTagFeature(), new RtBlockOutlineFeature());
    private boolean failed;

    // Shared world-overlay buffer every feature composites into (lazily sized to main's width/height, same
    // lazy-resize convention as e.g. RtGlowOutlineFeature's own private mask image). uiComposite* blends it
    // into RtUiOverlay's transparent target; RtUiOverlay owns the one final SDR/HDR blend to the real target.
    private RtContext ctxRef;
    private RtImage overlayImage;
    private RtOverlayPipelines.Pipeline uiCompositePipeline;
    private RtOverlayPipelines.StorageImageSet uiCompositeSet;

    private RtWorldOverlay() {
    }

    /**
     * Render every active world-overlay feature and fold it into {@link RtUiOverlay}'s shared transparent
     * target. Called after the RT world composite and before the vanilla hand/screen-effects/GUI path can draw
     * more UI layers into that same target.
     */
    public void compositeIntoUiOverlay(RenderTarget main, RtGpuExecutor.GraphicsUse graphicsUse) {
        if (graphicsUse == null || failed || main == null || main.getColorTexture() == null || !RtUiOverlay.enabled()) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        try {
            List<RtOverlayFeature> ready = new ArrayList<>(features.size());
            for (RtOverlayFeature f : features) {
                if (f.prepare(ctx, framePool, graphicsUse, main.width, main.height)) {
                    ready.add(f);
                }
            }
            if (!ready.isEmpty()) {
                ensureOverlayBuffer(ctx, main.width, main.height);
                RenderTarget uiTarget = RtUiOverlay.beginCompositeLayer(main);
                long targetView = vkImageView(uiTarget.getColorTextureView());
                if (targetView == 0L) {
                    CausticaMod.LOGGER.warn("World overlay: UI overlay target has no Vulkan image view; skipping");
                    return;
                }
                record(ctx, ready, targetView, main.width, main.height);
            }
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("World overlay failed; disabling for this session", t);
        } finally {
            framePool.endFrame(ctx, graphicsUse);
        }
    }

    private void ensureOverlayBuffer(RtContext ctx, int width, int height) {
        this.ctxRef = ctx;
        if (uiCompositePipeline == null) {
            uiCompositeSet = RtOverlayPipelines.storageImageSet(ctx, 1, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, "world overlay UI composite");
            // PREMULTIPLIED_ALPHA, not ALPHA: overlayImage ends up holding premultiplied content once more
            // than one feature has drawn into it (see Blend.ALPHA's doc) — blending it into the shared UI
            // image with the straight-alpha recipe would double-multiply by alpha.
            uiCompositePipeline = new RtOverlayPipelines.Spec("overlay_fullscreen_triangle.vert.spv", "overlay_passthrough_composite.frag.spv")
                    .blend(RtOverlayPipelines.Blend.PREMULTIPLIED_ALPHA)
                    .attachment(TARGET_FORMAT)
                    .descriptorSetLayout(uiCompositeSet.layout)
                    .build(ctx, "world overlay UI composite");
        }
        if (overlayImage == null || overlayImage.width != width || overlayImage.height != height) {
            if (overlayImage != null) {
                overlayImage.destroy();
            }
            overlayImage = ctx.createStorageImage(width, height, TARGET_FORMAT,
                    "world overlay " + width + "x" + height, VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        }
        uiCompositeSet.bind(ctx, 0, overlayImage.view);
    }

    private void record(RtContext ctx, List<RtOverlayFeature> ready, long targetView, int width, int height) {
        var encoder = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // host vertex writes visible

            long overlayView = overlayImage.view;
            beginColorRendering(cmd, stack, overlayView, width, height, true); // clear to transparent once
            endRendering(cmd);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            for (RtOverlayFeature f : ready) {
                f.record(cmd, overlayView, width, height);
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // this feature's writes visible to the next / final composite
            }

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world overlay UI composite")) {
                beginColorRendering(cmd, stack, targetView, width, height, false); // LOAD the transparent UI image
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, uiCompositePipeline.handle);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, uiCompositePipeline.layout, 0,
                        stack.longs(uiCompositeSet.set), null);
                VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
                endRendering(cmd);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // this composite's writes visible to whatever presents next
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(world overlay) failed");
        }
        encoder.execute(cmd);
    }

    /** Teardown with the rest of the RT stack ({@code RtComposite.destroy}); the device is idle by then. */
    public void destroy() {
        for (RtOverlayFeature f : features) {
            f.destroy();
        }
        if (uiCompositePipeline != null && ctxRef != null) {
            uiCompositePipeline.destroy(ctxRef.vk());
            uiCompositeSet.destroy(ctxRef.vk());
        }
        uiCompositePipeline = null;
        uiCompositeSet = null;
        if (overlayImage != null) {
            overlayImage.destroy();
            overlayImage = null;
        }
        ctxRef = null;
        framePool.destroy();
    }

    // ---- Recording helpers shared by features ----

    /**
     * Begin a one-attachment dynamic-rendering pass on {@code view} (GENERAL layout) and set the
     * viewport/scissor. {@code clear} = start from transparent black (mask passes); otherwise the existing
     * content is loaded (composite passes). Balance with {@link #endRendering}.
     */
    static void beginColorRendering(VkCommandBuffer cmd, MemoryStack stack, long view, int width, int height, boolean clear) {
        VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(clear ? VK10.VK_ATTACHMENT_LOAD_OP_CLEAR : VK10.VK_ATTACHMENT_LOAD_OP_LOAD)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_STORE);
        if (clear) {
            VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
            clearValue.get(0).color().float32(stack.floats(0f, 0f, 0f, 0f));
            colorAttach.get(0).clearValue(clearValue.get(0));
        }
        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
        renderArea.extent().set(width, height);
        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(renderArea).layerCount(1).pColorAttachments(colorAttach);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    /**
     * Begin a one-attachment dynamic-rendering pass on the multisample {@code msaaView}, always clearing to
     * transparent black (mask passes only — there is nothing sensible to "load" into a fresh multisample
     * image from a single-sample source). {@code resolveView} receives the driver's per-pixel sample average
     * when the pass ends ({@link #endRendering}) — {@code VK_RESOLVE_MODE_AVERAGE_BIT} is the only mode
     * color attachments support, which is exactly coverage-weighted anti-aliasing for a flat-colour mask.
     */
    static void beginMsaaColorRendering(VkCommandBuffer cmd, MemoryStack stack, long msaaView, long resolveView,
                                        int width, int height) {
        VkRenderingAttachmentInfo.Buffer colorAttach = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                .imageView(msaaView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .resolveMode(VK12.VK_RESOLVE_MODE_AVERAGE_BIT)
                .resolveImageView(resolveView).resolveImageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .loadOp(VK10.VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK10.VK_ATTACHMENT_STORE_OP_DONT_CARE); // only the resolved target's contents matter
        VkClearValue.Buffer clearValue = VkClearValue.calloc(1, stack);
        clearValue.get(0).color().float32(stack.floats(0f, 0f, 0f, 0f));
        colorAttach.get(0).clearValue(clearValue.get(0));

        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
        renderArea.extent().set(width, height);
        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack).sType$Default()
                .renderArea(renderArea).layerCount(1).pColorAttachments(colorAttach);
        KHRDynamicRendering.vkCmdBeginRenderingKHR(cmd, renderingInfo);

        VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
        viewport.get(0).x(0).y(0).width(width).height(height).minDepth(0f).maxDepth(1f);
        VK10.vkCmdSetViewport(cmd, 0, viewport);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).offset(VkOffset2D.calloc(stack).set(0, 0));
        scissor.get(0).extent().set(width, height);
        VK10.vkCmdSetScissor(cmd, 0, scissor);
    }

    static void endRendering(VkCommandBuffer cmd) {
        KHRDynamicRendering.vkCmdEndRenderingKHR(cmd);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
