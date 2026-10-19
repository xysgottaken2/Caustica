package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guards for the path-traced first-person viewmodel.
 *
 * <p>The viewmodel is captured from vanilla's own hand submission and uploaded as ordinary entity
 * geometry, which means its guarantees live in several files at once: a geometry-table flag lane
 * (Java writer + Slang reader), a payload bit, the held-item-light suppression, and the TLAS instance
 * mask.
 * None of these are checked by the compiler, and dropping any one of them fails quietly — a shadow
 * appears, the arm pokes through a wall, or the hand never renders at all. These tests pin the wiring;
 * the camera-space transform itself is covered by {@code RtViewmodelTransformTest}.
 */
final class RtViewmodelShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path WORLD_COMMON = REPO_ROOT.resolve("shaders/world/world_common.slang");
    private static final Path WORLD_RCHIT = REPO_ROOT.resolve("shaders/world/world.rchit.slang");
    private static final Path WORLD_CORE = REPO_ROOT.resolve("shaders/world/world_core.slang");
    private static final Path TRACE = REPO_ROOT.resolve("shaders/world/trace.slang");
    private static final Path LIGHTING = REPO_ROOT.resolve("shaders/world/lighting.slang");
    private static final Path PRIMARY_RGEN = REPO_ROOT.resolve("shaders/world/world_primary.rgen.slang");
    private static final Path INDIRECT_RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");
    private static final Path RT_ENTITIES =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java");
    private static final Path VIEWMODEL_CAPTURE =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/entity/RtViewmodelCapture.java");
    private static final Path CONTROLLER =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/client/VanillaRenderController.java");
    private static final Path GAME_RENDERER_MIXIN =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/mixin/GameRendererMixin.java");
    private static final Path ABI_TSV = REPO_ROOT.resolve("docs/minecraft-26.3-abi.tsv");
    private static final Path ACCEL = REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/accel/RtAccel.java");

    @Test
    void viewmodelIsPrimaryOnlyAndCannotCastShadowsOrLightTheWorld() {
        String entities = read(RT_ENTITIES);

        // The layer bit (0x08) is carried by the viewmodel alone. No shadow/GI/reflection/held-light mask
        // includes it, so the instance can neither cast shadows nor leak into indirect lighting.
        assertTrue(entities.contains("private static final int VIEWMODEL_MASK = RtAccel.MASK_VIEWMODEL_LAYER;"),
                "the viewmodel must carry the dedicated layer bit");
        assertTrue((0xF4 & 0x08) == 0,
                "CULL_HAND_LIGHT (0xF4) must keep excluding the viewmodel so a held torch does not shadow itself");
        assertTrue((0x01 & 0x08) == 0 && (0x02 & 0x08) == 0,
                "secondary and ordinary primary masks must not see the layer");
        assertTrue(entities.contains("ENTITY_BIT, VIEWMODEL_MASK"),
                "the viewmodel instance must be an entity-kind instance with the primary-only mask");
    }

    @Test
    void geometryTableCarriesTheViewmodelFlagEndToEnd() {
        String common = read(WORLD_COMMON);
        String entities = read(RT_ENTITIES);

        assertTrue(common.contains("public static const uint ENTITY_GEOM_VIEWMODEL = 1u;"));
        assertTrue(common.contains("public uint flags;"), "EntityGeom must expose the flags lane");
        assertTrue(entities.contains("public static final int ENTITY_FLAG_VIEWMODEL = 1;"));
        // std430 stride is 64 bytes; the flag lane is the first spare word after triBase[2].
        assertTrue(entities.contains("TABLE_ENTRY_BYTES = 64"));
        assertTrue(entities.contains("MemoryUtil.memPutInt(entry + 56, flags);"),
                "flags must land in EntityGeom.flags (+56), the word world.rchit reads");
        assertTrue(entities.contains("ENTITY_FLAG_VIEWMODEL);"),
                "the transient upload must stamp the viewmodel flag on its table entry");
    }

    @Test
    void viewmodelHitIsTaggedAndSurvivesTheMaterialWrite() {
        String rchit = read(WORLD_RCHIT);
        String core = read(WORLD_CORE);

        assertTrue(core.contains("public bool payloadViewmodel()"),
                "raygen needs a payload accessor for the viewmodel classification");
        assertTrue(rchit.contains("if ((g.flags & ENTITY_GEOM_VIEWMODEL) != 0u)"));
        assertTrue(rchit.contains("payload.flags |= PAYLOAD_VIEWMODEL;"));
        // payloadSetPacked rewrites the whole flags word; the viewmodel classification must survive it or
        // every ordinary material return below would erase the tag and the held light would hit the hand.
        assertTrue(rchit.contains("(payload.flags & PAYLOAD_VIEWMODEL)"),
                "payloadSetPacked must preserve PAYLOAD_VIEWMODEL across the material write");
    }

    @Test
    void viewmodelIsAVisualLayerAboveTheWorld() {
        String entities = read(RT_ENTITIES);
        String direct = read(INDIRECT_RGEN);
        String trace = read(TRACE);
        String lighting = read(LIGHTING);

        // The viewmodel is uploaded through the transient BLAS path into this frame's instance list, but
        // on a visibility bit of its own: the camera ray resolves that layer before the world and keeps it
        // on a hit regardless of distance, which is the only rule that cannot break at some camera angle
        // (two geometric remedies were tried and both could still be defeated by the wall's distance).
        assertTrue(entities.contains("appendCapture(ctx, build, vmCapture, motion, -1, ENTITY_BIT, VIEWMODEL_MASK,"),
                "the viewmodel must go through the same transient instance upload as particles");
        assertTrue(entities.contains("private static final int VIEWMODEL_MASK = RtAccel.MASK_VIEWMODEL_LAYER;"),
                "the viewmodel must carry its own layer bit, not the ordinary primary mask");
        assertTrue(read(ACCEL).contains("public static final int MASK_VIEWMODEL_LAYER = 0x08;"));
        assertTrue(read(ACCEL).contains("public static final int MASK_WORLD = 0xF7;"),
                "every world instance must leave the layer bit clear, or a layer trace could hit the world");

        String traceText = read(TRACE);
        assertTrue(traceText.contains("public static const uint CULL_VIEWMODEL = 0x08u;"));
        assertTrue(traceText.contains("public void traceViewmodelLayer("),
                "the ray generation needs a viewmodel-only trace");
        // Layer first, world only on a miss — in BOTH passes, or guides (pass A) and shading (pass B)
        // would disagree about which surface the camera ray hit.
        assertTrue(read(PRIMARY_RGEN).contains("traceViewmodelLayer(ro, rd, 10000.0, rayConeWidth, rayConeSpread);\n"
                        + "        if (!viewmodelLayerHit()) {"),
                "pass A must resolve the viewmodel layer before the world ray");
        assertTrue(read(INDIRECT_RGEN).contains("if (bounce == 0) {\n"
                        + "            traceViewmodelLayer(ro, rd, 10000.0, rayConeWidth, rayConeSpread);"),
                "pass B's camera ray must resolve the same layer");
        // The geometric clearance experiments must not come back: the layer has no distance to get wrong.
        assertTrue(!entities.contains("viewmodelPullIn"));
        assertTrue(!read(VIEWMODEL_CAPTURE).contains("clearOfBlock"));
        assertTrue(!entities.contains("ClipContext.Block.COLLIDER"));

        // The hand must not receive its own carried light: the analytic lamp sits inside the item's flame
        // geometry, so NEE from the hand's own surface would light it from the inside.
        assertTrue(direct.contains("bool isViewmodelEarly = payloadViewmodel();"));
        assertTrue(direct.contains("float3 handIrr = isViewmodelEarly"));
        assertTrue(direct.contains("? float3(0.0, 0.0, 0.0)"));

        // ...and it must not receive the player's body either. In first person that entity keeps the
        // secondary bit, so an ordinary shadow ray from the arm lands the avatar's silhouette on it.
        assertTrue(trace.contains("public static const uint CULL_VIEWMODEL_SHADOW = 0xF4u;"),
                "the viewmodel needs its own shadow cull mask with the player's bit removed");
        assertTrue((0xF4 & 0x01) == 0, "CULL_VIEWMODEL_SHADOW must exclude the first-person player");
        assertTrue((0xF4 & 0x02) == 0, "CULL_VIEWMODEL_SHADOW must exclude particles");
        assertTrue((0xF4 & 0x08) == 0, "CULL_VIEWMODEL_SHADOW must exclude the viewmodel layer itself");
        assertTrue(direct.contains("uint shadowCull = isViewmodelEarly ? CULL_VIEWMODEL_SHADOW : CULL_SECONDARY;"),
                "the receiver must pick its shadow mask from the viewmodel classification");
        assertTrue(direct.contains("visibilityCulled(p, lightDir, 10000.0, shadowCull)"),
                "the sun NEE must honour the per-receiver shadow mask");
        assertTrue(direct.contains("twoSidedEmitter, activeSss, shadowCull) * estimatorScale;"),
                "RIS/ReSTIR shading must honour the per-receiver shadow mask");
        assertTrue(direct.contains("twoSidedEmitter, activeSss, shadowCull, risDiff, risSpec);"),
                "the split RIS shading must honour the per-receiver shadow mask too");
        assertTrue(lighting.contains("float sss, uint shadowCull) {"),
                "shadeReservoir must take the receiver's shadow mask");
        assertTrue(lighting.contains("dist * 0.999, shadowCull)"),
                "the reservoir shadow ray must be culled with the receiver's mask");
    }

    @Test
    void captureWrapsVanillasOwnHandSubmission() {
        String capture = read(VIEWMODEL_CAPTURE);
        String mixin = read(GAME_RENDERER_MIXIN);
        String controller = read(CONTROLLER);
        String abi = read(ABI_TSV);

        String submitHandsDescriptor = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;"
                + "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                + "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
                + "Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V";
        assertTrue(mixin.contains(submitHandsDescriptor),
                "the capture hook must target the ABI-confirmed submission descriptor");
        assertTrue(mixin.contains("@ModifyArg(method = \"renderItemInHand\""));
        assertTrue(mixin.contains("RtViewmodelCapture.INSTANCE.begin(collector)"));
        assertTrue(mixin.contains("RtViewmodelCapture.INSTANCE.end(cameraState.viewRotationMatrix)"),
                "the seal must carry the capture frame's camera rotation into the capture");
        assertTrue(abi.contains("submitHandsWithItems\t(F"), "the ABI contract must list the captured method");
        assertTrue(capture.contains("this.collector.delegate(this.capturedOnce ? null : vanilla);"),
                "until a capture succeeds the vanilla hand must keep drawing (no disappearing viewmodel)");
        assertTrue(controller.contains("public boolean shouldRenderRtViewmodel()"));
        assertTrue(controller.contains("this.rtActive && this.baseReady && this.rtRenderedThisFrame"),
                "the viewmodel may only replace the vanilla hand after a successful RT world composite");
    }

    @Test
    void handFovIsRestoredByLateralScaleNotByMovingTheArm() {
        String entities = read(RT_ENTITIES);

        assertTrue(entities.contains("private static final float VANILLA_HAND_FOV = 70.0f;"));
        assertTrue(entities.contains("float tanWorld = 1.0f / m11;"));
        assertTrue(entities.contains("float tanHand = (float) Math.tan(Math.toRadians(handFov) * 0.5);"));
        assertTrue(entities.contains("return Math.max(0.05f, Math.min(20.0f, tanWorld / tanHand));"));
        assertTrue(entities.contains("float handFov = RtViewmodelCapture.INSTANCE.handFov();"),
                "the lateral scale must use the FOV vanilla actually built the hand projection with");
        assertTrue(read(GAME_RENDERER_MIXIN).contains("RtViewmodelCapture.INSTANCE.setHandFov(applied);"),
                "the hand-FOV hook must record the exact projection FOV for the next frame's capture");
        assertTrue(read(VIEWMODEL_CAPTURE).contains("public void setHandFov(float fov)"),
                "the recorded hand FOV is only meaningful when the capture exposes it");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("shaders/world"))
                    && Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not locate the repository root from " + dir);
    }
}
