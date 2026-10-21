package dev.comfyfluffy.caustica.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * World-space reprojection: the renderer's single producer of screen-space motion vectors.
 *
 * <p>Replaces the guide pass's old 2D vector ({@code writeGuides} projecting the shaded point
 * through {@code prevViewProj}). The tracer no longer computes a motion vector at all; this pass
 * rebuilds each pixel's exact 3D position from the depth G-buffer and projects that world point
 * through the previous frame's view-projection. {@code gMotion} keeps its format, units and every
 * consumer — SVGF, NRD, DLSS-RR, FSR 3 / XeSS and both frame generators all read the same buffer.
 *
 * <p>Bindings, matching {@code shaders/world/world_reproject.comp.slang}: 0 this frame's linear view
 * depth ({@code gViewZ}), 1 the lateral-offset guide ({@code gReprojDelta}: shown point minus the
 * point the depth buffer describes — nonzero only for transmitted content), 2 the surface's own
 * per-frame displacement ({@code gReprojDisp}), 3 the motion output ({@code gMotion}), 4 the HDR
 * trace target (debug view 5 only).
 *
 * <p>Push constants are the shared {@link WorldPushConstantsData} block the world trace already
 * builds each frame — no separate serialization, so the matrices the reprojection uses are literally
 * the ones the tracer traced with. The image dimensions come from the bound depth image, so the
 * dispatch size is the only host-side constant.
 */
public final class RtWorldReprojectPipeline {
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Storage images the reprojection shader binds; see the class docs for the slot order. */
    private static final int BINDINGS = 5;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    private final long[] boundViews = new long[BINDINGS];
    private boolean destroyed;

    private RtWorldReprojectPipeline(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    public static RtWorldReprojectPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }

            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(world reproject)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "world reproject descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(BINDINGS);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(world reproject)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "world reproject descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(world reproject)");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, "world reproject descriptor set");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0)
                    .size(WorldPushConstantsData.BYTE_SIZE);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(world reproject)");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "world reproject pipeline layout");

            long module = loadModule(vk, stack, "world_reproject.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "world reproject shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(world reproject)");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), "world reproject compute pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            return new RtWorldReprojectPipeline(ctx, dsl, pool, set, layout, pPipeline.get(0));
        }
    }

    /**
     * Drop the cached binding state so the next {@link #setImages} always rewrites the descriptor.
     * Required after the guide images are recreated: a recycled view handle would otherwise compare
     * equal to the destroyed one and the update would be skipped.
     */
    public void invalidateBindings() {
        Arrays.fill(boundViews, 0L);
    }

    public void setImages(long viewZView, long reprojDeltaView, long reprojDispView,
                          long motionView, long traceColorView) {
        long[] views = {viewZView, reprojDeltaView, reprojDispView, motionView, traceColorView};
        if (Arrays.equals(views, boundViews)) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(BINDINGS, stack);
            for (int i = 0; i < BINDINGS; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(views[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
        System.arraycopy(views, 0, boundViews, 0, BINDINGS);
    }

    /**
     * Record the reprojection. {@code pushConstants} is the SAME serialized
     * {@link WorldPushConstantsData} block the world trace used this frame, so the previous
     * view-projection, the camera delta and the jitter cannot drift from what was rendered.
     */
    public void dispatch(VkCommandBuffer cmd, int width, int height, ByteBuffer pushConstants) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world reprojection")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtWorldReprojectPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("missing SPIR-V resource: " + SHADER_DIR + name);
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read SPIR-V resource: " + SHADER_DIR + name, e);
        }
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length).put(bytes);
        code.flip();
        try {
            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
