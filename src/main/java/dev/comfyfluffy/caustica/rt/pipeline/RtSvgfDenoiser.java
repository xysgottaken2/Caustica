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

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * SVGF (Spatiotemporal Variance-Guided Filtering) — the renderer's own denoiser for every non-DLSS
 * path (upscaler Off / FSR 3 / XeSS). Two compute stages, both at render resolution:
 *
 * <ol>
 *   <li>{@code svgf_reproject.comp}: geometry-validated bilinear temporal reprojection of the
 *       previous accumulation plus accumulation of the first two luminance moments, whose difference
 *       is the per-pixel variance;</li>
 *   <li>{@code svgf_atrous.comp}: an edge-avoiding à-trous wavelet run several times with doubling
 *       tap spacing, whose luminance edge-stop is scaled by that variance.</li>
 * </ol>
 *
 * <p>This replaces the previous fixed-sigma spatial blur + neighbourhood-clamped TAA pair. Two
 * properties of the pair made the old output noisy and unstable while moving, and both are
 * structural rather than a matter of tuning:
 *
 * <ul>
 *   <li>the TAA estimated its history clamp from the CURRENT frame's 3x3 neighbourhood, which at
 *       1 spp is itself noise, so the clamp box was as wide as the noise it was meant to bound; to
 *       stop the resulting ghosting it fell back on a motion-scaled blend that threw history away
 *       whenever the camera moved — exactly when a temporal filter is needed most;</li>
 *   <li>the spatial pass used a fixed luminance sigma, so it could not distinguish a converged pixel
 *       (where blurring destroys detail) from a noisy one (where it is essential), and its two
 *       passes reached only ~5 px, leaving the low-frequency blotches that read as residual noise.</li>
 * </ul>
 *
 * <p>SVGF fixes both at the root: history is rejected on GEOMETRY (depth + normal) rather than on
 * colour, so camera motion no longer implies history loss, and the spatial kernel's strength is
 * driven per pixel by the measured variance, so it blurs where the estimate is uncertain and leaves
 * converged regions crisp. That variance coupling is what gives the DLSS-like "settles and stays
 * settled" behaviour the fixed-sigma filter could not reach.
 */
public final class RtSvgfDenoiser {
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Reproject push: int width, height, reset; float maxFrames. */
    private static final int REPROJECT_PUSH_BYTES = 5 * Integer.BYTES;
    /** À-trous push: int width, height, step; float phiLuminance, phiNormal, phiDepth; int extraSkySmooth. */
    private static final int ATROUS_PUSH_BYTES = 9 * Integer.BYTES;
    private static final int REPROJECT_BINDINGS = 12;
    private static final int ATROUS_BINDINGS = 6;

    /**
     * Number of à-trous iterations. Each doubles the tap spacing, so the reach is
     * {@code 2 * (2^n - 1) + 1} pixels: 5 passes cover ~63 px at render resolution, which is what
     * removes the low-frequency blotchiness of a 1-spp path trace. The cost is linear in the pass
     * count while the footprint grows exponentially.
     */
    public static final int ATROUS_PASSES = 5;
    /**
     * Which iteration's output is fed back as the next frame's temporal history. SVGF feeds back the
     * FIRST iteration (not the raw accumulation and not the final image): the raw accumulation is
     * noisier, so history converges more slowly, while the fully filtered image is over-blurred and
     * feeding it back compounds that blur every frame into permanent smearing.
     */
    public static final int HISTORY_FEEDBACK_PASS = 0;
    static {
        // The cascade filters DEMODULATED radiance and only the final iteration multiplies the
        // albedo back in, so the history feedback must be taken from an earlier iteration or the
        // next frame would blend a modulated history against demodulated samples — the albedo would
        // compound once per frame and the image would run away.
        if (HISTORY_FEEDBACK_PASS >= ATROUS_PASSES - 1) {
            throw new IllegalStateException(
                    "HISTORY_FEEDBACK_PASS must be before the final (re-modulating) a-trous pass");
        }
    }
    /** Ping-pong parities; see {@link Stage} for why the descriptor sets are keyed on them. */
    private static final int PARITIES = 2;

    private final RtContext ctx;
    private final Stage reproject;
    private final Stage atrous;
    private boolean destroyed;

