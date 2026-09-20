package dev.xys.vulkanrt.mixin;

import dev.xys.vulkanrt.geometry.*;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
public abstract class CompiledSectionMeshMixin implements CapturedMeshAccess {
    @Unique private GeometryInbox.Capture nativeVulkanRt$capture;
    @Inject(method = "<init>", at = @At("TAIL"))
    private void nativeVulkanRt$take(TranslucencyPointOfView view, SectionCompiler.Results results, long start, CallbackInfo ci) {
        nativeVulkanRt$capture = GeometryInbox.takeResult(results);
    }
    @Override public GeometryInbox.Capture nativeVulkanRt$capture() { var capture = nativeVulkanRt$capture; nativeVulkanRt$capture = null; return capture; }
}
