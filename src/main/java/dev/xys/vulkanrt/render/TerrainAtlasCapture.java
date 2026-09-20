package dev.xys.vulkanrt.render;

import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;

/** Borrow the actual Sampler0 arguments of the vanilla OPAQUE terrain draw. Never close them. */
public final class TerrainAtlasCapture {
    public record Atlas(VulkanGpuTextureView view, VulkanGpuSampler sampler) {
        public boolean live() {
            return !view.isClosed() && !view.texture().isClosed() && !sampler.isClosed()
                    && view.vkImageView()!=0 && sampler.vkSampler()!=0;
        }
    }
    private static Atlas current, lastLogged;
    public static void beginFrame() { current = null; }
    public static void record(GpuTextureView view, GpuSampler sampler) {
        if (!(view instanceof VulkanGpuTextureView vkView) || !(sampler instanceof VulkanGpuSampler vkSampler))
            throw new IllegalArgumentException("SOLID Sampler0 is not a Vulkan atlas/sampler");
        current = new Atlas(vkView, vkSampler);
        if (!current.live()) throw new IllegalStateException("SOLID atlas/sampler is closed or has a zero handle");
        if (!current.equals(lastLogged)) {
            lastLogged = current;
            org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info(
                    "[RT] Terrain material/texture=minecraft:textures/atlas/blocks.png (vanilla OPAQUE Sampler0); view=0x{} sampler=0x{} size={}x{}; normalized UV0, Color tint, explicit LOD=0; GPU hit sampling pending",
                    Long.toHexString(vkView.vkImageView()),Long.toHexString(vkSampler.vkSampler()),vkView.getWidth(0),vkView.getHeight(0));
        }
    }
    public static Atlas current() { return current != null && current.live() ? current : null; }
    private TerrainAtlasCapture() {}
}
