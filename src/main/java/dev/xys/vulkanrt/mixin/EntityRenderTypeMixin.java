package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import dev.xys.vulkanrt.geometry.ParticleCapture;
import dev.xys.vulkanrt.render.ViewmodelCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.rendertype.*;
@Mixin(RenderType.class)
public abstract class EntityRenderTypeMixin {
 @Inject(method="prepare",at=@At("RETURN")) private void nativeVulkanRt$material(CallbackInfoReturnable<PreparedRenderType> ci) { EntityCapture.prepared((RenderType)(Object)this,ci.getReturnValue()); ParticleCapture.prepared((RenderType)(Object)this,ci.getReturnValue()); ViewmodelCapture.prepared((RenderType)(Object)this,ci.getReturnValue()); }
}
