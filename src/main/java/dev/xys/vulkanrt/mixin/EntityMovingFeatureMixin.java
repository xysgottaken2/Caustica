package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.feature.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.resources.model.geometry.BakedQuad;
@Mixin(MovingBlockFeatureRenderer.class)
public abstract class EntityMovingFeatureMixin {
 @Redirect(method="buildGroup",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/feature/MovingBlockFeatureRenderer$Submit;movingBlockRenderState()Lnet/minecraft/client/renderer/block/MovingBlockRenderState;"))
 private net.minecraft.client.renderer.block.MovingBlockRenderState nativeVulkanRt$begin(MovingBlockFeatureRenderer.Submit submit) { EntityCapture.enter(submit);return submit.movingBlockRenderState(); }
 @Inject(method="buildGroup",at=@At("RETURN")) private void nativeVulkanRt$end(CallbackInfo ci) { EntityCapture.leave(); }
 @Redirect(method="putBakedQuad",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
 private void nativeVulkanRt$quad(VertexConsumer b,PoseStack.Pose p,BakedQuad q,QuadInstance i) { EntityCapture.quad(b,p,q,i); }
}
