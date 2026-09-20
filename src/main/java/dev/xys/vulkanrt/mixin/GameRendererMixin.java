package dev.xys.vulkanrt.mixin;

import dev.xys.vulkanrt.render.RayTracingRenderer;
import dev.xys.vulkanrt.render.ViewmodelCapture;
import dev.xys.vulkanrt.integration.RtHookAudit;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Triangle pass runs after world post effects, before GUI; no camera/projection prerequisite. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin implements RtHookAudit.FrameHook {
    @Inject(method = "render()V", at = @At("HEAD"))
    private void nativeVulkanRt$beginFrame(CallbackInfo ci) { RayTracingRenderer.beginFrame(); }

    /** 26.3's separate hand pass: keep viewmodel capture active only around vanilla hand preparation/draw. */
    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void nativeVulkanRt$beginViewmodel(CallbackInfo ci) { ViewmodelCapture.begin(); }

    @Inject(method = "renderItemInHand", at = @At("RETURN"))
    private void nativeVulkanRt$endViewmodel(CallbackInfo ci) { ViewmodelCapture.end(); }

    @ModifyArg(method = "renderLevel()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0)
    private Matrix4f nativeVulkanRt$projection(Matrix4f matrix) { RayTracingRenderer.captureProjection(matrix); return matrix; }

    @Inject(method = "render()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void nativeVulkanRt$render(CallbackInfo ci) { RayTracingRenderer.render((GameRenderer)(Object)this); }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void nativeVulkanRt$worldChanged(CallbackInfo ci) { RayTracingRenderer.worldChanged(); }
}
