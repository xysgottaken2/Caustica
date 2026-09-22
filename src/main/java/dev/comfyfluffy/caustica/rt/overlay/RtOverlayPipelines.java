package dev.comfyfluffy.caustica.rt.overlay;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDebugLabels;
import dev.comfyfluffy.caustica.rt.RtGpuExecutor;

import static dev.comfyfluffy.caustica.rt.RtContext.check;

/**
 * Shared creation-time boilerplate for the world-overlay raster passes ({@link RtWorldOverlay}). Overlay
 * features describe a pass as a {@link Spec} (shaders + vertex format + blend + attachment format) instead
 * of hand-rolling {@code VkGraphicsPipelineCreateInfo}. Pipelines are keyed by GPU state, not by feature —
 * a new feature should reuse an existing vertex-format/blend combination where one fits, so the pipeline
 * count grows with distinct state, which saturates at a handful.
 *
 * <p>All pipelines target {@code VK_KHR_dynamic_rendering} (already required + enabled by vanilla's own
 * Blaze3D device bring-up) with one colour attachment, no depth, dynamic viewport/scissor.
 */
public final class RtOverlayPipelines {
    private static final String SHADER_DIR = "/caustica/rt/";

    private RtOverlayPipelines() {
    }

    /** Vertex layouts available to overlay passes (one interleaved binding at binding 0). */
    public enum VertexFormat {
        /** No vertex input — fullscreen triangle via {@code gl_VertexIndex}. */
        NONE(0),
        /** vec3 position (12B). */
        POSITION(3 * Float.BYTES),
        /** vec3 position + RGBA8-unorm colour (16B). */
        POSITION_COLOR(3 * Float.BYTES + 4),
        /** vec3 position + vec2 uv + RGBA8-unorm colour (24B). */
        POSITION_TEX_COLOR(5 * Float.BYTES + 4);

        public final int stride;

        VertexFormat(int stride) {
            this.stride = stride;
        }
    }

    /** Colour-attachment blend state. */
    public enum Blend {
        /** Straight replace (mask writes). */
        NONE,
        /**
         * Standard "over" operator for a STRAIGHT-alpha (non-premultiplied) fragment shader output —
         * {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA} on colour, correctly-accumulating alpha on the alpha
         * channel. Every current feature (glow, name tags, block outline's own mask bridge) outputs
         * straight colour, so this is what they use to draw onto {@link RtWorldOverlay}'s shared buffer.
         * <p><b>Important:</b> applying this blend repeatedly across MULTIPLE layers drawn onto an
         * initially-transparent destination does NOT leave that destination holding straight-alpha values —
         * {@code dstRGB*(1-srcA)} decaying an already-scaled destination is exactly the premultiplied "over"
         * recipe, so the shared buffer ends up PREMULTIPLIED (`rgb = trueColour * accumulatedAlpha`) after
         * more than one layer, even though every individual draw's OWN fragment output was straight. Anyone
         * reading the shared buffer back as a SOURCE (not drawing straight colour onto it) must treat it as
         * premultiplied — see {@link #PREMULTIPLIED_ALPHA} and {@code hdr_ui_composite.comp}'s
         * un-premultiply step on the final combined UI image.
         */
        ALPHA,
        /**
         * Standard premultiplied "over" operator — {@code ONE / ONE_MINUS_SRC_ALPHA} on colour (the
         * fragment shader's own rgb is used as-is, NOT re-multiplied by its alpha) — for compositing a
         * SOURCE that already holds premultiplied content, e.g. {@link RtWorldOverlay}'s shared overlay
         * buffer (see {@link #ALPHA}'s doc) blended onto the real presented image. Using {@link #ALPHA}
         * here instead would double-multiply by alpha (dimmed/incorrect colour on anything semi-
         * transparent) since the source is already scaled.
         */
        PREMULTIPLIED_ALPHA
    }

    /** A pipeline plus its layout, destroyed together. */
    public static final class Pipeline {
        public final long layout;
        public final long handle;

        private Pipeline(long layout, long handle) {
            this.layout = layout;
            this.handle = handle;
        }

        public void destroy(VkDevice vk) {
            VK10.vkDestroyPipeline(vk, handle, null);
            VK10.vkDestroyPipelineLayout(vk, layout, null);
        }
    }

    /** Everything that varies between overlay pipelines; defaults cover the common case. */
    public static final class Spec {
        private final String vertSpv;
        private final String fragSpv;
        private VertexFormat vertexFormat = VertexFormat.NONE;
        private int topology = VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        private Blend blend = Blend.NONE;
        private int attachmentFormat;
        private int samples = VK10.VK_SAMPLE_COUNT_1_BIT;
        private int pushBytes;
        private int pushStages;
        private long descriptorSetLayout;

