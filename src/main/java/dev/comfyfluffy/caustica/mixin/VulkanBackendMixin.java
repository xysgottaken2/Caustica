package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanUtils;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Vulkan device-negotiation hook: adds the device extensions the Caustica runtime needs to the extension
 * list vanilla enables at vkCreateDevice time.
 *
 * <p>FFX resolves vkGetImageMemoryRequirements2KHR etc. through
 * vkGetDeviceProcAddr using the KHR-suffixed extension names; per Vulkan spec
 * that returns NULL unless the corresponding extension was enabled — even
 * though the functionality is core since 1.1 — and FFX then calls the NULL
 * pointer (verified: crash at amd_fidelityfx_vk.dll+0x1e5b0 building
 * VkMemoryRequirements2). Enabling the alias extensions is a behavioral no-op
 * for the rest of the engine.
 */
@Mixin(VulkanBackend.class)
public abstract class VulkanBackendMixin {
	private static final String OUTER_CREATE_DEVICE =
			"createDevice(Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)Lcom/mojang/renderpearl/api/device/GpuDevice;";
	private static final String INNER_CREATE_DEVICE =
			"createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;";

	// 26.3 VulkanFeature computes the member offset itself (name must name a VkBool32 member of the
	// struct), so only the struct + feature name are needed.
	private static final List<VulkanFeature> SDK_SHADER_FEATURES = List.of(
			new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat"),
			new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderInt16"),
			new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "shaderFloat16"));

	private static final List<String> CAUSTICA_WANTED_EXTENSIONS = List.of(
			// FFX (FSR)
			"VK_KHR_get_memory_requirements2",
			"VK_KHR_dedicated_allocation",
			// NGX (DLSS) — NVIDIA-only; skipped on other vendors. (The NGX instance
			// extension VK_KHR_get_physical_device_properties2 needs an instance hook;
			// DLSS relies on it being core/enabled at instance level.)
			"VK_NVX_binary_import",
			"VK_NVX_image_view_handle",
			"VK_KHR_push_descriptor");

	private static final Set<String> loggedMissingSdkFeatures = new HashSet<>();

	/**
	 * NVIDIA advertises AMD markers too, but its native diagnostic checkpoints contain better fault
	 * context. 26.3 picks the checkpoint backend by enabling at most one of the AMD/NV optional
	 * feature sets (the NV set's own condition already excludes it wherever AMD markers are
	 * supported), so force the NV set in and the AMD set out on NVIDIA hardware that advertises
	 * {@code VK_NV_device_diagnostic_checkpoints}.
	 */
	@WrapOperation(
			method = OUTER_CREATE_DEVICE,
			at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;checkCondition(Lorg/lwjgl/vulkan/VkPhysicalDevice;)Z"))
	private boolean caustica$preferNvidiaCheckpoints(FeatureSet featureSet, VkPhysicalDevice device,
			Operation<Boolean> original) throws BackendCreationException {
		if ((featureSet == VulkanFeatureSets.AMD_BUFFER_MARKER_FEATURESET
				|| featureSet == VulkanFeatureSets.NV_DIAGNOSTIC_CHECKPOINT_FEATURESET)
				&& caustica$isNvidiaWithNvCheckpoints(device)) {
			return featureSet == VulkanFeatureSets.NV_DIAGNOSTIC_CHECKPOINT_FEATURESET;
		}
		return original.call(featureSet, device);
	}

	private static boolean caustica$isNvidiaWithNvCheckpoints(VkPhysicalDevice device) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
			VK12.vkGetPhysicalDeviceProperties(device, properties);
			if (properties.vendorID() != 0x10DE) {
				return false;
			}
			return VulkanUtils.enumerateExtensions(device).contains("VK_NV_device_diagnostic_checkpoints");
		} catch (BackendCreationException e) {
			// Enumeration failure: fall back to vanilla's own set selection.
			return false;
		}
	}

	/**
	 * 26.3 merges vanilla's required + optional feature sets into one {@link FeatureSet} before the
	 * inner {@code createDevice} call; augment its extension/feature sets with Caustica's additions
	 * (each still individually support-checked) and composite them back in.
	 */
	@ModifyArgs(
			method = OUTER_CREATE_DEVICE,
			at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;" + INNER_CREATE_DEVICE))
	private void caustica$addDeviceExtensions(Args args) {
		VulkanPhysicalDevice physicalDevice = args.get(1);
		FeatureSet enabled = args.get(0);

		Set<String> extensions = new HashSet<>(enabled.extensions());
		for (String extension : CAUSTICA_WANTED_EXTENSIONS) {
			if (extensions.contains(extension)) {
				continue;
			}
			if (physicalDevice.hasDeviceExtension(extension)) {
				extensions.add(extension);
				CausticaMod.LOGGER.info("Enabling device extension {} for the Caustica runtime", extension);
			} else {
				CausticaMod.LOGGER.warn("Device extension {} not supported by {} — upscaling will be unavailable",
						extension, physicalDevice.deviceName());
			}
		}
		VulkanDiagnostics.addDeviceFaultExtension(extensions, physicalDevice);
		RtDeviceBringup.addExtensions(extensions, physicalDevice);

		Set<VulkanFeature> features = new HashSet<>(enabled.features());
		caustica$addCoreDeviceFeatures(features, physicalDevice);
		VulkanDiagnostics.addDeviceFaultFeature(features);
		RtDeviceBringup.addFeatures(features, physicalDevice);
		VulkanDiagnostics.logEnabledExtensions(extensions);

		args.set(0, enabled.composite(new FeatureSet("Caustica", extensions, features)));
	}

	private void caustica$addCoreDeviceFeatures(Set<VulkanFeature> features, VulkanPhysicalDevice physicalDevice) {
		for (VulkanFeature feature : SDK_SHADER_FEATURES) {
			if (!caustica$supportsFeature(physicalDevice, feature)) {
				if (loggedMissingSdkFeatures.add(feature.name())) {
					CausticaMod.LOGGER.warn("Device [{}] lacks {}; FSR/DLSS SDK shaders may fail validation",
							physicalDevice.deviceName(), feature.name());
				}
				continue;
			}

			if (features.add(feature)) {
				CausticaMod.LOGGER.info("Enabling Vulkan feature {} for FSR/DLSS SDK shaders", feature.name());
			}
		}
	}

	private static boolean caustica$supportsFeature(VulkanPhysicalDevice physicalDevice, VulkanFeature feature) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceFeatures2 deviceFeatures = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
			feature.struct().findOrCreateStructInPNextChain(deviceFeatures, stack);
			VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), deviceFeatures);
			return feature.get(deviceFeatures);
		}
	}

	/**
	 * P0 verification — once the RT-augmented device is created, confirm the RT entry
	 * points loaded and log the RT/AS limits. {@code device} is the local assigned just
	 * before {@code createVma} runs.
	 */
	@Inject(
			method = OUTER_CREATE_DEVICE,
			at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanBackend;createVma(Lorg/lwjgl/vulkan/VkDevice;)J"))
	private void caustica$probeRayTracing(GpuDebugOptions debugOptions,
			CallbackInfoReturnable<GpuDevice> cir, @Local VkDevice device) {
		VulkanDiagnostics.probe(device);
		RtDeviceBringup.probe(device);
	}

	@Inject(
			method = INNER_CREATE_DEVICE,
			at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
	private static void caustica$augmentDeviceCreateInfo(FeatureSet featureSet,
			VulkanPhysicalDevice physicalDevice,
			CallbackInfoReturnable<VkDevice> cir, @Local VkDeviceCreateInfo deviceCreateInfo) {
		RtDeviceBringup.reserveComputeQueue(deviceCreateInfo, physicalDevice, MemoryStack.stackGet());
		VulkanDiagnostics.attachNvDiagnosticsConfig(deviceCreateInfo, MemoryStack.stackGet());
	}
}
