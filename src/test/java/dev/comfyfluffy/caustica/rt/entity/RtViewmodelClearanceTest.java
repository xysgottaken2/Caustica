package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the block-clearance pull-in applied to a captured first-person viewmodel.
 *
 * <p>The arm is ordinary path-traced geometry, so a block nearer than it hides it — and a cut-off arm
 * reads as the hand being buried inside the wall. The fix pulls the whole mesh toward the eye until it
 * fits in front of the nearest block. Two properties make that free of side effects, and both are easy
 * to break silently:
 *
 * <ul>
 *   <li>the pull-in must be a <em>uniform</em> scale about the eye — a perspective divide keeps
 *       {@code (kx)/(kz) == x/z}, so the on-screen image is bit-for-bit unchanged; and</li>
 *   <li>the clearance must be computed exactly. Ray probes were the previous approach and let a block
 *       corner slip between two probe directions, which is why the arm still poked through walls at
 *       some camera angles. {@link RtViewmodelCapture#clearOfBlock} solves the overlap interval in k
 *       instead, so "the whole box is in front of this block" is a proof, not a sample.</li>
 * </ul>
 */
final class RtViewmodelClearanceTest {

    /** A hand-sized camera-relative box: right of and below the eye, 0.5 to 1.0 blocks in front of it. */
    private static final float[] BOX_MIN = {0.1f, -0.6f, -1.0f};
    private static final float[] BOX_MAX = {0.7f, 0.1f, -0.5f};

    private static float clear(float k, double loX, double loY, double loZ,
                               double hiX, double hiY, double hiZ) {
        return RtViewmodelCapture.clearOfBlock(k, BOX_MIN, BOX_MAX, 0.0, 0.0, 0.0,
                loX, loY, loZ, hiX, hiY, hiZ, 0.1f);
    }

    /** Nothing the arm can reach: the mesh must be uploaded exactly as captured. */
    @Test
    void openAirLeavesTheViewmodelUntouched() {
        // A wall behind the camera.
        assertEquals(1.0f, clear(1.0f, -2.0, -2.0, 0.5, 2.0, 2.0, 3.0), 1.0e-6f);
        // A block to the left, outside the box's x range.
        assertEquals(1.0f, clear(1.0f, -4.0, -2.0, -2.0, -2.0, 2.0, 0.0), 1.0e-6f);
        // The floor: 1.6 blocks below the eye, well under the arm's lowest vertex.
        assertEquals(1.0f, clear(1.0f, -2.0, -2.6, -2.0, 2.0, -1.6, 0.0), 1.0e-6f);
        // A block just past the arm (0.5 to 1.0 in front, this one sits beyond 1.0).
        assertEquals(1.0f, clear(1.0f, -2.0, -2.0, -2.0, 2.0, 2.0, -1.05), 1.0e-6f);
        // An empty k already in front of a wall that is closer than the arm.
        assertEquals(0.2f, clear(0.2f, -2.0, -2.0, -1.3, 2.0, 2.0, -0.3), 1.0e-6f);
    }

    /** A wall across the arm's path pulls it in until its farthest vertex clears the face. */
    @Test
    void wallInFrontPullsTheWholeBoxBehindIt() {
        // Wall face at z = -0.3. The box's farthest z is -1.0 at k = 1, and -k once scaled, so the
        // box first reaches the wall at k = 0.3: that is the largest scale that stays clear.
        assertEquals(0.3f, clear(1.0f, -2.0, -2.0, -1.3, 2.0, 2.0, -0.3), 1.0e-6f);
    }

    /** A block wedged between the eye and the arm counts too — the arm must move in front of it. */
    @Test
    void occluderBetweenEyeAndArmAlsoPullsIn() {
        // Block spanning z in [-0.9, -0.8]; the box crosses it from k = 0.8 upwards.
        assertEquals(0.8f, clear(1.0f, -2.0, -2.0, -0.9, 2.0, 2.0, -0.8), 1.0e-6f);
    }

    /** A wall against the eye clamps instead of collapsing the arm onto the camera. */
    @Test
    void huggingAWallClampsToTheFloor() {
        assertEquals(0.1f, clear(1.0f, -2.0, -2.0, -1.02, 2.0, 2.0, -0.02), 1.0e-6f);
    }

    /** The eye's world position is what the block boxes are compared against, not the mesh origin. */
    @Test
    void clearanceIsRelativeToTheEyeNotTheWorldOrigin() {
        double eyeX = 128.0;
        double eyeY = 70.0;
        double eyeZ = -64.0;
        float k = RtViewmodelCapture.clearOfBlock(1.0f, BOX_MIN, BOX_MAX, eyeX, eyeY, eyeZ,
                eyeX - 2.0, eyeY - 2.0, eyeZ - 1.3, eyeX + 2.0, eyeY + 2.0, eyeZ - 0.3, 0.1f);
        assertEquals(0.3f, k, 1.0e-5f, "a block a third of a block ahead must pull the arm in");
        // The same block described relative to a different eye is a different wall.
        assertEquals(1.0f, RtViewmodelCapture.clearOfBlock(1.0f, BOX_MIN, BOX_MAX, 0.0, 0.0, 0.0,
                eyeX - 2.0, eyeY - 2.0, eyeZ - 1.3, eyeX + 2.0, eyeY + 2.0, eyeZ - 0.3, 0.1f), 1.0e-6f);
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