        public Spec(String vertSpv, String fragSpv) {
            this.vertSpv = vertSpv;
            this.fragSpv = fragSpv;
        }

        public Spec vertex(VertexFormat format) {
            this.vertexFormat = format;
            return this;
        }

        public Spec topology(int vkTopology) {
            this.topology = vkTopology;
            return this;
        }

        public Spec blend(Blend blend) {
            this.blend = blend;
            return this;
        }

        /** The colour attachment's VkFormat — {@link RtWorldOverlay#TARGET_FORMAT} for composite passes. */
        public Spec attachment(int vkFormat) {
            this.attachmentFormat = vkFormat;
            return this;
        }

        /** Rasterization sample count (default {@code VK_SAMPLE_COUNT_1_BIT}) — pass {@link
         *  dev.comfyfluffy.caustica.rt.RtDeviceBringup#overlayMsaaSamples()} for a multisampled mask pass that gets
         *  dynamic-rendering-resolved into a single-sample target afterwards. */
        public Spec samples(int sampleCount) {
            this.samples = sampleCount;
            return this;
        }

        public Spec push(int bytes, int stageFlags) {
            this.pushBytes = bytes;
            this.pushStages = stageFlags;
            return this;
        }

        public Spec descriptorSetLayout(long dsl) {
            this.descriptorSetLayout = dsl;
            return this;
        }

        public Pipeline build(RtContext ctx, String label) {
            if (attachmentFormat == 0) {
                throw new IllegalStateException("overlay pipeline '" + label + "' has no attachment format");
            }
            return createGraphics(ctx, this, label);
        }
    }

