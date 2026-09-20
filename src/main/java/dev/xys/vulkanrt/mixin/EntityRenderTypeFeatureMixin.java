package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.vertex.VertexConsumer;
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class EntityRenderTypeFeatureMixin {
 @Inject(method="getVertexBuilder",at=@At("RETURN")) private void nativeVulkanRt$builder(RenderType type,CallbackInfoReturnable<VertexConsumer> ci) { EntityCapture.renderType(ci.getReturnValue(),type); }
}
