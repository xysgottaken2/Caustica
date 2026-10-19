package dev.comfyfluffy.caustica.client;

/**
 * Single source of truth for the first-person hand (viewmodel) field of view.
 *
 * <p>Vanilla draws the held item and arm through a "3d hud" projection built from a constant
 * {@value #VANILLA_HAND_FOV}-degree FOV (modulated only by the death/fluid factor that arrives as
 * {@code hudFov}). That keeps the hand a fixed size on screen no matter what the FOV slider says.
 *
 * <p>With the Hand FOV option enabled the hand instead follows the FOV slider, scaled against
 * {@value #VANILLA_HAND_FOV}. Keep this math free of Minecraft types so it stays unit-testable.
 */
public final class HandFov {
    /** Vanilla's fixed viewmodel FOV, per {@code Camera.calculateHudFov()}. */
    public static final float VANILLA_HAND_FOV = 70.0f;

    /** Clamp range so a hostile config or slider value can never produce a degenerate projection. */
    static final float MIN_FOV = 1.0f;
    static final float MAX_FOV = 179.0f;

    private HandFov() {
    }

    /**
     * @param hudFov         vanilla's hand FOV for this frame (70, modulated by death/fluid effects)
     * @param sliderFov      the player's FOV slider value, or {@code null} when unavailable
     * @param followsCamera  whether the Hand FOV option is enabled
     * @return the FOV to build the hand projection with. Equals {@code hudFov} whenever the option is off
     *         or the slider is unavailable, so the projection stays bit-identical to vanilla.
     */
    public static float effective(float hudFov, Integer sliderFov, boolean followsCamera) {
        if (!followsCamera) {
            return hudFov;
        }
        if (sliderFov == null || sliderFov <= 0) {
            return hudFov;
        }
        return Math.clamp(hudFov * (sliderFov / VANILLA_HAND_FOV), MIN_FOV, MAX_FOV);
    }
}
