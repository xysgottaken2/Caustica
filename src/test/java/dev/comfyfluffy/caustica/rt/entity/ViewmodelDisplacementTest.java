package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the viewmodel's motion convention — the one place in the reprojection where a sign error is silent
 * at compile time and destructive at runtime.
 *
 * <p>The guide pass publishes each surface's world displacement and {@code world_reproject.comp} moves the
 * vector's previous end back by it ({@code prev = shown - ownMove + camDelta}). The viewmodel's capture is
 * camera-relative rather than rebased, so its displacement is the hand's own travel <em>plus the eye's</em>.
 * The earlier revision subtracted the camera delta and added a terrain-anchor term that cannot move
 * camera-relative geometry at all; at half a block of depth that is a several-hundred-pixel vector, and the
 * denoisers smear the hand into the scratches this test exists to prevent.
 *
 * <p>These are pure array checks: no Minecraft, no GPU, no capture.
 */
final class ViewmodelDisplacementTest {
    private static final double EPS = 1.0e-6;

    /** A trivially small mesh: two vertices, xyz each. */
    private static final float[] MESH = {0.2f, -0.15f, -0.4f, 0.22f, -0.14f, -0.38f};

    @Test
    void firstCaptureReportsNoMotion() {
        // previousValid == false: there is no history to reproject from. The host's first frame also pushes
        // the current matrices with a zero camera delta, so zero here keeps both ends of the vector equal.
        float[] disp = ViewmodelDisplacement.fill(MESH, MESH.length, null, 0, false, 1.5, -2.5, 0.75);

        assertEquals(2 * 4, disp.length);
        for (float v : disp) {
            assertEquals(0.0f, v, 0.0f);
        }
    }

    @Test
    void lockedHandReportsExactlyTheCameraTravel() {
        // The hand did not move relative to the eye, so its only world motion is the camera's. This is the
        // assertion that fails on the old sign: -camDelta would put the previous end a whole step on the
        // wrong side of where the hand actually was.
        float[] disp = ViewmodelDisplacement.fill(MESH, MESH.length, MESH, MESH.length, true, -0.4, 0.1, 0.25);

        for (int i = 0; i < 2; i++) {
            assertEquals(-0.4f, disp[i * 4], EPS);
            assertEquals(0.1f, disp[i * 4 + 1], EPS);
            assertEquals(0.25f, disp[i * 4 + 2], EPS);
            assertEquals(0.0f, disp[i * 4 + 3], 0.0f);
        }
    }

    @Test
    void swingAddsToTheCameraTravel() {
        float[] cur = {MESH[0] + 0.03f, MESH[1], MESH[2], MESH[3] + 0.03f, MESH[4], MESH[5]};
        float[] disp = ViewmodelDisplacement.fill(cur, cur.length, MESH, MESH.length, true, -0.4, 0.0, 0.0);

        assertEquals(-0.37f, disp[0], EPS);
        assertEquals(-0.37f, disp[4], EPS);
    }

    @Test
    void itemSwapFallsBackToTheCameraTravel() {
        // A new item has no per-vertex predecessor, but the hand still travels with the eye. Reporting zero
        // (what a null displacement used to mean) would tell the reprojection the hand is world-static, and
        // at hand depth that is a scene-sized vector on the first frame of every item swap.
        float[] disp = ViewmodelDisplacement.fill(MESH, MESH.length, new float[3 * 7], 3 * 7, true, -0.4, 0.0, 0.0);

        assertEquals(2 * 4, disp.length);
        for (int i = 0; i < 2; i++) {
            assertEquals(-0.4f, disp[i * 4], EPS);
            assertEquals(0.0f, disp[i * 4 + 1], EPS);
            assertEquals(0.0f, disp[i * 4 + 2], EPS);
        }
    }

    @Test
    void previousEndLandsBackOnTheHand() {
        // The invariant the shader relies on, spelled out: prev = shown - disp + camDelta. For a hand welded
        // to the eye that must be the point the hand shows at, which is the whole reason a locked hand has
        // (near) zero screen motion while the world sweeps past.
        double camDeltaX = -0.4;
        float[] disp = ViewmodelDisplacement.fill(MESH, MESH.length, MESH, MESH.length, true, camDeltaX, 0.0, 0.0);

        double shownX = MESH[0];
        assertEquals(shownX, (shownX - disp[0]) + camDeltaX, EPS);
    }

    @Test
    void emptyMeshHasNoDisplacementBuffer() {
        float[] disp = ViewmodelDisplacement.fill(new float[0], 0, null, 0, true, 1.0, 2.0, 3.0);
        assertNull(disp);
    }

}
