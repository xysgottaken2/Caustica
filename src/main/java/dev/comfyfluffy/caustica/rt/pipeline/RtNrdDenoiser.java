package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.nrd.NrdLibrary;
import dev.comfyfluffy.caustica.nrd.NrdRuntime;
import dev.comfyfluffy.caustica.rt.accel.RtImage;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * NRD REBLUR_DIFFUSE_SPECULAR denoiser backend — the optional strong denoiser for the non-DLSS
 * paths, selectable from Video Settings. Consumes the tracer's demodulated per-lobe signals
 * ({@code gNrdDiff} / {@code gNrdSpec}, YCoCg radiance + REBLUR-normalized hit distance) plus the
 * shared guides at render resolution, and writes denoised per-lobe outputs that
 * {@link RtNrdCombinePipeline} re-modulates and sums.
 *
 * <h2>Why this is a full rewrite</h2>
 * The previous integration was disabled because REBLUR "trembled", ghosted and smeared under camera
 * motion. Those were not tuning problems; they were four contract violations, each fixed here and
 * each verified against the NRD v4.17.3 sources:
 *
 * <ol>
 *   <li><b>Rebased world space.</b> The renderer rebases its terrain origin as the player moves, and
 *       the old code passed {@code worldToView} built against the CURRENT anchor while NRD still
 *       held the previous frame's matrix built against the PREVIOUS anchor. NRD derives camera
 *       motion from the difference of the two translations
 *       ({@code InstanceImpl::SetCommonSettings} → {@code translationDelta}), so every rebase looked
 *       like a teleport of up to a chunk: history was misreprojected exactly during movement. Both
 *       matrices are now expressed against the SAME anchor — the previous frame's camera position is
 *       tracked in absolute world coordinates and re-expressed in the current anchor each frame,
 *       which is precisely what NRD's "if the coordinate system moves with the camera, camera delta
 *       must be included" requirement asks for.</li>
 *   <li><b>Double handedness conversion.</b> The old code negated the projection's Y row to "undo"
 *       Vulkan's NDC flip. NRD calls {@code DecomposeProjection(STYLE_D3D, ...)} and performs its
 *       own RH→LH conversion, so the hand-flip made it convert twice and mirror reconstructed
 *       positions about the camera. The projection is now handed over unmodified apart from the
 *       depth-row sanitization below.</li>
 *   <li><b>Degenerate depth row.</b> Minecraft's float-Z reverse-Z projection carries an effective
 *       near plane around 4.8e-6, and NRD derives every world-space scale from that row (frustum
 *       size → disocclusion thresholds, hit-distance factors, blur radii). All of them came out
 *       ~40x too tight, so history survived only near the screen centre — the reported "circle".
 *       A clean reverse-Z infinite row with vanilla's real near plane restores NRD's design point;
 *       REBLUR never reads a depth buffer (only {@code IN_VIEWZ}), so nothing else is affected.</li>
 *   <li><b>Un-demodulated radiance.</b> NRD documents that materials must be demodulated before
 *       denoising ("NRD works with pure radiance"), and the tracer previously fed it radiance with
 *       albedo already baked in. Denoising across a texture edge then blurs albedo, which reads as
 *       exactly the smearing that was blamed on the temporal filter. The tracer now divides by the
 *       material factors and the combine pass multiplies them back afterwards.</li>
 * </ol>
 *
 * <p>On top of that the per-frame reset logic no longer resets on "the camera moved a lot": the only
 * true discontinuities are an anchor rebase (which is now compensated instead), a teleport, a
 * resolution change and a projection change.
 */
public final class RtNrdDenoiser {
    public static final RtNrdDenoiser INSTANCE = new RtNrdDenoiser();

    /**
     * Whether REBLUR should run. Always false: the integration is retired.
     *
     * <p>REBLUR keeps its temporal history inside the library, so the reprojection fixes that made
     * the built-in SVGF path stable could not reach it, and it stayed blobby and noisy in exactly
     * the conditions SVGF now handles. The toggle is gone from the options screen and this gate
     * keeps a hand-edited config from bringing it back.
     *
     * <p>The code and the CMake shim stay in the tree because CI builds them, and because the
     * per-lobe demodulated signals it consumes are still produced for DLSS-RR.
     */
    public static boolean enabled() {
        return false;
    }

