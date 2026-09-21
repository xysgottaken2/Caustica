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
    private static Atlas current, lastLogged, lastBound;
    public static void beginFrame() { current = null; }
    public static void record(GpuTextureView view, GpuSampler sampler) {
        if (!(view instanceof VulkanGpuTextureView vkView) || !(sampler instanceof VulkanGpuSampler vkSampler))
            throw new IllegalArgumentException("SOLID Sampler0 is not a Vulkan atlas/sampler");
        current = new Atlas(vkView, vkSampler);
        if (!current.live()) throw new IllegalStateException("SOLID atlas/sampler is closed or has a zero handle");
        if (!current.equals(lastLogged)) {
            lastLogged = current;
            var texture=vkView.texture();
            var log=org.slf4j.LoggerFactory.getLogger("native_vulkan_rt");
            log.info("[RT][texture-quality] source=vanilla OPAQUE Sampler0 label={} image=0x{} view=0x{} format={} textureMip0={}x{} textureMips={} viewBaseMip={} viewMips={} viewLod0={}x{}; borrowed=true copy=NO",
                    texture.getLabel(),Long.toHexString(texture.vkImage()),Long.toHexString(vkView.vkImageView()),texture.getFormat(),
                    texture.getWidth(0),texture.getHeight(0),texture.getMipLevels(),vkView.baseMipLevel(),vkView.mipLevels(),vkView.getWidth(0),vkView.getHeight(0));
            log.info("[RT][texture-quality] sampler=0x{} min={} mag={} addressU={} addressV={} anisotropy={} maxLod={}; RT sampling={} explicitViewLod=0; TEXEL bypasses filtering; LINEAR reproduces 0.5.0 textureLod with the captured sampler",
                    Long.toHexString(vkSampler.vkSampler()),vkSampler.getMinFilter(),vkSampler.getMagFilter(),vkSampler.getAddressModeU(),vkSampler.getAddressModeV(),
                    vkSampler.getMaxAnisotropy(),vkSampler.getMaxLod(),ChunkTextureSampling.configured());
            if(vkView.baseMipLevel()!=0) log.warn("[RT][texture-quality] NONZERO_VIEW_BASE_MIP: view LOD 0 is image mip {}; borrowing exactly the vanilla view, not a full-resolution replacement", vkView.baseMipLevel());
        }
    }
    /** Called at the actual descriptor write, not an assertion based only on the resource name. */
    public static void binding(Atlas bound) {
        var vanilla=current();
        boolean sameView=vanilla!=null && bound.view()==vanilla.view();
        boolean sameSampler=vanilla!=null && bound.sampler()==vanilla.sampler();
        boolean sameImage=vanilla!=null && bound.view().texture()==vanilla.view().texture()
                && bound.view().texture().vkImage()==vanilla.view().texture().vkImage();
        if(!sameView || !sameSampler || !sameImage) throw new IllegalStateException("RT atlas binding is not this frame's vanilla SOLID view/sampler");
        if(!bound.equals(lastBound)) {
            lastBound=bound;
            org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info(
                    "[RT][texture-quality] descriptor[3]: sameVanillaImage={} sameVanillaView={} sameVanillaSampler={} image=0x{} view=0x{} sampler=0x{}; no simplified atlas",
                    sameImage,sameView,sameSampler,Long.toHexString(bound.view().texture().vkImage()),Long.toHexString(bound.view().vkImageView()),Long.toHexString(bound.sampler().vkSampler()));
        }
    }
    public static Atlas current() { return current != null && current.live() ? current : null; }
    private TerrainAtlasCapture() {}
}
