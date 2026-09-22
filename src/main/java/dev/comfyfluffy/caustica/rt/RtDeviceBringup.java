package dev.comfyfluffy.caustica.rt;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.xess.XessRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTMutableDescriptorType;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelinePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceOpacityMicromapPropertiesEXT;
import org.lwjgl.vulkan.VkPhysicalDevicePresentIdFeaturesKHR;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPositionFetch.VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTOpacityMicromap.VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME;
import static org.lwjgl.vulkan.NVLowLatency2.VK_NV_LOW_LATENCY_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPresentId.VK_KHR_PRESENT_ID_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTRayTracingInvocationReorder.VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME;

/**
 * RT device bring-up. Enables the hardware ray-tracing device extensions and their
 * feature structs on vanilla's Blaze3D device at {@code vkCreateDevice} time.
 *
 * <p>Vanilla assembles a {@code VkPhysicalDeviceFeatures2} pNext chain from the
 * {@code Set<VulkanFeature>} (arg2) via {@code VulkanFeature.set} →
 * {@code findOrCreateStructInPNextChain} (dedup by sType), so {@code bufferDeviceAddress}
 * merges into the existing {@code VkPhysicalDeviceVulkan12Features} struct and the two
 * KHR structs are created fresh. BDA / descriptor-indexing / SPIR-V 1.4 are core on the
 * 1.4 device, so only three extension <i>names</i> are needed; the rest are feature enables.
 *
 * <p>Extension names are added to the device extension list separately; feature structs are added here.
 * Both are gated on the selected device actually supporting RT; if not, nothing is added
 * and the device comes up exactly as vanilla. {@code caustica.rt} is read once here, at
 * {@code vkCreateDevice} time, before the device exists — flipping it later at runtime cannot add
 * device features to an already-created device, so a config change only takes effect on restart.
 */
public final class RtDeviceBringup {
    public static boolean enabledByProperty() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    /**
     * The device extensions RT needs (BDA/descriptor-indexing/SPIR-V 1.4 are core on 1.4).
     * {@code ray_tracing_position_fetch} lets the closest-hit read hit triangle vertex positions
     * ({@code gl_HitTriangleVertexPositionsEXT}) for the normal-map TBN, avoiding a positions buffer
     * plumbed through the geometry tables. Supported on all RTX GPUs (the project's target).
     * {@code ray_query} lets fragment shaders (the world-overlay pass, e.g. block outline) issue inline
     * {@code rayQueryEXT} occlusion tests against the same TLAS the ray-tracing pipeline traces, without a
     * dedicated raygen dispatch. Same RTX-GPU support floor as the rest of this list.
     */
    public static final List<String> RT_EXTENSIONS = List.of(
            VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME,
            VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME,
            VK_KHR_RAY_TRACING_POSITION_FETCH_EXTENSION_NAME,
            VK_KHR_RAY_QUERY_EXTENSION_NAME);

    /**
     * OPTIONAL RT extensions: enabled only when the selected device supports them AND the gate is on, but
     * never required — a device lacking them still comes up RT-capable (unlike {@link #RT_EXTENSIONS}, whose
     * absence disables RT entirely). {@code VK_EXT_opacity_micromap} (any-hit opt, lever C): per-triangle
     * opacity micromaps let the hardware skip {@code world.rahit} on fully-opaque/transparent cutout micro-
     * triangles, so the alpha-test any-hit runs only on the foliage silhouette. Hardware-accelerated on RTX
     * 40-series and Blackwell; absent / software elsewhere, hence optional.
     */
    public static final List<String> OPTIONAL_RT_EXTENSIONS = List.of(
            VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);

    /**
     * NVIDIA Reflex. {@code VK_NV_low_latency2} adds no feature bits (function-only extension). Bundled with
     * {@code VK_KHR_present_id}: Reflex's latency markers carry a {@code presentID} that only correlates with
     * a specific present call when that present's {@code vkQueuePresentKHR} chains a matching
     * {@code VkPresentIdKHR} — which needs its own device feature bit (unlike low_latency2, which is
     * function-only).
     */
    public static final List<String> REFLEX_EXTENSIONS = List.of(
            VK_NV_LOW_LATENCY_2_EXTENSION_NAME, VK_KHR_PRESENT_ID_EXTENSION_NAME);

