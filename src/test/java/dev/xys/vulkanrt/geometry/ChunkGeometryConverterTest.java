package dev.xys.vulkanrt.geometry;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ChunkGeometryConverterTest {
    @Test void decodesInterleavedQuadsWithoutChangingSourcePosition() {
        ByteBuffer vertices = ByteBuffer.allocate(16 + 4 * 28).order(ByteOrder.nativeOrder());
        vertices.position(16);
        for (int i = 0; i < 4; i++) vertices.putFloat(i).putFloat(i+1).putFloat(i+2)
                .putInt(0xff123456).putFloat(0.25f).putFloat(0.5f).putInt(0x00f000f0);
        vertices.position(16);
        var mesh = ChunkGeometryConverter.decodeQuads(vertices, 4, 28, 0, List.of(new TriangleMesh.Attribute("UV0", 16, "RG32_FLOAT")));
        assertEquals(16, vertices.position());
        assertArrayEquals(new int[]{0,1,2,2,3,0}, mesh.indices());
        assertArrayEquals(new float[]{0,1,2,1,2,3,2,3,4,3,4,5}, mesh.positions());
        assertEquals(112, mesh.vanillaVertices().length);
        assertEquals(0xff123456, ByteBuffer.wrap(mesh.vanillaVertices()).order(ByteOrder.nativeOrder()).getInt(12));
        assertEquals(0.25f, ByteBuffer.wrap(mesh.vanillaVertices()).order(ByteOrder.nativeOrder()).getFloat(16));
    }
    @Test void rejectsIncompleteOrNonFiniteMeshes() {
        assertThrows(IllegalArgumentException.class, () -> ChunkGeometryConverter.decodeQuads(ByteBuffer.allocate(12), 3, 12, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ChunkGeometryConverter.decodeQuads(ByteBuffer.allocate(12), 4, 12, 0, List.of()));
        var bytes = ByteBuffer.allocate(48).order(ByteOrder.nativeOrder()).putFloat(0, Float.NaN);
        assertThrows(IllegalArgumentException.class, () -> ChunkGeometryConverter.decodeQuads(bytes, 4, 12, 0, List.of()));
    }
}
