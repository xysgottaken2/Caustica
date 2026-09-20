package dev.xys.vulkanrt.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static dev.xys.vulkanrt.render.VulkanRayTracingContext.*;

/** Real triangle BLAS and instance TLAS builds. Scratch/input lifetimes are tied to the batch.
 * RUNTIME VERIFIED: NO. Update supports fixed instance count; changed topology requires rebuild. */
public final class AccelerationStructureManager {
    private final VulkanRayTracingContext context;
    public AccelerationStructureManager(VulkanRayTracingContext context) { this.context = context; }

    public static final class Structure implements AutoCloseable {
        private final VkDevice device;
        private final GpuBuffer storage;
        private GpuBuffer vertices, indices;
        private long handle, address;
        public final int type, count;
        private boolean built;
        private Structure(VkDevice device, GpuBuffer storage, long handle, long address, int type, int count) {
            this.device = device; this.storage = storage; this.handle = handle; this.address = address;
            this.type = type; this.count = count;
        }
        public int indexBytes() { return indexBytes; }
        public long indexAddress() { return indices.address(); }
        private int indexBytes; // original indexed TRANSLUCENT snapshot only
        public long handle() { return handle; }
        public long address() { return address; }
        /** Owned, unchanged interleaved chunk vertices; never the borrowed Uber allocation. */
        public long vertexAddress() {
            if (vertices == null) throw new IllegalStateException("AS has no vertex buffer");
            return vertices.address();
        }
        @Override public void close() {
            if (handle != 0) { vkDestroyAccelerationStructureKHR(device, handle, null); handle = 0; address = 0; }
            storage.close();
            if (vertices != null) vertices.close();
            if (indices != null) indices.close();
        }
    }

