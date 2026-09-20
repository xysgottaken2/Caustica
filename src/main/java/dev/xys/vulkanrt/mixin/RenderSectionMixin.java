package dev.xys.vulkanrt.mixin;

import dev.xys.vulkanrt.geometry.*;
import dev.xys.vulkanrt.render.RtOptions;
import net.minecraft.client.renderer.chunk.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Publish only the mesh accepted by vanilla's upload-completion callbacks, not speculative builds. */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public abstract class RenderSectionMixin {
    @Shadow public abstract long getSectionNode();
    @Inject(method = "setSectionMesh", at = @At("RETURN"))
    private void nativeVulkanRt$publish(SectionMesh mesh, CallbackInfoReturnable<SectionMesh> ci) {
        if (RtOptions.ENABLED && RtOptions.CHUNKS)
            GeometryInbox.publish(this, getSectionNode(), mesh instanceof CapturedMeshAccess access ? access.nativeVulkanRt$capture() : null);
    }
    @Inject(method = "reset", at = @At("HEAD"))
    private void nativeVulkanRt$remove(CallbackInfo ci) {
        if (RtOptions.ENABLED && RtOptions.CHUNKS) GeometryInbox.remove(this, getSectionNode());
    }
}
