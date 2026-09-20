package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.api.commands.GpuFence;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;

/** A single 8-byte GPU readback per triangle scene, not a per-frame copy or wait-idle.
 * The vanilla fence covers the submit containing AS builds, trace, readback and blit.
 * Pixel validation proves RT output, not swapchain presentation or general runtime correctness. */
public final class TriangleReadback implements AutoCloseable {
    private final GpuBuffer pixels;
    private final GpuFence fence;
    private final int width, height;
    private final long createdAt = System.nanoTime();
    private boolean complete, warned;

    public TriangleReadback(VulkanRayTracingContext context, int width, int height) {
        this.width = width; this.height = height;
        pixels = new GpuBuffer(context, 8, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true, false);
        try { fence = context.encoder().createFence(); }
        catch (RuntimeException | Error failure) { pixels.close(); throw failure; }
    }

    /** The caller has transitioned its RGBA8 image to TRANSFER_SRC_OPTIMAL. */
    public void record(VkCommandBuffer cmd, long image) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var regions = VkBufferImageCopy.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                var region = regions.get(i).bufferOffset(i * 4L);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.imageExtent().set(1, 1, 1);
            }
            regions.get(0).imageOffset().set(width / 2, height / 2, 0); // known closest hit
            regions.get(1).imageOffset().set(0, 0, 0); // known miss
            vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), regions);
        }
        VulkanRayTracingContext.memoryBarrier(cmd, VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,
                VK_PIPELINE_STAGE_2_HOST_BIT_KHR, VK_ACCESS_2_HOST_READ_BIT_KHR);
    }

    public void poll() {
        if (complete) return;
        if (!fence.awaitCompletion(0)) {
            if (!warned && System.nanoTime() - createdAt > 10_000_000_000L) {
                warned = true;
                LoggerFactory.getLogger("native_vulkan_rt").warn("[RT] Triangle proof still waiting for vanilla submit completion; no GPU result claimed");
            }
            return;
        }
        complete = true;
        var data = pixels.mapped();
        if (!matches(data, width, height))
            throw new IllegalStateException("Triangle GPU readback mismatch: hit=" + rgba(data, 0)
                    + ", miss=" + rgba(data, 4) + ", extent=" + width + "x" + height
                    + ". Check BLAS/TLAS, SBT, shaders and synchronization; not a successful triangle proof.");
        var log = LoggerFactory.getLogger("native_vulkan_rt");
        log.info("[RT] Test triangle rendered to RT image: fence completed and hit/miss pixels match; hit={}, miss={}", rgba(data, 0), rgba(data, 4));
        log.info("[RT] RT image blit to Minecraft main target completed in the same submission. Swapchain presentation not certified. RUNTIME NOT VERIFIED");
    }

    /** Reference for the fixed raygen camera, triangle vertices, closest-hit and miss shaders. */
    static int[] expectedHit(int width, int height) {
        double ndcX = ((width / 2 + 0.5) / width) * 2 - 1;
        double ndcY = ((height / 2 + 0.5) / height) * 2 - 1;
        double x = ndcX * width / height * (2.0 / 1.5);
        double y = -ndcY * (2.0 / 1.5);
        double[] bary = {(1 - y) / 4 - x / 2, (1 - y) / 4 + x / 2, (1 + y) / 2};
        return new int[]{unorm((0.12 + 0.88 * bary[0]) * 0.5), unorm((0.12 + 0.88 * bary[1]) * 0.5),
                unorm((0.12 + 0.88 * bary[2]) * 0.5), 255};
    }
    private static int unorm(double value) { return (int)Math.round(value * 255); }
    static boolean matches(ByteBuffer pixels, int width, int height) {
        if (width < 4 || height < 4 || pixels.remaining() < 8) return false;
        int[] hit = expectedHit(width, height), miss = {5, 6, 11, 255};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(Byte.toUnsignedInt(pixels.get(i)) - hit[i]) > 2
                    || Math.abs(Byte.toUnsignedInt(pixels.get(i + 4)) - miss[i]) > 2) return false;
        }
        return true;
    }
    private static String rgba(ByteBuffer bytes, int offset) {
        return Byte.toUnsignedInt(bytes.get(offset)) + "," + Byte.toUnsignedInt(bytes.get(offset + 1)) + ","
                + Byte.toUnsignedInt(bytes.get(offset + 2)) + "," + Byte.toUnsignedInt(bytes.get(offset + 3));
    }
    @Override public void close() { fence.close(); pixels.close(); }
}
