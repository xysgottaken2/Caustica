package dev.xys.vulkanrt.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.xys.vulkanrt.geometry.*;
import dev.xys.vulkanrt.integration.DeviceNegotiation;
import dev.xys.vulkanrt.render.RtOptions;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.SectionPos;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Unique private static final ThreadLocal<Long> nativeVulkanRt$epoch = new ThreadLocal<>();
    @Inject(method = "compile", at = @At("HEAD"))
    private void nativeVulkanRt$begin(SectionPos pos, RenderSectionRegion region, VertexSorting sorting,
                                     SectionBufferBuilderPack builders, CallbackInfoReturnable<SectionCompiler.Results> ci) {
        if (RtOptions.ENABLED && RtOptions.CHUNKS && DeviceNegotiation.hasEnabledDevice()) nativeVulkanRt$epoch.set(GeometryInbox.epoch());
        else nativeVulkanRt$epoch.remove();
    }
    @Inject(method = "compile", at = @At("RETURN"))
    private void nativeVulkanRt$capture(SectionPos pos, RenderSectionRegion region, VertexSorting sorting,
                                       SectionBufferBuilderPack builders, CallbackInfoReturnable<SectionCompiler.Results> ci) {
        if (!RtOptions.ENABLED || !RtOptions.CHUNKS) return;
        Long epoch = nativeVulkanRt$epoch.get(); nativeVulkanRt$epoch.remove();
        if (epoch == null || epoch != GeometryInbox.epoch() || !DeviceNegotiation.hasEnabledDevice()) return;
        try {
            var mesh = ci.getReturnValue().renderedLayers.get(ChunkSectionLayer.SOLID);
            GeometryInbox.attachResult(ci.getReturnValue(), new GeometryInbox.Capture(epoch, pos.asLong(),
                    mesh == null ? null : ChunkGeometryConverter.convert(mesh)));
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger("native_vulkan_rt").warn("[RT] Cannot capture opaque section {}; keeping vanilla", pos, failure);
        }
    }
}
