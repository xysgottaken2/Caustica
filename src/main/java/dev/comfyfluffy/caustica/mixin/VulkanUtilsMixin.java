package dev.comfyfluffy.caustica.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Add VK_EXT_device_fault details to vanilla's existing device-loss crash path. */
@Mixin(VulkanUtils.class)
public abstract class VulkanUtilsMixin {
    @Inject(method = "crashIfFailure(Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;ILjava/lang/String;)V",
            at = @At("HEAD"))
    private static void caustica$reportDeviceFault(VulkanDevice device, int result, String message,
                                                    CallbackInfo ci) {
        if (result == VK10.VK_ERROR_DEVICE_LOST) {
            VulkanDiagnostics.reportDeviceLost(device, message);
        }
    }
}
