package dev.xys.vulkanrt.geometry;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import net.minecraft.client.renderer.chunk.*;

/** Checked before selection AND before GPU copy. No Vulkan calls or vertex readback. */
public final class SectionGeometrySanity {
    public enum Failure {
        OK, SECTION_NOT_FOUND, SECTION_NODE_CHANGED, MESH_NOT_ACCEPTED, UNCOMPILED_MESH, EMPTY_MESH,
        SOLID_DRAW_MISSING, LAYER_DRAW_MISSING, VERTEX_UPLOAD_PENDING, INDEX_UPLOAD_PENDING, SLICE_MISSING,
        NOT_VULKAN_BUFFER, CUSTOM_INDICES_UNSUPPORTED, INVALID_INDEX_COUNT, POSITION_FORMAT_UNSUPPORTED,
        INVALID_VERTEX_LAYOUT, VERTEX_RANGE_EMPTY, SECTION_TOO_LARGE, ZERO_BUFFER_HANDLE,
        BUFFER_CLOSED, COPY_SRC_MISSING, MISALIGNED_OFFSET, RANGE_OUT_OF_BOUNDS,
        INDEX_SLICE_MISSING, INDEX_FORMAT_UNSUPPORTED, INDEX_RANGE_INVALID, NON_VANILLA_TRANSLUCENT_MESH, TRANSLUCENT_SORT_STATE_INVALID
    }
    public static Failure inspect(SectionRenderDispatcher.RenderSection section, long expectedNode, SectionMesh mesh,
                                  SectionRenderDispatcher.RenderSectionBufferSlice slice, VertexFormat format) {
        return inspect(section,expectedNode,mesh,slice,format,ChunkSectionLayer.SOLID);
    }
    public static Failure inspect(SectionRenderDispatcher.RenderSection section, long expectedNode, SectionMesh mesh,
                                  SectionRenderDispatcher.RenderSectionBufferSlice slice, VertexFormat format, ChunkSectionLayer layer) {
        if (section == null) return Failure.SECTION_NOT_FOUND;
        if (section.getSectionNode() != expectedNode) return Failure.SECTION_NODE_CHANGED;
        if (mesh == null || section.getSectionMesh() != mesh) return Failure.MESH_NOT_ACCEPTED;
        if (mesh == CompiledSectionMesh.UNCOMPILED) return Failure.UNCOMPILED_MESH;
        if (mesh == CompiledSectionMesh.EMPTY) return Failure.EMPTY_MESH;
        var draw = mesh.getSectionDraw(layer);
        if (draw == null) return layer == ChunkSectionLayer.SOLID ? Failure.SOLID_DRAW_MISSING : Failure.LAYER_DRAW_MISSING;
        if (mesh instanceof CompiledSectionMesh compiled) {
            if (!compiled.isVertexBufferUploaded(layer)) return Failure.VERTEX_UPLOAD_PENDING;
            if (!compiled.isIndexBufferUploaded(layer)) return Failure.INDEX_UPLOAD_PENDING;
        }
        if (slice == null) return Failure.SLICE_MISSING;
        if (!(slice.vertexBuffer() instanceof VulkanGpuBuffer buffer)) return Failure.NOT_VULKAN_BUFFER;
        boolean indexedTranslucent=layer==ChunkSectionLayer.TRANSLUCENT;
        if(indexedTranslucent) {
            // Vertex count derives from the verified vanilla QUADS compiler, not arbitrary custom meshes.
            if(mesh.getClass()!=CompiledSectionMesh.class) return Failure.NON_VANILLA_TRANSLUCENT_MESH;
            var sort=((CompiledSectionMesh)mesh).getTransparencyState();
            if(sort==null || sort.centroids().size()!=draw.indexCount()/6 || sort.indexType()!=draw.indexType()) return Failure.TRANSLUCENT_SORT_STATE_INVALID;
            if(!draw.hasCustomIndexBuffer() || !(slice.indexBuffer() instanceof VulkanGpuBuffer index)) return Failure.INDEX_SLICE_MISSING;
            int width=draw.indexType()==null ? 0 : draw.indexType().bytes;
            if(width!=2 && width!=4) return Failure.INDEX_FORMAT_UNSUPPORTED;
            if(!validIndexRange(draw.indexCount(),width,slice.indexBufferOffset(),index.size(),index.vkBuffer(),index.isClosed(),(index.usage()&GpuBuffer.USAGE_COPY_SRC)!=0)) return Failure.INDEX_RANGE_INVALID;
        }
        var pos = format.getElement("Position");
        return rangeFailure(draw.indexCount(), draw.hasCustomIndexBuffer() && !indexedTranslucent, format.getVertexSize(),
                pos == null ? -1 : pos.offset(), pos != null && pos.format() == GpuFormat.RGB32_FLOAT,
                slice.vertexBufferOffset(), buffer.size(), buffer.vkBuffer(), buffer.isClosed(),
                (buffer.usage() & GpuBuffer.USAGE_COPY_SRC) != 0);
    }
    public static boolean validIndexRange(int count,int width,long offset,long size,long handle,boolean closed,boolean copySrc) {
        long bytes=(long)count*width;
        return count>0 && count%6==0 && (width==2 || width==4) && offset>=0 && (offset&3)==0
                && bytes<=size && offset<=size-bytes && handle!=0 && !closed && copySrc;
    }
    public static Failure rangeFailure(int indices, boolean custom, int stride, int positionOffset, boolean rgb32,
                                       long offset, long bufferBytes, long handle, boolean closed, boolean copySrc) {
        if (custom) return Failure.CUSTOM_INDICES_UNSUPPORTED;
        if (indices <= 0 || indices % 6 != 0) return Failure.INVALID_INDEX_COUNT;
        if (!rgb32) return Failure.POSITION_FORMAT_UNSUPPORTED;
        if (stride < 12 || stride % 4 != 0 || positionOffset < 0 || positionOffset % 4 != 0 || positionOffset > stride - 12)
            return Failure.INVALID_VERTEX_LAYOUT;
        long bytes = (long)(indices / 6) * 4 * stride;
        if (bytes <= 0) return Failure.VERTEX_RANGE_EMPTY;
        if (bytes > 8L * 1024 * 1024) return Failure.SECTION_TOO_LARGE;
        if (handle == 0) return Failure.ZERO_BUFFER_HANDLE;
        if (closed) return Failure.BUFFER_CLOSED;
        if (!copySrc) return Failure.COPY_SRC_MISSING;
        if ((offset & 3) != 0) return Failure.MISALIGNED_OFFSET;
        if (offset < 0 || bytes > bufferBytes || offset > bufferBytes - bytes) return Failure.RANGE_OUT_OF_BOUNDS;
        return Failure.OK;
    }
    public static String identity(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }
    public static String meshState(SectionMesh mesh) {
        if (mesh == null) return "null";
        if (mesh == CompiledSectionMesh.UNCOMPILED) return "UNCOMPILED";
        if (mesh == CompiledSectionMesh.EMPTY) return "EMPTY";
        var draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
        if (draw == null) return "accepted mesh without SOLID draw";
        if (mesh instanceof CompiledSectionMesh compiled)
            return "SOLID vertexUploaded=" + compiled.isVertexBufferUploaded(ChunkSectionLayer.SOLID)
                    + " indexUploaded=" + compiled.isIndexBufferUploaded(ChunkSectionLayer.SOLID);
        return "custom SectionMesh implementation";
    }
    private SectionGeometrySanity() {}
}
