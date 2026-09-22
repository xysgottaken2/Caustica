package dev.comfyfluffy.caustica.rt.overlay;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.ARGB;

import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;

/**
 * Entity name tags — full-res, post-upscale, billboarded text quads. Unlike glow (which reuses the
 * entity's own rigid-body capture), name tags are text shaped fresh every frame with vanilla's own
 * {@link Font} (glyph layout/metrics/kerning all reused as-is) and billboarded to face the camera — see
 * {@code RtEntities.captureNameTag}'s comment for why that can't be folded into the entity's own mesh.
 * Occlusion is a single CPU {@code level.clip()} raycast per candidate tag (done at capture time, in
 * {@code RtEntities}); this feature only ever sees tags that already passed that check, so — unlike
 * vanilla, which draws a translucent "ghost" copy through walls — an occluded tag simply isn't submitted.
 *
 * <p>Glyphs are grouped by font-atlas page ({@link GpuTextureView} identity: the default ASCII page vs.
 * the unicode-fallback page, say) into one merged vertex buffer per page, each drawn with its page bound
 * as a real combined-image-sampler (not bindless — this is a plain forward raster pass, not in-RT
 * shading) — one descriptor set PER page (see {@link RtOverlayPipelines.SampledImageSetPool}), since a
 * single set rewritten between pages would leave every draw in the command buffer sampling whichever page
 * was bound last. Non-indexed (2 triangles/quad, 6 vertices) — text volume is small enough that doubling
 * two shared corners per quad isn't worth an index buffer.
 */
final class RtNameTagFeature implements RtOverlayFeature {
    // mat4 curViewProj (0, 64B) + vec3 camOffset (64, padded to 16B) = 80B.
    private static final int PUSH_BYTES = 80;
    private static final int VERTEX_STRIDE = RtOverlayPipelines.VertexFormat.POSITION_TEX_COLOR.stride; // 24B
    // Vanilla's unoccluded name tag is actually two overlaid copies (opaque depth-tested + translucent
    // see-through-with-background) — see SubmitNodeCollection.submitNameTag. We draw a single pass
    // matching the see-through copy's look (translucent white text + dark background box), since we
    // already resolved occlusion via a hard CPU visibility test instead of a second draw pass.
    private static final int TEXT_COLOR = -2130706433; // 0x80FFFFFF
    private static final int BACKGROUND_COLOR = 0x40000000; // ~25% opaque black; vanilla's default opacity

    // Generous cap on distinct font-atlas pages seen in one session (default ASCII + unicode fallback +
    // headroom) — see SampledImageSetPool's doc for why each page needs its own descriptor set rather than
    // one shared set rewritten per page.
    private static final int MAX_ATLAS_PAGES = 16;

    private RtContext ctx;
    private RtOverlayPipelines.Pipeline pipeline;
    private RtOverlayPipelines.SampledImageSetPool imageSetPool;
    private long sampler;

    // One descriptor set per distinct font-atlas page, allocated once and reused across frames (a page's
    // GpuTextureView is stable for the session — see SampledImageSetPool).
    private final Map<GpuTextureView, Long> pageSets = new IdentityHashMap<>();
    private final Map<GpuTextureView, PageBuilder> pages = new IdentityHashMap<>();
    private final GlyphCapture glyphCapture = new GlyphCapture();
    private final Matrix4f pose = new Matrix4f();
    private final Matrix4f viewProj = new Matrix4f();
    private float camOffX, camOffY, camOffZ;
    private final List<DrawPage> drawPages = new ArrayList<>();

    private record DrawPage(GpuTextureView view, RtBuffer vbo, int vertexCount) {
    }

    /** One font-atlas page's accumulated glyph quads this frame: x,y,z,u,v per vertex + a packed colour. */
    private static final class PageBuilder {
        final FloatArrayList posUv = new FloatArrayList();
        final IntArrayList colors = new IntArrayList();

