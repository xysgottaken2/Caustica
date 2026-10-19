package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the block-clearance pull-in applied to a captured first-person viewmodel.
 *
 * <p>The arm is ordinary path-traced geometry now, so a block nearer than it hides it — and that reads
 * as the hand being buried inside the wall. The fix pulls the whole mesh toward the eye until it fits in
 * front of the nearest block. Two properties make that free of side effects, and both are easy to break
 * silently: the pull-in must be a <em>uniform</em> scale about the eye (a perspective divide keeps
 * {@code (kx)/(kz) == x/z}, so the on-screen image is bit-for-bit unchanged), and the ratio must never
 * overshoot into the wall or collapse onto the camera.
 */
final class RtViewmodelClearanceTest {

    /** Nothing between the eye and the arm: the mesh must be uploaded exactly as captured. */
    @Test
    void openAirLeavesTheViewmodelUntouched() {
        assertEquals(1.0f, RtViewmodelCapture.pullInRatio(Float.NaN, 1.0f, 0.02f, 0.1f), 1.0e-6f);
        assertEquals(1.0f, RtViewmodelCapture.pullInRatio(Float.POSITIVE_INFINITY, 1.0f, 0.02f, 0.1f), 1.0e-6f);
        // A hit farther than the arm itself cannot be in front of it.
        assertEquals(1.0f, RtViewmodelCapture.pullInRatio(1.5f, 1.0f, 0.02f, 0.1f), 1.0e-6f);
        // A degenerate (zero-length) probe has no direction to divide by.
        assertEquals(1.0f, RtViewmodelCapture.pullInRatio(0.3f, 0.0f, 0.02f, 0.1f), 1.0e-6f);
    }

    /** A wall fractionally closer than the arm pulls it just far enough to keep a small margin. */
    @Test
    void wallJustInFrontPullsTheArmBehindIt() {
        // Wall at 0.48, arm at 1.0, 0.02 of clearance: the arm must shrink to 46% of its distance.
        assertEquals(0.46f, RtViewmodelCapture.pullInRatio(0.48f, 1.0f, 0.02f, 0.1f), 1.0e-6f);
        // Whatever the ratio, the scaled probe point must not reach the hit distance.
        float k = RtViewmodelCapture.pullInRatio(0.48f, 1.0f, 0.02f, 0.1f);
        assertEquals(0.48f, k * 1.0f + 0.02f, 1.0e-6f);
    }

    /** A wall almost touching the eye cannot shrink the arm onto the camera. */
    @Test
    void huggingAWallClampsInsteadOfCollapsingIntoTheEye() {
        assertEquals(0.1f, RtViewmodelCapture.pullInRatio(0.01f, 1.0f, 0.02f, 0.1f), 1.0e-6f);
        // Behind the eye (a hit at negative distance) is clamped the same way, never inverted.
        assertEquals(0.1f, RtViewmodelCapture.pullInRatio(-4.0f, 1.0f, 0.02f, 0.1f), 1.0e-6f);
    }

    /** Uniform scaling about the eye keeps every projected position, which is the whole point. */
    @Test
    void uniformScalePreservesTheProjectedImage() {
        float[] vertex = {0.56f, -0.52f, -0.72f};
        float screenX = vertex[0] / -vertex[2];
        float screenY = vertex[1] / -vertex[2];
        RtViewmodelCapture.scaleUniform(vertex, vertex.length, 0.3f);
        assertEquals(0.56f * 0.3f, vertex[0], 1.0e-6f);
        assertEquals(-0.52f * 0.3f, vertex[1], 1.0e-6f);
        assertEquals(-0.72f * 0.3f, vertex[2], 1.0e-6f);
        assertEquals(screenX, vertex[0] / -vertex[2], 1.0e-6f, "x/z must survive the pull-in");
        assertEquals(screenY, vertex[1] / -vertex[2], 1.0e-6f, "y/z must survive the pull-in");
    }

    /** Only positions are scaled, and only in the shrinking direction. */
    @Test
    void scaleIsNoOpWhenItWouldMoveTheArmAwayFromTheEye() {
        float[] positions = {0.1f, 0.2f, -0.3f};
        for (float k : new float[]{1.0f, 1.5f, 0.0f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY}) {
            float[] copy = positions.clone();
            RtViewmodelCapture.scaleUniform(copy, copy.length, k);
            for (int i = 0; i < copy.length; i++) {
                assertEquals(positions[i], copy[i], 1.0e-6f, "k=" + k + " must be a no-op");
            }
        }
        // The stride argument bounds what is touched: normals and UVs after `vertexFloats` stay intact.
        float[] mixed = {1.0f, 1.0f, 1.0f, 7.0f, 7.0f, 7.0f};
        RtViewmodelCapture.scaleUniform(mixed, 3, 0.5f);
        assertEquals(0.5f, mixed[0], 1.0e-6f);
        assertEquals(7.0f, mixed[3], 1.0e-6f);
    }
}
