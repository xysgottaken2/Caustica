package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Redirect the vanilla GUI/HUD into a transparent overlay target ({@code RtUiOverlay}), regardless of HDR
 * mode — in SDR the composite-back reproduces vanilla exactly, and {@code RtWorldOverlay}'s world-space
 * overlay composite runs at that same seam, so it needs to fire every frame. {@code GuiRenderer.draw} fetches the
 * destination via {@code gameRenderer.mainRenderTarget()} once and uses it for every GUI draw range (and the
 * after-blur depth clear), so redirecting that single expression routes all GUI rendering into the overlay.
 * The overlay is composited back over the world after {@code GuiRenderer.render} returns (see {@code
 * GameRendererMixin}) — its {@code draw} TAIL did not fire on in-game HUD frames. Post chains are
 * unaffected — they operate on the real main target.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@ModifyExpressionValue(
			method = "draw",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget caustica$redirectGuiToOverlay(RenderTarget original) {
		if (original != null && RtUiOverlay.enabled()) {
			return RtUiOverlay.beginAndRedirect(original);
		}
		return original;
	}
}
