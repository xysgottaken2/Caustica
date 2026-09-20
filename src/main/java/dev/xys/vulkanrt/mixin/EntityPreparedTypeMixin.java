package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.StagedVertexBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
@Mixin(PreparedRenderType.class)
public abstract class EntityPreparedTypeMixin {
 @Inject(method="draw",at=@At("RETURN")) private void nativeVulkanRt$draw(StagedVertexBuffer.ExecuteInfo info,RenderPass pass,RenderPipeline pipeline,CallbackInfo ci) { EntityCapture.draw((PreparedRenderType)(Object)this,info); }
}
