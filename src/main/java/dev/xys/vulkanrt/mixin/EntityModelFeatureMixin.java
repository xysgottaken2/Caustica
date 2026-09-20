package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
@Mixin(ModelFeatureRenderer.class)
public abstract class EntityModelFeatureMixin {
 @Inject(method="prepareModel",at=@At("HEAD")) private void nativeVulkanRt$begin(ModelFeatureRenderer.Submit<?> submit,CallbackInfo ci) { EntityCapture.enter(submit); }
 @Inject(method="prepareModel",at=@At("RETURN")) private void nativeVulkanRt$end(CallbackInfo ci) { EntityCapture.leave(); }
}
