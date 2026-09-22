package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guards for the volumetric fog wiring — the invariants whose silent breakage is the
 * bug family fog used to have (leaks, double darkening, display-seam-only scattering).
 *
 * <p>Style deliberately matches {@link RtCloudShaderRegressionTest}: the contracts live in shader
 * source that cannot be unit-tested numerically here, so the guards assert the structural shape.
 */
final class RtFogShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path FOG = REPO_ROOT.resolve("shaders/world/fog.slang");
    private static final Path WORLD_RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");
    private static final Path PRIMARY_RGEN = REPO_ROOT.resolve("shaders/world/world_primary.rgen.slang");
    private static final Path WORLD_COMMON = REPO_ROOT.resolve("shaders/world/world_common.slang");
    private static final Path RT_COMPOSITE =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");

    /**
     * Fog must be a term of the path integral, not of the display seam, and it must be ordered
     * BEFORE the cloud composite of the same segment: ground-level fog in front of the deck dims
     * the deck's own in-scatter, and a swapped order would silently stop fogging distant clouds.
     */
    @Test
    void segmentFogAccumulatesBeforeCloudsAndAttenuatesThePath() throws IOException {
        String source = Files.readString(WORLD_RGEN);
        assertInOrder(source,
                "float3 fogSegThroughput = throughput;",
                "FogVolume segFog = fogSegment(worldPush, ro, rd, payload.hitT, seed, showCelestial);",
                "throughput *= segFog.transmittance;",
                "CloudVolume segCloud = cloudSegment(");
    }

    /**
     * The Pass A prefix recovery owns the camera->surface fog for split dielectric continuations
     * (exactly like the clouds' "ocean drawn at full brightness above the deck" bug it mirrors),
     * and the primary pass itself must not also add fog there — that would double-count every
     * dielectric's prefix.
     */
    @Test
    void fogPrefixIsRecoveredOnceAndNotInPassA() throws IOException {
        String source = Files.readString(WORLD_RGEN);
        String recovery = slice(source, "if (seg.bounce > 0) {", "float rayConeWidth");
        assertInOrder(recovery,
                "FogVolume preFog = fogSegment(",
                "throughput *= preFog.transmittance;",
                "CloudVolume pre = cloudSegment(");
        assertFalse(recovery.contains("fogSegThroughput"),
                "the prefix recovery composites fog directly; the reservoir term below must not "
                        + "consume a prefix-weighted throughput too");
        String primary = Files.readString(PRIMARY_RGEN);
        assertFalse(primary.contains("fogSegment("),
                "world_primary.rgen must not integrate fog — the tracePath recovery block is its owner");
    }

    /**
     * God-ray occlusion must come from a real shadow ray through the BVH, gated to the full tier.
     * A cheap-tier (diffuse continuation) sun ray would re-light cave air the primary correctly
     * found dark — a leak in disguise — and an unoccluded "optimisation" removes the whole point.
     */
    @Test
    void sunInScatterIsOccludedByAShadowRayOnTheFullTierOnly() throws IOException {
        String source = Files.readString(FOG);
        String body = slice(source, "public FogVolume fogSegment(",
                "public float3 fogEmitterScatter(");
        assertInOrder(body,
                "if (highQuality) {",
                "VisibilityResult shadow = visibility(",
                "cloudSunShadow(push, sp - push.camOffset, lightDir);");
    }

    /**
     * There is no leak-prevention structure and that is load-bearing: fog occlusion is the shadow
     * ray, nothing else. If someone reintroduces a voxel/froxel density grid in here, this test
     * is where the argument has to be re-made in the header comment, not quietly in a PR.
     */
    @Test
    void fogKeepsNoWorldSpaceState() throws IOException {
        String source = Files.readString(FOG);
        for (String banned : new String[] {"StructuredBuffer", "texture3D", "RWTexture3D", "DeviceAddress"}) {
            assertFalse(source.contains(banned),
                    "fog.slang must stay stateless: world-space fog storage is the leak/ghosting "
                            + "family this design exists to avoid (" + banned + ")");
        }
    }

    /**
     * Emitter fog rides the reservoir winner (one shadow ray, no light selection) and must be
     * weighted by the segment's PRE-fog throughput — multiplying it by the already-darkened
     * throughput double-counts this segment's own transmittance.
     */
    @Test
    void emitterFogSharesTheReservoirAndPreFogThroughput() throws IOException {
        String source = Files.readString(WORLD_RGEN);
        String risBlock = slice(source, "Fog in-scatter from the very light", "if (nrdEnabled()) {");
        assertTrue(risBlock.contains("fogEmitterScatter(worldPush, ro, rd, payload.hitT,"),
                "emitter fog must integrate the segment that reached this shading point");
        assertTrue(risBlock.contains("r.pos, r.lnrm, r.le, r.area)"),
                "emitter fog must sample the reservoir's winner, not re-select a light");
        assertTrue(risBlock.contains("fogE *= fogSegThroughput;"),
                "emitter fog must weight by the segment-entry throughput, not the post-fog one");
        assertFalse(risBlock.contains("fogE *= throughput") && risBlock.contains("throughput * fogE"),
                "post-attenuation throughput here means the segment darkens its own in-scatter twice");
    }

    /** The one-lane ABI contract: the push field exists and Java writes it through the resolver. */
    @Test
    void fogParamsLaneExistsOnBothSidesOfTheAbi() throws IOException {
        assertTrue(Files.readString(WORLD_COMMON).contains("public float4   fogParams;"),
                "WorldPush.fogParams missing — the generated WorldPushData would not compile anyway, "
                        + "but this points at the right file on failure");
        String java = Files.readString(RT_COMPOSITE);
        assertTrue(java.contains("private static Float4 fogParams()"),
                "RtComposite.fogParams() resolver missing");
        assertInOrder(java,
                "CausticaConfig.Rt.Composite.FOG_ENABLED.value()",
                "CausticaConfig.Rt.Composite.FOG_DENSITY.value()");
    }

    /**
     * The tint lane's ABI: {@code fogTint} trails {@code fogParams} in WorldPush and Java appends
     * its value in the same order. The push codegen is reflection-based, so a one-sided reorder
     * would not fail to compile — it would silently tint the fog with the anisotropy float. Both
     * orders are pinned; the colour itself must come from the game's own attribute, not a
     * hand-ramped palette.
     */
    @Test
    void fogTintLaneTrailsFogParamsOnBothSides() throws IOException {
        assertInOrder(Files.readString(WORLD_COMMON),
                "public float4   fogParams;",
                "public float4   fogTint;");
        String composite = Files.readString(RT_COMPOSITE);
        assertInOrder(composite,
                "fogParams(),",
                "fogTint()");
        assertTrue(composite.contains("EnvironmentAttributes.FOG_COLOR"),
                "the tint must read vanilla's fog colour attribute — biome blend, weather and "
                        + "dimension are already composed there, and re-resolving them here is how "
                        + "world-space fog state (and its leak family) starts");
    }

    /**
     * Weather and tint are single-source lanes, not new fog state: rain rides worldPush.weather
     * behind the same feature flag the sky gates it with, thickens the one density coefficient at
     * BOTH scatter sites (so darkening and glow cannot disagree), and dims only the celestial
     * sunVis — the shadow ray stays the occlusion authority for everything geometry can see.
     */
    @Test
    void weatherAndBiomeTintRideTheSharedLanes() throws IOException {
        String source = Files.readString(FOG);
        String body = slice(source, "public FogVolume fogSegment(", "public float3 fogEmitterScatter(");
        assertInOrder(body,
                "sigmaS *= 1.0 + FOG_RAIN_DENSITY_GAIN * rain;",
                "sunVis *= 1.0 - FOG_RAIN_SUN_DIM * rain;");
        assertTrue(source.contains("worldFeature(push, FEATURE_WEATHER_LIGHTING) ? saturate(push.weather.x) : 0.0"),
                "fog rain must ride the sky's weather lane behind the sky's feature flag");
        assertTrue(body.contains("lightTerm = fogTintNow(push) * phase * sun * sunVis;"),
                "celestial scatter must tint through the shared lerp");
        String emitter = source.substring(source.indexOf("public float3 fogEmitterScatter("));
        assertTrue(emitter.contains("sigmaS *= 1.0 + FOG_RAIN_DENSITY_GAIN * fogRain(push);"),
                "emitter cones must travel the same rain-thickened medium as the sky shafts");
        assertTrue(emitter.contains("float3 scatter = fogTintNow(push) * (sigmaS * profile)"),
                "emitter scatter must tint through the same shared lerp as the celestial term");
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

    /** Same root discovery pattern as RtCloudShaderRegressionTest, kept local to avoid test coupling. */
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
