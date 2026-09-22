package dev.comfyfluffy.caustica.rt.pipeline;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
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
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/** Compute pipelines for histogram auto-exposure over the RT HDR trace output. */
final class RtExposurePipeline {
    private static final String SHADER_DIR = "/caustica/rt/";

    private final RtContext ctx;
    private final long histDescriptorSetLayout;
    private final long histDescriptorPool;
    private final long histDescriptorSet;
    private final long histPipelineLayout;
    private final long histPipeline;
    private final long resolveDescriptorSetLayout;
    private final long resolveDescriptorPool;
    private final long resolveDescriptorSet;
    private final long resolvePipelineLayout;
    private final long resolvePipeline;

    private long boundColorView;
    private long boundHistogramBufferForHist;
    private long boundHistogramBufferForResolve;
    private long boundExposureView;
    private long boundStateBuffer;
    private boolean destroyed;

    private RtExposurePipeline(RtContext ctx,
                               long histDescriptorSetLayout, long histDescriptorPool, long histDescriptorSet,
                               long histPipelineLayout, long histPipeline,
                               long resolveDescriptorSetLayout, long resolveDescriptorPool, long resolveDescriptorSet,
                               long resolvePipelineLayout, long resolvePipeline) {
        this.ctx = ctx;
        this.histDescriptorSetLayout = histDescriptorSetLayout;
        this.histDescriptorPool = histDescriptorPool;
        this.histDescriptorSet = histDescriptorSet;
        this.histPipelineLayout = histPipelineLayout;
        this.histPipeline = histPipeline;
        this.resolveDescriptorSetLayout = resolveDescriptorSetLayout;
        this.resolveDescriptorPool = resolveDescriptorPool;
        this.resolveDescriptorSet = resolveDescriptorSet;
        this.resolvePipelineLayout = resolvePipelineLayout;
        this.resolvePipeline = resolvePipeline;
    }