        int vertexCount() {
            return colors.size();
        }
    }

    @Override
    public boolean prepare(RtContext ctx, RtOverlayFramePool pool, RtGpuExecutor.GraphicsUse graphicsUse,
                           int width, int height) {
        if (!RtEntities.nameTagsEnabled()) {
            return false;
        }
        List<RtEntities.NameTagEntity> tags = RtEntities.INSTANCE.nameTagBatches();
        if (tags.isEmpty()) {
            return false;
        }
        ensureResources(ctx);

        Font font = Minecraft.getInstance().font;
        Quaternionf billboard = RtEntities.INSTANCE.cameraOrientation();
        pages.clear();
        for (RtEntities.NameTagEntity tag : tags) {
            pose.identity().translate(tag.x(), tag.y(), tag.z()).rotate(billboard)
                    .scale(EntityRenderer.NAMETAG_SCALE, -EntityRenderer.NAMETAG_SCALE, EntityRenderer.NAMETAG_SCALE);
            float x = -font.width(tag.text()) / 2.0F;
            Font.PreparedText text = font.prepareText(tag.text().getVisualOrderText(), x, 0.0F, TEXT_COLOR, false, false, BACKGROUND_COLOR);
            glyphCapture.pose = pose;
            text.visit(glyphCapture);
        }
        if (pages.isEmpty()) {
            return false; // every tag shaped to zero glyphs (blank name — shouldn't normally happen)
        }

        drawPages.clear();
        for (Map.Entry<GpuTextureView, PageBuilder> e : pages.entrySet()) {
            PageBuilder b = e.getValue();
            int vertexCount = b.vertexCount();
            if (vertexCount == 0) {
                continue;
            }
            RtBuffer vbo = pool.acquireVertex(ctx, (long) vertexCount * VERTEX_STRIDE, "name tag vbo");
            ByteBuffer buf = MemoryUtil.memByteBuffer(vbo.mapped, vertexCount * VERTEX_STRIDE).order(ByteOrder.LITTLE_ENDIAN);
            float[] posUv = b.posUv.elements();
            for (int v = 0; v < vertexCount; v++) {
                int o = v * 5;
                buf.putFloat(posUv[o]).putFloat(posUv[o + 1]).putFloat(posUv[o + 2]);
                buf.putFloat(posUv[o + 3]).putFloat(posUv[o + 4]);
                buf.putInt(ARGB.toABGR(b.colors.getInt(v))); // R8G8B8A8_UNORM memory order = ABGR-packed int
            }
            vbo.flush(0L, (long) vertexCount * VERTEX_STRIDE);
            drawPages.add(new DrawPage(e.getKey(), vbo, vertexCount));
        }
        if (drawPages.isEmpty()) {
            return false;
        }

        viewProj.set(RtComposite.INSTANCE.currentViewProjection());
        camOffX = RtEntities.INSTANCE.glowCamOffsetX();
        camOffY = RtEntities.INSTANCE.glowCamOffsetY();
        camOffZ = RtEntities.INSTANCE.glowCamOffsetZ();
        return true;
    }

    private void ensureResources(RtContext ctx) {
        this.ctx = ctx;
        if (pipeline != null) {
            return;
        }
        imageSetPool = RtOverlayPipelines.sampledImageSetPool(ctx, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, MAX_ATLAS_PAGES, "name tag");
        sampler = RtOverlayPipelines.createNearestClampSampler(ctx, "name tag font atlas");
        pipeline = new RtOverlayPipelines.Spec("name_tag.vert.spv", "name_tag.frag.spv")
                .vertex(RtOverlayPipelines.VertexFormat.POSITION_TEX_COLOR)
                .blend(RtOverlayPipelines.Blend.ALPHA)
                .attachment(RtWorldOverlay.TARGET_FORMAT)
                .push(PUSH_BYTES, VK10.VK_SHADER_STAGE_VERTEX_BIT)
                .descriptorSetLayout(imageSetPool.layout)
                .build(ctx, "name tag");
    }

