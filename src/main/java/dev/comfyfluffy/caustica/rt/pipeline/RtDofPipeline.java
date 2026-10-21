package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
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
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Depth of Field post-process compute pipeline.
 *
 * <p>Runs at display resolution between the bloom pass and the display tonemap pass. Reads the
 * bloomed HDR scene image ({@code rrOutput}) and the linear depth guide ({@code gDepth}), computes a
 * per-pixel Circle of Confusion from the configured focal distance / aperture / focal length, blurs
 * out-of-focus regions, and writes the result back into the scene image so the display mapper sees
 * the DoF'd picture.
 *
 * <p>Because the blur reads neighbouring pixels from the same image it is writing, the pass uses a
 * separate scratch image ({@code dofTemp}): scene -> dofTemp, then a full-image copy back into scene.
 * All settings are re-read every frame from {@link CausticaConfig.Rt.Dof}, so slider drags take
 * effect on the next frame with no reload.
 */
public final class RtDofPipeline {
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Push constants: 5 ints + 5 floats = 40 bytes. */
    private static final int PUSH_BYTES = 10 * Integer.BYTES;

    private final RtContext ctx;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;
    private final long pipelineLayout;
    private final long pipeline;
    // Scratch image the blur writes into before copying back over the scene image (read+write same
    // image would race on the neighbouring taps).
    private RtImage dofTemp;
    private long boundSceneView;
    private long boundDepthView;
    private long boundTempView;
    private int allocatedWidth = -1;
    private int allocatedHeight = -1;
    private boolean destroyed;

    private RtDofPipeline(RtContext ctx, long dsl, long pool, long set, long layout, long pipeline) {
        this.ctx = ctx;
        this.descriptorSetLayout = dsl;
        this.descriptorPool = pool;
        this.descriptorSet = set;
        this.pipelineLayout = layout;
        this.pipeline = pipeline;
    }

