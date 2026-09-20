package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static dev.xys.vulkanrt.render.VulkanRayTracingContext.*;

/** Storage image owned by RT; copy/blit only, not a fullscreen graphics effect. RUNTIME VERIFIED: NO. */
public final class RtOutputImage implements AutoCloseable {
    public static final int FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    public final int width, height;
    private final VulkanRayTracingContext context;
    private long image, memory, view;
    private boolean initialized;

    public RtOutputImage(VulkanRayTracingContext context, int width, int height) {
        this.context = context; this.width = width; this.height = height;
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Zero image extent");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            requireFormat(FORMAT, VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT);
            var create = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                    .format(FORMAT).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            create.extent().set(width, height, 1);
            var result = stack.mallocLong(1);
            check(vkCreateImage(context.device(), create, null, result), "vkCreateImage(RT)"); image = result.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(context.device(), image, requirements);
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(requirements.size())
                    .memoryTypeIndex(GpuBuffer.findMemoryType(context, requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(vkAllocateMemory(context.device(), allocation, null, result), "vkAllocateMemory(image)"); memory = result.get(0);
            check(vkBindImageMemory(context.device(), image, memory, 0), "vkBindImageMemory");
            var viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(FORMAT);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(vkCreateImageView(context.device(), viewInfo, null, result), "vkCreateImageView(RT)"); view = result.get(0);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    public long view() { return view; }

    private void requireFormat(int format, int flags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(context.physicalDevice(), format, properties);
            if ((properties.optimalTilingFeatures() & flags) != flags) throw new IllegalStateException("Unsupported RT image format/features: " + format);
        }
    }
    public void validateTarget(VulkanGpuTexture target) {
        if ((target.usage() & GpuTexture.USAGE_COPY_DST) == 0) throw new IllegalStateException("Vanilla target lacks COPY_DST");
        requireFormat(VulkanConst.toVk(target.getFormat()), VK_FORMAT_FEATURE_BLIT_DST_BIT);
    }

    public void beginTrace(VkCommandBuffer cmd) {
        transition(cmd, initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                initialized ? VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR : VK_PIPELINE_STAGE_2_TOP_OF_PIPE_BIT_KHR,
                initialized ? VK_ACCESS_2_MEMORY_READ_BIT_KHR | VK_ACCESS_2_MEMORY_WRITE_BIT_KHR : 0,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
        initialized = true;
    }

    /** Minecraft images stay GENERAL. Restore our image to GENERAL too; vanilla owns swapchain/present. */
    public void copyToMainTarget(VkCommandBuffer cmd, VulkanGpuTexture target) {
        transition(cmd, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_READ_BIT_KHR);
        memoryBarrier(cmd, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, VK_ACCESS_2_MEMORY_READ_BIT_KHR | VK_ACCESS_2_MEMORY_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.srcOffsets(1).set(width, height, 1);
            blit.dstOffsets(1).set(target.getWidth(0), target.getHeight(0), 1);
            vkCmdBlitImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target.vkImage(), VK_IMAGE_LAYOUT_GENERAL, blit, VK_FILTER_NEAREST);
        }
        memoryBarrier(cmd, VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, VK_ACCESS_2_MEMORY_READ_BIT_KHR | VK_ACCESS_2_MEMORY_WRITE_BIT_KHR);
        transition(cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_READ_BIT_KHR,
                VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR, VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
    }

    private void transition(VkCommandBuffer cmd, int oldLayout, int newLayout, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var barrier = VkImageMemoryBarrier2.calloc(1, stack).sType$Default().image(image).oldLayout(oldLayout).newLayout(newLayout)
                    .srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier2KHR(cmd, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(barrier));
        }
    }
    @Override public void close() {
        if (view != 0) { vkDestroyImageView(context.device(), view, null); view = 0; }
        if (image != 0) { vkDestroyImage(context.device(), image, null); image = 0; }
        if (memory != 0) { vkFreeMemory(context.device(), memory, null); memory = 0; }
    }
}
