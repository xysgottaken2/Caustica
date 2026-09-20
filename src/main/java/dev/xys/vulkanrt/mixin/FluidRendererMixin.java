package dev.xys.vulkanrt.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.xys.vulkanrt.geometry.BlockTintDiagnostics;
import net.minecraft.client.renderer.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe original emitted attributes only; never modify vertices, model selection or tint. */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    @Shadow @Final private FluidStateModelSet fluidModels;
    @Inject(method="tesselate",at=@At("HEAD"))
    private void nativeVulkanRt$beginFluid(BlockAndTintGetter level,BlockPos pos,FluidRenderer.Output output,BlockState state,FluidState fluid,CallbackInfo ci) {
        BlockTintDiagnostics.beginFluid(pos,state,fluid,fluidModels);
    }
    @Inject(method="tesselate",at=@At("RETURN"))
    private void nativeVulkanRt$endFluid(BlockAndTintGetter level,BlockPos pos,FluidRenderer.Output output,BlockState state,FluidState fluid,CallbackInfo ci) {
        BlockTintDiagnostics.endFluid();
    }
    @Inject(method="vertex",at=@At("HEAD"))
    private void nativeVulkanRt$fluidVertex(VertexConsumer consumer,float x,float y,float z,int color,float u,float v,int light,CallbackInfo ci) {
        BlockTintDiagnostics.fluidVertex(x,y,z,color,u,v,light);
    }
}