    public static RtDofPipeline create(RtContext ctx) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            // Layout: binding 0 = scene image (sampled), binding 1 = depth image (sampled),
            // binding 2 = temp image (storage write).
            long dsl = createSetLayout(ctx, stack, "dof",
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                    VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

            int totalSets = 1;
            int totalStorage = 3;

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(totalStorage);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(totalSets).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(dof)");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, "dof descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(dof)");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, "dof descriptor set");

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            LongBuffer pLayout = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, pLayout), "vkCreatePipelineLayout(dof)");
            long layout = pLayout.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, "dof pipeline layout");

            long module = loadModule(vk, stack, "dof.comp.spv");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, "dof shader module");
            try {
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                        .stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
                VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
                cpci.get(0).sType$Default().stage(stage).layout(layout);
                LongBuffer pPipeline = stack.mallocLong(1);
                check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                        "vkCreateComputePipelines(dof)");
                long pipeline = pPipeline.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pipeline, "dof compute pipeline");
                return new RtDofPipeline(ctx, dsl, pool, set, layout, pipeline);
            } finally {
                VK10.vkDestroyShaderModule(vk, module, null);
            }
        }
    }

    /**
     * Ensure the scratch image matches the display resolution. Caller must ensure the device is idle
     * if a previous scratch image is being replaced.
     */
    public void ensureSize(int displayWidth, int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            return;
        }
        if (dofTemp != null && allocatedWidth == displayWidth && allocatedHeight == displayHeight) {
            return;
        }
        if (dofTemp != null) {
            dofTemp.destroy();
            dofTemp = null;
        }
        dofTemp = ctx.createStorageImage(displayWidth, displayHeight, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "dof temp " + displayWidth + "x" + displayHeight);
        allocatedWidth = displayWidth;
        allocatedHeight = displayHeight;
        invalidateBindings();
    }

    public boolean readyForSize(int displayWidth, int displayHeight) {
        return !destroyed && dofTemp != null && allocatedWidth == displayWidth
                && allocatedHeight == displayHeight;
    }

    public void invalidateBindings() {
        boundSceneView = 0L;
        boundDepthView = 0L;
        boundTempView = 0L;
    }

    /**
     * Record the DoF pass: read {@code sceneImage} (the bloomed HDR scene), depth from {@code
     * depthImageView}, write the DoF'd result into {@code dofTemp}, then copy it back over {@code
     * sceneImage} so downstream passes (display mapping, HDR present) see the DoF'd picture without
     * any descriptor-set churn.
     *
     * @param sceneImage the Vulkan image handle of the scene target (copy destination)
     */
    public void dispatch(VkCommandBuffer cmd, MemoryStack stack, int displayWidth, int displayHeight,
            int renderWidth, int renderHeight, long sceneImageView, long sceneImage, long depthImageView) {
        if (destroyed || sceneImageView == 0L || sceneImage == 0L || depthImageView == 0L) {
            return;
        }
        if (!CausticaConfig.Rt.Dof.ENABLED.value()) {
            return;
        }
        if (!readyForSize(displayWidth, displayHeight)) {
            return;
        }

        boolean autoFocus = "auto".equals(CausticaConfig.Rt.Dof.FOCUS_MODE.get());
        float focalDistance = CausticaConfig.Rt.Dof.FOCAL_DISTANCE.value();
        float aperture = CausticaConfig.Rt.Dof.APERTURE.value();
        float focalLength = CausticaConfig.Rt.Dof.FOCAL_LENGTH.value();
        float maxBlur = CausticaConfig.Rt.Dof.MAX_BLUR.value();

        try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "rt dof")) {
            // Scene -> temp (blur).
            bindDescriptorSet(stack, sceneImageView, depthImageView, dofTemp.view);
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);

            ByteBuffer push = stack.malloc(PUSH_BYTES);
            push.putInt(0, displayWidth);
            push.putInt(4, displayHeight);
            push.putInt(8, renderWidth);
            push.putInt(12, renderHeight);
            push.putFloat(16, focalDistance);
            push.putFloat(20, aperture);
            push.putFloat(24, focalLength);
            push.putFloat(28, maxBlur);
            push.putInt(32, autoFocus ? 1 : 0);
            push.putFloat(36, 0.0f);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (displayWidth + 15) / 16, (displayHeight + 15) / 16, 1);

            // Make the blur writes visible to the copy that returns the result to the scene image.
            barrierComputeReadWrite(cmd, stack);

            // Copy dofTemp back over the scene image (same size, same format).
            VK10.vkCmdCopyImage(cmd, dofTemp.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    sceneImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayWidth, displayHeight));
            barrierComputeReadWrite(cmd, stack);
        }
    }

    private static void barrierComputeReadWrite(VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VK10.vkCmdPipelineBarrier(cmd,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, barrier, null, null);
    }

    private void bindDescriptorSet(MemoryStack stack, long sceneView, long depthView, long tempView) {
        if (boundSceneView == sceneView && boundDepthView == depthView && boundTempView == tempView) {
            return;
        }
        VkDescriptorImageInfo.Buffer sceneInfo = VkDescriptorImageInfo.calloc(1, stack);
        sceneInfo.get(0).imageView(sceneView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkDescriptorImageInfo.Buffer depthInfo = VkDescriptorImageInfo.calloc(1, stack);
        depthInfo.get(0).imageView(depthView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        VkDescriptorImageInfo.Buffer tempInfo = VkDescriptorImageInfo.calloc(1, stack);
        tempInfo.get(0).imageView(tempView).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
        writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(sceneInfo);
        writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(depthInfo);
        writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2).descriptorCount(1)
                .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(tempInfo);
        VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);

        boundSceneView = sceneView;
        boundDepthView = depthView;
        boundTempView = tempView;
    }

    /** Copy region covering the full image. */
    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (dofTemp != null) {
            dofTemp.destroy();
            dofTemp = null;
        }
        VkDevice vk = ctx.vk();
        VK10.vkDestroyPipeline(vk, pipeline, null);
        VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
        VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
        VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        destroyed = true;
    }

    private static long createSetLayout(RtContext ctx, MemoryStack stack, String label, int... types) {
        VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
        for (int i = 0; i < types.length; i++) {
            binds.get(i).binding(i).descriptorType(types[i])
                    .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
        }
        VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default().pBindings(binds);
        LongBuffer p = stack.mallocLong(1);
        check(VK10.vkCreateDescriptorSetLayout(ctx.vk(), dslci, null, p),
                "vkCreateDescriptorSetLayout(" + label + ")");
        long dsl = p.get(0);
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");
        return dsl;
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtDofPipeline.class.getResourceAsStream(SHADER_DIR + name)) {
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
