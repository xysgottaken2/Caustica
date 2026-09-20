package dev.xys.vulkanrt.mixin;

import dev.xys.vulkanrt.render.RayTracingRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"))
    private void nativeVulkanRt$reload(CallbackInfo ci) { RayTracingRenderer.worldChanged(); }
}
