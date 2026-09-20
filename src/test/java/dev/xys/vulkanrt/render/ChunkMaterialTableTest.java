package dev.xys.vulkanrt.render;

import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import dev.xys.vulkanrt.geometry.TriangleMesh;
import net.minecraft.core.SectionPos;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChunkMaterialTableTest {
    private static SectionGeometryLayout layout() {
        return new SectionGeometryLayout(8,12,28,0,List.of(
                new TriangleMesh.Attribute("Position",0,"RGB32_FLOAT"),
                new TriangleMesh.Attribute("Color",12,"RGBA8_UNORM"),
                new TriangleMesh.Attribute("UV0",16,"RG32_FLOAT"),
                new TriangleMesh.Attribute("UV2",24,"RG16_SINT")));
    }
    @Test void packsPhysicalAddressAsTwoWordsAndStd430MetadataWithoutReadingVertices() {
        var bytes=ByteBuffer.allocate(2*ChunkMaterialTable.ROW_BYTES).order(ByteOrder.nativeOrder());
        long a=SectionPos.asLong(-2,4,4), b=SectionPos.asLong(-3,4,5);
        ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(a,0x123456789abcdef0L,layout()));
        ChunkMaterialTable.pack(bytes,144,new ChunkMaterialTable.Entry(b,0x10203040L,layout()));
        assertEquals(0x9abcdef0,bytes.getInt(0)); assertEquals(0x12345678,bytes.getInt(4));
        assertEquals(7,bytes.getInt(8)); assertEquals(4,bytes.getInt(12)); assertEquals(3,bytes.getInt(16));
        assertEquals(0,bytes.getLong(48)); assertEquals(0,bytes.getLong(64)); assertEquals(0,bytes.getInt(76));
        assertEquals(8,bytes.getInt(20)); assertEquals(4,bytes.getInt(24));
        assertEquals(-2,bytes.getInt(32)); assertEquals(4,bytes.getInt(36)); assertEquals(4,bytes.getInt(40));
        assertEquals(-3,bytes.getInt(176)); assertEquals(5,bytes.getInt(184)); assertEquals(0,bytes.getInt(188));
    }
    @Test void comparisonModeChangesOnlyReservedMetadataWordNotGeometryOrUVLayout() {
        var texel=ByteBuffer.allocate(ChunkMaterialTable.ROW_BYTES).order(ByteOrder.nativeOrder());
        var linear=ByteBuffer.allocate(ChunkMaterialTable.ROW_BYTES).order(ByteOrder.nativeOrder());
        var entry=new ChunkMaterialTable.Entry(SectionPos.asLong(-4,4,5),0x123400L,layout());
        ChunkMaterialTable.pack(texel,0,entry,ChunkTextureSampling.TEXEL);
        ChunkMaterialTable.pack(linear,0,entry,ChunkTextureSampling.LINEAR);
        assertEquals(0,texel.getInt(28)); assertEquals(1,linear.getInt(28));
        for(int i=0;i<ChunkMaterialTable.ROW_BYTES;i+=4) if(i!=28) assertEquals(texel.getInt(i),linear.getInt(i));
    }
    @Test void rejectsMissingUnsupportedOrUnalignedAttributesBeforeGpuUse() {
        var bytes=ByteBuffer.allocate(ChunkMaterialTable.ROW_BYTES).order(ByteOrder.nativeOrder());
        assertThrows(IllegalArgumentException.class,()->ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(0,0,layout())));
        assertThrows(IllegalArgumentException.class,()->ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(0,3,layout())));
        var missing=new SectionGeometryLayout(8,12,28,0,List.of(new TriangleMesh.Attribute("Color",12,"RGBA8_UNORM")));
        assertThrows(IllegalArgumentException.class,()->ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(0,4,missing)));
        var unsupported=new SectionGeometryLayout(8,12,28,0,List.of(new TriangleMesh.Attribute("UV0",16,"RG16_SINT")));
        assertThrows(IllegalArgumentException.class,()->ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(0,4,unsupported)));
    }
    @Test void metadataUsesActualOffsetsRatherThanHardcodingBlockStride() {
        var extended=new SectionGeometryLayout(4,6,36,0,List.of(new TriangleMesh.Attribute("Color",16,"RGBA8_UNORM"),
                new TriangleMesh.Attribute("UV0",24,"RG32_FLOAT")));
        var bytes=ByteBuffer.allocate(ChunkMaterialTable.ROW_BYTES).order(ByteOrder.nativeOrder());
        ChunkMaterialTable.pack(bytes,0,new ChunkMaterialTable.Entry(0,4,extended));
        assertEquals(9,bytes.getInt(8)); assertEquals(6,bytes.getInt(12)); assertEquals(4,bytes.getInt(16));
    }
}
