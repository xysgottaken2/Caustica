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
 * viewmodel hits. Because it is a normal TLAS instance, a nearer block already wins the nearest hit —
 * the arm is depth-tested against the world per pixel instead of clipping through it.
 *
 * <p><b>Why one frame of latency.</b> The capture has to run where vanilla submits the hand, which is
 * {@code render3dHud} — <em>after</em> this frame's RT composite (the composite runs at the before-hand
 * seam so the HUD draws at native resolution on top of it). The mesh captured here is therefore consumed
 * by the next frame. That is harmless for a camera-relative viewmodel: it is re-posed into the current
 * camera's axes every frame (see {@link #takePosed}), so camera motion cannot make it lag. Only the
 * hand's own animation (bob/swing/item swap) trails by one frame, and its per-vertex motion deltas are
 * measured in that same camera-relative space, so the reprojection stays exact.
 *
 * <p><b>Geometry space.</b> Vanilla seeds the hand {@code PoseStack} with the camera view rotation, so
 * the captured vertices are in view space. They are re-expressed into camera-relative world axes (and
 * laterally scaled for a fixed viewmodel FOV — see {@code RtEntities#viewmodelLateralScale}) before the
 * upload, which lets the instance live at a translation-only transform and keeps motion vectors correct
 * while the camera turns.
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

    /** Seal the submission started by {@link #begin}: the capture becomes available to the frame. */
    public void end() {
        if (!this.capturing) {
            return;
        }
        this.capturing = false;
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
     * Consume the pending capture, re-expressing it from view space into camera-relative world axes and
     * applying the fixed-viewmodel-FOV lateral scale. Returns {@code null} when nothing was captured.
     *
     * <p>The transform is in-place: {@link RtEntityCapture#verts} becomes the camera-relative position
     * array (fed straight to the motion tracker and the geometry upload) and each triangle's stored
     * normal is rotated the same way, so the hit shader's normal is already in world axes for the
     * translation-only instance transform.
     */
    RtEntityCapture takePosed(float lateralScale, Matrix4f viewRotation) {
        if (!this.pending || viewRotation == null) {
            return null;
        }
        this.pending = false;
        pose(lateralScale, viewRotation);
        return this.capture;
    }

    private void pose(float lateralScale, Matrix4f viewRotation) {
        float s = lateralScale > 0.0f && Float.isFinite(lateralScale) ? lateralScale : 1.0f;
        // cameraState.viewRotationMatrix is view -> world (its columns are the camera's world-space axes,
        // matching RtComposite's camRight/camForward extraction), so it maps captured vertices and normals
        // straight into world axes. JOML names elements m<column><row>: world row 0 is
        // m00*x + m10*y + m20*z, which is why the m<col><row> getters are indexed that way below.
        float r00 = viewRotation.m00();
        float r10 = viewRotation.m10();
        float r20 = viewRotation.m20();
        float r01 = viewRotation.m01();
        float r11 = viewRotation.m11();
        float r21 = viewRotation.m21();
        float r02 = viewRotation.m02();
        float r12 = viewRotation.m12();
        float r22 = viewRotation.m22();

        float[] verts = this.capture.verts.elements();
        int vertexFloats = this.capture.verts.size();
        for (int i = 0; i < vertexFloats; i += 3) {
            float x = verts[i] * s;
            float y = verts[i + 1] * s;
            float z = verts[i + 2];
            verts[i] = r00 * x + r10 * y + r20 * z;
            verts[i + 1] = r01 * x + r11 * y + r21 * z;
            verts[i + 2] = r02 * x + r12 * y + r22 * z;
        }

        // Normals follow the same change of basis; the anisotropic lateral scale maps through the
        // inverse-transpose, i.e. (nx / s, ny / s, nz), renormalised.
        float[] prim = this.capture.prim.elements();
        int primFloats = this.capture.prim.size();
        for (int base = 0; base < primFloats; base += 12) {
            float nx = prim[base] / s;
            float ny = prim[base + 1] / s;
            float nz = prim[base + 2];
            float wx = r00 * nx + r10 * ny + r20 * nz;
            float wy = r01 * nx + r11 * ny + r21 * nz;
            float wz = r02 * nx + r12 * ny + r22 * nz;
            float len = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (len > 1.0e-8f) {
                wx /= len;
                wy /= len;
                wz /= len;
            }
            prim[base] = wx;
            prim[base + 1] = wy;
            prim[base + 2] = wz;
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