    private static volatile boolean rtRequested;
    private static volatile SerBackend serBackend = SerBackend.NONE;
    // Identity of the device the last (i.e. current) vkCreateDevice used, captured unconditionally in
    // addExtensions so UI capability hints (e.g. the Frame Generation gate on the options screen) can be
    // answered from the render thread without forcing NGX/Vulkan bring-up on the main menu.
    private static volatile String gpuName = "";
    private static volatile String gpuVendorName = "";
    private static volatile boolean ommEnabled; // VK_EXT_opacity_micromap actually enabled on the device
    private static volatile boolean reflexEnabled; // VK_NV_low_latency2 actually enabled on the device
    private static volatile boolean presentIdEnabled; // VK_KHR_present_id actually enabled on the device
    private static volatile boolean wideLinesEnabled; // VkPhysicalDeviceFeatures.wideLines actually enabled
    // XeSS's required device features actually enabled (storage-image-write-without-format +
    // mutable descriptors; DP4a int8/dot-product ride along per-feature when supported) — the
    // selector's hardware gate alongside the bundled runtime.
    private static volatile boolean xessFeaturesEnabled;
    private static volatile float maxLineWidth = 1.0f; // device's lineWidthRange[1]; 1.0 unless wideLinesEnabled
    private static volatile int overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT; // capped to the device's framebufferColorSampleCounts
    private static volatile int maxOpacity4StateSubdivisionLevel;
    private static volatile int computeQueueFamilyIndex = -1;
    private static volatile int computeQueueIndex = -1;
    private static boolean loggedUnavailable;

