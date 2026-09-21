package dev.xys.vulkanrt.geometry;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.xys.vulkanrt.mixin.EntityDrawAccessor;
import dev.xys.vulkanrt.mixin.EntityStagedAccessor;
import dev.xys.vulkanrt.render.RayTracingRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import java.util.*;

/**
 * Receipts for the vanilla particle upload.  ParticleRenderState and
 * QuadParticleFeatureRenderer feed the same StagedVertexBuffer path as other
 * feature draws; this class deliberately observes the PARTICLE format at that
 * boundary instead of calling Particle.render or making a second tessellator.
 *
 * The positions are the final camera-facing positions emitted by vanilla. They
 * are copied GPU-to-GPU and put in the shared scene with a camera-to-anchor
 * instance translation. No CPU vertex readback, atlas copy, or replacement
 * particle quad is made here.
 */
public final class ParticleCapture {
    public static final class Upload {
        private final StagedVertexBuffer.Draw draw;
        private final int first;
        private final SectionGeometryLayout layout;
        private volatile PreparedRenderType material;
        private final VulkanGpuBuffer source;
        private final long offset;
        public Upload(StagedVertexBuffer.Draw draw, int first, SectionGeometryLayout layout,
                      PreparedRenderType material, VulkanGpuBuffer source, long offset) {
            this.draw=draw; this.first=first; this.layout=layout; this.material=material;
            this.source=source; this.offset=offset;
        }
        public StagedVertexBuffer.Draw draw() { return draw; }
        public int first() { return first; }
        public SectionGeometryLayout layout() { return layout; }
        public PreparedRenderType material() { return material; }
        public void material(PreparedRenderType value) { material=value; }
        public VulkanGpuBuffer source() { return source; }
        public long offset() { return offset; }
    }
    private record Cursor(StagedVertexBuffer.Draw draw, RenderType type) {}
    private record DrawAddress(Object buffer, int baseVertex) {}

    private static final Map<BufferBuilder, Cursor> cursors = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.Draw, Cursor> drawCursors = new IdentityHashMap<>();
    private static final Map<BufferBuilder, RenderType> types = new IdentityHashMap<>();
    private static final Map<RenderType, PreparedRenderType> prepared = new IdentityHashMap<>();
    private static final Map<DrawAddress, List<Upload>> uploaded = new HashMap<>();
    private static final List<Upload> frameUploads = new ArrayList<>();
    private static final Set<StagedVertexBuffer.Draw> particleDraws =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<String, Integer> rejected = new TreeMap<>();
    private static long lastReport;

    private static boolean active() {
        return RayTracingRenderer.chunkCaptureEnabled() && RenderSystem.isRenderingLevel;
    }

    public static void beginFrame() {
        cursors.clear();
        drawCursors.clear();
        types.clear();
        prepared.clear();
        uploaded.clear();
        frameUploads.clear();
        particleDraws.clear();
        rejected.clear();
    }

    public static void prepared(RenderType type, PreparedRenderType value) {
        if (active()) prepared.put(type, value);
    }

    public static void renderType(VertexConsumer vertex, RenderType type) {
        if (active() && vertex instanceof BufferBuilder builder) types.put(builder, type);
    }

    public static void builder(StagedVertexBuffer.Draw draw, VertexConsumer vertex) {
        if (!active() || !(vertex instanceof BufferBuilder builder)) return;
        var accessor = (EntityDrawAccessor) (Object) draw;
        if (accessor.nativeVulkanRt$format() == DefaultVertexFormat.PARTICLE) {
            var cursor = new Cursor(draw, types.get(builder));
            cursors.put(builder, cursor);
            drawCursors.put(draw, cursor);
            particleDraws.add(draw);
        }
    }

    /** Called after the exact vanilla copyToBuffer call. */
    public static void upload(StagedVertexBuffer owner, GpuBufferSlice source, GpuBufferSlice target) {
        if (!active() || source.offset() != 0 || !(source.buffer() instanceof VulkanGpuBuffer vk)) return;
        var draws = ((EntityStagedAccessor) (Object) owner).nativeVulkanRt$draws();
        var uploads = new ArrayList<Upload>();
        for (var draw : draws) {
            var d = (EntityDrawAccessor) (Object) draw;
            if (d.nativeVulkanRt$format() != DefaultVertexFormat.PARTICLE) continue;
            if (d.nativeVulkanRt$topology() != PrimitiveTopology.QUADS) {
                reject("NON_QUAD_TOPOLOGY");
                continue;
            }
            int count = d.nativeVulkanRt$vertexCount();
            if (count <= 0 || (count & 3) != 0) {
                reject("INCOMPLETE_QUAD_RANGE");
                continue;
            }
            var cursor = drawCursors.get(draw);
            PreparedRenderType material = cursor == null ? null : prepared.get(cursor.type());
            // QuadParticleFeatureRenderer has its own feature path and may not
            // pass through RenderTypeFeatureRenderer#getVertexBuilder. The draw
            // callback below supplies the PreparedRenderType in that case; do
            // not discard the vanilla upload here.
            var format = d.nativeVulkanRt$format();
            SectionGeometryLayout layout;
            try {
                layout = SectionGeometryLayout.solidQuads(format, count / 4 * 6);
            } catch (RuntimeException failure) {
                reject("VERTEX_LAYOUT:" + failure.getMessage());
                continue;
            }
            long offset = d.nativeVulkanRt$vertexOffset();
            long bytes = layout.vertexBytes();
            if (offset < 0 || offset > source.length() - bytes) {
                reject("STAGED_UPLOAD_RANGE");
                continue;
            }
            var receipt = new Upload(draw, 0, layout, material, vk, offset);
            uploads.add(receipt);
            frameUploads.add(receipt);
            uploaded.computeIfAbsent(new DrawAddress(target.buffer(), d.nativeVulkanRt$vertexOffset() / layout.stride()),
                    ignored -> new ArrayList<>()).add(receipt);
        }
        if (!uploads.isEmpty()) RayTracingRenderer.uploadParticles(uploads);
        report();
    }

    public static void draw(PreparedRenderType material, StagedVertexBuffer.ExecuteInfo info) {
        if (!active()) return;
        var list = uploaded.get(new DrawAddress(info.vertexBuffer(), info.baseVertex()));
        if (list != null) for (var upload : list) upload.material(material);
    }

    private static void reject(String reason) { rejected.merge(reason, 1, Integer::sum); }

    public static List<Upload> uploads() { return List.copyOf(frameUploads); }
    public static Map<String, Integer> rejected() { return Map.copyOf(rejected); }

    private static void report() {
        long now = System.nanoTime();
        if (now - lastReport < 2_000_000_000L) return;
        lastReport = now;
        if (!frameUploads.isEmpty() || !rejected.isEmpty()) {
            org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info(
                    "[RT][particles] vanilla PARTICLE uploads={} vertices={} rejected={} source=StagedVertexBuffer GPUcopy=vanilla range CPU receipt; GPU hit counters are shader-only",
                    frameUploads.size(), frameUploads.stream().mapToInt(u -> u.layout().vertexCount()).sum(), rejected);
        }
    }

    private ParticleCapture() {}
}
