package dev.xys.vulkanrt.render;

import dev.xys.vulkanrt.geometry.TerrainDrawCapture;
import dev.xys.vulkanrt.geometry.SectionGeometryLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static dev.xys.vulkanrt.render.VulkanRayTracingContext.*;

/** GPU material lookup, NOT another terrain renderer or AS builder. Two compute passes associate
 * coincident, same-facing CUTOUT quads with existing SOLID faces; last matching quad wins.
 * No world/block sampling, vertex readback, new triangles, BLAS, TLAS or SBT records. */
public final class CoplanarOverlayMapper implements AutoCloseable {
    private final VulkanRayTracingContext context;
    private long layout, pipeline;
    public static final int PUSH_BYTES=72;
    public static final class Overlay implements AutoCloseable {
        final TerrainDrawCapture.Draw source;
        final long solidAddress;
        public final GpuBuffer vertices, matches;
        public final SectionGeometryLayout format;
        Overlay(TerrainDrawCapture.Draw source,long solidAddress,GpuBuffer vertices,GpuBuffer matches) {
            this.source=source;this.solidAddress=solidAddress;this.vertices=vertices;this.matches=matches;this.format=source.layout();
        }
        public boolean same(TerrainDrawCapture.Draw draw,long solid) {
            return draw!=null && solidAddress==solid && source.owner()==draw.owner() && source.mesh()==draw.mesh()
                    && source.buffer()==draw.buffer() && source.offset()==draw.offset() && source.layout().equals(draw.layout());
        }
        @Override public void close() { matches.close(); vertices.close(); }
    }
    public CoplanarOverlayMapper(VulkanRayTracingContext context) {
        this.context=context; long module=0;
        try (MemoryStack stack=MemoryStack.stackPush()) {
            var n=stack.ints(0); vkGetPhysicalDeviceQueueFamilyProperties(context.physicalDevice(),n,null);
            var families=VkQueueFamilyProperties.calloc(n.get(0),stack);
            vkGetPhysicalDeviceQueueFamilyProperties(context.physicalDevice(),n,families);
            if((families.get(context.graphicsQueue().queueFamilyIndex()).queueFlags()&VK_QUEUE_COMPUTE_BIT)==0)
                throw new IllegalStateException("Vanilla graphics queue lacks compute for coplanar overlay mapping");
            byte[] bytes;
            try(var input=getClass().getResourceAsStream("/assets/native_vulkan_rt/spirv/overlay.comp.spv")) {
                if(input==null) throw new IllegalStateException("Missing overlay compute shader");
                bytes=input.readAllBytes();
            }
            var code=MemoryUtil.memAlloc(bytes.length);
            var out=stack.mallocLong(1);
            try { code.put(bytes).flip(); check(vkCreateShaderModule(context.device(),VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),null,out),"overlay shader"); module=out.get(0); }
            finally { MemoryUtil.memFree(code); }
            var range=VkPushConstantRange.calloc(1,stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            check(vkCreatePipelineLayout(context.device(),VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(range),null,out),"overlay layout"); layout=out.get(0);
            var info=VkComputePipelineCreateInfo.calloc(1,stack).sType$Default().layout(layout);
            info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(context.device(),0,info,null,out),"overlay pipeline"); pipeline=out.get(0);
        } catch(java.io.IOException failure) { close(); throw new IllegalStateException(failure); }
        catch(RuntimeException | Error failure) { close(); throw failure; }
        finally { if(module!=0) vkDestroyShaderModule(context.device(),module,null); }
    }
    static int bucketCount(int quads) {
        if(quads<=0 || quads>1_000_000) throw new IllegalArgumentException("Unsupported overlay quad count");
        return Integer.highestOneBit(Math.max(64,quads*2)-1)<<1;
    }
    /** Caller revalidates captured CUTOUT range and holds dispatcher lock through batch enqueue. */
    public Overlay build(CommandBatch batch,TerrainDrawCapture.Draw cutout,long solidAddress,SectionGeometryLayout solid) {
        GpuBuffer vertices=null,matches=null;
        try(MemoryStack stack=MemoryStack.stackPush()) {
            int cq=cutout.layout().vertexCount()/4,sq=solid.vertexCount()/4,buckets=bucketCount(cq);
            vertices=new GpuBuffer(context,cutout.layout().vertexBytes(),VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,false,true);
            matches=new GpuBuffer(context,(long)sq*16,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,false,true);
            var heads=batch.temporary(new GpuBuffer(context,(long)buckets*4,VK_BUFFER_USAGE_TRANSFER_DST_BIT|VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,false,true));
            var links=batch.temporary(new GpuBuffer(context,(long)cq*4,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,false,true));
            memoryBarrier(batch.commands,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,VK_ACCESS_2_MEMORY_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,VK_ACCESS_2_TRANSFER_READ_BIT_KHR|VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR);
            vkCmdCopyBuffer(batch.commands,cutout.buffer().vkBuffer(),vertices.handle(),VkBufferCopy.calloc(1,stack).srcOffset(cutout.offset()).dstOffset(0).size(vertices.size));
            vkCmdFillBuffer(batch.commands,heads.handle(),0,heads.size,-1);
            memoryBarrier(batch.commands,VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT_KHR,VK_ACCESS_2_MEMORY_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_READ_BIT_KHR|VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
            var push=stack.calloc(PUSH_BYTES);
            push.putLong(0,solidAddress).putLong(8,vertices.address()).putLong(16,matches.address()).putLong(24,heads.address()).putLong(32,links.address());
            push.putInt(40,solid.stride()/4).putInt(44,solid.positionOffset()/4).putInt(48,sq)
                    .putInt(52,cutout.layout().stride()/4).putInt(56,cutout.layout().positionOffset()/4).putInt(60,cq).putInt(64,buckets-1).putInt(68,0);
            vkCmdBindPipeline(batch.commands,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
            vkCmdPushConstants(batch.commands,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            vkCmdDispatch(batch.commands,(cq+63)/64,1,1);
            memoryBarrier(batch.commands,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_READ_BIT_KHR|VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
            push.putInt(68,1); vkCmdPushConstants(batch.commands,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            vkCmdDispatch(batch.commands,(sq+63)/64,1,1);
            memoryBarrier(batch.commands,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
                    VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_READ_BIT_KHR);
            return new Overlay(cutout,solidAddress,vertices,matches);
        } catch(RuntimeException | Error failure) { if(matches!=null) matches.close(); if(vertices!=null) vertices.close(); throw failure; }
    }
    @Override public void close() {
        if(pipeline!=0) { vkDestroyPipeline(context.device(),pipeline,null);pipeline=0; }
        if(layout!=0) { vkDestroyPipelineLayout(context.device(),layout,null);layout=0; }
    }
}