    private static final VulkanPNextStruct AS_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class);
    private static final VulkanPNextStruct RT_PIPELINE_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceRayTracingPipelineFeaturesKHR.class);
    private static final VulkanPNextStruct POSITION_FETCH_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceRayTracingPositionFetchFeaturesKHR.class);
    private static final VulkanPNextStruct RAY_QUERY_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class);
    private static final VulkanPNextStruct SER_EXT_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceRayTracingInvocationReorderFeaturesEXT.class);
    private static final VulkanPNextStruct OMM_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceOpacityMicromapFeaturesEXT.class);
    private static final VulkanPNextStruct PRESENT_ID_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDevicePresentIdFeaturesKHR.class);
    private static final VulkanPNextStruct VK13_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceVulkan13Features.class);
    private static final VulkanPNextStruct MUTABLE_DESCRIPTOR_TYPE_FEATURES_STRUCT =
            new VulkanPNextStruct(VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT.class);

    private static final VulkanFeature BUFFER_DEVICE_ADDRESS_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress");
    private static final VulkanFeature RUNTIME_DESCRIPTOR_ARRAY_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK12_FEATURES_STRUCT, "runtimeDescriptorArray");
    private static final VulkanFeature SAMPLED_IMAGE_NON_UNIFORM_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderSampledImageArrayNonUniformIndexing");
    private static final VulkanFeature DESCRIPTOR_PARTIALLY_BOUND_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK12_FEATURES_STRUCT, "descriptorBindingPartiallyBound");
    private static final VulkanFeature SAMPLED_IMAGE_UPDATE_AFTER_BIND_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK12_FEATURES_STRUCT, "descriptorBindingSampledImageUpdateAfterBind");
    private static final VulkanFeature SHADER_INT64_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt64");
    private static final VulkanFeature ACCELERATION_STRUCTURE_FEATURE = new VulkanFeature(
            AS_FEATURES_STRUCT, "accelerationStructure");
    private static final VulkanFeature RAY_TRACING_PIPELINE_FEATURE = new VulkanFeature(
            RT_PIPELINE_FEATURES_STRUCT, "rayTracingPipeline");
    private static final VulkanFeature POSITION_FETCH_FEATURE = new VulkanFeature(
            POSITION_FETCH_FEATURES_STRUCT, "rayTracingPositionFetch");
    private static final VulkanFeature RAY_QUERY_FEATURE = new VulkanFeature(
            RAY_QUERY_FEATURES_STRUCT, "rayQuery");
    private static final VulkanFeature SER_EXT_FEATURE = new VulkanFeature(
            SER_EXT_FEATURES_STRUCT, "rayTracingInvocationReorder");
    private static final VulkanFeature OMM_FEATURE = new VulkanFeature(
            OMM_FEATURES_STRUCT, "micromap");
    private static final VulkanFeature PRESENT_ID_FEATURE = new VulkanFeature(
            PRESENT_ID_FEATURES_STRUCT, "presentId");
    private static final VulkanFeature WIDE_LINES_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK10_FEATURES_STRUCT, "wideLines");
    // Intel XeSS device features, per the XeSS-SR developer guide (v2.1.1 "Requirements"):
    // REQUIRED — shaderStorageImageWriteWithoutFormat (core VK10) + mutableDescriptorType
    // (VK_EXT_mutable_descriptor_type). Without both, xessVKCreateContext cannot run, so they gate
    // the whole XeSS offer.
    // PERFORMANCE-ONLY — shaderInt8 (VK12) + shaderIntegerDotProduct (VK13): the quantized INT8
    // network's DP4a fast path. XeSS still runs without them, just not accelerated — on a GeForce
    // RTX 2060 (no XMX, DP4a is its only path) these two ARE the performance, so they are enabled
    // whenever the device supports them alongside the required pair.
    private static final VulkanFeature SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat");
    private static final VulkanFeature MUTABLE_DESCRIPTOR_TYPE_FEATURE = new VulkanFeature(
            MUTABLE_DESCRIPTOR_TYPE_FEATURES_STRUCT, "mutableDescriptorType");
    private static final VulkanFeature SHADER_INT8_FEATURE = new VulkanFeature(
            VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderInt8");
    private static final VulkanFeature SHADER_INTEGER_DOT_PRODUCT_FEATURE = new VulkanFeature(
            VK13_FEATURES_STRUCT, "shaderIntegerDotProduct");

    private static final List<VulkanFeature> REQUIRED_RT_FEATURES = List.of(
            BUFFER_DEVICE_ADDRESS_FEATURE,
            RUNTIME_DESCRIPTOR_ARRAY_FEATURE,
            SAMPLED_IMAGE_NON_UNIFORM_FEATURE,
            DESCRIPTOR_PARTIALLY_BOUND_FEATURE,
            SAMPLED_IMAGE_UPDATE_AFTER_BIND_FEATURE,
            SHADER_INT64_FEATURE,
            ACCELERATION_STRUCTURE_FEATURE,
            RAY_TRACING_PIPELINE_FEATURE,
            POSITION_FETCH_FEATURE,
            RAY_QUERY_FEATURE);

    private enum SerBackend {
        NONE("none", null, "world_primary.rgen.spv", "world.rgen.spv"),
        EXT("EXT", VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME,
                "world_primary.rgen.spv", "world_ser.rgen.spv");

        final String label;
        final String extensionName;
        final String worldPrimaryRaygenShader;
        final String worldRaygenShader;

        SerBackend(String label, String extensionName, String worldPrimaryRaygenShader,
                   String worldRaygenShader) {
            this.label = label;
            this.extensionName = extensionName;
            this.worldPrimaryRaygenShader = worldPrimaryRaygenShader;
            this.worldRaygenShader = worldRaygenShader;
        }
    }

    private record FeatureSupport(List<String> missingRequired, SerBackend serBackend,
                                  boolean omm, boolean presentId, boolean wideLines,
                                  boolean xess, boolean xessInt8, boolean xessIntDot) {
        boolean supportsRt() {
            return missingRequired.isEmpty();
        }
    }

    private RtDeviceBringup() {
    }

    /** True once we have augmented a device creation to request RT (extensions + features). */
    public static boolean rtRequested() {
        return rtRequested;
    }

    public static String worldRaygenShader() {
        return serBackend.worldRaygenShader;
    }

    public static String worldPrimaryRaygenShader() {
        return serBackend.worldPrimaryRaygenShader;
    }

    public static boolean serExtEnabled() {
        return serBackend == SerBackend.EXT;
    }

    /** True if {@code VK_EXT_opacity_micromap} was enabled on the device (gate on + device support). */
    public static boolean ommEnabled() {
        return ommEnabled;
    }

    /** True if {@code VK_NV_low_latency2} (Reflex) was enabled on the device (gate on + device support). */
    public static boolean reflexEnabled() {
        return reflexEnabled;
    }

    /** True if {@code VK_KHR_present_id} was enabled on the device (needed for Reflex marker/present correlation). */
    public static boolean presentIdEnabled() {
        return presentIdEnabled;
    }

    /** Hardware limit for 4-state opacity micromaps, populated by {@link #probe(VkDevice)}. */
    public static int maxOpacity4StateSubdivisionLevel() {
        return maxOpacity4StateSubdivisionLevel;
    }

    /** True if {@code VkPhysicalDeviceFeatures.wideLines} was enabled on the device (world-overlay thick
     *  lines, e.g. the block outline, use this instead of a screen-space quad when available). */
    public static boolean wideLinesEnabled() {
        return wideLinesEnabled;
    }

    /**
     * True if XeSS's required device features (shaderStorageImageWriteWithoutFormat +
     * mutableDescriptorType) were enabled on the device. The upscaler selector's hardware gate:
     * the bundled runtime decides the platform half, this decides the GPU half.
     */
    public static boolean xessFeaturesEnabled() {
        return xessFeaturesEnabled;
    }

    /** The device's max native line width (raster {@code lineWidthRange[1]}); 1.0 if wideLines isn't
     *  enabled (Vulkan mandates exactly 1.0 in that case). Callers must clamp their desired width to this. */
    public static float maxLineWidth() {
        return maxLineWidth;
    }

    /** {@code VK_SAMPLE_COUNT_4_BIT} capped down to whatever the device's {@code framebufferColorSampleCounts}
     *  actually advertises (2x, or 1x/no MSAA on the rare device that lacks even that) — no device feature to
     *  enable, just a raster/framebuffer property, unlike {@link #wideLinesEnabled()}. World-overlay passes
     *  that need edge AA (e.g. the block outline's native wide line) use this as their pipeline's
     *  {@code rasterizationSamples}. */
    public static int overlayMsaaSamples() {
        return overlayMsaaSamples;
    }

    /** Whether device creation reserved a queue handle exclusively for Caustica terrain work. */
    public static boolean computeQueueReserved() {
        return computeQueueFamilyIndex >= 0 && computeQueueIndex >= 0;
    }

    public static int computeQueueFamilyIndex() {
        if (!computeQueueReserved()) {
            throw new IllegalStateException("Caustica compute queue was not reserved");
        }
        return computeQueueFamilyIndex;
    }

    public static int computeQueueIndex() {
        if (!computeQueueReserved()) {
            throw new IllegalStateException("Caustica compute queue was not reserved");
        }
        return computeQueueIndex;
    }

    /**
     * Reserve one additional physical queue at device-creation time. Minecraft's queue-family map only
     * requests handles for its graphics/compute/transfer queues; fetching a higher queue index without first
     * increasing the matching {@link VkDeviceQueueCreateInfo#queueCount()} would be invalid. Prefer a
     * compute-only family, but add a previously-unused compute family when that leaves the Minecraft queues
     * untouched and has a free physical slot.
     */
    public static void reserveComputeQueue(VkDeviceCreateInfo deviceCreateInfo,
                                           VulkanPhysicalDevice physicalDevice, MemoryStack stack) {
        computeQueueFamilyIndex = -1;
        computeQueueIndex = -1;
        if (!rtRequested) {
            return;
        }

        VkDeviceQueueCreateInfo.Buffer requestedQueues = deviceCreateInfo.pQueueCreateInfos();
        if (requestedQueues == null) {
            CausticaMod.LOGGER.warn("Caustica RT disabled: Vulkan device creation has no queue requests");
            return;
        }

        var count = stack.callocInt(1);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
        VK10.vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice.vkPhysicalDevice(), count, families);

        int[] requestedCounts = new int[families.capacity()];
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            VkDeviceQueueCreateInfo request = requestedQueues.get(i);
            int family = request.queueFamilyIndex();
            if (family >= 0 && family < requestedCounts.length) {
                requestedCounts[family] = request.queueCount();
            }
        }

        int selectedFamily = -1;
        int selectedScore = Integer.MAX_VALUE;
        for (int family = 0; family < families.capacity(); family++) {
            VkQueueFamilyProperties properties = families.get(family);
            int flags = properties.queueFlags();
            if ((flags & VK10.VK_QUEUE_COMPUTE_BIT) == 0
                    || requestedCounts[family] >= properties.queueCount()) {
                continue;
            }
            // Compute-only families win; fewer capabilities then fewer already-requested handles break ties.
            int score = ((flags & VK10.VK_QUEUE_GRAPHICS_BIT) != 0 ? 1_000 : 0)
                    + Integer.bitCount(flags) * 10 + requestedCounts[family];
            if (score < selectedScore) {
                selectedFamily = family;
                selectedScore = score;
            }
        }

        if (selectedFamily < 0) {
            CausticaMod.LOGGER.warn(
                    "Caustica RT disabled: no spare compute-capable Vulkan queue is available");
            return;
        }

        int queueIndex = requestedCounts[selectedFamily];
        VkDeviceQueueCreateInfo matchingRequest = null;
        for (int i = 0; i < requestedQueues.capacity(); i++) {
            VkDeviceQueueCreateInfo request = requestedQueues.get(i);
            if (request.queueFamilyIndex() == selectedFamily) {
                matchingRequest = request;
                break;
            }
        }

        if (matchingRequest != null) {
            var oldPriorities = matchingRequest.pQueuePriorities();
            var priorities = stack.callocFloat(queueIndex + 1);
            if (oldPriorities != null) {
                for (int i = 0; i < queueIndex; i++) {
                    priorities.put(i, oldPriorities.get(i));
                }
            }
            matchingRequest.pQueuePriorities(priorities);
        } else {
            VkDeviceQueueCreateInfo.Buffer expanded = VkDeviceQueueCreateInfo.calloc(requestedQueues.capacity() + 1, stack);
            for (int i = 0; i < requestedQueues.capacity(); i++) {
                VkDeviceQueueCreateInfo source = requestedQueues.get(i);
                expanded.get(i)
                        .sType(source.sType())
                        .pNext(source.pNext())
                        .flags(source.flags())
                        .queueFamilyIndex(source.queueFamilyIndex())
                        .pQueuePriorities(source.pQueuePriorities());
            }
            expanded.get(requestedQueues.capacity())
                    .sType$Default()
                    .queueFamilyIndex(selectedFamily)
                    .pQueuePriorities(stack.callocFloat(1));
            deviceCreateInfo.pQueueCreateInfos(expanded);
        }

        computeQueueFamilyIndex = selectedFamily;
        computeQueueIndex = queueIndex;
        VkQueueFamilyProperties selected = families.get(selectedFamily);
        CausticaMod.LOGGER.info(
                "Reserved Caustica compute queue family={} index={} (family queues={}, flags=0x{})",
                selectedFamily, queueIndex, selected.queueCount(), Integer.toHexString(selected.queueFlags()));
    }

    /** Optional extensions whose extension and feature requirements are both supported. */
    private static List<String> supportedOptionalExtensions(VulkanPhysicalDevice physicalDevice,
                                                             FeatureSupport support) {
        List<String> supported = new ArrayList<>();
        if (support.omm) {
            supported.add(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
        }
        if (reflexRequested() && physicalDevice.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME)) {
            supported.add(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);
            if (support.presentId) {
                supported.add(VK_KHR_PRESENT_ID_EXTENSION_NAME);
            }
        }
        if (support.xess) {
            supported.add(EXTMutableDescriptorType.VK_EXT_MUTABLE_DESCRIPTOR_TYPE_EXTENSION_NAME);
        }
        return supported;
    }

    private static boolean ommRequested() {
        return CausticaConfig.Rt.Omm.ENABLED.value();
    }

    /**
     * Reflex's features change no other behavior, so they are NOT gated on the reflex.enabled
     * config — requesting VK_NV_low_latency2 (+ present_id) up front whenever the device supports
     * them is what lets the Video Settings toggle engage Reflex LIVE, without a restart. Device
     * extensions are a vkCreateDevice-time-only decision: gating the request on the runtime toggle
     * (default off) meant the extension was never requested, and the toggle could never turn on —
     * the "not enabled on this device" grey button. Same pattern as XeSS below.
     */
    private static boolean reflexRequested() {
        return true;
    }

    /**
     * XeSS's features change no other behavior, so they are NOT gated on the xess.enabled config —
     * enabling them up front is what lets the upscaler be flipped on live from Video Settings
     * without a restart (a vkCreateDevice-time-only decision otherwise). The gates are platform
     * (bundled Windows runtime) + device support, queried below.
     */
    private static boolean xessRequested() {
        return XessRuntime.platformSupported();
    }

    /** Query every feature boolean Caustica might enable in one complete Features2 chain. */
    private static FeatureSupport queryFeatureSupport(VulkanPhysicalDevice physicalDevice) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceFeatures2 available = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            for (VulkanFeature feature : REQUIRED_RT_FEATURES) {
                feature.struct().findOrCreateStructInPNextChain(available, stack);
            }

            boolean hasSerExt = physicalDevice.hasDeviceExtension(
                    VK_EXT_RAY_TRACING_INVOCATION_REORDER_EXTENSION_NAME);
            if (hasSerExt) {
                SER_EXT_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            }

            boolean queryOmm = ommRequested()
                    && physicalDevice.hasDeviceExtension(VK_EXT_OPACITY_MICROMAP_EXTENSION_NAME);
            if (queryOmm) {
                OMM_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            }
            boolean queryPresentId = reflexRequested()
                    && physicalDevice.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME)
                    && physicalDevice.hasDeviceExtension(VK_KHR_PRESENT_ID_EXTENSION_NAME);
            if (queryPresentId) {
                PRESENT_ID_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            }
            boolean queryXess = xessRequested()
                    && physicalDevice.hasDeviceExtension(
                            EXTMutableDescriptorType.VK_EXT_MUTABLE_DESCRIPTOR_TYPE_EXTENSION_NAME);
            if (queryXess) {
                // findOrCreate dedups each struct by sType (the VK10/VK12 ones merge into the
                // chain's existing structs; EXT + VK13 are created fresh when missing).
                SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
                MUTABLE_DESCRIPTOR_TYPE_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
                SHADER_INT8_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
                SHADER_INTEGER_DOT_PRODUCT_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);
            }
            WIDE_LINES_FEATURE.struct().findOrCreateStructInPNextChain(available, stack);

            VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), available);

            List<String> missing = new ArrayList<>();
            for (VulkanFeature feature : REQUIRED_RT_FEATURES) {
                if (!feature.get(available)) {
                    missing.add(feature.name());
                }
            }
            SerBackend supportedSer = hasSerExt && SER_EXT_FEATURE.get(available) ? SerBackend.EXT
                    : SerBackend.NONE;
            boolean xessRequired = queryXess
                    && SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT_FEATURE.get(available)
                    && MUTABLE_DESCRIPTOR_TYPE_FEATURE.get(available);
            return new FeatureSupport(missing, supportedSer,
                    queryOmm && OMM_FEATURE.get(available),
                    queryPresentId && PRESENT_ID_FEATURE.get(available),
                    WIDE_LINES_FEATURE.get(available),
                    xessRequired,
                    xessRequired && SHADER_INT8_FEATURE.get(available),
                    xessRequired && SHADER_INTEGER_DOT_PRODUCT_FEATURE.get(available));
        }
    }

    private static String firstUnsupportedExtension(VulkanPhysicalDevice physicalDevice) {
        for (String ext : RT_EXTENSIONS) {
            if (!physicalDevice.hasDeviceExtension(ext)) {
                return ext;
            }
        }
        return null;
    }

    /** Standalone path: add RT extension names to the (mutable) arg0 list. */
    public static void addExtensions(Collection<String> augmentedExtensions, VulkanPhysicalDevice physicalDevice) {
        captureDeviceIdentity(physicalDevice);
        if (!enabledByProperty() || firstUnsupportedExtension(physicalDevice) != null) {
            return;
        }
        FeatureSupport support = queryFeatureSupport(physicalDevice);
        if (!support.supportsRt()) {
            return;
        }
        for (String ext : RT_EXTENSIONS) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
        String serExtension = support.serBackend.extensionName;
        if (serExtension != null && !augmentedExtensions.contains(serExtension)) {
            augmentedExtensions.add(serExtension);
        }
        for (String ext : supportedOptionalExtensions(physicalDevice, support)) {
            if (!augmentedExtensions.contains(ext)) {
                augmentedExtensions.add(ext);
            }
        }
    }

    /** Add the RT VulkanFeatures to the feature set after the matching extension names have been requested. */
    public static void addFeatures(Set<VulkanFeature> features, VulkanPhysicalDevice physicalDevice) {
        if (!enabledByProperty()) {
            return;
        }
        rtRequested = false;
        serBackend = SerBackend.NONE;
        ommEnabled = false;
        reflexEnabled = false;
        presentIdEnabled = false;
        wideLinesEnabled = false;
        xessFeaturesEnabled = false;
        maxLineWidth = 1.0f;
        String missingExtension = firstUnsupportedExtension(physicalDevice);
        if (missingExtension != null) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                CausticaMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks extension {}",
                        physicalDevice.deviceName(), missingExtension);
            }
            return;
        }

        FeatureSupport support = queryFeatureSupport(physicalDevice);
        if (!support.supportsRt()) {
            if (!loggedUnavailable) {
                loggedUnavailable = true;
                CausticaMod.LOGGER.warn("Ray tracing unavailable: device [{}] lacks features {}",
                        physicalDevice.deviceName(), support.missingRequired);
            }
            return;
        }

        // Core features merge into vanilla's VK10/VK12 structs; extension features create their matching
        // pNext structs. Every boolean here was verified by queryFeatureSupport above.
        features.addAll(REQUIRED_RT_FEATURES);
        // Bindless entity textures: a runtime-sized sampler2D[] indexed non-uniformly in the hit shader,
        // with partially-bound + update-after-bind slots (a growing per-RenderType registry). Core on the
        // VK 1.4 device; just needs enabling alongside bufferDeviceAddress on the same struct.
        if (support.serBackend == SerBackend.EXT) {
            features.add(SER_EXT_FEATURE);
        }

        // Optional: wideLines (core VK10 feature, no extension). Lets the world-overlay pass (block
        // outline) draw a real thick native line via a raster pipeline's lineWidth / VK_DYNAMIC_STATE_LINE
        // _WIDTH instead of a screen-space quad. Its absence must not disable RT — the overlay falls back
        // to whatever the device's mandated lineWidth (1.0) allows.
        wideLinesEnabled = support.wideLines;
        if (wideLinesEnabled) {
            features.add(WIDE_LINES_FEATURE);
            maxLineWidth = physicalDevice.vkPhysicalDeviceProperties().limits().lineWidthRange(1);
        }

        // World-overlay MSAA (block outline edge AA): a framebuffer property, not a feature — no
        // VulkanFeature entry needed, just cap the desired 4x down to what the device actually supports.
        int colorSampleCounts = physicalDevice.vkPhysicalDeviceProperties().limits().framebufferColorSampleCounts();
        if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_4_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_4_BIT;
        } else if ((colorSampleCounts & VK10.VK_SAMPLE_COUNT_2_BIT) != 0) {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_2_BIT;
        } else {
            overlayMsaaSamples = VK10.VK_SAMPLE_COUNT_1_BIT;
        }

        // Optional: opacity micromaps (any-hit opt). Only when the gate is on AND the device advertises the
        // extension — its absence must not disable RT, so it is kept out of the mandatory feature set above.
        ommEnabled = support.omm;
        if (ommEnabled) {
            features.add(OMM_FEATURE);
        }

        // Optional: NVIDIA Reflex (VK_NV_low_latency2). Function-only extension, no feature struct to add.
        reflexEnabled = reflexRequested() && physicalDevice.hasDeviceExtension(VK_NV_LOW_LATENCY_2_EXTENSION_NAME);

        // Optional: VK_KHR_present_id (presentID<->present correlation for Reflex markers). Its absence must
        // not disable Reflex sleep/pacing itself — only marker correlation degrades.
        presentIdEnabled = reflexEnabled && support.presentId;
        if (presentIdEnabled) {
            features.add(PRESENT_ID_FEATURE);
        }

        // Optional: Intel XeSS device features (XeSS-SR guide: required = format-less storage-image
        // writes + mutable descriptors; performance-only = shaderInt8 + shaderIntegerDotProduct, the
        // quantized INT8 network's DP4a path). Only when the bundled runtime's platform matches AND
        // the required pair is supported — their absence must not disable RT, and XeSS simply stays
        // out of the upscaler selector instead. The DP4a pair rides along per-feature so a device
        // with the required pair but no dot-product still gets a (slower) XeSS.
        xessFeaturesEnabled = support.xess;
        if (xessFeaturesEnabled) {
            features.add(SHADER_STORAGE_IMAGE_WRITE_WITHOUT_FORMAT_FEATURE);
            features.add(MUTABLE_DESCRIPTOR_TYPE_FEATURE);
            if (support.xessInt8) {
                features.add(SHADER_INT8_FEATURE);
            }
            if (support.xessIntDot) {
                features.add(SHADER_INTEGER_DOT_PRODUCT_FEATURE);
            }
        }

        rtRequested = true;
        serBackend = support.serBackend;
        List<String> optionalExtensions = supportedOptionalExtensions(physicalDevice, support);
        CausticaMod.LOGGER.info(
                "Ray tracing: enabling {}{}{} + features [bufferDeviceAddress, accelerationStructure, rayTracingPipeline, rayQuery, SER={}"
                        + (wideLinesEnabled ? ", wideLines(max=" + maxLineWidth + ")" : "")
                        + (ommEnabled ? ", opacityMicromap" : "")
                        + (xessFeaturesEnabled ? ", xess(mutableDesc,storageImgWrite"
                                + (support.xessInt8 ? ",int8" : "")
                                + (support.xessIntDot ? ",dp4a" : "") + ")" : "")
                        + "] + overlayMsaa=" + overlayMsaaSamples + "x on [{}]",
                RT_EXTENSIONS, serBackend.extensionName == null ? "" : " + " + serBackend.extensionName,
                optionalExtensions.isEmpty() ? "" : " + " + optionalExtensions,
                serBackend.label, physicalDevice.deviceName());
    }

    /** Records the selected device's name/vendor so capability hints work before any feature init. */
    private static void captureDeviceIdentity(VulkanPhysicalDevice physicalDevice) {
        try {
            gpuName = physicalDevice.deviceName();
            gpuVendorName = physicalDevice.vendorName();
        } catch (Throwable t) {
            // Identity capture is cosmetic (UI gating hints only); never let it break device creation.
            gpuName = "";
            gpuVendorName = "";
        }
    }

    /** {@code VkPhysicalDeviceProperties.deviceName} of the device in use ("" before device creation). */
    public static String gpuName() {
        return gpuName;
    }

    /**
     * Best-effort, NAME-BASED guess at DLSS Frame Generation hardware support, for the options screen's
     * FG gate before NGX is initialized (NGX init happens with the first DLSS render, not on the main
     * menu, and initializing it purely for a UI hint would be invasive). The authoritative answer is the
     * driver's own NGX capability query ({@code NVSDK_NGX_Parameter_FrameGeneration_Available}, see
     * {@code ngxshim_dlssg_available}); {@code RtUpscalerSupport} prefers it whenever NGX is up and only
     * falls back to this heuristic.
     *
     * <p>NVIDIA restricts DLSSG to GeForce RTX 40/50 series, so the heuristic matches exactly that: an
     * NVIDIA device whose reported name contains an RTX 40xx/50xx model token. Anything unrecognized —
     * including professional RTX cards and every non-NVIDIA device — is treated as NOT supported, per the
     * safe default for a name-based guess.
     */
    public static boolean looksLikeRtxFrameGenerationSeries() {
        if (!"NVIDIA".equalsIgnoreCase(gpuVendorName)) {
            return false;
        }
        // "NVIDIA GeForce RTX 4090", "... RTX 5070 Ti", "NVIDIA RTX 5880 Ada Generation", ...
        return gpuName.toUpperCase(Locale.ROOT).matches(".*\\bRTX\\s*(?:PRO\\s*)?[45]\\d{3}\\b.*");
    }

    /**
     * Post-creation verification: confirm the RT entry points actually loaded on the new
     * device and log the RT pipeline / acceleration-structure limits. If this logs "OK",
     * the device truly came up RT-capable.
     */
    public static void probe(VkDevice device) {
        if (!rtRequested) {
            CausticaMod.LOGGER.info("Ray tracing not requested; skipping RT probe");
            maxOpacity4StateSubdivisionLevel = 0;
            return;
        }
        try {
            VKCapabilitiesDevice caps = device.getCapabilities();
            boolean rtPipeline = caps.vkCreateRayTracingPipelinesKHR != 0L;
            boolean asBuild = caps.vkCmdBuildAccelerationStructuresKHR != 0L;
            boolean traceRays = caps.vkCmdTraceRaysKHR != 0L;
            if (!(rtPipeline && asBuild && traceRays)) {
                CausticaMod.LOGGER.error(
                        "RT extensions enabled but entry points missing (rtPipeline={}, asBuild={}, traceRays={}) — RT bring-up FAILED",
                        rtPipeline, asBuild, traceRays);
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps =
                        VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
                VkPhysicalDeviceRayTracingPipelinePropertiesKHR rtProps =
                        VkPhysicalDeviceRayTracingPipelinePropertiesKHR.calloc(stack).sType$Default();
                rtProps.pNext(asProps.address());
                // Chain the OMM properties only when the feature is enabled (else the driver would ignore an
                // unrecognized struct, but keeping the chain clean matches the enabled feature set).
                VkPhysicalDeviceOpacityMicromapPropertiesEXT ommProps = null;
                if (ommEnabled) {
                    ommProps = VkPhysicalDeviceOpacityMicromapPropertiesEXT.calloc(stack).sType$Default();
                    asProps.pNext(ommProps.address());
                }
                VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
                props2.pNext(rtProps.address());
                VK12.vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props2);

                CausticaMod.LOGGER.info(
                        "RT bring-up OK — shaderGroupHandleSize={}, shaderGroupBaseAlignment={}, maxRayRecursionDepth={}; "
                                + "maxAS geometry/instance/primitive = {}/{}/{}",
                        rtProps.shaderGroupHandleSize(), rtProps.shaderGroupBaseAlignment(), rtProps.maxRayRecursionDepth(),
                        asProps.maxGeometryCount(), asProps.maxInstanceCount(), asProps.maxPrimitiveCount());
                if (ommProps != null) {
                    maxOpacity4StateSubdivisionLevel = ommProps.maxOpacity4StateSubdivisionLevel();
                    CausticaMod.LOGGER.info(
                            "Opacity micromaps enabled — maxSubdivisionLevel 4-state={}, 2-state={}",
                            ommProps.maxOpacity4StateSubdivisionLevel(), ommProps.maxOpacity2StateSubdivisionLevel());
                } else {
                    maxOpacity4StateSubdivisionLevel = 0;
                }
            }
            if (reflexEnabled) {
                boolean sleepMode = caps.vkSetLatencySleepModeNV != 0L;
                boolean sleep = caps.vkLatencySleepNV != 0L;
                boolean marker = caps.vkSetLatencyMarkerNV != 0L;
                boolean timings = caps.vkGetLatencyTimingsNV != 0L;
                if (sleepMode && sleep && marker && timings) {
                    CausticaMod.LOGGER.info(
                            "Reflex (VK_NV_low_latency2) entry points loaded — presentId={}", presentIdEnabled);
                } else {
                    CausticaMod.LOGGER.error(
                            "Reflex extension enabled but entry points missing (sleepMode={}, sleep={}, marker={}, timings={})",
                            sleepMode, sleep, marker, timings);
                    reflexEnabled = false;
                }
            }
        } catch (Throwable t) {
            // A probe must never break device creation.
            CausticaMod.LOGGER.error("RT probe threw; continuing without RT", t);
        }
    }
}
