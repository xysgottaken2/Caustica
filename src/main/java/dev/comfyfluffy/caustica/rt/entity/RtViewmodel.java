package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

/**
 * The first-person viewmodel — the arm plus whatever is held in it — as real ray-traced geometry.
 *
 * <h2>Why this exists at all</h2>
 * Vanilla draws the hand in a screen-space raster pass ({@code GameRenderer.renderItemInHand}) that runs
 * after the world, after a depth clear, through its own 3d-HUD projection. Caustica keeps that pass (it
 * redirects it into the UI overlay), so under the path tracer the hand is a flat, un-lit decal: no
 * parallax occlusion on a held block, no sun highlight off a held lantern, no reflection of a torch in a
 * wet floor. This class is the opt-in replacement: the geometry moves into the traced scene and the vanilla
 * raster submission is suppressed.
 *
 * <h2>What is reused verbatim</h2>
 * Nothing about the viewmodel is re-implemented. {@link #capture} replays the game's own
 * {@code FirstPersonHandsAndItemsRenderer.submitHandsWithItems} into {@link RtEntityCollector}, the same
 * collector the world entities use. Every pose decision — {@code HandRenderSelection} main/off hand, the
 * swing, the item swap slide, the eating jiggle, the crossbow draw, hurt tilt, view bobbing, scoping
 * suppression, item tints and the enchantment glint — is computed by vanilla inside that call, so the
 * captured mesh is the mesh the game would have rastered. Material resolution comes from the same sprite and
 * atlas lookups the entity pass uses, which is what gives a held block real POM relief and a held glowstone
 * real emission instead of a flat decal.
 *
 * <h2>Space: welded to the camera, for free</h2>
 * {@code renderItemInHand} composes its pose as {@code V⁻¹ · bob · model} (V = the camera's
 * {@code viewRotationMatrix}) while the raster pass applies {@code V} again through the model-view stack.
 * The two cancel — which is exactly why the hand does not swing off screen when the player turns — so the
 * vertices arriving here are already <em>camera-relative world offsets around the eye</em>, in the same
 * space the tracer works in. The TLAS instance is therefore a pure translation by
 * {@code camera − rebaseOrigin}, and no rotation is baked into geometry that the hit shaders also rotate.
 *
 * <p>The one remaining difference from vanilla is the projection: the hand is rastered through a fixed
 * 70-degree 3d-HUD projection (modulated by {@code hand.fov-follows-camera}) while the traced world uses the
 * level projection. Two projections that differ only in vertical scale agree on the image of a point iff its
 * view depth is scaled by the ratio of their tangents — a dolly, i.e. the same trade between apparent size
 * and distance a viewmodel-FOV slider makes. {@link #applyHudDolly} bakes that into the captured positions
 * (and, with the inverse-transpose, the captured normals) rather than into the instance transform: under a
 * non-uniform scale the correct normal transform is not the position transform, and the hit shaders apply
 * the position one. Keeping the instance a translation dodges the question entirely and keeps
 * {@code entityO2w} the no-op it is for every other per-frame-built entity.
 *
 * <h2>The three rules that make it a viewmodel instead of a second player</h2>
 * <ul>
 *   <li><b>The camera sees it; nothing else does.</b> The instance carries its own TLAS mask bit, which no
 *       ordinary ray asks for — so it casts no shadow, does not appear in reflections or GI, and never
 *       occludes the world's own lighting. Shadows and reflections of "you" keep coming from the
 *       first-person body instance, exactly as today.
 *   <li><b>Nothing shades it into blackness.</b> Shadow rays fired from a viewmodel surface run under
 *       {@code CULL_VIEWMODEL_SHADOW}, which keeps terrain and entities as occluders but drops the
 *       first-person body, particles and the hand's own geometry. Without it, the very arm the camera sits
 *       inside would throw a hard shadow across the item it holds.
 *   <li><b>It layers above the blocks it clips into.</b> The raster pass gets away with a depth clear; a
 *       closest-hit shader has no such thing, so a block the arm swings through would win the hit and punch a
 *       hole in the hand. {@code traceViewmodelOverlay} in {@code trace.slang} re-traces a primary ray that
 *       stopped nearer than the viewmodel's own reach against viewmodel geometry only, and a hit there
 *       replaces the world hit. {@link #frameOverlayReach()} publishes that bound through
 *       {@code worldPush.viewmodel.x}, so the extra trace costs nothing on the pixels where nothing is that
 *       close, and is skipped entirely on frames without a hand.
 * </ul>
 *
 * <h2>Failure policy</h2>
 * The capture runs outside the RT composite's protective try/catch, and a viewmodel is a cosmetic overlay,
 * so it fails soft where the renderer cannot: a throw drops that frame's capture, a few in a row latch the
 * feature off with one logged line, and every fallback is vanilla's hand pass — the player never loses their
 * hands. That is deliberately different from the entity capture, which escalates to {@link RtComposite} and
 * reverts the whole renderer. The same conservatism explains {@link #capture}'s refusal to take over a frame
 * whose submission included something the collector cannot reproduce.
 */
