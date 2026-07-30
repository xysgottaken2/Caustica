package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
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
import dev.comfyfluffy.caustica.rt.accel.RtImage;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Bloom, built the way every modern engine builds it: a threshold prefilter into a half-resolution
 * image, a chain of progressively halved downsamples, then an upsample walk back up that adds each
 * blurred level into the one above it. Wide, smooth glow for the cost of a few small dispatches, with
 * none of the banding or box-blur boxiness a single large-radius kernel at full resolution produces.
 *
 * <p>The kernels come from Jorge Jimenez's "Next Generation Post Processing in Call of Duty: Advanced
 * Warfare" (SIGGRAPH 2014) — 13-tap downsample, 3x3 tent upsample — with a Karis average on the
 * prefilter so a single path-traced firefly cannot bloom into a huge blob.
 *
 * <p>The chain lives entirely in this class: {@link #ensure} (re)builds it when the display size or the
 * source views change, {@link #record} enqueues the passes, and {@link #resolvedView} is what the
 * display pass samples. Everything is in the exposed domain; the display pass divides the exposure back
 * out before adding bloom to the scene-referred radiance.
 */
public final class RtBloomPipeline {
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Chain depth cap. Six half-steps already cover a very wide glow at 4K. */
    private static final int MAX_MIPS = 6;
    /** Stop halving once a level gets this small; tiny mips add cost and flicker, not glow. */
    private static final int MIN_MIP_SIZE = 16;
    /** Prefilter push: threshold, softKnee, exposureEv. */
    private static final int PREFILTER_PUSH_BYTES = 3 * Float.BYTES;
    /** Down/up push: filterRadius, blend. */
    private static final int CHAIN_PUSH_BYTES = 2 * Float.BYTES;
    /** Knee width as a fraction of the threshold: bloom fades in instead of switching on. */
    private static final float SOFT_KNEE = 0.6f;

    private final RtContext ctx;
    private final long prefilterSetLayout;
    private final long chainSetLayout;
    private final long descriptorPool;
    private final long prefilterPipelineLayout;
    private final long chainPipelineLayout;
    private final long prefilterPipeline;
    private final long downPipeline;
    private final long upPipeline;

    private RtImage[] mips = new RtImage[0];
    private long prefilterSet;
    private long[] downSets = new long[0];
    private long[] upSets = new long[0];
    private long boundRtView;
    private long boundExposureView;
    private int chainWidth = -1;
    private int chainHeight = -1;
    private boolean destroyed;

    private RtBloomPipeline(RtContext ctx, long prefilterSetLayout, long chainSetLayout, long descriptorPool,
                            long prefilterPipelineLayout, long chainPipelineLayout,
                            long prefilterPipeline, long downPipeline, long upPipeline) {
        this.ctx = ctx;
        this.prefilterSetLayout = prefilterSetLayout;
        this.chainSetLayout = chainSetLayout;
        this.descriptorPool = descriptorPool;
        this.prefilterPipelineLayout = prefilterPipelineLayout;
        this.chainPipelineLayout = chainPipelineLayout;
        this.prefilterPipeline = prefilterPipeline;
        this.downPipeline = downPipeline;
        this.upPipeline = upPipeline;
    }

    public static RtBloomPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long prefilterSetLayout = createSetLayout(ctx, stack, 3, "bloom prefilter descriptor set layout");
            long chainSetLayout = createSetLayout(ctx, stack, 2, "bloom chain descriptor set layout");

            // One prefilter set plus a down set and an up set per chain step.
            int maxSets = 1 + 2 * (MAX_MIPS - 1);
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(3 + 4 * (MAX_MIPS - 1));
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(maxSets).pPoolSizes(poolSizes);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(rt bloom)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "bloom descriptor pool");

            long prefilterLayout = createPipelineLayout(ctx, stack, prefilterSetLayout, PREFILTER_PUSH_BYTES,
                    "bloom prefilter pipeline layout");
            long chainLayout = createPipelineLayout(ctx, stack, chainSetLayout, CHAIN_PUSH_BYTES,
                    "bloom chain pipeline layout");

            long prefilter = createComputePipeline(ctx, stack, prefilterLayout, "bloom_prefilter.comp.spv",
                    "bloom prefilter pipeline");
            long down = createComputePipeline(ctx, stack, chainLayout, "bloom_down.comp.spv",
                    "bloom downsample pipeline");
            long up = createComputePipeline(ctx, stack, chainLayout, "bloom_up.comp.spv",
                    "bloom upsample pipeline");

            return new RtBloomPipeline(ctx, prefilterSetLayout, chainSetLayout, pool, prefilterLayout,
                    chainLayout, prefilter, down, up);
        }
    }

    /**
     * (Re)build the chain for a display-resolution source. Call this from the same place that (re)creates
     * the display images, i.e. with the device already idle: a size change destroys and reallocates every
     * mip and resets the descriptor pool.
     */
    public void ensure(long rtImageView, long exposureImageView, int width, int height) {
        boolean sameSize = chainWidth == width && chainHeight == height && mips.length > 0;
        if (sameSize && boundRtView == rtImageView && boundExposureView == exposureImageView) {
            return;
        }
        boundRtView = rtImageView;
        boundExposureView = exposureImageView;
        if (!sameSize) {
            destroyMips();
            createMips(width, height);
            allocateSets();
        }
        writeSets();
    }

    /** The resolved (widest) bloom level, at half display resolution, in the exposed domain. */
    public long resolvedView() {
        return mips.length > 0 ? mips[0].view : 0L;
    }

    public boolean ready() {
        return mips.length > 0;
    }

    /**
     * Record the whole chain. {@code exposureEv} must match the value the display pass uses, so the
     * threshold means the same thing in both places.
     */
    public void record(VkCommandBuffer cmd, MemoryStack stack, float threshold, float exposureEv, float radius) {
        int levels = mips.length;
        if (levels == 0) {
            return;
        }
        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "bloom chain")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, prefilterPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, prefilterPipelineLayout,
                    0, stack.longs(prefilterSet), null);
            ByteBuffer prefilterPush = stack.malloc(PREFILTER_PUSH_BYTES);
            prefilterPush.putFloat(0, Math.max(threshold, 0f));
            prefilterPush.putFloat(4, SOFT_KNEE);
            prefilterPush.putFloat(8, exposureEv);
            VK10.vkCmdPushConstants(cmd, prefilterPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, prefilterPush);
            dispatchFor(cmd, mips[0]);
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, downPipeline);
            for (int i = 0; i + 1 < levels; i++) {
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, chainPipelineLayout,
                        0, stack.longs(downSets[i]), null);
                dispatchFor(cmd, mips[i + 1]);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }

            // The upsample tent radius is in source texels; the user-facing radius setting scales it, with
            // 1.0 landing on the classic 0.75-texel filter.
            ByteBuffer upPush = stack.malloc(CHAIN_PUSH_BYTES);
            upPush.putFloat(0, Math.max(radius, 0.2f) * 0.75f);
            upPush.putFloat(4, 1.0f);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, upPipeline);
            VK10.vkCmdPushConstants(cmd, chainPipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, upPush);
            for (int i = levels - 2; i >= 0; i--) {
                VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, chainPipelineLayout,
                        0, stack.longs(upSets[i]), null);
                dispatchFor(cmd, mips[i]);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        destroyMips();
        VK10.vkDestroyPipeline(vk, prefilterPipeline, null);
        VK10.vkDestroyPipeline(vk, downPipeline, null);
        VK10.vkDestroyPipeline(vk, upPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, prefilterPipelineLayout, null);
        VK10.vkDestroyPipelineLayout(vk, chainPipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, prefilterSetLayout, null);
        VK10.vkDestroyDescriptorSetLayout(vk, chainSetLayout, null);
        destroyed = true;
    }

    private void createMips(int width, int height) {
        int levels = levelsFor(width, height);
        mips = new RtImage[levels];
        int w = Math.max(1, width / 2);
        int h = Math.max(1, height / 2);
        for (int i = 0; i < levels; i++) {
            mips[i] = ctx.createStorageImage(w, h, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "bloom mip " + i + " " + w + "x" + h);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        chainWidth = width;
        chainHeight = height;
    }

    private void destroyMips() {
        for (RtImage mip : mips) {
            if (mip != null) {
                mip.destroy();
            }
        }
        mips = new RtImage[0];
        chainWidth = -1;
        chainHeight = -1;
    }

    private static int levelsFor(int width, int height) {
        int levels = 1;
        int smallest = Math.max(1, Math.min(width, height) / 2);
        while (levels < MAX_MIPS && smallest / 2 >= MIN_MIP_SIZE) {
            smallest /= 2;
            levels++;
        }
        return levels;
    }

    private void allocateSets() {
        VkDevice vk = ctx.vk();
        check(VK10.vkResetDescriptorPool(vk, descriptorPool, 0), "vkResetDescriptorPool(rt bloom)");
        int steps = Math.max(mips.length - 1, 0);
        downSets = new long[steps];
        upSets = new long[steps];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            prefilterSet = allocateSet(vk, stack, prefilterSetLayout);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, prefilterSet, "bloom prefilter set");
            for (int i = 0; i < steps; i++) {
                downSets[i] = allocateSet(vk, stack, chainSetLayout);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, downSets[i], "bloom down set " + i);
            }
            for (int i = 0; i < steps; i++) {
                upSets[i] = allocateSet(vk, stack, chainSetLayout);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, upSets[i], "bloom up set " + i);
            }
        }
    }

    private long allocateSet(VkDevice vk, MemoryStack stack, long setLayout) {
        VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(descriptorPool).pSetLayouts(stack.longs(setLayout));
        LongBuffer pSet = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(rt bloom)");
        return pSet.get(0);
    }

    private void writeSets() {
        int steps = Math.max(mips.length - 1, 0);
        int writeCount = 3 + 4 * steps;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
            int at = 0;
            // Prefilter: mip 0 out, the display-res RT image in, the 1x1 exposure image in.
            writeImage(writes, at++, stack, prefilterSet, 0, mips[0].view);
            writeImage(writes, at++, stack, prefilterSet, 1, boundRtView);
            writeImage(writes, at++, stack, prefilterSet, 2, boundExposureView);
            for (int i = 0; i < steps; i++) {
                writeImage(writes, at++, stack, downSets[i], 0, mips[i + 1].view); // dst: the smaller level
                writeImage(writes, at++, stack, downSets[i], 1, mips[i].view);     // src: the larger level
            }
            for (int i = 0; i < steps; i++) {
                writeImage(writes, at++, stack, upSets[i], 0, mips[i].view);       // dst: accumulated in place
                writeImage(writes, at++, stack, upSets[i], 1, mips[i + 1].view);   // src: the smaller level
            }
            VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
        }
    }

    private static void writeImage(VkWriteDescriptorSet.Buffer writes, int index, MemoryStack stack,
                                   long set, int binding, long view) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        writes.get(index).sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
    }

    private static void dispatchFor(VkCommandBuffer cmd, RtImage target) {
        VK10.vkCmdDispatch(cmd, (target.width + 15) / 16, (target.height + 15) / 16, 1);
    }

    private static long createSetLayout(RtContext ctx, MemoryStack stack, int bindingCount, String label) {
        VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
        for (int i = 0; i < bindingCount; i++) {
            binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(binds);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p), "vkCreateDescriptorSetLayout(rt bloom)");
        long layout = p.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, layout, label);
        return layout;
    }

    private static long createPipelineLayout(RtContext ctx, MemoryStack stack, long setLayout, int pushBytes,
                                             String label) {
        VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
        pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
        VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(pushRange);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreatePipelineLayout(ctx.vk(), plci, null, p), "vkCreatePipelineLayout(rt bloom)");
        long layout = p.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, label);
        return layout;
    }

    private static long createComputePipeline(RtContext ctx, MemoryStack stack, long pipelineLayout,
                                              String shader, String label) {
        VkDevice vk = ctx.vk();
        long module = loadModule(vk, stack, shader);
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
        cpci.get(0).sType$Default().stage(stage).layout(pipelineLayout);
        LongBuffer pPipeline = stack.mallocLong(1);
        check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                "vkCreateComputePipelines(" + shader + ")");
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), label);
        VK10.vkDestroyShaderModule(vk, module, null);
        return pPipeline.get(0);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtBloomPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
