package dev.comfyfluffy.caustica.rt.material;

import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Built-in refractive indices for the dielectric blocks vanilla ships.
 *
 * <p>Every dielectric is a volume: {@code world.rgen} refracts at the interface and pushes a
 * participating medium whose extinction attenuates the segment inside. IOR is therefore the whole
 * material description — it drives both the Snell bend and the Fresnel split, and getting it wrong is
 * visible as the wrong amount of distortion. Everything translucent used to share one per-model
 * constant, so ice refracted exactly like window glass.
 *
 * <p>Sprite-keyed rather than block-keyed because the material registry compiles per sprite, and
 * resolved once per sprite so it adds no variants to the profile x model x emission cross product. A
 * resource pack that renames textures falls back to the soda-lime default, and a
 * {@code caustica/materials/*.json} rule can set {@code transmission.ior} explicitly.
 */
public final class RtDielectrics {
    private RtDielectrics() {}

    /** Soda-lime glass. The default for anything translucent that is not otherwise classified. */
    public static final float GLASS_IOR = 1.52f;
    /** Fresh water at room temperature; also the fluid singleton's index. */
    public static final float WATER_IOR = 1.333f;
    /** Ice Ih, slightly below liquid water. */
    public static final float ICE_IOR = 1.309f;

    private static final Map<String, Float> IOR_BY_SPRITE = Map.of(
            "block/ice", ICE_IOR,
            "block/packed_ice", ICE_IOR,
            "block/blue_ice", ICE_IOR,
            "block/frosted_ice_0", ICE_IOR,
            "block/frosted_ice_1", ICE_IOR,
            "block/frosted_ice_2", ICE_IOR,
            "block/frosted_ice_3", ICE_IOR);

    /**
     * Built-in refractive index for a sprite, or soda-lime glass when the sprite is unrecognised. Only
     * meaningful for materials the mesher classified as translucent; opaque materials ignore it.
     */
    public static float iorForSprite(Identifier spriteName) {
        if (spriteName == null) return GLASS_IOR;
        return IOR_BY_SPRITE.getOrDefault(spriteName.getPath(), GLASS_IOR);
    }
}