    private static Pipeline createGraphics(RtContext ctx, Spec spec, String label) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);

            VkPipelineLayoutCreateInfo layoutCi = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default();
            if (spec.pushBytes > 0) {
                VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
                push.get(0).stageFlags(spec.pushStages).offset(0).size(spec.pushBytes);
                layoutCi.pPushConstantRanges(push);
            }
            if (spec.descriptorSetLayout != 0L) {
                layoutCi.pSetLayouts(stack.longs(spec.descriptorSetLayout));
            }
            check(VK10.vkCreatePipelineLayout(vk, layoutCi, null, p), "vkCreatePipelineLayout(" + label + ")");
            long layout = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE_LAYOUT, layout, label + " pipeline layout");

            long vertModule = loadModule(vk, stack, spec.vertSpv);
            long fragModule = loadModule(vk, stack, spec.fragSpv);
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK10.VK_SHADER_STAGE_VERTEX_BIT).module(vertModule).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK10.VK_SHADER_STAGE_FRAGMENT_BIT).module(fragModule).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (spec.vertexFormat != VertexFormat.NONE) {
                VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
                binding.get(0).binding(0).stride(spec.vertexFormat.stride).inputRate(VK10.VK_VERTEX_INPUT_RATE_VERTEX);
                vertexInput.pVertexBindingDescriptions(binding)
                        .pVertexAttributeDescriptions(vertexAttributes(stack, spec.vertexFormat));
            }

            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                    .sType$Default().topology(spec.topology);

            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(VK10.VK_POLYGON_MODE_FILL).cullMode(VK10.VK_CULL_MODE_NONE)
                    .frontFace(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default().rasterizationSamples(spec.samples);

            VkPipelineColorBlendAttachmentState.Buffer blendAttach = VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttach.get(0).colorWriteMask(
                    VK10.VK_COLOR_COMPONENT_R_BIT | VK10.VK_COLOR_COMPONENT_G_BIT
                            | VK10.VK_COLOR_COMPONENT_B_BIT | VK10.VK_COLOR_COMPONENT_A_BIT);
            if (spec.blend == Blend.ALPHA) {
                // Straight-alpha "over": the fragment's own rgb is scaled by ITS OWN alpha before adding —
                // see Blend.ALPHA's doc for why this leaves the destination premultiplied once more than one
                // layer has drawn onto it, even though every individual source is straight. Alpha channel
                // correctly accumulates (outA = srcA + dstA*(1-srcA)) rather than preserving whatever was
                // already there (srcAlphaFactor=ZERO/dstAlphaFactor=ONE would silently discard every
                // feature's alpha contribution — bit us once already, see memory).
                blendAttach.get(0).blendEnable(true)
                        .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK10.VK_BLEND_OP_ADD);
            } else if (spec.blend == Blend.PREMULTIPLIED_ALPHA) {
                // Premultiplied "over": the fragment's own rgb is used as-is (NOT re-multiplied by alpha) —
                // for a source that already holds premultiplied content, same alpha-channel accumulation.
                blendAttach.get(0).blendEnable(true)
                        .srcColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .dstColorBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK10.VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE)
                        .dstAlphaBlendFactor(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .alphaBlendOp(VK10.VK_BLEND_OP_ADD);
            } else {
                blendAttach.get(0).blendEnable(false);
            }
            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(blendAttach);

            // Line-topology pipelines get a dynamic line width (vkCmdSetLineWidth) instead of the fixed
            // 1.0 baked above — real thickness needs the device's wideLines feature (RtDeviceBringup
            // .wideLinesEnabled/maxLineWidth); callers must clamp their desired width to that max
            // themselves (Vulkan mandates exactly 1.0 without the feature).
            boolean isLineTopology = spec.topology == VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST
                    || spec.topology == VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            int[] dynamicStates = isLineTopology
                    ? new int[]{VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR, VK10.VK_DYNAMIC_STATE_LINE_WIDTH}
                    : new int[]{VK10.VK_DYNAMIC_STATE_VIEWPORT, VK10.VK_DYNAMIC_STATE_SCISSOR};
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(dynamicStates));

            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                    .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(spec.attachmentFormat));

            VkGraphicsPipelineCreateInfo.Buffer gpci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            gpci.get(0).sType$Default().pNext(renderingInfo.address())
                    .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(inputAssembly)
                    .pViewportState(viewportState).pRasterizationState(raster).pMultisampleState(multisample)
                    .pColorBlendState(colorBlend).pDynamicState(dynamicState).layout(layout)
                    .renderPass(VK10.VK_NULL_HANDLE).subpass(0);
            LongBuffer pPipeline = stack.mallocLong(1);
            check(VK10.vkCreateGraphicsPipelines(vk, VK10.VK_NULL_HANDLE, gpci, null, pPipeline),
                    "vkCreateGraphicsPipelines(" + label + ")");
            long handle = pPipeline.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_PIPELINE, handle, label + " pipeline");
            VK10.vkDestroyShaderModule(vk, vertModule, null);
            VK10.vkDestroyShaderModule(vk, fragModule, null);
            return new Pipeline(layout, handle);
        }
    }

    private static VkVertexInputAttributeDescription.Buffer vertexAttributes(MemoryStack stack, VertexFormat format) {
        return switch (format) {
            case NONE -> throw new IllegalArgumentException("NONE has no attributes");
            case POSITION -> {
                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(1, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                yield attrs;
            }
            case POSITION_COLOR -> {
                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(2, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(12);
                yield attrs;
            }
            case POSITION_TEX_COLOR -> {
                VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(3, stack);
                attrs.get(0).location(0).binding(0).format(VK10.VK_FORMAT_R32G32B32_SFLOAT).offset(0);
                attrs.get(1).location(1).binding(0).format(VK10.VK_FORMAT_R32G32_SFLOAT).offset(12);
                attrs.get(2).location(2).binding(0).format(VK10.VK_FORMAT_R8G8B8A8_UNORM).offset(20);
                yield attrs;
            }
        };
    }

    /**
     * A single descriptor set of {@code count} storage images (bindings 0..count-1), with its layout and
     * pool — enough for overlay composite passes that read a mod-owned mask/scratch image. (Vanilla-owned
     * textures can never be bound here: Blaze3D never sets VK_IMAGE_USAGE_STORAGE_BIT — they are reachable
     * only as colour attachments.)
     */
    public static final class StorageImageSet {
        public final long layout;
        private final long pool;
        public final long set;
        private final long[] boundViews;

        private StorageImageSet(long layout, long pool, long set, int count) {
            this.layout = layout;
            this.pool = pool;
            this.set = set;
            this.boundViews = new long[count];
        }

        /** Point binding {@code binding} at {@code view} (GENERAL layout); no-op when already bound. */
        public void bind(RtContext ctx, int binding, long view) {
            if (boundViews[binding] == view) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(binding)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            }
            boundViews[binding] = view;
        }

        public void destroy(VkDevice vk) {
            VK10.vkDestroyDescriptorPool(vk, pool, null);
            VK10.vkDestroyDescriptorSetLayout(vk, layout, null);
        }
    }

    public static StorageImageSet storageImageSet(RtContext ctx, int count, int stageFlags, String label) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(count, stack);
            for (int i = 0; i < count; i++) {
                binds.get(i).binding(i).descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(stageFlags);
            }
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(" + label + ")");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(count);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(" + label + ")");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, label + " descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(" + label + ")");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, label + " descriptor set");
            return new StorageImageSet(dsl, pool, set, count);
        }
    }

    /**
     * A single combined-image-sampler descriptor set (binding 0) — for overlay passes that sample a real
     * texture (e.g. a font atlas page) rather than reading a mod-owned storage image. Same {@code
     * VK_IMAGE_LAYOUT_GENERAL} convention as every other sampled-image binding in this codebase (Blaze3D
     * keeps its own textures in GENERAL too — see {@code RtEntityTextures}/{@code RtPipeline}'s bindless
     * texture arrays, which bind vanilla-owned atlases the same way).
     */
    public static final class SampledImageSet {
        public final long layout;
        private final long pool;
        public final long set;
        private long boundView;

        private SampledImageSet(long layout, long pool, long set) {
            this.layout = layout;
            this.pool = pool;
            this.set = set;
        }

        /** Point binding 0 at {@code view}, sampled with {@code sampler}; no-op when already bound. */
        public void bind(RtContext ctx, long view, long sampler) {
            if (boundView == view) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).sampler(sampler).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
                VK10.vkUpdateDescriptorSets(ctx.vk(), writes, null);
            }
            boundView = view;
        }

        public void destroy(VkDevice vk) {
            VK10.vkDestroyDescriptorPool(vk, pool, null);
            VK10.vkDestroyDescriptorSetLayout(vk, layout, null);
        }
    }

    public static SampledImageSet sampledImageSet(RtContext ctx, int stageFlags, String label) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(stageFlags);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(" + label + ")");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(" + label + ")");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, label + " descriptor pool");

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl));
            LongBuffer pSet = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(" + label + ")");
            long set = pSet.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, set, label + " descriptor set");
            return new SampledImageSet(dsl, pool, set);
        }
    }

    /**
     * A pool of combined-image-sampler descriptor sets sharing one layout — for overlay passes that sample
     * MULTIPLE distinct textures within the same frame (e.g. one draw per font-atlas page), where a single
     * descriptor set rewritten between draws (see {@link SampledImageSet}) is unsafe: descriptor sets are
     * live objects, so every {@code vkCmdBindDescriptorSets} call recorded against the SAME set ends up
     * sampling whichever texture was written LAST by the time the command buffer actually executes on the
     * GPU, not whatever was bound at record time — earlier draws in the same command buffer would silently
     * end up sampling the final page's atlas instead of their own. Each distinct view gets its own
     * descriptor set instead, allocated and written EXACTLY ONCE ({@link #allocateAndBind}) — never mutated
     * again — so callers should cache the returned handle per view (e.g. an
     * {@code IdentityHashMap<GpuTextureView, Long>}) and reuse it across frames; the underlying texture view
     * is expected to be stable for the session (a font atlas page doesn't get recreated), so there's nothing
     * to rebind after the first time.
     */
    public static final class SampledImageSetPool {
        public final long layout;
        private final long pool;

        private SampledImageSetPool(long layout, long pool) {
            this.layout = layout;
            this.pool = pool;
        }

        /** Allocate a fresh descriptor set from this pool and write {@code view}/{@code sampler} into it once. */
        public long allocateAndBind(RtContext ctx, long view, long sampler) {
            VkDevice vk = ctx.vk();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                        .descriptorPool(pool).pSetLayouts(stack.longs(layout));
                LongBuffer pSet = stack.mallocLong(1);
                check(VK10.vkAllocateDescriptorSets(vk, dsai, pSet), "vkAllocateDescriptorSets(sampled image set pool)");
                long set = pSet.get(0);

                VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
                info.get(0).sampler(sampler).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
                writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                        .descriptorCount(1).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
                VK10.vkUpdateDescriptorSets(vk, writes, null);
                return set;
            }
        }

        /** Frees every descriptor set ever allocated from this pool, along with the pool and layout. */
        public void destroy(VkDevice vk) {
            VK10.vkDestroyDescriptorPool(vk, pool, null);
            VK10.vkDestroyDescriptorSetLayout(vk, layout, null);
        }
    }

    public static SampledImageSetPool sampledImageSetPool(RtContext ctx, int stageFlags, int maxSets, String label) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binds.get(0).binding(0).descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1).stageFlags(stageFlags);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(" + label + ")");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(maxSets);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(maxSets).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(" + label + ")");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, label + " descriptor pool");

            return new SampledImageSetPool(dsl, pool);
        }
    }

    /**
     * A ring of descriptor sets each holding one {@code VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR}
     * binding — for overlay passes that issue an inline {@code rayQueryEXT} occlusion test against the
     * world TLAS (e.g. block outline). A ring (not a single set, unlike {@link StorageImageSet}/
     * {@link SampledImageSet}) is required because the TLAS handle changes most frames ({@code RtAccel
     * .TlasRing} cycles it every frame even when it doesn't grow) — rewriting a single set's binding while
     * an earlier frame's command buffer referencing that same set may still be executing on the GPU is the
     * same "descriptor set update while in use" hazard {@code RtPipeline.setTlas} guards against. Exact
     * graphics completion protects both rings; their depths only avoid routine host waits.
     */
    public static final class AccelStructureSet {
        private static final int RING = 4;
        public final long layout;
        private final long pool;
        private final long[] sets;
        private final RtGpuExecutor.TrackedGraphicsUse[] uses;
        private int current = -1;

        private AccelStructureSet(long layout, long pool, long[] sets) {
            this.layout = layout;
            this.pool = pool;
            this.sets = sets;
            this.uses = new RtGpuExecutor.TrackedGraphicsUse[sets.length];
            for (int i = 0; i < uses.length; i++) {
                uses[i] = new RtGpuExecutor.TrackedGraphicsUse();
            }
        }

        /** Wait for the next ring slot's prior use, write {@code tlas}, and return the set for this frame. */
        public long bind(RtContext ctx, long tlas, RtGpuExecutor.GraphicsUse graphicsUse) {
            current = (current + 1) % RING;
            RtGpuExecutor.TrackedGraphicsUse slotUse = uses[current];
            ctx.gpuExecutor().graphicsUseWaiter().await(slotUse);
            long set = sets[current];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                        .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                        .pAccelerationStructures(stack.longs(tlas));
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
                write.get(0).sType$Default().pNext(asWrite.address()).dstSet(set).dstBinding(0)
                        .descriptorCount(1).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                VK10.vkUpdateDescriptorSets(ctx.vk(), write, null);
            }
            slotUse.mark(graphicsUse);
            return set;
        }

        public void destroy(VkDevice vk) {
            VK10.vkDestroyDescriptorPool(vk, pool, null);
            VK10.vkDestroyDescriptorSetLayout(vk, layout, null);
        }
    }

    public static AccelStructureSet accelStructureSet(RtContext ctx, int stageFlags, String label) {
        VkDevice vk = ctx.vk();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(1, stack);
            binds.get(0).binding(0).descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1).stageFlags(stageFlags);
            VkDescriptorSetLayoutCreateInfo dslci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            check(VK10.vkCreateDescriptorSetLayout(vk, dslci, null, p), "vkCreateDescriptorSetLayout(" + label + ")");
            long dsl = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET_LAYOUT, dsl, label + " descriptor set layout");

            int ring = AccelStructureSet.RING;
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(ring);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(ring).pPoolSizes(poolSizes);
            check(VK10.vkCreateDescriptorPool(vk, dpci, null, p), "vkCreateDescriptorPool(" + label + ")");
            long pool = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_POOL, pool, label + " descriptor pool");

            LongBuffer dsls = stack.mallocLong(ring);
            for (int i = 0; i < ring; i++) {
                dsls.put(i, dsl);
            }
            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(dsls);
            LongBuffer pSets = stack.mallocLong(ring);
            check(VK10.vkAllocateDescriptorSets(vk, dsai, pSets), "vkAllocateDescriptorSets(" + label + ")");
            long[] sets = new long[ring];
            for (int i = 0; i < ring; i++) {
                sets[i] = pSets.get(i);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_DESCRIPTOR_SET, sets[i], label + " descriptor set " + i);
            }
            return new AccelStructureSet(dsl, pool, sets);
        }
    }

    /** A shared nearest/clamp sampler, for overlay passes sampling a real texture (e.g. a font atlas). */
    public static long createNearestClampSampler(RtContext ctx, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateSampler(ctx.vk(), sci, null, p), "vkCreateSampler(" + label + ")");
            long sampler = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler, label + " sampler");
            return sampler;
        }
    }

    static long loadModule(VkDevice vk, MemoryStack stack, String name) {
        byte[] bytes;
        try (InputStream in = RtOverlayPipelines.class.getResourceAsStream(SHADER_DIR + name)) {
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
