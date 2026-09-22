package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link RenderType}'s private {@code state} ({@link RenderSetup}) so the RT entity-texture
 * registry can recover a per-type texture's resource Identifier (needed to locate its LabPBR
 * {@code _n}/{@code _s} sibling files). {@code prepare()} only yields image views, not locations.
 */
@Mixin(RenderType.class)
public interface RenderTypeAccessor {
    @Accessor("state")
    RenderSetup caustica$state();
}
