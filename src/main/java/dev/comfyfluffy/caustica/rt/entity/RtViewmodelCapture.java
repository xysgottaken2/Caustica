package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.rt.RtComposite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix4f;

/**
 * First-person viewmodel (arm + held item) capture into the RT entity sink.
 *
 * <p>The vanilla viewmodel is a raster pass drawn after the world depth buffer is cleared, so it is
 * never part of the scene the path tracer sees. This class gives it a path-traced form instead: the
 * exact submission vanilla makes — {@code FirstPersonHandsAndItemsRenderer.submitHandsWithItems} into a
 * {@link SubmitNodeCollector} — is redirected into {@link RtEntityCollector}, so the arm, baked item
 * models, special models and every material resolution (block atlas POM, LabPBR items, emissive sprites)
 * travel through the same capture code as entities. {@link RtEntities} then uploads that mesh as a
 * transient BLAS with its own TLAS instance, and the record is tagged so {@code world.rchit} can mark
 * viewmodel hits. Because it is a normal TLAS instance, a nearer block wins the nearest hit. That alone
 * would only <em>cut</em> the arm at the wall, though, and a cut arm still reads as the hand being stuck
 * inside the block, so the consuming frame also pulls the mesh toward the eye until it fits in front of
 * the nearest block (see {@link #scaleUniform} and {@code RtEntities.viewmodelPullIn}).
 *
 * <p><b>Why one frame of latency.</b> The capture has to run where vanilla submits the hand, which is
 * {@code render3dHud} — <em>after</em> this frame's RT composite (the composite runs at the before-hand
 * seam so the HUD draws at native resolution on top of it). The mesh captured here is therefore consumed
 * by the next frame. That is harmless for a camera-relative viewmodel: it is re-posed into the current
 * camera's axes every frame (see {@link #takePosed}), so camera motion cannot make it lag. Only the
 * hand's own animation (bob/swing/item swap) trails by one frame, and its per-vertex motion deltas are
 * measured in that same camera-relative space, so the reprojection stays exact.
 *
 * <p><b>Geometry space.</b> {@code renderItemInHand} seeds the hand {@code PoseStack} with
 * {@code viewRotationMatrix.invert()}, i.e. the view-to-world camera rotation, and the hand pass binds
 * the view rotation itself as its model-view ({@code RenderSystem.getModelViewStack().mul(viewRotationMatrix)}).
 * Every submitted vertex is therefore in <b>camera-relative world space for the capture frame</b>: world
 * axes, camera at the origin, still carrying that frame's camera orientation. The frame that consumes
 * the capture would misplace it if it were uploaded as-is (the arm would be stuck pointing in the
 * capture frame's directions instead of staying in front of the eye), so {@link #takePosed} rotates it
 * by the relative camera rotation between the two frames and applies the fixed-viewmodel-FOV lateral
 * scale in view space. The instance transform is then a pure translation to the camera position, and the
 * per-vertex motion deltas stay exact while the camera turns.
 *
 * <p><b>Failure policy.</b> Until a capture has succeeded once, the submission is mirrored into the
 * caller's collector, so the vanilla hand keeps drawing. A capture that throws latches the RT viewmodel
 * off (logged once) and the vanilla hand stays visible — the viewmodel can never simply disappear.
 */
public final class RtViewmodelCapture {
    public static final RtViewmodelCapture INSTANCE = new RtViewmodelCapture();

    private final RtEntityCapture capture = new RtEntityCapture();
    /** Dedicated sink: entity/block-entity/particle capture uses the one owned by {@link RtEntities}. */
    private final RtEntityCollector collector = new RtEntityCollector();

    private boolean capturing;
    private long capturingFrame = -1L;
    private boolean pending;
    private boolean failed;
    /** True once a submission has been captured successfully; gates the vanilla hand mirror. */
    private boolean capturedOnce;
    /** Consecutive active submissions that produced no geometry — a diagnostic, not a gate. */
    private int emptyStreak;
    private boolean reportedEmpty;
    private static final int EMPTY_STREAK_WARN = 120;
    /** Hand FOV (degrees) that vanilla used for the hand projection of the capture being consumed. */
    private float handFov = 70.0f;
    /** World-to-view basis of the frame the pending capture came from (JOML storage order). */
    private final float[] captureBasis = new float[9];
    private boolean captureBasisKnown;
    /** Scratch for the consuming frame's world-to-view basis. */
    private final float[] currentBasis = new float[9];

    private RtViewmodelCapture() {
    }

