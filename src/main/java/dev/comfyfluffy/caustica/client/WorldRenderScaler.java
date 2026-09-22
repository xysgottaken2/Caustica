package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.rt.RtComposite;

/**
 * RT composite seam. Brackets vanilla's level-rendering section in {@code GameRenderer.render}: the
 * world renders at full resolution, then the ray-traced composite (DLSS-RR denoise + upscale, see
 * {@link RtComposite}) runs once at the before-hand seam, before vanilla's pre-GUI depth clear, so the
 * hand and HUD draw at native resolution on top.
 *
 * <p>This used to host the FSR/DLSS-SR low-res render-scale path; that has been removed — the RT
 * renderer owns reconstruction via DLSS Ray Reconstruction. With {@code -Dcaustica.rt=false} this is an
 * inert passthrough.
 */
public final class WorldRenderScaler {
	public static final WorldRenderScaler INSTANCE = new WorldRenderScaler();

	// Tracks that the level-render window is open so the safety-net end() does not composite twice.
	private boolean rtWindowOpen;

	private WorldRenderScaler() {
	}

	/** Open the level-render window. Called right before level rendering. */
	public void begin(RenderTarget mainTarget) {
		VanillaRenderController.INSTANCE.beginFrame(mainTarget);
		if (VanillaRenderController.INSTANCE.shouldCompositeRt()) {
			this.rtWindowOpen = true;
		}
	}

	/**
	 * Run the RT composite once, at the before-hand seam (the safety-net end() then no-ops because the
	 * window is already closed). In cancel-vanilla mode, this is where the skipped world is replaced.
	 */
	public void end(RenderTarget mainTarget) {
		this.end(mainTarget, true);
	}

	public void endSafetyNet(RenderTarget mainTarget) {
		this.end(mainTarget, false);
	}

	private void end(RenderTarget mainTarget, boolean beforeHandSeam) {
		if (this.rtWindowOpen) {
			this.rtWindowOpen = false;
			if (!beforeHandSeam && VanillaRenderController.INSTANCE.wasWorldSkippedThisFrame()) {
				VanillaRenderController.INSTANCE.markMissedBeforeHandSeam();
				return;
			}
			boolean success = RtComposite.INSTANCE.composite(mainTarget.getColorTexture(), mainTarget.width, mainTarget.height);
			VanillaRenderController.INSTANCE.markRtCompositeResult(success);
		}
	}

	public void destroy() {
		this.rtWindowOpen = false;
	}
}
