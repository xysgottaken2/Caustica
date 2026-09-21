package dev.xys.vulkanrt.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.xys.vulkanrt.render.ViewmodelCapture;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact 26.3 hand emission boundary: state -> SubmitNodeCollector. */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void nativeVulkanRt$state(float partialTicks, PoseStack poseStack, SubmitNodeCollector collector,
                                      PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state,
                                      CallbackInfo ci) {
        ViewmodelCapture.state(state);
    }
}
