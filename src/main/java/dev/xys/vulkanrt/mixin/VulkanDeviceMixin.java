package dev.xys.vulkanrt.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.xys.vulkanrt.integration.DeviceNegotiation;
import dev.xys.vulkanrt.render.RayTracingRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void nativeVulkanRt$close(CallbackInfo ci) {
        var device = (VulkanDevice)(Object)this;
        RayTracingRenderer.deviceClosing(device);
        DeviceNegotiation.forget(device.vkDevice());
    }
}