    @Override
    public void record(VkCommandBuffer cmd, long targetView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "name tags")) {
            RtWorldOverlay.beginColorRendering(cmd, stack, targetView, width, height, false);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            viewProj.get(0, push);
            push.putFloat(64, camOffX).putFloat(68, camOffY).putFloat(72, camOffZ);
            VK10.vkCmdPushConstants(cmd, pipeline.layout, VK10.VK_SHADER_STAGE_VERTEX_BIT, 0, push);
            for (DrawPage page : drawPages) {
                long set = pageSets.computeIfAbsent(page.view,
                        v -> imageSetPool.allocateAndBind(ctx, vkImageView(v), sampler));
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout, 0,
                        stack.longs(set), null);
                VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(page.vbo.handle), stack.longs(0L));
                VK10.vkCmdDraw(cmd, page.vertexCount, 1, 0, 0);
            }
            RtWorldOverlay.endRendering(cmd);
        }
    }

    @Override
    public void destroy() {
        if (ctx == null) {
            return;
        }
        if (pipeline != null) {
            pipeline.destroy(ctx.vk());
            pipeline = null;
        }
        if (imageSetPool != null) {
            imageSetPool.destroy(ctx.vk());
            imageSetPool = null;
        }
        pageSets.clear();
        if (sampler != 0L) {
            VK10.vkDestroySampler(ctx.vk(), sampler, null);
            sampler = 0L;
        }
        ctx = null;
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }

    /**
     * Adapts glyph rendering's builder-style {@link VertexConsumer} calls into {@link PageBuilder} arrays,
     * batching by the glyph/effect's source atlas page. Every {@code TextRenderable.render} call emits
     * whole quads (4 vertices each: top-left, bottom-left, bottom-right, top-right — see {@code
     * BakedSheetGlyph.render}/{@code buildEffect}), so a 4-vertex rolling buffer is enough to expand each
     * quad into 2 triangles (no index buffer) as the 4th vertex of each one commits.
     */
    private final class GlyphCapture implements Font.GlyphVisitor, VertexConsumer {
        Matrix4f pose;
        PageBuilder current;
        private final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];
        private final int[] qc = new int[4];
        private int quadCount;
        private float vx, vy, vz, vu, vv;
        private int vColor = -1;

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            current = pages.computeIfAbsent(renderable.textureView(), k -> new PageBuilder());
            quadCount = 0;
            renderable.render(pose, this, 0, false);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            vx = x;
            vy = y;
            vz = z;
            vColor = -1;
            vu = 0f;
            vv = 0f;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            vColor = ARGB.color(a, r, g, b);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            vColor = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            vu = u;
            vv = v;
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this; // name-tag glyphs carry a single UV layer; UV3 is unused here
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this; // overlay coords unused (no damage-tint pass here)
        }

        @Override
        public VertexConsumer setUv2(int lightU, int lightV) {
            // Always the last call of a vertex (mirrors RtEntityCollector.RtTextVertexConsumer) — commit here.
            qx[quadCount] = vx;
            qy[quadCount] = vy;
            qz[quadCount] = vz;
            qu[quadCount] = vu;
            qv[quadCount] = vv;
            qc[quadCount] = vColor;
            quadCount++;
            if (quadCount == 4) {
                emitQuad();
                quadCount = 0;
            }
            return this;
        }

        private void emitQuad() {
            emitVertex(0);
            emitVertex(1);
            emitVertex(2);
            emitVertex(0);
            emitVertex(2);
            emitVertex(3);
        }

        private void emitVertex(int i) {
            current.posUv.add(qx[i]);
            current.posUv.add(qy[i]);
            current.posUv.add(qz[i]);
            current.posUv.add(qu[i]);
            current.posUv.add(qv[i]);
            current.colors.add(qc[i]);
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this; // planar glyph quad — no lighting in this raster pass
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
