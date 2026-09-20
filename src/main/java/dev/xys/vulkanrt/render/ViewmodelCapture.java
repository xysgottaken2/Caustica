package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Isolated first-person pass receipt.
 *
 * Minecraft 26.3 does not submit the hands through EntityRenderDispatcher. The
 * hand renderer submits ItemStackRenderState and arm/model nodes to the
 * screen-effects StagedVertexBuffer from GameRenderer.renderItemInHand. This
 * class observes that exact path and its vanilla buffer/draw receipts without
 * turning the viewmodel into an entity or adding it to the world TLAS.
 *
 * The RT world pass is recorded before the vanilla "Item in hand" render pass.
 * Therefore vanilla remains the authoritative compositor for the viewmodel in
 * this milestone. These receipts are intentionally labelled CPU/enqueue
 * diagnostics; they are not RT intersection or GPU readback evidence.
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
    }

    public static void state(FirstPersonHandsAndItemsRenderState state) {
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
        if (active) builders++;
    }

    public static void upload(StagedVertexBuffer owner, GpuBufferSlice source, GpuBufferSlice target) {
        if (!active) return;
        uploads++;
        uploadedBytes += Math.max(0L, source.length());
    }

    public static void prepared(Object renderType, PreparedRenderType prepared) {
        // The prepared object is retained by vanilla; no second material or atlas is made here.
    }

    public static void draw(PreparedRenderType material, StagedVertexBuffer.ExecuteInfo info) {
        if (active) drawReceipts++;
    }

    public static void end() {
        if (!active) return;
        active = false;
        if (snapshot != null && drawReceipts > 0) {
            LOG.debug("[RT][viewmodel] vanilla first-person pass: main={} off={} selection={} builders={} uploads={} bytes={} drawReceipts={} | CPU/enqueue receipt, not RT hit; world TLAS unchanged",
                    snapshot.mainHand(), snapshot.offHand(), snapshot.handSelection(), builders, uploads,
                    uploadedBytes, drawReceipts);
        }
    }

    private ViewmodelCapture() {}
}
