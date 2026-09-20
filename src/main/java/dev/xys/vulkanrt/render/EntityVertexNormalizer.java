package dev.xys.vulkanrt.render;
import org.lwjgl.system.*;
import org.lwjgl.vulkan.*;
import org.joml.Matrix4fc;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRSynchronization2.*;
import static dev.xys.vulkanrt.render.VulkanRayTracingContext.*;
/** Geometry conversion only, on the shared queue: undo vanilla's CPU pose on GPU-copied positions. */
public final class EntityVertexNormalizer implements AutoCloseable {
 private final VulkanRayTracingContext context; private long layout,pipeline;
 public static final int PUSH_BYTES=80;
    public EntityVertexNormalizer(VulkanRayTracingContext context) {
        this.context=context; long module=0;
        try (MemoryStack stack=MemoryStack.stackPush()) {
            var n=stack.ints(0); vkGetPhysicalDeviceQueueFamilyProperties(context.physicalDevice(),n,null);
            var families=VkQueueFamilyProperties.calloc(n.get(0),stack);
            vkGetPhysicalDeviceQueueFamilyProperties(context.physicalDevice(),n,families);
            if((families.get(context.graphicsQueue().queueFamilyIndex()).queueFlags()&VK_QUEUE_COMPUTE_BIT)==0)
                throw new IllegalStateException("Vanilla graphics queue lacks compute for entity pose normalization");
            byte[] bytes;
            try(var input=getClass().getResourceAsStream("/assets/native_vulkan_rt/spirv/entity-local.comp.spv")) {
                if(input==null) throw new IllegalStateException("Missing entity local compute shader");
                bytes=input.readAllBytes();
            }
            var code=MemoryUtil.memAlloc(bytes.length);
            var out=stack.mallocLong(1);
            try { code.put(bytes).flip(); check(vkCreateShaderModule(context.device(),VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code),null,out),"entity local shader"); module=out.get(0); }
            finally { MemoryUtil.memFree(code); }
            var range=VkPushConstantRange.calloc(1,stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            check(vkCreatePipelineLayout(context.device(),VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(range),null,out),"entity local layout"); layout=out.get(0);
            var info=VkComputePipelineCreateInfo.calloc(1,stack).sType$Default().layout(layout);
            info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(context.device(),0,info,null,out),"entity local pipeline"); pipeline=out.get(0);
        } catch(java.io.IOException failure) { close(); throw new IllegalStateException(failure); }
        catch(RuntimeException | Error failure) { close(); throw failure; }
        finally { if(module!=0) vkDestroyShaderModule(context.device(),module,null); }
    }
 public void normalize(CommandBatch batch,GpuBuffer vertices,int stride,int count,Matrix4fc inverse) {
  try(MemoryStack stack=MemoryStack.stackPush()) {
   memoryBarrier(batch.commands,VK_PIPELINE_STAGE_2_TRANSFER_BIT_KHR,VK_ACCESS_2_TRANSFER_WRITE_BIT_KHR,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_READ_BIT_KHR|VK_ACCESS_2_SHADER_WRITE_BIT_KHR);
   var push=stack.calloc(PUSH_BYTES);push.putLong(0,vertices.address()).putInt(8,stride/4).putInt(12,count);inverse.get(16,push);
   vkCmdBindPipeline(batch.commands,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
   vkCmdPushConstants(batch.commands,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,push);
   vkCmdDispatch(batch.commands,(count+63)/64,1,1);
   memoryBarrier(batch.commands,VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_WRITE_BIT_KHR,
     VK_PIPELINE_STAGE_2_ACCELERATION_STRUCTURE_BUILD_BIT_KHR|VK_PIPELINE_STAGE_2_RAY_TRACING_SHADER_BIT_KHR,VK_ACCESS_2_SHADER_READ_BIT_KHR|VK_ACCESS_2_ACCELERATION_STRUCTURE_READ_BIT_KHR);
  }
 }
    @Override public void close() {
        if(pipeline!=0) { vkDestroyPipeline(context.device(),pipeline,null);pipeline=0; }
        if(layout!=0) { vkDestroyPipelineLayout(context.device(),layout,null);layout=0; }
    }
}