    public record Instance(Structure blas, float x, float y, float z, int customIndex, int mask) {
        public Instance(Structure blas,float x,float y,float z,int customIndex) { this(blas,x,y,z,customIndex,0xff); }
        public Instance {
            if (blas.type != VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR || blas.handle() == 0) throw new IllegalArgumentException("Not a live BLAS");
            if(mask<=0 || (mask&~0xff)!=0) throw new IllegalArgumentException("Invalid instance mask");
            if ((customIndex & ~0xffffff) != 0) throw new IllegalArgumentException("instanceCustomIndex is 24 bits");
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) throw new IllegalArgumentException("Non-finite transform");
        }
    }

    /** xyz float32 vertices and uint32 triangle-list indices. Preserves source mesh: no tessellation. */
    public Structure buildBlas(CommandBatch batch, float[] xyz, int[] triangleIndices) {
        if (xyz.length == 0 || xyz.length % 3 != 0 || triangleIndices.length == 0 || triangleIndices.length % 3 != 0)
            throw new IllegalArgumentException("Invalid triangle mesh");
        int vertexCount = xyz.length / 3, primitives = triangleIndices.length / 3;
        if (Long.compareUnsigned(primitives, context.capabilities().maxPrimitiveCount()) > 0) throw new IllegalArgumentException("Too many primitives");
        for (float value : xyz) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vertex");
        for (int index : triangleIndices) if (index < 0 || index >= vertexCount) throw new IllegalArgumentException("Index out of bounds");
        GpuBuffer vertices = null, indices = null;
        Structure result = null;
        ByteBuffer vertexBytes = MemoryUtil.memAlloc(Math.multiplyExact(xyz.length, 4));
        ByteBuffer indexBytes = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            indexBytes = MemoryUtil.memAlloc(Math.multiplyExact(triangleIndices.length, 4));
            vertexBytes.asFloatBuffer().put(xyz); indexBytes.asIntBuffer().put(triangleIndices);
            vertices = GpuBuffer.upload(context, batch, vertexBytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
            indices = GpuBuffer.upload(context, batch, indexBytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack).sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            geometry.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(12).maxVertex(vertexCount - 1).indexType(VK_INDEX_TYPE_UINT32);
            geometry.geometry().triangles().vertexData().deviceAddress(vertices.address());
            geometry.geometry().triangles().indexData().deviceAddress(indices.address());
            result = allocateAndBuild(batch, geometry, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, primitives, null, false);
            result.vertices = vertices; result.indices = indices;
            return result;
        } catch (RuntimeException | Error failure) {
            if (result != null) result.close();
            if (vertices != null) vertices.close();
            if (indices != null) indices.close();
            throw failure;
        } finally { MemoryUtil.memFree(vertexBytes); if (indexBytes != null) MemoryUtil.memFree(indexBytes); }
    }

    /** Copy ONLY an accepted vanilla UberGpuBuffer section range, preserving its full vertex layout.
     * Vanilla heaps have TRANSFER_SRC but not AS_BUILD_INPUT/BDA usage. They cannot be passed
     * directly to the AS builder or retroactively given those flags. No CPU readback/tessellation.
     * Caller holds the dispatcher lock; all GPU uploads/copies are ordered on the render thread. */
    public Structure buildSectionBlas(CommandBatch batch,
            com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer source, long sourceOffset,
            dev.xys.vulkanrt.geometry.SectionGeometryLayout layout) {
        return buildSectionBlas(batch,source,sourceOffset,layout,false);
    }
    static int sectionGeometryFlags(boolean cutout) {
        return cutout ? VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR : VK_GEOMETRY_OPAQUE_BIT_KHR;
    }
    public Structure buildSectionBlas(CommandBatch batch,
            com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer source, long sourceOffset,
            dev.xys.vulkanrt.geometry.SectionGeometryLayout layout, boolean cutout) {
        long bytes = layout.vertexBytes();
        if (source.isClosed() || (source.usage() & com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_SRC) == 0
                || sourceOffset < 0 || sourceOffset > source.size() - bytes || (sourceOffset & 3) != 0)
            throw new IllegalArgumentException("Invalid/non-copyable UberGpuBuffer section range");
        if (Long.compareUnsigned(layout.triangles(), context.capabilities().maxPrimitiveCount()) > 0)
            throw new IllegalArgumentException("Section exceeds AS primitive limit");
        GpuBuffer vertices = null, indices = null;
        Structure result = null;
        ByteBuffer indexBytes = MemoryUtil.memAlloc(Math.multiplyExact(layout.indexCount(), 4));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vertices = new GpuBuffer(context, bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, false, true);
            memoryBarrier(batch.commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR, VK_ACCESS_2_MEMORY_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR, VK_ACCESS_2_TRANSFER_READ_BIT_KHR);
            vkCmdCopyBuffer(batch.commands, source.vkBuffer(), vertices.handle(),
                    VkBufferCopy.calloc(1, stack).srcOffset(sourceOffset).dstOffset(0).size(bytes));
            // SOLID and CUTOUT have no per-section index allocation: vanilla uses shared sequential QUADS.
            // Reproduce that topology only, without changing/re-tessellating a single vertex.
            var quads = indexBytes.asIntBuffer();
            for (int v = 0; v < layout.vertexCount(); v += 4)
                quads.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
            indices = GpuBuffer.upload(context, batch, indexBytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack).sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(sectionGeometryFlags(cutout));
            geometry.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(layout.stride()).maxVertex(layout.vertexCount() - 1).indexType(VK_INDEX_TYPE_UINT32);
            geometry.geometry().triangles().vertexData().deviceAddress(vertices.address() + layout.positionOffset());
            geometry.geometry().triangles().indexData().deviceAddress(indices.address());
            result = allocateAndBuild(batch, geometry, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, layout.triangles(), null, false);
            result.vertices = vertices; result.indices = indices;
            org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info(
                    "[RT] Chunk BLAS buffers: layer={} vertex=0x{}, index=0x{}, triangles={}; original interleaved attributes retained",
                    cutout ? "CUTOUT (non-opaque/any-hit)" : "SOLID (opaque)",
                    Long.toHexString(vertices.handle()), Long.toHexString(indices.handle()), layout.triangles());
            return result;
        } catch (RuntimeException | Error failure) {
            if (result != null) result.close();
            if (vertices != null) vertices.close();
            if (indices != null) indices.close();
            throw failure;
        } finally { MemoryUtil.memFree(indexBytes); }
    }

    /** Original vertex AND sorted index bytes, GPU to GPU. A later index-only camera re-sort is
     * merely a permutation of these same quads; this immutable snapshot stays geometrically valid. */
    public Structure buildTranslucentBlas(CommandBatch batch,dev.xys.vulkanrt.geometry.TerrainDrawCapture.Draw draw) {
        var layout=draw.layout();
        var vertexFailure=dev.xys.vulkanrt.geometry.SectionGeometrySanity.rangeFailure(layout.indexCount(),false,layout.stride(),layout.positionOffset(),true,
                draw.offset(),draw.buffer().size(),draw.buffer().vkBuffer(),draw.buffer().isClosed(),
                (draw.buffer().usage()&com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_SRC)!=0);
        if(vertexFailure!=dev.xys.vulkanrt.geometry.SectionGeometrySanity.Failure.OK) throw new IllegalArgumentException("TRANSLUCENT vertices before copy: "+vertexFailure);
        if(!dev.xys.vulkanrt.geometry.SectionGeometrySanity.validIndexRange(layout.indexCount(),draw.indexBytes(),draw.indexOffset(),
                draw.indexBuffer().size(),draw.indexBuffer().vkBuffer(),draw.indexBuffer().isClosed(),
                (draw.indexBuffer().usage()&com.mojang.renderpearl.api.buffers.GpuBuffer.USAGE_COPY_SRC)!=0))
            throw new IllegalArgumentException("Invalid TRANSLUCENT index slice before GPU copy");
        if(Long.compareUnsigned(layout.triangles(),context.capabilities().maxPrimitiveCount())>0) throw new IllegalArgumentException("TRANSLUCENT primitive limit");
        GpuBuffer vertices=null,indices=null; Structure result=null;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            int usage=VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
            vertices=new GpuBuffer(context,layout.vertexBytes(),usage,false,true);
            indices=new GpuBuffer(context,(long)layout.indexCount()*draw.indexBytes(),usage,false,true);
            memoryBarrier(batch.commands,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,VK_ACCESS_2_MEMORY_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,VK_ACCESS_2_TRANSFER_READ_BIT_KHR);
            vkCmdCopyBuffer(batch.commands,draw.buffer().vkBuffer(),vertices.handle(),VkBufferCopy.calloc(1,stack).srcOffset(draw.offset()).size(vertices.size));
            vkCmdCopyBuffer(batch.commands,draw.indexBuffer().vkBuffer(),indices.handle(),VkBufferCopy.calloc(1,stack).srcOffset(draw.indexOffset()).size(indices.size));
            var geometry=VkAccelerationStructureGeometryKHR.calloc(1,stack).sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
            geometry.geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(layout.stride()).maxVertex(layout.vertexCount()-1).indexType(draw.indexBytes()==2?VK_INDEX_TYPE_UINT16:VK_INDEX_TYPE_UINT32);
            geometry.geometry().triangles().vertexData().deviceAddress(vertices.address()+layout.positionOffset());
            geometry.geometry().triangles().indexData().deviceAddress(indices.address());
            result=allocateAndBuild(batch,geometry,VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,layout.triangles(),null,false);
            result.vertices=vertices; result.indices=indices; result.indexBytes=draw.indexBytes();
            return result;
        } catch(RuntimeException | Error failure) {
            if(result!=null) result.close();
            if(vertices!=null) vertices.close(); if(indices!=null) indices.close(); throw failure;
        }
    }

    public Structure buildTlas(CommandBatch batch, List<Instance> instances) { return writeTlas(batch, instances, null); }

    /** GPU-ordered update: writes a NEW instance input buffer, never overwrites an in-flight mapping.
     * The caller must preserve referenced BLAS until all old TLAS consumers have completed. */
    public void updateTlas(CommandBatch batch, Structure tlas, List<Instance> instances) {
        if (tlas.type != VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR || !tlas.built || tlas.count != instances.size())
            throw new IllegalArgumentException("TLAS UPDATE requires built structure and unchanged count");
        writeTlas(batch, instances, tlas);
    }

    private Structure writeTlas(CommandBatch batch, List<Instance> instances, Structure existing) {
        int count = instances.size();
        if (count == 0 || Long.compareUnsigned(count, context.capabilities().maxInstanceCount()) > 0)
            throw new IllegalArgumentException("Unsupported instance count");
        ByteBuffer bytes = MemoryUtil.memCalloc(Math.multiplyExact(count, VkAccelerationStructureInstanceKHR.SIZEOF));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var records = VkAccelerationStructureInstanceKHR.create(MemoryUtil.memAddress(bytes), count);
            for (int i = 0; i < count; i++) {
                Instance instance = instances.get(i);
                var record = records.get(i);
                // VkTransformMatrixKHR is row-major 3x4, not JOML's column-major matrix storage.
                record.transform().matrix(0, 1).matrix(5, 1).matrix(10, 1)
                        .matrix(3, instance.x()).matrix(7, instance.y()).matrix(11, instance.z());
                record.instanceCustomIndex(instance.customIndex()).mask(instance.mask()).instanceShaderBindingTableRecordOffset(0)
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(instance.blas().address());
            }
            var input = batch.temporary(GpuBuffer.upload(context, batch, bytes, VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR));
            if ((input.address() & 15) != 0) throw new IllegalStateException("TLAS input address must be 16-byte aligned");
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(input.address());
            return allocateAndBuild(batch, geometry, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, count, existing, true);
        } finally { MemoryUtil.memFree(bytes); }
    }

    /** The same descriptor is used for BOTH size query and build/update recording.
     * LWJGL 3.4.3 pGeometries sets only the pointer: geometryCount is NOT inferred,
     * because Vulkan also permits ppGeometries. Leaving the calloc default of zero
     * constructs an empty AS and causes the triangle diagnostic to return only misses. */
    static VkAccelerationStructureBuildGeometryInfoKHR.Buffer singleGeometryBuildInfo(
            MemoryStack stack, VkAccelerationStructureGeometryKHR.Buffer geometry,
            int type, boolean update, boolean allowUpdate) {
        if (geometry.remaining() != 1)
            throw new IllegalArgumentException("This builder supplies exactly one geometry and one build range");
        if (update && !allowUpdate) throw new IllegalArgumentException("UPDATE requires ALLOW_UPDATE");
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default().type(type)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR
                        | (allowUpdate ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR : 0))
                .mode(update ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .geometryCount(geometry.remaining()).pGeometries(geometry);
    }

    private Structure allocateAndBuild(CommandBatch batch, VkAccelerationStructureGeometryKHR.Buffer geometry,
                                       int type, int primitives, Structure existing, boolean allowUpdate) {
        Structure result = existing;
        GpuBuffer storage = null;
        long createdHandle = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = singleGeometryBuildInfo(stack, geometry, type, existing != null, allowUpdate);
            var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            if (!RtOptions.CHUNKS) org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info(
                    "[RT] {} build input: geometryCount={}, primitiveCount={}, mode={}",
                    type == VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR ? "BLAS" : "TLAS",
                    info.geometryCount(), primitives, existing == null ? "BUILD" : "UPDATE");
            vkGetAccelerationStructureBuildSizesKHR(context.device(), VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    info.get(0), stack.ints(primitives), sizes);
            if (result == null) {
                storage = new GpuBuffer(context, sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, true);
                var create = VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default().buffer(storage.handle())
                        .offset(0).size(sizes.accelerationStructureSize()).type(type);
                var handle = stack.mallocLong(1);
                check(vkCreateAccelerationStructureKHR(context.device(), create, null, handle), "vkCreateAccelerationStructureKHR");
                createdHandle = handle.get(0);
                result = new Structure(context.device(), storage, createdHandle, 0, type, primitives);
                result.address = vkGetAccelerationStructureDeviceAddressKHR(context.device(),
                        VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(result.handle));
                if (result.address == 0) throw new IllegalStateException("AS address is zero");
            }
            long scratchSize = existing == null ? sizes.buildScratchSize() : sizes.updateScratchSize();
            long alignment = Integer.toUnsignedLong(context.capabilities().scratchAlignment());
            var scratch = batch.temporary(new GpuBuffer(context, Math.addExact(Math.max(1, scratchSize), alignment), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, true));
            info.dstAccelerationStructure(result.handle()).srcAccelerationStructure(existing == null ? 0 : existing.handle());
            info.scratchData().deviceAddress(GpuBuffer.alignUp(scratch.address(), alignment));
            // Covers previous trace reads (UPDATE), transfer input writes and prior BLAS builds.
            memoryBarrier(batch.commands, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,
                    VK_ACCESS_2_MEMORY_WRITE_BIT_KHR | VK_ACCESS_2_MEMORY_READ_BIT_KHR,
                    VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_2_SHADER_READ_BIT_KHR | VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
            var range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(primitives);
            vkCmdBuildAccelerationStructuresKHR(batch.commands, info, stack.pointers(range.address()));
            memoryBarrier(batch.commands, VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_2_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
            result.built = true;
            return result;
        } catch (RuntimeException | Error failure) {
            if (existing == null) {
                if (result != null) result.close();
                else {
                    if (createdHandle != 0) vkDestroyAccelerationStructureKHR(context.device(), createdHandle, null);
                    if (storage != null) storage.close();
                }
            }
            throw failure;
        }
    }
}
