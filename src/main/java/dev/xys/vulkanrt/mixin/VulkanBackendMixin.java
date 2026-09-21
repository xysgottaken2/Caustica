package dev.xys.vulkanrt.mixin;

import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.xys.vulkanrt.integration.DeviceNegotiation;
import dev.xys.vulkanrt.render.RayTracingCapabilities;
import dev.xys.vulkanrt.render.RtOptions;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.xys.vulkanrt.integration.RtHookAudit;

/** Exact 26.3 FeatureSet helper; the Collection/Set overload from Caustica 26.2 is gone.
 * RUNTIME VERIFIED: NO. No additional logical device: the successful result IS Minecraft's device. */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin implements RtHookAudit.DeviceHook {
    @Inject(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;", at = @At("HEAD"))
    private void nativeVulkanRt$deviceHook(CallbackInfoReturnable<GpuDevice> cir) {
        if (RtOptions.ENABLED) LoggerFactory.getLogger("native_vulkan_rt").info("[RT] VulkanBackend.createDevice hook reached BEFORE logical-device creation");
    }
    @Invoker("createDevice")
    public static VkDevice nativeVulkanRt$createDevice(FeatureSet features, VulkanPhysicalDevice physical)
            throws BackendCreationException { throw new AssertionError("Mixin invoker was not transformed"); }

    @Redirect(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"))
    private VkDevice nativeVulkanRt$negotiate(FeatureSet vanilla, VulkanPhysicalDevice physical) throws BackendCreationException {
        var log = LoggerFactory.getLogger("native_vulkan_rt");
        if (!RtOptions.ENABLED) return nativeVulkanRt$createDevice(vanilla, physical);
        String stage = "query physical device";
        String rejection;
        try {
            RayTracingCapabilities.logPhysicalDevice(physical.vkPhysicalDevice());
            var caps = RayTracingCapabilities.query(physical.vkPhysicalDevice());
            caps.logSupport();
            if (caps.supported()) {
                stage = "vkCreateDevice with RT FeatureSet (Minecraft helper)";
                log.info("[RT] Enabling AS, RT pipeline and core Vulkan 1.2 bufferDeviceAddress before vkCreateDevice");
                var device = nativeVulkanRt$createDevice(vanilla.composite(caps.featuresToEnable()), physical);
                DeviceNegotiation.enabled(device, caps);
                log.info("[RT] Logical device acquired: 0x{}; RT features enabled on Minecraft's device", Long.toHexString(device.address()));
                return device;
            }
            rejection = "Missing physical-device requirements: " + String.join(", ", caps.missing());
            log.warn("[RT] {}", rejection);
        } catch (BackendCreationException | RuntimeException failure) {
            rejection = stage + ": " + failure;
            log.error("[RT] Integration failed at {}; retrying vanilla device requirements", stage, failure);
        }
        // No successful RT device exists on this path. Do not create a second live device.
        var device = nativeVulkanRt$createDevice(vanilla, physical);
        DeviceNegotiation.rejected(device, rejection);
        return device;
    }
}