public final class RtViewmodel {
    public static final RtViewmodel INSTANCE = new RtViewmodel();

    /**
     * Per-vertex budget for one frame's viewmodel mesh. The vanilla arm plus a held block is a few hundred
     * quads; the cap only ever fires for an extreme custom viewmodel pack, and going past it means the
     * per-frame mesh upload and BLAS build would cost more than the overlay is worth. Discarding leaves
     * vanilla's raster hand in place, which is the same failure mode as the feature being off.
     */
    private static final int MAX_VERTICES = 1 << 16;

    /** Slack on the published reach so a hit at the hand's own surface depth never ties past it. */
    private static final float REACH_EPSILON = 0.02f;

    /**
     * Bounds on the HUD dolly. A positive scale can never move a vertex across the eye plane (it shrinks or
     * grows |view depth| without changing its sign), so this is not a clipping guard but a taste guard: below
     * 0.35 the arm is uncomfortably close to the face and above 2.5 the compensation has warped it into
     * something that no longer reads as a hand. Outside the bound the compensation is dropped and the
     * geometry keeps vanilla's world scale.
     */
    private static final float MIN_DOLLY = 0.35f;
    private static final float MAX_DOLLY = 2.5f;

    /** Below this the dolly is a no-op and the capture is left exactly as vanilla emitted it. */
    private static final float DOLLY_EPSILON = 1.0e-4f;

    /** Consecutive capture failures after which the feature stays off for the rest of the session. */
    private static final int FAILURE_LATCH_THRESHOLD = 3;

    /** Mirrors world_common.slang's ENTITY_GEOM_VIEWMODEL: the entity geometry-table entry's kind lane. */
    public static final int GEOM_FLAG_VIEWMODEL = 1;

    /** Vanilla's fixed viewmodel FOV, per {@code Camera.calculateHudFov()} — see GameRendererMixin. */
    private static final float VANILLA_HAND_FOV = 70.0f;

    private final RtEntityCapture capture = new RtEntityCapture();
    private final RtEntityCollector collector = new RtEntityCollector();
    /** Last frame's placed vertex positions, kept because the capture's own buffer is reused in place. */
    private float[] previousPlaced = new float[0];
    private int previousCount = -1;
    private double previousCamX, previousCamY, previousCamZ;
    private int previousRbx, previousRby, previousRbz;

    private boolean pending;
    private boolean capturing;
    private boolean suppressVanillaSubmission;
    /**
     * Set only by {@link RtEntities} while it instances the capture, and cleared every render frame. This,
     * not a frame counter, is what authorises cancelling vanilla's hand: a stale counter could survive a
     * frame where the composite never ran at all, and that would cancel the hand without replacing it.
     */
    private boolean instancedThisFrame;
    private boolean failureLatched;
    private boolean loggedFailure;
    private int consecutiveFailures;
    private float overlayReach;
    private double camX, camY, camZ;

    private RtViewmodel() {
    }

    /**
     * Whether the viewmodel should be captured this frame. Requires the entity frame build (the mesh rides
     * its geometry table, instance list and buffer lifetimes) and an active RT composite, and never comes
     * back after a capture failure within the session.
     */
    public static boolean enabled() {
        return !INSTANCE.failureLatched
                && CausticaConfig.Rt.Hand.PATH_TRACED.value()
                && RtEntities.enabled()
                && RtComposite.enabled();
    }

    /** Clear every per-frame latch. Called from {@code GameRenderer.render} HEAD, before anything renders. */
    public void beginFrame() {
        pending = false;
        suppressVanillaSubmission = false;
        instancedThisFrame = false;
    }

