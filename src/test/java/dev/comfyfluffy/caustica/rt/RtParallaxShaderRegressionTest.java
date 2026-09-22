package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guards for Parallax Occlusion Mapping.
 *
 * <p>POM intersects the LabPBR height field as a grid of per-texel box columns — the same analytic
 * grid walk the classic cloud deck uses — instead of sampling it at a fixed number of depth layers.
 * The difference is not a tuning detail: a layer stack has no sides, so a grazing or close-up view
 * looks straight through the slices. These tests pin the properties that make the walk a solid
 * surface, because all of them are easy to lose in an innocent-looking shader edit.
 */
final class RtParallaxShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path WORLD_RCHIT = REPO_ROOT.resolve("shaders/world/world.rchit.slang");
    private static final Path WORLD_COMMON = REPO_ROOT.resolve("shaders/world/world_common.slang");
    private static final Path COMPOSITE =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");

    @Test
    void heightFieldIsWalkedAsAColumnGridNotAsDepthLayers() throws IOException {
        String trace = slice(Files.readString(WORLD_RCHIT), "ParallaxHit parallaxTrace(", "\n}\n");

        assertFalse(trace.contains("layerDepth"), "the layer march must not come back: layers have no sides");
        assertFalse(trace.contains("layerCount"), "the walk is bounded by texel crossings, not by layers");
        // Amanatides & Woo, exactly like cloudClassicBoxes: per-axis boundary distances and steps.
        assertInOrder(trace,
                "float2 tDelta",
                "float2 tMax",
                "for (int crossing = 0; crossing < maxCrossings; ++crossing)",
                "float top = 1.0 - parallaxColumnHeight(",
                "float exitDepth = min(min(tMax.x, tMax.y), 1.0);",
                "if (exitDepth >= top)",
                "if (tMax.x < tMax.y)");
        // The two hit kinds: a column top, or the side wall the ray ran into below that top.
        assertTrue(trace.contains("if (enterAxis >= 0 && enterDepth > top)"),
                "entering a cell already below its column top is a side-wall hit");
        assertTrue(trace.contains("hitDepth = max(top, enterDepth);"), "otherwise the hit is the column top");
    }

    @Test
    void columnHeightsAreTexelExactAndStayInsideTheSprite() throws IOException {
        String source = Files.readString(WORLD_RCHIT);
        String sampler = slice(source, "float parallaxColumnHeight(", "\n}\n");

        // Columns ARE texels: a bilinear tap between two of them rounds the boxes back into a smear.
        assertTrue(sampler.contains("+ 0.5) / grid"), "columns must be sampled at their texel centre");
        assertFalse(sampler.contains("pomSmoothingEnabled"),
                "the height field is texel-exact by construction; the smoothing toggle must not reach it");
        assertTrue(sampler.contains("floor(float2(cell) / grid) * grid"),
                "out-of-range cells must wrap inside the sprite, never into a neighbouring atlas sprite");
        assertFalse(source.contains("samplePageHeight("),
                "the old bilinear height sampler is gone; parallaxColumnHeight is the only height reader");
    }

    @Test
    void blockSpritesTileWhileEntityAtlasesStopAtTheirIsland() throws IOException {
        String source = Files.readString(WORLD_RCHIT);

        assertTrue(source.contains("terrainParallax = parallaxTrace(materialHeader, uv, blockLod, n, tp0, tp1, tp2,\n"
                        + "                uv0, uv1, uv2, vdir, true);"),
                "block sprites tile, so the walk wraps and the relief runs into the block border");
        assertTrue(source.contains("entityParallax = parallaxTrace(header, euvCoord, entityLod, n, ep0, ep1, ep2,\n"
                        + "                    euv[e0], euv[e1], euv[e2], vdir, false);"),
                "entity textures are model atlases: the walk must stop rather than tile across islands");

        // The inward pinch at block borders was the old unconditional edge fade. Only the non-tiling
        // (entity) path may still fade, because only it has to stop at the sprite.
        String trace = slice(source, "ParallaxHit parallaxTrace(", "\n}\n");
        String edgeFade = slice(trace, "float edgeFade = 1.0;", "float reliefDepth");
        assertTrue(edgeFade.contains("if (!repeat)"), "tiling sprites must not fade the relief at the border");
    }

    @Test
    void sideWallsReplaceTheMappedNormalOnBothHitPaths() throws IOException {
        String source = Files.readString(WORLD_RCHIT);

        assertTrue(source.contains("n = entityParallax.wall ? entityParallax.normal : surface.normal;"),
                "an entity wall hit must shade with the wall, not with the face's normal map");
        assertTrue(source.contains("if (terrainParallax.wall) {\n        n = terrainParallax.normal;\n    }"),
                "a terrain wall hit must shade with the wall, not with the face's normal map");
        // Raygen offsets the next ray along the shading normal and spins its cosine hemisphere around
        // it, so a wall reported exactly parallel to the face would fire half of its bounces into the
        // block. It has to lean back toward the face.
        assertTrue(source.contains("normalize(wall + geometricNormal * PARALLAX_WALL_TILT)"),
                "wall normals must lean toward the face they stand on");
        assertTrue(slangFloat("PARALLAX_WALL_TILT") > 0.0f, "PARALLAX_WALL_TILT must tilt the wall");
    }

    @Test
    void crossingBudgetFromJavaStaysInsideTheShaderBounds() throws IOException {
        int min = slangInt("PARALLAX_MIN_CROSSINGS");
        int max = slangInt("PARALLAX_MAX_CROSSINGS");
        assertTrue(min > 0 && min < max, "crossing bounds must be a usable range: " + min + ".." + max);

        String params = slice(Files.readString(COMPOSITE), "private static Float4 parallaxParams()", "\n    }\n");
        Matcher matcher = Pattern
                .compile("Math\\.min\\((\\d+)\\.0f,\\s*Math\\.max\\((\\d+)\\.0f")
                .matcher(params);
        assertTrue(matcher.find(), "parallaxParams must clamp the crossing budget it pushes");
        int javaMax = Integer.parseInt(matcher.group(1));
        int javaMin = Integer.parseInt(matcher.group(2));
        assertTrue(javaMin >= min, "pushed budget floor " + javaMin + " is below the shader's " + min);
        assertTrue(javaMax <= max, "pushed budget ceiling " + javaMax + " is above the shader's " + max);
        // A grazing view of a 16x sprite at the default depth needs ~25 crossings, so the floor may not
        // drop to the shader's bare minimum or default packs would truncate their own relief.
        assertTrue(javaMin >= 16, "the default budget must cover a grazing view of a vanilla-resolution sprite");
    }

    @Test
    void reliefDepthStillCollapsesWhenPomIsDisabled() throws IOException {
        String params = slice(Files.readString(COMPOSITE), "private static Float4 parallaxParams()", "\n    }\n");
        assertTrue(params.contains("float depth = enabled ? strength * 0.125f : 0.0f;"),
                "a disabled POM must push a zero relief depth, which the shader treats as \"no effect\"");

        String trace = slice(Files.readString(WORLD_RCHIT), "ParallaxHit parallaxTrace(", "\n}\n");
        assertInOrder(trace, "if (reliefDepth <= 1.0e-5)", "return flatHit;");
        assertTrue(trace.contains("if ((header.features & MATERIAL_FEATURE_NORMAL) == 0u)"),
                "a material without an _n page has no height field and must keep its flat UV");
    }

    private static int slangInt(String name) throws IOException {
        Matcher matcher = Pattern.compile("const\\s+int\\s+" + name + "\\s*=\\s*(-?\\d+)\\s*;")
                .matcher(Files.readString(WORLD_COMMON));
        assertTrue(matcher.find(), "missing shader constant: " + name);
        return Integer.parseInt(matcher.group(1));
    }

    private static float slangFloat(String name) throws IOException {
        Matcher matcher = Pattern.compile("const\\s+float\\s+" + name + "\\s*=\\s*(-?[\\d.]+)\\s*;")
                .matcher(Files.readString(WORLD_COMMON));
        assertTrue(matcher.find(), "missing shader constant: " + name);
        return Float.parseFloat(matcher.group(1));
    }

    private static String slice(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        assertTrue(start >= 0, "missing snippet start: " + startNeedle);
        int end = source.indexOf(endNeedle, start);
        assertTrue(end > start, "missing snippet end: " + endNeedle);
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

    @Test
    void everyHeightSampleIsBoundedByTheCrossingBudget() throws IOException {
        String trace = slice(Files.readString(WORLD_RCHIT), "ParallaxHit parallaxTrace(", "\n}\n");
        assertEquals(1, count(trace, "parallaxColumnHeight("),
                "the walk must read the height field from exactly one bounded loop");
        assertTrue(trace.contains("clamp(int(round(parallaxParams.y)), PARALLAX_MIN_CROSSINGS, PARALLAX_MAX_CROSSINGS)"),
                "the pushed budget must be clamped shader-side too");
        // Rather than cut a ray short (which smears a band exactly where relief reads best), an
        // over-long walk drops to a coarser mip until the whole depth range fits the budget.
        assertTrue(trace.contains("mip = min(mip + ceil(log2(needed / float(maxCrossings))), maxMaterialLod);"),
                "an unaffordable walk must coarsen the column grid instead of being truncated");
    }

    private static int count(String source, String needle) {
        int total = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + 1)) {
            total++;
        }
        return total;
    }

    /** Same root discovery pattern as the other shader regression tests, kept local. */
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
