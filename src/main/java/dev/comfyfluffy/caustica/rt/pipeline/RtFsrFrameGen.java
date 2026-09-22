package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.RtUpscalerSupport;
import dev.comfyfluffy.caustica.fsr.FsrLibrary;
import dev.comfyfluffy.caustica.fsr.FsrRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;

import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * AMD FSR 3.1 Frame Generation backend (EXPERIMENTAL). Manual/swapchain-less integration: Caustica
 * owns Minecraft's swapchain and presents the generated frames itself ({@code RtFramePresenter}), so
 * the FFX FG context is created WITHOUT a swapchain — only the two compute dispatches run here:
 * PREPARE once per rendered frame (dilated depth + MVs + camera info at render resolution), then one
 * GENERATE dispatch producing the single interpolated frame into the presenter's target
 * (multiplier is fixed at 2x — see {@link #MAX_GENERATED_FRAMES} for why the runtime cannot do more).
 *
 * <p>Inputs mirror the FSR upscale conventions already validated in {@code RtFsrUpscaler}:
 * reversed-Z infinite depth (DEPTH_INVERTED | DEPTH_INFINITE), render-pixel motion vectors scaled to
 * UV by the shim, jitter negated, vertical FOV positive.
 */
public final class RtFsrFrameGen {
    public static final RtFsrFrameGen INSTANCE = new RtFsrFrameGen();

    /**
     * Hard engine limit, not a tuning knob: FSR 3.1 frame generation produces EXACTLY ONE
     * interpolated frame per rendered frame (= 2x multiplier), so this caps the generated count at 1.
     *
     * <p>Why: the {@code ffxDispatchDescFrameGeneration} descriptor declares {@code outputs[4]} and a
     * {@code numGeneratedFrames} field, but the FSR 3.1 runtime that ships in the signed
     * {@code amd_fidelityfx_vk.dll} ignores both — verified in the SDK's own provider source
     * (FidelityFX-SDK v1.1.4, {@code ffx-api/src/ffx_provider_framegeneration.cpp}): its Dispatch()
     * forwards only {@code desc->outputs[0]} into {@code ffxFrameInterpolationDispatch}, which
     * interpolates a single midpoint frame. Multi-output generation (3x/4x MFG) only exists in the
     * FSR 4 / FFX 1.2+ engine, which is not what this runtime bundles.
     *
     * <p>This is the root cause of the reported "3x black blink" and post-switch colorful corruption:
     * with a configured count of 2+ the presenter dutifully blitted + presented {@code outputs[1..3]},
     * which the runtime never writes — freshly created storage images present as black (the periodic
     * black blink) and stale/reused ones as colorful garbage, i.e. exactly one corrupted "slot" per
     * presented group. 2x stays clean because {@code outputs[0]} is the one image the runtime fills.
     */
    public static final int MAX_GENERATED_FRAMES = 1;

    /**
     * FG is opted into through the shared {@code caustica.rt.fg} toggle, and rides when FSR 3 OR
     * XeSS is the selected upscaler — same "FG rides on the selected upscaler" contract as the
     * DLSS path. XeSS has no Frame Generation of its own on Vulkan (Intel's XeSS-FG/XeLL are
     * D3D12-only in every SDK release to date), so the FFX API's FG engine rides on top of the
     * XeSS-upscaled frame instead: it only consumes depth + motion vectors + the presented frame,
     * which are upscaler-agnostic by design.
     */
    public static boolean enabled() {
        if (!CausticaConfig.Rt.Fg.ENABLED.value()) {
            return false;
        }
        String mode = RtUpscalerSupport.currentUpscalerMode();
        return RtUpscalerSupport.MODE_FSR3.equals(mode) || RtUpscalerSupport.MODE_XESS.equals(mode);
    }

    // FfxApiCreateContextFramegenerationFlags for this renderer: reversed-Z (infinite far) depth and
    // FfxApiCreateContextFramegenerationFlags. DEPTH_INVERTED + DEPTH_INFINITE match the tracer's
    // reversed-Z infinite-far depth. HIGH_DYNAMIC_RANGE is applied ONLY when the backbuffer actually
    // is HDR — declaring HDR while feeding SDR content makes FG linearize SDR values as PQ, which
    // maps them to near-black and was a prime suspect for the black generated frames.
    private static final int FLAG_DEPTH_INVERTED = 1 << 3;
    private static final int FLAG_DEPTH_INFINITE = 1 << 4;
    private static final int FLAG_HIGH_DYNAMIC_RANGE = 1 << 5;
    private static final int CONTEXT_FLAGS_SDR = FLAG_DEPTH_INVERTED | FLAG_DEPTH_INFINITE;
    private static final int CONTEXT_FLAGS_HDR = CONTEXT_FLAGS_SDR | FLAG_HIGH_DYNAMIC_RANGE;

    // FfxApiBackbufferTransferFunction values.
    private static final int TRANSFER_SRGB = 0;
    private static final int TRANSFER_PQ = 1;

    // Same near-plane heuristic as the FSR upscale path.
    private static final float CAMERA_NEAR = 0.05f;

    private FsrLibrary lib;
    private MemorySegment context = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean resetRequested = true;
    private long frameId;
    private long lastFrameNanos;
    // EMA-smoothed frame interval (see prepareAndGenerate): FG times its interpolation from the
    // frame delta, and this renderer's low/variable real framerates make the raw delta wobble.
    private float smoothedFrameMs;
    // Multiplier the FG temporal state was last configured for. Changing the generated-frame count
    // at runtime (user flips 2x->4x->5x in the menu) shifts the interpolation cadence, so FG gets a
    // clean reset on the transition instead of interpolating across a mismatched cadence. This is
    // what lets the multiplier change take effect live, without a game restart.
    private int lastNumGenerated = -1;

    private int contextDisplayWidth = -1;
    private int contextDisplayHeight = -1;
    private int contextRenderWidth = -1;
    private int contextRenderHeight = -1;
    private int contextFormat = Integer.MIN_VALUE;

    private RtFsrFrameGen() {
    }

    /**
     * Generated-frame count the player asked for, clamped to the engine limit (always 1 on this
     * runtime — see {@link #MAX_GENERATED_FRAMES}). A stale higher config value from an earlier
     * build is clamped silently rather than presenting never-written frames.
     */
    public int effectiveGeneratedCount() {
        return Math.clamp(CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value(), 1, MAX_GENERATED_FRAMES);
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(context);
    }

    public boolean featureReadyFor(int displayWidth, int displayHeight, int renderWidth, int renderHeight, int format) {
        return isReady() && contextDisplayWidth == displayWidth && contextDisplayHeight == displayHeight
                && contextRenderWidth == renderWidth && contextRenderHeight == renderHeight
                && contextFormat == format;
    }

    /**
     * Ensure an FG context exists for these dimensions/backbuffer format. Context creation is plain
     * device allocation (records nothing). Returns false (and latches off) on failure.
     */
    public boolean ensureFeature(int displayWidth, int displayHeight,
                                 int renderWidth, int renderHeight, int backbufferFormat) {
        if (!enabled() || failed) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        try {
            if (!initialized) {
                lib = FsrRuntime.INSTANCE.acquire(device);
                if (lib == null) {
                    throw new IllegalStateException("FSR runtime unavailable; FSR FG cannot initialize");
                }
                initialized = true;
            }
            if (!featureReadyFor(displayWidth, displayHeight, renderWidth, renderHeight, backbufferFormat)) {
                releaseContext(device);
                // HDR flag must match the real backbuffer format (see CONTEXT_FLAGS_* docs).
                boolean hdr = backbufferFormat == VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
                context = lib.createFg(renderWidth, renderHeight, displayWidth, displayHeight,
                        backbufferFormat, hdr ? CONTEXT_FLAGS_HDR : CONTEXT_FLAGS_SDR);
                if (isNull(context)) {
                    throw new IllegalStateException("fsrshim_create_fg failed: last=" + lib.lastResult());
                }
                contextDisplayWidth = displayWidth;
                contextDisplayHeight = displayHeight;
                contextRenderWidth = renderWidth;
                contextRenderHeight = renderHeight;
                contextFormat = backbufferFormat;
                resetRequested = true; // fresh context has no temporal history
                frameId = 0;
                CausticaMod.LOGGER.info("FSR FG context created: {}x{} (render {}x{}, backbuffer format {})",
                        displayWidth, displayHeight, renderWidth, renderHeight, backbufferFormat);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("FSR FG setup failed; frame generation disabled", t);
            return false;
        }
    }

    /**
     * Record PREPARE + GENERATE into {@code cmd} in one shot: prepares the temporal state from the
     * render-res guides, then generates {@code numGenerated} interpolated frames from {@code present}
     * into {@code outputs}. Camera arrays are world-space 3-vectors (position, up, right, forward).
     * Returns false on failure (caller treats FG as fatal for the session).
     */
    public boolean prepareAndGenerate(long cmd,
                                      long depthImage, long mvImage,
                                      int renderWidth, int renderHeight,
                                      float jitterX, float jitterY, float fovY,
                                      float[] camPos, float[] camUp, float[] camRight, float[] camForward,
                                      long presentImage, int presentFormat,
                                      long[] outputs, int numGenerated,
                                      int width, int height, boolean hdr) {
        if (!isReady() || numGenerated < 1 || numGenerated > MAX_GENERATED_FRAMES) {
            return false;
        }
        try {
            long now = System.nanoTime();
            float rawFrameMs = lastFrameNanos == 0 ? 16.6f
                    : Math.clamp((now - lastFrameNanos) / 1_000_000.0f, 0.1f, 200.0f);
            lastFrameNanos = now;
            // EMA-smooth the frame interval before handing it to FG: at the low/variable real
            // framerates this renderer runs at (~30fps on a 2060), the raw delta wobbles
            // frame-to-frame, and FSR FG times its interpolation from it — a jittery delta makes
            // the generated frames' positions wobble too, a flicker that reads worst on large
            // uniform regions like the sky. A light EMA stabilises the timing signal while still
            // tracking genuine pace changes within a few frames.
            smoothedFrameMs = smoothedFrameMs <= 0.0f ? rawFrameMs
                    : 0.75f * smoothedFrameMs + 0.25f * rawFrameMs;
            float frameMs = smoothedFrameMs;
            frameId++;

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pos = vec3(arena, camPos);
                MemorySegment up = vec3(arena, camUp);
                MemorySegment right = vec3(arena, camRight);
                MemorySegment forward = vec3(arena, camForward);
                int rc = lib.fgPrepare(context, cmd,
                        depthImage, VK10.VK_FORMAT_R32_SFLOAT,
                        mvImage, VK10.VK_FORMAT_R16G16_SFLOAT,
                        renderWidth, renderHeight, jitterX, jitterY,
                        frameMs, Float.MAX_VALUE, CAMERA_NEAR, fovY,
                        frameId, pos, up, right, forward);
                if (rc != 0) {
                    throw new IllegalStateException("fsrshim_fg_prepare failed: " + rc + " last=" + lib.lastResult());
                }

                boolean multiplierChanged = lastNumGenerated > 0 && lastNumGenerated != numGenerated;
                lastNumGenerated = numGenerated;
                MemorySegment outArray = arena.allocate(ValueLayout.JAVA_LONG, numGenerated);
                for (int i = 0; i < numGenerated; i++) {
                    outArray.setAtIndex(ValueLayout.JAVA_LONG, i, outputs[i]);
                }
                rc = lib.fgGenerate(context, cmd,
                        presentImage, presentFormat,
                        outArray, presentFormat, numGenerated,
                        width, height, frameId,
                        hdr ? TRANSFER_PQ : TRANSFER_SRGB, (resetRequested || multiplierChanged) ? 1 : 0);
                if (rc != 0) {
                    throw new IllegalStateException("fsrshim_fg_generate failed: " + rc + " last=" + lib.lastResult());
                }
            }
            resetRequested = false;
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("FSR FG dispatch failed; frame generation disabled", t);
            return false;
        }
    }

    private static MemorySegment vec3(Arena arena, float[] v) {
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, 3);
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 0, v[0]);
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 1, v[1]);
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 2, v[2]);
        return seg;
    }

    /** Request a temporal reset on the next generate (e.g. after a teleport/rebase jump). */
    public void requestReset() {
        resetRequested = true;
    }

    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseContext(device);
        }
        initialized = false;
        lib = null;
    }

    private void releaseContext(VulkanDevice device) {
        if (!isNull(context)) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null && ctx.device() == device) {
                ctx.waitIdle();
            } else {
                VK10.vkDeviceWaitIdle(device.vkDevice());
            }
            lib.destroyFg(context);
            context = MemorySegment.NULL;
        }
        contextDisplayWidth = -1;
        contextDisplayHeight = -1;
        contextRenderWidth = -1;
        contextRenderHeight = -1;
        contextFormat = Integer.MIN_VALUE;
        lastFrameNanos = 0;
        smoothedFrameMs = 0.0f; // fresh context restarts the timing EMA too
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }
}
