package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the hand-maintained half of the shader ABI.
 *
 * <p>Most of {@code WorldPush} is safe by construction: {@code generateShaderRecords} reflects the Slang
 * struct and generates {@code WorldPushData} plus its serializer, so a field added on one side simply
 * fails to compile on the other. The {@code FEATURE_*} bit values and the {@code DIMENSION_*} ids get no
 * such protection — they are plain integers written into a {@code uint} lane, so a mismatch would
 * compile cleanly on both sides and silently mean something different at runtime (SSS toggling the
 * denoiser flag, the Nether rendering as the End).
 *
 * <p>Rather than duplicate the numbers a third time in an assertion, this reads both sources and checks
 * they agree, so the test cannot drift out of date with either file.
 */
final class RtShaderConstantMirrorTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path SLANG = REPO_ROOT.resolve("shaders/world/world_common.slang");
    private static final Path WORLD_CORE = REPO_ROOT.resolve("shaders/world/world_core.slang");
    private static final Path WORLD_RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");
    private static final Path JAVA =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");

    @Test
    void featureFlagBitsMatchBetweenJavaAndSlang() throws IOException {
        Map<String, Long> slang = slangConstants("FEATURE_");
        Map<String, Long> java = javaConstants("FEATURE_");

        assertFalse(slang.isEmpty(), "no FEATURE_* constants found in " + SLANG);
        assertEquals(slang, java, "FEATURE_* bits differ between world_common.slang and RtComposite");
        // Each flag must own exactly one distinct bit, or two options would toggle each other.
        long seen = 0L;
        for (Map.Entry<String, Long> entry : slang.entrySet()) {
            long bit = entry.getValue();
            assertEquals(1, Long.bitCount(bit), entry.getKey() + " must be a single bit");
            assertEquals(0L, seen & bit, entry.getKey() + " reuses a bit already taken");
            seen |= bit;
        }
    }

    @Test
    void dimensionIdsMatchBetweenJavaAndSlang() throws IOException {
        Map<String, Long> slang = slangConstants("DIMENSION_");
        Map<String, Long> java = javaConstants("DIMENSION_");

        assertFalse(slang.isEmpty(), "no DIMENSION_* constants found in " + SLANG);
        assertEquals(slang, java, "DIMENSION_* ids differ between world_common.slang and RtComposite");
        // The Overworld must stay 0: it is the value every fallback path (no level, unknown dimension)
        // produces, and world.rmiss branches to its own skybox on "not the Overworld".
        assertEquals(0L, slang.get("DIMENSION_OVERWORLD"));
        assertEquals(slang.size(), slang.values().stream().distinct().count(), "duplicate dimension id");
    }

    @Test
    void terrainPrimFlagBitsMatchBetweenJavaAndSlang() throws IOException {
        Map<String, Long> slang = slangConstants("TERRAIN_PRIM_");
        Path mesher = REPO_ROOT.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrainMesher.java");
        Map<String, Long> java = scan(Files.readString(mesher),
                Pattern.compile("static\\s+final\\s+int\\s+(TERRAIN_PRIM_\\w+)\\s*=\\s*(\\d+)\\s*;"));

        assertFalse(slang.isEmpty(), "no TERRAIN_PRIM_* constants found in " + SLANG);
        assertEquals(slang, java,
                "TERRAIN_PRIM_* bits differ between world_common.slang and RtTerrainMesher");
        long seen = 0L;
        for (Map.Entry<String, Long> entry : slang.entrySet()) {
            long bit = entry.getValue();
            assertEquals(0L, seen & bit, entry.getKey() + " reuses a bit already taken");
            seen |= bit;
        }
    }

    @Test
    void worldFlagBitsDoNotCollideWithEachOther() throws IOException {
        Map<String, Long> flags = slangConstants("WORLD_FLAG_");

        assertFalse(flags.isEmpty(), "no WORLD_FLAG_* constants found in " + SLANG);
        long seen = 0L;
        for (Map.Entry<String, Long> entry : flags.entrySet()) {
            long bit = entry.getValue();
            assertEquals(1, Long.bitCount(bit), entry.getKey() + " must be a single bit");
            assertEquals(0L, seen & bit, entry.getKey() + " reuses a bit already taken");
            seen |= bit;
        }
    }

    @Test
    void restirToggleControlsBoundHistoryAndTheFinalShadingEstimator() throws IOException {
        String common = Files.readString(SLANG);
        String core = Files.readString(WORLD_CORE);
        String raygen = Files.readString(WORLD_RGEN);
        String java = Files.readString(JAVA);

        assertTrue(common.contains("public uint restirMode;"),
                "the live ReSTIR/RIS selector must be an explicit push uniform");
        assertTrue(core.contains("pc.restirMode != 0u")
                        && core.contains("pc.restirPreviousAddr != 0")
                        && core.contains("pc.restirCurrentAddr != 0"),
                "restirEnabled must require both the live mode and bound ping-pong buffers");
        assertTrue(java.contains("terrain.lightGeneration(), restirMode()).write(pushConstants)"),
                "RtComposite must write restirMode into the world pipeline push constants every frame");
        assertTrue(raygen.contains("bool shadeWithRestir = restirReceiverPending && restirOwner;")
                        && raygen.contains("r = restirSpatiotemporal(r")
                        && raygen.contains("shadeReservoir(r"),
                "the selected ReSTIR reservoir must flow into the radiance added to final color");
        assertTrue(raygen.contains("skipSiblingRis") && raygen.contains("restirSampleScale"),
                "SPP averaging must not dilute ReSTIR with legacy RIS at the same receiver");
    }

    @Test
    void worldPushCarriesTheFieldsTheNewFeaturesPush() throws IOException {
        String slang = Files.readString(SLANG);
        // These three lanes are what SSS, weather lighting, the dimension skyboxes and the denoiser
        // toggle travel in. Removing one would still compile (the generated record just loses a
        // component) but would silently drop a feature, so pin them here.
        for (String field : new String[] {"weather", "dimension", "featureFlags"}) {
            assertTrue(Pattern.compile("^\\s*public\\s+\\S+\\s+" + field + "\\s*;", Pattern.MULTILINE)
                            .matcher(slang).find(),
                    "WorldPush is missing the '" + field + "' field");
        }
    }

    /** {@code public static const uint NAME = 1u;} in Slang. */
    private static Map<String, Long> slangConstants(String prefix) throws IOException {
        return scan(Files.readString(SLANG),
                Pattern.compile("public\\s+static\\s+const\\s+uint\\s+(" + Pattern.quote(prefix)
                        + "\\w+)\\s*=\\s*(\\d+)u?\\s*;"));
    }

    /** {@code private static final int NAME = 1;} in Java. */
    private static Map<String, Long> javaConstants(String prefix) throws IOException {
        return scan(Files.readString(JAVA),
                Pattern.compile("static\\s+final\\s+int\\s+(" + Pattern.quote(prefix)
                        + "\\w+)\\s*=\\s*(\\d+)\\s*;"));
    }

    private static Map<String, Long> scan(String source, Pattern pattern) {
        Map<String, Long> found = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            found.put(matcher.group(1), Long.parseLong(matcher.group(2)));
        }
        return found;
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
