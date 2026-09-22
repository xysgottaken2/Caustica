package dev.comfyfluffy.caustica.mixin;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void caustica$cancelVanillaWorld(
			GraphicsResourceAllocator resourceAllocator,
			boolean renderOutline,
			CameraRenderState cameraState,
			GpuBufferSlice terrainFog,
			Vector4f fogColor,
			boolean shouldRenderSky,
			boolean consistentDepthRequired,
			CallbackInfo ci) {
		Runnable playerCompiledSectionCallback = this.levelRenderState.playerCompiledSectionCallback;
		boolean waitingForRtPlayerSection = false;
		if (VanillaRenderController.rtRuntimeWorkRequested() && playerCompiledSectionCallback != null) {
			if (RtTerrain.isSectionReady(cameraState.blockPos)) {
				playerCompiledSectionCallback.run();
				VanillaRenderController.INSTANCE.markRtPlayerSectionReady();
			} else {
				waitingForRtPlayerSection = true;
			}
		}

		if (!VanillaRenderController.INSTANCE.shouldCancelLevelRenderer(waitingForRtPlayerSection)) {
			return;
		}

		VanillaRenderController.INSTANCE.markWorldSkipped();
		ci.cancel();
	}
}