    /**
     * One compute pipeline plus a RING of descriptor sets, one per dispatch that the stage records
     * into a single frame's command buffer.
     *
     * <p>The ring is not an optimization, it is a correctness requirement, and getting it wrong is
     * what silently broke the filter this class replaces. A descriptor set is read when the GPU
     * EXECUTES the dispatch, not when the CPU records it, so rewriting one set between two
     * {@code vkCmdDispatch} calls in the same command buffer does not give the two dispatches
     * different images — both end up reading whatever was written last. The previous à-trous
     * implementation rebound a single set between its two passes exactly like that, so its second
     * pass re-read the images the first one had just been pointed at instead of the ping-pong pair
     * it was supposed to chain through.
     *
     * <p>The set count also covers the history PARITY (the ping-pong swaps which history/moment
     * images are read and written each frame). With a set per (dispatch, parity) pair, every set is
     * written at most twice — once for each parity — and is never touched again, so no set is ever
     * updated while an in-flight frame might still be reading it.
     */
    private static final class Stage {
        private final long descriptorSetLayout;
        private final long descriptorPool;
        private final long[] descriptorSets;
        private final long pipelineLayout;
        private final long pipeline;
        private final long[][] bound;

        private Stage(long dsl, long pool, long[] sets, long layout, long pipeline, int bindings) {
            this.descriptorSetLayout = dsl;
            this.descriptorPool = pool;
            this.descriptorSets = sets;
            this.pipelineLayout = layout;
            this.pipeline = pipeline;
            this.bound = new long[sets.length][bindings];
        }

        /** Point set {@code index} at {@code views}, skipping the update when nothing changed. */
        private long setImages(RtContext ctx, int index, long[] views) {
            long set = descriptorSets[index];
            if (Arrays.equals(bound[index], views)) {
                return set;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(views.length, stack);
                for (int i = 0; i < views.length; i++) {
                    VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                    info.get(0).imageView(views[i]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(i).sType$Default().dstSet(set).dstBinding(i)
                            .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(info);
                }
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            }
            System.arraycopy(views, 0, bound[index], 0, views.length);
            return set;
        }

        /** Forget the cached bindings so the next setImages always rewrites (see invalidateBindings). */
        private void invalidate() {
            for (long[] views : bound) {
                Arrays.fill(views, 0L);
            }
        }

        private void destroy(VkDevice vk) {
            VK10.vkDestroyPipeline(vk, pipeline, null);
            VK10.vkDestroyPipelineLayout(vk, pipelineLayout, null);
            VK10.vkDestroyDescriptorPool(vk, descriptorPool, null);
            VK10.vkDestroyDescriptorSetLayout(vk, descriptorSetLayout, null);
        }
    }

    private RtSvgfDenoiser(RtContext ctx, Stage reproject, Stage atrous) {
        this.ctx = ctx;
        this.reproject = reproject;
        this.atrous = atrous;
    }

    public static RtSvgfDenoiser create(RtContext ctx) {
        // The reprojection runs once per frame and the wavelet ATROUS_PASSES times; each dispatch
        // needs its own descriptor set, in both history parities (see Stage).
        Stage reproject = createStage(ctx, "svgf_reproject.comp.spv", REPROJECT_BINDINGS,
                REPROJECT_PUSH_BYTES, PARITIES, "svgf reproject");
        Stage atrous = createStage(ctx, "svgf_atrous.comp.spv", ATROUS_BINDINGS,
                ATROUS_PUSH_BYTES, ATROUS_PASSES * PARITIES, "svgf a-trous");
        return new RtSvgfDenoiser(ctx, reproject, atrous);
    }

    private static Stage createStage(RtContext ctx, String shader, int bindings, int pushBytes,
                                     int setCount, String label) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(bindings, stack);
            for (int i = 0; i < bindings; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p),
                    "vkCreateDescriptorSetLayout(" + label + ")");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(bindings * setCount);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(setCount).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(" + label + ")");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, label + " descriptor pool");

