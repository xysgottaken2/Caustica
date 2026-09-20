package dev.xys.vulkanrt.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(net.minecraft.client.renderer.StagedVertexBuffer.Draw.class)
public interface EntityDrawAccessor {
 @Accessor("vertexCount") int nativeVulkanRt$vertexCount();
 @Accessor("vertexOffset") int nativeVulkanRt$vertexOffset();
 @Accessor("format") com.mojang.renderpearl.api.vertex.VertexFormat nativeVulkanRt$format();
 @Accessor("primitiveTopology") com.mojang.renderpearl.api.pipeline.PrimitiveTopology nativeVulkanRt$topology();
}
