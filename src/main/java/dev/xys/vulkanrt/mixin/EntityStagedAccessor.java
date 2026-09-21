package dev.xys.vulkanrt.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(net.minecraft.client.renderer.StagedVertexBuffer.class)
public interface EntityStagedAccessor {
 @Accessor("draws") java.util.List<net.minecraft.client.renderer.StagedVertexBuffer.Draw> nativeVulkanRt$draws();
}
