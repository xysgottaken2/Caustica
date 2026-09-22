package dev.comfyfluffy.caustica.mixin;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes {@link TextureAtlas}'s private sprite list so the RT LabPBR ingestion can enumerate every
 * block-atlas sprite up front and build the {@code _s}/{@code _n} parallel atlases in advance, instead
 * of decoding each sprite's maps lazily on the terrain-build hot path.
 */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
    @Accessor("sprites")
    List<TextureAtlasSprite> caustica$sprites();
}
