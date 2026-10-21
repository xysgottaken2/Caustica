package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;

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
 * <p>The hand's <em>size</em> is left to the world's own projection, which is what an object held in the world
 * does: the traced hand scales with the level FOV exactly as the terrain behind it does, and reconciling that
 * with vanilla's separate 3d-HUD projection is the job of the existing {@code hand.fov-follows-camera} toggle
 * rather than a second one here. An earlier revision additionally dollied the capture's view depth by the
 * ratio of the two projections' tangents, and it is gone on purpose: at the default 70-degree FOV it was a
 * no-op, and away from the default it "fixed" apparent size by compressing one axis of the mesh — which
 * stretches parallax relief, moves the surface the fog and the focal plane measure, and makes the arm read as
 * a different object every time the player sprints. Keeping the instance a pure translation also keeps
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
 *       hole in the hand. {@code traceViewmodelOverlay} in {@code trace.slang} therefore re-traces <em>every
 *       primary ray</em> against viewmodel geometry only, and a hit there replaces the world hit however near
 *       the block in front of it was: ranking the two by distance would invert the rule, because it is exactly
 *       the close blocks (a wall you are facing, the floor you are looking down at) that would otherwise eat
 *       the hand. What makes that affordable is direction, not distance — {@link #frameOverlayCull()}
 *       publishes the cone enclosing every captured vertex direction ({@code worldPush.viewmodelCull}) and a
 *       ray outside it cannot touch the hand — while {@link #frameOverlayReach()} both bounds the trace's tmax
 *       and switches the pass off on frames without a hand ({@code worldPush.viewmodel}).
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

    /** Consecutive capture failures after which the feature stays off for the rest of the session. */
    private static final int FAILURE_LATCH_THRESHOLD = 3;

    /** Mirrors world_common.slang's ENTITY_GEOM_VIEWMODEL: the entity geometry-table entry's kind lane. */
    public static final int GEOM_FLAG_VIEWMODEL = 1;

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
    /**
     * Enclosing cone of this capture's vertex directions, in world space (and therefore independent of
     * rebasing, which translates rays' origins but not their directions): axis plus the cosine of the half
     * angle, {@code -1} when the capture declined to bound itself. Consumed by {@code traceViewmodelOverlay}
     * as the one cheap test allowed to skip a hand pixel — see {@link #frameOverlayCull()}.
     */
    private float cullX, cullY, cullZ;
    private float cullCos = -1.0f;
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
        overlayBounds(vertices);
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
     * The directional bound that goes with {@link #frameOverlayReach()}: {@code xyz} a unit axis, {@code w} the
     * cosine of the half angle of the cone enclosing every vertex direction of the instanced capture. Frames
     * that did not instance a capture publish {@code (0, 0, 0, -1)} — a zero-length axis with a permissive
     * cosine, so the worst a mismatch could ever do is trace for a hand that is not there, never cull one that
     * is.
     */
    public Float4 frameOverlayCull() {
        if (!instancedThisFrame) {
            return new Float4(0.0f, 0.0f, 0.0f, -1.0f);
        }
        return new Float4(cullX, cullY, cullZ, cullCos);
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
     * Publishes the two bounds {@code traceViewmodelOverlay} runs on: how far the capture reaches from the eye
     * (its tmax, and the "there is a hand this frame" flag) and the cone enclosing all of its vertex
     * directions (the test that lets the rest of the screen skip the trace entirely). Both are measured on the
     * mesh exactly as it is instanced, so they describe what the tracer will see rather than what some other
     * frame emitted.
     *
     * <p>The cone is conservative and only ever widens: the axis is the mean vertex direction and the half
     * angle the widest direction from it, so every vertex is inside by construction, and a triangle's directions
     * from the eye are positive combinations of its three vertices' directions, which a convex cone is closed
     * under — so whole triangles are inside too. Any degeneracy (no usable vertices, a pose wrapped around the
     * eye whose mean cancels, a non-finite result) leaves the cosine at {@code -1}, which the shader reads as
     * "no bound, always trace".
     */
    private void overlayBounds(int vertices) {
        float[] p = capture.verts.elements();
        cullCos = -1.0f;
        float max = 0.0f;
        float sx = 0.0f;
        float sy = 0.0f;
        float sz = 0.0f;
        for (int i = 0; i < vertices; i++) {
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            float d = (float) Math.sqrt(x * x + y * y + z * z);
            if (d > max) {
                max = d;
            }
            if (d > 1.0e-4f) {
                sx += x / d;
                sy += y / d;
                sz += z / d;
            }
        }
        overlayReach = max <= 0.0f ? 0.0f : max + REACH_EPSILON;
        float axisLength = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (axisLength < 0.5f) {
            return; // too few usable directions, or they cancel: bound nothing rather than bound wrongly
        }
        float inv = 1.0f / axisLength;
        cullX = sx * inv;
        cullY = sy * inv;
        cullZ = sz * inv;
        float minDot = 1.0f;
        for (int i = 0; i < vertices; i++) {
            float x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
            float d = (float) Math.sqrt(x * x + y * y + z * z);
            if (d <= 1.0e-4f) {
                continue;
            }
            float dot = (x * cullX + y * cullY + z * cullZ) / d;
            if (dot < minDot) {
                minDot = dot;
            }
        }
        if (!Float.isFinite(minDot)) {
            cullCos = -1.0f;
            return;
        }
        // Slack of about two degrees: the bound is tested against ray directions, and a silhouette pixel just
        // outside would clip the hand's own edge.
        cullCos = Math.clamp(minDot * 0.999f - 0.03f, -1.0f, 1.0f);
    }

    /** Diagnostic: whether the capture failure latch tripped (feature off until the game restarts). */
    public static boolean failureLatched() {
        return INSTANCE.failureLatched;
    }
}
