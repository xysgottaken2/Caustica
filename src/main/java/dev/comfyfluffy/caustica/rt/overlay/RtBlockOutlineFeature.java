package dev.comfyfluffy.caustica.rt.overlay;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;

import it.unimi.dsi.fastutil.floats.FloatArrayList;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.BlockOutlineRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

/**
 * The targeted block's wireframe outline: a real {@link VoxelShape} edge list (not just a full-cube
 * approximation), raster full-res post-upscale, occluded per-fragment via an inline {@code rayQueryEXT}
 * test against the world TLAS (see {@code block_outline.frag}) instead of a depth buffer — RT's own
 * {@code gDepth} is at DLSS-RR's internal render resolution, not this pass's full display resolution, and
 * outline pixels need to sit exactly on the depth surface.
 *
 * <p>Reads {@code Minecraft.gameRenderer.gameRenderState().levelRenderState.blockOutlineRenderState}
 * directly — populated every frame by vanilla's own {@code LevelExtractor.extractBlockOutline}, which runs
 * from {@code GameRenderer.extract()} independently of {@code LevelRenderer.render()} (permanently
 * cancelled under {@code cancelVanillaWorld}), the same reason entity/camera state already worked for the
 * glow-outline and name-tag features.
 *
 * <p>A native {@code LINE_LIST} draw, real width via the device's {@code wideLines} feature +
 * {@code vkCmdSetLineWidth} (see {@link RtDeviceBringup#wideLinesEnabled()}/{@link
 * RtDeviceBringup#maxLineWidth()}) — clamped to whatever the device actually supports (Vulkan mandates
 * exactly 1.0 without the feature, so this degrades gracefully rather than failing). A two-pass screen-space
 * quad + coverage-mask approach was tried first (real geometry, no device-feature dependency, correct
 * mitred joints) but was reverted as unnecessary complexity for what a native wide line already solves;
 * revisit that approach only if wideLines turns out inadequate (unsupported hardware, joint artifacts at
 * large widths, etc.) — see the memory note for what was tried.
 *
 * <p>Edge AA follows {@link RtGlowOutlineFeature}'s mask/composite split rather than drawing straight onto
 * {@code main}: the line list rasterizes at {@link RtDeviceBringup#overlayMsaaSamples()} into a transient
 * MSAA scratch attachment that dynamic rendering resolve-averages into a single-sample mask, then a tiny
 * composite pass alpha-blends that mask onto {@code main}. Since every line pixel is the same flat colour
 * (rgb = 0,0,0), per-sample coverage averages straight into a fractional alpha with no colour-bleed risk —
 * the occlusion {@code discard} in {@code block_outline.frag} still runs once per fragment (not per sample,
 * no {@code sampleShading}), so occlusion itself stays pixel-rate; only the silhouette edges get antialiased.
 */
final class RtBlockOutlineFeature implements RtOverlayFeature {
    // mat4 curViewProj (0, 64B) + vec3 camOffset (64, padded to 16B) + vec4 color (80, 16B) = 96B.
    private static final int PUSH_BYTES = 96;
    // Vanilla's default (non-high-contrast) outline colour: ARGB.black(102) ~= rgba(0,0,0,0.4).
    private static final float OUTLINE_ALPHA = 102f / 255f;
    // Reference thickness at REFERENCE_HEIGHT display pixels; record() scales this by the actual display
    // height (then clamps to the device's max) so the line reads as the same relative thickness regardless
    // of window/render resolution — a fixed pixel width looks disproportionately thick on a smaller screen.
    private static final float LINE_WIDTH_PX_AT_REFERENCE = 2.0f;
    private static final float REFERENCE_HEIGHT = 1080f;

    private RtContext ctxRef;
    private RtOverlayPipelines.Pipeline pipeline;
    private RtOverlayPipelines.AccelStructureSet accelSet;
    private RtOverlayPipelines.Pipeline compositePipeline;
    private RtOverlayPipelines.StorageImageSet compositeSet;
    private RtImage msaaImage;
    private RtImage resolvedMask;

    private final Matrix4f viewProj = new Matrix4f();
    private RtBuffer vbo;
    private int vertexCount;
    private long boundSet;