    /**
     * Replay vanilla's own viewmodel submission into the ray-traced capture. Called from the before-hand
     * seam, immediately <em>before</em> the RT composite — the last point in the frame where a new instance
     * can still reach the TLAS build that composite is about to record.
     *
     * @param poseStack the hand pose stack the caller built the way {@code renderItemInHand} builds its own
     * @param renderer  vanilla's {@code FirstPersonHandsAndItemsRenderer}, so the submission is the game's
     */
    public void capture(CameraRenderState cameraState, PlayerRenderState playerState, PoseStack poseStack,
                        FirstPersonHandsAndItemsRenderer renderer) {
        if (!enabled() || capturing || playerState.firstPersonHandsAndItems == null) {
            return;
        }
        long started = RtFrameStats.FRAME.startStage();
        capture.reset();
        collector.begin(capture, false);
        capturing = true;
        try {
            renderer.submitHandsWithItems(cameraState.cameraEntityPartialTicks, poseStack, collector,
                    playerState, playerState.firstPersonHandsAndItems);
        } catch (Throwable t) {
            // Fail soft and count it. A single throw is usually a race — the material snapshot a held block
            // resolves against is torn down and rebuilt across a resource reload, and the capture runs
            // outside the composite's protective try/catch because it happens before that frame is built.
            // Three in a row is not a race, so the feature then stays off for the session rather than paying
            // a failed capture every frame.
            capture.reset();
            pending = false;
            if (++consecutiveFailures < FAILURE_LATCH_THRESHOLD) {
                return;
            }
            failureLatched = true;
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("Ray-traced viewmodel capture failed {} frames in a row; disabling "
                                + "caustica.rt.viewmodelRayTracing for this session and leaving the hand to "
                                + "vanilla's render pass",
                        consecutiveFailures, t);
            }
            return;
        } finally {
            capturing = false;
            collector.begin(null, false);
            RtFrameStats.FRAME.endStage("viewmodel.capture", started);
        }
        int vertices = capture.verts.size() / 3;
        // A submission the collector cannot reproduce (a drawn map's contents, a mod's custom viewmodel phase)
        // means the mesh is only part of what vanilla would have shown. Rather than a hand missing its map,
        // leave the whole pass to vanilla: the same behaviour as the toggle being off, decided per frame, so
        // lowering and raising a map needs no config change.
        int discarded = collector.discardedSubmissions();
        if (capture.isEmpty() || vertices > MAX_VERTICES || discarded > 0) {
            pending = false;
            if (discarded > 0) {
                RtFrameStats.FRAME.count("viewmodelSubmissionsUnsupported", discarded);
            }
            return;
        }
        // The camera's world position is the instance's translation. CameraRenderState.pos is what every
        // other capture in this package measures against (and what vanilla offsets the hand from), so
        // placement and render state can never disagree.
        if (cameraState.pos == null || !Double.isFinite(cameraState.pos.x)
                || !Double.isFinite(cameraState.pos.y) || !Double.isFinite(cameraState.pos.z)) {
            pending = false;
            return;
        }
        camX = cameraState.pos.x;
        camY = cameraState.pos.y;
        camZ = cameraState.pos.z;
        applyHudDolly(cameraState.viewRotationMatrix, hudDepthScale(cameraState));
        overlayReach = overlayReach(vertices);
        pending = true;
        consecutiveFailures = 0;
        // Only the very next submission is ours to suppress — the one renderItemInHand is about to make.
        // Any other caller of the same renderer (a future HUD preview, another mod) keeps its hand.
        suppressVanillaSubmission = true;
        RtFrameStats.FRAME.count("viewmodelVertices", vertices);
    }

    /**
     * Whether the vanilla raster submission must be skipped. True only for the single submission that
     * follows our capture in the same frame, and only once the RT frame has really instanced the mesh — if
     * the composite bailed (terrain not resident, a material-epoch boundary, a pipeline rebuild), the hand
     * stays vanilla's and nothing flickers.
     */
    public boolean shouldSuppressVanillaSubmission() {
        if (!suppressVanillaSubmission || !instancedThisFrame) {
            return false;
        }
        suppressVanillaSubmission = false;
        return true;
    }

    /** A pending capture is consumed by exactly one entity frame build. */
    public boolean hasPendingCapture() {
        return pending;
    }

    /** Discard the pending capture without tracing it (the geometry table has no room left). */
    public void discardPendingCapture() {
        pending = false;
    }

    RtEntityCapture pendingCapture() {
        return capture;
    }

    /** Called by {@link RtEntities} once the instance is in this frame's build. */
    void markInstanced() {
        instancedThisFrame = true;
        pending = false;
    }

    /**
     * Pure translation placing the capture at the eye, in rebased terrain space. Identity linear part by
     * design: the capture is already camera-relative world, so any rotation here would double-apply the
     * camera (and make the hit shaders' {@code entityO2w} normal rotation stop being the no-op it assumes
     * for per-frame-built entity geometry).
     */
    float[] instanceTransform(int rbx, int rby, int rbz) {
        return new float[] {
                1, 0, 0, (float) (camX - rbx),
                0, 1, 0, (float) (camY - rby),
                0, 0, 1, (float) (camZ - rbz)};
    }

    /**
     * The reach this RT frame published to the shaders, or 0 when the frame did not instance the capture.
     * {@code worldPush.viewmodel.x} consumes this: bounding the overlay trace by the mesh that is really in
     * this frame's TLAS means a frame that bailed before the capture could be instanced costs nothing.
     */
    public float frameOverlayReach() {
        return instancedThisFrame ? overlayReach : 0.0f;
    }

    /**
     * Per-vertex motion vectors for the captured mesh, in one atomic step: the world displacement of each
     * vertex since the previous frame, in the <em>current</em> frame's rebased space, then the history is
     * rotated so the next frame can diff against it. A viewmodel is camera-locked, so its screen position
     * barely moves while the world behind it sweeps past; the denoiser and the upscaler can only reuse a hand
     * pixel once the vectors say "this surface travelled with the eye", which is what these say.
     *
     * @return four floats per vertex (xyz displacement, w unused), or null when the vertex count changed
     *         (an item swap has no predecessor to reproject from, so shading must re-estimate instead of
     *         dragging a previous item's colour across the swap)
     */
    float[] motionDisplacements(int rbx, int rby, int rbz) {
        int vertices = capture.verts.size() / 3;
        float[] cur = capture.verts.elements();
        float[] disp = null;
        if (previousCount == vertices && previousPlaced.length >= vertices * 3) {
            // Same convention as the entity uploader: (cur - prev) + shift, where the shift re-expresses
            // last frame's anchor in this frame's rebased origin (skipping it would paint a whole-world-sized
            // vector the moment terrain re-bases).
            float sx = (float) (camX - previousCamX) + (previousRbx - rbx);
            float sy = (float) (camY - previousCamY) + (previousRby - rby);
            float sz = (float) (camZ - previousCamZ) + (previousRbz - rbz);
            disp = new float[vertices * 4];
            for (int i = 0; i < vertices; i++) {
                disp[i * 4] = cur[i * 3] - (previousPlaced[i * 3] + sx);
                disp[i * 4 + 1] = cur[i * 3 + 1] - (previousPlaced[i * 3 + 1] + sy);
                disp[i * 4 + 2] = cur[i * 3 + 2] - (previousPlaced[i * 3 + 2] + sz);
            }
        }
        // The capture's buffer is reused in place next frame, so the history needs a real copy.
        if (previousPlaced.length < vertices * 3) {
            previousPlaced = new float[vertices * 3];
        }
        System.arraycopy(cur, 0, previousPlaced, 0, vertices * 3);
        previousCount = vertices;
        previousCamX = camX;
        previousCamY = camY;
        previousCamZ = camZ;
        previousRbx = rbx;
        previousRby = rby;
        previousRbz = rbz;
        return disp;
    }

    /**
     * The largest distance from the eye any vertex of this capture reaches, plus slack. Computed after
     * {@link #applyHudDolly} so the bound describes what the tracer will actually see: the dollied mesh is
     * nearer or further depending on where the camera is looking, and a reach measured on the raw capture
     * would let the overlay pass miss the hand it exists to find.
     */
    private float overlayReach(int vertices) {
        float[] p = capture.verts.elements();
        float max = 0.0f;
        for (int i = 0; i < vertices; i++) {
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            float d = (float) Math.sqrt(x * x + y * y + z * z);
            if (d > max) {
                max = d;
            }
        }
        return max <= 0.0f ? 0.0f : max + REACH_EPSILON;
    }

    /**
     * Scale the capture along the camera's forward axis by {@code dolly}, in place: positions by
     * {@code V⁻¹ · diag(1,1,dolly) · V} and normals by its inverse-transpose, which for the orthonormal
     * rotation V is the same conjugation with {@code 1/dolly}. Doing this to the mesh instead of to the
     * instance transform is what keeps the instance a translation, keeps the shading frames (TBN from
     * positions and UVs, the captured prim normal) mutually consistent under a non-uniform scale, and costs
     * nothing at all when {@code dolly == 1} — the case whenever the FOV match is off or the level and HUD
     * projections already agree.
     */
    private void applyHudDolly(Matrix4f viewRotation, float dolly) {
        if (Float.compare(dolly, 1.0f) == 0) {
            return;
        }
        // Rows of V are the camera's basis in world space; V is a pure rotation, so V⁻¹ is its transpose.
        float r00 = viewRotation.m00(), r01 = viewRotation.m01(), r02 = viewRotation.m02();
        float r10 = viewRotation.m10(), r11 = viewRotation.m11(), r12 = viewRotation.m12();
        float r20 = viewRotation.m20(), r21 = viewRotation.m21(), r22 = viewRotation.m22();
        float inv = 1.0f / dolly;
        FloatArrayList verts = capture.verts;
        float[] v = verts.elements();
        int vertices = verts.size() / 3;
        for (int i = 0; i < vertices; i++) {
            float x = v[i * 3], y = v[i * 3 + 1], z = v[i * 3 + 2];
            // view = V · p ; view.z *= dolly ; p' = Vᵀ · view
            float vx = r00 * x + r01 * y + r02 * z;
            float vy = r10 * x + r11 * y + r12 * z;
            float vz = (r20 * x + r21 * y + r22 * z) * dolly;
            v[i * 3] = r00 * vx + r10 * vy + r20 * vz;
            v[i * 3 + 1] = r01 * vx + r11 * vy + r21 * vz;
            v[i * 3 + 2] = r02 * vx + r12 * vy + r22 * vz;
        }
        // Prim normals live in the same space as the positions (lane 0..2 of each 12-float record; lane 3 is
        // a packed float the dolly must not touch).
        FloatArrayList prim = capture.prim;
        float[] p = prim.elements();
        int prims = prim.size() / 12;
        for (int i = 0; i < prims; i++) {
            int base = i * 12;
            float x = p[base], y = p[base + 1], z = p[base + 2];
            float nx = r00 * x + r01 * y + r02 * z;
            float ny = r10 * x + r11 * y + r12 * z;
            float nz = (r20 * x + r21 * y + r22 * z) * inv;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length <= 1.0e-6f) {
                continue; // a degenerate captured normal stays degenerate rather than becoming NaN
            }
            float scale = 1.0f / length;
            p[base] = (r00 * nx + r10 * ny + r20 * nz) * scale;
            p[base + 1] = (r01 * nx + r11 * ny + r21 * nz) * scale;
            p[base + 2] = (r02 * nx + r12 * ny + r22 * nz) * scale;
        }
    }

    /**
     * The view-depth scale that keeps the hand the size vanilla rasterizes it:
     * {@code m11_level / m11_hud}, both read as the projection scales they really are
     * ({@code m11 == 1/tan(fovY/2)}, aspect-independent). A wider level projection than the 3d-HUD one
     * shrinks the scale below 1, dollying the arm closer until the projected image matches again.
     */
    private static float hudDepthScale(CameraRenderState cameraState) {
        if (!CausticaConfig.Rt.Hand.PATH_TRACED_MATCHES_HUD_FOV.value()) {
            return 1.0f;
        }
        float hudFov = cameraState.hudFov;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || hudFov <= 0.0f) {
            return 1.0f;
        }
        // The same rescale GameRendererMixin applies to the 3d-HUD projection itself, so the two agree by
        // construction: with that toggle off the hand keeps vanilla's fixed FOV, with it on it follows the
        // slider. The level side is read off the matrix the rays are actually projected by, so the sprint and
        // zoom multiplier, the spyglass and the death/fluid factor need no separate accounting here — whatever
        // they do to the world, the ratio already undoes for the hand.
        if (CausticaConfig.Rt.Hand.FOV_FOLLOWS_CAMERA.value()) {
            Integer sliderFov = mc.options.fov().get();
            if (sliderFov != null && sliderFov > 0) {
                hudFov = Math.clamp(hudFov * (sliderFov / VANILLA_HAND_FOV), 1.0f, 179.0f);
            }
        }
        float levelScale = cameraState.projectionMatrix.m11();
        if (!Float.isFinite(levelScale) || levelScale <= 1.0e-4f) {
            return 1.0f;
        }
        float hudScale = 1.0f / (float) Math.tan(Mth.DEG_TO_RAD * hudFov * 0.5f);
        float ratio = levelScale / hudScale;
        if (!Float.isFinite(ratio) || ratio < MIN_DOLLY || ratio > MAX_DOLLY) {
            return 1.0f;
        }
        return Math.abs(ratio - 1.0f) < DOLLY_EPSILON ? 1.0f : ratio;
    }

    /** Diagnostic: whether the capture failure latch tripped (feature off until the game restarts). */
    public static boolean failureLatched() {
        return INSTANCE.failureLatched;
    }
}