    /**
     * Whether NRD is switched on AND has not fallen over. Once a denoise throws, the integration
     * latches off for the rest of the session; the renderer must then hand the slot back to SVGF
     * rather than presenting the raw trace, which is what "the NRD button does nothing" looks like
     * from the player's side — the toggle appeared inert because both states ended up undenoised.
     */
    public static boolean active() {
        return enabled() && !INSTANCE.failed && !NrdRuntime.INSTANCE.hasFailed();
    }

    // ---- REBLUR tuning ---------------------------------------------------------------------
    //
    // nrd::HitDistanceReconstructionMode::AREA_5X5. The tracer picks ONE lobe per pixel at 1 spp
    // (specular with probability clamped to [0.1, 0.9]), so the other lobe reports hit distance 0 =
    // "no data" every frame. REBLUR must reconstruct it from neighbours or its history rejection
    // misfires. AREA_5X5 is the mode whose required probability range [1/16, 15/16] contains the
    // tracer's [0.1, 0.9] clamp; AREA_3X3 would need [1/4, 3/4] plus Bayer dithering.
    private static final int HIT_DIST_RECONSTRUCTION_AREA_5X5 = 2;
    /**
     * Main history length. NRD recommends deriving this from an accumulation TIME rather than a
     * fixed frame count so the denoiser behaves the same at 30 and at 120 fps
     * ({@code GetMaxAccumulatedFrameNum(time, fps)}); {@link #maxAccumulatedFrames()} does that with
     * a measured frame rate, and this is the target time. REBLUR's own default is 0.5 s; 0.75 s
     * gives 1-spp Minecraft interiors a longer window to converge, which is where the residual
     * noise complaint came from.
     */
    private static final float ACCUMULATION_TIME_SECONDS = 0.75f;
    /** REBLUR's hard cap ({@code REBLUR_MAX_HISTORY_FRAME_NUM}); values above it are rejected. */
    private static final int MAX_HISTORY_FRAMES = 63;
    /**
     * Fast history, used to clamp the slow one. NRD recommends 5-7x shorter than the main history:
     * it is what lets the denoiser react to real lighting changes without dragging the long
     * accumulation behind it — the mechanism that keeps a long window from ghosting.
     */
    private static final int FAST_HISTORY_DIVISOR = 6;
    /**
     * Frames of post-accumulation temporal stabilization. This is REBLUR's anti-flicker stage and
     * the reason its output looks steadier than a plain à-trous filter; the default is the maximum,
     * which is what the "should look like DLSS" target wants.
     */
    private static final int MAX_STABILIZED_FRAMES = MAX_HISTORY_FRAMES;
    /** History reconstruction after a reset/disocclusion — a few frames of aggressive spatial fill. */
    private static final int HISTORY_FIX_FRAMES = 4;
    /**
     * Firefly suppression: [1; 3], smaller suppresses harder at the cost of some bias. A 1-spp path
     * tracer with emissive blocks produces plenty of outliers, so this sits below the 2.0 default.
     */
    private static final float FIREFLY_SUPPRESSOR_SCALE = 1.75f;
    /**
     * Pre-pass blur radii, in PIXELS, and why they are scaled by resolution.
     *
     * NRD requires a non-zero pre-pass "in case of badly defined signals and probabilistic
     * sampling", which describes this tracer, so the pass stays enabled. But its defaults (30
     * diffuse / 50 specular) are quoted for a full-resolution render: at the upscaled render size
     * this mod actually traces at -- 640x337 for a 1920x1009 window -- a 50-pixel specular radius
     * is 7.8% of the frame width. That is what made reflections look like flat, simplified colour
     * (energy smeared across a large fraction of the image before any accumulation) and what turned
     * a single firefly into a large, stretched blob: the pre-pass is anisotropic along the specular
     * lobe, so on a slanted surface the disc becomes an ellipse.
     *
     * Scaling to a reference width keeps NRD's intent (the radius covers the same ANGULAR extent)
     * without letting it eat the frame at low render resolutions.
     */
    private static final float PREPASS_REFERENCE_WIDTH = 1920.0f;
    private static final float DIFFUSE_PREPASS_BLUR_RADIUS = 30.0f;
    private static final float SPECULAR_PREPASS_BLUR_RADIUS = 50.0f;
    /** Never shrink the pre-pass to nothing: NRD warns it is required for probabilistic input. */
    private static final float MIN_PREPASS_BLUR_RADIUS = 6.0f;
    /** Spatial filter radii, in pixels: NRD defaults (converged pixels shrink toward the minimum). */
    private static final float MIN_BLUR_RADIUS = 1.0f;
    private static final float MAX_BLUR_RADIUS = 30.0f;
    /** Normal / roughness / plane-distance rejection fractions — NRD defaults. */
    private static final float LOBE_ANGLE_FRACTION = 0.15f;
    private static final float ROUGHNESS_FRACTION = 0.15f;
    private static final float PLANE_DISTANCE_SENSITIVITY = 0.02f;
    /**
     * Colour-box sigma used to clamp the slow history against the fast one. NRD notes 1.5 "works
     * well even for dirty signals" while 2.0 is the legacy default; the tighter value is the
     * anti-ghosting knob that actually applies to a noisy 1-spp signal.
     */
    private static final float FAST_HISTORY_CLAMPING_SIGMA = 1.5f;

