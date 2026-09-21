package dev.xys.vulkanrt.render;

import org.joml.Matrix4fc;
import org.joml.Vector3fc;
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

/** KHR ray tracing shader groups, immutable SBT and per-scene descriptor sets.
 * RUNTIME VERIFIED: NO. No graphics/fullscreen shader is used to generate the RT image. */
public final class RayTracingPipeline implements AutoCloseable {
    private final VulkanRayTracingContext context;
    private long descriptorLayout, pipelineLayout, pipeline;
    private GpuBuffer sbt, hitProbe;
    private final boolean chunks = RtOptions.CHUNKS;
    private final boolean materialDiagnostics = chunks && (Boolean.getBoolean("nativevulkanrt.materialDiagnostics") || Boolean.getBoolean("nativevulkanrt.entityDiagnostics"));
    private final int groupCount = chunks ? 5 : 3;
    private long raygenAddress, missAddress, hitAddress, missSize, hitSize, stride;
    /** mat4 + origin/frame + solar direction/intensity + sky/ambient colors and factors. */
    public static final int PUSH_BYTES = 160;

    public RayTracingPipeline(VulkanRayTracingContext context) {
        this.context = context;
        org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT] Creating ray tracing pipeline...");
        long[] modules = new long[chunks ? 7 : 3];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bindings = VkDescriptorSetLayoutBinding.calloc(chunks ? 8 : 2, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
            if (chunks) {
                bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
                bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR | VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
                bindings.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                bindings.get(5).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(EntityGeometryManager.TEXTURES).stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR|VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
                bindings.get(6).binding(6).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR);
                bindings.get(7).binding(7).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR|VK_SHADER_STAGE_ANY_HIT_BIT_KHR);
                hitProbe = new GpuBuffer(context,ChunkTextureSampling.PROBE_BYTES,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,false,false);
                org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT] Chunks material shader: vanilla atlas + GPU vertex addresses; GPU-only center-hit HUD={}; no readback, primary+shadow hit groups/SBT", materialDiagnostics);
            }
            var out = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(context.device(), VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, out), "vkCreateDescriptorSetLayout(RT)");
            descriptorLayout = out.get(0);
            var pushRange = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK_SHADER_STAGE_RAYGEN_BIT_KHR | VK_SHADER_STAGE_MISS_BIT_KHR)
                    .offset(0).size(PUSH_BYTES);
            check(vkCreatePipelineLayout(context.device(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(descriptorLayout)).pPushConstantRanges(pushRange), null, out), "vkCreatePipelineLayout(RT)");
            pipelineLayout = out.get(0);
            String[] resources = chunks
                    ? new String[]{"chunks.rgen.spv", "chunks.rmiss.spv", "chunks.shadow.rmiss.spv",
                    "chunks.rchit.spv", "chunks.rahit.spv", "chunks.shadow.rchit.spv", "chunks.shadow.rahit.spv"}
                    : new String[]{"primary.rgen.spv", "primary.rmiss.spv", "primary.rchit.spv"};
            int[] stageBits = chunks
                    ? new int[]{VK_SHADER_STAGE_RAYGEN_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR,
                    VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, VK_SHADER_STAGE_ANY_HIT_BIT_KHR,
                    VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, VK_SHADER_STAGE_ANY_HIT_BIT_KHR}
                    : new int[]{VK_SHADER_STAGE_RAYGEN_BIT_KHR, VK_SHADER_STAGE_MISS_BIT_KHR, VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR};
            var stages = VkPipelineShaderStageCreateInfo.calloc(modules.length, stack);
            for (int i = 0; i < modules.length; i++) {
                modules[i] = loadModule(resources[i]);
                stages.get(i).sType$Default().stage(stageBits[i]).module(modules[i]).pName(stack.UTF8("main"));
            }
            var groups = VkRayTracingShaderGroupCreateInfoKHR.calloc(groupCount, stack);
            for (int i = 0; i < groupCount; i++) groups.get(i).sType$Default()
                    .generalShader(VK_SHADER_UNUSED_KHR).closestHitShader(VK_SHADER_UNUSED_KHR)
                    .anyHitShader(VK_SHADER_UNUSED_KHR).intersectionShader(VK_SHADER_UNUSED_KHR);
            groups.get(0).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(0);
            groups.get(1).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(1);
            if (chunks) {
                // Primary and shadow payloads have distinct incoming interfaces. They still share the
                // same TLAS/material rows; the ray's SBT offset selects the corresponding hit group.
                groups.get(2).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_GENERAL_KHR).generalShader(2);
                groups.get(3).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                        .closestHitShader(3).anyHitShader(4);
                groups.get(4).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR)
                        .closestHitShader(5).anyHitShader(6);
            } else {
                groups.get(2).type(VK_RAY_TRACING_SHADER_GROUP_TYPE_TRIANGLES_HIT_GROUP_KHR).closestHitShader(2);
            }
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
        sbt = new GpuBuffer(context, Math.addExact(groupCount * step, baseAlignment), VK_BUFFER_USAGE_SHADER_BINDING_TABLE_BIT_KHR, true, true);
        long alignedBase = GpuBuffer.alignUp(sbt.address(), baseAlignment);
        ByteBuffer handles = MemoryUtil.memAlloc(Math.multiplyExact(limits.handleSize(), groupCount));
        try {
            check(vkGetRayTracingShaderGroupHandlesKHR(context.device(), pipeline, 0, groupCount, handles), "vkGetRayTracingShaderGroupHandlesKHR");
            var target = sbt.mapped();
            for (int group = 0; group < groupCount; group++) {
                int offset = Math.toIntExact(alignedBase - sbt.address() + group * step);
                for (int b = 0; b < limits.handleSize(); b++) target.put(offset + b, handles.get(group * limits.handleSize() + b));
            }
            raygenAddress = alignedBase;
            missAddress = alignedBase + step;
            missSize = (chunks ? 2 : 1) * step;
            hitAddress = alignedBase + (chunks ? 3 : 2) * step;
            hitSize = (chunks ? 2 : 1) * step;
            org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").info("[RT] Shader Binding Table created: raygen=0x{}, miss=0x{} ({} records), hit=0x{} ({} records), stride={}",
                    Long.toHexString(raygenAddress), Long.toHexString(missAddress), missSize / stride, Long.toHexString(hitAddress), hitSize / stride, stride);
        } finally { MemoryUtil.memFree(handles); }
    }

    public Bindings bind(AccelerationStructureManager.Structure tlas, RtOutputImage output) { return new Bindings(tlas, output, null, null, null, null); }
    public Bindings bind(AccelerationStructureManager.Structure tlas, RtOutputImage output,
                         ChunkMaterialTable materials, TerrainAtlasCapture.Atlas atlas,EntityGeometryManager.Frame entities,
                         TerrainAtlasCapture.Atlas particleAtlas) { return new Bindings(tlas,output,materials,atlas,entities,particleAtlas); }

    /** Pool/set are never rewritten in flight. Allocate a new binding after TLAS replacement/resize. */
    public final class Bindings implements AutoCloseable {
        private long pool, set;
        private Bindings(AccelerationStructureManager.Structure tlas, RtOutputImage output,
                         ChunkMaterialTable materials, TerrainAtlasCapture.Atlas atlas,EntityGeometryManager.Frame entities,
                         TerrainAtlasCapture.Atlas particleAtlas) {
            if (chunks && (materials == null || materials.count != tlas.count || atlas == null || !atlas.live()))
                throw new IllegalArgumentException("Chunk material rows/TLAS count/atlas mismatch");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var sizes = VkDescriptorPoolSize.calloc(chunks ? 4 : 2, stack);
                sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
                sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
                if (chunks) {
                    sizes.get(2).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(3);
                    sizes.get(3).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2+EntityGeometryManager.TEXTURES);
                }
                var result = stack.mallocLong(1);
                check(vkCreateDescriptorPool(context.device(), VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, result), "vkCreateDescriptorPool(RT)");
                pool = result.get(0);
                check(vkAllocateDescriptorSets(context.device(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(pool).pSetLayouts(stack.longs(descriptorLayout)), result), "vkAllocateDescriptorSets(RT)");
                set = result.get(0);
                var asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default().pAccelerationStructures(stack.longs(tlas.handle()));
                var image = VkDescriptorImageInfo.calloc(1, stack).imageView(output.view()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                var writes = VkWriteDescriptorSet.calloc(chunks ? 8 : 2, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(0).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).pNext(asWrite);
                writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(image);
                if (chunks) {
                    if(atlas == null || !atlas.live()) throw new IllegalStateException("Missing live fallback atlas for chunk material binding");
                    if(TerrainAtlasCapture.current()!=null) TerrainAtlasCapture.binding(atlas);
                    var table = VkDescriptorBufferInfo.calloc(1,stack).buffer(materials.buffer.handle()).offset(0).range(materials.buffer.size);
                    var atlasImage = VkDescriptorImageInfo.calloc(1,stack).imageView(atlas.view().vkImageView())
                            .sampler(atlas.sampler().vkSampler()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    var probe = VkDescriptorBufferInfo.calloc(1,stack).buffer(hitProbe.handle()).offset(0).range(ChunkTextureSampling.PROBE_BYTES);
                    writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(table);
                    writes.get(3).sType$Default().dstSet(set).dstBinding(3).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(atlasImage);
                    writes.get(4).sType$Default().dstSet(set).dstBinding(4).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(probe);
                    var entityImages=VkDescriptorImageInfo.calloc(EntityGeometryManager.TEXTURES,stack);
                    for(int i=0;i<EntityGeometryManager.TEXTURES;i++) {
                        var t=i<entities.textures().size()?entities.textures().get(i):atlas;
                        if(!t.live()) throw new IllegalStateException("Entity borrowed texture expired before bind");
                        entityImages.get(i).imageView(t.view().vkImageView()).sampler(t.sampler().vkSampler()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    }
                    writes.get(5).sType$Default().dstSet(set).dstBinding(5).descriptorCount(EntityGeometryManager.TEXTURES).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(entityImages);
                    var entityHud=VkDescriptorBufferInfo.calloc(1,stack).buffer(entities.hud().handle()).offset(0).range(entities.hud().size);
                    writes.get(6).sType$Default().dstSet(set).dstBinding(6).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(entityHud);
                    var particleImage = particleAtlas != null && particleAtlas.live() ? particleAtlas : atlas;
                    var particleInfo = VkDescriptorImageInfo.calloc(1,stack).imageView(particleImage.view().vkImageView())
                            .sampler(particleImage.sampler().vkSampler()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(7).sType$Default().dstSet(set).dstBinding(7).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(particleInfo);
                }
                vkUpdateDescriptorSets(context.device(), writes, null);
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }
        @Override public void close() { if (pool != 0) { vkDestroyDescriptorPool(context.device(), pool, null); pool = 0; set = 0; } }
    }

    public void trace(VkCommandBuffer cmd, Bindings bindings, Matrix4fc inverseViewProjection,
                      float originX, float originY, float originZ, int width, int height, boolean testTriangle,
                      Vector3fc sunDirection, float sunIntensity, Vector3fc skyColor,
                      Vector3fc skyLightColor, float skyFactor, Vector3fc ambientColor, float rainBrightness) {
        if (width <= 0 || height <= 0 || (long)width * height > Integer.toUnsignedLong(context.capabilities().maxDispatchInvocations()))
            throw new IllegalArgumentException("Ray dispatch dimensions exceed limit");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_RAY_TRACING_KHR, pipelineLayout, 0, stack.longs(bindings.set), null);
            var constants = stack.calloc(PUSH_BYTES);
            inverseViewProjection.get(0, constants);
            constants.putFloat(64, originX).putFloat(68, originY).putFloat(72, originZ);
            constants.putInt(80, width).putInt(84, height).putInt(88, testTriangle ? 1 : 0);
            // Keep all world lighting in the same push contract used by raygen and miss.
            constants.putFloat(96, sunDirection.x()).putFloat(100, sunDirection.y()).putFloat(104, sunDirection.z()).putFloat(108, sunIntensity);
            constants.putFloat(112, skyColor.x()).putFloat(116, skyColor.y()).putFloat(120, skyColor.z()).putFloat(124, 1.0f);
            constants.putFloat(128, skyLightColor.x()).putFloat(132, skyLightColor.y()).putFloat(136, skyLightColor.z()).putFloat(140, skyFactor);
            constants.putFloat(144, ambientColor.x()).putFloat(148, ambientColor.y()).putFloat(152, ambientColor.z()).putFloat(156, rainBrightness);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_RAYGEN_BIT_KHR, 0, constants);
            var raygen = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(raygenAddress).stride(stride).size(stride);
            var miss = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(missAddress).stride(stride).size(missSize);
            var hit = VkStridedDeviceAddressRegionKHR.calloc(stack).deviceAddress(hitAddress).stride(stride).size(hitSize);
            var callable = VkStridedDeviceAddressRegionKHR.calloc(stack);
            if (chunks) {
                // Includes copied vertices/table, vanilla atlas uploads/animation, prior probe readers,
                // and the serial shadow-ray payloads launched by chunks.rgen.
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