    static RtExposurePipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            VkDescriptorSetLayoutBinding.Buffer histBinds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            histBinds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            histBinds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo histDslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(histBinds);
            check(VK10.vkCreateDescriptorSetLayout(vk, histDslci, null, p), "vkCreateDescriptorSetLayout(rt exposure hist)");
            long histDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, histDsl, "exposure histogram descriptor set layout");
            long histPool = createPool(vk, stack, 1, 1, "hist");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, histPool, "exposure histogram descriptor pool");
            long histSet = allocateSet(vk, stack, histPool, histDsl, "hist");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, histSet, "exposure histogram descriptor set");
            long histLayout = createPipelineLayout(vk, stack, histDsl, 0, "hist");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, histLayout, "exposure histogram pipeline layout");
            long histModule = loadModule(vk, stack, "exposure_hist.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, histModule, "exposure histogram shader module");
            long histPipeline = createComputePipeline(vk, stack, histLayout, histModule, "hist");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, histPipeline, "exposure histogram pipeline");
            VK10.vkDestroyShaderModule(vk, histModule, null);

            VkDescriptorSetLayoutBinding.Buffer resolveBinds = VkDescriptorSetLayoutBinding.calloc(3, stack);
            resolveBinds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            resolveBinds.get(1).binding(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            resolveBinds.get(2).binding(2).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo resolveDslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(resolveBinds);
            check(VK10.vkCreateDescriptorSetLayout(vk, resolveDslci, null, p), "vkCreateDescriptorSetLayout(rt exposure resolve)");
            long resolveDsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, resolveDsl, "exposure resolve descriptor set layout");
            long resolvePool = createPool(vk, stack, 1, 2, "resolve");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, resolvePool, "exposure resolve descriptor pool");
            long resolveSet = allocateSet(vk, stack, resolvePool, resolveDsl, "resolve");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, resolveSet, "exposure resolve descriptor set");
            long resolveLayout = createPipelineLayout(vk, stack, resolveDsl, 32, "resolve");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, resolveLayout, "exposure resolve pipeline layout");
            long resolveModule = loadModule(vk, stack, "exposure_resolve.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, resolveModule, "exposure resolve shader module");
            long resolvePipeline = createComputePipeline(vk, stack, resolveLayout, resolveModule, "resolve");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, resolvePipeline, "exposure resolve pipeline");
            VK10.vkDestroyShaderModule(vk, resolveModule, null);

            return new RtExposurePipeline(ctx, histDsl, histPool, histSet, histLayout, histPipeline,
                    resolveDsl, resolvePool, resolveSet, resolveLayout, resolvePipeline);
        }
    }

    void setResources(long colorView, RtBuffer histogram, long exposureView, RtBuffer state) {
        if (boundColorView != colorView || boundHistogramBufferForHist != histogram.handle) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer colorInfo = VkDescriptorImageInfo.calloc(1, stack);
                colorInfo.get(0).imageView(colorView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorBufferInfo.Buffer histInfo = VkDescriptorBufferInfo.calloc(1, stack);
                histInfo.get(0).buffer(histogram.handle).offset(0).range(histogram.size);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                writes.get(0).sType$Default().dstSet(histDescriptorSet).dstBinding(0)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(colorInfo);
                writes.get(1).sType$Default().dstSet(histDescriptorSet).dstBinding(1)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(histInfo);
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            }
            boundColorView = colorView;
            boundHistogramBufferForHist = histogram.handle;
        }
        if (boundHistogramBufferForResolve != histogram.handle || boundExposureView != exposureView
                || boundStateBuffer != state.handle) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorBufferInfo.Buffer histInfo = VkDescriptorBufferInfo.calloc(1, stack);
                histInfo.get(0).buffer(histogram.handle).offset(0).range(histogram.size);
                VkDescriptorImageInfo.Buffer exposureInfo = VkDescriptorImageInfo.calloc(1, stack);
                exposureInfo.get(0).imageView(exposureView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorBufferInfo.Buffer stateInfo = VkDescriptorBufferInfo.calloc(1, stack);
                stateInfo.get(0).buffer(state.handle).offset(0).range(state.size);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
                writes.get(0).sType$Default().dstSet(resolveDescriptorSet).dstBinding(0)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(histInfo);
                writes.get(1).sType$Default().dstSet(resolveDescriptorSet).dstBinding(1)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(exposureInfo);
                writes.get(2).sType$Default().dstSet(resolveDescriptorSet).dstBinding(2)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(stateInfo);
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            }
            boundHistogramBufferForResolve = histogram.handle;
            boundExposureView = exposureView;
            boundStateBuffer = state.handle;
        }
    }

    void dispatchHistogram(org.lwjgl.vulkan.VkCommandBuffer cmd, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure histogram")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, histPipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, histPipelineLayout, 0,
                    stack.longs(histDescriptorSet), null);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    void dispatchResolve(org.lwjgl.vulkan.VkCommandBuffer cmd, int pixelCount, RtExposure.AutoConfig config, float frameTimeSeconds) {
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure resolve")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, resolvePipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, resolvePipelineLayout, 0,
                    stack.longs(resolveDescriptorSet), null);
            ByteBuffer push = stack.malloc(32);
            push.putInt(0, pixelCount);
            push.putFloat(4, config.key());
            push.putFloat(8, config.minEv());
            push.putFloat(12, config.maxEv());
            push.putFloat(16, config.adaptUp());
            push.putFloat(20, config.adaptDown());
            push.putFloat(24, frameTimeSeconds);
            push.putFloat(28, config.evBias());
            VK10.vkCmdPushConstants(cmd, resolvePipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, 1, 1, 1);
        }
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, resolvePipeline, null);
        VK10.vkDestroyPipelineLayout(vk, resolvePipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, resolveDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, resolveDescriptorSetLayout, null);
        VK10.vkDestroyPipeline(vk, histPipeline, null);
        VK10.vkDestroyPipelineLayout(vk, histPipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, histDescriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, histDescriptorSetLayout, null);
        destroyed = true;
    }

    private static long createPool(VkDevice vk, MemoryStack stack, int storageImages, int storageBuffers, String label) {
        int poolCount = (storageImages > 0 ? 1 : 0) + (storageBuffers > 0 ? 1 : 0);
        VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(poolCount, stack);
        int idx = 0;
        if (storageImages > 0) {
            sizes.get(idx++).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(storageImages);
        }
        if (storageBuffers > 0) {
            sizes.get(idx).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(storageBuffers);
        }
        VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorPool(vk, ci, null, p), "vkCreateDescriptorPool(rt exposure " + label + ")");
        return p.get(0);
    }

    private static long allocateSet(VkDevice vk, MemoryStack stack, long pool, long layout, String label) {
        VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                .descriptorPool(pool).pSetLayouts(stack.longs(layout));
        LongBuffer pSet = stack.mallocLong(1);
        check(VK10.vkAllocateDescriptorSets(vk, ai, pSet), "vkAllocateDescriptorSets(rt exposure " + label + ")");
        return pSet.get(0);
    }

    private static long createPipelineLayout(VkDevice vk, MemoryStack stack, long setLayout, int pushBytes, String label) {
        VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout));
        if (pushBytes > 0) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack)
                    .stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
            ci.pPushConstantRanges(pcr);
        }
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreatePipelineLayout(vk, ci, null, p), "vkCreatePipelineLayout(rt exposure " + label + ")");
        return p.get(0);
    }

    private static long createComputePipeline(VkDevice vk, MemoryStack stack, long layout, long module, String label) {
        VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
        VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
        cpci.get(0).sType$Default().stage(stage).layout(layout);
        LongBuffer pPipeline = stack.mallocLong(1);
        check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                "vkCreateComputePipelines(rt exposure " + label + ")");
        return pPipeline.get(0);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtExposurePipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
            VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(vk, ci, null, pModule), "vkCreateShaderModule(" + name + ")");
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }
}
