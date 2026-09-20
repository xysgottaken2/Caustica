package dev.xys.vulkanrt.render;

import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static dev.xys.vulkanrt.render.VulkanRayTracingContext.check;

/** Three real KHR shader groups, immutable SBT and per-scene descriptor sets.
 * RUNTIME VERIFIED: NO. No graphics/fullscreen shader is used to generate the RT image. */
public final class RayTracingPipeline implements AutoCloseable {
    private final VulkanRayTracingContext context;
    private long descriptorLayout, pipelineLayout, pipeline;
    private GpuBuffer sbt, hitProbe;
    private final boolean chunks = RtOptions.CHUNKS;
    private final boolean materialDiagnostics = chunks && Boolean.getBoolean("nativevulkanrt.materialDiagnostics");
    private long raygenAddress, missAddress, hitAddress, stride;
    public static final int PUSH_BYTES = 96;

    public RayTracingPipeline(VulkanRayTracingContext context) {
        this.context = context;
        org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT] Creating ray tracing pipeline...");
        long[] modules = new long[3];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(chunks ? 5 : 2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            if (chunks) {
                bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
                bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR);
                bindings.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                hitProbe = new GpuBuffer(context,64,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,false,false);
                org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT] Chunks material shader: vanilla atlas + GPU vertex addresses; GPU-only center-hit HUD={}; no readback, unchanged 3-group SBT", materialDiagnostics);
            }
            var out = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(context.device(), VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, out), "vkCreateDescriptorSetLayout(RT)");
            descriptorLayout = out.get(0);
            var pushRange = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR).offset(0).size(PUSH_BYTES);
            check(vkCreatePipelineLayout(context.device(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(pushRange), null, out), "vkCreatePipelineLayout(RT)");
            pipelineLayout = out.get(0);
            String prefix = chunks ? "chunks" : "primary";
            String[] resources = {prefix + ".rgen.spv", prefix + ".rmiss.spv", prefix + ".rchit.spv"};
            int[] stageBits = {VK_SHADER_STAGE_RAYGEN_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR, VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR};
            var stages = VkPipelineShaderStageCreateInfo.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                modules[i] = loadModule(resources[i]);
                stages.get(i).sType$Default().stage(stageBits[i]).module(modules[i]).pName(stack.UTF8("main"));
            }
            var groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(3, stack);
            for (int i = 0; i < 3; i++) groups.get(i).sType$Default()
                    .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(VK_SHADER_UNUSED_KHR)
                    .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            groups.get(0).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0);
            groups.get(1).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(1);
            groups.get(2).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR).closestHitShader(2);
            var create = VkRayTracingPipelineCreateInfoKHR.calloc(1, stack).sType$Default()
                    .pStages(stages).pGroups(groups).maxPipelineRayRecursionDepth(1).layout(pipelineLayout)
                    .basePipelineIndex(-1).basePipelineHandle(VK_NULL_HANDLE);
            check(vkCreateRayTracingPipelinesKHR(context.device(), VK_NULL_HANDLE, VK_NULL_HANDLE, create, null, out), "vkCreateRayTracingPipelinesKHR");
            pipeline = out.get(0);
            org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT] Pipeline created: 0x{}", Long.toHexString(pipeline));
            buildShaderBindingTable();
        } catch (RuntimeException | Error failure) { close(); throw failure; }
        finally { for (long module : modules) if (module != 0) vkDestroyShaderModule(context.device(), module, null); }
    }

    private long loadModule(String resource) {
        byte[] bytes;
        try (var stream = RayTracingPipeline.class.getResourceAsStream("/assets/native_vulkan_rt/spirv/" + resource)) {
            if (stream == null) throw new IOException("Missing build-time SPIR-V: " + resource);
            bytes = stream.readAllBytes();
        } catch (IOException failure) { throw new IllegalStateException("Cannot load RT shader", failure); }
        if (bytes.length < 20 || (bytes.length & 3) != 0) throw new IllegalArgumentException("Invalid SPIR-V size");
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            code.put(bytes).flip();
            if (code.getInt(0) != 0x07230203) throw new IllegalArgumentException("Invalid SPIR-V magic");
            var out = stack.mallocLong(1);
            check(vkCreateShaderModule(context.device(), VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, out), "vkCreateShaderModule");
            return out.get(0);
        } finally { MemoryUtil.memFree(code); }
    }

    private void buildShaderBindingTable() {
        var limits = context.capabilities();
        stride = GpuBuffer.alignUp(limits.handleSize(), Integer.toUnsignedLong(limits.handleAlignment()));
        if (stride > Integer.toUnsignedLong(limits.maxGroupStride())) throw new IllegalStateException("SBT stride exceeds device limit");
        long baseAlignment = Integer.toUnsignedLong(limits.baseAlignment());
        long step = GpuBuffer.alignUp(stride, baseAlignment);
        sbt = new GpuBuffer(context, Math.addExact(3 * step, baseAlignment), VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, true, true);
        long alignedBase = GpuBuffer.alignUp(sbt.address(), baseAlignment);
        ByteBuffer handles = MemoryUtil.memAlloc(Math.multiplyExact(limits.handleSize(), 3));
        try {
            check(vkGetRayTracingShaderGroupHandlesKHR(context.device(), pipeline, 0, 3, handles), "vkGetRayTracingShaderGroupHandlesKHR");
            var target = sbt.mapped();
            for (int group = 0; group < 3; group++) {
                int offset = Math.toIntExact(alignedBase - sbt.address() + group * step);
                for (int b = 0; b < limits.handleSize(); b++) target.put(offset + b, handles.get(group * limits.handleSize() + b));
            }
            raygenAddress = alignedBase; missAddress = alignedBase + step; hitAddress = alignedBase + 2 * step;
            org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT] Shader Binding Table created: raygen=0x{}, miss=0x{}, hit=0x{}, stride={}",
                    Long.toHexString(raygenAddress), Long.toHexString(missAddress), Long.toHexString(hitAddress), stride);
        } finally { MemoryUtil.memFree(handles); }
    }

    public Bindings bind(AccelerationStructureManager.Structure tlas, RtOutputImage output) { return new Bindings(tlas, output, null, null); }
    public Bindings bind(AccelerationStructureManager.Structure tlas, RtOutputImage output,
                         ChunkMaterialTable materials, TerrainAtlasCapture.Atlas atlas) { return new Bindings(tlas,output,materials,atlas); }

    /** Pool/set are never rewritten in flight. Allocate a new binding after TLAS replacement/resize. */
    public final class Bindings implements AutoCloseable {
        private long pool, set;
        private Bindings(AccelerationStructureManager.Structure tlas, RtOutputImage output,
                         ChunkMaterialTable materials, TerrainAtlasCapture.Atlas atlas) {
            if (chunks && (materials == null || materials.count != tlas.count || atlas == null || !atlas.live()))
                throw new IllegalArgumentException("Chunk material rows/TLAS count/atlas mismatch");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var sizes = VkDescriptorPoolSize.calloc(chunks ? 4 : 2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                if (chunks) {
                    sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
                    sizes.get(3).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
                }
                var result = stack.mallocLong(1);
                check(vkCreateDescriptorPool(context.device(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, result), "vkCreateDescriptorPool(RT)");
                pool = result.get(0);
                check(vkAllocateDescriptorSets(context.device(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(pool).pSetLayouts(stack.longs(descriptorLayout)), result), "vkAllocateDescriptorSets(RT)");
                set = result.get(0);
                var asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(tlas.handle()));
                var image = VkDescriptorImageInfo.calloc(1, stack).imageView(output.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                var writes = VkWriteDescriptorSet.calloc(chunks ? 5 : 2, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).pNext(asWrite);
                writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(image);
                if (chunks) {
                    var table = VkDescriptorBufferInfo.calloc(1,stack).buffer(materials.buffer.handle()).offset(0).range(materials.buffer.size);
                    var atlasImage = VkDescriptorImageInfo.calloc(1,stack).imageView(atlas.view().vkImageView())
                            .sampler(atlas.sampler().vkSampler()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    var probe = VkDescriptorBufferInfo.calloc(1,stack).buffer(hitProbe.handle()).offset(0).range(64);
                    writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(table);
                    writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(atlasImage);
                    writes.get(4).sType$Default().dstSet(set).dstBinding(4).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(probe);
                }
                vkUpdateDescriptorSets(context.device(), writes, null);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        @Override public void close() { if (pool != 0) { vkDestroyDescriptorPool(context.device(), pool, null); pool = 0; set = 0; } }
    }

    public void trace(VkCommandBuffer cmd, Bindings bindings, Matrix4fc inverseViewProjection,
                      float originX, float originY, float originZ, int width, int height, boolean testTriangle) {
        if (width <= 0 || height <= 0 || (long)width * height > Integer.toUnsignedLong(context.capabilities().maxDispatchInvocations()))
            throw new IllegalArgumentException("Ray dispatch dimensions exceed limit");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, stack.longs(bindings.set), null);
            var constants = stack.calloc(PUSH_BYTES);
            inverseViewProjection.get(0, constants);
            constants.putFloat(64, originX).putFloat(68, originY).putFloat(72, originZ);
            constants.putInt(80, width).putInt(84, height).putInt(88, testTriangle ? 1 : 0);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_RAYGEN_BIT_KHR, 0, constants);
            var raygen = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(raygenAddress).stride(stride).size(stride);
            var miss = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(missAddress).stride(stride).size(stride);
            var hit = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(hitAddress).stride(stride).size(stride);
            var callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
            if (chunks) {
                // Includes copied vertices/table, vanilla atlas uploads/animation and prior probe readers.
                VulkanRayTracingContext.memoryBarrier(cmd,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,
                        VK_ACCESS_2_MEMORY_WRITE_BIT_KHR | VK_ACCESS_2_MEMORY_READ_BIT_KHR,
                        VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_READ_BIT_KHR | VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
                if (materialDiagnostics) {
                    constants.putInt(92,1);
                    vkCmdPushConstants(cmd,pipelineLayout,VK_SHADER_STAGE_RAYGEN_BIT_KHR,0,constants);
                    vkCmdTraceRaysKHR(cmd,raygen,miss,hit,callable,1,1,1);
                    VulkanRayTracingContext.memoryBarrier(cmd,VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
                            VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_READ_BIT_KHR);
                    constants.putInt(92,2);
                    vkCmdPushConstants(cmd,pipelineLayout,VK_SHADER_STAGE_RAYGEN_BIT_KHR,0,constants);
                }
            }
            vkCmdTraceRaysKHR(cmd, raygen, miss, hit, callable, width, height, 1);
            if (chunks) VulkanRayTracingContext.memoryBarrier(cmd,VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,
                    VK_ACCESS_2_SHADER_READ_BIT_KHR | VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,VK_ACCESS_2_MEMORY_WRITE_BIT_KHR | VK_ACCESS_2_MEMORY_READ_BIT_KHR);
        }
    }

    @Override public void close() {
        if (pipeline != 0) { vkDestroyPipeline(context.device(), pipeline, null); pipeline = 0; }
        if (sbt != null) sbt.close();
        if (hitProbe != null) hitProbe.close();
        if (pipelineLayout != 0) { vkDestroyPipelineLayout(context.device(), pipelineLayout, null); pipelineLayout = 0; }
        if (descriptorLayout != 0) { vkDestroyDescriptorSetLayout(context.device(), descriptorLayout, null); descriptorLayout = 0; }
    }
}