    /**
     * {@code CommonSettings::denoisingRange}. Pixels with a larger viewZ are treated as sky/INF; the
     * tracer writes 1e6 there, so the cutoff sits below that. Mirrors
     * {@code RtComposite.NRD_DENOISING_RANGE} and the combine shader's push constant.
     */
    public static final float DENOISING_RANGE = 500000.0f;
    /**
     * {@code CommonSettings::disocclusionThreshold} — relative depth difference above which two
     * samples are considered disoccluded, in NRD's documented [0.01; 0.02] range. Minecraft's
     * geometry is full of axis-aligned steps at grazing angles, where the strictest value rejects
     * valid history on every block edge, so this sits at the permissive end of the range.
     */
    private static final float DISOCCLUSION_THRESHOLD = 0.02f;
    /**
     * Near plane written into the sanitized depth row (see the class docs). Vanilla's real near
     * plane; anything larger would make NRD's derived world scales too coarse.
     */
    private static final float NRD_PROJECTION_NEAR = 0.05f;
    /** Shim status for "a parameter was rejected"; see native/nrd_shim/nrd_shim.cpp. */
    private static final int NRDSHIM_ERR_INVALID_ARGUMENT = -2;
    /**
     * Camera jump (in blocks, squared below) beyond which history cannot be reprojected at all: a
     * teleport, respawn or dimension change. Ordinary movement — including a terrain rebase, which
     * is compensated rather than reset — must never reach this.
     */
    private static final float TELEPORT_JUMP_BLOCKS = 64.0f;
    /** Frame-rate estimate bounds for the accumulation-time conversion. */
    private static final float MIN_FPS = 10.0f;
    private static final float MAX_FPS = 300.0f;

    private boolean failed;
    private boolean loggedFirstDispatch;
    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean hasPrev;
    private int appliedAccumulatedFrames = -1;
    private final float[] prevViewToClip = new float[16];
    /**
     * Previous frame's rotation-only view matrix. The full {@code worldToViewPrev} NRD receives is
     * rebuilt from this plus the previous camera position against the CURRENT anchor every frame —
     * storing the assembled matrix instead would freeze the anchor it was built with, which is
     * exactly the rebase bug this integration exists to fix.
     */
    private final Matrix4f prevViewRotation = new Matrix4f();
    /**
     * Previous frame's camera position in ABSOLUTE world coordinates (not the rebased terrain
     * space). Keeping it absolute is what lets a terrain rebase be compensated: the previous camera
     * is simply re-expressed in whatever anchor the current frame uses.
     */
    private double prevCamWorldX;
    private double prevCamWorldY;
    private double prevCamWorldZ;
    private float prevJitterX;
    private float prevJitterY;
    private int lastWidth;
    private int lastHeight;
    private long lastFrameNanos;
    private float smoothedFps = 60.0f;

    private RtNrdDenoiser() {
    }

