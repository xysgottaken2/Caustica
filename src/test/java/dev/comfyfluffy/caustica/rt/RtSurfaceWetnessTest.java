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

    // The two shader-wiring tests from PR #21 (rain-exposure prim flags in the terrain
    // mesher + wet-film shading in world.rchit, and the weather-column capture budget)
    // are intentionally not ported yet: those features were deferred, so their guards
    // will land with the follow-up that implements them.

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
