package dev.xys.vulkanrt.mixin;

import com.mojang.blaze3d.vertex.QuadInstance;
import dev.xys.vulkanrt.geometry.BlockTintDiagnostics;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
    @Shadow private int tintCacheValue;
    @Shadow @Final private QuadInstance quadInstance;
    @Inject(method="putQuadWithTint",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/block/BlockQuadOutput;put(FFFLnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"))
    private void nativeVulkanRt$tintSource(BlockQuadOutput output,float x,float y,float z,BlockAndTintGetter level,BlockState state,BlockPos pos,BakedQuad quad,CallbackInfo ci) {
        BlockTintDiagnostics.observe(pos,quad,quadInstance,tintCacheValue);
    }
}