    /**
     * Install the capture sink for one {@code submitHandsWithItems} call. Returns {@code collector}
     * (which may also forward to {@code vanilla} while warming up) or {@code vanilla} untouched when the
     * RT viewmodel is not active this frame.
     */
    public SubmitNodeCollector begin(SubmitNodeCollector vanilla) {
        // A second submission in the same hand pass keeps accumulating into the capture that is already
        // open (main + off hand would otherwise overwrite each other). A capture left open by an earlier
        // frame — a submission that threw or a cancelled frame — is dropped instead.
        if (this.capturing) {
            if (this.capturingFrame == RtComposite.frameCounter()) {
                return this.collector;
            }
            this.capturing = false;
            this.collector.begin(null, false);
        }
        if (!active()) {
            // No RT hand this frame (third person, paused, RT off): drop any capture the previous frame
            // left behind so a stale pose can never be uploaded when the RT world comes back.
            this.pending = false;
            return vanilla;
        }
        try {
            this.capture.reset();
            this.collector.viewmodelMode(true);
            // Mirror into vanilla's sink until the RT path has captured the viewmodel once: the first RT
            // frame, a capture failure and a resource reload then degrade to the vanilla hand instead of
            // a missing one.
            this.collector.delegate(this.capturedOnce ? null : vanilla);
            this.collector.begin(this.capture, false);
            this.capturing = true;
            this.capturingFrame = RtComposite.frameCounter();
            return this.collector;
        } catch (Throwable t) {
            fail(t);
            return vanilla;
        }
    }

    /**
     * Seal the submission started by {@link #begin}: the capture becomes available to the frame.
     *
     * @param captureViewRotation world-to-view rotation of the camera the hand was submitted with, i.e.
     *                            the rotation baked into the captured vertices. Recorded so the consuming
     *                            frame can re-express the arm in its own camera orientation.
     */
    public void end(Matrix4f captureViewRotation) {
        if (!this.capturing) {
            return;
        }
        this.capturing = false;
        this.captureBasisKnown = basis(captureViewRotation, this.captureBasis);
        try {
            this.collector.begin(null, false);
            this.pending = this.capture.idx.size() >= 3 && this.capture.verts.size() >= 9;
            if (this.pending) {
                this.capturedOnce = true;
                this.emptyStreak = 0;
            } else if (++this.emptyStreak == EMPTY_STREAK_WARN && !this.reportedEmpty) {
                // A systematically empty capture means the hand's geometry is not reaching this sink (for
                // example, deferred to a feature flush after renderItemInHand). The vanilla hand keeps
                // drawing in that case, so this is a diagnostic rather than a failure.
                this.reportedEmpty = true;
                CausticaMod.LOGGER.warn("RT viewmodel capture produced no geometry over {} hand submissions;"
                        + " keeping the vanilla hand", this.emptyStreak);
            }
        } catch (Throwable t) {
            fail(t);
        } finally {
            this.collector.delegate(null);
            this.collector.viewmodelMode(false);
        }
    }

    /**
     * Record the FOV vanilla used to build the hand projection ({@code caustica$applyHandFov} sees the
     * exact value, including the death/fluid modulation, after the Hand FOV toggle). The capture is
     * consumed next frame, so this is the hand FOV that mesh was authored for.
     */
    public void setHandFov(float fov) {
        if (fov > 1.0f && fov <= 180.0f && Float.isFinite(fov)) {
            this.handFov = fov;
        }
    }

    /** @see #setHandFov(float) */
    public float handFov() {
        return this.handFov;
    }

    /** Whether a completed submission is waiting for the next {@link RtEntities#beginFrame}. */
    public boolean hasPending() {
        return this.pending;
    }

    /**
     * Consume the pending capture, re-expressing it in this frame's camera orientation and applying the
     * fixed-viewmodel-FOV lateral scale. Returns {@code null} when nothing was captured.
     *
     * @param currentViewRotation world-to-view rotation of the frame consuming the capture
     */
    RtEntityCapture takePosed(float lateralScale, Matrix4f currentViewRotation) {
        if (!this.pending) {
            return null;
        }
        this.pending = false;
        if (!basis(currentViewRotation, this.currentBasis)) {
            return this.capture; // no camera basis this frame: upload as captured rather than guess
        }
        if (!this.captureBasisKnown) {
            // Nothing recorded at seal time (RT was not active when the hand was submitted): the capture
            // is at most one frame old, so treating it as already current is the closest available guess.
            System.arraycopy(this.currentBasis, 0, this.captureBasis, 0, 9);
        }
        poseCameraRelative(this.capture.verts.elements(), this.capture.verts.size(),
                this.capture.prim.elements(), this.capture.prim.size(),
                lateralScale, this.captureBasis, this.currentBasis);
        return this.capture;
    }

