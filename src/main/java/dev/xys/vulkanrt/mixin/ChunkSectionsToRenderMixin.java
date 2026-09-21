package dev.xys.vulkanrt.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.xys.vulkanrt.render.RayTracingRenderer;
import dev.xys.vulkanrt.render.TerrainAtlasCapture;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkSectionsToRender.class)
public abstract class ChunkSectionsToRenderMixin {
    @Inject(method = "renderGroup", at = @At("HEAD"))
    private void nativeVulkanRt$atlas(ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler,
                                     GpuTextureView atlas, boolean wireframe, CallbackInfo ci) {
        if (RayTracingRenderer.chunkCaptureEnabled() && group == ChunkSectionLayerGroup.OPAQUE) {
            try { TerrainAtlasCapture.record(atlas,sampler); }
            catch (RuntimeException failure) { RayTracingRenderer.chunkCaptureFailed(failure); }
        }
    }
}
