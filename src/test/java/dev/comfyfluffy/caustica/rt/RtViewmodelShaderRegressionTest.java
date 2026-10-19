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

    @Test
    void viewmodelIsPrimaryOnlyAndCannotCastShadowsOrLightTheWorld() {
        String entities = read(RT_ENTITIES);

        // Primary-camera mask only. Secondary culling (shadows/GI/reflections/refraction) is bit 0, and
        // the analytic held-item light traces with bits 2..7, so a 0x02 instance can do neither.
        assertTrue(entities.contains("private static final int VIEWMODEL_MASK = MASK_PRIMARY;"),
                "the viewmodel must share the particles' primary-only visibility");
        assertTrue((0xFC & 0x02) == 0,
                "CULL_HAND_LIGHT (0xFC) must keep excluding the viewmodel so a held torch does not shadow itself");
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
    void viewmodelStaysInFrontOfBlocksAndIgnoresThePlayersOwnBody() {
        String entities = read(RT_ENTITIES);
        String direct = read(INDIRECT_RGEN);
        String trace = read(TRACE);
        String lighting = read(LIGHTING);

        // The viewmodel is uploaded through the transient BLAS path into this frame's instance list: a
        // normal TLAS instance, so nearest-hit already depth-tests it against the world. No extra
        // occlusion ray is needed (and an earlier probe culled with CULL_SECONDARY would have hidden the
        // arm behind the player's own first-person body, which is exactly the one occluder it must ignore).
        assertTrue(entities.contains("appendCapture(ctx, build, vmCapture, motion, -1, ENTITY_BIT, VIEWMODEL_MASK,"),
                "the viewmodel must go through the same transient instance upload as particles");
        for (Path shader : new Path[] {TRACE, PRIMARY_RGEN, INDIRECT_RGEN}) {
            assertTrue(!read(shader).contains("resolveViewmodelOcclusion"),
                    "no depth-test ray may remain: the TLAS nearest hit is the depth test (" + shader + ")");
        }

        // Nearest-hit alone only CUTS the arm at the wall, so the upload also pulls the mesh in front of
        // every block. The clearance has to stay the exact per-block solve: a sampled version let block
        // corners slip between probe directions and the hand poked through walls at some camera angles.
        assertTrue(entities.contains("RtViewmodelCapture.clearOfBlock(pullIn, boxMin, boxMax"),
                "the upload must scale the captured mesh clear of nearby blocks");
        assertTrue(entities.contains("RtViewmodelCapture.scaleUniform(vmCapture.verts.elements()"),
                "the clearance must be applied as a projection-exact uniform scale");
        assertTrue(!entities.contains("ClipContext.Block.COLLIDER"),
                "block clearance must not go back to sampled ray probes");

        // The hand must not receive its own carried light: the analytic lamp sits inside the item's flame
        // geometry, so NEE from the hand's own surface would light it from the inside.
        assertTrue(direct.contains("bool isViewmodelEarly = payloadViewmodel();"));
        assertTrue(direct.contains("float3 handIrr = isViewmodelEarly"));
        assertTrue(direct.contains("? float3(0.0, 0.0, 0.0)"));

        // ...and it must not receive the player's body either. In first person that entity keeps the
        // secondary bit, so an ordinary shadow ray from the arm lands the avatar's silhouette on it.
        assertTrue(trace.contains("public static const uint CULL_VIEWMODEL_SHADOW = 0xFCu;"),
                "the viewmodel needs its own shadow cull mask with the player's bit removed");
        assertTrue((0xFC & 0x01) == 0, "CULL_VIEWMODEL_SHADOW must exclude the first-person player");
        assertTrue((0xFC & 0x02) == 0, "CULL_VIEWMODEL_SHADOW must exclude the viewmodel itself");
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
