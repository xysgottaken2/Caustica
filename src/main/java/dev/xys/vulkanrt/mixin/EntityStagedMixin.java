package dev.xys.vulkanrt.mixin;
import dev.xys.vulkanrt.geometry.EntityCapture;
import dev.xys.vulkanrt.geometry.ParticleCapture;
import dev.xys.vulkanrt.render.ViewmodelCapture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import net.minecraft.client.renderer.StagedVertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
@Mixin(StagedVertexBuffer.class)
public abstract class EntityStagedMixin {
 @Inject(method="getVertexBuilder",at=@At("RETURN")) private void nativeVulkanRt$builder(StagedVertexBuffer.Draw draw,CallbackInfoReturnable<VertexConsumer> ci) { EntityCapture.builder(draw,ci.getReturnValue()); ParticleCapture.builder(draw,ci.getReturnValue()); ViewmodelCapture.builder(draw,ci.getReturnValue()); }
 @Redirect(method="uploadDrawsToBuffers",at=@At(value="INVOKE",target="Lcom/mojang/renderpearl/api/commands/CommandEncoder;copyToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V"))
 private void nativeVulkanRt$upload(CommandEncoder encoder,GpuBufferSlice source,GpuBufferSlice target) {
  encoder.copyToBuffer(source,target);
  EntityCapture.upload((StagedVertexBuffer)(Object)this,source,target);
  ParticleCapture.upload((StagedVertexBuffer)(Object)this,source,target);
  ViewmodelCapture.upload((StagedVertexBuffer)(Object)this,source,target);
 }
}
