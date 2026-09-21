package dev.xys.vulkanrt.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
import dev.xys.vulkanrt.integration.DeviceNegotiation;
import dev.xys.vulkanrt.mixin.FrontendGpuDeviceAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;

/** Borrowed Minecraft handles. Never creates/destroys an instance, device, queue or command pool.
 * RUNTIME VERIFIED: NO. All calls and resource mutation must occur on the render thread. */
public final class VulkanRayTracingContext {
    private final VulkanDevice backend;
    private final RayTracingCapabilities capabilities;

    private VulkanRayTracingContext(VulkanDevice backend, RayTracingCapabilities capabilities) {
        this.backend = backend;
        this.capabilities = capabilities;
    }

    public static VulkanRayTracingContext borrow() {
        RenderSystem.assertOnRenderThread();
        var log = org.slf4j.LoggerFactory.getLogger("native_vulkan_rt");
        var frontend = RenderSystem.getDevice();
        log.info("[RT] Renderer device frontend: {}", frontend.getClass().getName());
        if (!(frontend instanceof FrontendGpuDeviceAccessor access))
            throw new IllegalStateException("FrontendGpuDeviceAccessor Mixin not applied to " + frontend.getClass().getName());
        var actualBackend = access.nativeVulkanRt$backend();
        log.info("[RT] Renderer backend: {}", actualBackend.getClass().getName());
        if (!(actualBackend instanceof VulkanDevice backend))
            throw new IllegalStateException("Active backend is not Vulkan: " + actualBackend.getClass().getName());
        var device = backend.vkDevice();
        log.info("[RT] Logical device acquired from RenderSystem: 0x{}", Long.toHexString(device.address()));
        RayTracingCapabilities enabled = DeviceNegotiation.enabledFor(device);
        if (enabled == null) {
            // Diagnose the real selected device even if the creation hook never ran.
            RayTracingCapabilities.logPhysicalDevice(device.getPhysicalDevice());
            RayTracingCapabilities.query(device.getPhysicalDevice()).logSupport();
            throw new IllegalStateException(DeviceNegotiation.rejectionFor(device));
        }
        var dispatch = device.getCapabilities();
        java.util.List<String> absent = new java.util.ArrayList<>();
        if (dispatch.vkCmdTraceRaysKHR == 0) absent.add("vkCmdTraceRaysKHR");
        if (dispatch.vkCreateRayTracingPipelinesKHR == 0) absent.add("vkCreateRayTracingPipelinesKHR");
        if (dispatch.vkGetRayTracingShaderGroupHandlesKHR == 0) absent.add("vkGetRayTracingShaderGroupHandlesKHR");
        if (dispatch.vkCreateAccelerationStructureKHR == 0) absent.add("vkCreateAccelerationStructureKHR");
        if (dispatch.vkCmdBuildAccelerationStructuresKHR == 0) absent.add("vkCmdBuildAccelerationStructuresKHR");
        if (dispatch.vkGetAccelerationStructureBuildSizesKHR == 0) absent.add("vkGetAccelerationStructureBuildSizesKHR");
        if (dispatch.vkGetAccelerationStructureDeviceAddressKHR == 0) absent.add("vkGetAccelerationStructureDeviceAddressKHR");
        if (dispatch.vkGetBufferDeviceAddress == 0) absent.add("vkGetBufferDeviceAddress (core 1.2)");
        if (dispatch.vkCmdPipelineBarrier2KHR == 0) absent.add("vkCmdPipelineBarrier2KHR (vanilla dependency)");
        if (!absent.isEmpty()) throw new IllegalStateException("Enabled device missing Vulkan dispatch: " + String.join(", ", absent));
        log.info("[RT] Borrowed graphics queue and encoder; required Vulkan entry points resolved");
        return new VulkanRayTracingContext(backend, enabled);
    }

    public VkInstance instance() { return physicalDevice().getInstance(); }
    public VkPhysicalDevice physicalDevice() { return device().getPhysicalDevice(); }
    public VkDevice device() { return backend.vkDevice(); }
    public VulkanDevice backend() { return backend; }
    public VulkanQueue graphicsQueue() { return backend.graphicsQueue(); }
    public VulkanQueue computeQueue() { return backend.computeQueue(); }
    public VulkanQueue transferQueue() { return backend.transferQueue(); }
    public VulkanCommandEncoder encoder() { return backend.createCommandEncoder(); }
    public RayTracingCapabilities capabilities() { return capabilities; }

    public VkCommandBuffer beginCommands() {
        RenderSystem.assertOnRenderThread();
        return encoder().allocateAndBeginTransientCommandBuffer();
    }

    /** Ownership transfers to vanilla only after the command buffer has been fully recorded. */
    public void enqueue(VkCommandBuffer commands) {
        check(vkEndCommandBuffer(commands), "vkEndCommandBuffer(RT)");
        encoder().execute(commands);
    }

    public void retire(AutoCloseable resource) {
        encoder().queueForDestroy(() -> {
            try { resource.close(); }
            catch (Exception failure) { throw new IllegalStateException("RT retirement failed", failure); }
        });
    }

    public static void memoryBarrier(VkCommandBuffer cmd, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkMemoryBarrier2.calloc(1, stack).sType$Default()
                    .srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier2KHR(cmd, VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(barrier));
        }
    }

    public static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new VulkanFailure(operation, result);
    }

    public static final class VulkanFailure extends RuntimeException {
        public final int result;
        public VulkanFailure(String operation, int result) {
            super(operation + " failed: VkResult=" + result);
            this.result = result;
        }
    }
}
