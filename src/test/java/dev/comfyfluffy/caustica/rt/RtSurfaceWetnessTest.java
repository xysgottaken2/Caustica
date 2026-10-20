package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rain-wetness clock: the 5 s wet / 30 s dry default timing, its bounded-continuous behaviour,
 * and the host/shader plumbing that turns the clock plus the mesher's baked shelter distances into a
 * wet film. The plumbing assertions are cheap string checks against the real sources — they cannot prove
 * shading quality, but they do catch the failure that matters for a port: a lane that silently stops
 * being written or read.
 */
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
    void longStallsCannotJumpWetnessAcrossTheWholeRange() {
        RtSurfaceWetness wetness = new RtSurfaceWetness();
        long start = 40_000_000_000L;
        wetness.update(true, start);
        // One hour of wall clock in a single delta (alt-tab / debugger): the 1 s cap must hold, so a
        // single resume can advance wetness by at most one second's worth of the configured duration.
        float afterStall = wetness.update(true, start + 3_600_000_000_000L);
        assertTrue(afterStall <= 0.2f + 1.0e-4f,
                "a long frame delta must be clamped instead of soaking the world instantly");
    }

    @Test
    void rainWetnessIsWiredFromTheHeightmapThroughTheMesher() throws IOException {
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
        // Liquid rain only: snow shares the storm but must not soak the ground. The biome is the
        // authority for that question — see onlyBiomesWhereRainFallsShowRainSurfaceEffects below.
        assertTrue(composite.contains("boolean liquidRain = liquidRainAt(level, camX, camZ, partialTick);"));
        assertTrue(composite.contains("surfaceWetnessForFrame(liquidRain)"));
        assertTrue(composite.contains("SURFACE_WETNESS.update(raining, now,"));
        assertTrue(hit.contains("smoothstep(0.45, 0.85, n.y)"));
        assertTrue(hit.contains("float roughnessScale = lerp(0.30, 0.035, gloss)"));
        assertTrue(hit.contains("surface.normal, n, wetness * lerp(0.25, 0.90, gloss)"));
        assertTrue(hit.contains("lerp(1.0, 0.76, wetness)"));
        assertTrue(common.contains("TERRAIN_PRIM_RAIN_EXPOSED = 8u"));
        assertTrue(common.contains("TERRAIN_PRIM_CUTOUT = 16u"));
        assertTrue(common.contains("TERRAIN_PRIM_TRANSLUCENT = 32u"));
        assertTrue(mesher.contains("TERRAIN_PRIM_RAIN_EXPOSED = 8"));
        assertTrue(mesher.contains("TERRAIN_PRIM_CUTOUT = 16"));
        assertTrue(mesher.contains("TERRAIN_PRIM_TRANSLUCENT = 32"));
        assertTrue(mesher.contains("snapshot.isRainExposed(m)"));
        assertTrue(mesher.contains("snapshot.rainShelterDistance(m)"));
        assertTrue(snapshots.contains("Heightmap.Types.MOTION_BLOCKING"));
        assertTrue(snapshots.contains("boolean isRainExposed(BlockPos pos)"));
        assertTrue(snapshots.contains("private void bakeRainShelterDistances()"));
        assertTrue(snapshots.contains("private static final int RAIN_TRANSITION_RADIUS = 12;"),
                "the baked shelter-distance map must cover the full slider range");
        assertTrue(hit.contains("bool rainWettable = (!nonSolid || cutoutSurface) && !translucentSurface"),
                "solid and cutout surfaces are wettable, while translucent materials stay excluded");
        assertTrue(hit.contains("1.0 - smoothstep(0.0, transitionDistance, shelterDistance)"),
                "shelter distance must feed the continuous wetness transition");
        assertTrue(hit.contains("float transitionDistance = clamp(framePush.surfaceWetness.w, 0.0, 12.0);"));
    }

    /**
     * Only biomes where rain actually falls may show rain's surface response.
     *
     * <p>{@code getRainLevel} is one global storm number: it says how hard the storm is, not what is
     * coming out of it, so it reads the same in a snowy taiga, in a desert downpour-less afternoon and in
     * a jungle. Keying the wet film and the impact rings off it alone made every biome look rained on —
     * glossy dark ground and rings on the water under a sky of flakes.
     *
     * <p>An earlier attempt asked the weather renderer instead (does the camera's column appear in
     * vanilla's rain column list). That is a derived view of the same question, refreshed with the render
     * state, and it did not hold up when crossing biome borders: the rings came back inside the snow
     * biome. The biome itself is the authority — it is what vanilla consults to choose the rain sheet or
     * the snow sheet — so the gate asks it directly, once per frame, for the column under the camera.
     */
    @Test
    void onlyBiomesWhereRainFallsShowRainSurfaceEffects() throws IOException {
        Path root = repoRoot();
        String composite = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String weatherCapture = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/entity/RtWeatherCapture.java"));

        // --- the rule: vanilla's per-column precipitation answer, sampled at the surface ---
        int helper = composite.indexOf("private static boolean liquidPrecipitationAt(");
        assertTrue(helper > 0, "RtComposite must ask the biome for its precipitation type");
        int helperEnd = composite.indexOf("\n    }\n", helper);
        String body = composite.substring(helper, helperEnd);
        assertTrue(body.contains("level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel())"),
                "the precipitation type must come from the biome, the same call vanilla's weather uses");
        assertTrue(body.contains("== Biome.Precipitation.RAIN"),
                "only RAIN counts: SNOW and NONE (desert, badlands, savanna) must all be excluded");
        assertTrue(body.contains("Heightmap.Types.MOTION_BLOCKING")
                        && body.contains("Math.max(surfaceY, level.getMinY())"),
                "sample the column at its surface, not at the eye: rain vs. snow depends on height");

        // The storm gate stays: a clear sky must never produce a wet film even in a rainy biome.
        assertTrue(composite.contains("level.getRainLevel(partialTick) > 0.001f")
                        && composite.contains("&& liquidPrecipitationAt(level, cameraX, cameraZ)"),
                "liquidRainAt must require both a storm and rain (not snow) at the camera");

        // --- the renderer's column lists must not be the signal again ---
        assertFalse(composite.contains("RtWeatherCapture.isRainingAt"),
                "the answer must come from the biome, not from a derived render-state column list");
        assertFalse(weatherCapture.contains("isRainingAt("),
                "the render-state based rain query is gone; leaving it invites the same drift");

        // --- every rain-only lane still consumes the signal ---
        assertTrue(composite.contains("boolean liquidRain = liquidRainAt(level, camX, camZ, partialTick);"),
                "the composite must resolve liquid precipitation once per frame");
        assertTrue(composite.contains("surfaceWetnessForFrame(liquidRain)"),
                "the wet film must accumulate only under liquid rain");
        assertTrue(composite.contains("liquidRain && CausticaConfig.Rt.Composite.GROUND_RAIN_RIPPLES.value()"),
                "ground rain ripples must be gated on liquid rain");
        assertTrue(composite.contains("liquidRain && CausticaConfig.Rt.Composite.WATER_RAIN_RIPPLES.value()"),
                "water rain ripples must be gated on liquid rain");
        assertTrue(composite.contains("liquidRain ? weather.rain() : 0f"),
                "the strike scheduler must not run while snow is falling");

        // --- the storm response stays global: snow is still overcast ---
        assertTrue(composite.contains("float fill = rainCloudResponse ? Math.min(1f, weather.rain() + weather.thunder()) : 0f;"),
                "cloud thickening must keep following the storm, not the precipitation type");
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
