package dev.comfyfluffy.caustica.rt;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.RtUpscalerSupport;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtFsrFrameGen;

import it.unimi.dsi.fastutil.longs.LongList;

import net.minecraft.client.Minecraft;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * DLSS Frame Generation present engine (slice 2). Shows more than one image per rendered frame: the
 * generated frame(s), then the real frame.
 *
 * <p>It hooks Minecraft's frame tail (Minecraft.java: {@code blitFromTexture} → {@code encoder.submit()} →
 * {@code present()}). At {@code blitFromTexture} TAIL it acquires extra swapchain image(s) and records a
 * Y-flipped blit into <em>Minecraft's own command encoder</em> (the persistent singleton), so MC's
 * once-per-frame {@code submit()} flushes our work in the same {@code vkQueueSubmit} that signals the real
 * frame — this is what makes our present semaphores actually get signaled (the deferred-submit model is why
 * a self-contained present here failed validation). Then at {@code present()} HEAD we present the extra
 * image(s) before MC presents the real one, giving display order generated-then-real.
 *
 * <p>The generated frame is {@link RtDlssFg}'s real DLSSG-interpolated output (via
 * {@link RtComposite#fgInterpolate}); when there's simply no captured RT frame this tick (menu/loading/
 * transition — routine) it falls back to duplicating the real frame for just that one frame (see
 * {@code interpFallbackDuplicate} in the present-rate log), but a genuine FG failure is fatal (see that
 * method's docs) rather than silently duplicating forever. Called from both present paths — the normal SDR
 * {@code blitFromTexture} TAIL, and (via {@code hdrBackbuffer=true}) explicitly from inside the HDR present
 * hook, since the HDR/PQ path cancels {@code blitFromTexture} at HEAD and never reaches the TAIL inject.
 * Gated by {@code caustica.rt.fg} (default off).
 */
public final class RtFramePresenter {
    public static final RtFramePresenter INSTANCE = new RtFramePresenter();

    private static final long LOG_INTERVAL_NS = 1_000_000_000L;

    private long[] acquireSemaphores = new long[0];
    private int acquireCursor;
    private boolean failed;

    // Frames acquired + recorded this frame, awaiting present at present() HEAD (after MC's submit flush).
    private int[] pendingImageIndex = new int[0];
    private long[] pendingPresentSem = new long[0];
    private int pendingCount;

    // Present-rate diagnostics: real vs generated vkQueuePresentKHR calls per second, independent of MC's own
    // fps counter (which only counts rendered/simulated frames, not our extra presents).
    private long logWindowStartNs;
    private int realFramesInWindow;
    private int generatedFramesInWindow;
    private int interpOkInWindow;
    private int interpFallbackInWindow;
    private int acquireSkippedInWindow;
    private int loggedImageCount = -1;

    private RtFramePresenter() {
    }

    /**
     * Whether FG extra-present should run this frame (enabled, available, in a world). Backend
     * selection mirrors {@code RtComposite.fgInterpolate}: the Caustica native engine first (FSR 3
     * / XeSS default, and the DLSS-path fallback on GPUs without DLSS-G); then the FSR 3.1 runtime
     * fallback (needs the bundled runtime); then DLSS-G on capable hardware.
     */
    public boolean isActive() {
        if (failed || Minecraft.getInstance().level == null) {
            return false;
        }
        if (dev.comfyfluffy.caustica.rt.pipeline.RtNativeFrameGen.enabled()) {
            return true; // no external runtime, no hardware gate
        }
        String mode = RtUpscalerSupport.currentUpscalerMode();
        if (RtUpscalerSupport.MODE_FSR3.equals(mode) || RtUpscalerSupport.MODE_XESS.equals(mode)) {
            return RtFsrFrameGen.enabled()
                    && dev.comfyfluffy.caustica.fsr.FsrRuntime.platformSupported();
        }
        return RtDlssFg.enabled() && RtDlssFg.INSTANCE.isAvailable();
    }

    /**
     * Acquire {@code generatedCount} extra swapchain images and record a Y-flipped blit of {@code srcImage}
     * (the final rendered frame, GENERAL layout) into each, using Minecraft's command encoder {@code enc} so
     * the work rides MC's next {@code submit()}. The presents happen later in {@link #flushPendingPresents}.
     * Blits DLSSG's real interpolated output per generated frame, or a duplicate of the real frame when RT
     * simply isn't producing frames this tick (routine). A genuine DLSSG failure latches FG off for the
     * session — see {@link RtComposite#fgInterpolate}.
     *
     * @param hdrBackbuffer whether {@code backbufferView}/{@code srcImage} is the PQ HDR backbuffer
     *     ({@link RtComposite#hdrBackbufferView()}, already UI-composited) rather than the SDR main target —
     *     selects DLSSG's HDR backbuffer format/flag in {@link RtComposite#fgInterpolate}.
     */
    public void prepareExtraFrames(VulkanCommandEncoder enc, VulkanDevice device, long swapchain,
            LongList swapchainImages, long[] presentSemaphores, int swapW, int swapH,
            long backbufferView, long srcImage, int srcW, int srcH, int generatedCount, boolean hdrBackbuffer) {
        pendingCount = 0;
        if (failed || swapchain == 0L || srcImage == 0L || generatedCount <= 0) {
            return;
        }
        if (swapchainImages.size() != loggedImageCount) {
            loggedImageCount = swapchainImages.size();
            CausticaMod.LOGGER.info("FG: swapchain has {} images (each generated frame needs one per "
                    + "real frame on top of the real one)", loggedImageCount);
        }
        try {
            // Never ask for more extra images than the swapchain can lend: Minecraft needs one image
            // for the real frame itself, so the pool caps the generated count. Requesting beyond it
            // made vkAcquireNextImageKHR sit until timeout and froze the frame loop (the 0.2 FPS
            // slideshow at high multipliers).
            int want = Math.min(generatedCount, Math.max(0, swapchainImages.size() - 1));
            ensureCapacity(device, swapchainImages.size() + 1, want);
            for (int i = 0; i < want; i++) {
                // null = no captured RT frame this tick (menu/loading/transition — routine, not a bug): fall
                // back to duplicating the real frame for just this one frame. A genuine FG failure instead
                // throws, caught below, which disables FG for the session.
                RtImage interp = RtComposite.INSTANCE.fgInterpolate(enc, backbufferView, srcImage,
                        swapW, swapH, i + 1, generatedCount, hdrBackbuffer);
                if (interp != null) {
                    interpOkInWindow++;
                } else {
                    interpFallbackInWindow++;
                }
                long blitSrc = interp != null ? interp.image : srcImage;
                int copyW = Math.min(swapW, interp != null ? interp.width : srcW);
                int copyH = Math.min(swapH, interp != null ? interp.height : srcH);

                long acquireSem = acquireSemaphores[acquireCursor];
                acquireCursor = (acquireCursor + 1) % acquireSemaphores.length;

                int imageIndex;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer pIndex = stack.callocInt(1);
                    // BOUNDED 1ms acquire: images released by the presentation engine can lag a
                    // hair behind the frame tail, and a timeout-0 acquire missed them systemically
                    // (the 2x ceiling — acquireSkipped == real in the present-rate log). 1ms per
                    // acquire keeps the worst-case stall at ~4ms, nowhere near the frame budget;
                    // the old unbounded wait (pre-diagnostic) is what froze the loop, not a short
                    // bounded one. Still non-fatal: on miss, present what we already recorded.
                    int r = KHRSwapchain.vkAcquireNextImageKHR(device.vkDevice(), swapchain, 1_000_000L, acquireSem, 0L, pIndex);
                    if (r != VK10.VK_SUCCESS && r != 1000001003 /* SUBOPTIMAL */) {
                        acquireSkippedInWindow++;
                        break; // present what we already recorded this frame
                    }
                    imageIndex = pIndex.get(0);
                }
                long dstImage = swapchainImages.getLong(imageIndex);
                long presentSem = presentSemaphores[imageIndex];
                recordBlit(enc, blitSrc, dstImage, copyW, copyH, acquireSem, presentSem);

                pendingImageIndex[pendingCount] = imageIndex;
                pendingPresentSem[pendingCount] = presentSem;
                pendingCount++;
            }
        } catch (Throwable t) {
            failed = true;
            pendingCount = 0;
            CausticaMod.LOGGER.error("DLSS-FG present-record failed; frame generation disabled", t);
        }
    }

    /**
     * Present the frames acquired in {@link #prepareExtraFrames} (call at {@code present()} HEAD, after MC's
     * {@code submit()} has flushed — so the present semaphores are signaled — and before MC presents the real
     * frame, giving generated-then-real order).
     */
    public void flushPendingPresents(long swapchain, VkQueue presentQueue) {
        int presentedThisFrame = 0;
        if (!failed && pendingCount != 0) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                for (int i = 0; i < pendingCount; i++) {
                    VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
                    present.pWaitSemaphores(stack.longs(pendingPresentSem[i]));
                    present.swapchainCount(1);
                    present.pSwapchains(stack.longs(swapchain));
                    present.pImageIndices(stack.ints(pendingImageIndex[i]));
                    KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
                    presentedThisFrame++;
                }
            } catch (Throwable t) {
                failed = true;
                CausticaMod.LOGGER.error("DLSS-FG present failed; frame generation disabled", t);
            } finally {
                pendingCount = 0;
            }
        }
        // Diagnostic only fires while FG is ACTUALLY presenting this session: with the FG toggle
        // on but the active backend unavailable (e.g. DLSS-G on a non-40/50 card), the old gate
        // (config toggle) kept logging "gen=0" lines that looked like a stuck engine.
        if (isActive()) {
            logPresentRate(presentedThisFrame);
        }
    }

    /**
     * Debug diagnostic (2026-07-01): tracks real vs generated {@code vkQueuePresentKHR} calls per second,
     * separate from MC's own fps counter — that counter only reflects simulated/rendered frames
     * (blitFromTexture calls), so it would NOT show an increase from FG's extra presents even if the display
     * is genuinely receiving more frames. Logged only when {@code caustica.rt.fg} is enabled.
     */
    private void logPresentRate(int generatedThisFrame) {
        realFramesInWindow++;
        generatedFramesInWindow += generatedThisFrame;
        long now = System.nanoTime();
        if (logWindowStartNs == 0L) {
            logWindowStartNs = now;
            return;
        }
        long elapsed = now - logWindowStartNs;
        if (elapsed < LOG_INTERVAL_NS) {
            return;
        }
        double seconds = elapsed / 1.0e9;
        double realFps = realFramesInWindow / seconds;
        double totalFps = (realFramesInWindow + generatedFramesInWindow) / seconds;
        CausticaMod.LOGGER.info(
                "[FG present-rate] real={} gen={} realFps={} totalPresentFps={} configuredMultiFrameCount={} "
                        + "interpOk={} interpFallbackDuplicate={} acquireSkipped={}",
                realFramesInWindow, generatedFramesInWindow,
                String.format("%.1f", realFps), String.format("%.1f", totalFps),
                RtComposite.fgGeneratedCount(), interpOkInWindow, interpFallbackInWindow,
                acquireSkippedInWindow);
        logWindowStartNs = now;
        realFramesInWindow = 0;
        generatedFramesInWindow = 0;
        interpOkInWindow = 0;
        interpFallbackInWindow = 0;
        acquireSkippedInWindow = 0;
    }

    private void recordBlit(VulkanCommandEncoder enc, long srcImage, long dstImage, int copyW, int copyH,
            long acquireSem, long presentSem) {
        VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Swapchain UNDEFINED -> TRANSFER_DST (stage/access values mirror MC's blitFromTexture).
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            // Make the source's prior writes visible to the blit read — the world render (duplicate path) or
            // the DLSSG evaluate that wrote the interp image earlier in this same submit (interp path).
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit final frame (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg blit) failed");
        }
        // Register on MC's encoder (same order as MC's blitFromTexture): wait on the acquire, run the blit,
        // signal the image's present semaphore. MC's once-per-frame submit() flushes this in one
        // vkQueueSubmit, which is what actually signals presentSem (deferred-submit model).
        enc.waitSemaphore(acquireSem, 0L, 65536L);
        enc.execute(cmd);
        enc.signalSemaphore(presentSem, 0L, 4096L);
    }

    private void ensureCapacity(VulkanDevice device, int semaphoreCount, int generatedCount) {
        if (pendingImageIndex.length < generatedCount) {
            pendingImageIndex = new int[generatedCount];
            pendingPresentSem = new long[generatedCount];
        }
        if (acquireSemaphores.length >= semaphoreCount) {
            return;
        }
        destroy(device);
        acquireSemaphores = new long[semaphoreCount];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < semaphoreCount; i++) {
                if (VK10.vkCreateSemaphore(device.vkDevice(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed");
                }
                acquireSemaphores[i] = p.get(0);
            }
        }
        acquireCursor = 0;
    }

    /** Destroy the acquire-semaphore pool (device teardown). */
    public void destroy(VulkanDevice device) {
        for (long sem : acquireSemaphores) {
            if (sem != 0L) {
                VK10.vkDestroySemaphore(device.vkDevice(), sem, null);
            }
        }
        acquireSemaphores = new long[0];
        acquireCursor = 0;
        pendingCount = 0;
    }
}
