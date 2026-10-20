package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        // Liquid rain only: snow shares the storm but must not soak the ground (see
        // RtWeatherCapture.isRainingAt, and RtWeatherCaptureTest for the full gate).
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