    /** Copy a world-to-view rotation into {@code out} (JOML storage order); false when {@code m} is null. */
    private static boolean basis(Matrix4f m, float[] out) {
        if (m == null) {
            return false;
        }
        out[0] = m.m00();
        out[1] = m.m01();
        out[2] = m.m02();
        out[3] = m.m10();
        out[4] = m.m11();
        out[5] = m.m12();
        out[6] = m.m20();
        out[7] = m.m21();
        out[8] = m.m22();
        return true;
    }

    /**
     * Re-express a capture from its own camera orientation into {@code currentView}'s, scaling it
     * laterally by {@code s}, in place.
     *
     * <p>Vertices arrive in world axes with the capture camera at the origin. Undoing that frame's
     * world-to-view rotation puts them in view space — the hand's true camera-local pose, which must not
     * change when the camera merely turns. The lateral scale then belongs there too (the FOV acts on
     * view x/y), and applying the consuming frame's view-to-world rotation finally places the arm in
     * front of the <em>current</em> camera. Normals use the inverse-transpose (V_cᵀ·S⁻¹·V_c followed by
     * V_cur), renormalised.
     *
     * <p>Bases are 9 floats in JOML storage order (m00,m01,m02,m10,m11,m12,m20,m21,m22). Package-private
     * with primitive arguments so the transform can be unit-tested without a render context.
     */
    static void poseCameraRelative(float[] verts, int vertexFloats, float[] prim, int primFloats, float s,
                                   float[] captureView, float[] currentView) {
        if (currentView == null) {
            return;
        }
        float k = s > 0.0f && Float.isFinite(s) ? s : 1.0f;
        for (int i = 0; i < vertexFloats; i += 3) {
            float x = verts[i];
            float y = verts[i + 1];
            float z = verts[i + 2];
            // World -> view in the capture frame (rows of V_c; a[0..2] is its first math row).
            float vx = captureView == null ? x : captureView[0] * x + captureView[3] * y + captureView[6] * z;
            float vy = captureView == null ? y : captureView[1] * x + captureView[4] * y + captureView[7] * z;
            float vz = captureView == null ? z : captureView[2] * x + captureView[5] * y + captureView[8] * z;
            vx *= k;
            vy *= k;
            // View -> world for the current frame (V_curᵀ; math row r contributes to every column).
            verts[i] = currentView[0] * vx + currentView[1] * vy + currentView[2] * vz;
            verts[i + 1] = currentView[3] * vx + currentView[4] * vy + currentView[5] * vz;
            verts[i + 2] = currentView[6] * vx + currentView[7] * vy + currentView[8] * vz;
        }

        for (int base = 0; base < primFloats; base += 12) {
            float nx = prim[base];
            float ny = prim[base + 1];
            float nz = prim[base + 2];
            float vx = captureView == null ? nx : captureView[0] * nx + captureView[3] * ny + captureView[6] * nz;
            float vy = captureView == null ? ny : captureView[1] * nx + captureView[4] * ny + captureView[7] * nz;
            float vz = captureView == null ? nz : captureView[2] * nx + captureView[5] * ny + captureView[8] * nz;
            vx /= k;
            vy /= k;
            float wx = currentView[0] * vx + currentView[1] * vy + currentView[2] * vz;
            float wy = currentView[3] * vx + currentView[4] * vy + currentView[5] * vz;
            float wz = currentView[6] * vx + currentView[7] * vy + currentView[8] * vz;
            float len = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (len > 1.0e-8f && Float.isFinite(len)) {
                wx /= len;
                wy /= len;
                wz /= len;
            } else {
                wx = 0.0f;
                wy = 0.0f;
                wz = 0.0f;
            }
            prim[base] = wx;
            prim[base + 1] = wy;
            prim[base + 2] = wz;
        }
    }

