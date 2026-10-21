package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.rt.entity.RtViewmodel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses the vanilla raster viewmodel for exactly the submission the ray-traced hand replaced.
 *
 * <p>Caustica draws the world into its own image and composites it before the hand pass, so a hand that is
 * now part of the traced scene must not also be rastered over it — that would double-draw the arm, with a
 * flat, un-lit copy sitting on top of the traced one. Cancelling here (rather than the whole
 * {@code renderItemInHand} pass) is what keeps everything else that pass does intact: its render-pass
 * view redirection into the UI overlay, the screen-effect and depth seams around it, and any mod that
 * submits into the same collector for its own HUD preview all keep running.
 *
 * <p>The cancellation is one-shot and conditional. {@link RtViewmodel} sets the latch only after a
 * successful capture, {@code render()} HEAD clears it every frame, and the "was this mesh really instanced
 * into this frame's TLAS" check inside {@code shouldSuppressVanillaSubmission()} means a frame where the
 * composite bailed (terrain not resident, a material-epoch boundary, a pipeline rebuild) keeps vanilla's
 * hand. There is no window in which neither renderer draws the viewmodel.
 *
 * <p>Our own capture calls the very method this cancels, from the before-hand seam. It never trips the
 * latch, because the latch is only set once that call has returned — so the re-entrant submission is
 * collected normally instead of being skipped.
 */
@Mixin(net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
	@Inject(method = "submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;"
			+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
			+ "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;"
			+ "Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V",
			at = @At("HEAD"), cancellable = true)
	private void caustica$skipViewmodelAlreadyTraced(float partialTick, PoseStack poseStack,
			SubmitNodeCollector collector, PlayerRenderState playerState,
			FirstPersonHandsAndItemsRenderState handsAndItems, CallbackInfo ci) {
		if (RtViewmodel.INSTANCE.shouldSuppressVanillaSubmission()) {
			ci.cancel();
		}
	}
}
