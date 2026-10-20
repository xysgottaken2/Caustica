package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.pipeline.RtSvgfDenoiser;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numerical verification of the SVGF reprojection's previous-depth prediction
 * ({@link RtSvgfDenoiser#depthPrediction} + the reconstruction in {@code svgf_reproject.comp}).
 *
 * <p>The shader's geometry gate accepts reprojected history only when the previous frame's
 * stored view depth matches the predicted one within 5%. The prediction used to be the scalar
 * {@code z + forwardTravel}, which is exact for pure translation but wrong under camera
 * rotation by {@code distance * sin(deltaRot) * sin(bearing)} — the "light/shadows flicker
 * while moving, settle when still" failure (at 30 fps an ordinary mouse turn mispredicts
 * wide-bearing depths by ~10%). These tests check the replacement affine row against a
 * brute-force reprojection of known world points, including the rotation cases the scalar
 * got wrong, and keep one assertion documenting the old error so the regression cannot be
 * reintroduced by "simplifying" back to the scalar.
 */
final class RtSvgfDepthPredictionTest {

    /** Minecraft-style projection: y-flipped, reversed-Z, clip.w = view depth. */
    private static Matrix4f projection(float shearX, float shearY) {
        Matrix4f p = new Matrix4f().identity();
        p.m00(1.2f);            // f / aspect
        p.m11(-1.7f);           // -f (Vulkan y-flip, as the game's projection carries)
        p.m20(shearX);          // clip.x coefficient of view Z (view-bob shear)
        p.m21(shearY);          // clip.y coefficient of view Z
        p.m23(-1.0f);           // clip.w = -z_view = viewZ
        p.m33(0.0f);            // a real perspective matrix has no w contribution from w
        p.m22(0.0f);
        p.m32(0.05f);           // reversed-Z depth row (unused by the prediction)
        return p;
    }

    private static Matrix4f rotation(float yawDeg, float pitchDeg, float rollDeg) {
        Quaternionf q = new Quaternionf().rotateYXZ(
                (float) Math.toRadians(yawDeg),
                (float) Math.toRadians(pitchDeg),
                (float) Math.toRadians(rollDeg));
        return new Matrix4f().rotation(q);
    }

    /** A world point seen from {@code cam} at view-space ({@code vx}, {@code vy}, {@code -depth}). */
    private static Vector3f worldPoint(Matrix4f curRot, Vector3f cam,
                                       float vx, float vy, float depth) {
        return new Matrix4f(curRot).invert()
                .transformPosition(new Vector3f(vx, vy, -depth))
                .add(cam);
    }

    /** The shader's side of the contract, mirrored from svgf_reproject.comp. */
    private static float predict(float[] row, float ndcX, float ndcY, float viewZ) {
        float viewX = (ndcX + row[6]) * viewZ * row[4];
        float viewY = (ndcY + row[7]) * viewZ * row[5];
        return -(row[0] * viewX + row[1] * viewY + row[2] * -viewZ + row[3]);
    }

    /** Brute-force truth: transform the world point into each camera and project. */
    private record Sample(float ndcX, float ndcY, float viewZ, float prevViewZ) {
    }

    private static Sample sample(Matrix4f proj, Matrix4f curRot, Matrix4f prevRot,
                                 Vector3f cam, Vector3f prevCam, Vector3f point) {
        Vector4f curView = new Vector4f(point.x - cam.x, point.y - cam.y, point.z - cam.z, 1.0f)
                .mul(curRot);
        Vector4f clip = new Vector4f(curView).mul(proj);
        Vector4f prevView = new Vector4f(point.x - prevCam.x, point.y - prevCam.y,
                point.z - prevCam.z, 1.0f).mul(prevRot);
        return new Sample(clip.x / clip.w, clip.y / clip.w, -curView.z, -prevView.z);
    }

    private static void assertPrediction(Matrix4f proj, Matrix4f curRot, Matrix4f prevRot,
                                         Vector3f cam, Vector3f prevCam, Vector3f point) {
        Sample s = sample(proj, curRot, prevRot, cam, prevCam, point);
        assertTrue(s.viewZ() > 0.1f && s.prevViewZ() > 0.1f,
                "test point must be in front of both cameras");
        float[] row = RtSvgfDenoiser.depthPrediction(proj, curRot, prevRot,
                cam.x - prevCam.x, cam.y - prevCam.y, cam.z - prevCam.z);
        float predicted = predict(row, s.ndcX(), s.ndcY(), s.viewZ());
        // The gate's tolerance is 5%; the prediction itself must be exact to float precision.
        assertEquals(s.prevViewZ(), predicted, Math.max(1.0e-3, s.prevViewZ() * 2.0e-3),
                "predicted previous depth must match the brute-force reprojection");
    }

    @Test
    void pureTranslationStaysExact() {
        // The one case the old scalar also got right; it must not regress.
        Matrix4f proj = projection(0.0f, 0.0f);
        Matrix4f rot = rotation(35.0f, -12.0f, 0.0f);
        Vector3f cam = new Vector3f(120.5f, 64.0f, -40.25f);
        Vector3f prevCam = new Vector3f(120.1f, 63.8f, -40.9f);
        float[][] viewPoints = {{0, 0, 12}, {6, -2, 25}, {-15, 4, 40}, {0.5f, 0.5f, 1.2f}};
        for (float[] vp : viewPoints) {
            assertPrediction(proj, rot, rot, cam, prevCam,
                    worldPoint(rot, cam, vp[0], vp[1], vp[2]));
        }
    }

    @Test
    void rotationWhileWalkingIsExactWhereTheScalarFailed() {
        Matrix4f proj = projection(0.0f, 0.0f);
        Matrix4f curRot = rotation(0.0f, 0.0f, 0.0f);
        // One frame at 30 fps of an ordinary mouse turn: ~6 degrees of yaw, a touch of pitch.
        Matrix4f prevRot = rotation(6.0f, 1.5f, 0.0f);
        Vector3f cam = new Vector3f(0.0f, 64.0f, 0.0f);
        Vector3f prevCam = new Vector3f(-0.11f, 63.98f, -0.03f); // walking while turning
        float[][] viewPoints = {
                {8, -1, 16},     // off-axis, mid distance
                {-25, 3, 30},    // wide bearing, the worst case
                {30, -5, 54},
                {1.5f, 0.2f, 3}, // close range
                {0.2f, 0, 25},   // near the view axis
        };
        for (float[] vp : viewPoints) {
            assertPrediction(proj, curRot, prevRot, cam, prevCam,
                    worldPoint(curRot, cam, vp[0], vp[1], vp[2]));
        }

        // Document the bug being fixed: for the wide-bearing point the old scalar prediction
        // (current depth + forward travel along the current view row) misses the true previous
        // depth by far more than the gate's 5% tolerance (measured: ~10% for this frame).
        Vector3f point = worldPoint(curRot, cam, -25, 3, 30);
        Sample s = sample(proj, curRot, prevRot, cam, prevCam, point);
        double dx = cam.x - prevCam.x, dy = cam.y - prevCam.y, dz = cam.z - prevCam.z;
        float forwardTravel = (float) -(curRot.m02() * dx + curRot.m12() * dy + curRot.m22() * dz);
        float oldPrediction = s.viewZ() + forwardTravel;
        double oldRelativeError = Math.abs(oldPrediction - s.prevViewZ()) / s.prevViewZ();
        assertTrue(oldRelativeError > 0.05,
                "the old scalar should demonstrably fail this case, got " + oldRelativeError);
    }

    @Test
    void combinedRotationTranslationAndShearAreExact() {
        Matrix4f proj = projection(0.012f, -0.02f); // view-bob shear in the projection
        Matrix4f curRot = rotation(123.0f, -18.0f, 2.0f);
        Matrix4f prevRot = rotation(120.1f, -16.4f, 1.2f);
        Vector3f cam = new Vector3f(-512.4f, 71.0f, 893.7f);
        Vector3f prevCam = new Vector3f(-512.55f, 70.87f, 893.61f);
        Random random = new Random(0xCA0571CA);
        int checked = 0;
        for (int i = 0; i < 400 && checked < 60; i++) {
            Vector3f point = worldPoint(curRot, cam,
                    (random.nextFloat() - 0.5f) * 80.0f,
                    (random.nextFloat() - 0.5f) * 30.0f,
                    0.6f + random.nextFloat() * 80.0f);
            Sample s = sample(proj, curRot, prevRot, cam, prevCam, point);
            if (s.prevViewZ() <= 0.5f) {
                continue; // the turn swung this point behind the previous camera
            }
            assertPrediction(proj, curRot, prevRot, cam, prevCam, point);
            checked++;
        }
        assertTrue(checked >= 40, "expected a meaningful number of valid samples, got " + checked);
    }
}