    /**
     * Largest scale {@code k' <= k} (never below {@code minK}) for which the camera-relative box
     * {@code [boxMin, boxMax]} scaled by {@code k'} about the eye misses the world-space block box
     * {@code [lo, hi]}. Returns {@code k} untouched when the block cannot constrain it.
     *
     * <p>The scaled box on one axis is {@code [eye + k*a, eye + k*b]}, and "overlaps the block on this
     * axis" is two linear inequalities in k, so the whole overlap is an interval
     * {@code [enter, leave]}. Below {@code enter} the box is in front of the block (the case we want);
     * above {@code leave} the block sits between the eye and the box, which is just as wrong for a
     * viewmodel that must stay visible — so anything at or past {@code enter} is pulled back to it.
     *
     * <p>Pure and package-private so the clearance rule is unit-tested without a world.
     */
    static float clearOfBlock(float k, float[] boxMin, float[] boxMax,
                              double eyeX, double eyeY, double eyeZ,
                              double loX, double loY, double loZ,
                              double hiX, double hiY, double hiZ, float minK) {
        double enter = Double.NEGATIVE_INFINITY;   // first scale at which the box reaches the block
        double leave = Double.POSITIVE_INFINITY;   // first scale at which the box is past it
        for (int axis = 0; axis < 3; axis++) {
            double eye = axis == 0 ? eyeX : axis == 1 ? eyeY : eyeZ;
            double lo = axis == 0 ? loX : axis == 1 ? loY : loZ;
            double hi = axis == 0 ? hiX : axis == 1 ? hiY : hiZ;
            double a = boxMin[axis];
            double b = boxMax[axis];
            // eye + k * a <= hi
            double d = hi - eye;
            if (a > 0.0) {
                leave = Math.min(leave, d / a);
            } else if (a < 0.0) {
                enter = Math.max(enter, d / a);
            } else if (d < 0.0) {
                return k; // the box never reaches this block on this axis
            }
            // eye + k * b >= lo
            d = lo - eye;
            if (b > 0.0) {
                enter = Math.max(enter, d / b);
            } else if (b < 0.0) {
                leave = Math.min(leave, d / b);
            } else if (d > 0.0) {
                return k; // the box never reaches this block on this axis
            }
        }
        if (!(enter <= leave)) {
            return k; // no scale makes the box touch this block
        }
        if (leave < 0.0) {
            return k; // only a mirrored (negative) scale would reach it
        }
        if (k <= enter) {
            return k; // already in front of it
        }
        return Math.max(minK, (float) Math.max(enter, 0.0));
    }

    /**
     * Uniformly scale camera-relative positions toward the eye, in place. Uniform scaling about the
     * camera is projection-exact — a perspective divide makes {@code (k·x)/(k·z) == x/z} — so the
     * viewmodel keeps its exact on-screen size and position while every vertex moves closer to the eye.
     * That is what lets the arm fit in front of a nearby block instead of being cut by it.
     *
     * <p>Only the {@code vertexFloats} position floats are touched — normals and tangents are derived
     * from the geometry, not stored here. Only pulls in; {@code k >= 1} is a no-op.
     */
    static void scaleUniform(float[] verts, int vertexFloats, float k) {
        if (!(k > 0.0f) || !(k < 1.0f) || !Float.isFinite(k)) {
            return;
        }
        for (int i = 0; i < vertexFloats; i++) {
            verts[i] *= k;
        }
    }

    /** Whether the RT viewmodel may be rendered: RT world output is live and the local player is in first person. */
    private boolean active() {
        if (this.failed) {
            return false;
        }
        if (!CausticaConfig.Rt.ENABLED.value() || !CausticaConfig.Rt.Entities.ENABLED.value()) {
            return false;
        }
        if (!VanillaRenderController.INSTANCE.shouldRenderRtViewmodel()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return false;
        }
        return minecraft.options.getCameraType().isFirstPerson();
    }

    private void fail(Throwable t) {
        this.failed = true;
        this.capturing = false;
        this.pending = false;
        try {
            this.collector.delegate(null);
            this.collector.viewmodelMode(false);
            this.collector.begin(null, false);
        } catch (Throwable ignored) {
            // best-effort cleanup; the vanilla hand is drawn by the caller's own collector again
        }
        CausticaMod.LOGGER.error("RT viewmodel capture failed; using the vanilla hand instead", t);
    }

    /** Drop all pending state and caches (RT shutdown). */
    public void clear() {
        this.capturing = false;
        this.capturingFrame = -1L;
        this.pending = false;
        this.failed = false;
        this.capturedOnce = false;
        this.emptyStreak = 0;
        this.reportedEmpty = false;
        this.captureBasisKnown = false;
        this.capture.reset();
        this.collector.delegate(null);
        this.collector.viewmodelMode(false);
        this.collector.begin(null, false);
        this.collector.clearCaches();
    }

    /** Resource reload: drop model/atlas-backed caches; the next submission re-resolves its materials. */
    public void onResourceReload() {
        this.collector.clearCaches();
    }
}
