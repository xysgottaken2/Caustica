package dev.xys.vulkanrt.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        public long handle() { return handle; }
        public long address() { return address; }
        @Override public void close() {
            if (handle != 0) { vkDestroyAccelerationStructureKHR(device, handle, null); handle = 0; address = 0; }
            storage.close();
            if (vertices != null) vertices.close();
            if (indices != null) indices.close();
        }
    }

    public record Instance(Structure blas, float x, float y, float z, int customIndex) {
        public Instance {
            if (blas.type != VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR || blas.handle() == 0) throw new IllegalArgumentException("Not a live BLAS");
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
                record.instanceCustomIndex(instance.customIndex()).mask(0xff).instanceShaderBindingTableRecordOffset(0)
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

    private Structure allocateAndBuild(CommandBatch batch, VkAccelerationStructureGeometryKHR.Buffer geometry,
                                       int type, int primitives, Structure existing, boolean allowUpdate) {
        Structure result = existing;
        GpuBuffer storage = null;
        long createdHandle = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default().type(type)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR | (allowUpdate ? VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR : 0))
                    .mode(existing == null ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR)
                    .pGeometries(geometry);
            var sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
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
