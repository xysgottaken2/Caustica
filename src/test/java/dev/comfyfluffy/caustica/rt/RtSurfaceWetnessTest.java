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
        // Which SURFACES respond is per prim (see rainSurfaceResponseIsDecidedPerColumnNotPerCamera);
        // this lane is only the storm-wide switch that drives the wet/dry clock.
        assertTrue(composite.contains("boolean raining = precipitating(level, partialTick);"));
        assertTrue(composite.contains("surfaceWetnessForFrame(raining)"));
        assertTrue(composite.contains("SURFACE_WETNESS.update(raining, now,"));
        assertTrue(hit.contains("smoothstep(0.45, 0.85, n.y)"));
        assertTrue(hit.contains("float roughnessScale = lerp(0.30, 0.035, gloss)"));
        assertTrue(hit.contains("surface.normal, n, wetness * lerp(0.25, 0.90, gloss)"));
        assertTrue(hit.contains("lerp(1.0, 0.76, wetness)"));
        assertTrue(common.contains("TERRAIN_PRIM_RAIN_EXPOSED = 8u"));
        assertTrue(common.contains("TERRAIN_PRIM_CUTOUT = 16u"));
        assertTrue(common.contains("TERRAIN_PRIM_TRANSLUCENT = 32u"));
        // The biome answer is its own bit so it can be hard, while the sky-exposure bit keeps fading.
        assertTrue(common.contains("TERRAIN_PRIM_RAIN_BIOME = 64u"));
        assertTrue(mesher.contains("TERRAIN_PRIM_RAIN_EXPOSED = 8"));
        assertTrue(mesher.contains("TERRAIN_PRIM_CUTOUT = 16"));
        assertTrue(mesher.contains("TERRAIN_PRIM_TRANSLUCENT = 32"));
        assertTrue(mesher.contains("TERRAIN_PRIM_RAIN_BIOME = 64"));
        assertTrue(mesher.contains("snapshot.isRainExposed(m)"));
        assertTrue(mesher.contains("snapshot.isLiquidRainColumn(m)"));
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
        assertTrue(hit.contains("bool rainBiome = (terrainFlags & TERRAIN_PRIM_RAIN_BIOME) != 0u;"),
                "the hit shader must read the biome bit");
        assertTrue(hit.contains("if (!rainBiome) {"),
                "a non-rainy column must be zeroed after the smooth shelter mask, never inside it");
    }

    /**
     * Rain's surface response is decided per column, never per camera.
     *
     * <p>Two regressions are worth remembering here, because they look right until you walk around:
     *
     * <ol>
     *   <li>Keying the wet film and the rings off {@code getRainLevel} alone. That value is one global
     *       storm number, so a snow biome got glossy wet ground and rings on its water.</li>
     *   <li>Gating the same lanes on the <em>camera's</em> biome. That fixed the biome the player stood
     *       in and broke every other one: standing in a taiga switched off the rain in the forest next
     *       door, and stepping into the forest lit up the taiga. The effect is global geometry, so a
     *       per-frame camera decision can only ever be wrong for part of the view.</li>
     * </ol>
     *
     * <p>The fix is to bake the answer into the geometry: the snapshot samples each column's biome next
     * to its heightmap, and every meshed prim carries whether its own column is rained on and open to
     * the sky. A snow biome and the rainy biome beside it are meshed in the same frame and each keeps
     * its own answer, which is exactly what the camera cannot know.
     */
    @Test
    void rainSurfaceResponseIsDecidedPerColumnNotPerCamera() throws IOException {
        Path root = repoRoot();
        String composite = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));
        String snapshots = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/terrain/RtSectionSnapshots.java"));
        String precipitation = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtPrecipitation.java"));

        // --- the rule itself lives in one place: vanilla's per-column biome answer ---
        assertTrue(precipitation.contains("getPrecipitationAt(pos, level.getSeaLevel())"),
                "the precipitation type must come from the biome, the same call vanilla's weather uses");
        assertTrue(precipitation.contains("== Biome.Precipitation.RAIN"),
                "only RAIN counts: SNOW and NONE (desert, badlands, savanna) must all be excluded");
        assertTrue(precipitation.contains("Math.clamp(surfaceY, level.getMinY(), level.getMaxY())"),
                "the query must be clamped into the level before it reaches getBiome");

        // --- it is baked per column into the terrain snapshot ---
        assertTrue(snapshots.contains("record RainColumnMap(int[] motionBlockingTopY, boolean[] liquidRain)"),
                "the per-chunk snapshot must carry the biome answer alongside the heightmap");
        assertTrue(snapshots.contains("liquid[index] = RtPrecipitation.isLiquidRain(level, blockX, blockZ, top);"),
                "each apron column must be sampled at its own surface height");
        assertTrue(snapshots.contains("boolean isLiquidRainColumn(BlockPos pos)"),
                "the snapshot must expose the per-column biome answer on its own, next to the sky answer");
        assertTrue(snapshots.contains("liquidRainColumn[(z + RAIN_TRANSITION_RADIUS) * RAIN_MAP_SIZE"),
                "the biome answer must be read at this prim's own apron column");
        assertTrue(mesher.contains("capture.rainBiome = snapshot.isLiquidRainColumn(m);"),
                "every solid prim must carry its column's biome answer into the mesh");
        assertTrue(mesher.contains("flags |= TERRAIN_PRIM_RAIN_BIOME;"),
                "that answer must reach the shader as a hard prim bit");
        assertTrue(snapshots.contains("distance[i] = surfaceY >= motionBlockingTopY[i] - 1"),
                "the shelter field must keep seeding on sky exposure only, so its edge still fades");
        assertFalse(snapshots.contains("&& liquidRainColumn[i]"),
                "the biome answer must not enter the distance field: a fading field would blend it "
                        + "across the border and put rings several blocks inside the snow");

        // --- the frame lanes must stay feature switches, not camera-biome gates ---
        assertTrue(composite.contains("private static boolean precipitating(ClientLevel level, float partialTick)"),
                "the frame-level predicate must be the storm, shared by every prim in the frame");
        assertTrue(composite.contains("return level != null && level.getRainLevel(partialTick) > 0.001f;"),
                "the storm predicate must stay the global rain level");
        assertFalse(composite.contains("isRainingAt"),
                "asking the camera's column per frame is the bias this test exists to prevent");
        assertFalse(composite.contains("liquidRain &&"),
                "no lane may be gated on a per-frame camera-biome answer");
        for (String lane : new String[] {
                "surfaceWetnessForFrame(raining)",
                "CausticaConfig.Rt.Composite.GROUND_RAIN_RIPPLES.value()",
                "CausticaConfig.Rt.Composite.WATER_RAIN_RIPPLES.value()"}) {
            assertTrue(composite.contains(lane), "missing lane wiring: " + lane);
        }

        // --- lightning picks a column too, instead of trusting the camera's biome ---
        String lightning = Files.readString(root.resolve(
                "src/main/java/dev/comfyfluffy/caustica/rt/RtLightning.java"));
        assertTrue(lightning.contains("RtPrecipitation.isLiquidRain(level, blockX, blockZ, surfaceY)"),
                "the scheduler must refuse strike columns where nothing is falling");
        assertTrue(lightning.contains("for (int attempt = 0; attempt < STRIKE_ATTEMPTS; attempt++)"),
                "a rainless draw must be retried, not silently skipped");
        assertTrue(composite.contains("LIGHTNING.update(level, camX, camY, camZ, weather.rain(),"),
                "the scheduler keeps running on the storm, so storms in a neighbouring biome still strike");
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
