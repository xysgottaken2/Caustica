package dev.comfyfluffy.caustica.client;

/**
 * Sub-pixel camera jitter for the temporal upscalers.
 *
 * <p>Generates a Halton(2,3) low-discrepancy sequence in render-pixel space — the exact sequence
 * both consumers want: DLSS Ray Reconstruction (with its phase-count rule
 * {@code max(32, ceil(8 * (display/render)^2))}) and FSR 3, whose ffx_api jitter query
 * ({@code GetJitterPhaseCount}/{@code GetJitterOffset}) evaluates to the same Halton offsets with
 * the phase-count rule {@code int(8 * (display/render)^2)} — verified against the FSR 3.1 SDK
 * source, so no separate sequence is needed, only the phase-count rule differs.
 * {@link dev.comfyfluffy.caustica.rt.RtComposite} reads the per-frame offset, applies it to the
 * primary ray in the path-tracing shader, and reports it to the active upscaler's dispatch.
 */
public final class CausticaJitter {
	public static final CausticaJitter INSTANCE = new CausticaJitter();

	private int frameIndex;
	private float pixelsX;
	private float pixelsY;

	private CausticaJitter() {
	}

	/** Advance one frame on the DLSS phase-count rule. Call once per frame before the level
	 *  projection is built. */
	public void prepare(int renderWidth, int renderHeight, int displayWidth) {
		prepareWithPhaseCount(jitterPhaseCount(renderWidth, displayWidth));
	}

	/** Advance one frame on FSR 3's phase-count rule (same Halton offsets, no 32-phase floor). */
	public void prepareFsr(int renderWidth, int displayWidth) {
		float ratio = (float) displayWidth / Math.max(1, renderWidth);
		prepareWithPhaseCount(Math.max(1, (int) (8.0f * ratio * ratio)));
	}

	/** Advance one frame on Intel XeSS's rule: a fixed 32-phase cycle, exactly the
	 *  {@code GenerateHalton(2, 3, 1, 32)} sequence Intel's VK sample feeds XeSS. */
	public void prepareXess() {
		prepareWithPhaseCount(32);
	}

	private void prepareWithPhaseCount(int phaseCount) {
		int index = (this.frameIndex++ % phaseCount) + 1; // Halton(0) is degenerate
		this.pixelsX = halton(index, 2) - 0.5f;
		this.pixelsY = halton(index, 3) - 0.5f;
	}

	/** Jitter offset in render-pixel space, applied to the primary ray and reported to RR evaluate. */
	public float jitterPixelsX() {
		return this.pixelsX;
	}

	public float jitterPixelsY() {
		return this.pixelsY;
	}

	private static int jitterPhaseCount(int renderWidth, int displayWidth) {
		float ratio = (float) displayWidth / Math.max(1, renderWidth);
		return Math.max(32, (int) Math.ceil(8.0f * ratio * ratio));
	}

	private static float halton(int index, int base) {
		float f = 1.0f;
		float result = 0.0f;
		int i = index;
		while (i > 0) {
			f /= base;
			result += f * (i % base);
			i /= base;
		}
		return result;
	}
}