    @Override
    public boolean prepare(RtContext ctx, RtOverlayFramePool pool, RtGpuExecutor.GraphicsUse graphicsUse,
                           int width, int height) {
        if (!CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED.value()) {
            return false;
        }
        RtTerrain terrain = RtTerrain.currentOrNull();
        if (terrain == null) {
            return false;
        }
        long tlas = RtComposite.INSTANCE.currentTlasHandle();
        if (tlas == 0L) {
            return false;
        }
        var gameRenderState = Minecraft.getInstance().gameRenderer.gameRenderState();
        // Vanilla's own gate (GameRenderer.shouldRenderBlockOutline) is a *parameter* it computes right
        // before the (in our setup, permanently cancelled) LevelRenderer.render() call — never stored
        // anywhere we could otherwise read it — so extraction populates blockOutlineRenderState regardless
        // of F1/game-mode. guiRenderState.isHudHidden is independently tracked in GameRenderState (F1);
        // the rest of shouldRenderBlockOutline's logic (camera-entity/adventure-mode/spectator-menu
        // narrowing) is replicated in shouldRenderForGameMode below.
        if (gameRenderState.guiRenderState.isHudHidden) {
            return false;
        }
        BlockOutlineRenderState state = gameRenderState.levelRenderState.blockOutlineRenderState;
        if (state == null) {
            return false;
        }
        BlockPos pos = state.pos();
        if (!shouldRenderForGameMode(pos)) {
            return false;
        }
        VoxelShape shape = state.shape();
        float baseX = pos.getX() - terrain.blockX;
        float baseY = pos.getY() - terrain.blockY;
        float baseZ = pos.getZ() - terrain.blockZ;

        FloatArrayList verts = new FloatArrayList(72);
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            verts.add((float) (baseX + x1));
            verts.add((float) (baseY + y1));
            verts.add((float) (baseZ + z1));
            verts.add((float) (baseX + x2));
            verts.add((float) (baseY + y2));
            verts.add((float) (baseZ + z2));
        });
        vertexCount = verts.size() / 3;
        if (vertexCount == 0) {
            return false;
        }

        ensureResources(ctx, width, height);
        float[] data = verts.toFloatArray();
        vbo = pool.acquireVertex(ctx, (long) data.length * Float.BYTES, "block outline vbo");
        MemoryUtil.memFloatBuffer(vbo.mapped, data.length).put(data);
        vbo.flush(0L, (long) data.length * Float.BYTES);

        viewProj.set(RtComposite.INSTANCE.currentViewProjection());
        boundSet = accelSet.bind(ctx, tlas, graphicsUse);
        return true;
    }

    /**
     * Replicates {@code GameRenderer.shouldRenderBlockOutline()}'s camera-entity/adventure-mode/
     * spectator-menu narrowing (the one piece of vanilla's gate not already covered by the F1 check above).
     * Survival/creative (full build permission) always shows; adventure mode only shows when the held item
     * can actually break or place on this specific block; spectator only shows when the block has an
     * interactable menu (chests, furnaces, etc. — so a spectator can see what they're allowed to open);
     * spectating a non-player entity (not just flying as a spectator) shows nothing at all, matching vanilla.
     */
    private static boolean shouldRenderForGameMode(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        Entity cameraEntity = mc.getCameraEntity();
        if (!(cameraEntity instanceof Player player)) {
            return false;
        }
        if (player.getAbilities().mayBuild) {
            return true;
        }
        Level level = mc.level;
        if (level == null) {
            return false;
        }
        BlockState blockState = level.getBlockState(pos);
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return blockState.getMenuProvider(level, pos) != null;
        }
        ItemStack itemStack = player.getMainHandItem();
        BlockInWorld blockInWorld = new BlockInWorld(level, pos, false);
        return !itemStack.isEmpty()
                && (itemStack.canBreakBlockInAdventureMode(blockInWorld) || itemStack.canPlaceOnBlockInAdventureMode(blockInWorld));
    }

    private void ensureResources(RtContext ctx, int width, int height) {
        this.ctxRef = ctx;
        if (pipeline == null) {
            accelSet = RtOverlayPipelines.accelStructureSet(ctx, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, "block outline");
            pipeline = new RtOverlayPipelines.Spec("block_outline.vert.spv", "block_outline.frag.spv")
                    .vertex(RtOverlayPipelines.VertexFormat.POSITION)
                    .topology(VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST)
                    // NONE (straight write), not ALPHA: ALPHA's blend factors (srcAlpha=ZERO, dstAlpha=ONE)
                    // preserve the DESTINATION's alpha, which was fine composited straight onto opaque `main`
                    // (only RGB mattered) but is wrong now that this pass writes into a transparent scratch
                    // mask whose alpha IS the coverage signal the composite pass reads — ALPHA here would
                    // leave every resolved pixel's alpha stuck at the clear value (0), invisible outline.
                    .blend(RtOverlayPipelines.Blend.NONE)
                    .attachment(RtWorldOverlay.TARGET_FORMAT)
                    .samples(RtDeviceBringup.overlayMsaaSamples())
                    .push(PUSH_BYTES, VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT)
                    .descriptorSetLayout(accelSet.layout)
                    .build(ctx, "block outline");
            compositeSet = RtOverlayPipelines.storageImageSet(ctx, 1, VK10.VK_SHADER_STAGE_FRAGMENT_BIT, "block outline composite");
            compositePipeline = new RtOverlayPipelines.Spec("overlay_fullscreen_triangle.vert.spv", "overlay_passthrough_composite.frag.spv")
                    .blend(RtOverlayPipelines.Blend.ALPHA)
                    .attachment(RtWorldOverlay.TARGET_FORMAT)
                    .descriptorSetLayout(compositeSet.layout)
                    .build(ctx, "block outline composite");
        }
        if (msaaImage == null || msaaImage.width != width || msaaImage.height != height) {
            if (msaaImage != null) {
                msaaImage.destroy();
            }
            msaaImage = ctx.createTransientMsaaColorImage(width, height, RtWorldOverlay.TARGET_FORMAT,
                    RtDeviceBringup.overlayMsaaSamples(), "block outline msaa " + width + "x" + height);
        }
        if (resolvedMask == null || resolvedMask.width != width || resolvedMask.height != height) {
            if (resolvedMask != null) {
                resolvedMask.destroy();
            }
            resolvedMask = ctx.createStorageImage(width, height, RtWorldOverlay.TARGET_FORMAT,
                    "block outline resolved mask " + width + "x" + height, VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        }
        compositeSet.bind(ctx, 0, resolvedMask.view);
    }

    @Override
    public void record(VkCommandBuffer cmd, long targetView, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctxRef, cmd, "block outline mask")) {
                RtWorldOverlay.beginMsaaColorRendering(cmd, stack, msaaImage.view, resolvedMask.view, width, height);
                VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle);
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.layout, 0,
                        stack.longs(boundSet), null);
                VK10.vkCmdBindVertexBuffers(cmd, 0, stack.longs(vbo.handle), stack.longs(0L));
                float desiredWidthPx = LINE_WIDTH_PX_AT_REFERENCE * (height / REFERENCE_HEIGHT);
                float lineWidth = RtDeviceBringup.wideLinesEnabled()
                        ? Math.min(desiredWidthPx, RtDeviceBringup.maxLineWidth()) : 1.0f;
                VK10.vkCmdSetLineWidth(cmd, lineWidth);
                ByteBuffer push = stack.malloc(PUSH_BYTES);
                viewProj.get(0, push);
                RtEntities entities = RtEntities.INSTANCE;
                push.putFloat(64, entities.glowCamOffsetX()).putFloat(68, entities.glowCamOffsetY())
                        .putFloat(72, entities.glowCamOffsetZ());
                push.putFloat(80, 0f).putFloat(84, 0f).putFloat(88, 0f).putFloat(92, OUTLINE_ALPHA);
                VK10.vkCmdPushConstants(cmd, pipeline.layout,
                        VK10.VK_SHADER_STAGE_VERTEX_BIT | VK10.VK_SHADER_STAGE_FRAGMENT_BIT, 0, push);
                VK10.vkCmdDraw(cmd, vertexCount, 1, 0, 0);
                RtWorldOverlay.endRendering(cmd);
            }

            VulkanCommandEncoder.memoryBarrier(cmd, stack); // resolved mask writes visible to the composite's reads

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctxRef, cmd, "block outline composite")) {
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
        if (ctxRef == null) {
            return;
        }
        if (pipeline != null) {
            pipeline.destroy(ctxRef.vk());
            pipeline = null;
        }
        if (accelSet != null) {
            accelSet.destroy(ctxRef.vk());
            accelSet = null;
        }
        if (compositePipeline != null) {
            compositePipeline.destroy(ctxRef.vk());
            compositePipeline = null;
        }
        if (compositeSet != null) {
            compositeSet.destroy(ctxRef.vk());
            compositeSet = null;
        }
        if (msaaImage != null) {
            msaaImage.destroy();
            msaaImage = null;
        }
        if (resolvedMask != null) {
            resolvedMask.destroy();
            resolvedMask = null;
        }
        ctxRef = null;
    }
}
