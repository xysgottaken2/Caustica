package dev.xys.vulkanrt.geometry;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.List;

/** Describes the interleaved section allocation, not the MDI command or instance buffer.
 * Position is section-local in block units; retain every other attribute unchanged on GPU. */
public record SectionGeometryLayout(int vertexCount, int indexCount, int stride, int positionOffset,
                                    List<TriangleMesh.Attribute> attributes) {
    public static SectionGeometryLayout solidQuads(VertexFormat format, int indexCount) {
        if (indexCount <= 0 || indexCount % 6 != 0) throw new IllegalArgumentException("SOLID must contain complete indexed quads");
        var position = format.getElement("Position");
        if (position == null || position.format() != GpuFormat.RGB32_FLOAT)
            throw new IllegalArgumentException("Unsupported section Position format: " + position);
        int stride = format.getVertexSize();
        if (stride < 12 || stride % 4 != 0 || position.offset() % 4 != 0 || position.offset() > stride - 12)
            throw new IllegalArgumentException("Invalid section position stride/offset");
        var layout = new SectionGeometryLayout(Math.multiplyExact(indexCount / 6, 4), indexCount, stride, position.offset(),
                format.getElements().stream().map(e -> new TriangleMesh.Attribute(e.name(), e.offset(), e.format().name())).toList());
        if (layout.vertexBytes() > 8L * 1024 * 1024) throw new IllegalArgumentException("Section exceeds 8 MiB milestone limit");
        return layout;
    }
    public long vertexBytes() { return Math.multiplyExact((long)vertexCount, stride); }
    public int triangles() { return indexCount / 3; }
}
