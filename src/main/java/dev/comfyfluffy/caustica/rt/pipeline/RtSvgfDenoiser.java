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
 * path (upscaler Off / FSR 3 / XeSS). Three compute stages, all at render resolution:
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
 *
 * <h2>A-SVGF: the adaptive temporal term</h2>
 *
 * <p>The stack is A-SVGF (Schied et al. 2018), which adds a THIRD stage ahead of the two above:
 *
 * <ol start="0">
 *   <li>{@code svgf_gradient.comp}: a downsampled, demodulated temporal-gradient statistic — how
 *       much each tile's ILLUMINATION changed since the previous frame, measured net of that tile's
 *       own noise floor.</li>
 * </ol>
 *
 * <p>Plain SVGF's blend rate is 1/history-length, so it knows how much history a pixel HAS but never
 * whether that history still describes the current lighting. A converged pixel therefore drags a
 * shadow that moved, or a highlight that turned away, behind the geometry for as long as its history
 * lasts — the temporal lag SVGF is criticised for, and the reason its output can look worse than a
 * neural denoiser that was trained on exactly this failure. The gradient supplies the missing
 * staleness term: where the lighting genuinely changed, the blend opens up and the pixel re-converges
 * immediately; where it did not, accumulation continues untouched and the pixel keeps its 48-frame
 * noise reduction. The same statistic also widens the firefly clamp, because a clamp whose bound
 * comes from a stale history would otherwise undo the release it was granted.
 *
 * <p>Implemented from the published algorithm rather than ported from Quake II RTX's
 * {@code asvgf_*.comp} shaders: those are GPL-2.0, which cannot be combined with this project's
 * LGPL-3.0-or-later. The structure deliberately differs too — q2vkpt measures the gradient from a
 * single carried-over sample location per stratum and filters it with its own à-trous chain, while
 * this implementation measures a tile MEAN of the raw difference, which is self-contained (no
 * extra sample-position buffer, no second filter chain) and cheaper.
 */
public final class RtSvgfDenoiser {
    private static final String SHADER_DIR = "/caustica/rt/";
    /** Reproject push: int width, height, reset; float maxFrames. */
    private static final int REPROJECT_PUSH_BYTES = 5 * Integer.BYTES;
    /** À-trous push: int width, height, step; float phiLuminance, phiNormal, phiDepth; int extraSkySmooth. */
    private static final int ATROUS_PUSH_BYTES = 9 * Integer.BYTES;
    private static final int REPROJECT_BINDINGS = 13;
    private static final int ATROUS_BINDINGS = 6;
    /** A-SVGF temporal-gradient push: int width, height, tile, reset. */
    private static final int GRADIENT_PUSH_BYTES = 4 * Integer.BYTES;
    private static final int GRADIENT_BINDINGS = 5;

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

    /**
     * Edge of the A-SVGF temporal-gradient tile, in pixels. The statistic is a MEAN luminance
     * difference, so a tile of N pixels averages N samples of noise down by sqrt(N), and that
     * averaging is the whole reason the statistic can tell a lighting change from 1-spp noise.
     *
     * <p>16, not the 4 this started at, and the difference is not cosmetic. The release threshold is
     * a multiple of the tile mean's own noise floor, which scales as 1/sqrt(N): at 4x4 the floor is
     * high enough that, with the per-pixel noise of a 1-spp path trace, a genuine shadow edge moving
     * through the tile does not clear it at all (verified arithmetically in
     * {@code RtDenoiserShaderRegressionTest.asvgfNoiseFloorIsMeasuredInTheUnitsOfTheStatistic}). 16x16
     * resolves a 40%-of-level change comfortably. The cost is not a concern because the pass runs once
     * per TILE rather than per pixel, so a 16x16 tile is 256x cheaper than the full-resolution
     * dispatch this replaced — the reason a bigger tile is affordable exactly where a smaller one was
     * not.
     *
     * <p>The trade-off is localisation: a shadow edge is placed to within one tile, so a release can
     * briefly affect a 16-pixel neighbourhood of the boundary. That is the same scale as the a-trous
     * cascade's own spatial reach, and it only ever costs a few frames of extra noise near a moving
     * edge rather than blur anywhere.
     */
    public static final int GRADIENT_TILE = 16;
    private final RtContext ctx;
    private final Stage reproject;
    private final Stage atrous;
    private final Stage gradient;
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

