package dev.xys.vulkanrt.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** CPU ABI checks for the vanilla PARTICLE upload contract; no GPU or readback is used. */
final class ParticleIntegrationTest {
    @Test void particleFormatIsVanillaPositionUvColorLightAndQuadSized() {
        var format = DefaultVertexFormat.PARTICLE;
        assertEquals(28, format.getVertexSize());
        var layout = SectionGeometryLayout.solidQuads(format, 6);
        assertEquals(0, layout.positionOffset());
        assertEquals(3, ChunkMaterialTable.attribute(layout, "UV0", "RG32_FLOAT", 8));
        assertEquals(5, ChunkMaterialTable.attribute(layout, "Color", "RGBA8_UNORM", 4));
        assertEquals(6, ChunkMaterialTable.attribute(layout, "UV2", "RG16_SINT", 4));
        assertEquals(4, layout.vertexCount());
    }

    @Test void particleMaterialPreservesVertexTintAndUsesDedicatedTextureBit() {
        var layout = SectionGeometryLayout.solidQuads(DefaultVertexFormat.PARTICLE, 6);
        var material = new ParticleGeometryManager.ParticleMaterial(0, 0.5f, false);
        var entry = new ChunkMaterialTable.Entry(0, 0x123400L, layout, null,
                ChunkMaterialTable.PARTICLE | ChunkMaterialTable.CUTOUT, 0, 0, null, material);
        var bytes = ByteBuffer.allocate(ChunkMaterialTable.ROW_BYTES).order(ByteOrder.nativeOrder());
        ChunkMaterialTable.pack(bytes, 0, entry);
        assertEquals(ChunkMaterialTable.PARTICLE | ChunkMaterialTable.CUTOUT, bytes.getInt(44));
        assertEquals(0, bytes.getInt(96));
        assertEquals(0.5f, bytes.getFloat(100));
        assertEquals(0, bytes.getInt(104));
        assertEquals(-1, bytes.getInt(108));
    }

    @Test void particleTranslucencyDoesNotDemandTerrainIndexSnapshot() {
        var layout = SectionGeometryLayout.solidQuads(DefaultVertexFormat.PARTICLE, 6);
        var entry = new ChunkMaterialTable.Entry(0, 0x123400L, layout, null,
                ChunkMaterialTable.PARTICLE | ChunkMaterialTable.TRANSLUCENT, 0, 0, null,
                new ParticleGeometryManager.ParticleMaterial(0, 0.0f, false));
        assertDoesNotThrow(() -> ChunkMaterialTable.pack(ByteBuffer.allocate(ChunkMaterialTable.ROW_BYTES)
                .order(ByteOrder.nativeOrder()), 0, entry));
    }
}
