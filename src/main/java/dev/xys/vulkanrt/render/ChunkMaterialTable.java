package dev.xys.vulkanrt.render;

import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.List;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/** Immutable rows in exactly the TLAS input order (gl_InstanceID, not custom index/SBT offset).
 * Only CPU-known addresses/layout/section metadata are uploaded. Vertex bytes stay on the GPU. */
public final class ChunkMaterialTable implements AutoCloseable {
    public static final int ROW_BYTES = 48;
    public record Entry(long section, long vertexAddress, SectionGeometryLayout layout) {}
    public final GpuBuffer buffer;
    public final int count;
    private static String lastLayout;

    public ChunkMaterialTable(VulkanRayTracingContext context, CommandBatch batch, List<Entry> entries) {
        if (entries.isEmpty()) throw new IllegalArgumentException("Empty material table");
        count = entries.size();
        var sampling=ChunkTextureSampling.configured();
        ByteBuffer bytes = MemoryUtil.memCalloc(Math.multiplyExact(count, ROW_BYTES));
        try {
            for (int i = 0; i < count; i++) pack(bytes, i * ROW_BYTES, entries.get(i), sampling);
            var layout=entries.getFirst().layout();
            String shape="stride="+layout.stride()+" UV0 byteOffset="+(attribute(layout,"UV0","RG32_FLOAT",8)*4)
                    +" Color byteOffset="+(attribute(layout,"Color","RGBA8_UNORM",4)*4);
            if(!shape.equals(lastLayout)) {
                lastLayout=shape;
                org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT][texture-quality] {} UV0=RG32_FLOAT normalized atlas coordinates; no UV rescale; interleaved GPU vertex copy preserved",shape);
            }
            buffer = GpuBuffer.upload(context, batch, bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
        } finally { MemoryUtil.memFree(bytes); }
    }
    static int attribute(SectionGeometryLayout layout, String name, String format, int bytes) {
        var attr = layout.attributes().stream().filter(a -> a.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing terrain attribute " + name));
        if (!attr.format().equals(format) || attr.offset() < 0 || attr.offset() % 4 != 0 || attr.offset() > layout.stride()-bytes)
            throw new IllegalArgumentException("Unsupported terrain attribute " + attr);
        return attr.offset()/4;
    }
    public static void pack(ByteBuffer bytes, int offset, Entry entry) { pack(bytes,offset,entry,ChunkTextureSampling.TEXEL); }
    public static void pack(ByteBuffer bytes, int offset, Entry entry, ChunkTextureSampling sampling) {
        var layout = entry.layout();
        if (entry.vertexAddress() == 0 || (entry.vertexAddress() & 3) != 0 || layout.stride()%4 != 0)
            throw new IllegalArgumentException("Invalid material vertex address/stride");
        int uv = attribute(layout,"UV0","RG32_FLOAT",8), color = attribute(layout,"Color","RGBA8_UNORM",4);
        // GLSL uvec2 physical address avoids requiring shaderInt64 or descriptor indexing.
        bytes.putLong(offset, entry.vertexAddress());
        bytes.putInt(offset+8,layout.stride()/4).putInt(offset+12,uv);
        bytes.putInt(offset+16,color).putInt(offset+20,layout.vertexCount()).putInt(offset+24,layout.triangles()).putInt(offset+28,sampling.shaderId);
        bytes.putInt(offset+32,SectionPos.x(entry.section())).putInt(offset+36,SectionPos.y(entry.section()))
                .putInt(offset+40,SectionPos.z(entry.section())).putInt(offset+44,0);
    }
    @Override public void close() { buffer.close(); }
}
