package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.rt.entity.ContainedBlockSource;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the blockState each {@code BlockModelResolver.update} resolves onto the render state ({@link
 * ContainedBlockSource}), so the RT entity capture can re-mesh a contained block display that the display
 * model set hands to a special renderer (see {@link BlockModelRenderStateMixin}).
 */
@Mixin(BlockModelResolver.class)
public class BlockModelResolverMixin {
    @Inject(method = "update", at = @At("TAIL"))
    private void caustica$recordContained(BlockModelRenderState renderState, BlockState blockState,
                                          BlockDisplayContext displayContext, CallbackInfo ci) {
        ((ContainedBlockSource) renderState).caustica$setContainedBlock(blockState);
    }
}