    private RtSvgfDenoiser(RtContext ctx, Stage reproject, Stage atrous, Stage gradient) {
        this.ctx = ctx;
        this.reproject = reproject;
        this.atrous = atrous;
        this.gradient = gradient;
    }

    public static RtSvgfDenoiser create(RtContext ctx) {
        // The reprojection runs once per frame and the wavelet ATROUS_PASSES times; each dispatch
        // needs its own descriptor set, in both history parities (see Stage).
        Stage reproject = createStage(ctx, "svgf_reproject.comp.spv", REPROJECT_BINDINGS,
                REPROJECT_PUSH_BYTES, PARITIES, "svgf reproject");
        Stage atrous = createStage(ctx, "svgf_atrous.comp.spv", ATROUS_BINDINGS,
                ATROUS_PUSH_BYTES, ATROUS_PASSES * PARITIES, "svgf a-trous");
        // The gradient runs once per frame, before the reprojection that consumes it. One set per
        // parity, like the reprojection: the raw trace it compares against alternates parity too.
        Stage gradient = createStage(ctx, "svgf_gradient.comp.spv", GRADIENT_BINDINGS,
                GRADIENT_PUSH_BYTES, PARITIES, "svgf gradient");
        return new RtSvgfDenoiser(ctx, reproject, atrous, gradient);
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
     * A-SVGF stage 0: the tile temporal-gradient statistic that {@link #reproject} consumes.
     *
     * <p>Must be recorded BEFORE the reprojection for this frame and AFTER the raw trace exists, and
     * {@code prevColorView} must be the PREVIOUS frame's raw trace (never the accumulated history —
     * comparing against the filter's own output feeds its error back into its guidance). {@code reset}
     * makes the pass report "no information", which leaves the reprojection on its ordinary 1/n
     * accumulation for this frame.
     */
    public void gradient(VkCommandBuffer cmd, int width, int height, int parity,
                         long currentView, long prevColorView, long gradientOutView,
                         long albedoView, long viewZView, boolean reset) {
        long set = gradient.setImages(ctx, parity, new long[] {
                currentView, prevColorView, gradientOutView, albedoView, viewZView,
        });
        try (MemoryStack stack = MemoryStack.stackPush();
             RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "svgf gradient")) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, gradient.pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, gradient.pipelineLayout,
                    0, stack.longs(set), null);
            ByteBuffer push = stack.malloc(GRADIENT_PUSH_BYTES);
            push.putInt(0, width);
            push.putInt(4, height);
            push.putInt(8, GRADIENT_TILE);
            push.putInt(12, reset ? 1 : 0);
            VK10.vkCmdPushConstants(cmd, gradient.pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            // One invocation per TILE (svgf_gradient.comp's local size is 8x8, so a group covers
            // 8*GRADIENT_TILE pixels per axis). The math rounds UP, so a frame whose size is not a
            // multiple of the tile still gets its last partial tile; the shader clamps every store
            // to the image bounds.
            int groupPixels = 8 * GRADIENT_TILE;
            VK10.vkCmdDispatch(cmd,
                    (width + groupPixels - 1) / groupPixels,
                    (height + groupPixels - 1) / groupPixels, 1);
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
                          long gradientView,
                          boolean reset, float maxFrames, float camForwardDelta) {
        long set = reproject.setImages(ctx, parity, new long[] {
                currentView, historyView, momentsInView, histOutView, momentsOutView, filterOutView,
                motionView, viewZView, normalView, prevViewZView, prevNormalView, albedoView,
                gradientView,
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
        gradient.invalidate();
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        VkDevice vk = ctx.vk();
        reproject.destroy(vk);
        atrous.destroy(vk);
        gradient.destroy(vk);
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
