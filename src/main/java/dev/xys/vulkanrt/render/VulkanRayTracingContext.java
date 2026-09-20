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
        if (!(RenderSystem.getDevice() instanceof FrontendGpuDeviceAccessor access)
                || !(access.nativeVulkanRt$backend() instanceof VulkanDevice backend)) return null;
        RayTracingCapabilities enabled = DeviceNegotiation.enabledFor(backend.vkDevice());
        if (enabled == null || !enabled.supported()) return null;
        var dispatch = backend.vkDevice().getCapabilities();
        if (dispatch.vkCmdTraceRaysKHR == 0 || dispatch.vkCreateRayTracingPipelinesKHR == 0
                || dispatch.vkCmdBuildAccelerationStructuresKHR == 0) return null;
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
