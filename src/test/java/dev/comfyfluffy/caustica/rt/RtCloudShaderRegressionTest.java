package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression guards for cloud/shadow interactions that are easy to re-break in shader-only changes. */
final class RtCloudShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path CLOUDS = REPO_ROOT.resolve("shaders/world/clouds.slang");
    private static final Path WORLD_RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");
    private static final Path WORLD_RMISS = REPO_ROOT.resolve("shaders/world/world.rmiss.slang");
    private static final Path RT_COMPOSITE =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");

    /**
     * The celestials atlas binding in {@code world.rmiss} must equal the descriptor slot the
     * pipeline actually writes it to: {@code skyBinding = firstExtraBinding(3) + GUIDE_COUNT}
     * (RtPipeline.create layout + RtComposite.ensureWorld wiring). Nothing in the toolchain ties
     * these two numbers together — a drift samples an unwritten binding, which drivers answer with
     * a black texture: the sun and moon discs silently "evaporate" while every other sky term
     * (directions, radiance, fog, god rays) keeps looking perfect. This has happened before; both
     * sides are now parsed instead of trusted, so the next guide-block change that forgets the
     * other fails CI instead of hiding the sun.
     */
    @Test
    void skyAtlasBindingFollowsGuideCount() throws IOException {
        var decl = java.util.regex.Pattern.compile(
                "\\[\\[vk::binding\\((\\d+), 0\\)]] Sampler2D celestialsAtlas")
                .matcher(Files.readString(WORLD_RMISS));
        assertTrue(decl.find(), "world.rmiss must declare the celestialsAtlas binding");
        int atlasBinding = Integer.parseInt(decl.group(1));

        String composite = Files.readString(RT_COMPOSITE);
        var guide = java.util.regex.Pattern.compile("GUIDE_COUNT = (\\d+);").matcher(composite);
        assertTrue(guide.find(), "RtComposite must define GUIDE_COUNT for the world pipeline");
        int guideCount = Integer.parseInt(guide.group(1));

        // firstExtraBinding is 3 whenever the world pipeline carries the block albedo atlas
        // (RtPipeline.create's withBlockAlbedoAtlas ? 3 : 2 — RtComposite passes true).
        assertEquals(3 + guideCount, atlasBinding,
                "world.rmiss celestialsAtlas sampling " + atlasBinding + " while the pipeline "
                        + "binds the atlas at 3 + GUIDE_COUNT(" + guideCount + ") = "
                        + (3 + guideCount) + " renders the sun/moon discs black");
    }

    @Test
    void cloudSunShadowDoesNotReuseVisibleCloudTrace() throws IOException {
        String source = Files.readString(CLOUDS);
        String body = slice(source, "public float cloudSunShadow", "// ---- Classic");

        assertFalse(body.contains("cloudTrace("),
                "sun visibility must not use the visible-cloud query with camera/view fades");
        assertFalse(body.contains("cloudAlpha("),
                "sun visibility must not inherit visible-cloud alpha fades");
        assertTrue(body.contains("float t = deckRel / lightDir.y;"),
                "cloud shadows must intersect the light ray from the receiver");
        assertTrue(body.contains("push.cloudAnchor.xy + pointRel.xz + lightDir.xz * t"),
                "cloud shadows must sample at the receiver/light intersection, not at the camera");
    }

    @Test
    void directLightingAppliesCloudShadowOnlyAfterSceneVisibilitySurvives() throws IOException {
        String source = Files.readString(WORLD_RGEN);
        String frontNeeBlock = slice(source, "float cloudShadow = 1.0;", "float activeSss");
        String sssBlock = slice(source, "VisibilityResult shadowBack", "// Preserve all local lighting");

        assertInOrder(frontNeeBlock,
                "float3 vis = shadow.transmittance;",
                "if (max(vis.r, max(vis.g, vis.b)) > 0.0) {",
                "cloudShadow = cloudSunShadow(worldPush, hitPos - worldPush.camOffset, lightDir);");
        assertInOrder(sssBlock,
                "float3 visB = shadowBack.transmittance;",
                "if (max(visB.r, max(visB.g, visB.b)) > 0.0) {",
                "if (!cloudShadowReady) {");
    }

    private static String slice(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        assertTrue(start >= 0, "missing shader snippet start: " + startNeedle);
        int end = source.indexOf(endNeedle, start);
        assertTrue(end > start, "missing shader snippet end: " + endNeedle);
        return source.substring(start, end);
    }

    private static void assertInOrder(String source, String... needles) {
        int at = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, at + 1);
            assertTrue(next > at, "expected snippet after index " + at + ": " + needle);
            at = next;
        }
    }

    /** Same root discovery pattern as RtShaderConstantMirrorTest, kept local to avoid test coupling. */
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
