package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guards for the ray-traced first-person viewmodel.
 *
 * <p>The feature is a set of agreements between five files that no compiler can check. The instance mask bit
 * the CPU gives the hand has to be the bit the shaders trace with <em>and</em> the bit every other instance
 * leaves clear; the geometry-kind flag in the CPU's 64-byte table entry has to be the lane the hit shader
 * reads; and cancelling vanilla's hand pass has to be authorised by exactly the frames the tracer really
 * instanced the mesh. Break any one of those and the failure is silent and visible at once — a hand drawn
 * twice, a hand that vanishes, a hand that throws a shadow over the whole screen — so all of them are pinned
 * here against the real sources instead of against a duplicated constant.
 */
final class RtViewmodelRayTracingTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path WORLD_COMMON = REPO_ROOT.resolve("shaders/world/world_common.slang");
    private static final Path TRACE = REPO_ROOT.resolve("shaders/world/trace.slang");
    private static final Path WORLD_RCHIT = REPO_ROOT.resolve("shaders/world/world.rchit.slang");
    private static final Path WORLD_PRIMARY = REPO_ROOT.resolve("shaders/world/world_primary.rgen.slang");
    private static final Path WORLD_RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");
    private static final Path VIEWMODEL = REPO_ROOT.resolve(
            "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtViewmodel.java");
    private static final Path ENTITIES = REPO_ROOT.resolve(
            "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java");
    private static final Path ACCEL = REPO_ROOT.resolve(
            "src/main/java/dev/comfyfluffy/caustica/rt/accel/RtAccel.java");
    private static final Path COMPOSITE = REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
    private static final Path GAME_RENDERER_MIXIN = REPO_ROOT.resolve(
            "src/main/java/dev/comfyfluffy/caustica/mixin/GameRendererMixin.java");
    private static final Path HAND_MIXIN = REPO_ROOT.resolve(
            "src/main/java/dev/comfyfluffy/caustica/mixin/FirstPersonHandsAndItemsRendererMixin.java");
    private static final Path MIXINS_JSON = REPO_ROOT.resolve("src/main/resources/caustica.mixins.json");
    private static final Path ABI = REPO_ROOT.resolve("docs/minecraft-26.3-abi.tsv");

    /**
     * The hand's instance mask must be a bit nothing else owns, and every default instance mask must leave it
     * free — otherwise a viewmodel-only trace also hits terrain and the "layer above the blocks" pass is
     * really just "trace the world twice".
     */
    @Test
    void viewmodelMaskBitIsReservedOnBothSides() throws IOException {
        int cullViewmodel = hex(constant(Files.readString(TRACE), "CULL_VIEWMODEL"));
        int viewmodelMask = hex(constant(Files.readString(ENTITIES), "VIEWMODEL_MASK"));
        assertEquals(cullViewmodel, viewmodelMask,
                "RtEntities' instance mask and the shader's overlay cull mask must be the same single bit");
        assertEquals(1, Integer.bitCount(cullViewmodel), "the viewmodel mask must be exactly one bit");

        int maskAll = hex(constant(Files.readString(ENTITIES), "MASK_ALL"));
        assertEquals(0, maskAll & cullViewmodel,
                "the default world instance mask must leave the viewmodel bit clear");
        int defaultInstanceMask = decode(pattern(Files.readString(ACCEL),
                "this\\(transform3x4, blasDeviceAddress, customIndex, (0x[0-9a-fA-F]+|\\d+), 0\\)"));
        assertEquals(0, defaultInstanceMask & cullViewmodel,
                "RtAccel.Instance's 3-argument default (terrain sections) must also leave the bit clear");
        assertNotEquals(0, defaultInstanceMask & 0x01, "terrain must still answer shadow rays");
        assertNotEquals(0, defaultInstanceMask & 0x02, "terrain must still answer the primary ray");
        assertNotEquals(0, maskAll & hex(constant(Files.readString(TRACE), "CULL_VIEWMODEL_SHADOW")),
                "terrain must still occlude a viewmodel surface's own shadow rays");
        assertEquals(0, cullViewmodel & hex(constant(Files.readString(TRACE), "CULL_HAND_LIGHT")),
                "the held light's shadow rays must not be blocked by the held item itself");
        assertEquals(0, cullViewmodel & hex(constant(Files.readString(TRACE), "CULL_VIEWMODEL_SHADOW")),
                "the viewmodel must not occlude itself while being shaded");
    }

    /** The kind flag travels in the geometry table's own lane; both sides must agree on which one. */
    @Test
    void viewmodelKindFlagAgreesBetweenCpuAndShader() throws IOException {
        String common = Files.readString(WORLD_COMMON);
        int shaderFlag = decode(pattern(common, "ENTITY_GEOM_VIEWMODEL\\s*=\\s*(\\d+)u?\\s*;"));
        int javaFlag = decode(pattern(Files.readString(VIEWMODEL),
                "GEOM_FLAG_VIEWMODEL\\s*=\\s*(\\d+)\\s*;"));
        assertEquals(shaderFlag, javaFlag, "ENTITY_GEOM_VIEWMODEL and RtViewmodel.GEOM_FLAG_VIEWMODEL differ");

        // std430 order, so the CPU's byte lane has to match the field's position in the struct.
        String geometry = slice(common, "public struct EntityGeom {", "};");
        assertInOrder(geometry, "public uint triBase[2];", "public uint flags;", "public uint reserved;");
        int entryBytes = decode(pattern(Files.readString(ENTITIES), "TABLE_ENTRY_BYTES = (\\d+);"));
        assertEquals(64, entryBytes, "the entity geometry table entry layout is pinned at 64 bytes");
        assertTrue(Files.readString(ENTITIES).contains("MemoryUtil.memPutInt(entry + 56, geomFlags);"),
                "EntityGeom.flags is the entry's +56 lane; writing the kind flag anywhere else is a silent no-op");
    }

    /**
     * Criterion: shading the viewmodel ignores the player's own body and the geometry held in the hand,
     * while the world still shades it. That is the payload-aware {@code visibility()} — one function, so the
     * sun, the RIS emitters, fog and the backface shadow all inherit the rule.
     */
    @Test
    void shadowRaysFromAViewmodelSurfaceSwapTheirCullMask() throws IOException {
        String trace = Files.readString(TRACE);
        String visibility = slice(trace, "public VisibilityResult visibility(float3 origin, float3 dir, float tmax)",
                "\n}\n");
        assertTrue(visibility.contains("PAYLOAD_VIEWMODEL"),
                "visibility() must key off the payload's viewmodel flag, not off a caller-threaded parameter");
        assertTrue(visibility.contains("CULL_VIEWMODEL_SHADOW") && visibility.contains("CULL_SECONDARY"),
                "a viewmodel surface swaps to the player-free mask; everything else keeps the ordinary one");
        // The shadow trace must not clobber the surface it is shading, or the swap would only survive the
        // first occlusion query of the pass.
        assertTrue(trace.contains("TraceRay(topLevelAS,\n"
                        + "            RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,\n"
                        + "            cullMask, SBT_SHADOW, SBT_STRIDE_BUCKET, MISS_GUIDE,\n"
                        + "            makeRay(origin, RAY_TMIN, dir, tmax), shadowPayload);"),
                "visibilityCulled must trace into its own local payload so the caller's payload survives");
    }

    /** The hit shader must mark the hand on every entity return, and keep it out of the refraction paths. */
    @Test
    void hitShaderTagsEveryViewmodelReturn() throws IOException {
        String rchit = Files.readString(WORLD_RCHIT);
        String entity = slice(rchit, "if ((InstanceID() & ENTITY_BIT) != 0u) {", "\n    }\n\n    Section sec");
        assertEquals(3, count(entity, "payload.flags |= PAYLOAD_VIEWMODEL;"),
                "portal, glint and ordinary entity hits all need the tag: each one returns early");
        assertTrue(entity.contains("bool viewmodelGeom = (g.flags & ENTITY_GEOM_VIEWMODEL) != 0u;"),
                "the kind flag is read once from the geometry table entry");
        assertTrue(entity.contains("material == MATERIAL_DIELECTRIC || material == MATERIAL_WATER"),
                "a held glass or water block is forced opaque, or it refracts the image it is drawn over");
        assertInOrder(entity, "uint material = header.model;", "bool viewmodelGeom =",
                "ParallaxHit entityParallax = parallaxFlat(euvCoord, n);");
    }

    /**
     * Above-blocks layering: primary rays only, never ranked against the world hit's distance (the hand wins
     * where it covers, as it does in vanilla), bounded by the reach and the cone the frame published, and
     * rolled back when the extra trace finds nothing.
     */
    @Test
    void aboveBlocksLayerRunsOnPrimaryRaysOnly() throws IOException {
        String trace = Files.readString(TRACE);
        String overlay = slice(trace, "public void traceViewmodelOverlay(float3 ro, float3 rd)", "\n}\n");
        assertTrue(overlay.contains("worldPush.viewmodel.x"), "the pass must switch itself off at reach 0");
        // The viewmodel is an overlay, so the hand wins wherever it covers a primary ray no matter how near the
        // block in front of it was — a gate on the world hit's distance inverts that (it shows the hand against
        // the sky and hides it behind every wall and every floor you look down at). Pinned as a negative
        // because the shape of the correct code is "there is no such test".
        assertFalse(overlay.contains("payload.hitT") && overlay.indexOf("payload.hitT") < overlay.indexOf("TraceRay"),
                "the overlay must not rank itself against the world hit before tracing");
        for (String forbidden : new String[] {"hitT >= reach", "hitT < reach", "hitT > reach"}) {
            assertFalse(overlay.contains(forbidden), "distance gate reappeared: " + forbidden);
        }
        // Cost is instead bounded by direction, and only ever conservatively: a cosine of -1 always traces.
        assertTrue(overlay.contains("worldPush.viewmodelCull") && overlay.contains("viewmodelCull.w > -1.0"),
                "the extra trace must be gated by the capture's own directional bound, with the permissive "
                        + "sentinel favouring tracing over culling");
        assertTrue(overlay.contains("Payload worldHit = payload;") && overlay.contains("payload = worldHit;"),
                "a viewmodel miss must restore the world/sky payload, not leave the ray traced at sky radiance");
        assertTrue(overlay.contains("MISS_GUIDE"),
                "MISS_RADIANCE would march the atmosphere for a result that is thrown away");
        assertTrue(overlay.contains("makeRay(ro, 0.0, rd, reach)"), "tmax must be the reach, not 10000");

        String primary = Files.readString(WORLD_PRIMARY);
        assertInOrder(primary, "traceRadiance(CULL_PRIMARY, ro, 0.0, rd, 10000.0,",
                "traceViewmodelOverlay(ro, rd);", "if (payload.hitT < 0.0) {");
        String path = slice(Files.readString(WORLD_RGEN), "for (int bounce = seg.bounce; bounce <= maxBounces; bounce++)",
                "// First-bounce hit distance capture");
        assertInOrder(path, "if (bounce == 0) {", "traceViewmodelOverlay(ro, rd);", "}");
    }

    /** The reach the shaders bound their trace by has to come from the frame that instanced the mesh. */
    @Test
    void pushLanePublishesOnlyThisFramesReach() throws IOException {
        String composite = Files.readString(COMPOSITE);
        assertTrue(composite.contains("new Float4(RtViewmodel.INSTANCE.frameOverlayReach(), 0.0f, 0.0f, 0.0f)"),
                "worldPush.viewmodel must carry the reach, and only when the frame really instanced the hand");
        assertTrue(composite.contains("RtViewmodel.INSTANCE.frameOverlayCull()"),
                "worldPush.viewmodelCull must come from the same frame's capture, or a stale cone would cull a "
                        + "new pose off the screen");
        String viewmodel = Files.readString(VIEWMODEL);
        assertTrue(viewmodel.contains("cullCos = -1.0f;"),
                "a capture that cannot bound itself must publish the always-trace sentinel");
        assertInOrder(viewmodel, "renderer.submitHandsWithItems(", "overlayBounds(vertices);", "pending = true;");
        assertInOrder(composite, "RtEntities.INSTANCE.beginFrame(ctx, staticInstances,",
                "new WorldPushData(");
        String common = Files.readString(WORLD_COMMON);
        assertInOrder(common, "public float4   lightning;", "public float4   viewmodel;",
                "public float4   viewmodelCull;", "};");
    }

    /** Vanilla's hand is cancelled only for the one submission we replaced, and only with the mesh on screen. */
    @Test
    void vanillaSubmissionIsCancelledConditionally() throws IOException {
        String hand = Files.readString(HAND_MIXIN);
        assertTrue(hand.contains("at = @At(\"HEAD\"), cancellable = true"), "the cancel point is the method head");
        assertTrue(hand.contains("if (RtViewmodel.INSTANCE.shouldSuppressVanillaSubmission()) {")
                        && hand.contains("ci.cancel();"),
                "cancellation must be conditional on this frame's capture having been instanced");
        // A capture that is not cancelled is a hand drawn twice: our own re-entrant submission must be able
        // to run through the very method this mixin cancels.
        String viewmodel = Files.readString(VIEWMODEL);
        assertInOrder(viewmodel, "capturing = true;", "renderer.submitHandsWithItems(", "capturing = false;");
        assertTrue(viewmodel.contains("suppressVanillaSubmission = true;"),
                "the latch is raised only after a successful capture, never during it");
        assertTrue(viewmodel.contains("pending = false;\n        suppressVanillaSubmission = false;\n        instancedThisFrame = false;"),
                "every latch is cleared at frame start, so a stale one can never hide the hand");
    }

    /** The seam rebuilds vanilla's pose from the game's own pieces, with vanilla's own gates. */
    @Test
    void captureSeamReplicatesVanillasHandPass() throws IOException {
        String mixin = Files.readString(GAME_RENDERER_MIXIN);
        assertInOrder(mixin,
                "@Invoker(\"bobHurt\")",
                "@Invoker(\"bobView\")");
        assertInOrder(mixin, "poseStack.mulPose(cameraState.viewRotationMatrix.invert(new Matrix4f()));",
                "this.caustica$callBobHurt(cameraState, poseStack);",
                "if (optionsState.bobView) {",
                "this.caustica$callBobView(cameraState, poseStack);");
        for (String gate : new String[] {"cameraState.isPanoramicMode", "playerState.hasPlayer",
                "optionsState.cameraType.isFirstPerson()", "cameraState.entityRenderState.isSleeping",
                "isHudHidden", "GameType.SPECTATOR"}) {
            assertTrue(mixin.contains(gate), "renderItemInHand's gate is missing: " + gate);
        }
        assertInOrder(mixin, "this.caustica$captureViewmodelForRt(cameraState, playerState, optionsState);",
                "WorldRenderScaler.INSTANCE.end(this.mainRenderTarget);");
    }

    /** Registration, ABI pinning and the default: all three are silent failures if they regress. */
    @Test
    void mixinIsRegisteredTsvIsPinnedAndDefaultIsOff() throws IOException {
        assertTrue(Files.readString(MIXINS_JSON).contains("\"FirstPersonHandsAndItemsRendererMixin\""),
                "an unregistered mixin silently never runs, and the hand would be drawn twice");
        String abi = Files.readString(ABI);
        assertTrue(abi.contains("FirstPersonHandsAndItemsRenderer\tM\tsubmitHandsWithItems"),
                "the submission this feature replays and cancels must stay pinned by verifyMinecraftAbi");
        assertTrue(abi.contains("GameRenderer\tM\tbobHurt") && abi.contains("GameRenderer\tM\tbobView"),
                "the pose transforms the seam borrows through @Invoker are ABI dependencies now");
        assertTrue(abi.contains("CameraRenderState\tF\thudFov"),
                "hand.fov-follows-camera, the only FOV knob the traced hand has, reads this field");
        assertEquals("hand.ray-traced", CausticaConfig.Rt.Hand.PATH_TRACED.tomlPath());
        assertEquals(Boolean.FALSE, CausticaConfig.Rt.Hand.PATH_TRACED.defaultValue(),
                "the viewmodel toggle must default to off");
        assertTrue(CausticaConfig.settings().contains(CausticaConfig.Rt.Hand.PATH_TRACED),
                "an unregistered setting never reaches the config file or the reset-to-defaults list");
    }

    // ---- helpers ----

    private static int hex(String source) {
        return Integer.parseInt(source.replace("0x", "").replace("u", ""), 16);
    }

    /** Accepts either spelling of a literal, so neither language has to write its masks a certain way. */
    private static int decode(String source) {
        return Integer.decode(source);
    }

    /** {@code NAME = value;} for either language's constant form. */
    private static String constant(String source, String name) throws IOException {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*(0x[0-9a-fA-F]+|\\d+)u?\\s*;").matcher(source);
        assertTrue(matcher.find(), "constant " + name + " not found");
        return matcher.group(1);
    }

    private static String pattern(String source, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(source);
        assertTrue(matcher.find(), "pattern not found: " + regex);
        return matcher.group(1);
    }

    private static int count(String source, String needle) {
        int found = 0;
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) {
            found++;
        }
        return found;
    }

    private static String slice(String source, String from, String until) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "missing start marker: " + from);
        int end = source.indexOf(until, start);
        assertTrue(end > start, "missing end marker after " + from);
        return source.substring(start, end);
    }

    private static void assertInOrder(String source, String... needles) {
        int at = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, at + 1);
            assertTrue(next > at, "expected after the previous marker: " + needle);
            at = next;
        }
    }

    /**
     * Walk up from the working directory to the directory that holds both {@code shaders} and
     * {@code src}, so the test works whether Gradle runs it from the project directory or the root.
     */
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
