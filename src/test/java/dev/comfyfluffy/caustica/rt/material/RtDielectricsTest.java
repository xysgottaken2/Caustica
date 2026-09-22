package dev.comfyfluffy.caustica.rt.material;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtDielectricsTest {
    @Test
    void iceFamilyRefractsAtIceIndex() {
        for (String sprite : new String[]{"block/ice", "block/packed_ice", "block/blue_ice",
                "block/frosted_ice_0", "block/frosted_ice_3"}) {
            assertEquals(RtDielectrics.ICE_IOR,
                    RtDielectrics.iorForSprite(Identifier.parse("minecraft:" + sprite)), 1.0e-6f, sprite);
        }
    }

    @Test
    void unknownAndNullSpritesFallBackToSodaLimeGlass() {
        assertEquals(RtDielectrics.GLASS_IOR,
                RtDielectrics.iorForSprite(Identifier.parse("minecraft:block/glass")), 1.0e-6f);
        assertEquals(RtDielectrics.GLASS_IOR,
                RtDielectrics.iorForSprite(Identifier.parse("somemod:block/weird_crystal")), 1.0e-6f);
        assertEquals(RtDielectrics.GLASS_IOR, RtDielectrics.iorForSprite(null), 1.0e-6f);
    }

    @Test
    void iceRefractsLessStronglyThanWaterWhichRefractsLessThanGlass() {
        // Ice Ih sits just below liquid water, which sits well below soda-lime glass. Collapsing these
        // onto one per-model constant is exactly what made ice refract like a window.
        assertTrue(RtDielectrics.ICE_IOR < RtDielectrics.WATER_IOR);
        assertTrue(RtDielectrics.WATER_IOR < RtDielectrics.GLASS_IOR);
    }
}
