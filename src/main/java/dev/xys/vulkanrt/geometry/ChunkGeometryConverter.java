package dev.xys.vulkanrt.geometry;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Converts an already-tessellated 26.3 MeshData before vanilla releases its CPU storage.
 * Does not inspect MultiDrawIndirect draws or assume UberGpuBuffer offsets remain stable.
 * RUNTIME VERIFIED: NO. The initial world pass intentionally captures SOLID only. */
public final class ChunkGeometryConverter {
    public static TriangleMesh convert(MeshData mesh) {
        var state = mesh.drawState();
        if (state.primitiveTopology() != PrimitiveTopology.QUADS) throw new IllegalArgumentException("Terrain mesh must be QUADS");
        var format = state.format();
        var position = format.getElement("Position");
        if (position == null || position.format() != GpuFormat.RGB32_FLOAT) throw new IllegalArgumentException("Unknown terrain position format");
        List<TriangleMesh.Attribute> attributes = format.getElements().stream()
                .map(e -> new TriangleMesh.Attribute(e.name(), e.offset(), e.format().name())).toList();
        // Opaque mesh ordering is irrelevant for rays; regenerate only the implicit quad INDEX list,
        // not vertices. CUTOUT/translucent materials are not admitted to this path.
        return decodeQuads(mesh.vertexBuffer(), state.vertexCount(), format.getVertexSize(), position.offset(), attributes);
    }

    public static TriangleMesh decodeQuads(ByteBuffer source, int count, int stride, int positionOffset,
                                           List<TriangleMesh.Attribute> attributes) {
        if (count <= 0 || count % 4 != 0 || stride < 12 || positionOffset < 0 || positionOffset > stride - 12)
            throw new IllegalArgumentException("Malformed quad vertex layout");
        int bytes = Math.multiplyExact(count, stride);
        if (bytes > source.remaining() || bytes > 8 * 1024 * 1024) throw new IllegalArgumentException("Invalid/oversized section mesh");
        ByteBuffer view = source.duplicate().order(ByteOrder.nativeOrder());
        int base = view.position();
        float[] positions = new float[Math.multiplyExact(count, 3)];
        for (int i = 0; i < count; i++) for (int axis = 0; axis < 3; axis++) {
            float value = view.getFloat(base + i * stride + positionOffset + axis * 4);
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite chunk position");
            positions[i * 3 + axis] = value;
        }
        int[] indices = new int[Math.multiplyExact(count / 4, 6)];
        for (int q = 0; q < count / 4; q++) {
            int v = q * 4, j = q * 6;
            indices[j] = v; indices[j+1] = v+1; indices[j+2] = v+2;
            indices[j+3] = v+2; indices[j+4] = v+3; indices[j+5] = v;
        }
        byte[] original = new byte[bytes]; view.get(original);
        return new TriangleMesh(positions, indices, original, stride, List.copyOf(attributes));
    }
    private ChunkGeometryConverter() {}
}
