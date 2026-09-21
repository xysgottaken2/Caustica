package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import java.util.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static dev.xys.vulkanrt.render.VulkanRayTracingContext.check;

/** Physical support is deliberately separate from DeviceNegotiation's enabled-device registry.
 * RUNTIME VERIFIED: NO. Ray query, descriptor indexing and shaderInt64 are queried but not required
 * by this minimal non-bindless pipeline (no 64-bit shader addresses). */
public record RayTracingCapabilities(Set<String> extensions, List<String> missing,
        boolean accelerationStructure, boolean rayTracingPipeline, boolean bufferDeviceAddress,
        boolean descriptorIndexing, boolean shaderInt64, boolean rayQuery,
        int handleSize, int handleAlignment, int baseAlignment, int maxGroupStride,
        int scratchAlignment, long maxPrimitiveCount, long maxInstanceCount, int maxDispatchInvocations) {

    public static final Set<String> REQUIRED_EXTENSIONS = Set.of(
            "VK_KHR_acceleration_structure", "VK_KHR_ray_tracing_pipeline", "VK_KHR_deferred_host_operations");

    public boolean supported() {
        return missing.isEmpty() && accelerationStructure && rayTracingPipeline && bufferDeviceAddress
                && extensions.containsAll(REQUIRED_EXTENSIONS);
    }

    public FeatureSet featuresToEnable() {
        if (!supported()) throw new IllegalStateException(String.join(", ", missing));
        return new FeatureSet("Native Vulkan RT", REQUIRED_EXTENSIONS, Set.of(
                new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceVulkan12Features.class), "bufferDeviceAddress"),
                new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class), "accelerationStructure"),
                new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class), "rayTracingPipeline")));
    }

    public static void logPhysicalDevice(VkPhysicalDevice physical) {
        var log = org.slf4j.LoggerFactory.getLogger("native_vulkan_rt");
        log.info("[RT] Vulkan instance acquired: 0x{} (borrowed)", Long.toHexString(physical.getInstance().address()));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            int version = properties.apiVersion();
            log.info("[RT] Physical device: {}; handle=0x{}; Vulkan {}.{}.{}; vendor=0x{}; device=0x{}",
                    properties.deviceNameString(), Long.toHexString(physical.address()),
                    VK_VERSION_MAJOR(version), VK_VERSION_MINOR(version), VK_VERSION_PATCH(version),
                    Integer.toHexString(properties.vendorID()), Integer.toHexString(properties.deviceID()));
        }
    }

    public void logSupport() {
        var log = org.slf4j.LoggerFactory.getLogger("native_vulkan_rt");
        for (String extension : new TreeSet<>(REQUIRED_EXTENSIONS))
            log.info("[RT] {}: {}", extension, extensions.contains(extension) ? "supported" : "MISSING");
        log.info("[RT] VK_KHR_buffer_device_address extension: {}; core Vulkan 1.2 is used instead",
                extensions.contains("VK_KHR_buffer_device_address") ? "supported" : "not advertised");
        log.info("[RT] Buffer device address feature: {}", bufferDeviceAddress ? "supported" : "MISSING");
        log.info("[RT] accelerationStructure feature: {}; rayTracingPipeline feature: {}",
                accelerationStructure ? "supported" : "MISSING", rayTracingPipeline ? "supported" : "MISSING");
        log.info("[RT] SPIR-V 1.4 / shader float controls / descriptor indexing dependencies: provided by Vulkan 1.2 core; bindless features not required");
        if (!missing.isEmpty()) log.warn("[RT] Missing requirements: {}", String.join(", ", missing));
    }

    private static Set<String> enumerateExtensions(VkPhysicalDevice physical) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.ints(0);
            for (int attempt = 0; attempt < 4; attempt++) {
                check(vkEnumerateDeviceExtensionProperties(physical, (String)null, count, null), "enumerate extension count");
                // Hundreds of extensions can exceed LWJGL's small thread-local stack.
                try (var properties = VkExtensionProperties.calloc(count.get(0))) {
                    int result = vkEnumerateDeviceExtensionProperties(physical, (String)null, count, properties);
                    if (result == VK_INCOMPLETE) continue;
                    check(result, "enumerate device extensions");
                    Set<String> extensions = new TreeSet<>();
                    for (int i = 0; i < count.get(0); i++) extensions.add(properties.get(i).extensionNameString());
                    return extensions;
                }
            }
        }
        throw new IllegalStateException("Device extension enumeration remained VK_INCOMPLETE after four attempts");
    }

    public static RayTracingCapabilities query(VkPhysicalDevice physical) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Set<String> extensions = enumerateExtensions(physical);
            List<String> missing = new ArrayList<>();
            for (String name : REQUIRED_EXTENSIONS) if (!extensions.contains(name)) missing.add(name);

            var base = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, base);
            boolean core12 = Integer.compareUnsigned(base.apiVersion(), VK_API_VERSION_1_2) >= 0;
            // Minecraft 26.3 requires 1.2: BDA, SPIR-V 1.4 and float controls are promoted to core.
            if (!core12) missing.add("Vulkan 1.2 (Minecraft 26.3 baseline)");
            var f2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            var f12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            var as = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack).sType$Default();
            var rt = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack).sType$Default();
            var rq = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack).sType$Default();
            if (core12) f2.pNext(f12);
            if (extensions.contains("VK_KHR_acceleration_structure")) { as.pNext(f2.pNext()); f2.pNext(as); }
            if (extensions.contains("VK_KHR_ray_tracing_pipeline")) { rt.pNext(f2.pNext()); f2.pNext(rt); }
            if (extensions.contains("VK_KHR_ray_query")) { rq.pNext(f2.pNext()); f2.pNext(rq); }
            vkGetPhysicalDeviceFeatures2(physical, f2);
            if (!as.accelerationStructure()) missing.add("accelerationStructure");
            if (!rt.rayTracingPipeline()) missing.add("rayTracingPipeline");
            if (!f12.bufferDeviceAddress()) missing.add("bufferDeviceAddress");
            if (as.accelerationStructure()) {
                var vertexFormat = VkFormatProperties.calloc(stack);
                vkGetPhysicalDeviceFormatProperties(physical, VK_FORMAT_R32G32B32_SFLOAT, vertexFormat);
                if ((vertexFormat.bufferFeatures() & KHRAccelerationStructure.VK_FORMAT_FEATURE_ACCELERATION_STRUCTURE_VERTEX_BUFFER_BIT_KHR) == 0)
                    missing.add("RGB32_FLOAT acceleration-structure vertex format");
            }
            var ap = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            var rp = VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
            var p2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            if (extensions.contains("VK_KHR_acceleration_structure")) { ap.pNext(p2.pNext()); p2.pNext(ap); }
            if (extensions.contains("VK_KHR_ray_tracing_pipeline")) { rp.pNext(p2.pNext()); p2.pNext(rp); }
            vkGetPhysicalDeviceProperties2(physical, p2);
            if (rt.rayTracingPipeline() && rp.maxRayRecursionDepth() < 1) missing.add("maxRayRecursionDepth >= 1");
            return new RayTracingCapabilities(Set.copyOf(extensions), List.copyOf(missing),
                    as.accelerationStructure(), rt.rayTracingPipeline(), f12.bufferDeviceAddress(),
                    f12.descriptorIndexing(), f2.features().shaderInt64(), rq.rayQuery(),
                    rp.shaderGroupHandleSize(), rp.shaderGroupHandleAlignment(), rp.shaderGroupBaseAlignment(),
                    rp.maxShaderGroupStride(), ap.minAccelerationStructureScratchOffsetAlignment(),
                    ap.maxPrimitiveCount(), ap.maxInstanceCount(), rp.maxRayDispatchInvocationCount());
        }
    }
}