            LongBuffer setLayouts = stack.mallocLong(setCount);
            for (int i = 0; i < setCount; i++) {
                setLayouts.put(i, dsl);
            }
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(setLayouts);
            LongBuffer pSets = stack.mallocLong(setCount);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSets), "vkAllocateDescriptorSets(" + label + ")");
            long[] sets = new long[setCount];
            for (int i = 0; i < setCount; i++) {
                sets[i] = pSets.get(i);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[i],
                        label + " descriptor set " + i);
            }

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushBytes);
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl)).pPushConstantRanges(pushRange);
            check(VK10.vkCreatePipelineLayout(vk, plci, null, p), "vkCreatePipelineLayout(" + label + ")");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, label + " pipeline layout");

            long module = loadModule(vk, stack, shader);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SHADER_MODULE, module, label + " shader module");
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(module)
                    .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci = VkComputePipelineCreateInfo.calloc(1, stack);
            cpci.get(0).sType$Default().stage(stage).layout(layout);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(vk, VK10.VK_NULL_HANDLE, cpci, null, pPipeline),
                    "vkCreateComputePipelines(" + label + ")");
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, pPipeline.get(0), label + " compute pipeline");
            VK10.vkDestroyShaderModule(vk, module, null);

            return new Stage(dsl, pool, sets, layout, pPipeline.get(0), bindings);
        }
    }

    /**
     * Temporal stage. {@code parity} is the caller's ping-pong parity (0 or 1), which selects the
     * descriptor set matching this frame's history/moment images. {@code reset} discards all
     * history for this frame (first frame after a (re)allocation or an FOV/projection change); the
     * shader then falls back on its spatial variance estimate.
     */
    public void reproject(VkCommandBuffer cmd, int width, int height, int parity,
                          long currentView, long historyView, long momentsInView,
                          long histOutView, long momentsOutView, long filterOutView,
                          long motionView, long viewZView, long normalView,
                          long prevViewZView, long prevNormalView, long albedoView,
                          boolean reset, float maxFrames, float camForwardDelta) {
        long set = reproject.setImages(ctx, parity, new long[] {
                currentView, historyView, momentsInView, histOutView, momentsOutView, filterOutView,
                motionView, viewZView, normalView, prevViewZView, prevNormalView, albedoView,
        });
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "svgf reproject")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, reproject.pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, reproject.pipelineLayout,
                    0, stack.longs(set), null);
            ByteBuffer push = stack.malloc(REPROJECT_PUSH_BYTES);
            push.putInt(0, width);
            push.putInt(4, height);
            push.putInt(8, reset ? 1 : 0);
            push.putFloat(12, maxFrames);
            push.putFloat(16, camForwardDelta);
            VK10.vkCmdPushConstants(cmd, reproject.pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    /**
     * One à-trous iteration. {@code pass} is the 0-based iteration index, which selects both the tap
     * spacing ({@code 1 << pass}) and the descriptor set; the caller ping-pongs
     * {@code colorIn}/{@code colorOut} between iterations and inserts a memory barrier.
     */
    public void atrous(VkCommandBuffer cmd, int width, int height, int pass, int parity,
                       long colorInView, long colorOutView, long viewZView, long normalView,
                       long momentsView, long albedoView,
                       float phiLuminance, float phiNormal, float phiDepth,
                       int extraSkySmooth, boolean modulate, int debugMode) {
        // Each (iteration, parity) pair owns a descriptor set, so every dispatch reads exactly the
        // images it was recorded with and no set is rewritten under an in-flight frame (see Stage).
        int step = 1 << pass;
        long set = atrous.setImages(ctx, pass * PARITIES + parity,
                new long[] {colorInView, colorOutView, viewZView, normalView, momentsView, albedoView});
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "svgf a-trous step " + step)) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, atrous.pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, atrous.pipelineLayout,
                    0, stack.longs(set), null);
            ByteBuffer push = stack.malloc(ATROUS_PUSH_BYTES);
            push.putInt(0, width);
            push.putInt(4, height);
            push.putInt(8, step);
            push.putFloat(12, phiLuminance);
            push.putFloat(16, phiNormal);
            push.putFloat(20, phiDepth);
            push.putInt(24, extraSkySmooth);
            push.putInt(28, modulate ? 1 : 0);
            // Diagnostic overlay selector; only the final (modulating) pass acts on it.
            push.putInt(32, debugMode);
            VK10.vkCmdPushConstants(cmd, atrous.pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            VK10.vkCmdDispatch(cmd, (width + 15) / 16, (height + 15) / 16, 1);
        }
    }

    /**
     * Drop the "these views are already bound" cache. MUST be called whenever the images are
     * recreated (resize, denoiser toggle): Vulkan is free to hand a recycled handle to a brand new
     * image view, so an unchanged handle value does not imply an unchanged view. Without this, the
     * skip-if-unchanged check could leave a descriptor pointing at a destroyed view.
     */
    public void invalidateBindings() {
        reproject.invalidate();
        atrous.invalidate();
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        VkDevice vk = ctx.vk();
        reproject.destroy(vk);
        atrous.destroy(vk);
    }

    private static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtSvgfDenoiser.class.getResourceAsStream(SHADER_DIR + name)) {
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
