package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.feature.*;
@Mixin({ModelFeatureRenderer.Submit.class,ItemFeatureRenderer.Submit.class,MovingBlockFeatureRenderer.Submit.class})
public abstract class EntitySubmitMixin {
 @Inject(method="<init>",at=@At("RETURN")) private void nativeVulkanRt$tag(CallbackInfo ci) { EntityCapture.tag(this); }
}
