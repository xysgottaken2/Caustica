package dev.comfyfluffy.caustica.mixin;

import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtFramePresenter;
import dev.comfyfluffy.caustica.rt.RtHdr;
import dev.comfyfluffy.caustica.rt.RtReflex;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPresentIdKHR;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSwapchainLatencyCreateInfoNV;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.LongBuffer;

/**
 * HDR Phase 0 capability logging + PQ swapchain selection.
 *
 * <p>The {@link VulkanGpuSurface} constructor holds both the live {@code VkSurfaceKHR} and the physical
 * device, so we enumerate the surface's formats/color spaces there (once) for diagnostics.
 *
 * <p>When {@code caustica.rt.hdr.pqSwapchain} is on and the surface advertises HDR10_ST2084 (ST.2084/PQ,
 * paired with whatever pixel format the surface offers for it — commonly a 10-bit UNORM, but this is
 * discovered by scanning the surface's advertised formats rather than assumed), we steer Minecraft's
 * swapchain to it: vanilla {@code pickSwapchainSurfaceFormat} only accepts SDR (color space 0, format 37/44)
 * and {@code configure} hardcodes {@code imageColorSpace(0)}. We override the picked format and the
 * color-space arg. Falls back to the vanilla SDR path when the flag is off or PQ is unavailable; default off.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {
	private static final int VK_COLOR_SPACE_HDR10_ST2084_EXT = 1000104008;

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private long surface;

	@Shadow
	@Final
	private org.lwjgl.vulkan.VkQueue presentQueue;

	@Shadow
	private long swapchain;

	@Shadow
	@Final
	private int swapchainImageFormat;

	@Shadow
	@Final
	private LongList swapchainImages;

	@Shadow
	private int currentImageIndex;

	@Shadow
	private int swapchainWidth;

	@Shadow
	private int swapchainHeight;

	@Shadow
	@Final
	private long[] acquireSemaphores;

	@Shadow
	private int currentAcquireSemaphore;

	@Shadow
	private long[] presentSemaphores;

	@Unique
	private int caustica$colorSpace = 0;

	@Inject(method = "<init>(Lcom/mojang/renderpearl/backend/vulkan/VulkanDevice;J)V", at = @At("TAIL"))
	private void caustica$logHdrCapabilities(VulkanDevice device, long windowHandle, CallbackInfo ci) {
		try {
			RtHdr.logSurfaceCapabilities(this.device.vkDevice().getPhysicalDevice(), this.surface, this.swapchainImageFormat);
		} catch (Throwable t) {
			// Diagnostics only — never let HDR logging break surface creation.
		}
	}

	/**
	 * Pick a PQ (HDR10_ST2084) surface format when requested + available, before vanilla's SDR-only selection
	 * runs. Scans for any format the surface pairs with that color space rather than assuming a specific one
	 * (IHVs commonly pair it with a 10-bit UNORM like A2R10G10B10, but this must not be hardcoded). Sets
	 * {@link #caustica$colorSpace} so {@code configure} can pass the matching color space.
	 */
	@Inject(method = "pickSwapchainSurfaceFormat", at = @At("HEAD"), cancellable = true)
	private void caustica$pickPqFormat(VkSurfaceFormatKHR.Buffer formats, CallbackInfoReturnable<VkSurfaceFormatKHR> cir) {
		if (!CausticaConfig.Rt.Hdr.enabled()) {
			return;
		}
		for (int i = 0; i < formats.capacity(); i++) {
			VkSurfaceFormatKHR f = formats.get(i);
			if (f.colorSpace() == VK_COLOR_SPACE_HDR10_ST2084_EXT) {
				this.caustica$colorSpace = VK_COLOR_SPACE_HDR10_ST2084_EXT;
				CausticaMod.LOGGER.info("HDR: selecting PQ swapchain (format={}, colorSpace=HDR10_ST2084)", f.format());
				cir.setReturnValue(f);
				return;
			}
		}
		CausticaMod.LOGGER.warn("HDR: PQ swapchain requested but HDR10_ST2084 was not advertised by the surface; "
				+ "using SDR (enable OS/display HDR; on Linux use a native Wayland session with HDR enabled in the compositor)");
	}

	/** Replace the hardcoded {@code imageColorSpace(0)} with the PQ color space when one was selected. */
	@ModifyArg(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;imageColorSpace(I)Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;"),
			index = 0)
	private int caustica$overrideColorSpace(int original) {
		return this.caustica$colorSpace != 0 ? this.caustica$colorSpace : original;
	}

	/**
	 * Reflex Phase 1a: chain {@code VkSwapchainLatencyCreateInfoNV{latencyModeEnable=true}} into the
	 * swapchain's pNext at creation. Per spec {@code vkSetLatencySleepModeNV} (not called yet — lands with
	 * the sleep loop) only takes effect on a swapchain created with this flag, so it has to be set here,
	 * before there's any other reason to touch swapchain creation. Preserves whatever pNext was already
	 * there (currently nothing else chains one). The extra struct is stack-allocated and only needs to
	 * survive this call — Vulkan reads pNext chains synchronously during {@code vkCreateSwapchainKHR}, it
	 * doesn't retain the pointer afterward, so freeing it when this method's stack frame pops is safe even
	 * though {@code pCreateInfo} isn't touched again after this point in {@code configure()}. No-op (calls
	 * through unchanged) when Reflex isn't enabled + device-supported.
	 *
	 * <p>Also makes every RT swapchain Frame-Generation-ready, unconditionally: (1) forces FIFO
	 * present mode — with IMMEDIATE/MAILBOX the end-of-frame burst of generated-frame presents
	 * overwrites itself before scanout (flicker, zero FPS gain); (2) bumps {@code minImageCount} —
	 * every generated frame needs its own image on top of the real frame's and the one on display,
	 * and vanilla's pool (~3) hard-capped FG at ~2x regardless of multiplier. Target covers the max
	 * multiplier (5 generated = 6x) + real + display + one spare. Both are creation-time properties,
	 * and FG is toggled at runtime, so they are applied on EVERY swapchain (not gated on the FG
	 * toggle) — otherwise flipping FG on after load left the pool at 3 images and FG capped at 2x.
	 */
	@Redirect(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int caustica$createSwapchainWithReflex(VkDevice device, VkSwapchainCreateInfoKHR pCreateInfo,
			VkAllocationCallbacks pAllocator, LongBuffer pSwapchain) {
		caustica$configureSwapchainForFg(pCreateInfo);
		if (!RtDeviceBringup.reflexEnabled()) {
			return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSwapchainLatencyCreateInfoNV latency = VkSwapchainLatencyCreateInfoNV.calloc(stack).sType$Default();
			latency.pNext(pCreateInfo.pNext());
			latency.latencyModeEnable(true);
			pCreateInfo.pNext(latency.address());
			return KHRSwapchain.vkCreateSwapchainKHR(device, pCreateInfo, pAllocator, pSwapchain);
		}
	}

	/**
	 * The FG image-pool target: room for the DLSS MFG maximum (4 generated = 5x) + 1 real + 1 on
	 * display + spares. FSR 3.1 only ever generates 1 frame per real one, but the pool is sized for
	 * the best case of any backend so the same swapchain works whichever FG rides on it.
	 */
	@Unique
	private static final int caustica$FG_SWAPCHAIN_IMAGES = 8;

	@Unique
	private void caustica$configureSwapchainForFg(VkSwapchainCreateInfoKHR pCreateInfo) {
		// Applied UNCONDITIONALLY (not gated on the FG toggle): FG is flipped on/off at runtime from
		// the Video Settings screen, but present mode and image count are swapchain-CREATION-time
		// properties. If we only deepened the pool when FG was already enabled at creation, toggling
		// FG on after load left the swapchain at vanilla's ~3 images / IMMEDIATE mode and FG silently
		// capped at 2x no matter the multiplier (the reported "6x but FPS isn't smooth"). Making every
		// RT swapchain FG-ready means the toggle works live. Cost: a few extra swapchain images and
		// FIFO pacing — negligible for a path-traced renderer running well below the display rate.
		//
		// FIFO pacing: with IMMEDIATE/MAILBOX the end-of-frame BURST of generated-frame presents
		// overwrites each other before the display ever scans them out (flicker, no FPS gain). FIFO
		// (spec-mandated on every surface) gives every queued present its own vblank, which is what
		// frame generation needs to actually reach the display.
		if (pCreateInfo.presentMode() != KHRSurface.VK_PRESENT_MODE_FIFO_KHR) {
			CausticaMod.LOGGER.info("FG: forcing FIFO present mode (was {}) so generated frames are "
					+ "paced on vblanks instead of overwriting each other", pCreateInfo.presentMode());
			pCreateInfo.presentMode(KHRSurface.VK_PRESENT_MODE_FIFO_KHR);
		}
		// Deepen the image pool: each generated frame needs its own swapchain image on top of the
		// real frame's and the one the display scans out — vanilla's ~3-image pool hard-caps FG
		// at ~2x no matter the multiplier.
		if (pCreateInfo.minImageCount() < caustica$FG_SWAPCHAIN_IMAGES) {
			int target = caustica$FG_SWAPCHAIN_IMAGES;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR caps =
						org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR.calloc(stack);
				if (KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
						this.device.vkDevice().getPhysicalDevice(), this.surface, caps) == VK10.VK_SUCCESS
						&& caps.maxImageCount() > 0) {
					// maxImageCount == 0 means "no limit"; otherwise the driver's ceiling wins.
					target = Math.min(target, caps.maxImageCount());
				}
			}
			if (target > pCreateInfo.minImageCount()) {
				pCreateInfo.minImageCount(target);
				CausticaMod.LOGGER.info("FG: swapchain minImageCount raised to {} (generated frames need a "
						+ "deeper image pool than vanilla's default)", target);
			}
		}
	}

	/**
	 * Reflex Phase 1b: (re)apply the sleep-mode config for the just-(re)configured swapchain. Per spec this
	 * is scoped to a specific swapchain object, so it must be re-called whenever {@code configure()} builds a
	 * new one (e.g. resize) — {@link RtReflex#applySleepMode} is idempotent (no-op if unchanged), so calling
	 * it unconditionally here is cheap. No-op when Reflex isn't enabled + device-supported.
	 */
	@Inject(method = "configure", at = @At("TAIL"))
	private void caustica$applyReflexSleepMode(GpuSurface.Configuration config, CallbackInfo ci) {
		if (RtDeviceBringup.reflexEnabled()) {
			RtReflex.INSTANCE.applySleepMode(this.device.vkDevice(), this.swapchain);
		}
		// DLSS-FG diagnostic: MAILBOX/IMMEDIATE present modes let a later present silently replace/skip an
		// earlier queued-but-not-yet-scanned-out one, which would drop FG's generated frame before the
		// display ever shows it — even though our vkQueuePresentKHR call itself reports success. FIFO is the
		// only mode that guarantees every queued present gets its own vblank. Log once per (re)configure so
		// this is checkable without guessing at the in-game V-Sync setting.
		if (dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.enabled()) {
			CausticaMod.LOGGER.info("DLSS-FG: swapchain present mode = {} (FIFO required for generated frames "
					+ "to actually display; MAILBOX/IMMEDIATE will silently drop them — enable V-Sync if not FIFO)",
					config.presentMode());
		}
	}

	/**
	 * Reflex Phase 1b: PRESENT_START/END markers around the real frame's present, plus (when
	 * {@code VK_KHR_present_id} is enabled) chaining a {@code VkPresentIdKHR} onto it so the marker's
	 * {@code presentID} correlates with this exact present call. The FG-generated extra presents
	 * ({@link RtFramePresenter}) are deliberately NOT marked/present-id'd — Reflex paces/measures the real
	 * frame only. No-op passthrough unless Reflex has successfully applied sleep mode for this swapchain.
	 */
	@Redirect(method = "present",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int caustica$presentWithReflex(VkQueue queue, VkPresentInfoKHR presentInfo) {
		boolean reflexActive = RtReflex.enabled() && this.swapchain == RtReflex.INSTANCE.appliedSwapchain();
		if (!reflexActive) {
			return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
		}
		VkDevice vkDevice = this.device.vkDevice();
		// Own counter (not currentSimFrameId()): Minecraft can present outside the normal tick loop (e.g.
		// Minecraft.setScreenAndShow's synchronous redraw when opening a world), so presentID must advance on
		// every actual vkQueuePresentKHR call, not just once per sleep()/runTick — otherwise a stale, already-
		// used id gets resent and VUID-VkPresentIdKHR-presentIds-04999 fires.
		long presentId = RtReflex.INSTANCE.advancePresentId();
		RtReflex.INSTANCE.marker(vkDevice, this.swapchain, RtReflex.MARKER_RENDERSUBMIT_END, presentId);
		RtReflex.INSTANCE.marker(vkDevice, this.swapchain, RtReflex.MARKER_PRESENT_START, presentId);
		int result;
		if (RtDeviceBringup.presentIdEnabled()) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkPresentIdKHR vkPresentId = VkPresentIdKHR.calloc(stack).sType$Default()
						.pNext(presentInfo.pNext())
						.swapchainCount(1)
						.pPresentIds(stack.longs(presentId));
				presentInfo.pNext(vkPresentId.address());
				result = KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
			}
		} else {
			result = KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
		}
		RtReflex.INSTANCE.marker(vkDevice, this.swapchain, RtReflex.MARKER_PRESENT_END, presentId);
		return result;
	}

	/**
	 * Step C — world-only HDR present. When the RT renderer has a fresh PQ HDR image and the swapchain is
	 * PQ, blit that image straight into the swapchain instead of Minecraft's SDR main target. Replaces the
	 * vanilla blit entirely (the SDR target + its UI are bypassed for now; UI compositing is a later step).
	 *
	 * <p>Because this cancels {@code blitFromTexture} at HEAD, the normal {@code caustica$presentGeneratedFrames}
	 * TAIL inject below never runs on HDR frames — so DLSS-FG's extra-present step is invoked explicitly here,
	 * right after the real HDR frame is recorded, using the just-composited {@code hdrDisplayImage} (already
	 * UI-composited by {@code presentHdr}) as the interpolation source instead of the SDR main target.
	 */
	@Inject(method = "blitFromTexture", at = @At("HEAD"), cancellable = true)
	private void caustica$presentHdr(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0) {
			return;
		}
		RtComposite rt = RtComposite.INSTANCE;
		long swapchainImage = this.swapchainImages.getLong(this.currentImageIndex);
		long acquireSem = this.acquireSemaphores[this.currentAcquireSemaphore];
		long presentSem = this.presentSemaphores[this.currentImageIndex];
		if (rt.isHdrPresentActive()) {
			VulkanCommandEncoder enc = (VulkanCommandEncoder) commandEncoder;
			rt.presentHdr(enc, swapchainImage, this.swapchainWidth, this.swapchainHeight, acquireSem, presentSem);
			caustica$presentGeneratedFramesHdr(enc, rt);
			ci.cancel();
			return;
		}
		// Non-RT frame (menu, title panorama, loading screen) on a PQ swapchain: vanilla's raw SDR blit would
		// misdisplay (SDR bytes reinterpreted as PQ codes). Convert sRGB -> PQ at paper white instead. Falls
		// through to vanilla SDR if conversion resources aren't ready or the source view is not a Vulkan view.
		if (rt.isPqSdrPresentActive()) {
			long sdrView = caustica$vkImageView(textureView);
			if (sdrView != 0L && rt.presentSdrToPq((VulkanCommandEncoder) commandEncoder, swapchainImage,
					this.swapchainWidth, this.swapchainHeight, sdrView, acquireSem, presentSem)) {
				ci.cancel();
			}
		}
	}

	@Unique
	private static long caustica$vkImageView(GpuTextureView view) {
		return view instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView v ? v.vkImageView() : 0L;
	}

	/**
	 * DLSS Frame Generation (slice 2): after Minecraft blits the real frame into its acquired swapchain image
	 * (but before {@code present()} shows it), present the generated frame(s) into additional swapchain images
	 * via {@link RtFramePresenter}, so the display order is generated-then-real. Runs only on the normal
	 * present path — the HDR/PQ present hooks cancel {@code blitFromTexture} at HEAD, so this TAIL is
	 * skipped there (HDR+FG deferred). Iteration 1 duplicates the final frame (no DLSSG eval yet).
	 */
	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void caustica$presentGeneratedFrames(CommandEncoderBackend commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		if (this.currentImageIndex < 0 || !RtFramePresenter.INSTANCE.isActive()) {
			return;
		}
		long srcImage = textureView.texture() instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture t ? t.vkImage() : 0L;
		long srcView = caustica$vkImageView(textureView);
		if (srcImage == 0L) {
			return;
		}
		int generatedCount = dev.comfyfluffy.caustica.rt.RtComposite.fgGeneratedCount();
		RtFramePresenter.INSTANCE.prepareExtraFrames((VulkanCommandEncoder) commandEncoder, this.device,
				this.swapchain, this.swapchainImages, this.presentSemaphores,
				this.swapchainWidth, this.swapchainHeight,
				srcView, srcImage, textureView.getWidth(0), textureView.getHeight(0), generatedCount, false);
	}

	/**
	 * DLSS-FG on the HDR present path: same extra-present mechanism as {@link #caustica$presentGeneratedFrames},
	 * but sourced from the HDR backbuffer ({@link RtComposite#hdrBackbufferView()}/{@code hdrBackbufferImage()})
	 * since HDR frames never reach that TAIL inject (HEAD cancels {@code blitFromTexture} above). No-op if FG
	 * isn't active or the HDR backbuffer isn't available (shouldn't happen right after a successful
	 * {@code presentHdr} call, but mirrors the defensive {@code srcImage == 0L} check in the SDR path).
	 */
	@Unique
	private void caustica$presentGeneratedFramesHdr(VulkanCommandEncoder enc, RtComposite rt) {
		if (this.currentImageIndex < 0 || !RtFramePresenter.INSTANCE.isActive()) {
			return;
		}
		long hdrView = rt.hdrBackbufferView();
		long hdrImage = rt.hdrBackbufferImage();
		if (hdrImage == 0L) {
			return;
		}
		int generatedCount = dev.comfyfluffy.caustica.rt.RtComposite.fgGeneratedCount();
		RtFramePresenter.INSTANCE.prepareExtraFrames(enc, this.device, this.swapchain, this.swapchainImages,
				this.presentSemaphores, this.swapchainWidth, this.swapchainHeight,
				hdrView, hdrImage, this.swapchainWidth, this.swapchainHeight, generatedCount, true);
	}

	// Present the FG-generated frame(s) acquired/recorded at blitFromTexture TAIL — at present() HEAD, after
	// Minecraft.java's encoder.submit() has flushed (so our present semaphores are signaled) and before MC
	// presents the real frame, giving display order generated-then-real.
	@Inject(method = "present", at = @At("HEAD"))
	private void caustica$flushGeneratedPresents(CallbackInfo ci) {
		RtFramePresenter.INSTANCE.flushPendingPresents(this.swapchain, this.presentQueue);
	}
}
