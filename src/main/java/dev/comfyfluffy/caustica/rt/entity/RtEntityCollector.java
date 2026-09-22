package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.ModelPartAccessor;
import dev.comfyfluffy.caustica.mixin.RenderSetupAccessor;
import dev.comfyfluffy.caustica.mixin.RenderTypeAccessor;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.sprite.FabricTextureAtlas;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.font.TextRenderable;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.util.RandomSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.comfyfluffy.caustica.rt.material.RtMaterials;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A {@link SubmitNodeCollector} that intercepts supported entity submissions and renders them straight
 * into an {@link RtEntityCapture}, reusing vanilla's posing and animation. Models, items, blocks, text,
 * custom quads, and line primitives are captured; screen-space effects and debug geometry are ignored.
 *
 * <p>Driven once per entity per frame: {@link #begin} sets the capture, then {@code
 * EntityRenderDispatcher.submit} fans out into {@code submitModel} here. Reused across entities.
 */
public final class RtEntityCollector implements SubmitNodeCollector {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final Predicate<Direction> NEVER_CULL = direction -> false;
    // Vanilla leash constants (LeashFeatureRenderer.LEASH_RENDER_STEPS / LEASH_WIDTH).
    private static final int LEASH_STEPS = 24;
    private static final float LEASH_WIDTH = 0.05f;
    // Shared all-zero UV quad for untextured geometry (leash/line ribbons on the white slot).
    private static final float[] ZERO_UV = new float[4];
    // Full-sprite UVs for the flame cube faces (remapped into the fire sprite's atlas region by the
    // capture's uvRemap, which submitFlame sets). v=0 is the top of the texture (flame tips), so the
    // quad's top samples v=0 and its bottom samples v=1 — the flames point up in world space.
    private static final float[] FLAME_UV_U = {0f, 1f, 1f, 0f};
    private static final float[] FLAME_UV_V = {1f, 1f, 0f, 0f};
    // Vanilla renders the entity flame with LightTexture.FULL_BRIGHT; the RT equivalent is a strong
    // per-prim self-emission (radiance = albedo * emission * the dynamic light scale).
    private static final float FLAME_EMISSION = 1.5f;
    // The entity fire overlay uses the block atlas's animated fire_0 sprite.
    private static final Identifier FIRE_SPRITE = Identifier.withDefaultNamespace("block/fire_0");
    // Portal sprites that may reach the entity path (block entities, held/displayed blocks).
    private static final Identifier NETHER_PORTAL_SPRITE =
            Identifier.withDefaultNamespace("block/nether_portal");
    private static final Identifier END_PORTAL_SPRITE =
            Identifier.withDefaultNamespace("block/end_portal");
    // TerrainPrim.flags values (mirror world_common.slang / RtTerrainMesher).
    private static final int TERRAIN_PRIM_PORTAL_NETHER = 2;
    private static final int TERRAIN_PRIM_PORTAL_END = 4;
    // Identity pose fallback for submitFlame when the caller hands over an empty PoseStack
    // (read-only; transformPosition never mutates it).
    private static final Matrix4f IDENTITY_POSE = new Matrix4f();
    // Enchantment/foil glint overlays are special-captured exactly like the portal surfaces: the
    // collector tags the submission (PRIM_FLAG_GLINT) and world.rchit shades it with a dedicated
    // branch — an animated, self-lit shimmer — instead of a scene-lit texture. Coverage is the glint
    // texture's own alpha (vanilla's glint shader discards texels below 0.1), so the armour/body shows
    // through where there is no streak and the shimmer is solid where there is one. The old
    // uniform-0.28-opacity stochastic shell is gone.
    // Push glint decals just above the armour surface so the any-hit shader sees the overlay first; when
    // its texture alpha passes the ray through, traversal continues to the base geometry behind it.
    private static final int ENCHANTMENT_GLINT_ORDER = 1;

    // Bindless texture slot of the most recent non-glint model submission (the armour layer, in the
    // vanilla armour path: EquipmentLayerRenderer submits the armour model and, when the item has
    // foil, the SAME model with armorEntityGlint() immediately after). The glint submission copies
    // this into capture.currentGlintArmorSlot so world.rahit can gate the shimmer to the armour
    // surface (vanilla's EQUAL-depth semantics) instead of painting the bare body.
    private int lastArmorTexSlot;

    private RtEntityCapture capture;
    private boolean profileDynamicEntity;
    private final RtEntityCapture parityCapture = new RtEntityCapture();
    private final RtCuboidEmitter cuboidEmitter = new RtCuboidEmitter();
    // Lazy FRAPI emitter used for contained and moving block models. Its callback reads the synchronous
    // context fields below; the entity collector itself is render-thread confined.
    private QuadEmitter blockQuadEmitter;
    private Matrix4f emittedBlockPose;
    private BlockAndTintGetter emittedBlockView;
    private BlockState emittedBlockState;
    private BlockPos emittedBlockPos;
    private float emittedBlockOffsetX, emittedBlockOffsetY, emittedBlockOffsetZ;
    private final RandomSource emittedBlockRandom = RandomSource.create();
    // Set by order(int) for the very next submitModel call (banner/shield pattern-layer stacking), then
    // consumed. Baked-quad paths (addQuad) don't use ordering and always reset the capture's order to 0.
    private int pendingOrder;
    private final RtTextVertexConsumer textVertexConsumer = new RtTextVertexConsumer();
    private final TextGlyphVisitor textGlyphVisitor = new TextGlyphVisitor();
    private final RtCustomQuadVertexConsumer customQuadVertexConsumer = new RtCustomQuadVertexConsumer();
    private final RtLineVertexConsumer lineVertexConsumer = new RtLineVertexConsumer();
    // Staging for Fabric Renderer API mesh quads (addMeshQuad); reused across quads, single-threaded.
    private final float[] meshX = new float[4], meshY = new float[4], meshZ = new float[4];
    private final float[] meshU = new float[4], meshV = new float[4];
    private final Vector3f meshPos = new Vector3f();
    // The fire sprite for burning entities, cached per resource epoch (cleared by clearCaches).
    private TextureAtlasSprite fireSprite;
    private boolean loggedFlameSpriteFailure;
    // Whether vanilla's dispatcher submitted the flame overlay for the entity currently being
    // captured (set by submitFlame, reset per entity in begin). RtEntities uses this to emit the
    // flame itself when the dispatcher's gate didn't fire for the RT capture.
    private boolean flameSubmittedThisEntity;
    // Vanilla already computes the Glowing-effect outline colour (opaque team colour, or 0 when not
    // glowing) per submitModel call — see EntityRenderer.extractCommon's outlineColor. Every submitModel
    // call for one entity carries the same value, so the last non-zero one seen this entity is enough.
    private int outlineColor;

    /**
     * Point the collector at the capture buffer for the next {@code dispatcher.submit}, resetting the
     * outline colour for the entity about to be captured. The end-of-entity {@code begin(null)} detach
     * call must NOT reset it — {@link RtEntities} reads {@link #outlineColor()} after that detach.
     */
    public void begin(RtEntityCapture capture, boolean profileDynamicEntity) {
        this.capture = capture;
        this.profileDynamicEntity = capture != null && profileDynamicEntity;
        if (capture != null) {
            this.outlineColor = 0;
            this.pendingOrder = 0;
            this.flameSubmittedThisEntity = false;
            this.lastArmorTexSlot = 0;
        }
    }

    /** This entity's Glowing-effect outline colour (opaque ARGB), or 0 if it isn't glowing. */
    public int outlineColor() {
        return outlineColor;
    }

    /** Release model/resource-pack-owned CPU caches after reload or RT shutdown. */
    public void clearCaches() {
        cuboidEmitter.clear();
        blockQuadEmitter = null;
        fireSprite = null;
        loggedFlameSpriteFailure = false;
    }

