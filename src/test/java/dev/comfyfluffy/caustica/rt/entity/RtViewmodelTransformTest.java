package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the camera-space transform applied to a captured first-person viewmodel.
 *
 * <p>The capture arrives in camera-relative world space that still carries the submitting frame's
 * camera orientation, because {@code GameRenderer.renderItemInHand} seeds the hand {@code PoseStack}
 * with {@code viewRotationMatrix.invert()}. The consuming frame has to undo that rotation, apply the
 * Hand-FOV lateral scale in view space, and re-apply its own camera rotation. Getting that wrong is not
 * a compile error: the arm simply stops following the camera and floats in mid air, which is exactly
 * the regression these tests exist to prevent.
 */
final class RtViewmodelTransformTest {
    /** Identity world-to-view rotation in JOML storage order (m&lt;column&gt;&lt;row&gt;). */
    private static final float[] IDENTITY = {1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 1.0f};

    /** 90-degree yaw world-to-view rotation in JOML storage order (m&lt;column&gt;&lt;row&gt;). */
    private static final float[] YAW = {0.0f, 0.0f, -1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 0.0f};

    @Test
    void viewmodelFollowsTheCameraInsteadOfStayingPinnedToWorldAxes() {
        // Same camera on both frames: the transform must be a pure no-op.
        float[] forward = {0.0f, 0.0f, 1.0f};
        RtViewmodelCapture.poseCameraRelative(forward, 3, new float[0], 0, 1.0f, IDENTITY, IDENTITY);
        assertEquals(0.0f, forward[0], 1.0e-6f, "same camera: the capture must pass through untouched");
        assertEquals(1.0f, forward[2], 1.0e-6f);

        // The camera then yaws 90 degrees. The arm must still be in front of the eye, i.e. on the NEW
        // camera's forward axis (world -X for this basis), not still pointing along the old one.
        float[] stillForward = {0.0f, 0.0f, 1.0f};
        RtViewmodelCapture.poseCameraRelative(stillForward, 3, new float[0], 0, 1.0f, IDENTITY, YAW);
        assertEquals(-1.0f, stillForward[0], 1.0e-6f, "the arm must follow the camera's new forward axis");
        assertEquals(0.0f, stillForward[1], 1.0e-6f);
        assertEquals(0.0f, stillForward[2], 1.0e-6f);

        // A capture taken and consumed with the same orientation round-trips exactly (Vᵀ·V = I), so a
        // static hand keeps bit-exact geometry instead of drifting by tiny rotation errors each frame.
        float[] roundTrip = {0.3f, -0.2f, 0.9f};
        RtViewmodelCapture.poseCameraRelative(roundTrip, 3, new float[0], 0, 1.0f, YAW, YAW);
        assertEquals(0.3f, roundTrip[0], 1.0e-6f);
        assertEquals(-0.2f, roundTrip[1], 1.0e-6f);
        assertEquals(0.9f, roundTrip[2], 1.0e-6f);
    }

    @Test
    void handFovScaleIsAppliedAlongTheViewAxesNotWorldXY() {
        // The lateral scale must act on the camera's own right/up axes. Applied to world x/y instead (the
        // naive fix), a yawed camera would scale the wrong directions and the hand would change shape.
        float[] alongRight = {0.0f, 0.0f, 1.0f};   // camera right in world space for this basis
        float[] alongForward = {-1.0f, 0.0f, 0.0f}; // camera forward in world space
        RtViewmodelCapture.poseCameraRelative(alongRight, 3, new float[0], 0, 2.0f, YAW, YAW);
        RtViewmodelCapture.poseCameraRelative(alongForward, 3, new float[0], 0, 2.0f, YAW, YAW);

        assertEquals(0.0f, alongRight[0], 1.0e-6f, "a lateral point must not gain a forward component");
        assertEquals(0.0f, alongRight[1], 1.0e-6f);
        assertEquals(2.0f, alongRight[2], 1.0e-6f, "the lateral axis must scale by exactly s");
        assertEquals(-1.0f, alongForward[0], 1.0e-6f, "the forward axis must not be scaled");
        assertEquals(0.0f, alongForward[2], 1.0e-6f, "the forward axis must keep its direction");
    }

    @Test
    void normalsStayUnitLengthAndKeepTheirForwardComponent() {
        // A normal with a forward component must stay normalized after the anisotropic inverse-transpose.
        float[] prim = {0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
        RtViewmodelCapture.poseCameraRelative(new float[0], 0, prim, prim.length, 4.0f, YAW, YAW);
        assertEquals(1.0f, prim[2], 1.0e-6f, "the camera-forward normal is not a lateral axis");

        float inv = (float) (1.0 / Math.sqrt(2.0));
        float[] diagonal = {inv, 0.0f, inv, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
        RtViewmodelCapture.poseCameraRelative(new float[0], 0, diagonal, diagonal.length, 4.0f, YAW, YAW);
        float len = (float) Math.sqrt(diagonal[0] * diagonal[0] + diagonal[1] * diagonal[1]
                + diagonal[2] * diagonal[2]);
        assertEquals(1.0f, len, 1.0e-5f, "the transformed normal must be renormalised");
        assertEquals(0.0f, diagonal[1], 1.0e-6f, "a normal in the right/forward plane keeps zero up");
    }

    @Test
    void missingCameraBasisLeavesTheCaptureUntouched() {
        float[] verts = {1.0f, 2.0f, 3.0f};
        RtViewmodelCapture.poseCameraRelative(verts, 3, new float[0], 0, 2.0f, IDENTITY, null);
        assertEquals(1.0f, verts[0], 1.0e-6f, "without a camera basis the mesh must not be guessed at");
        assertEquals(2.0f, verts[1], 1.0e-6f);
        assertEquals(3.0f, verts[2], 1.0e-6f);
    }
}
