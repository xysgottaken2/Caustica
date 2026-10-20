package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guards for the SHaRC radiance cache contract.
 *
 * <p>SHaRC was enabled in the field and reported to "do nothing": the debug view showed cache hits,
 * yet dark scenes were just as noisy as with the cache off. The tracer was querying the cache from
 * bounce 2 onwards, so the first indirect bounce — which carries the bulk of a dark scene's temporal
 * variance (one emitter sample, one sky sample, Russian-roulette survivors) — was always fully
 * traced and never replaced. NVIDIA's contract is to query every eligible hit <i>except the primary
 * hit</i>, i.e. from bounce 1; the shipped default of 2 left ~99% of that variance unreachable and
 * made the feature look inert.
 *
 * <p>The gates below pin the parts of that contract that are easy to "tidy up" into a no-op again:
 * where the query sits in the bounce loop, that it replaces (not adds to) the vertex's shading, that
 * the write still warms every vertex, and that the config default stays at NVIDIA's bounce 1.
 */
final class RtSharcShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");
    private static final Path SHARC = REPO_ROOT.resolve("shaders/world/sharc.slang");
    private static final Path CONFIG = REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java");
    private static final Path COMPOSITE = REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");

    private static final String QUERY_START = "// ---- Experimental SHaRC: cache query";
    private static final String EMISSION_START = "// Emissive surfaces (lava, glowstone, torches";
    private static final String UPDATE_START = "// ---- Experimental SHaRC: cache update";
    private static final String TERMINAL_START = "// Preserve all local lighting";

    @Test
    void startBounceDefaultIsNVIDIAsFirstIndirectHit() {
        // 1, not 2: the cache must be consulted from the first indirect bounce. Bounce 0 (the
        // primary hit) is excluded by the slider floor — replacing it would quantise the image's
        // own direct light to voxel resolution.
        String config = read(CONFIG);
        assertTrue(config.contains(
                        "clampedInt(\"caustica.rt.sharc.startBounce\", \"sharc.start-bounce\", 1, 1, 6)"),
                "Rt.Sharc.START_BOUNCE must default to 1 (NVIDIA queries every eligible hit except "
                        + "the primary hit). A default of 2 skips the first indirect bounce, which "
                        + "holds most of a dark scene's flicker, and SHaRC stops doing anything visible.");
    }

    @Test
    void queryGateReadsTheConfiguredBounceInsteadOfAHardCodedOne() {
        String rgen = read(RGEN);
        String query = slice(rgen, QUERY_START, "\n        " + EMISSION_START);

        assertTrue(query.contains("&& bounce >= int(worldPush.sharcParams2.x)"),
                "the query gate must take the start bounce from WorldPush.sharcParams2.x, so the "
                        + "Start Bounce slider actually reaches the tracer");
        assertFalse(query.contains("bounce >= 2"),
                "the query gate must not hard-code a bounce: a literal 2 in the gate is how the "
                        + "config default of 2 used to survive being changed in the settings file");
        assertTrue(rgen.contains("&& channel != 2"),
                "specular (channel 2) paths must keep tracing so reflections stay sharp");
        assertTrue(query.contains("&& !exactSpecular"),
                "exact mirrors must never be replaced by a voxel average");
        assertTrue(query.contains("&& payload.hitT > worldPush.sharcParams.x * SHARC_CELL_DIAGONAL"),
                "the arriving segment must be longer than one cell diagonal: a path that stops in "
                        + "the cell its own previous vertex just wrote would feed its radiance back "
                        + "into the image and gain energy every frame");
    }

    @Test
    void queryReplacesTheVertexAndTerminatesInsteadOfAddingOnTop() {
        String rgen = read(RGEN);
        String query = slice(rgen, QUERY_START, "\n        " + EMISSION_START);

        // The cached value is the cell's OUTGOING radiance: it already contains this vertex's own
        // direct light, so the query has to run before any local term lands and then stop the path.
        assertTrue(query.contains("float3 sharcGi = throughput * sharcCached * worldPush.sharcParams.y;"),
                "the query replacement must be throughput * cached * strength with no albedo, no "
                        + "1/pi and no confidence factor: re-applying a BRDF here is what made the "
                        + "previous integration invisible");
        assertTrue(query.contains("L += sharcGi;") && query.contains("Ldiff += sharcGi;"),
                "a cache hit must contribute to both the full and the diffuse radiance accumulators");
        assertTrue(query.contains("sharcPropagateToPath(worldPush, sharcState, hitPos, sharcCached, throughput);"),
                "a cache hit must feed the cached tail back into the upstream voxels (NVIDIA's cache "
                        + "resampling) so upstream cells learn multi-bounce light");
        assertTrue(query.contains("break;"),
                "a cache hit must terminate the path, otherwise the vertex's shading is added on top "
                        + "of a value that already contains it and the tail is traced anyway");

        // Ordering inside the bounce loop: query first, local light second, write after both.
        assertTrue(rgen.indexOf(QUERY_START) < rgen.indexOf(EMISSION_START),
                "the query must run before the vertex's emission/NEE terms land");
        assertTrue(rgen.indexOf(UPDATE_START) > rgen.indexOf(EMISSION_START),
                "the cache update must run after the local light it demodulates has been added");
    }

    @Test
    void everyDiffuseVertexWarmsTheCacheNotJustTheEarlyBounces() {
        String rgen = read(RGEN);
        String update = stripComments(slice(rgen, UPDATE_START, "\n        " + TERMINAL_START));

        assertTrue(update.contains("if (sharcUpdatePath && !exactSpecular) {"),
                "the write is gated on update-path selection and specularity only");
        assertFalse(update.contains("bounce <") || update.contains("bounce >="),
                "the write must not be restricted to a bounce range: cells that queries actually look "
                        + "up are the deep, non-camera-visible ones, and a write-side bounce gate "
                        + "left them permanently cold");
        assertTrue(update.contains("float3 localOut = localAdded / throughput;"),
                "the written value must be demodulated by the path throughput so the entry holds the "
                        + "vertex's own outgoing radiance");
    }

    @Test
    void hostPublishesTheStartBounceIntoTheLaneTheShaderReads() {
        String composite = read(COMPOSITE);

        assertTrue(composite.contains("CausticaConfig.Rt.Sharc.START_BOUNCE.value(),"),
                "WorldPush.sharcParams2.x must be published from Rt.Sharc.START_BOUNCE, the lane the "
                        + "query gate reads");
        assertTrue(composite.contains("private static Float4 sharcParams2()"),
                "the start bounce travels in sharcParams2 alongside coverage/lifetime/normal threshold");
    }

    @Test
    void queryAndUpdateHelpersStayInTheCacheModule() {
        String sharc = read(SHARC);

        assertTrue(sharc.contains("public bool sharcQuery("),
                "the query helper must stay a first-class part of sharc.slang so the gates above "
                        + "cannot be bypassed by an inlined copy");
        assertTrue(sharc.contains("public bool sharcWrite("),
                "the demodulating write must stay shared between all callers");
        assertTrue(sharc.contains("public void sharcPropagateToPath("),
                "back-propagation must stay shared so the throughput-ratio weighting is applied once");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    private static String slice(String text, String from, String to) {
        int start = text.indexOf(from);
        assertTrue(start >= 0, "missing section: " + from);
        int end = text.indexOf(to, start);
        assertTrue(end >= 0, "missing section end: " + to);
        return text.substring(start, end);
    }

    /** Drops // line comments so a gate check only sees code, not the prose explaining it. */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int comment = line.indexOf("//");
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return out.toString();
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
