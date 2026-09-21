package dev.xys.vulkanrt.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK12.*;
import static dev.xys.vulkanrt.render.VulkanRayTracingContext.check;

/** Small dedicated allocations for bring-up. Raw Vulkan allocation permits BDA without modifying
 * Minecraft's VMA allocator (which lacks the BDA flag). No separate allocator/device is created.
 * RUNTIME VERIFIED: NO. Never overwrite a mapped buffer while any submitted command can read it. */
public final class GpuBuffer implements AutoCloseable {
    private final VkDevice device;
    public final long size;
    private long buffer, memory, mapped, address;

    public GpuBuffer(VulkanRayTracingContext context, long size, int usage, boolean hostVisible, boolean bda) {
        if (size <= 0) throw new IllegalArgumentException("Empty GPU allocation");
        this.device = context.device(); this.size = size;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var out = stack.mallocLong(1);
            var create = VkBufferCreateInfo.calloc(stack).sType$Default().size(size)
                    .usage(usage | (bda ? VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT : 0)).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(device, create, null, out), "vkCreateBuffer"); buffer = out.get(0);
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            int flags = hostVisible ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            int type = findMemoryType(context, requirements.memoryTypeBits(), flags);
            var allocation = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(requirements.size()).memoryTypeIndex(type);
            if (bda) allocation.pNext(VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default().flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT));
            check(vkAllocateMemory(device, allocation, null, out), "vkAllocateMemory(buffer)"); memory = out.get(0);
            check(vkBindBufferMemory(device, buffer, memory, 0), "vkBindBufferMemory");
            if (hostVisible) {
                var pointer = stack.mallocPointer(1);
                check(vkMapMemory(device, memory, 0, size, 0, pointer), "vkMapMemory"); mapped = pointer.get(0);
            }
            if (bda) {
                address = vkGetBufferDeviceAddress(device, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
                if (address == 0) throw new IllegalStateException("vkGetBufferDeviceAddress returned zero");
            }
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    public long handle() { return buffer; }
    public long address() { if (address == 0) throw new IllegalStateException("Buffer has no device address"); return address; }
    public ByteBuffer mapped() {
        if (mapped == 0) throw new IllegalStateException("Not mapped");
        return MemoryUtil.memByteBuffer(mapped, Math.toIntExact(size));
    }

    public static int findMemoryType(VulkanRayTracingContext context, int typeBits, int flags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(context.physicalDevice(), properties);
            for (int i = 0; i < properties.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) != 0 && (properties.memoryTypes(i).propertyFlags() & flags) == flags) return i;
            }
        }
        throw new IllegalStateException("No compatible Vulkan memory type, flags=" + flags);
    }

    public static GpuBuffer upload(VulkanRayTracingContext context, CommandBatch batch, ByteBuffer bytes, int usage) {
        GpuBuffer dst = new GpuBuffer(context, bytes.remaining(), usage | VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, true);
        try {
            GpuBuffer staging = batch.temporary(new GpuBuffer(context, bytes.remaining(), VK_BUFFER_USAGE_TRANSFER_SRC_BIT, true, false));
            staging.mapped().put(bytes.duplicate());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdCopyBuffer(batch.commands, staging.handle(), dst.handle(), VkBufferCopy.calloc(1, stack).size(bytes.remaining()));
            }
            return dst;
        } catch (RuntimeException | Error failure) { dst.close(); throw failure; }
    }

    public static long alignUp(long value, long alignment) {
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) throw new IllegalArgumentException("Alignment must be power-of-two");
        return Math.addExact(value, alignment - 1) & -alignment;
    }

    @Override public void close() {
        if (mapped != 0) { vkUnmapMemory(device, memory); mapped = 0; }
        if (buffer != 0) { vkDestroyBuffer(device, buffer, null); buffer = 0; }
        if (memory != 0) { vkFreeMemory(device, memory, null); memory = 0; }
        address = 0;
    }
}
