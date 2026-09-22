package dev.comfyfluffy.caustica.rt.entity;

import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Duck interface mixed into {@code BlockModelRenderState}: carries the blockState resolved by
 * {@code BlockModelResolver.update} so the RT entity capture can re-mesh it from the world
 * block-state model set. The display model set wraps some blocks in a {@code SpecialBlockModelWrapper}
 * whose special-renderer path the entity collector can't capture — meshing from the world set (as
 * falling blocks do) sidesteps that.
 */
public interface ContainedBlockSource {
    @Nullable
    BlockState caustica$containedBlock();

    void caustica$setContainedBlock(@Nullable BlockState state);
}
