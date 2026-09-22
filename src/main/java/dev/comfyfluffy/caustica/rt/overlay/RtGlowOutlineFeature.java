package dev.comfyfluffy.caustica.rt.overlay;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.List;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;

/**
 * Entity glow (Glowing-effect) outline — full-res, post-upscale, depth-less. Two passes:
 * <ol>
 *   <li>Re-rasterize this frame's glowing entities (their CPU-side capture positions, already kept around
 *   by {@link RtEntities} for BLAS refit) with a trivial unlit pipeline into a full-res RGBA8 mask
 *   (rgb = the entity's vanilla outline colour, a = coverage) — a mod-owned storage image. The camera
 *   transform mirrors the one {@code world.rgen} used this frame, so the silhouette lands pixel-exact on
 *   the ray-traced entity.</li>
 *   <li>Sobel-edge that mask and blend the ~2px boundary onto the composite target via fixed-function
 *   alpha blending.</li>
 * </ol>
 * Matches vanilla's silhouette-through-walls look without ever touching a depth buffer. Never makes the
 * entity itself emissive.
 */
final class RtGlowOutlineFeature implements RtOverlayFeature {
    // mat4 curViewProj (0, 64B) + vec3 camOffset (64, padded to 16B) + vec4 color (80, 16B) = 96B.
    private static final int MASK_PUSH_BYTES = 96;
    private static final int MASK_FORMAT = VK10.VK_FORMAT_R8G8B8A8_UNORM;

    private RtContext ctx;
    private RtOverlayPipelines.Pipeline maskPipeline;
    private RtOverlayPipelines.Pipeline compositePipeline;
    private RtOverlayPipelines.StorageImageSet compositeSet;
    private RtImage maskImage;

    // This frame's prepared draw data (valid between prepare() returning true and record()).
    private final Matrix4f viewProj = new Matrix4f();
    private float camOffX, camOffY, camOffZ;
    private RtBuffer vbo;
    private RtBuffer ibo;
    private int[] firstIndex;
    private int[] indexCount;
    private float[] colorRgba;
    private int drawCount;

    @Override
    public boolean prepare(RtContext ctx, RtOverlayFramePool pool, RtGpuExecutor.GraphicsUse graphicsUse,
                           int width, int height) {
        if (!RtEntities.glowEnabled()) {
            return false;
        }
        List<RtEntities.GlowEntity> batches = RtEntities.INSTANCE.glowBatches();
        if (batches.isEmpty()) {
            return false;
        }
        ensureResources(ctx, width, height);

        // Merge every glowing entity's mesh into one vertex/index pair (indices rebased onto the merged
        // vertex buffer); one draw per entity so each can push its own outline colour.
        int totalVerts = 0;
        int totalIdx = 0;
        for (RtEntities.GlowEntity e : batches) {
            totalVerts += e.verts().length / 3;
            totalIdx += e.idx().length;
        }
        float[] mergedVerts = new float[totalVerts * 3];
        int[] mergedIdx = new int[totalIdx];
        drawCount = batches.size();
        firstIndex = new int[drawCount];
        indexCount = new int[drawCount];
        colorRgba = new float[drawCount * 4];
        int vOff = 0;
        int iOff = 0;
        int vBase = 0;
        for (int i = 0; i < drawCount; i++) {
            RtEntities.GlowEntity e = batches.get(i);
            float[] verts = e.verts();
            System.arraycopy(verts, 0, mergedVerts, vOff, verts.length);
            int[] idx = e.idx();
            firstIndex[i] = iOff;
            indexCount[i] = idx.length;
            for (int j = 0; j < idx.length; j++) {
                mergedIdx[iOff + j] = idx[j] + vBase;
            }
            int color = e.color();
            colorRgba[i * 4] = ((color >> 16) & 0xFF) / 255f;
            colorRgba[i * 4 + 1] = ((color >> 8) & 0xFF) / 255f;
            colorRgba[i * 4 + 2] = (color & 0xFF) / 255f;
            colorRgba[i * 4 + 3] = ((color >>> 24) & 0xFF) / 255f;
            vOff += verts.length;
            iOff += idx.length;
            vBase += verts.length / 3;
        }

        vbo = pool.acquireVertex(ctx, (long) mergedVerts.length * Float.BYTES, "glow vbo");
        ibo = pool.acquireIndex(ctx, (long) mergedIdx.length * Integer.BYTES, "glow ibo");
        MemoryUtil.memFloatBuffer(vbo.mapped, mergedVerts.length).put(mergedVerts);
        MemoryUtil.memIntBuffer(ibo.mapped, mergedIdx.length).put(mergedIdx);
        vbo.flush(0L, (long) mergedVerts.length * Float.BYTES);
        ibo.flush(0L, (long) mergedIdx.length * Integer.BYTES);

        viewProj.set(RtComposite.INSTANCE.currentViewProjection());
        camOffX = RtEntities.INSTANCE.glowCamOffsetX();
        camOffY = RtEntities.INSTANCE.glowCamOffsetY();
        camOffZ = RtEntities.INSTANCE.glowCamOffsetZ();
        return true;
    }