    /**
     * Record one REBLUR denoise into {@code cmd}.
     *
     * @param viewToClip   the frame's projection, exactly as the renderer built it (jitter is applied
     *                     to the ray, not the matrix, so this is already non-jittered)
     * @param viewRotation the rotation-only view matrix
     * @param camWorldX/Y/Z the camera position in ABSOLUTE world coordinates
     * @param anchorX/Y/Z  the rebased terrain anchor the signals live in; the world-to-view matrix is
     *                     built as {@code rotation * translate(-(cam - anchor))} and the previous
     *                     frame's camera is re-expressed against this same anchor
     * @param jitterPixelsX/Y this frame's sub-pixel jitter in PIXELS (NRD validates [-0.5, 0.5])
     * @param projectionChanged FOV/projection jumps (sprint, fly), which warp the reprojection space
     * @return false when the runtime is unavailable or the call failed; the caller then falls back
     *         to the renderer's own denoiser
     */
    public boolean denoise(long cmd, int renderWidth, int renderHeight,
                           RtImage motion, RtImage normalRoughness, RtImage viewZ,
                           RtImage diffIn, RtImage specIn, RtImage diffOut, RtImage specOut,
                           RtImage validation,
                           Matrix4fc viewToClip, Matrix4fc viewRotation,
                           double camWorldX, double camWorldY, double camWorldZ,
                           double anchorX, double anchorY, double anchorZ,
                           float jitterPixelsX, float jitterPixelsY, int frameIndex,
                           boolean projectionChanged) {
        if (failed || !enabled()) {
            return false;
        }
        boolean validationOn = validation != null && CausticaConfig.Rt.Nrd.VALIDATION.value();
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        NrdLibrary lib = NrdRuntime.INSTANCE.acquire(device, renderWidth, renderHeight);
        if (lib == null) {
            return false;
        }
        if (!loggedFirstDispatch) {
            loggedFirstDispatch = true;
            // One line, once per session. "The NRD toggle does nothing" is indistinguishable from
            // "NRD ran and its output looked the same", and the two need completely different
            // fixes, so record that the integration really is dispatching.
            CausticaMod.LOGGER.info("NRD/REBLUR active: {}x{}, shim version {}",
                    renderWidth, renderHeight, lib.version());
        }
        try {
            // History length is derived from a target accumulation TIME, so the denoiser converges
            // over the same wall-clock window regardless of frame rate (NRD's own recommendation).
            // Re-sent only when the derived count actually changes, since it is a full settings push.
            int accumulatedFrames = maxAccumulatedFrames();
            if (accumulatedFrames != appliedAccumulatedFrames) {
                int rc = lib.setReblurSettings(
                        HIT_DIST_RECONSTRUCTION_AREA_5X5,
                        accumulatedFrames,
                        Math.max(2, accumulatedFrames / FAST_HISTORY_DIVISOR),
                        MAX_STABILIZED_FRAMES,
                        HISTORY_FIX_FRAMES,
                        0.0f, 0.0f, // anti-lag: NRD defaults (2.0 / 3.0) are the best balance here
                        FIREFLY_SUPPRESSOR_SCALE,
                        prepassRadius(DIFFUSE_PREPASS_BLUR_RADIUS, renderWidth),
                        prepassRadius(SPECULAR_PREPASS_BLUR_RADIUS, renderWidth),
                        MIN_BLUR_RADIUS, MAX_BLUR_RADIUS,
                        LOBE_ANGLE_FRACTION, ROUGHNESS_FRACTION, PLANE_DISTANCE_SENSITIVITY,
                        FAST_HISTORY_CLAMPING_SIGMA,
                        true);
                if (rc != 0) {
                    throw new IllegalStateException("nrdshim_set_reblur_settings failed: " + rc
                            + " last=" + lib.lastResult());
                }
                appliedAccumulatedFrames = accumulatedFrames;
            }
            lib.newFrame();

            // ---- Projection. Handed over as built, except for the depth row: NRD derives every
            // world-space scale from it, and Minecraft's float-Z reverse-Z row carries a degenerate
            // effective near plane that shrinks all of them (see the class docs). A clean reverse-Z
            // infinite row with vanilla's near plane restores NRD's design point. The Y row is NOT
            // touched: NRD's DecomposeProjection handles handedness itself.
            // JOML names elements mNM = COLUMN N, ROW M, so the depth row (row 2) is
            // m02/m12/m22/m32 and the w row (row 3) is m03/m13/m23/m33. Writing near into m23 and
            // 1 into m32 -- the obvious-looking but transposed choice -- builds row2 = (0,0,0,1)
            // and row3 = (0,0,near,0), i.e. clipZ = w_in and clipW = near*z. NRD's
            // DecomposeProjection then divides by a zero/degenerate term and hands back non-finite
            // values, which the shim rejects with "set_settings: non-finite matrix" -- the exact
            // error that disabled REBLUR for the whole session and made the toggle look inert.
            // The projection arrives from the game and is NOT always sane: a single frame with a
            // degenerate or non-finite projection (a zero/NaN FOV during a transition, a resize
            // mid-frame) is enough to poison everything derived from it. The log shows exactly
            // that -- REBLUR ran fine for ~40 seconds and then one bad frame arrived. Skip that
            // frame instead of failing, because the previous behaviour turned a one-frame glitch
            // into "NRD is dead for the rest of the session", which is what made the toggle look
            // like it did nothing.
            if (!isFinite(viewToClip) || !isFinite(viewRotation)) {
                warnOnce("NRD: skipping a frame whose camera matrices were not finite");
                return false;
            }
            Matrix4f nrdViewToClip = new Matrix4f(viewToClip);
            nrdViewToClip.m22(0f);                     // row2.z
            nrdViewToClip.m32(NRD_PROJECTION_NEAR);    // row2.w  -> clipZ = near
            nrdViewToClip.m23(1f);                     // row3.z  -> clipW = z_view
            nrdViewToClip.m33(0f);                     // row3.w

            // ---- worldToView in the rebased terrain space the signals live in.
            // The camera and the anchor are doubles that come from the game; a NaN camera (seen
            // during dimension changes and respawns) turns the whole matrix non-finite. Validate
            // the operands, not just the result, so the message says which side was bad.
            if (!Double.isFinite(camWorldX) || !Double.isFinite(camWorldY) || !Double.isFinite(camWorldZ)
                    || !Double.isFinite(anchorX) || !Double.isFinite(anchorY) || !Double.isFinite(anchorZ)) {
                warnOnce("NRD: skipping a frame with a non-finite camera or terrain anchor");
                return false;
            }
            Matrix4f worldToView = new Matrix4f(viewRotation)
                    .translate((float) (anchorX - camWorldX), (float) (anchorY - camWorldY),
                            (float) (anchorZ - camWorldZ));
            if (!isFinite(worldToView)) {
                warnOnce("NRD: skipping a frame whose worldToView was not finite");
                return false;
            }

            // A teleport/respawn is the only camera discontinuity left: a rebase is compensated
            // below by re-expressing the previous camera against the CURRENT anchor, so ordinary
            // movement never invalidates history.
            boolean jumped = projectionChanged || lastWidth != renderWidth || lastHeight != renderHeight;
            if (hasPrev && !jumped) {
                double dx = camWorldX - prevCamWorldX;
                double dy = camWorldY - prevCamWorldY;
                double dz = camWorldZ - prevCamWorldZ;
                jumped = dx * dx + dy * dy + dz * dz
                        > (double) TELEPORT_JUMP_BLOCKS * TELEPORT_JUMP_BLOCKS;
            }
            boolean useHistory = hasPrev && !jumped;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment v2c = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment v2cPrev = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment w2v = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                MemorySegment w2vPrev = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
                // JOML's buffer store is column-major, exactly NRD's "vector is a column" layout.
                copyInto(nrdViewToClip, v2c);
                copyInto(worldToView, w2v);
                // A stored previous projection that is not finite (it was captured from a frame
                // the game had already mangled) must not be handed to NRD; drop to the
                // no-history path for this frame instead, which is always safe.
                if (useHistory && !isFinite(prevViewToClip)) {
                    warnOnce("NRD: dropping a non-finite stored projection; restarting history");
                    useHistory = false;
                    hasPrev = false;
                }
                if (useHistory) {
                    copyInto(prevViewToClip, v2cPrev);
                    // THE rebase fix: the previous camera is re-expressed against the CURRENT
                    // anchor, so both matrices describe the same world space. NRD reads its camera
                    // delta from the difference of the two translations, so if the previous matrix
                    // still carried the previous anchor, every rebase would show up as a jump of up
                    // to a chunk and throw the history away in the middle of movement.
                    Matrix4f worldToViewPrev = new Matrix4f(prevViewRotation)
                            .translate((float) (anchorX - prevCamWorldX),
                                    (float) (anchorY - prevCamWorldY),
                                    (float) (anchorZ - prevCamWorldZ));
                    if (!isFinite(worldToViewPrev)) {
                        warnOnce("NRD: previous worldToView was not finite; restarting history");
                        useHistory = false;
                        hasPrev = false;
                        copyInto(nrdViewToClip, v2cPrev);
                        copyInto(worldToView, w2vPrev);
                    } else {
                        copyInto(worldToViewPrev, w2vPrev);
                    }
                } else {
                    // No history to reproject: "previous" equals "current" so NRD cannot chase a
                    // reprojection that does not exist.
                    copyInto(nrdViewToClip, v2cPrev);
                    copyInto(worldToView, w2vPrev);
                }
                // cameraJitterPrev must be the jitter the HISTORY was rendered with; feeding the
                // current jitter there de-jitters history by the wrong sub-pixel offset every frame,
                // which is precisely the "camera tremble" the old integration showed.
                float jitterPrevX = useHistory ? prevJitterX : jitterPixelsX;
                float jitterPrevY = useHistory ? prevJitterY : jitterPixelsY;
                int rc = lib.setSettings(v2c, v2cPrev, w2v, w2vPrev,
                        jitterPixelsX, jitterPixelsY, jitterPrevX, jitterPrevY,
                        // The renderer's MVs are in render pixels; NRD reprojects in UV space.
                        1.0f / renderWidth, 1.0f / renderHeight,
                        DENOISING_RANGE, DISOCCLUSION_THRESHOLD,
                        frameIndex, useHistory ? 0 : 1, validationOn ? 1 : 0);
                if (rc != 0) {
                    // NRDSHIM_ERR_INVALID_ARGUMENT (-2) means the shim rejected this frame's
                    // parameters. That is a per-frame condition, not a broken integration, so skip
                    // the frame and let SVGF cover it; throwing here is what previously killed
                    // REBLUR for the rest of the session on a single bad frame.
                    if (rc == NRDSHIM_ERR_INVALID_ARGUMENT) {
                        warnOnce("NRD: the shim rejected a frame's settings (rc=-2); skipping"
                                + " those frames and continuing");
                        return false;
                    }
                    throw new IllegalStateException("nrdshim_set_settings failed: " + rc
                            + " last=" + lib.lastResult());
                }
            }

            int rc = lib.denoise(cmd,
                    motion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                    normalRoughness.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    viewZ.image, VK10.VK_FORMAT_R32_SFLOAT,
                    diffIn.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specIn.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    diffOut.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    specOut.image, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    validationOn ? validation.image : 0L, VK10.VK_FORMAT_R8G8B8A8_UNORM);
            if (rc != 0) {
                throw new IllegalStateException("nrdshim_denoise failed: " + rc + " last=" + lib.lastResult());
            }

            // Remember this frame's state for the next reprojection, AFTER a successful denoise so a
            // failed frame cannot leave a half-updated history behind. Note what is stored: the
            // camera in ABSOLUTE world coordinates and the rotation-only view matrix, never the
            // assembled worldToView — the next frame rebuilds it against ITS anchor (see above).
            prevCamWorldX = camWorldX;
            prevCamWorldY = camWorldY;
            prevCamWorldZ = camWorldZ;
            prevJitterX = jitterPixelsX;
            prevJitterY = jitterPixelsY;
            nrdViewToClip.get(prevViewToClip);
            prevViewRotation.set(viewRotation);
            lastWidth = renderWidth;
            lastHeight = renderHeight;
            hasPrev = true;
            return true;
        } catch (Throwable t) {
            // Latch off, but allow the player to retry by flipping the toggle: resetHistory()
            // clears this, so turning NRD off and on again re-attempts instead of forcing a
            // restart. SVGF owns the slot in the meantime (RtComposite keys on active()).
            failed = true;
            CausticaMod.LOGGER.error("NRD denoise failed; the renderer's own denoiser takes over. "
                    + "Toggle NRD off and on again to retry.", t);
            return false;
        }
    }

    /**
     * REBLUR's history length for this frame, derived from a target accumulation TIME rather than a
     * fixed frame count. NRD recommends exactly this ("recalculate the number of accumulated frames
     * from the accumulation time ... it allows to minimize lags if FPS is low and maximize IQ if FPS
     * is high"), and it is what makes the denoiser behave the same at 30 and at 120 fps instead of
     * accumulating twice as long a tail on a fast machine.
     */
    private int maxAccumulatedFrames() {
        long now = System.nanoTime();
        if (lastFrameNanos != 0) {
            float dtSeconds = (now - lastFrameNanos) / 1.0e9f;
            if (dtSeconds > 0.0f) {
                float fps = Math.clamp(1.0f / dtSeconds, MIN_FPS, MAX_FPS);
                // Exponential smoothing: a single hitching frame must not shorten the history.
                smoothedFps = smoothedFps * 0.9f + fps * 0.1f;
            }
        }
        lastFrameNanos = now;
        // nrd::GetMaxAccumulatedFrameNum(accumulationTime, fps).
        int frames = Math.round(ACCUMULATION_TIME_SECONDS * smoothedFps);
        return Math.clamp(frames, 8, MAX_HISTORY_FRAMES);
    }

    /** True when every element of the matrix is finite (no NaN, no Inf). */
    /**
     * NRD's pre-pass radii are quoted in pixels for a full-resolution render; scale them to the
     * render width actually in use so the blur covers the same angular extent instead of a much
     * larger share of a small frame. See PREPASS_REFERENCE_WIDTH.
     */
    private static float prepassRadius(float referenceRadius, int renderWidth) {
        float scaled = referenceRadius * (renderWidth / PREPASS_REFERENCE_WIDTH);
        return Math.max(scaled, MIN_PREPASS_BLUR_RADIUS);
    }

    private static boolean isFinite(Matrix4fc m) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                if (!Float.isFinite(m.get(col, row))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isFinite(float[] m) {
        for (float v : m) {
            if (!Float.isFinite(v)) {
                return false;
            }
        }
        return true;
    }

    /** Log a recurring condition once per session so a per-frame glitch cannot flood the log. */
    private void warnOnce(String message) {
        if (warned.add(message)) {
            CausticaMod.LOGGER.warn(message);
        }
    }

    /**
     * Write a matrix into native memory in COLUMN-MAJOR order and NATIVE byte order.
     *
     * Deliberately not {@code matrix.get(segment.asByteBuffer().asFloatBuffer())}: the FFM spec says
     * {@link MemorySegment#asByteBuffer()} returns a buffer in BIG_ENDIAN order, so on x86 every
     * float reached the shim byte-reversed. The failure was almost invisible because zeros survive a
     * byte swap unchanged -- the matrix kept its shape, and only the non-zero entries turned into
     * garbage. The logged m33 of 4.6006e-41 is exactly the bit pattern of 1.0f read backwards.
     */
    private static void copyInto(Matrix4fc src, MemorySegment dst) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                dst.setAtIndex(ValueLayout.JAVA_FLOAT, col * 4 + row, src.get(col, row));
            }
        }
    }

    private static void copyInto(float[] src, MemorySegment dst) {
        for (int i = 0; i < 16; i++) {
            dst.setAtIndex(ValueLayout.JAVA_FLOAT, i, src[i]);
        }
    }

    /** Drop the temporal history without tearing the runtime down (toggle flips, resolution change). */
    public void resetHistory() {
        hasPrev = false;
        // Clearing the failure latch here is what makes the UI toggle a real retry: ensureOutput
        // calls this whenever the NRD path is (re)selected or the resolution changes.
        failed = false;
        appliedAccumulatedFrames = -1;
    }

    public void destroy() {
        NrdRuntime.INSTANCE.shutdown();
        failed = false;
        hasPrev = false;
        lastFrameNanos = 0;
        smoothedFps = 60.0f;
        // The shim's tuning state dies with the unloaded library; resend it on the next session.
        appliedAccumulatedFrames = -1;
    }
}
