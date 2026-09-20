package dev.xys.vulkanrt.geometry;

import java.util.List;

/** Immutable by ownership after capture. Original interleaved bytes retain UV/tint/light attributes.
 * The visibility milestone reads only positions/indices; it does not pretend to implement materials. */
public record TriangleMesh(float[] positions, int[] indices, byte[] vanillaVertices,
                           int vertexStride, List<Attribute> attributes) {
    public record Attribute(String name, int offset, String format) {}
    public long bytes() { return positions.length * 4L + indices.length * 4L + vanillaVertices.length; }
    public static TriangleMesh testTriangle() {
        return new TriangleMesh(new float[]{-1,-1,0, 1,-1,0, 0,1,0}, new int[]{0,1,2}, new byte[0], 0, List.of());
    }
}