    private void ensureResources(RtContext ctx, int width, int height) {
        this.ctx = ctx;
        if (maskPipeline == null) {
            maskPipeline = new RtOverlayPipelines.Spec("entity_glow.vert.spv", "entity_glow.frag.spv")
                    .vertex(RtOverlayPipelines.VertexFormat.POSITION)
                    .attachment(MASK_FORMAT)
                    .push(MASK_PUSH_BYTES, VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .build(ctx, "glow mask");
            compositeSet = RtOverlayPipelines.storageImageSet(ctx, 1, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, "glow composite");
            compositePipeline = new RtOverlayPipelines.Spec("overlay_fullscreen_triangle.vert.spv", "entity_glow_composite.frag.spv")
                    .blend(RtOverlayPipelines.Blend.ALPHA)
                    .attachment(RtWorldOverlay.TARGET_FORMAT)
                    .descriptorSetLayout(compositeSet.layout)
                    .build(ctx, "glow composite");
        }
        if (maskImage == null || maskImage.width != width || maskImage.height != height) {
            if (maskImage != null) {
                maskImage.destroy();
            }
            maskImage = ctx.createStorageImage(width, height, MASK_FORMAT,
                    "glow outline mask " + width + "x" + height, VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        }
        compositeSet.bind(ctx, 0, maskImage.view);
    }

    @Override
    public void record(VkCommandBuffer cmd, long targetView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "glow entity mask")) {
                RtWorldOverlay.beginColorRendering(cmd, stack, maskImage.view, width, height, true);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, maskPipeline.handle);
                VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(vbo.handle), stack.longs(0L));
                VK10.vkCmdBindIndexBuffer(cmd, ibo.handle, 0, VK10.VK_INDEX_TYPE_UINT32);
                ByteBuffer push = stack.malloc(MASK_PUSH_BYTES);
                viewProj.get(0, push);
                for (int i = 0; i < drawCount; i++) {
                    push.putFloat(64, camOffX).putFloat(68, camOffY).putFloat(72, camOffZ);
                    push.putFloat(80, colorRgba[i * 4]).putFloat(84, colorRgba[i * 4 + 1])
                            .putFloat(88, colorRgba[i * 4 + 2]).putFloat(92, colorRgba[i * 4 + 3]);
                    VK10.vkCmdPushConstants(cmd, maskPipeline.layout,
                            VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
                    VK10.vkCmdDrawIndexed(cmd, indexCount[i], 1, firstIndex[i], 0, 0);
                }
                RtWorldOverlay.endRendering(cmd);
            }

            VulkanCommandEncoder.memoryBarrier(cmd, stack); // mask attachment writes visible to the composite's reads

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "glow entity composite")) {
                RtWorldOverlay.beginColorRendering(cmd, stack, targetView, width, height, false);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline.handle);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, compositePipeline.layout, 0,
                        stack.longs(compositeSet.set), null);
                VK10.vkCmdDraw(cmd, 3, 1, 0, 0);
                RtWorldOverlay.endRendering(cmd);
            }
        }
    }

    @Override
    public void destroy() {
        if (ctx == null) {
            return;
        }
        if (maskPipeline != null) {
            maskPipeline.destroy(ctx.vk());
            maskPipeline = null;
        }
        if (compositePipeline != null) {
            compositePipeline.destroy(ctx.vk());
            compositePipeline = null;
        }
        if (compositeSet != null) {
            compositeSet.destroy(ctx.vk());
            compositeSet = null;
        }
        if (maskImage != null) {
            maskImage.destroy();
            maskImage = null;
        }
        ctx = null;
    }
}
