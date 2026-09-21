package dev.xys.vulkanrt.geometry;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SectionGeometryLayoutTest {
    @Test void describesActualMinecraftBlockFormatAndImplicitIndices() {
        var layout = SectionGeometryLayout.solidQuads(DefaultVertexFormat.BLOCK,36);
        assertEquals(24,layout.vertexCount()); assertEquals(12,layout.triangles());
        assertEquals(28,layout.stride()); assertEquals(0,layout.positionOffset()); assertEquals(672,layout.vertexBytes());
        assertEquals(java.util.List.of("Position","Color","UV0","UV2"),layout.attributes().stream().map(TriangleMesh.Attribute::name).toList());
        assertEquals(java.util.List.of(0,12,16,24),layout.attributes().stream().map(TriangleMesh.Attribute::offset).toList());
    }
    @Test void positionOffsetComesFromFormatInsteadOfAssumingPackedXyz() {
        var format = VertexFormat.builder(0).addAttribute("Color", GpuFormat.RGBA8_UNORM)
                .addAttribute("Position", GpuFormat.RGB32_FLOAT).build();
        var layout = SectionGeometryLayout.solidQuads(format,6);
        assertEquals(4,layout.positionOffset()); assertEquals(16,layout.stride()); assertEquals(64,layout.vertexBytes());
    }
    @Test void rejectsEmptyMalformedAndUnknownPositions() {
        assertThrows(IllegalArgumentException.class, () -> SectionGeometryLayout.solidQuads(DefaultVertexFormat.BLOCK,0));
        assertThrows(IllegalArgumentException.class, () -> SectionGeometryLayout.solidQuads(DefaultVertexFormat.BLOCK,5));
        var format = VertexFormat.builder(0).addAttribute("Position",GpuFormat.RG32_FLOAT).build();
        assertThrows(IllegalArgumentException.class, () -> SectionGeometryLayout.solidQuads(format,6));
    }
}
