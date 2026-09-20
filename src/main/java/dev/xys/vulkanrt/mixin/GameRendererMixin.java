package dev.xys.vulkanrt.mixin;

import dev.xys.vulkanrt.render.RayTracingRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 26.3 renderLevel() has no DeltaTracker parameter. Exact projection and pre-HUD seam. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render()V", at = @At("HEAD"))
    private void nativeVulkanRt$beginFrame(CallbackInfo ci) { RayTracingRenderer.beginFrame(); }

    @ModifyArg(method = "renderLevel()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0)
    private Matrix4f nativeVulkanRt$projection(Matrix4f matrix) { RayTracingRenderer.captureProjection(matrix); return matrix; }

    @Inject(method = "renderLevel()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
    private void nativeVulkanRt$render(CallbackInfo ci) { RayTracingRenderer.render((GameRenderer)(Object)this); }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void nativeVulkanRt$worldChanged(CallbackInfo ci) { RayTracingRenderer.worldChanged(); }
}
