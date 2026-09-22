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

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Sky mask over FSR FG's generated frames. The FFX frame-interpolation engine cannot handle sky
 * pixels (reversed-Z depth ~0 + textureless gradients + 1-SPP hot samples): its block-based
 * motion/disocclusion logic emits black blocks and gray inpainting smudges there — the reported
 * sky flicker/black squares when FG is on. This pass replaces every sky pixel of a generated
 * frame with the corresponding pixel of the real presented frame (sky content moves slowly enough
 * that holding it at the real frame's position for the generated frame's half-frame step is
 * invisible), removing the artifacts by construction.
 *
 * <p>Bindings: 0 generated FG output (read-write, masked in place), 1 the real presented frame
 * (read-only), 2 render-res reversed-Z depth guide (read-only). Two pipeline variants share the
 * layout: rgba8 for the SDR outputs, rgba16f for the HDR ones (same GLSL, compiled twice).
 */
public final class RtFgSkyMaskPipeline {
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Push: int displayWidth, int displayHeight, int renderWidth, int renderHeight. */
    private static final int PUSH_BYTES = 4 * Integer.BYTES;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipelineSdr; // rgba8 variant
    private final long pipelineHdr; // rgba16f variant
    private boolean destroyed;

    private RtFgSkyMaskPipeline(RtContext ctx, long dsl, long pool, long set, long layout,
                                long pipelineSdr, long pipelineHdr) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipelineSdr = pipelineSdr;
        this.pipelineHdr = pipelineHdr;
    }

    public static RtFgSkyMaskPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(fg sky mask)");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, "fg sky mask descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(3);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(fg sky mask)");
            long pool = p.get(0);

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            check(VK10.vkAllocateDescriptorSets(vk, dsai, p), "vkAllocateDescriptorSets(fg sky mask)");
            long set = p.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(fg sky mask)");
            long layout = p.get(0);

            long pipelineSdr = createPipeline(ctx, vk, stack, layout, "fg_sky_mask_rgba8.comp.spv", "fg sky mask (rgba8)");
            long pipelineHdr = createPipeline(ctx, vk, stack, layout, "fg_sky_mask.comp.spv", "fg sky mask (rgba16f)");

            return new RtFgSkyMaskPipeline(ctx, dsl, pool, set, layout, pipelineSdr, pipelineHdr);
        }
    }

    private static long createPipeline(RtContext ctx, VkDevice vk, MemoryStack stack, long layout,
                                       String module, String label) {
        long mod = loadModule(vk, stack, module);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, mod, label + " shader module");
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(mod).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
        cpci.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer pPipeline = stack.mallocLong(1);
        check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                "vkCreateComputePipelines(" + module + ")");
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), label + " compute pipeline");
        VK10.vkDestroyShaderModule(vk, mod, null);
        return pPipeline.get(0);
    }

    /**
     * Mask one generated FG output in place: sky pixels (per the render-res reversed-Z depth
     * guide, dilated by one render pixel toward geometry at the horizon) are replaced with the
     * real presented frame's pixels. Call AFTER the FG generate dispatch in the same command
     * buffer, with a memory barrier between (the FFX writes must be visible to this read-modify-
     * write). Bindings are rewritten on every call — the generated output changes per frame.
     */
    public void dispatch(VkCommandBuffer cmd, long generatedView, long presentView, long depthView,
                         int displayWidth, int displayHeight, int renderWidth, int renderHeight, boolean hdr) {
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fg sky mask")) {
            long[] views = {generatedView, presentView, depthView};
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
            for (int i = 0; i < 3; i++) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(views[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                writes.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(info);
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, hdr ? pipelineHdr : pipelineSdr);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, displayWidth);
            push.putInt(4, displayHeight);
            push.putInt(8, renderWidth);
            push.putInt(12, renderHeight);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (displayWidth + 15) / 16, (displayHeight + 15) / 16, 1);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipelineSdr, null);
        VK10.vkDestroyPipeline(vk, pipelineHdr, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtFgSkyMaskPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, smci, null, p), "vkCreateShaderModule(" + name + ")");
            return p.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
