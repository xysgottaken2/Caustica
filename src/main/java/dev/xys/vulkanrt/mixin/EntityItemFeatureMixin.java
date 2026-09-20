package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.feature.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.resources.model.geometry.BakedQuad;
@Mixin(ItemFeatureRenderer.class)
public abstract class EntityItemFeatureMixin {
 @Inject(method="prepareSubmit",at=@At("HEAD")) private void nativeVulkanRt$begin(ItemFeatureRenderer.Submit submit,CallbackInfo ci) { EntityCapture.enter(submit);
  if(EntityCapture.collecting() && submit.foilType()!=net.minecraft.client.renderer.item.ItemStackRenderState.FoilType.NONE)
   EntityCapture.reject("UNSUPPORTED_ITEM_FOIL:"+submit.foilType());
 }
 @Inject(method="prepareSubmit",at=@At("RETURN")) private void nativeVulkanRt$end(CallbackInfo ci) { EntityCapture.leave(); }
 @Redirect(method="prepareMainSubmit",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
 private void nativeVulkanRt$quad(VertexConsumer b,PoseStack.Pose p,BakedQuad q,QuadInstance i) { EntityCapture.quad(b,p,q,i); }
}
