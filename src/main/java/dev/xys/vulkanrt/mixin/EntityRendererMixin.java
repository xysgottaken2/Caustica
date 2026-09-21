package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
 @Inject(method="extractRenderState",at=@At("RETURN"))
 private void nativeVulkanRt$state(Entity entity,EntityRenderState state,float partial,CallbackInfo ci) { EntityCapture.state(state,entity.getId()); }
}