    /**
     * The block atlas's animated fire sprite (never null once the atlas is loaded; null on failure).
     * Uses {@link AtlasIds#BLOCKS} — the same key RtTerrain's working sprite-finder uses — because
     * {@code getAtlasOrThrow(TextureAtlas.LOCATION_BLOCKS)} (the legacy identifier) can throw on
     * 26.x, which silently killed the whole flame overlay: submitFlame bailed out with no geometry
     * and the fallback saw the "already submitted" flag.
     */
    private TextureAtlasSprite fireSprite() {
        if (fireSprite == null) {
            try {
                fireSprite = Minecraft.getInstance().getAtlasManager()
                        .getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(FIRE_SPRITE);
            } catch (Throwable t) {
                fireSprite = null; // atlas not ready yet — retry next submission
                if (!loggedFlameSpriteFailure) {
                    loggedFlameSpriteFailure = true;
                    CausticaMod.LOGGER.warn("RT flame sprite resolution failed (fire overlay disabled)", t);
                }
            }
        }
        return fireSprite;
    }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType,
                                int lightCoords, int overlayCoords, int tintedColor, UvMapping uvMapping,
                                int outlineColor) {
        if (capture == null) {
            return;
        }
        if (outlineColor != 0) {
            this.outlineColor = outlineColor;
        }
        boolean glint = isGlint(renderType);
        // Vanilla gives the base-colour banner pass order 0 even though it is drawn after an uncoloured,
        // exactly coplanar cloth pass. Move every banner-pattern pass out one rank: base colour becomes 1,
        // and the explicitly ordered emblem layers become 2+. Otherwise the BVH can select white cloth.
        // Glint is another coplanar overlay; offset it so alpha pass-through reaches the base armour.
        capture.currentOrder = pendingOrder + (isBannerPattern(renderType) ? 1 : 0)
                + (glint ? ENCHANTMENT_GLINT_ORDER : 0);
        pendingOrder = 0;
        // Glint is never faked translucent: its texture alpha gates coverage (vanilla's own discard), so
        // the prim keeps full opacity and the ray either shades the shimmer or passes to the armour.
        capture.currentOpacity = 1.0f;
        // Reset the per-submission tags (portal + weather/glint): they are set only by the paths that own
        // them, so an ordinary model after a tagged layer in the same capture cannot inherit them.
        capture.currentPortalFlags = 0;
        capture.currentPrimFlags = glint ? RtEntityCapture.PRIM_FLAG_GLINT : 0;
        // The armour layer's texture (submitted immediately before this) gates the glint to the armour
        // surface — see lastArmorTexSlot. Non-glint submissions carry no armour context.
        capture.currentGlintArmorSlot = glint ? lastArmorTexSlot : 0;
        if (profileDynamicEntity) {
            RtFrameStats.FRAME.count("entityModelSubmissions", 1);
        }
        long materialStart = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        boolean stochasticAlpha = !glint && isTranslucent(renderType);
        capture.currentAlphaBucket = glint ? RtAccel.ENTITY_BUCKET_ANY_HIT : alphaBucket(renderType);
        // End-portal block entities draw their night-sky quad through this path; tag it so
        // world.rchit shades it procedurally instead of sampling the flat end_portal texture.
        if (isEndPortal(renderType)) {
            tagPortalSubmission(capture, TERRAIN_PRIM_PORTAL_END);
        }
        // Resolve this submission's texture to a bindless slot; the capture stamps it on every prim.
        // Block-entity models (chests/signs/beds) texture from an atlas SPRITE: use that atlas + remap
        // the ModelPart 0..1 UVs into the sprite's region. Mobs use a full texture (sprite == null).
        // 26.3 passes the sprite's UV mapping instead of the sprite itself; the mapping still IS the
        // atlas sprite for block-entity models (TextureAtlasSprite implements UvMapping).
        TextureAtlasSprite sprite = uvMapping instanceof TextureAtlasSprite tas ? tas : null;
        try {
            capture.currentMaterialId = RtMaterialRegistry.INSTANCE.entityFallbackId(stochasticAlpha);
            if (sprite != null) {
                capture.setUvRemap(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
                if (TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
                    // A block entity drawing from the block atlas (rare) → reuse the terrain _s/_n atlases.
                    capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForAtlas(sprite.atlasLocation());
                    setSpriteMaterial(sprite, stochasticAlpha);
                } else {
                    // Dedicated block-entity atlas: albedo remains atlas-bound, while the immutable
                    // canonical texels were pack-compiled. Appending the first-seen sprite header only
                    // records this atlas's UV rectangle; it never mutates an existing material ID.
                    capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForAtlas(sprite.atlasLocation());
                    capture.currentMaterialId = RtEntityTextures.entityPbr()
                            ? RtMaterialRegistry.INSTANCE.resolveEntitySprite(sprite, stochasticAlpha)
                            : RtMaterialRegistry.INSTANCE.entityFallbackId(stochasticAlpha);
                }
            } else {
                // Mobs use full per-type textures. Their authored _s/_n maps were decoded into canonical
                // pages during resource-pack load, so capture stores only the stable material ID.
                capture.currentTexSlot = RtEntityTextures.INSTANCE.slotFor(renderType);
                capture.currentMaterialId = RtEntityTextures.INSTANCE.materialIdFor(renderType, stochasticAlpha);
                capture.clearUvRemap();
            }
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.submit.material", materialStart);
        }
        // Remember this submission's texture for the next glint submission (the armour layer is always
        // followed by its glint in vanilla's EquipmentLayerRenderer). Glint submissions themselves are
        // skipped so lastArmorTexSlot keeps pointing at the armour texture beneath them.
        if (!glint) {
            lastArmorTexSlot = capture.currentTexSlot;
        }
        // Pose the model from its render state (idempotent re-pose; mirrors what the renderer does for
        // its feature layers), then render the posed parts into the capture. renderToBuffer applies the
        // PoseStack to every vertex/normal, so the capture receives world-/camera-relative geometry.
        long setupStart = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            model.setupAnim(state);
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.submit.setupAnim", setupStart);
        }
        if (profileDynamicEntity && RtFrameStats.enabled()) {
            long metricsStart = RtFrameStats.FRAME.startStage();
            try {
                RtFrameStats.FRAME.count("entityCuboids", countVisibleCuboids(model.root()));
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.submit.metrics", metricsStart);
            }
        }
        int color = tintedColor == 0 ? -1 : tintedColor; // vanilla uses 0 as the no-tint sentinel in some submit paths
        int vertStart = capture.verts.size();
        int idxStart = capture.idx.size();
        int uvStart = capture.uvList.size();
        int primStart = capture.prim.size();

        // Water mask detection: boat's water cutout (water_patch) uses RenderType waterMask - skip it entirely
        // to avoid black rectangle covering the real wood floor (depth/stencil mask for vanilla).
        if (isWaterMask(renderType)) {
            return;
        }

        RtCuboidEmitter.ModelTemplate directTemplate = cuboidEmitter.prepare(model);
        // Boat fix: boat models (BoatModel, ChestBoatModel, RaftModel) have interior faces that the cuboid
        // emitter does not capture with correct UVs / normals (interior bottom becomes black). Force fallback
        // path (renderToBuffer) which uses the actual ModelPart vertex data with proper UVs and normals.
        // Also hide water_patch sub-mesh that is the water mask cutout (black rectangle).
        String modelName = model.getClass().getName().toLowerCase();
        boolean isBoatModel = modelName.contains("boat") || modelName.contains("raft");
        java.util.ArrayList<ModelPart> hiddenWaterParts = null;
        java.util.ArrayList<Boolean> hiddenWaterPrevSkip = null;
        if (isBoatModel) {
            directTemplate = null;
            // Collect water_patch parts (name contains "water") and hide them for this capture
            java.util.ArrayList<ModelPart> waterParts = new java.util.ArrayList<>();
            collectWaterPatchParts(model.root(), waterParts);
            if (!waterParts.isEmpty()) {
                hiddenWaterParts = waterParts;
                hiddenWaterPrevSkip = new java.util.ArrayList<>(waterParts.size());
                for (ModelPart wp : waterParts) {
                    hiddenWaterPrevSkip.add(wp.skipDraw);
                    wp.skipDraw = true;
                }
            }
        }
        long directCubeCounts = 0L;
        long drawStart = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            if (directTemplate != null) {
                directCubeCounts = cuboidEmitter.emit(directTemplate, poseStack, capture, color);
            } else {
                model.renderToBuffer(poseStack, capture, lightCoords, overlayCoords, color);
            }
        } finally {
            RtFrameStats.FRAME.endStage(directTemplate != null
                    ? "entity.capture.submit.modelDraw.direct"
                    : "entity.capture.submit.modelDraw.fallback", drawStart);
            RtFrameStats.FRAME.endStage("entity.capture.submit.modelDraw", drawStart);
            // Restore water mask parts that were hidden to avoid black rectangle
            if (hiddenWaterParts != null) {
                for (int i = 0; i < hiddenWaterParts.size(); i++) {
                    hiddenWaterParts.get(i).skipDraw = hiddenWaterPrevSkip.get(i);
                }
            }
        }
        int addedVertices = (capture.verts.size() - vertStart) / 3;
        int addedQuads = (capture.idx.size() - idxStart) / 6;
        if (profileDynamicEntity) {
            RtFrameStats.FRAME.count("entityModelVertices", addedVertices);
            RtFrameStats.FRAME.count("entityModelQuads", addedQuads);
            RtFrameStats.FRAME.count(directTemplate != null ? "entityDirectSubmissions" : "entityDirectFallbacks", 1);
            if (directTemplate != null) {
                RtFrameStats.FRAME.count("entityDirectVertices", addedVertices);
                RtFrameStats.FRAME.count("entityDirectQuads", addedQuads);
                RtFrameStats.FRAME.count("entitySpecializedCuboids", directCubeCounts >>> 32);
                RtFrameStats.FRAME.count("entityGenericCuboids", directCubeCounts & 0xffffffffL);
            }
        }

        if (CausticaConfig.Rt.Entities.CAPTURE_PARITY.value()) {
            parityCapture.reset(addedVertices);
            capture.copySubmissionStateTo(parityCapture);
            long parityStart = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
            try {
                model.renderToBuffer(poseStack, parityCapture, lightCoords, overlayCoords, color);
                capture.assertSubmissionBitwiseIdentical(vertStart, idxStart, uvStart, primStart,
                        parityCapture, "model " + model.getClass().getName());
                if (profileDynamicEntity) {
                    RtFrameStats.FRAME.count("entityParityChecks", 1);
                }
            } finally {
                RtFrameStats.FRAME.endStage("entity.capture.submit.parity", parityStart);
            }
        }
    }

    private static int countVisibleCuboids(ModelPart part) {
        if (!part.visible) {
            return 0;
        }
        ModelPartAccessor access = (ModelPartAccessor) (Object) part;
        int count = part.skipDraw ? 0 : access.caustica$cubes().size();
        for (ModelPart child : access.caustica$children().values()) {
            count += countVisibleCuboids(child);
        }
        return count;
    }

    @Override
    public OrderedSubmitNodeCollector order(int order) {
        // Single un-ordered capture sink reused synchronously (no queuing): the caller always issues the
        // very next submission immediately after requesting an order, so stashing it for that one
        // submitModel call (see there) is enough to recover banner/shield pattern-layer stacking.
        pendingOrder = order;
        return this;
    }

    @Override
    public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack,
                                           RenderType renderType, int lightCoords, int overlayCoords,
                                           int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        // Block-breaking stages over entity models are transient raster effects with no RT equivalent;
        // the model itself is captured through the normal submitModel path. Mirrors submitShadow.
    }

    /** Capture a list of baked quads (items / block models), each textured from its sprite's atlas. */
    private void addQuads(Matrix4f pose, List<BakedQuad> quads, int[] tintLayers) {
        int idxStart = capture.idx.size();
        long started = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            for (BakedQuad q : quads) {
                addQuad(pose, q, tintLayers);
            }
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.submit.bakedQuads", started);
            countBakedOutput(idxStart);
        }
    }

    private void countBakedOutput(int idxStart) {
        if (!profileDynamicEntity) {
            return;
        }
        int quads = (capture.idx.size() - idxStart) / 6;
        RtFrameStats.FRAME.count("entityBakedQuads", quads);
        RtFrameStats.FRAME.count("entityBakedVertices", (long) quads * 4L);
    }

    /** Capture one baked quad, resolving its atlas (block vs item) to a bindless slot stamped per-prim. */
    private void addQuad(Matrix4f pose, BakedQuad q, int[] tintLayers) {
        TextureAtlasSprite sprite = q.materialInfo().sprite();
        capture.currentTexSlot = sprite != null
                ? RtEntityTextures.INSTANCE.slotForAtlas(sprite.atlasLocation())
                : 0;
        // Baked item quads retain the block model's material layer. Mirror terrain's classification so
        // dropped and held translucent block items (glass, ice, etc.) use the thin-dielectric variant
        // instead of the opaque DEFAULT variant. No BlockState reaches submitItem, so the layer is the
        // authoritative semantic available here; glass-model roughness/IOR are profile-independent.
        boolean transmissive = q.materialInfo().layer() == ChunkSectionLayer.TRANSLUCENT;
        float emission = itemSpriteEmission(sprite);
        capture.currentAlphaBucket = alphaBucket(q.materialInfo().layer(), false);
        setSpriteMaterial(sprite, transmissive ? RtMaterials.Profile.GLASS : RtMaterials.Profile.DEFAULT,
                transmissive, false, emission > 0.0f);
        capture.currentOpacity = 1.0f;
        capture.currentOrder = 0; // baked-quad paths never stack decal layers
        capture.currentPortalFlags = 0; // per-submission; the tag below applies only to this quad
        capture.currentPrimFlags = 0; // glint never arrives through baked quads — drop any stale tag
        capture.currentGlintArmorSlot = 0; // no armour context on baked-quad paths
        // Held/displayed portal blocks (endermen, block displays in 26.1+) render as baked quads with
        // the portal sprite; tag them for the procedural portal branches.
        tagPortalSubmission(capture, portalFlagsForSprite(sprite));
        capture.addBakedQuad(pose, q, tintColor(q.materialInfo().tintIndex(), tintLayers), emission);
    }

    /** Resolve block-atlas geometry through the same immutable material snapshot as terrain. */
    private void setSpriteMaterial(TextureAtlasSprite sprite, boolean stochasticAlpha) {
        setSpriteMaterial(sprite, RtMaterials.Profile.DEFAULT, false, stochasticAlpha);
    }

    private void setSpriteMaterial(TextureAtlasSprite sprite, RtMaterials.Profile profile,
                                   boolean transmissive, boolean stochasticAlpha) {
        setSpriteMaterial(sprite, profile, transmissive, stochasticAlpha, false);
    }

    private void setSpriteMaterial(TextureAtlasSprite sprite, RtMaterials.Profile profile,
                                   boolean transmissive, boolean stochasticAlpha, boolean emitting) {
        if (sprite != null && TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
            int materialId = RtMaterialRegistry.INSTANCE.requireSnapshot()
                    .resolve(sprite, profile, transmissive, emitting);
            capture.currentMaterialId = stochasticAlpha
                    ? RtMaterialRegistry.INSTANCE.withStochasticAlpha(materialId) : materialId;
        } else if (sprite != null && RtEntityTextures.entityPbr()) {
            capture.currentMaterialId = RtMaterialRegistry.INSTANCE.resolveEntitySprite(sprite, stochasticAlpha);
        } else {
            capture.currentMaterialId = RtMaterialRegistry.INSTANCE.entityFallbackId(stochasticAlpha);
        }
    }

    /** Whether a render type is alpha-blended (translucent) — its pipeline's color target has a blend
     *  function. Cutout/solid have none. Drives stochastic entity transparency in world.rahit. */
    private static boolean isTranslucent(RenderType renderType) {
        return renderType != null && renderType.hasBlending();
    }

    /** Classify one vanilla submission for the entity BLAS geometry split. */
    private static int alphaBucket(RenderType renderType) {
        if (renderType == null) {
            return RtAccel.ENTITY_BUCKET_ANY_HIT;
        }
        if (renderType.hasBlending()) {
            return RtAccel.ENTITY_BUCKET_ANY_HIT;
        }
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
        // Vanilla's cutout pipelines carry the exact ALPHA_CUTOUT shader define. This is more robust
        // than matching pipeline names and also works for mod-provided RenderPipelines.
        if (pipeline.getShaderDefines().values().containsKey("ALPHA_CUTOUT")
                || pipeline.getShaderDefines().flags().contains("ALPHA_CUTOUT")) {
            return RtAccel.ENTITY_BUCKET_ANY_HIT;
        }
        return RtAccel.ENTITY_BUCKET_OPAQUE;
    }

    private static int alphaBucket(ChunkSectionLayer layer, boolean stochasticAlpha) {
        if (stochasticAlpha || layer == ChunkSectionLayer.TRANSLUCENT) {
            return RtAccel.ENTITY_BUCKET_ANY_HIT;
        }
        return layer == ChunkSectionLayer.SOLID
                ? RtAccel.ENTITY_BUCKET_OPAQUE : RtAccel.ENTITY_BUCKET_ANY_HIT;
    }

    /** Read the draw topology so custom triangle effects are never mis-grouped as RT quads. */
    private static PrimitiveTopology primitiveTopology(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        return ((RenderSetupAccessor) setup).caustica$pipeline().getPrimitiveTopology();
    }

    private static boolean isBannerPattern(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
        return "minecraft:pipeline/banner_pattern".equals(pipeline.getLocation().toString());
    }

    /** Vanilla enchantment/foil glint pipelines include "glint" in their resource path
     *  (entity_glint, armor_entity_glint, glint_direct, etc.). */
    private static boolean isGlint(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        Object setup = ((RenderTypeAccessor) renderType).caustica$state();
        RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
        String location = pipeline.getLocation().toString();
        return location.contains("glint");
    }

    /**
     * Vanilla's end-portal render type (EndPortalRenderer, and the portal quad of the end gateway)
     * draws the starfield with a special shader over the flat purple end_portal texture. In the RT
     * path that quad is plain captured geometry, so it must be tagged for world.rchit's procedural
     * night-sky branch instead. Detected by pipeline location, mirroring {@link #isGlint}.
     */
    private static boolean isEndPortal(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        try {
            Object setup = ((RenderTypeAccessor) renderType).caustica$state();
            RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
            return pipeline.getLocation().toString().contains("end_portal");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Portal flags for an atlas sprite (held/displayed portal blocks), or 0 for ordinary sprites. */
    private static int portalFlagsForSprite(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return 0;
        }
        Identifier name = sprite.contents().name();
        if (NETHER_PORTAL_SPRITE.equals(name)) {
            return TERRAIN_PRIM_PORTAL_NETHER;
        }
        if (END_PORTAL_SPRITE.equals(name)) {
            return TERRAIN_PRIM_PORTAL_END;
        }
        return 0;
    }

    /** Tag a portal submission (render-type or sprite detected) for world.rchit's portal branches. */
    private static void tagPortalSubmission(RtEntityCapture capture, int portalFlags) {
        if (portalFlags == 0) {
            return;
        }
        capture.currentPortalFlags = portalFlags;
        // Portal surfaces are opaque self-lit shader surfaces; never alpha-test or glass them.
        capture.currentAlphaBucket = RtAccel.ENTITY_BUCKET_OPAQUE;
        capture.currentOpacity = 1.0f;
    }

    private static boolean isWaterMask(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        try {
            Object setup = ((RenderTypeAccessor) renderType).caustica$state();
            RenderPipeline pipeline = ((RenderSetupAccessor) setup).caustica$pipeline();
            String loc = pipeline.getLocation().toString().toLowerCase();
            return loc.contains("water_mask") || loc.contains("watermask") || loc.contains("watermask") || loc.contains("boat_water") || RenderTypes.waterMask().equals(renderType);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void collectWaterPatchParts(ModelPart root, List<ModelPart> out) {
        if (root == null) return;
        ModelPartAccessor access = (ModelPartAccessor) (Object) root;
        var children = access.caustica$children();
        if (children == null) return;
        for (var entry : children.entrySet()) {
            String name = entry.getKey().toLowerCase();
            ModelPart child = entry.getValue();
            if (name.contains("water") || name.contains("mask") || name.contains("patch")) {
                out.add(child);
            }
            // Recurse
            collectWaterPatchParts(child, out);
        }
    }

    /** Resolve a quad's tint colour from its tint index + the submission's tint layers (white if untinted). */
    private static int tintColor(int tintIndex, int[] tintLayers) {
        if (tintIndex < 0 || tintLayers == null || tintIndex >= tintLayers.length) {
            return -1; // white
        }
        return tintLayers[tintIndex] | 0xFF000000; // force opaque; capture uses only the rgb
    }

    /** Re-mesh a contained block-model display through FRAPI so model wrappers remain effective.
     *  For block-entity blocks (chest, trapped chest, ender chest, shulker, etc.) that have no baked
     *  model, capture the actual BlockEntity model via BlockEntityRenderDispatcher so the minecart
     *  with chest geometry is correctly sent to Vulkan.
     */
    public void captureBlockState(BlockState blockState, Matrix4fc transform, PoseStack poseStack) {
        if (capture == null || blockState.isAir()) {
            return;
        }

        // First try to capture as a real BlockEntity (chest in minecart) via dispatcher
        if (blockState.hasBlockEntity()) {
            try {
                var mc = Minecraft.getInstance();
                var level = mc.level;
                var dispatcher = mc.getBlockEntityRenderDispatcher();
                var camState = RtEntities.INSTANCE.getCameraStateForCollector();
                // Only attempt if we have a camera state (i.e. we are inside beginFrame)
                if (camState != null && dispatcher != null) {
                    net.minecraft.world.level.block.entity.BlockEntity be = null;
                    // A contained chest is synthetic: it is not a world BE and used to be created at the
                    // origin. BlockEntityRenderDispatcher#tryExtractRenderState applies its normal
                    // 64-block cull to that position, so a ChestMinecartEntity outside that small area
                    // never reached ChestRenderer and fell through to the beige proxy box. Keep the
                    // synthetic BE near the camera solely for extraction/culling; its actual placement
                    // still comes exclusively from the minecart's active pose below.
                    boolean chestRendererOwnsTransform = blockState.getBlock()
                            instanceof net.minecraft.world.level.block.ChestBlock;
                    BlockPos syntheticPos = chestRendererOwnsTransform
                            ? BlockPos.containing(camState.pos) : BlockPos.ZERO;
                    // Chest cases: normal, trapped, ender
                    if (blockState.is(net.minecraft.world.level.block.Blocks.CHEST)) {
                        be = new net.minecraft.world.level.block.entity.ChestBlockEntity(syntheticPos, blockState);
                        be.setLevel(level);
                    } else if (blockState.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)) {
                        be = new net.minecraft.world.level.block.entity.TrappedChestBlockEntity(syntheticPos, blockState);
                        be.setLevel(level);
                    } else if (blockState.is(net.minecraft.world.level.block.Blocks.ENDER_CHEST)) {
                        be = new net.minecraft.world.level.block.entity.EnderChestBlockEntity(BlockPos.ZERO, blockState);
                        be.setLevel(level);
                    } else if (chestRendererOwnsTransform) {
                        be = new net.minecraft.world.level.block.entity.ChestBlockEntity(syntheticPos, blockState);
                        be.setLevel(level);
                    } else {
                        // Generic: try to create via Block's EntityBlock path if possible
                        // For blocks like barrel, hopper, etc., create minimal BE via type
                        // Use BlockEntityType to create - best effort, skip if fails
                        // This fallback prevents invisible chest and also handles other BE minecart displays
                        try {
                            String bName = blockState.getBlock().toString().toLowerCase();
                            if (bName.contains("chest")) {
                                be = new net.minecraft.world.level.block.entity.ChestBlockEntity(BlockPos.ZERO, blockState);
                                be.setLevel(level);
                            }
                        } catch (Throwable ignored) {}
                    }

                    if (be != null) {
                        dispatcher.prepare(camState.pos);
                        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                        var berState = dispatcher.tryExtractRenderState(be, partial, null, false);
                        if (berState != null) {
                            int idxBeforeBE = capture.idx.size();
                            poseStack.pushPose();
                            // The intercepted display state already carries ChestRenderer's facing
                            // transform. ChestRenderer.submit applies that exact transform from the
                            // synthetic chest state, so applying it here as well would rotate the model
                            // twice. Other block-entity paths retain their display transform unchanged.
                            if (transform != null && !chestRendererOwnsTransform) {
                                poseStack.mulPose(transform);
                            }
                            try {
                                dispatcher.submit(berState, poseStack, this, camState);
                            } finally {
                                poseStack.popPose();
                            }
                            // If we got new geometry from the BE, we're done (chest now visible)
                            if (capture.idx.size() > idxBeforeBE) {
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                // Fall through to block model path
                // System.out.println(\"RT chest BE capture failed: \" + t);
            }
        }

        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(blockState);
        int idxBeforeModel = capture.idx.size();
        if (model != null) {
            poseStack.pushPose();
            if (transform != null) {
                poseStack.mulPose(transform);
            }
            try {
                // Display models are isolated from the world and do not apply random block offsets, matching
                // Fabric's BlockStateModelWrapper path.
                emitBlockModel(poseStack.last().pose(), model, BlockAndTintGetter.EMPTY, BlockPos.ZERO,
                        blockState, 42L, false);
            } finally {
                poseStack.popPose();
            }
            if (capture.idx.size() > idxBeforeModel) {
                return; // block model gave geometry (stone, etc.)
            }
        }

        // Final safe fallback: for any remaining BE block without model and without successful BE capture
        // (should not happen for chest if BE path worked), emit a solid-color box using white slot to
        // guarantee visibility without corrupted atlas UVs.
        if (blockState.hasBlockEntity()) {
            // Recompute final pose (stack may have been popped, so use identity + transform)
            Matrix4f fallbackPose = new Matrix4f();
            if (transform != null) {
                fallbackPose.set(transform);
            }
            emitChestFallbackSafe(fallbackPose, blockState);
        }
    }

    private void emitChestFallbackSafe(Matrix4f pose, BlockState state) {
        // Use white slot (untextured) and solid tint to avoid Vulkan atlas corruption
        capture.currentAlphaBucket = RtAccel.ENTITY_BUCKET_OPAQUE;
        capture.currentOpacity = 1.0f;
        capture.currentOrder = 0;
        capture.currentPortalFlags = 0;
        capture.currentPrimFlags = 0; // the proxy box is never glint/weather tagged
        capture.currentGlintArmorSlot = 0; // no armour context on the fallback box
        capture.clearUvRemap();
        capture.currentTexSlot = RtEntityTextures.INSTANCE.whiteSlot();
        capture.currentMaterialId = RtMaterialRegistry.INSTANCE.entityFallbackId(false);

        boolean isChest = state.getBlock().toString().toLowerCase().contains("chest");
        int chestTint = 0xFF8B5A2B;
        int tint = isChest ? chestTint : -1;

        float min = 0.0625f, max = 0.9375f, minY = 0.0f, maxY = 0.875f;
        if (state.toString().toLowerCase().contains("hopper")) {
            min = 0.0f; max = 1.0f; minY = 0.0f; maxY = 0.625f;
        }

        float[][] corners = {
                {min, minY, min}, {max, minY, min}, {max, minY, max}, {min, minY, max},
                {min, maxY, min}, {max, maxY, min}, {max, maxY, max}, {min, maxY, max},
        };
        int[][] faces = {{0,1,2,3},{4,7,6,5},{0,4,5,1},{2,6,7,3},{0,3,7,4},{1,5,6,2}};
        float[] nx = {0,0,0,0,-1,1}, ny = {-1,1,0,0,0,0}, nz = {0,0,-1,1,0,0};

        for (int f = 0; f < faces.length; f++) {
            int[] face = faces[f];
            for (int i = 0; i < 4; i++) {
                float[] c = corners[face[i]];
                meshPos.set(c[0], c[1], c[2]);
                pose.transformPosition(meshPos);
                meshX[i] = meshPos.x; meshY[i] = meshPos.y; meshZ[i] = meshPos.z;
                meshU[i] = 0f; meshV[i] = 0f; // zero UV to sample white slot uniformly
            }
            capture.addDirectQuad(meshX, meshY, meshZ, ZERO_UV, ZERO_UV, nx[f], ny[f], nz[f], tint);
        }
    }

    @Override
    public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
    }

    @Override
    public void submitNameTag(PoseStack poseStack, Vec3 nameTagAttachment, int offset, Component name,
                              boolean seeThrough, int lightCoords, CameraRenderState camera) {
    }


    // Sign text (AbstractSignRenderer) and any other in-world text (maps, …) land here. Mirrors
    // TextFeatureRenderer.buildGroup's own flush path exactly (same Font.prepareText/prepare8xTextOutline
    // + GlyphVisitor calls) but visits glyphs straight into the capture instead of a real vertex buffer,
    // so sign text gets real ray-traced geometry instead of being dropped.
    @Override
    public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow,
                           Font.DisplayMode displayMode, int lightCoords, int color, int backgroundColor, int outlineColor) {
        if (capture == null) {
            return;
        }
        Matrix4f pose = poseStack.last().pose();
        Font font = Minecraft.getInstance().font;
        textGlyphVisitor.pose = pose;
        textGlyphVisitor.lightCoords = lightCoords;
        if (outlineColor == 0) {
            textGlyphVisitor.displayMode = displayMode;
            font.prepareText(string, x, y, color, dropShadow, false, backgroundColor).visit(textGlyphVisitor);
        } else {
            // Same two-pass outline technique as vanilla: an 8-directional expanded copy first (flat,
            // NORMAL), then the real text on top with a polygon-offset display mode so it doesn't z-fight
            // the outline it's sitting on.
            Font.PreparedText outline = font.prepare8xTextOutline(string, x, y, outlineColor);
            Font.PreparedText text = font.prepareText(string, x, y, color, false, false, 0);
            textGlyphVisitor.displayMode = Font.DisplayMode.NORMAL;
            outline.visit(textGlyphVisitor);
            textGlyphVisitor.displayMode = Font.DisplayMode.POLYGON_OFFSET;
            text.visit(textGlyphVisitor);
        }
    }

    @Override
    public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color,
                                     Font.DisplayMode displayMode, int lightCoords) {
        // 26.3 submits the text background quad separately; the RT text path bakes the background
        // color into the glyph quads via prepareText, so there is nothing to capture here.
    }

    /** Resolves each glyph's render type to a bindless slot and renders it into {@link #textVertexConsumer}. */
    private final class TextGlyphVisitor implements Font.GlyphVisitor {
        Matrix4f pose;
        int lightCoords;
        Font.DisplayMode displayMode;

        @Override
        public void acceptRenderable(TextRenderable renderable) {
            RenderType renderType = renderable.renderType(displayMode);
            boolean stochasticAlpha = isTranslucent(renderType);
            capture.currentAlphaBucket = alphaBucket(renderType);
            // Resolve the glyph's atlas page from the renderable's LIVE texture view, not from the
            // RenderType. Font pages are destroyed and re-created when the font is re-selected (toggling
            // Force Unicode Font) while the RenderType identity is memoized per texture name and survives,
            // so the RenderType-keyed cache would pin a handle to the destroyed image and every glyph
            // would sample garbage. See RtEntityTextures.slotForTextureView.
            capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForTextureView(renderable.textureView());
            capture.currentMaterialId = RtMaterialRegistry.INSTANCE.entityFallbackId(stochasticAlpha);
            capture.currentOpacity = 1.0f;
            capture.currentOrder = 0;
            capture.currentPortalFlags = 0;
            capture.currentPrimFlags = 0; // text is never glint/weather tagged
            capture.currentGlintArmorSlot = 0; // no armour context on glyphs
            capture.clearUvRemap(); // glyph U/V are already atlas-space
            renderable.render(pose, textVertexConsumer, lightCoords, false);
        }
    }

    /**
     * Adapts the builder-style {@link VertexConsumer} calls glyph rendering makes ({@code
     * addVertex(pose,x,y,z).setColor(c).setUv(u,v).setLight(light)}) into {@link RtEntityCapture}'s bulk
     * {@code addVertex}. {@code setUv2} (light, via the default {@code setLight}) is always the last call
     * per vertex in {@code BakedSheetGlyph}, so committing there is safe — no vertex is ever left pending.
     */
    private final class RtTextVertexConsumer implements VertexConsumer {
        private float vx, vy, vz;
        private int vColor = -1;
        private float vu, vv;

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
            vColor = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
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
        public VertexConsumer setUv1(int u, int v) {
            return this; // overlay unused by the capture
        }

        @Override
        public VertexConsumer setUv2(int lightU, int lightV) {
            // Inverts VertexConsumer#setLight's default packing; light itself is unused by the capture
            // (entities are fully path-traced), so any value round-trips fine.
            int light = (lightU & 0xFFFF) | (lightV << 16);
            capture.addVertex(vx, vy, vz, vColor, vu, vv, 0, light, 0f, 0f, 0f);
            return this;
        }

        @Override
        public VertexConsumer setUv3(float u, float v) {
            return this; // entity text has no third UV layer
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this; // planar glyph quad → emitQuad's geometric fallback is exact
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }

    // The classic flame overlay on burning entities. Vanilla's FlameFeatureRenderer renders the
    // submitted flame nodes with the animated fire sprite and full-bright lighting; the RT path has no
    // fixed-function flame pass, so capture the same look as real cutout geometry on the entity's BLAS:
    // a flame cube around the feet textured with the block atlas's fire_0 sprite. The sprite lives in
    // the live (animated) block atlas, so the flame flickers like vanilla's, and the per-prim emission
    // makes it self-lit instead of shaded by the scene.
    @Override
    public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
        if (capture == null) {
            return;
        }
        flameSubmittedThisEntity = true; // vanilla's dispatcher asked for the flame this entity
        // Never inherit a portal or glint/weather tag from an earlier submission in the same capture
        // (e.g. an enderman holding a portal block that is also on fire): the flame is plain textured
        // cutout geometry.
        capture.currentPortalFlags = 0;
        capture.currentPrimFlags = 0;
        capture.currentGlintArmorSlot = 0; // no armour context on the flame
        TextureAtlasSprite fire = fireSprite();
        if (fire == null) {
            return;
        }
        // The block atlas is pre-seeded as bindless slot 0 (RtEntityTextures.atlasSlotCache), and the
        // fire sprite lives in it — no atlas-key lookup needed for the flame's sampler.
        capture.currentTexSlot = 0;
        capture.currentMaterialId = RtMaterialRegistry.INSTANCE.entityFallbackId(false);
        // The fire sprite's transparent gaps must not block the ray: alpha-test like any cutout layer.
        capture.currentAlphaBucket = RtAccel.ENTITY_BUCKET_ANY_HIT;
        capture.currentOpacity = 1.0f;
        capture.currentOrder = 0;
        capture.setUvRemap(fire.getU0(), fire.getV0(), fire.getU1(), fire.getV1());
        // The dispatcher normally passes the entity's pushed pose; some callers pass an empty stack
        // (our own fallback guarantees identity). An empty stack has no "last" pose — fall back to
        // identity so the flame lands in the same entity-local space as the body capture.
        Matrix4f pose = poseStack.isEmpty() ? IDENTITY_POSE : poseStack.last().pose();
        // Flame cube proportions: the box must be WIDER than the mob's body (a zombie is 0.6 wide,
        // half 0.3) so its front/side faces sit in front of the body and the fire shows over it —
        // a flush cube hides entirely inside the body silhouette. The fire texture's transparent
        // gaps let the body show through, which is exactly the vanilla look.
        float half = 0.5f;
        float top = 1.3f;
        float x0 = -half, x1 = half, z0 = -half, z1 = half, y0 = 0.0f, y1 = top;
        // Corner table (front face z-: 0..3 CCW from bottom-left; back face z+: 4..7). Face quads are
        // wound around the perimeter so every face is planar and the fire texture maps straight.
        float[] cx = {x0, x1, x1, x0, x1, x0, x1, x0};
        float[] cy = {y0, y0, y1, y1, y0, y0, y1, y1};
        float[] cz = {z0, z0, z0, z0, z1, z1, z1, z1};
        // Four open sides only — deliberately NO top cap. A horizontal top quad at head/neck height
        // samples the whole fire sprite onto a flat plane, reading as a floating cut sheet ("flame
        // hat"); the vanilla entity fire is an open box, so the flames rise naturally around the
        // body and looking down shows the mob through the ring.
        emitFlameFace(pose, cx, cy, cz, new int[]{0, 1, 2, 3}, 0f, 0f, -1f);  // north (z-)
        emitFlameFace(pose, cx, cy, cz, new int[]{5, 4, 6, 7}, 0f, 0f, 1f);   // south (z+)
        // Corner order walks the BOTTOM edge first (0 -> 5) so UV index i advances along it: the
        // texture's v axis stays vertical (flames up). The old {0,3,7,5} climbed the z0 edge first,
        // mapping the sprite's v axis onto world Z — flames lying on their side on this face
        // (visible on the player's right when facing south).
        emitFlameFace(pose, cx, cy, cz, new int[]{0, 5, 7, 3}, -1f, 0f, 0f);  // west (x-)
        emitFlameFace(pose, cx, cy, cz, new int[]{4, 1, 2, 6}, 1f, 0f, 0f);   // east (x+)
        // The flame's sprite-rect remap is only for its own quads; leave the capture clean so a
        // later submission (held item / armour layer) cannot sample through the fire sprite region.
        capture.clearUvRemap();
    }

    /** One flame-cube face, pose-transformed into the capture with the fire sprite's full region. */
    private void emitFlameFace(Matrix4f pose, float[] cx, float[] cy, float[] cz, int[] corners,
                               float nx, float ny, float nz) {
        for (int i = 0; i < 4; i++) {
            int p = corners[i];
            pose.transformPosition(cx[p], cy[p], cz[p], meshPos);
            meshX[i] = meshPos.x;
            meshY[i] = meshPos.y;
            meshZ[i] = meshPos.z;
            meshU[i] = FLAME_UV_U[i];
            meshV[i] = FLAME_UV_V[i];
        }
        // White tint, full-bright emission (vanilla renders the flame with LightTexture.FULL_BRIGHT).
        capture.addDirectQuad(meshX, meshY, meshZ, meshU, meshV, nx, ny, nz, -1, FLAME_EMISSION);
    }

    /** Whether vanilla's dispatcher submitted the flame overlay during this entity's capture (set in
     *  {@link #submitFlame}, cleared per entity by {@link #begin}). Lets {@code RtEntities} emit the
     *  flame itself when the dispatcher's gate didn't fire for the RT capture. */
    public boolean flameSubmittedThisEntity() {
        return flameSubmittedThisEntity;
    }

    // Leashes/leads: replicate LeashFeatureRenderer's geometry — a 24-segment curve with two crossed
    // diagonal ribbons across the 0.05-wide cross-section (the crossing keeps the leash visible from
    // every view direction), checkered per segment by darkening alternate ranks. Vanilla draws it as an
    // untextured triangle strip (POSITION_COLOR_LIGHTMAP); here each strip step becomes one RT quad on
    // the white bindless slot with the leash colour as per-prim tint. Light coords are path-traced away.
    @Override
    public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
        if (capture == null) {
            return;
        }
        capture.clearUvRemap();
        capture.currentOrder = 0;
        capture.currentTexSlot = RtEntityTextures.INSTANCE.whiteSlot();
        capture.currentMaterialId = RtMaterialRegistry.INSTANCE.entityFallbackId(false);
        capture.currentAlphaBucket = RtAccel.ENTITY_BUCKET_OPAQUE;
        capture.currentOpacity = 1.0f;
        capture.currentPortalFlags = 0;
        capture.currentPrimFlags = 0; // a leash ribbon is never glint/weather tagged
        capture.currentGlintArmorSlot = 0; // no armour context on leashes
        Matrix4f pose = poseStack.last().pose();
        // Same derivation as LeashFeatureRenderer.prepare: the ribbon's horizontal half-extent is the
        // curve's ground-plane perpendicular, and the attachment offset shifts the whole curve in the
        // pose's local space (translate-then-transform == transform(v + offset)).
        float dx = (float) (leashState.end.x - leashState.start.x);
        float dy = (float) (leashState.end.y - leashState.start.y);
        float dz = (float) (leashState.end.z - leashState.start.z);
        float offsetFactor = Mth.invSqrt(dx * dx + dz * dz) * LEASH_WIDTH / 2.0f;
        float dxOff = dz * offsetFactor;
        float dzOff = dx * offsetFactor;
        if (!Float.isFinite(dxOff) || !Float.isFinite(dzOff)) {
            // Perfectly vertical leash: the ground-plane perpendicular degenerates (vanilla emits NaN
            // vertices and draws nothing). Pick an arbitrary horizontal extent instead — NaN positions
            // must never reach the capture, where they'd poison the motion/hash passes.
            dxOff = LEASH_WIDTH / 2.0f;
            dzOff = 0f;
        }
        emitLeashRibbon(pose, leashState, dx, dy, dz, dxOff, dzOff, LEASH_WIDTH, false);
        emitLeashRibbon(pose, leashState, dx, dy, dz, dxOff, dzOff, 0f, true);
    }

    /** One diagonal leash ribbon: 25 vertex pairs along the curve, quadified pairwise. */
    private void emitLeashRibbon(Matrix4f pose, EntityRenderState.LeashState state,
                                 float dx, float dy, float dz, float dxOff, float dzOff,
                                 float fudge, boolean altParity) {
        float ox = (float) state.offset.x;
        float oy = (float) state.offset.y;
        float oz = (float) state.offset.z;
        float prevAx = 0f, prevAy = 0f, prevAz = 0f, prevBx = 0f, prevBy = 0f, prevBz = 0f;
        int prevColor = 0;
        for (int k = 0; k <= LEASH_STEPS; k++) {
            float progress = (float) k / LEASH_STEPS;
            float x = dx * progress;
            float y;
            if (state.slack) {
                y = dy > 0.0f ? dy * progress * progress : dy - dy * (1.0f - progress) * (1.0f - progress);
            } else {
                y = dy * progress;
            }
            float z = dz * progress;
            // Vanilla's per-pair checker: the two crossed ribbons darken opposite ranks (backwards pass).
            float m = k % 2 == (altParity ? 1 : 0) ? 0.7f : 1.0f;
            int color = 0xFF000000
                    | ((int) (0.5f * m * 255.0f) << 16)
                    | ((int) (0.4f * m * 255.0f) << 8)
                    | (int) (0.3f * m * 255.0f);
            pose.transformPosition(ox + x - dxOff, oy + y + fudge, oz + z + dzOff, meshPos);
            float ax = meshPos.x, ay = meshPos.y, az = meshPos.z;
            pose.transformPosition(ox + x + dxOff, oy + y + LEASH_WIDTH - fudge, oz + z - dzOff, meshPos);
            float bx = meshPos.x, by = meshPos.y, bz = meshPos.z;
            if (k > 0) {
                meshX[0] = prevAx; meshY[0] = prevAy; meshZ[0] = prevAz;
                meshX[1] = prevBx; meshY[1] = prevBy; meshZ[1] = prevBz;
                meshX[2] = bx; meshY[2] = by; meshZ[2] = bz;
                meshX[3] = ax; meshY[3] = ay; meshZ[3] = az;
                capture.addDirectQuad(meshX, meshY, meshZ, ZERO_UV, ZERO_UV, 0f, 0f, 0f, prevColor);
            }
            prevAx = ax; prevAy = ay; prevAz = az;
            prevBx = bx; prevBy = by; prevBz = bz;
            prevColor = color;
        }
    }

    // Falling blocks and moving piston blocks render here. Emit through FRAPI so connected textures,
    // emissive overlays, and custom model geometry survive outside the ordinary chunk renderer.
    @Override
    public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outlineColor) {
        if (capture == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockState bs = state.blockState;
        BlockStateModel model = mc.getModelManager().getBlockStateModelSet().get(bs);
        if (model == null) {
            return;
        }
        // No local catch: failures propagate through the entity-capture handler like other entities.
        emitBlockModel(poseStack.last().pose(), model, state, state.blockPos, bs,
                bs.getSeed(state.randomSeedPos), true);
    }

    /** Synchronously emit one block model into the active entity capture through Fabric Renderer API. */
    private void emitBlockModel(Matrix4f pose, BlockStateModel model, BlockAndTintGetter view, BlockPos pos,
                                BlockState state, long seed, boolean applyBlockOffset) {
        if (blockQuadEmitter == null) {
            blockQuadEmitter = Renderer.get().quadEmitter(this::addEmittedBlockQuad);
        }
        Vec3 offset = applyBlockOffset ? state.getOffset(pos) : Vec3.ZERO;
        emittedBlockPose = pose;
        emittedBlockView = view;
        emittedBlockState = state;
        emittedBlockPos = pos;
        emittedBlockOffsetX = (float) offset.x;
        emittedBlockOffsetY = (float) offset.y;
        emittedBlockOffsetZ = (float) offset.z;
        int idxStart = capture.idx.size();
        long started = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            capture.clearUvRemap();
            emittedBlockRandom.setSeed(seed);
            model.emitQuads(blockQuadEmitter, view, pos, state, emittedBlockRandom, NEVER_CULL);
        } finally {
            emittedBlockPose = null;
            emittedBlockView = null;
            emittedBlockState = null;
            emittedBlockPos = null;
            RtFrameStats.FRAME.endStage("entity.capture.submit.bakedQuads", started);
            countBakedOutput(idxStart);
        }
    }

    /** Resolve a dynamic block quad with its state-dependent profile, overrides, and emission variant. */
    private void setBlockSpriteMaterial(TextureAtlasSprite sprite, BlockState state,
                                        boolean transmissive, boolean stochasticAlpha) {
        if (sprite != null && TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())) {
            int materialId = RtMaterialRegistry.INSTANCE.requireSnapshot().resolve(sprite, state, transmissive);
            capture.currentMaterialId = stochasticAlpha
                    ? RtMaterialRegistry.INSTANCE.withStochasticAlpha(materialId) : materialId;
        } else {
            setSpriteMaterial(sprite, RtMaterials.profile(state), transmissive, stochasticAlpha);
        }
    }

    private void addEmittedBlockQuad(MutableQuadView quad) {
        addMeshQuad(emittedBlockPose, quad, null, false, emittedBlockView, emittedBlockPos,
                emittedBlockState, emittedBlockOffsetX, emittedBlockOffsetY, emittedBlockOffsetZ);
    }

    // FallingBlockEntity renders its block model here. Capture every part's quads (direction-independent
    // + all six cullface lists), block-atlas textured (slot 0).
    @Override
    public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        if (capture == null) {
            return;
        }
        Matrix4f pose = poseStack.last().pose();
        for (BlockStateModelPart part : parts) {
            addQuads(pose, part.getQuads(null), tintLayers);
            for (Direction d : DIRECTIONS) {
                addQuads(pose, part.getQuads(d), tintLayers);
            }
        }
    }

    /**
     * FRAPI (fabric-renderer-api) reroutes every block-display model — the item-frame frame model
     * included — through a Fabric mesh: {@code BlockStateModelWrapper.update} is overwritten to emit
     * into the render state's {@code MutableMesh}, vanilla {@code modelParts} stays empty, and submit
     * calls this interface-injected overload instead of the vanilla one. Fabric's default forwards
     * only the (empty) parts list and silently drops the mesh, so without this override such models
     * capture zero quads in RT.
     */
    @Override
    public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypeByLayer,
                                 boolean hasTranslucency, List<BlockStateModelPart> parts, Mesh mesh,
                                 int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
        if (capture == null) {
            return;
        }
        if (!parts.isEmpty()) {
            submitBlockModel(poseStack, renderTypeByLayer.apply(ChunkSectionLayer.SOLID), parts, tintLayers,
                    lightCoords, overlayCoords, outlineColor);
        }
        addMeshQuads(poseStack, mesh, tintLayers, false);
    }

    /** Fabric item models can carry a mesh besides (or instead of) vanilla baked quads; the injected
     *  default drops it the same way the block-model overload does. */
    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
                           int overlayCoords, int outlineColor, int[] tintLayers, ItemQuads quads,
                           MeshView mesh, ItemStackRenderState.FoilType foilType) {
        if (capture == null) {
            return;
        }
        addQuads(poseStack.last().pose(), quads.all(), tintLayers);
        addMeshQuads(poseStack, mesh, tintLayers, true);
    }

    /** Capture a Fabric Renderer API mesh; each quad already carries final atlas UVs. */
    private void addMeshQuads(PoseStack poseStack, MeshView mesh, int[] tintLayers, boolean itemMesh) {
        if (mesh == null || mesh.size() == 0) {
            return;
        }
        Matrix4f pose = poseStack.last().pose();
        int idxStart = capture.idx.size();
        long started = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
        try {
            capture.clearUvRemap();
            mesh.forEach(quad -> addMeshQuad(pose, quad, tintLayers, itemMesh,
                    null, null, null, 0f, 0f, 0f));
        } finally {
            RtFrameStats.FRAME.endStage("entity.capture.submit.bakedQuads", started);
            countBakedOutput(idxStart);
        }
    }

    private void addMeshQuad(Matrix4f pose, QuadView quad, int[] tintLayers, boolean itemMesh,
                             BlockAndTintGetter view, BlockPos pos, BlockState state,
                             float offsetX, float offsetY, float offsetZ) {
        TextureAtlasSprite sprite = ((FabricTextureAtlas) Minecraft.getInstance().getAtlasManager()
                .getAtlasOrThrow(quad.atlas().getId())).spriteFinder().find(quad);
        capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForAtlas(quad.atlas().getTextureLocation());
        // Chunk-layer translucency denotes a block-derived dielectric; a blended item render type denotes
        // ordinary stochastic alpha when the quad did not come from such a layer.
        boolean transmissive = quad.chunkLayer() == ChunkSectionLayer.TRANSLUCENT;
        boolean stochasticAlpha = itemMesh && !transmissive && quad.itemRenderType() != null
                && quad.itemRenderType().hasBlending();
        capture.currentAlphaBucket = alphaBucket(quad.chunkLayer(), stochasticAlpha);
        float emission = state == null && itemMesh ? itemSpriteEmission(sprite) : 0.0f;
        if (state != null) {
            setBlockSpriteMaterial(sprite, state, transmissive, stochasticAlpha);
        } else {
            setSpriteMaterial(sprite, transmissive ? RtMaterials.Profile.GLASS : RtMaterials.Profile.DEFAULT,
                    transmissive, stochasticAlpha, emission > 0.0f);
        }
        capture.currentOpacity = 1.0f;
        capture.currentOrder = 0; // baked-quad paths never stack decal layers
        capture.currentPortalFlags = 0; // per-submission; the tag below applies only to this quad
        capture.currentPrimFlags = 0; // glint never arrives through FRAPI meshes — drop any stale tag
        capture.currentGlintArmorSlot = 0; // no armour context on FRAPI meshes
        // Held/displayed portal blocks through FRAPI meshes (endermen, block displays): tag them for
        // the procedural portal branches, mirroring addQuad.
        tagPortalSubmission(capture, portalFlagsForSprite(sprite));
        for (int i = 0; i < 4; i++) {
            pose.transformPosition(quad.x(i) + offsetX, quad.y(i) + offsetY, quad.z(i) + offsetZ, meshPos);
            meshX[i] = meshPos.x;
            meshY[i] = meshPos.y;
            meshZ[i] = meshPos.z;
            meshU[i] = quad.u(i);
            meshV[i] = quad.v(i);
        }
        int tint = tintColor(quad.tintIndex(), tintLayers);
        if (quad.tintIndex() >= 0 && tintLayers == null && state != null && view != null && pos != null) {
            BlockTintSource source = Minecraft.getInstance().getBlockColors()
                    .getTintSource(state, quad.tintIndex());
            if (source != null) {
                tint = source.colorInWorld(state, view, pos) | 0xFF000000;
            }
        }
        int color = ARGB.multiply(averageQuadColor(quad), tint);
        if (quad.emissive()) {
            emission = 1.0f;
        } else if (state != null) {
            emission = state.getLightEmission() / 15f;
        }
        capture.addDirectQuad(meshX, meshY, meshZ, meshU, meshV, 0f, 0f, 0f, color, emission);
    }


    /**
     * Item submissions do not expose the original ItemStack in Minecraft's submit API, so dynamic item
     * emission is inferred from well-known luminous sprite names. Block items that texture from block
     * sprites still resolve through the emitting material variant above; item-atlas sprites use the
     * explicit per-primitive emission value and the shader's fallback dynamic emission strength.
     */
    private static float itemSpriteEmission(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return 0.0f;
        }
        String path = sprite.contents().name().getPath();
        int level = 0;
        if (path.contains("lava")) level = 15;
        else if (path.contains("soul_torch") || path.contains("soul_lantern")) level = 10;
        else if (path.contains("torch")) level = 14;
        else if (path.contains("lantern")) level = 15;
        else if (path.contains("glowstone") || path.contains("sea_lantern")
                || path.contains("shroomlight") || path.contains("froglight")
                || path.contains("jack_o_lantern") || path.contains("redstone_lamp")
                || path.contains("beacon")) level = 15;
        else if (path.contains("end_rod") || path.contains("glow_berries")) level = 14;
        else if (path.contains("sea_pickle")) level = 6;
        else if (path.contains("magma")) level = 3;
        else if (path.contains("blaze_rod")) level = 10;
        return level > 0 ? level / 15.0f : 0.0f;
    }

    /** Collapse Fabric's per-vertex colour into the flat per-primitive tint stored by the RT layout. */
    private static int averageQuadColor(QuadView quad) {
        int a = 0, r = 0, g = 0, b = 0;
        for (int i = 0; i < 4; i++) {
            int color = quad.color(i);
            a += color >>> 24;
            r += (color >>> 16) & 0xFF;
            g += (color >>> 8) & 0xFF;
            b += color & 0xFF;
        }
        return ((a + 2) / 4 << 24) | ((r + 2) / 4 << 16) | ((g + 2) / 4 << 8) | (b + 2) / 4;
    }

    @Override
    public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress,
                                           boolean isBlockTranslucent) {
    }

    @Override
    public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color,
                                   float width, boolean afterTerrain) {
    }

    // Held weapons/tools (via the in-hand layer) + dropped items (ItemEntity) render here as baked
    // quads on the block atlas. Capture them block-atlas textured (slot 0).
    @Override
    public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords,
                           int outlineColor, int[] tintLayers, ItemQuads quads, ItemStackRenderState.FoilType foilType) {
        if (capture == null) {
            return;
        }
        addQuads(poseStack.last().pose(), quads.all(), tintLayers);
    }

    @Override
    public void submitCustomGeometry(PoseStack poseStack, RenderType renderType,
                                     SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
        if (capture == null) {
            return;
        }
        PrimitiveTopology topology = primitiveTopology(renderType);
        boolean lines = topology == PrimitiveTopology.LINES || topology == PrimitiveTopology.DEBUG_LINES
                || renderType == RenderTypes.lines() || renderType == RenderTypes.linesTranslucent();
        if (!lines && topology != PrimitiveTopology.QUADS) {
            return;
        }

        boolean glint = !lines && isGlint(renderType);
        capture.currentOrder = pendingOrder + (glint ? ENCHANTMENT_GLINT_ORDER : 0);
        pendingOrder = 0;
        capture.clearUvRemap(); // custom callbacks already emit final texture/atlas UV coordinates
        capture.currentPortalFlags = 0; // per-submission; tagPortalSubmission below may set it
        capture.currentPrimFlags = glint ? RtEntityCapture.PRIM_FLAG_GLINT : 0;
        // Custom-geometry glint (e.g. a trident's foil) has no preceding armour submission, so no
        // armour-texture gate applies; coverage falls back to the texel-brightness dither alone.
        capture.currentGlintArmorSlot = 0;
        boolean stochasticAlpha = !glint && isTranslucent(renderType);
        capture.currentOpacity = 1.0f; // glint coverage comes from its texture alpha, not a fake opacity
        // Lines are untextured: bind the white slot so albedo is exactly the vertex colour (slot 0 is
        // the block atlas, whose (0,0) texel would tint the ribbon arbitrarily).
        capture.currentTexSlot = lines ? RtEntityTextures.INSTANCE.whiteSlot()
                : RtEntityTextures.INSTANCE.slotFor(renderType);
        capture.currentMaterialId = lines
                ? RtMaterialRegistry.INSTANCE.entityFallbackId(false)
                : RtEntityTextures.INSTANCE.materialIdFor(renderType, stochasticAlpha);
        capture.currentAlphaBucket = lines ? RtAccel.ENTITY_BUCKET_OPAQUE
                : (glint ? RtAccel.ENTITY_BUCKET_ANY_HIT : alphaBucket(renderType));
        // End-portal block entities may submit their night-sky quad as custom geometry
        // (EndPortalRenderer renders a single textured quad); tag it for the procedural night-sky
        // branch.
        if (!lines && isEndPortal(renderType)) {
            tagPortalSubmission(capture, TERRAIN_PRIM_PORTAL_END);
        }

        if (lines) {
            lineVertexConsumer.begin();
            customGeometryRenderer.render(poseStack.last(), lineVertexConsumer);
            lineVertexConsumer.finish();
        } else {
            customQuadVertexConsumer.begin();
            customGeometryRenderer.render(poseStack.last(), customQuadVertexConsumer);
            customQuadVertexConsumer.finish();
            capture.requireCompleteQuads("custom geometry " + renderType);
        }
    }

    /**
     * Converts builder-style custom quad vertices into the bulk vertex form consumed by
     * {@link RtEntityCapture}. A new vertex commits the previous one because vanilla does not expose an
     * explicit endVertex call; {@link #finish()} commits the final vertex after the callback returns.
     */
    private final class RtCustomQuadVertexConsumer implements VertexConsumer {
        private float x, y, z, u, v, nx, ny, nz;
        private int color;
        private boolean pending;

        void begin() {
            pending = false;
        }

        void finish() {
            commit();
        }

        private void commit() {
            if (!pending) {
                return;
            }
            capture.addVertex(x, y, z, color, u, v, 0, 0, nx, ny, nz);
            pending = false;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            commit();
            this.x = x;
            this.y = y;
            this.z = z;
            this.u = 0f;
            this.v = 0f;
            this.nx = 0f;
            this.ny = 0f;
            this.nz = 0f;
            this.color = -1;
            this.pending = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            color = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            this.color = color;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.nx = x;
            this.ny = y;
            this.nz = z;
            return this;
        }

        @Override public VertexConsumer setLineWidth(float width) { return this; }
    }

    /** Expands each raster line segment into two crossed, camera-independent RT ribbons. */
    private final class RtLineVertexConsumer implements VertexConsumer {
        private static final float WORLD_UNITS_PER_PIXEL = 0.0025f;
        private final float[] ax = new float[4], ay = new float[4], az = new float[4];
        private float x, y, z, width;
        private int color;
        private boolean pending;
        private boolean haveFirst;
        private float firstX, firstY, firstZ, firstWidth;
        private int firstColor;

        void begin() {
            pending = false;
            haveFirst = false;
        }

        void finish() {
            commit();
            if (haveFirst) {
                haveFirst = false;
                throw new IllegalStateException("custom line geometry left an unmatched vertex");
            }
        }

        private void commit() {
            if (!pending) {
                return;
            }
            if (!haveFirst) {
                firstX = x;
                firstY = y;
                firstZ = z;
                firstWidth = width;
                firstColor = color;
                haveFirst = true;
            } else {
                emitSegment(firstX, firstY, firstZ, x, y, z,
                        Math.max(firstWidth, width), firstColor);
                haveFirst = false;
            }
            pending = false;
        }

        private void emitSegment(float x0, float y0, float z0, float x1, float y1, float z1,
                                 float pixelWidth, int color) {
            float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length <= 1.0e-6f) {
                return;
            }
            dx /= length;
            dy /= length;
            dz /= length;
            float halfWidth = Math.max(0.0015f, pixelWidth * WORLD_UNITS_PER_PIXEL * 0.5f);

            // Cross the segment with the least-parallel cardinal axis for a stable perpendicular.
            float px, py, pz;
            float adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
            if (adx <= ady && adx <= adz) {
                px = 0f;
                py = dz;
                pz = -dy;
            } else if (ady <= adz) {
                px = -dz;
                py = 0f;
                pz = dx;
            } else {
                px = dy;
                py = -dx;
                pz = 0f;
            }
            float plen = (float) Math.sqrt(px * px + py * py + pz * pz);
            px = px / plen * halfWidth;
            py = py / plen * halfWidth;
            pz = pz / plen * halfWidth;
            emitRibbon(x0, y0, z0, x1, y1, z1, px, py, pz, color);

            float qx = (dy * pz - dz * py);
            float qy = (dz * px - dx * pz);
            float qz = (dx * py - dy * px);
            emitRibbon(x0, y0, z0, x1, y1, z1, qx, qy, qz, color);
        }

        private void emitRibbon(float x0, float y0, float z0, float x1, float y1, float z1,
                                float px, float py, float pz, int color) {
            ax[0] = x0 - px; ay[0] = y0 - py; az[0] = z0 - pz;
            ax[1] = x1 - px; ay[1] = y1 - py; az[1] = z1 - pz;
            ax[2] = x1 + px; ay[2] = y1 + py; az[2] = z1 + pz;
            ax[3] = x0 + px; ay[3] = y0 + py; az[3] = z0 + pz;
            capture.addDirectQuad(ax, ay, az, ZERO_UV, ZERO_UV, 0f, 0f, 0f, color);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            commit();
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = 1f;
            this.color = -1;
            this.pending = true;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            color = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            return this;
        }

        @Override public VertexConsumer setColor(int color) { this.color = color; return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setUv3(float u, float v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        @Override public VertexConsumer setLineWidth(float width) { this.width = width; return this; }
    }

    @Override
    public void submitQuadParticleGroup(QuadParticleRenderState particles) {
    }

    @Override
    public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
    }

    @Override
    public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
    }
}
