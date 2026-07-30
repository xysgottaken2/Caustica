package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.CausticaJitter;
import dev.comfyfluffy.caustica.mixin.CommandEncoderAccessor;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.BreakEntry;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.accel.RtBuffer;
import dev.comfyfluffy.caustica.rt.accel.RtImage;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.material.RtEmissionSemantics;
import dev.comfyfluffy.caustica.rt.material.RtMaterialOverrides;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.pipeline.RtBloomPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;


/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, write that into a storage-capable
 * copy of the world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dcaustica.rt=true}.
 *
 * <p>The path tracer and its guide buffers run at the configured render scale of display res with a per-frame
 * sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the display-res image. With RR
 * disabled the trace runs at 1:1 and a linear blit stands in for the upscale (a raw, noisy reference).
 *
 * <p>Traces the extracted {@link RtTerrain} with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until terrain is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();

    public static boolean enabled() {
        return CausticaConfig.Rt.ENABLED.value();
    }

    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // Hot addresses/frameIndex and raygen's debugView avoid unnecessary global-memory dereferences;
    // WorldPushConstantsData is generated from the same Slang module and owns this second ABI as well.
    private static final int GUIDE_COUNT = 6; // RR guide buffers bound at world-pipeline bindings 3..8
    private static final long PATH_RECORD_BYTES = 48L;
    private static int debugView() {
        return CausticaConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static int spp() {
        return CausticaConfig.Rt.Composite.SPP.value();
    }

    private static int maxBounces() {
        return CausticaConfig.Rt.Composite.MAX_BOUNCES.value();
    }

    private static boolean waterWaves() {
        return CausticaConfig.Rt.Composite.WATER_WAVES.value();
    }

    // Finite sun/moon angular sizes let NEE shadow rays sample the light disk (soft, contact-hardening
    // penumbrae). Radii in degrees; the real sun/moon are ~0.27°, but a touch larger reads pleasantly.
    private static final int WATER_ANCHOR_MASK = 4095;
    private static final Identifier SUN_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_IDS = createMoonIds();
    // Celestial rotation axis (the pole the sun/moon arc about): perpendicular to the east-west arc,
    // tilted by SUN_NOON_SOUTH_TILT. Pushed so the sky shader can build the sun/moon square's tangent
    // frame (right = travel direction) and wheel the starfield. = normalize(noonDir x sunriseDir).
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return CausticaConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    private static float sunNoonTilt() {
        return CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT.value();
    }

    private static float sunNoonY() {
        return Mth.cos(sunNoonTilt());
    }

    private static float sunNoonZ() {
        return Mth.sin(sunNoonTilt());
    }

    private static float celestialAxisY() {
        return -sunNoonZ();
    }

    private static float celestialAxisZ() {
        return sunNoonY();
    }

    // Monotonic per-composite frame counter used for cache eviction, shader sampling, and diagnostics.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private RtPipeline worldPipeline;
    // Set at the HEAD of Minecraft.reloadResourcePacks() (mixin): a resource reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundBlockAlbedoAtlasHandle to a fresh non-zero value (MC's deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundBlockAlbedoAtlasHandle;
    private int bindlessTextureCapacity;
    // True after the LabPBR atlases have been resolved/bound for the currently alive world pipeline.
    private boolean materialBindingsReady;
    // Set when a new material epoch is published. The first composite returns to vanilla so the next
    // client tick can apply RtTerrain's full-clear before any old-epoch primitive IDs are traced.
    private boolean materialEpochTraceGate;
    // World push data lives in a host-visible BDA ring; only the slot address and a small hot subset are
    // pushed inline (the full generated structure exceeds NVIDIA's 256-byte push-constant ceiling).
    // Exact graphics completion guards host writes; ring depth only avoids routine waits.
    private static final int PUSH_RING = 6;
    private PushSlot[] pushRing;
    private int pushSlot;
    private RtDisplayPipeline displayPipeline;
    // Mip-chain bloom feeding the display pass (prefilter -> downsample* -> upsample*). Sized alongside the
    // display images; its resolved half-res result is bound into the display pipeline.
    private RtBloomPipeline bloomPipeline;
    private RtImage output;
    // Packed primary -> indirect continuations. Pass A is fixed at one sample and owns two records per
    // render pixel (base + optional transmission); Pass B resamples them at the configured SPP.
    private RtBuffer continuationQueue;
    private RtImage displayImage;
    // Parallel PQ-encoded ([0,1], ST.2084) HDR display image. Written alongside displayImage when HDR is
    // enabled. When the PQ swapchain is active, the combined UI overlay is composited over this image, then
    // this image is blitted straight to the swapchain.
    private RtImage hdrDisplayImage;
    // Set true after this frame's display dispatch wrote hdrDisplayImage (HDR enabled + RT ran); gates the
    // HDR present blit so a frame where RT did not run falls back to the vanilla SDR present.
    private boolean hdrWrittenThisFrame;
    // DLSS-FG "hudless" resource: a copy of the main render target before the combined UI overlay
    // composites back on top. Lazily allocated (only meaningful once FG + the UI overlay redirect are both
    // active), resized on demand.
    private RtImage fgHudlessImage;
    // Same idea as fgHudlessImage but for the HDR present path: a copy of hdrDisplayImage taken in
    // presentHdr right before its own combined-UI composite dispatch overwrites it in place (see
    // captureFgHdrHudless). Already PQ-encoded (same as hdrDisplayImage), so this is a plain image copy, not
    // a format conversion — DLSS-FG requires a display-ready EOTF-encoded [0,1] signal (its programming
    // guide explicitly disallows scRGB), and PQ is exactly that.
    private RtImage fgHdrHudlessImage;
    // Step C.2: composites the combined UI overlay over hdrDisplayImage at paper white, just before present.
    private RtHdrCompositePipeline hdrCompositePipeline;
    private long hdrUiSampler;

    private static final class PushSlot {
        final RtBuffer buffer;
        final RtGpuExecutor.TrackedGraphicsUse graphicsUse = new RtGpuExecutor.TrackedGraphicsUse();

        PushSlot(RtBuffer buffer) {
            this.buffer = buffer;
        }
    }
    // Menu/non-RT present: converts the SDR main target (sRGB) to PQ-encoded at paper white so menus,
    // the title panorama and the loading screen present correctly to the PQ swapchain instead of being
    // raw-copied (misdisplayed). Lazily created; the image is sized to the swapchain.
    private RtSdrPresentPipeline sdrPresentPipeline;
    private RtImage sdrPresentImage;
    // DLSS Frame Generation: per-generated-frame interpolated output images (backbuffer size/format), and
    // the jitter-free reprojection matrices derived from the MV view-projections each frame. In HDR mode
    // these hold DLSSG's raw PQ-encoded output, which is blitted straight to the (PQ) swapchain — no decode
    // needed since the swapchain itself is PQ-native.
    private RtImage[] fgInterp = new RtImage[0];
    private int fgInterpW = -1;
    private int fgInterpH = -1;
    private int fgInterpFormat = Integer.MIN_VALUE;
    private boolean fgReset = true;
    private final Matrix4f fgClipToPrev = new Matrix4f();
    private final Matrix4f fgPrevToClip = new Matrix4f();
    private final Matrix4f fgMatTmp = new Matrix4f();
    // Guide buffers (first-hit attributes for DLSS-RR): normal+roughness, albedo, depth, motion,
    // specular albedo, and reflection motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    private RtImage gSpecMotion;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;
    private final RtExposure exposure = new RtExposure();

    // Trace + guide buffers run at render res; composite (display-mapping) runs at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;
    // What ensureOutput last sized the render/guide images for, so a quality change (or RR being
    // toggled) at a fixed window size is noticed even though displayW/displayH didn't change.
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;

    // Motion-vector reprojection state: the previous frame's camera-relative view-projection and
    // camera position, read into the push constant each frame then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private final Matrix4f frameInvViewProj = new Matrix4f();
    private final BlockPos.MutableBlockPos cameraBlockPos = new BlockPos.MutableBlockPos();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private float previousWaterWaveTime;
    private boolean waterWaveTimeValid;
    private long atlasSampler;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private double camX;
    private double camY;
    private double camZ;
    private boolean frameCaptured;
    private long celestialUvAtlasHandle;
    private int celestialUvMoonPhase = -1;
    private float sunU0;
    private float sunV0;
    private float sunU1 = 1f;
    private float sunV1 = 1f;
    private float moonU0;
    private float moonV0;
    private float moonU1 = 1f;
    private float moonV1 = 1f;

    // Per-frame TLAS resources, rebuilt in place from a small ring of persistent slots (see
    // RtAccel.TlasRing — replaces the old create-and-defer-destroy-per-frame churn whose VMA slow path
    // showed up as rare multi-ms prepareTlas spikes).
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();

    // This frame's TLAS handle, published after prepareTlas so the world-overlay pass (block outline's
    // rayQueryEXT occlusion test) can bind the exact same acceleration structure the primary trace used —
    // same-queue submission order (RtWorldOverlay's transient buffer runs later, same graphics queue)
    // makes the TLAS build's writes visible without an extra semaphore, matching every other overlay
    // feature's reliance on in-order queue execution for this frame's world content.
    private volatile long currentTlasHandle;
    private RtGpuExecutor.GraphicsUse pendingGraphicsUse;

    private RtComposite() {
    }

    /** This frame's TLAS handle (0 if none built yet), for {@code dev.comfyfluffy.caustica.rt.overlay} occlusion queries. */
    public long currentTlasHandle() {
        return currentTlasHandle;
    }

    private static Identifier[] createMoonIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /**
     * Whether the current frame must retain vanilla world rendering while RT resource state converges.
     *
     * <p>The composite still runs at the normal seam so it can consume the one-frame epoch gate or observe
     * the newly uploaded atlas. This method only prevents {@code LevelRenderer} from being cancelled before
     * a deliberately transient {@link #composite} return. Such a return is not a renderer failure and must
     * not trip {@code VanillaRenderController}'s permanent safety latch.</p>
     */
    public boolean requiresVanillaWorldFallback() {
        // Pipeline creation publishes a new material epoch and deliberately makes composite() return
        // false once so RtTerrain can apply the matching full clear. Keep vanilla alive for that bring-up
        // frame; otherwise LevelRenderer is cancelled before composite() discovers it must fall back and
        // VanillaRenderController permanently latches the resulting missing replacement frame.
        if (worldPipeline == null || !materialBindingsReady) {
            return true;
        }
        if (materialEpochTraceGate) {
            return true;
        }
        if (RtEntityTextures.maxTextures() > bindlessTextureCapacity) {
            return true;
        }
        if (reloadRebindRequested) {
            long atlas = blockAlbedoAtlasView();
            return atlas == 0L || atlas == boundBlockAlbedoAtlasHandle;
        }
        return false;
    }

    /**
     * Clear the failure latch on an explicit render-state invalidation (F3+A, dimension change) so RT
     * re-arms after a transient error instead of staying on vanilla until restart. A deterministic
     * failure just latches again on the next frame (bounded log spam: one error line per invalidation).
     */
    public void resetFailureLatch() {
        if (failed) {
            failed = false;
            CausticaMod.LOGGER.info("RT failure latch cleared by render-state invalidation; retrying RT");
        }
    }

    /** Capture the frame's camera for the next composite. Called from GameRendererMixin. */
    public void captureFrame(Matrix4f projection, Matrix4fc viewRotation, double cameraX, double cameraY, double cameraZ) {
        frameProjection.set(projection);
        frameViewRotation.set(viewRotation);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        frameCaptured = true;
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — overlay raster passes ({@code dev.comfyfluffy.caustica.rt.overlay}) reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    public Matrix4fc currentViewProjection() {
        return mvCurProjView;
    }

    /**
     * Reset per-frame present state at the very start of {@link net.minecraft.client.renderer.GameRenderer}
     * render (before any RT work). Critical for menu/no-world frames: {@link #composite()} is only called
     * while a level is rendering ({@code WorldRenderScaler} opens its window in {@code renderLevel}), so on
     * menu frames {@code composite} never runs and {@code hdrWrittenThisFrame} would otherwise keep its stale
     * {@code true} from the last world frame — presenting a black/stale HDR image behind the menu. Clearing it
     * here every frame makes {@link #isHdrPresentActive()} false on menu frames so the SDR convert-present path
     * runs instead.
     */
    public void beginFrame() {
        if (pendingGraphicsUse != null) {
            throw new IllegalStateException("Previous RT graphics use was never completed");
        }
        RtFrameStats.FRAME.beginIfInactive();
        hdrWrittenThisFrame = false;
    }

    /** This frame's completion token, valid until {@link #finishGraphicsUse()} signals it. */
    public RtGpuExecutor.GraphicsUse currentGraphicsUse() {
        RenderSystem.assertOnRenderThread();
        return pendingGraphicsUse;
    }

    /** Signal this RT frame's shared completion token after its final TLAS consumer (world overlay). */
    public void finishGraphicsUse() {
        RtGpuExecutor.GraphicsUse graphicsUse = pendingGraphicsUse;
        if (graphicsUse == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("RT context disappeared before graphics use completed");
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice()
                .createCommandEncoder()).caustica$getBackend();
        ctx.gpuExecutor().endGraphicsUse(encoder, graphicsUse);
        pendingGraphicsUse = null;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // global frame serial used by remaining per-frame/entity rings and diagnostics
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
        hdrWrittenThisFrame = false; // set true again below once this frame's HDR display image is written
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        ctx.gpuExecutor().throwIfFailed();
        // Count-bounded terrain streaming (dispatch/drain/build kick) runs here once per render frame — before
        // the ready gate below, because it is what MAKES terrain ready during the initial fill.
        try {
            RtTerrain.frame(ctx);
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT terrain streaming failed; reverting to vanilla path", t);
            return false;
        }
        if (RtTerrain.currentOrNull() == null || !frameCaptured || Minecraft.getInstance().level == null) {
            // No world this frame (incl. after quitting to the title — terrain residency + frameCaptured can
            // linger until an explicit invalidate, which would otherwise present a stale/empty HDR image as a
            // black menu background). Skip RT so the present path falls back to vanilla SDR / the PQ SDR
            // convert path, which shows the menu + panorama correctly.
            return false;
        }
        try {
            if (displayPipeline == null) {
                displayPipeline = RtDisplayPipeline.create(ctx);
            }
            if (bloomPipeline == null) {
                bloomPipeline = RtBloomPipeline.create(ctx);
            }
            // A resource reload re-stitches the block atlas. We've already torn down the world pipeline
            // (onResourceReloadStart) so nothing references the old atlas, but MC's deferred free keeps the
            // old view handle live for a few frames, then swaps in the new atlas (whose GPU upload may lag,
            // leaving the handle 0 transiently). Skip RT — vanilla renders — until the handle becomes a
            // fresh, non-zero value different from what we last bound; only then rebuild against it.
            if (reloadRebindRequested) {
                long atlas = blockAlbedoAtlasView();
                if (atlas == 0L || atlas == boundBlockAlbedoAtlasHandle) {
                    return false;
                }
            }
            ensureOutput(ctx, width, height);
            // Cheap idempotent check every frame (not just on resize): if the exposure mode is switched
            // manual -> auto at runtime (video settings), the auto-mode histogram/state/pipeline must be
            // allocated before recordFrame's exposure.record() below needs them, or it throws.
            exposure.ensureResources(ctx);
            refreshPipelineShapeIfNeeded(ctx);
            RtPipeline active = ensureWorld(ctx);
            if (materialEpochTraceGate) {
                materialEpochTraceGate = false;
                return false;
            }
            refreshMaterialBindingsIfNeeded(ctx);
            updateMotion();
            recordFrame(ctx, active, nativeColor);
            if (!loggedActive) {
                loggedActive = true;
                CausticaMod.LOGGER.info("RT composite active (terrain): {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            CausticaMod.LOGGER.error("RT composite failed; reverting to vanilla path", t);
            return false;
        }
    }

    /**
     * Bring the world pipeline + LabPBR atlases up as soon as we're in a world and the block atlas is
     * loaded — <em>before</em> terrain tessellates — so the immutable material snapshot is available to
     * the first worker section. Driven from the client tick ahead of {@link RtTerrain#update}. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public void ensureResourcesReady(RtContext ctx) {
        if (failed || worldPipeline != null || reloadRebindRequested) {
            return;
        }
        if (Minecraft.getInstance().level == null || blockAlbedoAtlasView() == 0L) {
            return;
        }
        try {
            ensureWorld(ctx);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("RT resource bring-up failed; reverting to vanilla path", t);
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            bindlessTextureCapacity = RtEntityTextures.maxTextures();
            worldPipeline = RtPipeline.create(ctx, new String[]{
                            RtDeviceBringup.worldPrimaryRaygenShader(),
                            RtDeviceBringup.worldRaygenShader()},
                    new String[]{"world.rmiss.spv", "world_guide.rmiss.spv"},
                    "world.rchit.spv", "world.rahit.spv",
                    WorldPushConstantsData.BYTE_SIZE, true, GUIDE_COUNT, bindlessTextureCapacity, true);
            // Per-frame world data lives in this BDA ring; the pipeline pushes its address and hot fields.
            if (pushRing == null) {
                pushRing = new PushSlot[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = new PushSlot(ctx.createBuffer(WORLD_PUSH_SIZE,
                            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i));
                }
            }
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindWorldTextures(ctx);
            reloadRebindRequested = false;
        }
        // The TLAS is rebuilt and bound per frame in recordFrame since dynamic entity content animates
        // the instance set every frame.
        return worldPipeline;
    }

    private void refreshPipelineShapeIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        int desiredBindlessCapacity = RtEntityTextures.maxTextures();
        if (desiredBindlessCapacity <= bindlessTextureCapacity) {
            return;
        }
        ctx.waitIdle();
        worldPipeline.destroy();
        worldPipeline = null;
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
    }

    /**
     * Resolve + bind every world-pipeline texture: the block atlas (binding 2 + bindless fallback slot 0)
     * and the canonical material page bundles in reserved bindless slots. Shared by first creation and
     * the post-reload rebind. Resets the entity bindless registry, recreates material pages, builds
     * the shared material registry, and invalidates old-epoch geometry before tracing resumes.
     */
    private void bindWorldTextures(RtContext ctx) {
        long sampler = atlasSampler(ctx);
        long atlasView = blockAlbedoAtlasView();
        boundBlockAlbedoAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        worldPipeline.setBlockAlbedoAtlas(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtBlockMaterials.INSTANCE.reset();
        RtMaterialOverrides materialOverrides = RtMaterialOverrides.load();
        RtEmissionSemantics emissionSemantics = RtEmissionSemantics.analyze();
        RtBlockMaterials.INSTANCE.prepareAll(ctx, bindlessTextureCapacity, emissionSemantics, materialOverrides);
        RtEntityTextures.INSTANCE.reset(bindlessTextureCapacity);
        worldPipeline.setEntityAlbedoTexture(0, atlasView, sampler);
        RtBlockMaterials.INSTANCE.bindPages(worldPipeline, sampler);
        RtMaterialRegistry.INSTANCE.rebuild(ctx, RtBlockMaterials.INSTANCE, materialOverrides);
        materialBindingsReady = true;
        // Sky rewrite: bind the vanilla celestials atlas (sun + moon phases) for world.rmiss. The view
        // handle is stable across frames; the shader only samples it inside the sun/moon discs (sky
        // directions), so the block-atlas fallback is never read if the celestials atlas isn't ready.
        long celView = celestialsAtlasView();
        if (worldPipeline.hasSkyAtlas()) {
            worldPipeline.setSkyAtlas(celView != 0L ? celView : atlasView, sampler);
        }
        setCelestialUvAtlas(celView);
        // Atlas UVs and material IDs are one resource epoch. Drop old terrain as a unit rather than
        // incrementally displaying old UVs/IDs against the new atlas/table.
        RtTerrain.requestFullClear();
        materialEpochTraceGate = true;
    }

    private void refreshMaterialBindingsIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        if (!materialBindingsReady) {
            bindWorldTextures(ctx);
        }
    }

    /** Vulkan image-view of the vanilla celestials atlas (sun + moon-phase sprites), or 0 if unavailable. */
    private static long celestialsAtlasView() {
        try {
            GpuTextureView view = Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.CELESTIALS).getTextureView();
            return vkImageView(view);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Hooked at the HEAD of {@link net.minecraft.client.Minecraft#reloadResourcePacks()} (mixin). A
     * resource reload re-stitches the block atlas (and reloads entity textures): MC frees the old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every descriptor reference (block atlas binding 2 +
     * bindless set) — so MC can free its textures cleanly. The pipeline is cheap to rebuild (no terrain
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). The new material epoch clears terrain before trace.
     */
    public void onResourceReloadStart() {
        reloadRebindRequested = true;
        materialBindingsReady = false;
        setCelestialUvAtlas(0L);
        RtEntities.INSTANCE.onResourceReload();
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null) {
            ctx.waitIdle();
            if (worldPipeline != null) {
                worldPipeline.destroy();
                worldPipeline = null;
                bindlessTextureCapacity = 0;
            }
            RtMaterialRegistry.INSTANCE.destroy();
        }
    }

    /** Bind the guide buffers into the world pipeline's extra storage-image slots. */
    private void bindGuideImages() {
        if (worldPipeline == null || gNormal == null) {
            return;
        }
        worldPipeline.setExtraStorageImage(0, gNormal.view);
        worldPipeline.setExtraStorageImage(1, gAlbedo.view);
        worldPipeline.setExtraStorageImage(2, gDepth.view);
        worldPipeline.setExtraStorageImage(3, gMotion.view);
        worldPipeline.setExtraStorageImage(4, gSpecAlbedo.view);
        worldPipeline.setExtraStorageImage(5, gSpecMotion.view);
    }

    private void destroyGuideImages() {
        if (gNormal != null) {
            gNormal.destroy();
            gNormal = null;
        }
        if (gAlbedo != null) {
            gAlbedo.destroy();
            gAlbedo = null;
        }
        if (gDepth != null) {
            gDepth.destroy();
            gDepth = null;
        }
        if (gMotion != null) {
            gMotion.destroy();
            gMotion = null;
        }
        if (gSpecAlbedo != null) {
            gSpecAlbedo.destroy();
            gSpecAlbedo = null;
        }
        if (gSpecMotion != null) {
            gSpecMotion.destroy();
            gSpecMotion = null;
        }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        boolean rrEnabled = RtDlssRr.enabled();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (output != null && continuationQueue != null
                && displayImage != null && hdrDisplayImage != null && rrOutput != null && exposure.ready()
                && displayW == width && displayH == height
                && renderSizeRrEnabled == rrEnabled && renderSizeRrQuality == rrQuality) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        if (displayImage != null) {
            displayImage.destroy();
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
        destroyGuideImages();

        displayW = width;
        displayH = height;
        // The path tracer + its guide buffers run at render res; DLSS-RR (or a fallback blit) upscales
        // to display res. With RR off there is no reconstruction pass, so trace at 1:1 for a faithful reference.
        // With RR on, ask NGX what render resolution its chosen quality mode actually expects rather
        // than assuming a fixed ratio: different quality modes (and driver versions) use different
        // ratios, and DLSSD's own optimal-settings query is the source of truth for what it will accept.
        int[] optimal = rrEnabled ? RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height) : null;
        renderW = optimal != null ? optimal[0] : width;
        renderH = optimal != null ? optimal[1] : height;
        renderSizeRrEnabled = rrEnabled;
        renderSizeRrQuality = rrQuality;

        // RT traces into an HDR (R16G16B16A16_SFLOAT) target so radiance > 1 survives to the display
        // mapping seam. displayImage stays R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderW + "x" + renderH);
        long pixelRecords = Math.multiplyExact((long) renderW, (long) renderH);
        long continuationBytes = Math.multiplyExact(
                Math.multiplyExact(pixelRecords, 2L), PATH_RECORD_BYTES);
        continuationQueue = ctx.createBuffer(continuationBytes,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                "path continuation queue " + renderW + "x" + renderH + "x2");
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        // PQ-encoded ([0,1], ST.2084) HDR display image, written in parallel by display.comp when HDR mode is active.
        hdrDisplayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "RT HDR display image " + width + "x" + height);
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "guide linear depth " + renderW + "x" + renderH);
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderW + "x" + renderH);
        gSpecMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderW + "x" + renderH);
        // Display-res RT image the display mapper reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "DLSS-RR output " + width + "x" + height);
        exposure.ensureResources(ctx);

        mvHasPrev = false; // recreated images -> first MV frame is zero
        waterWaveTimeValid = false;
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        // The bloom chain reads the same display-res RT image and exposure image the display pass does, so
        // it is (re)built here, while the device is idle and the views are known good.
        bloomPipeline.ensure(rrOutput.view, exposure.image().view, width, height);
        displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view, hdrDisplayImage.view,
                bloomPipeline.resolvedView());
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion() {
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        if (mvHasPrev) {
            mvPushMatrix.set(mvPrevProjView);
            mvCamDeltaX = (float) (camX - mvPrevCamX);
            mvCamDeltaY = (float) (camY - mvPrevCamY);
            mvCamDeltaZ = (float) (camZ - mvPrevCamZ);
        } else {
            mvPushMatrix.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = camX;
        mvPrevCamY = camY;
        mvPrevCamZ = camZ;
        mvHasPrev = true;
    }

    private void recordFrame(RtContext ctx, RtPipeline active, GpuTexture nativeColor) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).caustica$getBackend();
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        // Reserve the graphics-use value that guards this frame's reusable TLAS and entity resources.
        RtGpuExecutor.GraphicsUse graphicsUse = gpuExecutor.beginGraphicsUse(encoder);
        RtGpuExecutor.GraphicsUseWaiter graphicsUseWaiter = gpuExecutor.graphicsUseWaiter();
        pendingGraphicsUse = graphicsUse;
        RtEntities.FrameEntities frameEntities = null;
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        int debugView = debugView();
        RtTerrain terrain = RtTerrain.currentOrNull();
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // Jitter is suppressed for the no-RR reference and for the debug guide views (raw inspection).
            boolean rrPath = RtDlssRr.enabled() && debugView == 0;
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                CausticaJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }

            boolean rrDone = false;
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including entity addresses and block-breaking entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            PushSlot selectedPushSlot = pushRing[pushSlot];
            graphicsUseWaiter.await(selectedPushSlot.graphicsUse);
            selectedPushSlot.graphicsUse.mark(graphicsUse);
            RtBuffer pushBuf = selectedPushSlot.buffer;
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            // flags: camera-in-water (so the path tracer starts in the water medium when the eye is
            // submerged, fixing the air→water first-segment orientation) + W1 wave normals. Bit 1 used to
            // gate a Lambertian fallback BRDF that nothing ever turned off; the GGX path is unconditional
            // now, so that bit is unused rather than reassigned, to avoid a stale reader elsewhere.
            int flags = 0;
            var level = Minecraft.getInstance().level;
            if (level != null) {
                cameraBlockPos.set(Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ));
                // Height-aware, mirroring vanilla's own Camera.getFluidInCamera(): a plain block-granular
                // test wrongly flags the eye submerged anywhere in a water column's top block, even well
                // above its actual surface (shallow/flowing water, or standing with your head just over a
                // source block).
                FluidState fs = level.getFluidState(cameraBlockPos);
                if (fs.is(FluidTags.WATER) && camY < cameraBlockPos.getY() + fs.getHeight(level, cameraBlockPos)) {
                    flags |= 0b01;
                }
            }
            if (waterWaves()) {
                flags |= 0b10000; // W1: animated water wave normals
            }

            // W1/W2 water parameters: camera-biome tint plus wrapped animation time. Per-water-body tint
            // comes from the primitive; this is the fallback for a camera already inside the medium.
            float wtr = 0.25f, wtg = 0.46f, wtb = 0.9f; // neutral ocean-ish default if no level/biome
            if (level != null) {
                int wc = BiomeColors.getAverageWaterColor(level, cameraBlockPos);
                wtr = ((wc >> 16) & 0xFF) / 255f;
                wtg = ((wc >> 8) & 0xFF) / 255f;
                wtb = (wc & 0xFF) / 255f;
            }
            float waterWaveTime = (float) (System.nanoTime() / 1.0e9 % 3600.0);
            float waterWaveDelta = waterWaveTime - previousWaterWaveTime;
            // A first frame, long pause, or one-hour phase wrap has no adjacent wave frame to reproject.
            // Use the current phase so the reflection MV is neutral instead of manufacturing a huge jump.
            float priorWaterWaveTime = waterWaveTimeValid
                    && waterWaveDelta >= 0f && waterWaveDelta <= 0.25f
                    ? previousWaterWaveTime : waterWaveTime;
            previousWaterWaveTime = waterWaveTime;
            waterWaveTimeValid = true;
            Float4 waterParams = new Float4(wtr, wtg, wtb, waterWaveTime);
            // W1 wave-domain anchor: the terrain rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so the
            // ripple pattern stays fixed in the world as the player moves and the rebase origin shifts.
            Float4 waterAnchor = new Float4(terrain.blockX & WATER_ANCHOR_MASK,
                    terrain.blockZ & WATER_ANCHOR_MASK, priorWaterWaveTime, 0f);

            // Rebuild the TLAS this frame from static section instances merged with dynamic entity
            // instances, bind it into the pipeline's descriptor ring, record the build, then barrier so
            // the trace sees the finished TLAS. Section BLASes are already built (async, by RtTerrain);
            // only the cheap instance-level TLAS is rebuilt per frame. Retired terrain geometry/table
            // generations are reclaimed by graphics-timeline completion.
            // Entity BLASes are built inline below and merged into the per-frame TLAS. geomTableAddr
            // feeds the hit shader entity path (per-prim normal/tint) and motion vectors.
            RtEntities.FrameEntities fe = RtEntities.INSTANCE.beginFrame(ctx, terrain.staticInstances(),
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ, frameProjection, frameViewRotation);
            frameEntities = fe;
            // Block-breaking overlay: resolves each destroy-stage RenderType's texture into the
            // SAME bindless entity-texture array (destroy_stage_N.png is a standalone Sampler0 texture,
            // not a block-atlas sprite — see ModelBakery.BREAKING_LOCATIONS/DESTROY_TYPES), so any newly
            // resolved slot rides along with the uploadPending() call right below.
            BreakEntry[] breaking = breakingEntries(terrain);
            SkyPush sky = skyPush();
            new WorldPushData(
                    frameInvViewProj,
                    new Float3((float) (camX - terrain.blockX), (float) (camY - terrain.blockY),
                            (float) (camZ - terrain.blockZ)),
                    (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    spp(),
                    new Float2(jitterX, jitterY),
                    flags,
                    maxBounces(),
                    sky.sunDir(),
                    sky.lightDir(),
                    sky.lightRadiance(),
                    sky.moonDir(),
                    sky.celestial(),
                    sky.sunUv(),
                    sky.moonUv(),
                    waterParams,
                    waterAnchor,
                    mvCurProjView,
                    breaking.length,
                    breaking,
                    // RIS emitter NEE: candidate count (0 = emitter NEE off; the shader also requires
                    // lightCount > 0, so an empty buffer degrades to legacy gather). The light buffer
                    // device addresses themselves are pc.light*Addr — every 64-bit address lives in the
                    // push-constant block now, not here.
                    new Float4(terrain.lightRebaseOffsetX(), terrain.lightRebaseOffsetY(),
                            terrain.lightRebaseOffsetZ(), terrain.lightInvGlobalPowerSum()),
                    new Float4(terrain.lightGridOriginX(), terrain.lightGridOriginY(), terrain.lightGridOriginZ(), 16f),
                    new Int4(terrain.lightGridDimX(), terrain.lightGridDimY(), terrain.lightGridDimZ(), 0),
                    terrain.lightCount(),
                    CausticaConfig.Rt.Lights.RIS_CANDIDATES.value(),
                    // z = night brightness: the sky miss shader scales its night ambient by it, so the one
                    // slider dims or lifts the whole night, not just the moon's direct light below.
                    new Float4(CausticaConfig.Rt.Lights.BLOCK_INTENSITY.value(),
                            CausticaConfig.Rt.Lights.DYNAMIC_INTENSITY.value(),
                            CausticaConfig.Rt.DayNight.MOON_INTENSITY.value(), 0.0f)
            ).write(push);
            pushBuf.flush(0L, WORLD_PUSH_SIZE);
            // Upload any entity textures registered this frame into the bindless set before the trace.
            RtEntityTextures.INSTANCE.uploadPending(active, atlasSampler(ctx));
            // Build the entity BLAS, the TLAS that references it and the terrain BLAS, then the trace.
            // Barriers separate each stage; the graphics-use timeline guards resource reuse.
            if (!fe.blas().isEmpty()) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blasRecord")) {
                    RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // entity BLAS writes visible to the TLAS build
            }
            RtAccel.PreparedTlas frameTlas;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                frameTlas = RtAccel.prepareTlas(ctx, fe.baseInstances(), fe.dynamicInstances(), tlasRing,
                        graphicsUse);
            }
            active.setTlas(frameTlas.accel.handle, graphicsUse, graphicsUseWaiter);
            currentTlasHandle = frameTlas.accel.handle;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // TLAS build visible to the trace

            // Push the BDA ring slot's address plus the small hot subset used directly by the shaders.
            // Every 64-bit device address the trace needs lives here, not behind worldPushAddr: the
            // section/entity/material tables are read from world.rahit/world.rchit, which never load
            // WorldPush at all, and the RIS light buffers are read from world.rgen's hot inner loop, so
            // none of them should cost an extra BDA dereference to find.
            ByteBuffer pushConstants = stack.malloc(WorldPushConstantsData.BYTE_SIZE);
            new WorldPushConstantsData(pushBuf.deviceAddress, terrain.tableAddress(), fe.geomTableAddr(),
                    RtMaterialRegistry.INSTANCE.tableAddress(),
                    terrain.lightBufferAddress(), terrain.lightAliasBufferAddress(),
                    terrain.lightLocalAliasBufferAddress(), terrain.lightGridCellBufferAddress(),
                    terrain.lightGridSpanBufferAddress(), continuationQueue.deviceAddress,
                    (int) frameCounter, debugView).write(pushConstants);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world primary trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.tracePrimary")) {
                active.trace(cmd, renderW, renderH, pushConstants, 0);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // continuation/guide writes visible to pass B
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world indirect trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.traceIndirect")) {
                active.trace(cmd, renderW, renderH, pushConstants, 1);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
            // DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput.
            if (rrPath && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH)) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.dlssRr")) {
                    rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                            gSpecAlbedo, gNormal, gSpecMotion, rrOutput, renderW, renderH, displayW, displayH,
                            -jitterX, -jitterY, frameViewRotation, frameProjection);
                }
            }

            // When DLSS-RR did not produce the display-res image (disabled, debug view, or a runtime
            // failure), bring the render-res trace up to display res with a linear blit so the display mapper
            // always has a display-res RT image. With RR off render == display, so this is a 1:1 copy.
            if (!rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, output, rrOutput);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // rrOutput visible to exposure histogram

            // Auto-exposure meters rrOutput (the post-RR, denoised/converged image), not the raw
            // pre-RR trace: RR has no notion of exposure (DLSS-RR Integration Guide §3.7 — ignore
            // exposure/auto-exposure/sharpness entirely for RR), so this is purely our own metering
            // choice, independent of RR's pipeline placement. Metering the noisy pre-RR buffer made
            // the histogram's log-luminance average biased by Monte-Carlo noise (Jensen's inequality
            // on the concave log()), so the computed exposure drifted with SPP; rrOutput is stable
            // regardless of SPP, keeping exposure consistent.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.exposure")) {
                exposure.record(ctx, cmd, stack, rrOutput);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // exposure image visible to the display mapper

            // Bloom runs between exposure and the display mapping: the prefilter needs this frame's
            // exposure to threshold in the same domain the tonemap curve works in, and the display pass
            // needs the finished chain. Skipped entirely when bloom is off or its intensity is zero.
            boolean bloomActive = CausticaConfig.Rt.Bloom.ENABLED.value()
                    && CausticaConfig.Rt.Bloom.INTENSITY.value() > 0.0f
                    && bloomPipeline.ready();
            if (bloomActive) {
                try (RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.bloom")) {
                    bloomPipeline.record(cmd, stack, CausticaConfig.Rt.Bloom.THRESHOLD.value(),
                            CausticaConfig.Rt.Tonemapping.EXPOSURE_EV.value(),
                            CausticaConfig.Rt.Bloom.RADIUS.value());
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // bloom writes visible to the display mapper
            }

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                displayPipeline.dispatch(cmd, displayW, displayH, CausticaConfig.Rt.Hdr.enabled(),
                        CausticaConfig.Rt.Hdr.paperWhiteNits(), CausticaConfig.Rt.Hdr.headroom(),
                        CausticaConfig.Rt.Tonemapping.operatorIndex(),
                        CausticaConfig.Rt.Tonemapping.EXPOSURE_EV.value(),
                        CausticaConfig.Rt.Tonemapping.GAMMA.value(),
                        CausticaConfig.Rt.Tonemapping.SATURATION.value(),
                        CausticaConfig.Rt.Tonemapping.CONTRAST.value(),
                        bloomActive,
                        CausticaConfig.Rt.Bloom.INTENSITY.value(),
                        CausticaConfig.Rt.Bloom.THRESHOLD.value(),
                        CausticaConfig.Rt.Bloom.RADIUS.value());
            }
            hdrWrittenThisFrame = CausticaConfig.Rt.Hdr.enabled();
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.copyOutput")) {
                VK10.vkCmdCopyImage(cmd, displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
        // Do not attach a merely reserved token: failed recording may never signal it. Once execute succeeds,
        // every owner in this frame's manifest is protected through the final overlay consumer.
        RtEntities.INSTANCE.markGraphicsUse(frameEntities, graphicsUse);
    }

    /**
     * Block-breaking overlay: mirrors vanilla's {@code ClientLevel.destructionProgress()} (populated
     * by network packets, independent of the cancelled {@code LevelRenderer.render()} — see
     * [[rt-native-overlay-tier1]]) into the push's {@code breaking[]} list, so {@code world.rchit} can blend
     * the matching destroy-stage crack texture into a hit terrain block's albedo. Each block's own
     * destroy-stage texture ({@code minecraft:textures/block/destroy_stage_N.png}, resolved via
     * {@link ModelBakery#DESTROY_TYPES}) is a standalone {@code Sampler0} texture, not a block-atlas sprite,
     * so it rides the same bindless entity-texture array as entity textures ({@link RtEntityTextures}).
     */
    private BreakEntry[] breakingEntries(RtTerrain terrain) {
        BreakEntry[] result = new BreakEntry[WorldPushData.BREAKING_CAPACITY];
        int count = 0;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (var entry : level.destructionProgress().long2ObjectEntrySet()) {
                if (count >= result.length) {
                    break;
                }
                var progresses = entry.getValue();
                if (progresses == null || progresses.isEmpty()) {
                    continue;
                }
                int stage = Mth.clamp(progresses.last().getProgress(), 0, 9);
                BlockPos pos = BlockPos.of(entry.getLongKey());
                int slot = RtEntityTextures.INSTANCE.slotFor(ModelBakery.DESTROY_TYPES.get(stage));
                result[count++] = new BreakEntry(new Int4(
                        pos.getX() - terrain.blockX,
                        pos.getY() - terrain.blockY,
                        pos.getZ() - terrain.blockZ,
                        slot));
            }
        }
        return count == result.length ? result : java.util.Arrays.copyOf(result, count);
    }


    private record SkyPush(Float4 sunDir, Float4 lightDir, Float4 lightRadiance, Float4 moonDir,
                           Float4 celestial, Float4 sunUv, Float4 moonUv) {}

    private record CelestialUv(Float4 sun, Float4 moon) {}

    /**
     * Derive the celestial light from Minecraft's time of day as typed values for {@link WorldPushData}.
     * Celestial angles come from the camera's {@link EnvironmentAttributeProbe} (partial-tick
     * interpolated). {@code caustica.rt.sunNoonSouthDeg} tilts the east-west arc toward south (+Z) at
     * noon.
     */
    private SkyPush skyPush() {
        float sunX, sunY, sunZ, dayFactor, lx, ly, lz, rr, rg, rb, lightRadius;
        float moonX, moonY, moonZ, moonPhase, starAngle, starBrightness;
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * (float) (Math.PI / 180.0);
        float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * (float) (Math.PI / 180.0);
        float sunNoon = Mth.cos(sunAngle);
        sunX = -Mth.sin(sunAngle); sunY = sunNoonY() * sunNoon; sunZ = sunNoonZ() * sunNoon;
        float moonNoon = Mth.cos(moonAngle);
        moonX = -Mth.sin(moonAngle); moonY = sunNoonY() * moonNoon; moonZ = sunNoonZ() * moonNoon;
        moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(); // 0 full .. 4 new
        starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * (float) (Math.PI / 180.0);
        starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
        dayFactor = smoothstep(-0.08f, 0.10f, sunY);
        float[] trans = new float[3];
        if (sunY > -0.05f) {
            atmosphereTransmittance(sunX, sunY, sunZ, trans);
            float fade = smoothstep(-0.05f, 0.005f, sunY);
            // The sun's colour at any elevation IS the atmospheric transmittance along its own direction:
            // the very function the sky shader tints its disc and horizon with, so sunlight on terrain can
            // never disagree with the sky behind it. The old hand-authored tint ladder (pink/orange steps
            // keyed off sunY) is gone; all that survives is a small warm residual, scaled by
            // SUNSET_TINT_STRENGTH, for people who want a pushed sunset.
            float sunPeak = 21.0f;
            // 1 with the disc on the horizon, 0 by ~14 degrees up.
            float low = 1.0f - smoothstep(0.0f, 0.25f, sunY);
            // Real sunsets are dim. By the time the disc touches the horizon the direct beam has crossed
            // dozens of air masses and most of the scene is lit by skylight instead; transmittance alone
            // still leaves the beam too hot next to that (already dim) sky, so roll the peak down with it.
            float horizonDim = 1.0f - 0.45f * low * low;
            float warm = 0.12f * low * CausticaConfig.Rt.DayNight.SUNSET_TINT_STRENGTH.value();
            lx = sunX; ly = sunY; lz = sunZ;
            rr = sunPeak * trans[0] * fade * horizonDim * (1.0f + warm);
            rg = sunPeak * trans[1] * fade * horizonDim;
            rb = sunPeak * trans[2] * fade * horizonDim * (1.0f - 0.5f * warm);
            lightRadius = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value();
        } else {
            // Moonlight, scaled by the same night-brightness slider that scales the sky's night ambient.
            atmosphereTransmittance(moonX, moonY, moonZ, trans);
            float moonStrength = smoothstep(0.04f, 0.22f, -sunY);
            float litFraction = 1.0f - Math.abs(moonPhase - 4.0f) / 4.0f; // 0 new .. 1 full
            float moonPeak = 0.035f * (0.05f + 0.95f * litFraction);
            moonPeak *= CausticaConfig.Rt.DayNight.MOON_INTENSITY.value();
            lx = moonX; ly = moonY; lz = moonZ;
            rr = 0.30f * moonPeak * moonStrength * trans[0];
            rg = 0.36f * moonPeak * moonStrength * trans[1];
            rb = 0.55f * moonPeak * moonStrength * trans[2];
            lightRadius = CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.value();
        }
        CelestialUv uv = celestialUv(moonPhase);
        return new SkyPush(
                new Float4(sunX, sunY, sunZ, dayFactor),
                new Float4(lx, ly, lz, lightRadius),
                new Float4(rr, rg, rb, starBrightness),
                new Float4(moonX, moonY, moonZ, moonPhase),
                new Float4(0f, celestialAxisY(), celestialAxisZ(), starAngle),
                uv.sun(),
                uv.moon());
    }

    /**
     * Push the celestials-atlas UV rects (u0,v0,u1,v1) for the sun sprite and the current moon-phase
     * sprite, so world.rmiss can sample the real vanilla textures on the discs. Atlas-not-ready (early
     * boot / no resources) leaves full-range UVs and the shader's block-atlas fallback covers it.
     */
    private CelestialUv celestialUv(float moonPhaseIndex) {
        if (celestialUvAtlasHandle == 0L) {
            setCelestialUvAtlas(celestialsAtlasView());
        }
        int phase = Math.clamp((int) moonPhaseIndex, 0, MOON_IDS.length - 1);
        if (phase != celestialUvMoonPhase) {
            refreshCelestialUvCache(phase);
        }
        return new CelestialUv(
                new Float4(sunU0, sunV0, sunU1, sunV1),
                new Float4(moonU0, moonV0, moonU1, moonV1));
    }

    private void setCelestialUvAtlas(long atlasHandle) {
        if (celestialUvAtlasHandle == atlasHandle) {
            return;
        }
        celestialUvAtlasHandle = atlasHandle;
        celestialUvMoonPhase = -1;
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
    }

    private void refreshCelestialUvCache(int moonPhase) {
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
        try {
            if (celestialUvAtlasHandle != 0L) {
                TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
                TextureAtlasSprite sun = atlas.getSprite(SUN_ID);
                sunU0 = sun.getU0(); sunV0 = sun.getV0(); sunU1 = sun.getU1(); sunV1 = sun.getV1();
                TextureAtlasSprite moon = atlas.getSprite(MOON_IDS[moonPhase]);
                moonU0 = moon.getU0(); moonV0 = moon.getV0(); moonU1 = moon.getU1(); moonV1 = moon.getV1();
            }
        } catch (Exception ignored) {
            // celestials atlas not yet loaded — keep full-range UVs (fallback texture is the block atlas)
        }
        celestialUvMoonPhase = moonPhase;
    }

    /** Hermite smoothstep matching GLSL semantics (0 below edge0, 1 above edge1). */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * RGB transmittance from the camera to space along {@code dir} — a verbatim port of
     * {@code world.rmiss}'s {@code transmittanceToSpace} (Rayleigh + Mie + ozone optical depth, 8-step
     * march from 2 km altitude; constants must stay in lock-step with the shader). This is what colours
     * the NEE sun/moonlight: because the sky shader tints its visible discs with the identical function,
     * the light on terrain and the sky's sunset can never disagree. A direction below the geometric
     * horizon accumulates enormous optical depth, so the result rolls to zero smoothly on its own —
     * no explicit planet-shadow test needed.
     */
    private static void atmosphereTransmittance(float dx, float dy, float dz, float[] out) {
        final double planetR = 6371000.0, atmosR = 6471000.0;
        final double[] rayBeta = {5.5e-6, 13.0e-6, 22.4e-6};
        final double mieBeta = 21.0e-6 * 1.1;
        final double[] ozoneBeta = {0.650e-6, 1.881e-6, 0.085e-6};
        final double oy = planetR + 2000.0;
        // Larger root of ray vs atmosphere sphere, origin (0, oy, 0).
        double b = oy * dy;
        double tEnd = -b + Math.sqrt(Math.max(b * b - (oy * oy - atmosR * atmosR), 0.0));
        double seg = tEnd / 8.0;
        double odR = 0.0, odM = 0.0, odO = 0.0;
        for (int i = 0; i < 8; i++) {
            double t = seg * (i + 0.5);
            double px = dx * t, py = oy + dy * t, pz = dz * t;
            double h = Math.sqrt(px * px + py * py + pz * pz) - planetR;
            odR += Math.exp(-h / 8000.0) * seg;
            odM += Math.exp(-h / 1200.0) * seg;
            odO += Math.max(0.0, 1.0 - Math.abs(h - 25000.0) / 15000.0) * seg;
        }
        for (int i = 0; i < 3; i++) {
            out[i] = (float) Math.exp(-(rayBeta[i] * odR + mieBeta * odM + ozoneBeta[i] * odO));
        }
    }

    public void destroy() {
        // Teardown runs after the device is idle (CLIENT_STOPPING waits), so the TLAS ring's slots are no
        // longer in flight and can be freed immediately.
        tlasRing.destroy();
        if (RtDlssRr.enabled()) {
            RtDlssRr.INSTANCE.destroy();
        }
        if (displayImage != null) {
            displayImage.destroy();
            displayImage = null;
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
            hdrDisplayImage = null;
        }
        if (fgHudlessImage != null) {
            fgHudlessImage.destroy();
            fgHudlessImage = null;
        }
        if (fgHdrHudlessImage != null) {
            fgHdrHudlessImage.destroy();
            fgHdrHudlessImage = null;
        }
        RtWorldOverlay.INSTANCE.destroy(); // overlay features/pipelines/scratch live on the same device lifetime
        if (output != null) {
            output.destroy();
            output = null;
        }
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
        destroyGuideImages();
        exposure.destroy();
        if (bloomPipeline != null) {
            bloomPipeline.destroy();
            bloomPipeline = null;
        }
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (hdrCompositePipeline != null) {
            hdrCompositePipeline.destroy();
            hdrCompositePipeline = null;
        }
        if (hdrUiSampler != 0L) {
            RtContext hdrCtx = RtContext.currentOrNull();
            if (hdrCtx != null) {
                VK10.vkDestroySampler(hdrCtx.vk(), hdrUiSampler, null);
            }
            hdrUiSampler = 0L;
        }
        if (sdrPresentPipeline != null) {
            sdrPresentPipeline.destroy();
            sdrPresentPipeline = null;
        }
        if (sdrPresentImage != null) {
            sdrPresentImage.destroy();
            sdrPresentImage = null;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[0];
        fgInterpW = -1;
        fgInterpH = -1;
        fgInterpFormat = Integer.MIN_VALUE;
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
        materialEpochTraceGate = false;
        RtMaterialRegistry.INSTANCE.destroy();
        if (pushRing != null) {
            for (PushSlot slot : pushRing) {
                if (slot != null) {
                    slot.buffer.destroy();
                }
            }
            pushRing = null;
        }
        if (atlasSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
    }

    private long atlasSampler(RtContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(16f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, atlasSampler, "block atlas sampler");
            }
        }
        return atlasSampler;
    }

    private static long blockAlbedoAtlasView() {
        GpuTextureView view = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        return vkImageView(view);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        throw new IllegalStateException("cannot resolve VkImageView for " + view);
    }

    private static long vkImage(GpuTexture texture) {
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    /** Whether the HDR present path (HDR image + combined UI -> PQ swapchain) should replace the vanilla SDR blit. */
    public boolean isHdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled()
                && hdrWrittenThisFrame
                && hdrDisplayImage != null;
    }

    /**
     * DLSS-FG: the PQ-encoded HDR backbuffer (view/image), valid only right after {@link #presentHdr} has run
     * this frame (it's the same image {@code presentHdr} just composited UI into and blitted to the
     * swapchain) — used as the interpolation source for HDR frame generation instead of the SDR main target.
     * Already display-ready PQ, so it's fed to DLSSG directly with no extra encode step. 0 if HDR isn't
     * active this frame.
     */
    public long hdrBackbufferView() {
        return hdrDisplayImage != null ? hdrDisplayImage.view : 0L;
    }

    public long hdrBackbufferImage() {
        return hdrDisplayImage != null ? hdrDisplayImage.image : 0L;
    }

    /**
     * Blit this frame's PQ-encoded HDR image straight into the swapchain image, replacing Minecraft's SDR
     * blit. Replicates {@code VulkanGpuSurface.blitFromTexture}'s barrier + acquire-wait/present-signal
     * sequence with the HDR {@link RtImage} as the (GENERAL-layout) source; an added memory barrier makes the
     * display-compute writes visible to the blit read. The SDR main target is bypassed; the combined UI image
     * is blended over the HDR image here at paper white before the swapchain blit. The magic stage/access
     * values mirror vanilla {@code blitFromTexture} exactly. Y is flipped to match the vanilla swapchain blit.
     */
    public void presentHdr(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH, long acquireSem, long presentSem) {
        RtImage src = hdrDisplayImage;
        int copyW = Math.min(swapW, src.width);
        int copyH = Math.min(swapH, src.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // DLSS-FG "hudless" capture: hdrDisplayImage right now holds the RT world before the combined
            // UI overlay is blended in. Snapshot it before that composite overwrites it in place, mirroring
            // captureFgHudless's SDR pattern (pre-UI copy) but reusing this frame's already-open command
            // buffer.
            if (RtDlssFg.enabled()) {
                captureFgHdrHudless(cmd, stack, src);
            }

            // Step C.2: composite the combined UI overlay over the HDR world image (in place) at paper white,
            // before the swapchain blit. The overlay is an MC render target kept in GENERAL layout, sampled by
            // the compute pass. A memory barrier first makes the overlay writes + the world HDR writes visible
            // to the compute; the dep1 barrier below (ALL writes -> transfer read) then covers the compute's
            // HDR write for the blit.
            long overlayView = RtUiOverlay.populatedThisFrame() ? RtUiOverlay.overlayColorView() : 0L;
            if (overlayView != 0L) {
                ensureHdrUiResources();
                if (hdrCompositePipeline != null) {
                    VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                    pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
                    VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
                    KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);
                    hdrCompositePipeline.setImages(hdrDisplayImage.view, overlayView, hdrUiSampler);
                    hdrCompositePipeline.dispatch(cmd, src.width, src.height, CausticaConfig.Rt.Hdr.paperWhiteNits());
                }
                RtUiOverlay.markConsumed();
            }
            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the HDR compute writes visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit HDR (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
    }

    /** Lazily create the HDR UI-composite compute pipeline + its nearest/clamp sampler (first HDR present). */
    private void ensureHdrUiResources() {
        if (hdrCompositePipeline != null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return;
        }
        hdrCompositePipeline = RtHdrCompositePipeline.create(ctx);
    }

    /** Ensure the shared nearest/clamp sampler used to sample SDR/overlay targets in the present compute. */
    private boolean ensureUiSampler(RtContext ctx) {
        if (hdrUiSampler != 0L) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            var p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                return false;
            }
            hdrUiSampler = p.get(0);
        }
        return true;
    }

    /**
     * Whether a non-RT frame (menu, title panorama, loading screen) should be SDR-&gt;PQ converted for
     * present instead of vanilla's raw SDR blit. True when the PQ swapchain is active but this frame did
     * not produce an HDR image ({@link #isHdrPresentActive()} false).
     */
    public boolean isPqSdrPresentActive() {
        return CausticaConfig.Rt.Hdr.enabled()
                && !isHdrPresentActive();
    }

    /**
     * Present a non-RT (menu/loading) frame to the PQ swapchain: convert the SDR main target (sRGB-encoded
     * rgba8, GENERAL layout, already holding the composited panorama + UI) to PQ-encoded at paper white via
     * a compute pass into {@link #sdrPresentImage}, then blit that into the swapchain. Mirrors
     * {@link #presentHdr} barrier-for-barrier; returns false (keep vanilla SDR blit) if resources are
     * unavailable.
     */
    public boolean presentSdrToPq(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH,
            long sdrMainView, long acquireSem, long presentSem) {
        if (sdrMainView == 0L || failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return false;
        }
        if (sdrPresentPipeline == null) {
            sdrPresentPipeline = RtSdrPresentPipeline.create(ctx);
        }
        if (sdrPresentImage == null || sdrPresentImage.width != swapW || sdrPresentImage.height != swapH) {
            if (sdrPresentImage != null) {
                sdrPresentImage.destroy();
            }
            sdrPresentImage = ctx.createStorageImage(swapW, swapH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + swapW + "x" + swapH);
        }
        RtImage dst = sdrPresentImage;
        int copyW = Math.min(swapW, dst.width);
        int copyH = Math.min(swapH, dst.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // Make the prior GUI/overlay writes to the SDR main target visible to the compute sample.
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
            VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            sdrPresentPipeline.setImages(dst.view, sdrMainView, hdrUiSampler);
            sdrPresentPipeline.dispatch(cmd, dst.width, dst.height, CausticaConfig.Rt.Hdr.paperWhiteNits());

            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the compute write visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout