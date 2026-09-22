package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.pipeline.RtSvgfDenoiser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the denoiser contracts that span two files and would otherwise break silently.
 *
 * <p>Nothing here re-implements the maths; each test pins an invariant that a compiler cannot see:
 * the NRD material demodulation is split across a Slang shader (which divides) and a GLSL shader
 * (which multiplies back), and SVGF's temporal and spatial stages agree on where the accumulated
 * frame count lives. A drift in either place still compiles and still runs — it just produces a
 * wrong image, which is exactly the class of bug this suite exists to catch.
 */
final class RtDenoiserShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path WORLD_CORE = REPO_ROOT.resolve("shaders/world/world_core.slang");
    private static final Path WORLD_RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");
    private static final Path NRD_COMBINE = REPO_ROOT.resolve("shaders/display/nrd_combine.comp");
    private static final Path SVGF_REPROJECT = REPO_ROOT.resolve("shaders/display/svgf_reproject.comp");
    private static final Path SVGF_ATROUS = REPO_ROOT.resolve("shaders/display/svgf_atrous.comp");
    private static final Path NRD_DENOISER =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtNrdDenoiser.java");

    /**
     * The demodulation floors must agree between the shader that divides the material out and the
     * one that multiplies it back. If they drift, the round trip stops being the identity and the
     * denoised image comes out with the wrong albedo — subtly, and only on the NRD path.
     */
    @Test
    void nrdMaterialFactorConstantsMatchBetweenSlangAndGlsl() throws IOException {
        Map<String, String> slang = scan(Files.readString(WORLD_CORE),
                Pattern.compile("public\\s+static\\s+const\\s+float\\s+(NRD_\\w*FACTOR_MIN_SCALE)\\s*=\\s*([\\d.]+)\\s*;"));
        Map<String, String> glsl = scan(Files.readString(NRD_COMBINE),
                Pattern.compile("const\\s+float\\s+(NRD_\\w*FACTOR_MIN_SCALE)\\s*=\\s*([\\d.]+)\\s*;"));

        assertFalse(slang.isEmpty(), "no NRD material factor constants found in " + WORLD_CORE);
        assertEquals(slang, glsl,
                "NRD demodulation floors differ between world_core.slang and nrd_combine.comp");
    }

    /**
     * Demodulation and re-modulation must be derived from the SAME guide textures. Reading a value
     * on one side that the other cannot see (anything internal to the tracer) is what would make
     * the round trip inexact, so both sides are pinned to the albedo/specular-albedo/roughness
     * guides.
     */
    @Test
    void nrdSignalsAreDemodulatedByTheTracerAndRemodulatedByTheCombinePass() throws IOException {
        String raygen = Files.readString(WORLD_RGEN);
        String combine = Files.readString(NRD_COMBINE);

        assertTrue(raygen.contains("nrdMaterialFactors(albedoGuide, specAlbedoGuide, rough, diffFactor, specFactor)"),
                "the tracer must build the demodulation factors from the guide textures");
        assertTrue(raygen.contains("diffRad /= diffFactor;") && raygen.contains("specRad /= specFactor;"),
                "the tracer must divide the per-lobe radiance by the material factors");
        assertTrue(combine.contains("diffRadiance * diffFactor + specRadiance * specFactor"),
                "the combine pass must multiply the material factors back after denoising");
        assertTrue(combine.contains("void nrdMaterialFactors("),
                "the combine pass must mirror nrdMaterialFactors rather than approximating it");
    }

    /**
     * NRD is strict about its projection contract: the matrices must arrive non-jittered and
     * unflipped, because {@code DecomposeProjection} performs its own handedness conversion.
     * Re-introducing the old Y-flip is the single easiest way to break REBLUR's reprojection, and
     * it fails invisibly (history reprojects to a mirrored position), so pin its absence.
     */
    @Test
    void nrdProjectionIsNotHandednessFlippedByTheHost() throws IOException {
        String denoiser = Files.readString(NRD_DENOISER);

        assertFalse(denoiser.contains("nrdViewToClip.m11(-"),
                "NRD converts handedness itself; pre-flipping the projection makes it flip twice");
        // The depth row is still sanitized, but with the CORRECT element names: JOML's mNM is
        // column N, row M, so the near plane belongs in m32 (row2.w) and the 1 in m23 (row3.z).
        // The transposed spelling this assertion used to pin is what produced a degenerate matrix
        // and the shim's "non-finite matrix" rejection; nrdDepthRowIsNotTransposed covers it.
        assertTrue(denoiser.contains("nrdViewToClip.m22(0f)")
                        && denoiser.contains("nrdViewToClip.m32(NRD_PROJECTION_NEAR)"),
                "the degenerate float-Z depth row must still be sanitized for NRD's world-space scales");
    }

    /**
     * REBLUR's history must survive a terrain rebase. The denoiser therefore takes the camera in
     * absolute world coordinates plus the current anchor and re-expresses the previous frame
     * against that anchor; passing anchor-relative coordinates alone (what the retired integration
     * did) makes every rebase look like a teleport and drops the history mid-motion.
     */
    @Test
    void nrdCompensatesTerrainRebaseInsteadOfResettingOnCameraMotion() throws IOException {
        String denoiser = Files.readString(NRD_DENOISER);
        String composite = Files.readString(
                REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));

        assertTrue(denoiser.contains("double camWorldX, double camWorldY, double camWorldZ,")
                        && denoiser.contains("double anchorX, double anchorY, double anchorZ,"),
                "the denoiser needs both the absolute camera and the anchor to compensate a rebase");
        assertTrue(composite.contains("camX, camY, camZ,")
                        && composite.contains("terrain.blockX, terrain.blockY, terrain.blockZ,"),
                "RtComposite must pass the absolute camera position and the terrain anchor");
        assertTrue(denoiser.contains("TELEPORT_JUMP_BLOCKS"),
                "only a real teleport may drop REBLUR's history");
    }

    /**
     * The SVGF depth gate predicts what a static surface's PREVIOUS view depth should have been by
     * adding the camera's forward travel to the current depth. Row 2 of the rotation-only view
     * matrix is view-space +Z, and view space looks down -Z (the tracer treats curClip.w = -z_view
     * as a positive forward-growing depth), so that dot product is BACKWARD travel and has to be
     * negated.
     *
     * <p>Getting this sign wrong does not soften the gate, it inverts the correction: the error
     * becomes twice the per-frame travel instead of zero, and every surface within ~4.8 blocks
     * fails the gate on every frame while walking. The whole history collapses to a single sample
     * in motion -- confirmed in-game with debug view 10, which showed white standing still and
     * black while moving. Pin the negation; it is one character and it is invisible in review.
     */
    @Test
    void svgfDepthGateUsesForwardNotBackwardCameraTravel() throws IOException {
        String composite = Files.readString(
                REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));

        assertTrue(composite.contains("svgfCamForwardDelta = (float) -((camX - svgfPrevCamX) * fx"),
                "the camera's forward travel must negate the dot with view row 2, which points backward");
        String reproject = Files.readString(SVGF_REPROJECT);
        assertTrue(reproject.contains("float expectedZPrev = z + pc.camForwardDelta;"),
                "the gate must compare against the predicted previous depth, not the current one");
    }

    /**
     * Nothing may restart the temporal history because of an FOV change.
     *
     * <p>The motion vectors are built against prevViewProj, the previous frame's projection, so a
     * zoom reaches the reprojection as the on-screen displacement it actually is and the geometry
     * gate validates the result. Resetting instead dropped every pixel from the 48-frame window to
     * a single sample at once, and vanilla eases a sprint FOV over about three frames, so it
     * flashed three times -- the artefact left when starting or stopping a sprint and when
     * toggling flight.
     *
     * <p>This is also why the whole projection-matrix comparison is gone: with view bobbing baked
     * into that matrix, no threshold on it could tell a zoom from a footstep anyway.
     */
    @Test
    void noTemporalResetIsDrivenByTheProjectionMatrix() throws IOException {
        String composite = Files.readString(
                REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java"));

        assertFalse(composite.contains("projectionChanged"),
                "an FOV change must not restart accumulation; the motion vectors already carry it");
        assertFalse(composite.contains("prevProjection"),
                "no leftover projection snapshot should remain to tempt a future reset");
        assertTrue(composite.contains("boolean svgfReset = !svgfHasHistory;"),
                "only a genuine absence of history may restart SVGF");
    }

    /**
     * SVGF's accumulated frame count lives in the moments texture, NOT in the history's alpha,
     * because the à-trous feedback overwrites the whole history image. Moving it back into the
     * history alpha would make the count read as variance (and vice versa) with no compile error.
     */
    @Test
    void svgfFrameCountLivesInTheMomentsTexture() throws IOException {
        String reproject = Files.readString(SVGF_REPROJECT);
        String atrous = Files.readString(SVGF_ATROUS);

        assertTrue(reproject.contains("imageStore(momentsOut, pix, vec4(moments, frames, 0.0));"),
                "the reprojection must store the frame count in the moments texture");
        // The wavelet no longer reads the count at all: its only consumer was the per-pixel reach
        // cap, which was the mutating-footprint bug (see svgfCascadeReachIsUniform). The count is
        // still produced here because the reprojection itself needs it to drive alpha.
        // The wavelet may read the count ONLY to draw the diagnostic overlay, never to shape the
        // filter: a per-pixel reach driven by the frame count was the mutating-footprint bug (see
        // svgfCascadeReachIsUniform). Pin that structurally -- every read must sit after the
        // debug-mode branch, which runs once at the very end and returns immediately.
        int momentsRead = atrous.indexOf("imageLoad(moments");
        if (momentsRead >= 0) {
            int debugBranch = atrous.indexOf("pc.debugMode != 0");
            assertTrue(debugBranch >= 0 && momentsRead > debugBranch,
                    "the wavelet may only read the frame count inside the diagnostic overlay");
            assertTrue(atrous.indexOf("imageLoad(moments", momentsRead + 1) < 0,
                    "exactly one frame-count read is allowed, the diagnostic one");
        }
        assertFalse(atrous.contains("histLen"),
                "the frame count no longer travels in the history image's alpha channel");
    }

    /**
     * The two properties that make SVGF stable rather than smeary: history is rejected on GEOMETRY
     * (depth + normal from the previous frame) instead of clamped on colour, and the wavelet's
     * luminance edge-stop is scaled by the estimated standard deviation. Losing either turns the
     * filter back into the fixed-sigma blur + colour-clamped TAA pair this replaced.
     */
    @Test
    void svgfValidatesHistoryOnGeometryAndDrivesTheFilterWithVariance() throws IOException {
        String reproject = Files.readString(SVGF_REPROJECT);
        String atrous = Files.readString(SVGF_ATROUS);

        assertTrue(reproject.contains("bool reprojectionValid(")
                        && reproject.contains("imageLoad(prevViewZ, prevPix).r")
                        && reproject.contains("imageLoad(prevNormal, prevPix).xyz"),
                "history must be validated against the previous frame's depth and normal");
        assertTrue(reproject.contains("moments.y - moments.x * moments.x"),
                "variance must come from the accumulated luminance moments");
        assertTrue(atrous.contains("pc.phiLuminance * sqrt(max(filteredVar, 1.0e-8))"),
                "the luminance edge-stop must be scaled by the estimated standard deviation");
        assertTrue(atrous.contains("varianceSum += w * w * vt;"),
                "variance must propagate through the squared filter weights");
    }

    private static Map<String, String> scan(String source, Pattern pattern) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            found.put(matcher.group(1), matcher.group(2));
        }
        return found;
    }

    /** Same root discovery as the sibling shader tests, kept local to avoid coupling them. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("shaders/world"))
                    && Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not locate the repository root from " + dir);
    }

    /**
     * The SVGF cascade must filter DEMODULATED radiance: the reprojection divides the albedo guide
     * out and only the final à-trous iteration multiplies it back. Filtering modulated radiance
     * averages across texture detail and flattens it (measured: an albedo contrast ratio of 3.27
     * collapsing to 1.01 — the "everything looks like flat poster paint" failure).
     */
    @Test
    void svgfFiltersDemodulatedRadiance() throws Exception {
        String reproject = Files.readString(SVGF_REPROJECT);
        String atrous = Files.readString(SVGF_ATROUS);
        assertTrue(reproject.contains("c = c / demodFactor(q)"),
                "svgf_reproject must divide the albedo guide out of the current sample");
        assertTrue(atrous.contains("pc.modulate != 0"),
                "svgf_atrous must re-modulate on the final pass only");
        // The floor is shared by both halves of the round trip; a mismatch tints the image.
        assertTrue(reproject.contains("const float ALBEDO_FLOOR = 0.10;"),
                "svgf_reproject ALBEDO_FLOOR changed");
        assertTrue(atrous.contains("const float ALBEDO_FLOOR = 0.10;"),
                "svgf_atrous ALBEDO_FLOOR must match svgf_reproject");
        assertTrue(reproject.contains("const float SPECULAR_SHARE = 0.04;"),
                "svgf_reproject SPECULAR_SHARE changed");
        assertTrue(atrous.contains("const float SPECULAR_SHARE = 0.04;"),
                "svgf_atrous SPECULAR_SHARE must match svgf_reproject");
    }

    /**
     * The reprojected frame count must be normalized by the surviving bilinear weight, exactly like
     * the colour and the moments. Keeping the unnormalized sum makes the count follow n <- n*w + 1
     * under sustained sub-pixel motion, saturating at 1/(1-w) instead of reaching the cap: at 90%
     * acceptance that is 10 frames (32% of the raw noise left) forever, which is precisely the
     * "converges when standing still, stays noisy while walking" behaviour.
     */
    @Test
    void svgfNormalizesTheFrameCount() throws Exception {
        String reproject = Files.readString(SVGF_REPROJECT);
        assertTrue(reproject.contains("histFrames *= inv;"),
                "the frame count must be renormalized over the accepted taps");
        // Sanity-check the arithmetic the fix is based on.
        double w = 0.9;
        double unnormalized = 0.0;
        double normalized = 0.0;
        for (int i = 0; i < 500; i++) {
            unnormalized = Math.min(unnormalized * w + 1.0, 48.0);
            normalized = Math.min(normalized + 1.0, 48.0);
        }
        assertTrue(unnormalized < 11.0,
                "unnormalized count should saturate near 1/(1-w) = 10, was " + unnormalized);
        assertEquals(48.0, normalized, 1.0e-6,
                "normalized count must be able to reach the accumulation cap");
    }

    /**
     * The history feedback must be taken from a still-demodulated iteration. If it came from the
     * final (re-modulating) pass, the next frame would blend a modulated history against
     * demodulated samples and the albedo would compound once per frame.
     */
    @Test
    void svgfHistoryFeedbackStaysDemodulated() {
        assertTrue(RtSvgfDenoiser.HISTORY_FEEDBACK_PASS < RtSvgfDenoiser.ATROUS_PASSES - 1,
                "history feedback must precede the final re-modulating pass");
    }

    /**
     * The sky must bypass both denoiser stages. It is evaluated analytically by the tracer (sky
     * gradient, sun disc, cloud coverage) and arrives noise-free, so there is nothing to denoise —
     * but the à-trous cascade's reach is a 62-pixel radius (five iterations of a 5x5 kernel at
     * spacing 1,2,4,8,16), which turned the sun into a glow and melted cloud edges.
     */
    @Test
    void svgfLeavesTheSkyAlone() throws Exception {
        String reproject = Files.readString(SVGF_REPROJECT);
        String atrous = Files.readString(SVGF_ATROUS);
        assertTrue(reproject.contains("if (sky) {"),
                "svgf_reproject must pass sky pixels straight through");
        assertTrue(atrous.contains("if (centerSky) {"),
                "svgf_atrous must pass sky pixels straight through");
    }

    /**
     * The reprojection's normal gate exists to catch disocclusion, not to measure shading detail.
     * At 0.85 it rejected roughly a fifth of taps every frame on geometry that never moved, which
     * shortened the history exactly where the signal is weakest (shadow) and produced flicker that
     * settled only when the camera stopped.
     */
    @Test
    void svgfNormalGateIsNotOverTight() throws Exception {
        String reproject = Files.readString(SVGF_REPROJECT);
        assertTrue(reproject.contains("const float NORMAL_TOLERANCE = 0.70;"),
                "the normal gate must stay loose enough to accept unchanged geometry");
    }

    /**
     * The reprojection's depth gate must compare against the depth a static surface is PREDICTED to
     * have had last frame, not against this frame's depth. View depth changes legitimately as the
     * camera moves: at 36 fps walking advances ~0.12 blocks per frame, which already exceeds the 5%
     * tolerance for anything closer than ~2.5 blocks, so the naive comparison reset the history of
     * every nearby surface on every frame while moving — noise (nothing accumulates) and blur (the
     * short-history fallback filter widens) at the same time, both vanishing when standing still.
     */
    @Test
    void svgfDepthGateAccountsForCameraTravel() throws Exception {
        String reproject = Files.readString(SVGF_REPROJECT);
        assertTrue(reproject.contains("float expectedZPrev = z + pc.camForwardDelta;"),
                "the gate must predict the previous depth from the camera's forward travel");
        assertTrue(reproject.contains("abs(expectedZPrev - zPrev)"),
                "the depth comparison must use the predicted previous depth");
        assertFalse(reproject.contains("abs(z - zPrev) >"),
                "the naive current-vs-previous depth comparison must be gone");
    }

    /**
     * NRD's projection depth row, in JOML's column-major mNM naming. Getting m23 and m32 the wrong
     * way round builds a degenerate matrix whose decomposition is non-finite; the shim rejects it
     * with "set_settings: non-finite matrix", REBLUR never runs, and the UI toggle looks inert.
     */
    @Test
    void nrdDepthRowIsNotTransposed() throws Exception {
        String denoiser = Files.readString(NRD_DENOISER);
        assertTrue(denoiser.contains("nrdViewToClip.m32(NRD_PROJECTION_NEAR);"),
                "row2.w (JOML m32) must carry the near plane");
        assertTrue(denoiser.contains("nrdViewToClip.m23(1f);"),
                "row3.z (JOML m23) must be 1 so clipW = z_view");
        assertFalse(denoiser.contains("nrdViewToClip.m23(NRD_PROJECTION_NEAR);"),
                "the transposed depth row is what disabled REBLUR");
    }

    /** A failed NRD frame must be retryable by toggling, not latched off for the whole session. */
    @Test
    void nrdFailureIsRecoverable() throws Exception {
        String denoiser = Files.readString(NRD_DENOISER);
        int reset = denoiser.indexOf("public void resetHistory()");
        assertTrue(reset > 0, "resetHistory must exist");
        String body = denoiser.substring(reset, Math.min(denoiser.length(), reset + 400));
        assertTrue(body.contains("failed = false;"),
                "resetHistory must clear the failure latch so the toggle retries");
    }

    /**
     * The à-trous reach must be UNIFORM across the image.
     *
     * It used to be capped per pixel by that pixel's history length, via
     * {@code int maxStep = int(clamp(frames, 4.0, 32.0))}. Because the history length is per pixel
     * and changes every frame while the camera moves, the integer cap gave neighbouring pixels
     * visibly different filter radii (30px beside 46px beside 62px) and made each pixel's radius
     * jump between discrete values frame to frame. That mutating footprint is what read as block
     * textures whose pixels keep rearranging while walking and settle a few seconds after stopping.
     *
     * How much to trust a pixel is already carried, continuously, by its variance driving the
     * luminance sigma. Encoding it a second time as a hard integer cap only adds discontinuity.
     */
    @Test
    void svgfCascadeReachIsUniform() throws Exception {
        String atrous = Files.readString(SVGF_ATROUS);
        assertFalse(atrous.contains("int step = min(pc.step, maxStep);"),
                "the per-pixel integer reach cap is the mutating-footprint bug; it must stay gone");
        assertTrue(atrous.contains("int step = pc.step;"),
                "every pixel must use the same tap spacing for a given iteration");
        assertTrue(atrous.contains("ivec2 offset = ivec2(dx, dy) * step;"),
                "taps must use that spacing");
    }

    /**
     * The variance handed to the spatial pass must be continuous in history length.
     *
     * Two discontinuities used to live here: a hard if/else at 4 frames between the spatial and
     * temporal estimates, and a {@code 4/frames} boost that scaled the spatial estimate by up to
     * 4x (so sigma by up to 2x) purely because a pixel was new. Both made the edge-stop differ
     * sharply between adjacent pixels, which is the same artefact as the reach cap seen through
     * the sigma instead of the radius.
     */
    @Test
    void svgfVarianceHandoverIsContinuous() throws Exception {
        String reproject = Files.readString(SVGF_REPROJECT);
        assertFalse(reproject.contains("spatial * (SPATIAL_VARIANCE_FRAMES / max(frames, 1.0))"),
                "the history-dependent variance boost must stay gone");
        assertTrue(reproject.contains("variance = mix(spatial, temporalVar, t);"),
                "spatial and temporal variance must blend, not switch at a threshold");
    }

    /**
     * Short history must not widen the luminance sigma on top of an already inflated variance. In
     * that regime the variance handed over is a spatial estimate scaled by 4/frames; multiplying
     * again pushed sigma to ~5.7 against a signal level of 0.3, weighting every neighbour ~0.99 —
     * a plain box blur over the full cascade reach.
     */
    @Test
    void svgfDoesNotDoubleWidenShortHistory() throws Exception {
        String atrous = Files.readString(SVGF_ATROUS);
        assertFalse(atrous.contains("sigmaL *= 1.0 + 3.0 * (1.0 - frames / HISTORY_FIX_FRAMES);"),
                "the short-history sigma widening compounded an already inflated estimate");
        // The bound is a FLOOR, not a ceiling: a sigma narrower than the residual noise makes the
        // bilateral weight mode-seeking, which darkens shadows (measured -20% before this).
        assertTrue(atrous.contains("SIGMA_LUMINANCE_MIN_RELATIVE"),
                "the luminance stop needs a lower bound so it cannot go narrower than the noise");
        assertFalse(atrous.contains("SIGMA_LUMINANCE_MAX_RELATIVE"),
                "clamping sigma DOWN biases the estimate toward the mode and darkens shadows");
    }

    /**
     * A single bad frame must not disable NRD for the session. The game hands over the projection,
     * and one non-finite or degenerate frame (seen ~40 s into a session, right before the pause
     * menu) previously latched the integration off permanently — which is what "the NRD toggle
     * does nothing" actually looked like.
     */
    @Test
    void nrdSkipsBadFramesInsteadOfLatchingOff() throws Exception {
        String denoiser = Files.readString(NRD_DENOISER);
        assertTrue(denoiser.contains("if (!isFinite(viewToClip) || !isFinite(viewRotation))"),
                "the incoming camera matrices must be validated before use");
        assertTrue(denoiser.contains("rc == NRDSHIM_ERR_INVALID_ARGUMENT"),
                "a per-frame parameter rejection must skip the frame, not throw");
        assertTrue(denoiser.contains("NRD: skipping a frame"),
                "skipped frames must be reported");
    }

    /**
     * The luminance edge-stop must decide on a PREFILTERED guide, never on raw 1-spp samples.
     *
     * Path-traced noise is multiplicative and right-skewed, so a bilateral weight computed from
     * noisy samples rejects bright neighbours more often than dark ones and the weighted mean
     * converges toward the distribution's mode instead of its mean. The image comes out
     * systematically darker, and the narrower the sigma the worse it gets — which is why shadows
     * and reflections turned dark and harsh whenever motion shortened the history. Measured on a
     * flat 0.30 signal: -20% brightness with raw guides, -1.6% with the prefilter.
     */
    @Test
    void svgfEdgeStopUsesAPrefilteredGuide() throws Exception {
        String atrous = Files.readString(SVGF_ATROUS);
        assertTrue(atrous.contains("float prefilteredLuma(ivec2 p, ivec2 maxPix)"),
                "the prefiltered luminance guide must exist");
        assertTrue(atrous.contains("float lc = prefilteredLuma(pix, maxPix);"),
                "the centre luminance must come from the prefiltered guide");
        assertTrue(atrous.contains("float lt = prefilteredLuma(q, maxPix);"),
                "the tap luminance must come from the prefiltered guide");
    }

    /**
     * Matrices must reach the shim in NATIVE byte order.
     *
     * {@code MemorySegment.asByteBuffer()} is specified to return a BIG_ENDIAN buffer, so writing a
     * matrix through {@code matrix.get(segment.asByteBuffer().asFloatBuffer())} byte-reverses every
     * float on x86. The failure is nearly invisible: zeros survive a byte swap unchanged, so the
     * matrix keeps its shape and only the non-zero entries become garbage — REBLUR ran for a while
     * on a nonsense camera before one of them happened to decode as NaN. The smoking gun in the
     * game log was m33 = 4.6006e-41, which is exactly the bit pattern of 1.0f read backwards.
     */
    @Test
    void nrdMatricesAreWrittenInNativeByteOrder() throws Exception {
        String denoiser = Files.readString(NRD_DENOISER);
        // Match the CALL, not the word: the fix's own Javadoc names the method it replaced.
        assertFalse(denoiser.contains(".get(v2c.asByteBuffer()")
                        || denoiser.contains(".get(w2v.asByteBuffer()")
                        || denoiser.contains(".get(v2cPrev.asByteBuffer()")
                        || denoiser.contains(".get(w2vPrev.asByteBuffer()"),
                "asByteBuffer() is BIG_ENDIAN; matrices must be written with setAtIndex");
        assertTrue(denoiser.contains("private static void copyInto(Matrix4fc src, MemorySegment dst)"),
                "the native-order matrix writer must exist");
        assertTrue(denoiser.contains("dst.setAtIndex(ValueLayout.JAVA_FLOAT, col * 4 + row, src.get(col, row));"),
                "the writer must store column-major floats at native endianness");

        // The bit pattern that identified the bug, kept here so the reasoning stays verifiable.
        int oneBits = Float.floatToRawIntBits(1.0f);
        int reversed = Integer.reverseBytes(oneBits);
        assertEquals(4.6006e-41f, Float.intBitsToFloat(reversed), 1.0e-45f,
                "byte-reversed 1.0f is the value the game log reported");
    }

    /**
     * A pixel with no surviving history must not be displayed as a raw 1-spp sample.
     *
     * At a shadow level of 0.05 with multiplicative path-tracing noise, a single sample reaches
     * ~13x the correct value in the tail, next to neighbours that DO have history and show the
     * right value — isolated bright specks popping in shadow while moving. Bounding the fresh
     * sample by its spatial neighbourhood cuts the peak roughly in half while shifting the mean by
     * under 5%, and it only applies where there is no temporal information to contradict it.
     */
    @Test
    void svgfBoundsHistorylessSamples() throws Exception {
        String reproject = Files.readString(SVGF_REPROJECT);
        // The bound that matters is the TEMPORAL one: fireflies occur every frame, yet the artefact
        // only shows while moving, because motion is what shortens the history that was hiding them.
        assertTrue(reproject.contains("const float FIREFLY_HISTORY_SIGMA = 3.0;"),
                "the firefly bound must be driven by the accumulated history");
        assertTrue(reproject.contains("cur *= bound / lum;"),
                "rejection must scale colour, preserving hue");
        assertTrue(reproject.contains("continue; // exclude the centre: an outlier must not raise its own bound"),
                "the spatial fallback must exclude the centre tap");
    }

    /**
     * The per-lobe signals handed to NRD need a firefly bound too, and it must be RELATIVE.
     *
     * The tracer's undenoised clamp is absolute (luminance 24), so it only ever fires in brightly
     * lit scenes; at a shadow level of ~0.05 a low-pdf sample at luminance 5 is a 100x outlier and
     * passes untouched. REBLUR's own suppressor works against a mean it forms after accumulation,
     * by which point the spike has already been averaged in — which is why the same bright specks
     * appeared with NRD and with the built-in denoiser. The cause is upstream of both.
     */
    @Test
    void nrdPerLobeSignalsAreFireflyBounded() throws Exception {
        String raygen = Files.readString(WORLD_RGEN);
        String core = Files.readString(WORLD_CORE);
        assertTrue(core.contains("NRD_FIREFLY_RELATIVE_MAX"),
                "the per-lobe bound must be relative to the local light level");
        assertTrue(raygen.contains("float maxLobe = localLevel * NRD_FIREFLY_RELATIVE_MAX;"),
                "the bound must scale with the pixel's own combined radiance");
        assertTrue(raygen.contains("diffRad *= maxLobe / diffLum;")
                        && raygen.contains("specRad *= maxLobe / specLum;"),
                "both lobes must be bounded, scaling colour to preserve hue");
    }

    /**
     * NRD's pre-pass blur radii are quoted in pixels for a full-resolution render, so they must be
     * scaled to the render size actually in use. At 640x337 (a 1920-wide window with upscaling) the
     * stock 50-pixel specular radius is 7.8% of the frame width: it smears reflection detail into
     * flat colour before any accumulation happens, and because the pre-pass is anisotropic along
     * the specular lobe it turns a single firefly into a large, stretched blob.
     */
    @Test
    void nrdPrepassRadiusScalesWithRenderResolution() throws Exception {
        String denoiser = Files.readString(NRD_DENOISER);
        assertTrue(denoiser.contains("private static float prepassRadius(float referenceRadius, int renderWidth)"),
                "the pre-pass radius must be resolution-scaled");
        assertTrue(denoiser.contains("prepassRadius(SPECULAR_PREPASS_BLUR_RADIUS, renderWidth)"),
                "the specular pre-pass must use the scaled radius");
        assertTrue(denoiser.contains("private static final float MIN_PREPASS_BLUR_RADIUS"),
                "the pre-pass must never scale to zero; NRD requires it for probabilistic input");
    }
}
