package dev.xys.vulkanrt.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import dev.xys.vulkanrt.mixin.EntityDrawAccessor;
import dev.xys.vulkanrt.mixin.EntityStagedAccessor;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures the real first-person StagedVertexBuffer ranges emitted by
 * GameRenderer.renderItemInHand. It never retessellates or reads the buffer back:
 * the later RT manager copies the same GPU upload into an RT-build buffer.
 */
public final class ViewmodelCapture {
    private static final Logger LOG = LoggerFactory.getLogger("native_vulkan_rt");
    private static boolean active;
    private static Snapshot snapshot;
    private static int builders;
    private static int uploads;
    private static int drawReceipts;
    private static int submittedItems;
    private static long uploadedBytes;
    private static final Map<BufferBuilder, RenderType> types = new IdentityHashMap<>();
    private static final Map<RenderType, PreparedRenderType> prepared = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.Draw, BufferBuilder> drawBuilders = new IdentityHashMap<>();
    private static final Map<StagedVertexBuffer.Draw, PreparedRenderType> drawMaterials = new IdentityHashMap<>();
    private static final java.util.Set<StagedVertexBuffer.Draw> uploadedDraws = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public record Upload(StagedVertexBuffer.Draw draw, VulkanGpuBuffer source, long offset,
                         SectionGeometryLayout layout, PreparedRenderType material) {}

    public record Snapshot(
            String mainHand,
            String offHand,
            String handSelection,
            float mainHandHeight,
            float offHandHeight,
            float viewXRot,
            float viewYRot,
            float xBob,
            float yBob,
            boolean scoping
    ) {}

    public static void begin() {
        active = true;
        snapshot = null;
        builders = uploads = drawReceipts = submittedItems = 0;
        uploadedBytes = 0L;
        types.clear();
        prepared.clear();
        drawBuilders.clear();
        drawMaterials.clear();
        uploadedDraws.clear();
    }

    public static void state(net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState state) {
        if (!active || state == null) return;
        snapshot = new Snapshot(
                state.mainHandItem.toString(), state.offHandItem.toString(),
                String.valueOf(state.handRenderSelection), state.mainHandHeight,
                state.offHandHeight, state.viewXRot, state.viewYRot,
                state.xBob, state.yBob, state.isScoping);
        submittedItems++;
    }

    public static boolean active() { return active; }

    public static void builder(StagedVertexBuffer.Draw draw, VertexConsumer vertex) {
        if (!active || !(vertex instanceof BufferBuilder b)) return;
        builders++;
        drawBuilders.put(draw, b);
        RenderType type = types.get(b);
        if (type != null) drawMaterials.put(draw, prepared.get(type));
    }

    public static void renderType(VertexConsumer vertex, RenderType type) {
        if (active && vertex instanceof BufferBuilder b) {
            types.put(b, type);
            PreparedRenderType value = prepared.get(type);
            if (value != null) drawBuilders.forEach((draw, builder) -> {
                if (builder == b) drawMaterials.put(draw, value);
            });
        }
    }

    public static void upload(StagedVertexBuffer owner, GpuBufferSlice source, GpuBufferSlice target) {
        if (!active || source.offset() != 0 || !(source.buffer() instanceof VulkanGpuBuffer vk)) return;
        var draws = ((EntityStagedAccessor)(Object) owner).nativeVulkanRt$draws();
        var uploadsNow = new ArrayList<Upload>();
        for (var draw : draws) {
            if (uploadedDraws.contains(draw) || !drawBuilders.containsKey(draw)) continue;
            PreparedRenderType material = drawMaterials.get(draw);
            if (material == null) {
                RenderType type = types.get(drawBuilders.get(draw));
                material = prepared.get(type);
            }
            if (material == null) continue;
            var access = (EntityDrawAccessor)(Object) draw;
            if (access.nativeVulkanRt$topology() != com.mojang.renderpearl.api.pipeline.PrimitiveTopology.QUADS) continue;
            int vertexCount = access.nativeVulkanRt$vertexCount();
            if (vertexCount < 4 || (vertexCount & 3) != 0) continue;
            SectionGeometryLayout layout;
            try {
                layout = SectionGeometryLayout.solidQuads(access.nativeVulkanRt$format(), vertexCount / 4 * 6);
            } catch (RuntimeException unsupportedFormat) {
                LOG.debug("[RT][viewmodel] skipped vanilla hand draw with unsupported vertex layout: {}", unsupportedFormat.getMessage());
                continue;
            }
            long offset = access.nativeVulkanRt$vertexOffset();
            if (offset < 0 || offset > source.length() - layout.vertexBytes()) continue;
            uploadedDraws.add(draw);
            uploadsNow.add(new Upload(draw, vk, offset, layout, material));
            uploads++;
            uploadedBytes += layout.vertexBytes();
        }
        if (!uploadsNow.isEmpty()) RayTracingRenderer.uploadViewmodel(uploadsNow);
    }

    public static void prepared(Object renderType, PreparedRenderType value) {
        if (active && renderType instanceof RenderType type) {
            prepared.put(type, value);
        }
    }

    public static void draw(PreparedRenderType material, StagedVertexBuffer.ExecuteInfo info) {
        if (active) drawReceipts++;
    }

    public static void end() {
        if (!active) return;
        active = false;
        if (snapshot != null && (drawReceipts > 0 || uploads > 0)) {
            LOG.debug("[RT][viewmodel] vanilla first-person GPU ranges: main={} off={} selection={} builders={} uploads={} bytes={} drawReceipts={} | copied from vanilla staged upload; no world shadow mask",
                    snapshot.mainHand(), snapshot.offHand(), snapshot.handSelection(), builders, uploads,
                    uploadedBytes, drawReceipts);
        }
    }

    private ViewmodelCapture() {}
}
