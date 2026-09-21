package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityDispatcherMixin {
 @Inject(method="submit",at=@At("HEAD"))
 private void nativeVulkanRt$begin(EntityRenderState state,CameraRenderState camera,double x,double y,double z,PoseStack pose,SubmitNodeCollector output,CallbackInfo ci) { EntityCapture.beginEntity(state); }
 @Inject(method="submit",at=@At("RETURN"))
 private void nativeVulkanRt$end(CallbackInfo ci) { EntityCapture.endEntity(); }
}
