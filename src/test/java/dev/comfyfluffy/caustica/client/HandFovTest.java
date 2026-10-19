package dev.comfyfluffy.caustica.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The hand must stay locked at vanilla's 70 degrees while the FOV slider moves, and only follow the
 * slider when the Hand FOV option is on.
 */
final class HandFovTest {
    private static final float DELTA = 1e-4f;

    @Test
    void handIsLockedToVanillaFovWhenOptionIsOff() {
        for (int slider = 30; slider <= 110; slider += 10) {
            assertEquals(70.0f, HandFov.effective(70.0f, slider, false), DELTA,
                    "slider " + slider + " must not move the hand while Hand FOV is off");
        }
    }

    @Test
    void deathOrFluidModulationStillPassesThroughWhenOptionIsOff() {
        assertEquals(58.0f, HandFov.effective(58.0f, 110, false), DELTA);
    }

    @Test
    void handFollowsSliderWhenOptionIsOn() {
        assertEquals(70.0f, HandFov.effective(70.0f, 70, true), DELTA);
        assertEquals(110.0f, HandFov.effective(70.0f, 110, true), DELTA);
        assertEquals(30.0f, HandFov.effective(70.0f, 30, true), DELTA);
    }

    @Test
    void deathModulationScalesWithSliderWhenOptionIsOn() {
        assertEquals(58.0f * 110.0f / 70.0f, HandFov.effective(58.0f, 110, true), DELTA);
    }

    @Test
    void missingOrNonPositiveSliderFallsBackToVanilla() {
        assertEquals(70.0f, HandFov.effective(70.0f, null, true), DELTA);
        assertEquals(70.0f, HandFov.effective(70.0f, 0, true), DELTA);
        assertEquals(70.0f, HandFov.effective(70.0f, -5, true), DELTA);
    }

    @Test
    void resultIsClampedToSafeProjectionRange() {
        assertEquals(HandFov.MAX_FOV, HandFov.effective(70.0f, 10_000, true), DELTA);
        assertEquals(HandFov.MIN_FOV, HandFov.effective(0.001f, 1, true), DELTA);
    }
}
