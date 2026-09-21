package dev.xys.vulkanrt.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(com.mojang.blaze3d.vertex.BufferBuilder.class)
public interface EntityBuilderAccessor {
 @Accessor("vertices") int nativeVulkanRt$vertices();
}
