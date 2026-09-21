package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.geom.ModelPart;
import java.util.List;
@Mixin(ModelPart.class)
public abstract class EntityModelPartMixin {
 @Shadow @Final private List<ModelPart.Cube> cubes;
 @Inject(method="compile",at=@At("HEAD"))
 private void nativeVulkanRt$begin(PoseStack.Pose pose,VertexConsumer builder,int light,int overlay,int color,CallbackInfo ci) {
  if(!EntityCapture.collecting()) return;
  EntityCapture.beginPiece(builder,List.copyOf(cubes),pose,EntityCapture.Colors.uniform(color),overlay);
 }
 @Inject(method="compile",at=@At("RETURN")) private void nativeVulkanRt$end(CallbackInfo ci) { EntityCapture.endPiece(); }
}
