package dev.xys.vulkanrt.mixin;

import dev.xys.vulkanrt.geometry.TerrainDrawCapture;
import dev.xys.vulkanrt.render.RayTracingRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow @Final private GameRenderer gameRenderer;
    @Shadow private SectionRenderDispatcher sectionRenderDispatcher;
    @Unique private SectionRenderDispatcher.RenderSection nativeVulkanRt$drawSection;

    @Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"))
    private void nativeVulkanRt$reload(CallbackInfo ci) { RayTracingRenderer.worldChanged(); }

    @Inject(method = "extractSectionDrawGroups(ZLjava/util/List;Ljava/util/Map;)I", at = @At("HEAD"))
    private void nativeVulkanRt$beginDraws(CallbackInfoReturnable<Integer> ci) {
        nativeVulkanRt$drawSection = null;
        if (RayTracingRenderer.chunkCaptureEnabled()) {
            try {
                var pos = gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
                TerrainDrawCapture.begin((LevelRenderer)(Object)this, sectionRenderDispatcher, pos.x, pos.y, pos.z);
            } catch (RuntimeException failure) { RayTracingRenderer.chunkCaptureFailed(failure); }
        }
    }
    // Pair the actual owner and mesh without relying on LVT slot numbers or inferred @Local indexes.
    @Redirect(method = "extractSectionDrawGroups(ZLjava/util/List;Ljava/util/Map;)I", require = 1, allow = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getSectionMesh()Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
    private SectionMesh nativeVulkanRt$drawMesh(SectionRenderDispatcher.RenderSection section) {
        if (RayTracingRenderer.chunkCaptureEnabled()) { nativeVulkanRt$drawSection = section; TerrainDrawCapture.sawSection(); }
        return section.getSectionMesh();
    }
    @Redirect(method = "extractSectionDrawGroups(ZLjava/util/List;Ljava/util/Map;)I", require = 1, allow = 1,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;getRenderSectionSlice(Lnet/minecraft/client/renderer/chunk/SectionMesh;Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSectionBufferSlice;"))
    private SectionRenderDispatcher.RenderSectionBufferSlice nativeVulkanRt$drawSlice(
            SectionRenderDispatcher dispatcher, SectionMesh mesh, ChunkSectionLayer layer) {
        var slice = dispatcher.getRenderSectionSlice(mesh, layer);
        if (RayTracingRenderer.chunkCaptureEnabled()) {
            try { TerrainDrawCapture.observe(dispatcher, nativeVulkanRt$drawSection, mesh, layer, slice); }
            catch (RuntimeException failure) { RayTracingRenderer.chunkCaptureFailed(failure); }
        }
        return slice; // Exact same object returned to vanilla's draw/MDI builder.
    }
    @Inject(method = "extractSectionDrawGroups(ZLjava/util/List;Ljava/util/Map;)I", at = @At("RETURN"))
    private void nativeVulkanRt$finishDraws(CallbackInfoReturnable<Integer> ci) {
        nativeVulkanRt$drawSection = null;
        if (RayTracingRenderer.chunkCaptureEnabled()) {
            TerrainDrawCapture.finish((LevelRenderer)(Object)this);
            RayTracingRenderer.prepareChunkGeometry((LevelRenderer)(Object)this, gameRenderer);
        }
    }
}
