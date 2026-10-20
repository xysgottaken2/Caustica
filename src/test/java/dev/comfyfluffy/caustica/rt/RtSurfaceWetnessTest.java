package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSurfaceWetnessTest {
    @Test
    void reachesFullWetnessInAboutFiveSecondsOfRain() {
        RtSurfaceWetness wetness = new RtSurfaceWetness();
        long start = 10_000_000_000L;
        assertEquals(0.0f, wetness.update(true, start), 1.0e-6f);
        for (int second = 1; second <= 5; second++) {
            wetness.update(true, start + second * 1_000_000_000L);
        }
        assertEquals(1.0f, wetness.value(), 1.0e-6f);
    }

    @Test
    void driesFullyInAboutThirtySecondsAfterRainStops() {
        RtSurfaceWetness wetness = new RtSurfaceWetness();
        long start = 20_000_000_000L;
        wetness.update(true, start);
        for (int second = 1; second <= 5; second++) {
            wetness.update(true, start + second * 1_000_000_000L);
        }
        for (int second = 1; second <= 30; second++) {
            wetness.update(false, start + (5L + second) * 1_000_000_000L);
        }
        assertEquals(0.0f, wetness.value(), 1.0e-6f);
    }

    @Test
    void wetnessChangesContinuouslyAndRemainsBounded() {
        RtSurfaceWetness wetness = new RtSurfaceWetness();
        long start = 30_000_000_000L;
        wetness.update(true, start);
        float previous = wetness.value();
        for (int second = 1; second <= 10; second++) {
            float current = wetness.update(true, start + second * 1_000_000_000L);
            assertTrue(current >= previous, "wetness should never decrease while raining");
            assertTrue(current >= 0.0f && current <= 1.0f);
            previous = current;
        }
        float drying = wetness.update(false, start + 11_000_000_000L);
        assertTrue(drying < previous, "wetness should start falling after rain stops");
        assertTrue(drying >= 0.0f && drying <= 1.0f);
    }

    @Test
    void rainWetnessHasLiveShelterTransitionAndSupportsCutoutFoliage() throws IOException {
        Path root = repoRoot();
        String common = Files.readString(root.resolve("shaders/world/world_common.slang"));
        String hit = Files.readString(root.resolve("shaders/world/world.rchit.slang"));
        String composite = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String mesher = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtTerrainMesher.java"));
        String snapshots = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtSectionSnapshots.java"));
        assertTrue(common.contains("public float4   surfaceWetness;"));
        assertTrue(composite.contains("surfaceWetnessForFrame(level != null && level.getRainLevel(partialTick) > 0.001f"));
        assertTrue(composite.contains("SURFACE_WETNESS.update(raining, now,"));
        assertTrue(hit.contains("smoothstep(0.45, 0.85, n.y)"));
        assertTrue(hit.contains("float roughnessScale = lerp(0.30, 0.035, gloss)"));
        assertTrue(hit.contains("surface.roughness * roughnessScale"));
        assertTrue(hit.contains("surface.normal, n, wetness * lerp(0.25, 0.90, gloss)"));
        assertTrue(hit.contains("float3(0.055, 0.055, 0.055)"));
        assertTrue(hit.contains("lerp(1.0, 0.76, wetness)"));
        assertTrue(common.contains("TERRAIN_PRIM_RAIN_EXPOSED = 8u"));
        assertTrue(common.contains("TERRAIN_PRIM_CUTOUT = 16u"));
        assertTrue(common.contains("TERRAIN_PRIM_TRANSLUCENT = 32u"));
        assertTrue(common.contains("w = live wetness-transition radius in blocks"));
        assertTrue(mesher.contains("TERRAIN_PRIM_RAIN_EXPOSED = 8"));
        assertTrue(mesher.contains("TERRAIN_PRIM_CUTOUT = 16"));
        assertTrue(mesher.contains("TERRAIN_PRIM_TRANSLUCENT = 32"));
        assertTrue(mesher.contains("snapshot.isRainExposed(m)"));
        assertTrue(mesher.contains("snapshot.rainShelterDistance(m)"));
        assertTrue(snapshots.contains("Heightmap.Types.MOTION_BLOCKING"));
        assertTrue(snapshots.contains("boolean isRainExposed(BlockPos pos)"));
        assertTrue(snapshots.contains("private void bakeRainShelterDistances()"));
        assertTrue(hit.contains("bool rainExposed = (terrainFlags & TERRAIN_PRIM_RAIN_EXPOSED) != 0u"));
        assertTrue(hit.contains("bool rainWettable = (!nonSolid || cutoutSurface) && !translucentSurface"),
                "solid and cutout surfaces are wettable, while translucent materials stay excluded");
        assertTrue(hit.contains("1.0 - smoothstep(0.0, transitionDistance, shelterDistance)"),
                "shelter distance must feed the continuous wetness transition");
        assertTrue(hit.contains("float transitionDistance = clamp(framePush.surfaceWetness.w, 0.0, 12.0);"),
                "the shader transition range must match the wider user setting");
        assertTrue(hit.contains("if (!nonSolid && rainAmount > 0.01 && framePush.surfaceWetness.z > 0.001)"),
                "ground ripples must be evaluated under shelter too, so their strength can fade with wetness");
        assertTrue(!hit.contains("if (!nonSolid && rainExposed && wetness > 0.05"),
                "direct-exposure and wetness-threshold gates must not reintroduce a hard ripple cutoff");
        assertTrue(composite.contains("CausticaConfig.Rt.Composite.WETNESS_TRANSITION_DISTANCE.value()"));
        String config = Files.readString(root.resolve("src/main/java/dev/comfyfluffy/caustica/CausticaConfig.java"));
        String videoOptions = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java"));
        assertTrue(config.contains("WETNESS_TRANSITION_DISTANCE"));
        assertTrue(config.contains("6.0f, 0.0f, 12.0f"), "the factory default should match the stronger bundled preset while preserving the wider transition range");
        assertTrue(videoOptions.contains("wetnessTransitionDistance()"));
        assertTrue(videoOptions.contains("WETNESS_TRANSITION_DISTANCE, 0, 12"));
        assertTrue(snapshots.contains("private static final int RAIN_TRANSITION_RADIUS = 12;"),
                "the baked shelter-distance map must cover the full slider range");
        int waterBranch = hit.indexOf("if (materialHeader.model == MATERIAL_WATER)");
        int wetnessMask = hit.indexOf("float wetFaceUp");
        assertTrue(waterBranch >= 0 && wetnessMask > waterBranch,
                "the water dielectric path must return before terrain wetness is evaluated");
    }

    @Test
    void weatherColumnBudgetIsReservedAndDistributedAcrossTheGrid() throws IOException {
        Path root = repoRoot();
        String entities = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtEntities.java"));
        String weather = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtWeatherCapture.java"));
        assertTrue(entities.contains("reservedWeatherSlots = RtWeatherCapture.willRenderWeather()"),
                "the frame's logical geometry cap should reserve slots for active weather");
        assertTrue(entities.contains("boolean hardFull()"));
        assertTrue(entities.contains("Capture vanilla weather BEFORE ordinary particles"),
                "weather columns must be captured ahead of billboards and not disappear in particle-heavy scenes");
        assertTrue(entities.contains("particlesCaptured >= ordinaryParticleLimit"));
        assertTrue(weather.contains("public static final int MAX_CAPTURE_COLUMNS = 512"),
                "vanilla's full weather grid should have a dedicated capture cap");
        assertTrue(weather.contains("int sampleCount = Math.min(columns.size(), budget)"),
                "tight budgets must distribute every available weather slot across the full grid");
        assertTrue(weather.contains("(long) sampleIndex * columns.size() + columns.size() / 2L"),
                "weather sampling must use even index mapping rather than a coarse ceil stride");
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
