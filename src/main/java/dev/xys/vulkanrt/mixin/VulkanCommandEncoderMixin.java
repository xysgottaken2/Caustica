package dev.xys.vulkanrt.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import dev.xys.vulkanrt.integration.RtHookAudit;
import dev.xys.vulkanrt.render.RayTracingRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin implements RtHookAudit.SubmitHook {
    @Inject(method = "submit()V", at = @At("RETURN"))
    private void nativeVulkanRt$submitted(CallbackInfo ci) {
        RayTracingRenderer.afterSubmit((VulkanCommandEncoder)(Object)this);
    }
}
