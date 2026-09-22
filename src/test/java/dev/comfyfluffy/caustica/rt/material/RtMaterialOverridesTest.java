package dev.comfyfluffy.caustica.rt.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialOverridesTest {
    @Test
    void parsesVersionedExtensibleMaterialProperties() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"block":"minecraft:blue_stained_glass",
                "sprite":"minecraft:block/blue_stained_glass"},"model":"dielectric",
                "base":{"roughness":0.06,"metalness":0.0},
                "emission":{"strength":2.0,"color_source":"albedo"},
                "transmission":{"factor":1.0,"ior":1.52}}
                """).getAsJsonObject(), Identifier.parse("test:caustica/materials/glass.json"));
        assertEquals(Identifier.parse("minecraft:block/blue_stained_glass"), rule.sprite());
        assertEquals(RtMaterialRegistry.MODEL_DIELECTRIC, rule.model());
        assertEquals(1.52f, rule.ior());
        assertEquals(2.0f, rule.emissionStrength());
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.LAB_PBR, RtMaterialRegistry.FEATURE_SPEC,
                0.8f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.LAB_PBR,
                5.0f, new RtMaterialDesc.EmissionSummary(0.2f, 0.1f, 0.05f, 0.1f, 0.5f));
        RtMaterialDesc applied = rule.apply(base);
        assertEquals(RtMaterialDesc.Source.OVERRIDE, applied.source());
        // emission.strength is a multiplier on the already-resolved strength: it scales LabPBR's own
        // emission rather than replacing it, so the source and summary stay exactly base's.
        assertEquals(RtMaterialDesc.EmissionSource.LAB_PBR, applied.emissionSource());
        assertEquals(10.0f, applied.emissionStrength());
        assertEquals(base.emissionSummary(), applied.emissionSummary());
        assertEquals(0.06f, applied.roughness());
        assertEquals(1.0f, applied.transmission());
    }

    @Test
    void transmissionIorOverridesTheBuiltInIndex() {
        RtMaterialDesc glassBase = new RtMaterialDesc(RtMaterialRegistry.MODEL_DIELECTRIC,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.0025f, 0.0f, RtDielectrics.GLASS_IOR, 1.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE);
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"somemod:block/crystal"},
                "model":"dielectric","transmission":{"ior":2.417}}
                """).getAsJsonObject(), Identifier.parse("test:caustica/materials/crystal.json"));
        RtMaterialDesc applied = rule.apply(glassBase);
        assertEquals(RtMaterialRegistry.MODEL_DIELECTRIC, applied.model());
        assertEquals(2.417f, applied.ior());

        // Omitting ior on a rule that does not change the model leaves the base index alone.
        var silent = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"somemod:block/crystal"},"base":{"roughness":0.5}}
                """).getAsJsonObject(), Identifier.parse("test:caustica/materials/crystal.json"));
        assertEquals(RtDielectrics.GLASS_IOR, silent.apply(glassBase).ior());
    }

    @Test
    void waterModelKeepsItsOwnIndexWhenSelectedByName() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"somemod:block/pool"},"model":"water"}
                """).getAsJsonObject(), Identifier.parse("test:caustica/materials/pool.json"));
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.8f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE);
        RtMaterialDesc applied = rule.apply(base);
        assertEquals(RtMaterialRegistry.MODEL_WATER, applied.model());
        assertEquals(RtDielectrics.WATER_IOR, applied.ior());
        assertEquals(1.0f, applied.transmission());
    }

    @Test
    void emissionStrengthCannotForceEmissionOntoANonEmissiveMaterial() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:block/stone"},
                "emission":{"strength":5.0}}
                """).getAsJsonObject(), Identifier.parse("test:boost.json"));
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.8f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE);

        RtMaterialDesc applied = rule.apply(base);

        assertEquals(RtMaterialDesc.EmissionSource.NONE, applied.emissionSource());
        assertEquals(0.0f, applied.emissionStrength());
    }

    @Test
    void rejectsUnknownVersionsAndOutOfRangePhysicalValues() {
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":2,\"match\":{\"sprite\":\"minecraft:block/stone\"}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":1,\"match\":{\"sprite\":\"minecraft:block/stone\"},"
                        + "\"base\":{\"metalness\":2}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
    }

    @Test
    void clampsOutOfRangeEmissionStrengthInsteadOfThrowing() {
        var rule = RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":1,\"match\":{\"sprite\":\"minecraft:block/stone\"},"
                        + "\"emission\":{\"strength\":5.1}}")
                        .getAsJsonObject(), Identifier.parse("test:clamp.json"));
        assertEquals(5.0f, rule.emissionStrength());
    }

    @Test
    void spriteWideRulesApplyToCompiledEntityResources() {
        var entityRule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:entity/zombie/zombie"},
                "base":{"roughness":0.7}}
                """).getAsJsonObject(), Identifier.parse("test:entity.json"));
        var blockRule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:entity/zombie/zombie",
                "block":"minecraft:stone"}}
                """).getAsJsonObject(), Identifier.parse("test:block.json"));

        assertTrue(entityRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/zombie")));
        assertFalse(entityRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/husk")));
        assertFalse(blockRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/zombie")));
    }
}
