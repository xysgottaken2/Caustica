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

/** Exact 26.3 FeatureSet helper; the Collection/Set overload from Caustica 26.2 is gone.
 * RUNTIME VERIFIED: NO. No additional logical device: the successful result IS Minecraft's device. */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
    @Invoker("createDevice")
    public static VkDevice nativeVulkanRt$createDevice(FeatureSet features, VulkanPhysicalDevice physical)
            throws BackendCreationException { throw new AssertionError("Mixin invoker was not transformed"); }

    @Redirect(method = "createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;",
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;"))
    private VkDevice nativeVulkanRt$negotiate(FeatureSet vanilla, VulkanPhysicalDevice physical) throws BackendCreationException {
        var log = LoggerFactory.getLogger("native_vulkan_rt");
        if (RtOptions.ENABLED) {
            try {
                var caps = RayTracingCapabilities.query(physical.vkPhysicalDevice());
                log.info("[RT] AS={}, pipeline={}, BDA={}, descriptorIndexing={}, shaderInt64={}, rayQuery={}",
                        caps.accelerationStructure(), caps.rayTracingPipeline(), caps.bufferDeviceAddress(),
                        caps.descriptorIndexing(), caps.shaderInt64(), caps.rayQuery());
                if (caps.supported()) {
                    var device = nativeVulkanRt$createDevice(vanilla.composite(caps.featuresToEnable()), physical);
                    DeviceNegotiation.enabled(device, caps);
                    log.info("[RT] Features enabled on Minecraft's VkDevice; RUNTIME VERIFIED: NO");
                    return device;
                }
                log.warn("[RT] Hardware ray tracing unavailable: {}", caps.missing());
            } catch (BackendCreationException | RuntimeException failure) {
                // vkCreateDevice failure produces no live device. Retry vanilla requirements unchanged.
                log.warn("[RT] RT device negotiation failed; falling back to vanilla Vulkan", failure);
            }
        }
        return nativeVulkanRt$createDevice(vanilla, physical);
    }
}
