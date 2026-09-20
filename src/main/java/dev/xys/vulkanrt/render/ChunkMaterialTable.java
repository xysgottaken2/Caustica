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
    public static final int ROW_BYTES = 144;
    public static final int CUTOUT = 1, CULL_BACK = 2, TRANSLUCENT = 4, ENTITY = 8, PARTICLE = 16;
    public record Entry(long section, long vertexAddress, SectionGeometryLayout layout, CoplanarOverlayMapper.Overlay overlay, int flags,long indexAddress,int indexBytes, EntityGeometryManager.Material entity, ParticleGeometryManager.ParticleMaterial particle) {
        public Entry {
            if((flags & ~(CUTOUT|CULL_BACK|TRANSLUCENT|ENTITY|PARTICLE))!=0 || flags==CULL_BACK || (flags&(CUTOUT|TRANSLUCENT))==(CUTOUT|TRANSLUCENT) || (flags!=0 && overlay!=null) || ((flags&PARTICLE)!=0 && particle==null) || ((flags&PARTICLE)==0 && particle!=null))
                throw new IllegalArgumentException("Invalid layer/material flags");
        }
        public Entry(long section,long vertexAddress,SectionGeometryLayout layout,CoplanarOverlayMapper.Overlay overlay,int flags,long indexAddress,int indexBytes) { this(section,vertexAddress,layout,overlay,flags,indexAddress,indexBytes,null,null); }
        public Entry(long section,long vertexAddress,SectionGeometryLayout layout,CoplanarOverlayMapper.Overlay overlay,int flags) { this(section,vertexAddress,layout,overlay,flags,0,0); }
        public Entry(long section,long vertexAddress,SectionGeometryLayout layout,CoplanarOverlayMapper.Overlay overlay) { this(section,vertexAddress,layout,overlay,0); }
        public Entry(long section,long vertexAddress,SectionGeometryLayout layout) { this(section,vertexAddress,layout,null); }
        public Entry(long section,long vertexAddress,SectionGeometryLayout layout,CoplanarOverlayMapper.Overlay overlay,int flags,long indexAddress,int indexBytes,EntityGeometryManager.Material entity) { this(section,vertexAddress,layout,overlay,flags,indexAddress,indexBytes,entity,null); }
    }
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
                .putInt(offset+40,SectionPos.z(entry.section())).putInt(offset+44,entry.flags());
        if((entry.flags()&TRANSLUCENT)!=0 && (entry.flags()&(ENTITY|PARTICLE))==0 && (entry.indexAddress()==0 || (entry.indexAddress()&3)!=0 || (entry.indexBytes()!=2 && entry.indexBytes()!=4)))
            throw new IllegalArgumentException("Missing/invalid TRANSLUCENT index snapshot");
        bytes.putLong(offset+80,entry.indexAddress()).putInt(offset+88,entry.indexBytes()).putInt(offset+92,entry.indexAddress()==0?0:layout.indexCount());
        for(int i=96;i<ROW_BYTES;i+=4) bytes.putInt(offset+i,0);
        var e=entry.entity();
        if(e!=null) {
            bytes.putInt(offset+96,e.texture()).putFloat(offset+100,e.cutoff()).putInt(offset+104,e.overlay()).putInt(offset+108,e.record());
            bytes.putInt(offset+112,EntityGeometryManager.abgr(e.colors().a())).putInt(offset+116,EntityGeometryManager.abgr(e.colors().b()))
                .putInt(offset+120,EntityGeometryManager.abgr(e.colors().c())).putInt(offset+124,EntityGeometryManager.abgr(e.colors().d()));
            bytes.putInt(offset+128,e.overlayTexture()).putInt(offset+132,e.mirror()?1:0).putInt(offset+136,e.id()).putInt(offset+140,e.falling()?1:0);
        } else if(entry.particle()!=null) {
            var p=entry.particle();
            bytes.putInt(offset+96,p.texture()).putFloat(offset+100,p.cutoff()).putInt(offset+104,p.blockAtlas()?1:0).putInt(offset+108,-1);
            bytes.putInt(offset+112,0).putInt(offset+116,0).putInt(offset+120,0).putInt(offset+124,0);
            bytes.putInt(offset+128,0).putInt(offset+132,0).putInt(offset+136,-1).putInt(offset+140,0);
        }
        var overlay=entry.overlay();
        bytes.putLong(offset+48,overlay==null?0:overlay.vertices.address());
        bytes.putInt(offset+56,overlay==null?0:overlay.format.stride()/4).putInt(offset+60,overlay==null?0:attribute(overlay.format,"UV0","RG32_FLOAT",8));
        bytes.putLong(offset+64,overlay==null?0:overlay.matches.address());
        bytes.putInt(offset+72,overlay==null?0:attribute(overlay.format,"Color","RGBA8_UNORM",4)).putInt(offset+76,layout.positionOffset()/4);
    }
    @Override public void close() { buffer.close(); }
}
