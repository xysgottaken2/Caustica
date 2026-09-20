package dev.xys.vulkanrt.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.xys.vulkanrt.integration.RtHookAudit;
import dev.xys.vulkanrt.render.RayTracingRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin implements RtHookAudit.InitHook {
    @Inject(method = "initRenderer(Lcom/mojang/renderpearl/api/device/GpuDevice;)V", at = @At("RETURN"))
    private static void nativeVulkanRt$deviceReady(CallbackInfo ci) { RayTracingRenderer.deviceReady(); }
}
