package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.client.CausticaJitter;
import dev.comfyfluffy.caustica.rt.gen.RestirReservoirData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.BreakEntry;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float2;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float3;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Float4;
import dev.comfyfluffy.caustica.rt.gen.WorldPushData.Int4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
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
import dev.comfyfluffy.caustica.rt.pipeline.RtDisplayPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssRr;
import dev.comfyfluffy.caustica.rt.pipeline.RtFsrFrameGen;
import dev.comfyfluffy.caustica.rt.pipeline.RtFsrUpscaler;
import dev.comfyfluffy.caustica.rt.pipeline.RtXessUpscaler;
import dev.comfyfluffy.caustica.rt.pipeline.RtFgSkyMaskPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtFgUiCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtNativeFrameGen;
import dev.comfyfluffy.caustica.rt.pipeline.RtNativeFrameGenPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSvgfDenoiser;
import dev.comfyfluffy.caustica.rt.pipeline.RtNrdDenoiser;
import dev.comfyfluffy.caustica.rt.pipeline.RtNrdCombinePipeline;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import dev.comfyfluffy.caustica.rt.pipeline.RtHdrCompositePipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtSdrPresentPipeline;
import dev.comfyfluffy.caustica.rt.pipeline.RtExposure;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    // Per-frame DH/Voxy hand-off readiness mask, appended in the same BDA ring slot behind WorldPush.
    private static final int READY_MASK_OFFSET = (WORLD_PUSH_SIZE + 15) & ~15;
    // Covers a 257x257x48-section window (render distance 128) with room to spare.
    private static final int READY_MASK_CAPACITY = 512 * 1024;
    // Vanilla's authored classic cloud shape (RtCloudCells): a bit-packed clouds.png occupancy bitmap
    // riding the same ring slot, addressed from WorldPush.cloudCellsAddr exactly like the ready mask
    // above is addressed from the push constants — no extra binding, one flush covers all three.
    private static final int CLOUD_CELLS_OFFSET = (READY_MASK_OFFSET + READY_MASK_CAPACITY + 15) & ~15;
    private static final int WORLD_PUSH_BUFFER_SIZE = CLOUD_CELLS_OFFSET + RtCloudCells.MAP_BYTES;
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // Hot addresses/frameIndex and raygen's debugView avoid unnecessary global-memory dereferences;
    // WorldPushConstantsData is generated from the same Slang module and owns this second ABI as well.
    // RR guide buffers (bindings 3..8) + NRD signals (bindings 9..11: viewZ + per-lobe radiance/hit
    // distance). The NRD images are only written when FEATURE_NRD is on, but the bindings always exist.
    private static final int GUIDE_COUNT = 9;
    private static final long PATH_RECORD_BYTES = 48L;
    // Reflected from PackedRestirReservoir's std430 array stride (world_layout_probe.slang).
    private static final long RESTIR_RECORD_BYTES = RestirReservoirData.BYTE_SIZE;
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

    /**
     * Shader-only POM parameters: x relief depth (blocks), y max texel crossings, z unused,
     * w fade distance.
     *
     * <p>The shader walks the LabPBR height field as a grid of per-texel box columns (the same
     * Amanatides &amp; Woo walk the classic cloud deck uses), so its cost is bounded by texel crossings
     * instead of by a fixed layer count. Deeper relief slants the walk further across the sprite before
     * it reaches the base plane, so the budget scales with the configured depth; the shader clamps it
     * to PARALLAX_MIN/MAX_CROSSINGS either way.
     */
    private static Float4 parallaxParams() {
        boolean enabled = CausticaConfig.Rt.Composite.PARALLAX_ENABLED.value();
        float strength = CausticaConfig.Rt.Composite.PARALLAX_STRENGTH.value();
        float depth = enabled ? strength * 0.125f : 0.0f;
        // POM Quality slider: multiplies the strength-derived texel-crossing budget without touching
        // the relief depth, so the player trades sampling level for fps independently of the look.
        // The outer clamp keeps the pushed budget inside the shader's compiled bounds (the shader
        // re-clamps it too — see PARALLAX_MIN/MAX_CROSSINGS in world_common.slang).
        float quality = CausticaConfig.Rt.Composite.PARALLAX_QUALITY.value();
        float crossings = Math.min(128.0f,
                Math.max(16.0f, Math.round(32.0f * Math.max(1.0f, strength) * quality)));
        return new Float4(depth, crossings, 0.0f,
                CausticaConfig.Rt.Composite.PARALLAX_DISTANCE.value());
    }

    /**
     * Volumetric fog lanes (WorldPush.fogParams): x density fraction, y Henyey-Greenstein
     * anisotropy, z integration distance in blocks, w height falloff scale in blocks.
     *
     * <p>The toggle writes a zero density lane rather than a feature bit: {@code fog.slang} gates
     * every term on {@code fogParams.x > 0}, so "off" is bit-identical to a frame that never ran
     * the fog path, and there is no second switch for the shader and Java to disagree about. Read
     * fresh every frame like the other Composite lanes, so a slider drag lands next frame.
     */
    private static Float4 fogParams() {
        if (!CausticaConfig.Rt.Composite.FOG_ENABLED.value()) {
            return new Float4(0.0f, 0.0f, 0.0f, 0.0f);
        }
        return new Float4(
                CausticaConfig.Rt.Composite.FOG_DENSITY.value(),
                CausticaConfig.Rt.Composite.FOG_ANISOTROPY.value(),
                CausticaConfig.Rt.Composite.FOG_DISTANCE.value(),
                CausticaConfig.Rt.Composite.FOG_HEIGHT_FALLOFF.value());
    }

    /**
     * The fog scatter's colour (WorldPush.fogTint): vanilla's own {@code FOG_COLOR} through the
     * same camera probe {@link #cloudColorState} reads for the deck — the game resolves it per
     * biome blend at the camera, per weather, per dimension, so the swamp greens its god rays and
     * a downpour greys them on vanilla's curve, with no biome walk or upload of our own. Early
     * boot or a missing attribute leaves the lane at strength 0, which is the module's neutral
     * cool-white tint — the same picture as before this lane existed, not a black fog.
     *
     * <p>Fog off writes the whole lane zero (matching {@link #fogParams()}'s "off costs one
     * comparison" contract; the shader multiplies nothing when nobody reads it anyway).
     */
    private static Float4 fogTint() {
        float strength = CausticaConfig.Rt.Composite.FOG_BIOME_TINT.value();
        if (!CausticaConfig.Rt.Composite.FOG_ENABLED.value() || strength <= 0f) {
            return new Float4(1f, 1f, 1f, 0f);
        }
        float r = 1f, g = 1f, b = 1f;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameRenderer != null) {
                float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vector3fc fog = mc.gameRenderer.mainCamera().attributeProbe()
                        .getValue(EnvironmentAttributes.FOG_COLOR, partial);
                r = srgbToLinear(fog.x());
                g = srgbToLinear(fog.y());
                b = srgbToLinear(fog.z());
            }
        } catch (Throwable ignored) {
            // Probe unavailable (early boot / unsupported context): strength 0 keeps the neutral tint.
            return new Float4(1f, 1f, 1f, 0f);
        }
        return new Float4(r, g, b, Math.clamp(strength, 0f, 1f));
    }

    // ---- Shader feature flags (WorldPush.featureFlags). Mirrors world_common.slang's FEATURE_*
    // constants: these are player-facing effect toggles, kept in their own word so they can never be
    // confused with WorldPush.flags, which describes the camera's physical state for the frame.
    private static final int FEATURE_SSS = 1;
    private static final int FEATURE_WEATHER_LIGHTING = 2;
    private static final int FEATURE_DENOISER = 4;
    private static final int FEATURE_CLOUDS = 8;
    private static final int FEATURE_CLOUDS_VOLUMETRIC = 16;
    private static final int FEATURE_RESTIR = 32;
    // Per-lobe NRD signal capture: only the NRD path needs it (it costs an extra shadow ray for the
    // lobe split), so it is a feature bit rather than something the tracer always pays for.
    private static final int FEATURE_NRD = 64;
    // gViewZ capture. Every denoised non-DLSS path needs it (SVGF's reprojection validation and
    // sky cutoff, NRD's IN_VIEWZ), so it is set for both denoisers.
    private static final int FEATURE_VIEWZ = 128;
    private static final int FEATURE_SHARC = 256;

    // ---- Dimension ids (WorldPush.dimension). Mirrors world_common.slang's DIMENSION_* constants.
    // The Overworld runs the atmosphere march and the sun/moon cycle; the Nether and the End have no
    // celestial cycle at all and draw their own skybox in world.rmiss.
    private static final int DIMENSION_OVERWORLD = 0;
    private static final int DIMENSION_NETHER = 1;
    private static final int DIMENSION_END = 2;

    /**
     * Collect this frame's shader feature toggles into the {@code featureFlags} word. Read fresh every
     * frame (never cached) so the Video Settings toggles take effect on the next frame, the way every
     * other runtime-tunable option in the renderer does.
     */
    private static int featureFlags() {
        int flags = 0;
        if (CausticaConfig.Rt.Composite.SSS.value()) {
            flags |= FEATURE_SSS;
        }
        if (CausticaConfig.Rt.Composite.WEATHER_LIGHTING.value()) {
            flags |= FEATURE_WEATHER_LIGHTING;
        }
        if (CausticaConfig.Rt.Lights.RESTIR_SAMPLING.value()) {
            flags |= FEATURE_RESTIR;
        }
        if (CausticaConfig.Rt.Sharc.ENABLED.value() && RtSharc.INSTANCE.entryCount() > 0) {
            flags |= FEATURE_SHARC;
        }
        if (CausticaConfig.Rt.Composite.CLOUDS.value()) {
            flags |= FEATURE_CLOUDS;
            // The style is a feature bit, not a packed float lane: featureFlags is exactly the word for
            // player-facing effect toggles, and an integer bit survives every float quirk.
            if (CausticaConfig.Rt.Composite.cloudStyleIndex() == CLOUD_STYLE_VOLUMETRIC) {
                flags |= FEATURE_CLOUDS_VOLUMETRIC;
            }
        }
        // Reports what the pipeline is ACTUALLY doing, not just what the option asks for:
        // RtDlssRr.enabled() already folds in the backend switch, and a debug view suppresses RR
        // entirely (see recordFrame's rrPath), so a shader reading this flag learns whether its output
        // will be denoised rather than whether the player would like it to be.
        if (RtDlssRr.enabled() && debugView() == 0) {
            flags |= FEATURE_DENOISER;
        } else {
            if (RtNrdDenoiser.active() && debugView() == 0) {
                // Per-lobe signal capture only runs when NRD is the active denoiser: RR denoises
                // internally and SVGF works on the combined radiance, so capturing the split would
                // burn an extra shadow ray plus bandwidth for buffers nothing reads.
                flags |= FEATURE_NRD;
                flags |= FEATURE_VIEWZ;
            } else if (CausticaConfig.Rt.Denoise.ENABLED.value() && debugView() == 0) {
                // SVGF needs gViewZ for its geometry-validated reprojection and its sky cutoff.
                flags |= FEATURE_VIEWZ;
            }
        }
        return flags;
    }

    /**
     * Keep the SHaRC cache buffer matched to the current config, and honour an explicit "reset" action
     * from the options UI. Called every frame before the trace so a live toggle takes effect on the
     * next frame; the shader feature flag is keyed on {@link RtSharc#entryCount()} being non-zero so an
     * enabled toggle with no buffer (or a failed allocation) degrades to the normal tracer.
     */
    private void syncSharcResources(RtContext ctx) {
        boolean active = RtSharc.INSTANCE.enabled();
        if (active) {
            // Scene-change detection (dimension travel or world reload): same world coordinates,
            // different scene. Clear the cache rather than letting staleness evict it over
            // FRAME_LIFETIME frames of wrong light.
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                int dimension = dimensionId(level);
                if (sharcPrevLevel != null && (sharcPrevLevel != level || sharcPrevDimension != dimension)) {
                    CausticaMod.LOGGER.info("[SHaRC] scene changed; clearing radiance cache");
                    RtSharc.INSTANCE.requestClear();
                }
                sharcPrevLevel = level;
                sharcPrevDimension = dimension;
            }
            RtSharc.INSTANCE.ensure(ctx);
            if (RtSharc.INSTANCE.clearRequested()) {
                CausticaMod.LOGGER.info("[SHaRC] cache reset requested; clearing via vkCmdFillBuffer");
                // A reset is a rare menu action; idle the device briefly so the fill cannot race a
                // trace that still reads the old contents.
                ctx.waitIdle();
                RtSharc.INSTANCE.clearNow(ctx);
            }
            if (!sharcDebugWasActive) {
                sharcDebugWasActive = true;
                CausticaMod.LOGGER.info("[SHaRC] enabled: {}", sharcDebugDescription());
            }
            // With sharc.debug on, also report the active parameters periodically so a live tuning
            // change can be tracked without having to read the toml file.
            if (CausticaConfig.Rt.Sharc.DEBUG.value() && frameCounter - sharcDebugLastLogFrame >= 300) {
                sharcDebugLastLogFrame = frameCounter;
                CausticaMod.LOGGER.info("[SHaRC] active (frame {}): {}", frameCounter,
                        sharcDebugDescription());
            }
        } else {
            if (sharcDebugWasActive) {
                sharcDebugWasActive = false;
                CausticaMod.LOGGER.info("[SHaRC] disabled");
            }
            // Re-arm scene tracking: the cache is released on disable, so the next enable starts
            // from an empty buffer and must not log a spurious "scene changed" clear.
            sharcPrevLevel = null;
            RtSharc.INSTANCE.releaseIfDisabled(ctx);
        }
    }

    private static String sharcDebugDescription() {
        return "cell=" + CausticaConfig.Rt.Sharc.CELL_SIZE.value()
                + " blocks, entries=" + RtSharc.INSTANCE.entryCount()
                + ", coverage=" + CausticaConfig.Rt.Sharc.UPDATE_COVERAGE.value()
                + ", blend=" + CausticaConfig.Rt.Sharc.TEMPORAL_BLEND.value()
                + ", startBounce=" + CausticaConfig.Rt.Sharc.START_BOUNCE.value()
                + ", strength=" + CausticaConfig.Rt.Sharc.STRENGTH.value()
                + ", lifetime=" + CausticaConfig.Rt.Sharc.FRAME_LIFETIME.value()
                + ", normal=" + CausticaConfig.Rt.Sharc.NORMAL_THRESHOLD.value()
                + ", minSamples=" + CausticaConfig.Rt.Sharc.STABLE_FRAMES.value()
                + ", cacheAddr=0x" + Long.toHexString(RtSharc.INSTANCE.address());
    }

    private static long sharcCacheAddress() {
        return RtSharc.INSTANCE.address();
    }

    /**
     * WorldPush.sharcParams: x cell size, y strength (scale on the cached radiance that replaces a
     * vertex's path), z inverse temporal accumulation window (blend cap), w reserved (was max query
     * distance — a query position is always inside its own cell, so the limit could never fire).
     */
    private static Float4 sharcParams() {
        return new Float4(
                CausticaConfig.Rt.Sharc.CELL_SIZE.value(),
                CausticaConfig.Rt.Sharc.STRENGTH.value(),
                CausticaConfig.Rt.Sharc.TEMPORAL_BLEND.value(),
                CausticaConfig.Rt.Sharc.MAX_DISTANCE.value());
    }

    /** WorldPush.sharcParams2: x start bounce, y update coverage, z frame lifetime, w normal threshold. */
    private static Float4 sharcParams2() {
        return new Float4(
                CausticaConfig.Rt.Sharc.START_BOUNCE.value(),
                CausticaConfig.Rt.Sharc.UPDATE_COVERAGE.value(),
                CausticaConfig.Rt.Sharc.FRAME_LIFETIME.value(),
                CausticaConfig.Rt.Sharc.NORMAL_THRESHOLD.value());
    }

    /** WorldPush.sharcParams3: x = minimum sample count before an entry may be queried, y/z/w reserved. */
    private static Float4 sharcParams3() {
        return new Float4(CausticaConfig.Rt.Sharc.STABLE_FRAMES.value(), 0.0f, 0.0f, 0.0f);
    }

    /**
     * WorldPush.sharcGridOrigin: xyz = terrain origin block in WORLD coordinates (the same anchor
     * the water wave domain and NRD use), w = cache entry count. The shader adds the origin to the
     * rebased hit position to key the cache in absolute world coordinates, which are stable across
     * camera movement and terrain rebases — anything derived from the camera (hitPos - camOffset is
     * camera-relative) would smear entries across cells every frame, TAA jitter included.
     */
    private static Int4 sharcGridOrigin(RtTerrain terrain) {
        return new Int4(terrain.blockX, terrain.blockY, terrain.blockZ, RtSharc.INSTANCE.entryCount());
    }

    // Finite sun/moon angular sizes let NEE shadow rays sample the light disk (soft, contact-hardening
    // penumbrae). Radii in degrees; the real sun/moon are ~0.27°, but a touch larger reads pleasantly.
    private static final int WATER_ANCHOR_MASK = 4095;

    // ---- SVGF denoiser tuning (see RtSvgfDenoiser + shaders/display/svgf_*.comp).
    //
    // Accumulation window. Longer than the old TAA's 32 frames because history is now rejected on
    // GEOMETRY rather than thrown away on motion: a long window no longer means ghosting, it means
    // a converged image. The exponential tail after the 1/n phase still tracks lighting changes.
    private static final float SVGF_MAX_FRAMES = 48.0f;
    /**
     * Debug-view ids that inspect SVGF's internal state instead of the tracer's guides. Unlike the
     * guide views these leave the denoiser enabled, because the point is to see what it is doing.
     * 10 = history length (black means the reprojection threw the history away), 11 = variance,
     * 12 = luminance sigma (bright means the bilateral has degenerated into a box blur).
     */
    private static final int SVGF_DEBUG_FIRST = 10;
    private static final int SVGF_DEBUG_LAST = 12;
    // Luminance edge-stop, in estimated standard deviations. This is the knob that decides how much
    // the wavelet trusts its own variance estimate: 4 sigma filters the 1-spp signal hard while
    // still stopping at genuine luminance edges (SVGF's paper uses 4 as well).
    private static final float SVGF_PHI_LUMINANCE = 4.0f;
    // Normal edge-stop exponent, the standard SVGF value: cos^128 keeps block faces separate.
    private static final float SVGF_PHI_NORMAL = 128.0f;
    // Depth edge-stop, as a multiple of the local screen-space depth gradient. Scaling by the
    // gradient is what keeps slanted surfaces from reading as discontinuities.
    private static final float SVGF_PHI_DEPTH = 2.0f;
    // Matches the viewZ cap the tracer writes for sky/miss pixels: everything beyond is passed
    // through the denoise chain raw (the sky never accumulates history).
    private static final float NRD_DENOISING_RANGE = 500000.0f;
    // ---- Cloud deck. These mirror clouds.slang and must stay in lock-step with it.
    //
    // The classic field repeats every CLOUD_CELL_BLOCKS * CLOUD_PERIOD_CELLS = 12 * 512 = 6144 blocks,
    // but the VOLUMETRIC field samples the same hash at CLOUD_VOLUMETRIC_SCALE (0.5), so in its own
    // sampled space 6144 blocks is only half a period. Wrapping the anchor there landed mid-period and
    // snapped the entire cloudscape to a different pattern — clouds visibly changing shape while
    // walking, in the volumetric style only.
    //
    // The wrap must therefore be a whole period in EVERY space the field is sampled in: the base
    // octaves, the domain warp, and both billow layers. The binding constraint is the largest octave
    // divisor (CLOUD_WARP_DIV = 2.0 in clouds.slang):
    //
    //     period = 512 cells * 12 blocks/cell * maxDivisor(2.0) / scale(0.5) = 24576 blocks
    //
    // Every divisor there is a power of two, so all of these multiplies are exact in binary floating
    // point and the wrap identity holds bit-for-bit rather than approximately. Verified: the full
    // density function (base octaves + warp + billow) is now identical across a wrap to 0.0.
    private static final double CLOUD_FIELD_PERIOD_BLOCKS = 512.0 * 12.0 * 2.0 / 0.5;
    // Vanilla's clouds drift at 0.03 blocks/tick; matched so the sky moves at a familiar speed.
    private static final double CLOUD_WIND_BLOCKS_PER_TICK = 0.03;
    // Deck thickness at the slider's 100%. Both styles march a real slab now, so this is the depth the
    // clouds actually have in the world: at full thickness a bank is tall enough to fly into, while the
    // slider at 0 collapses the deck to the old flat plane.
    // Real cumulus is as tall as it is wide, often taller — a bank whose base sits at cloud height can
    // easily tower 100+ blocks. 40 was too shallow for the deck to ever read as heaped rather than
    // layered, and since extinction is now normalised by the slab depth (CLOUD_REFERENCE_THICKNESS in
    // clouds.slang) raising this adds VOLUME without making the clouds more opaque.
    private static final float CLOUD_MAX_THICKNESS_BLOCKS = 110.0f;
    // Classic boxes never get thinner than vanilla's own 4-block extrusion (CloudRenderer's
    // putVec3(12, 4, 12)): the thickness slider scales the box HEIGHT from that baseline up, per the
    // classic rework's "vanilla shapes, slider-driven depth" decision. Volumetric keeps the full
    // 0..110 range, including the flat-sheet collapse at zero.
    private static final float CLOUD_CLASSIC_MIN_THICKNESS = 4.0f;
    // Vanilla offsets the deck half a cell minus a sliver in Z (CloudRenderer.render: cameraZ + 3.96),
    // so the camera sits asymmetrically inside the cell grid. Matched for shape-parity with vanilla;
    // the x offset is the wind scroll itself.
    private static final double CLOUD_Z_OFFSET_BLOCKS = 3.96;
    // Mirrors clouds.slang's CLOUD_STYLE_* constants.
    private static final int CLOUD_STYLE_VOLUMETRIC = 1;
    // How far along the deck clouds remain visible. A plane extends to the horizon, where it degenerates
    // into an aliasing band; the shader fades coverage out over the last stretch of this distance.
    private static final float CLOUD_VIEW_LIMIT_BLOCKS = 3072.0f;
    // The view limit has to scale with how far up the deck is, or a high deck fades out at a steep
    // elevation: the fade is measured as horizontal distance, and looking 30 degrees up at a deck 1000
    // blocks overhead is already ~1750 blocks out. Keeping at least this many multiples of the deck's
    // height in view means the fade always stays down near the horizon where it belongs.
    private static final float CLOUD_VIEW_LIMIT_HEIGHT_MULTIPLE = 6.0f;

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
    private RtImage output;
    // Packed primary -> indirect continuations. Pass A is fixed at one sample and owns two records per
    // render pixel (base + optional transmission); Pass B resamples them at the configured SPP.
    private RtBuffer continuationQueue;
    // ReSTIR DI/GI history is a strict two-buffer ping-pong: a dispatch reads only `previous` and writes
    // only `current`, so spatial neighbour reuse never races another raygen invocation. The pair exists
    // only while the player setting is ON; live toggles idle the device before destruction/allocation.
    private final RtBuffer[] restirReservoirs = new RtBuffer[2];
    private int restirWriteIndex;
    private boolean restirResourcesEnabled;
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
    // SDR FG backbuffer copy: Minecraft's main target arrives in TRANSFER_SRC layout (MC's own
    // blit barrier ran first in the encoder) with no contractually known format, but the FFX FG
    // GENERATE reads its presentColor as GENERAL with the declared format taken literally — a
    // mismatch on either axis is undefined reads (the flickering generated frames). Blitting into
    // an image we own makes both the layout (GENERAL) and the format (RGBA8) certain.
    private RtImage fgBackbufferCopy;
    private int fgBackbufferCopyW = -1;
    private int fgBackbufferCopyH = -1;
    private boolean fgReset = true;
    // Caustica native frame generation: the motion-vector interpolation pipeline plus the previous
    // presented frame it interpolates from. fgPrevFrame holds last tick's final image (same
    // format/size as fgBackbufferCopy / the HDR backbuffer); the current frame is copied into it
    // after each interpolation dispatch so the next tick can blend. fgPrevFrameValid is false until
    // the first successful capture (and after any resize), which makes that tick fall back to
    // duplicating the real frame instead of blending against garbage.
    private RtNativeFrameGenPipeline nativeFgPipeline;
    private boolean nativeFgFailed;
    // SDR UI re-composite for native FG's generated frames (HDR reuses hdrCompositePipeline).
    private RtFgUiCompositePipeline fgUiCompositePipeline;
    private boolean fgUiCompositeFailed;
    // frameCounter of the last native-FG interpolation; a gap (> 2 composite frames) means the
    // stored previous frame no longer neighbours the current one (menu/loading/toggle gap) and
    // must be dropped before blending. -1 = never ran.
    private long fgNativeLastUseFrame = -1;
    private RtImage fgPrevFrame;
    private int fgPrevFrameW = -1;
    private int fgPrevFrameH = -1;
    private int fgPrevFrameFormat = Integer.MIN_VALUE;
    private boolean fgPrevFrameValid;
    private final Matrix4f fgClipToPrev = new Matrix4f();
    private final Matrix4f fgPrevToClip = new Matrix4f();
    private final Matrix4f fgMatTmp = new Matrix4f();
    // Jitter applied to this frame's trace (signed, render pixels) — the FG PREPARE input wants the
    // same sub-pixel offset the camera rays used; captured in recordFrame, read at present time.
    private float fgJitterX;
    private float fgJitterY;
    // Guide buffers (first-hit attributes for DLSS-RR): normal+roughness, albedo, depth, motion,
    // specular albedo, and reflection motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    private RtImage gSpecMotion;
    // Primary-hit linear view depth (the denoisers' sky cutoff + NRD's IN_VIEWZ), plus the tracer's
    // per-lobe NRD signals (demodulated YCoCg radiance + normalized hit distance). The lobe images
    // are written only under FEATURE_NRD, but their bindings always exist in the pipeline layout.
    private RtImage gViewZ;
    private RtImage gNrdDiff;
    private RtImage gNrdSpec;
    // ---- SVGF (the renderer's own denoiser for every non-DLSS path).
    //
    // colour/history ping-pong (rgb = colour, a = accumulated frame count), the luminance-moment
    // ping-pong feeding the variance estimate, and the à-trous ping-pong (rgb = colour,
    // a = variance). The reprojection also needs LAST frame's geometry to validate history against,
    // which is what the prev-guide copies hold.
    private RtImage svgfHistoryPing;
    private RtImage svgfHistoryPong;
    private RtImage svgfMomentsPing;
    private RtImage svgfMomentsPong;
    private RtImage svgfFilterPing;
    private RtImage svgfFilterPong;
    private RtImage svgfPrevViewZ;
    private RtImage svgfPrevNormal;
    private boolean svgfWriteToPing;
    private boolean svgfHasHistory;
    private double svgfPrevCamX;
    private double svgfPrevCamY;
    private double svgfPrevCamZ;
    private RtSvgfDenoiser svgfDenoiser;
    /** Sky-mask pass over FSR FG's generated frames (see RtFgSkyMaskPipeline); created lazily. */
    private RtFgSkyMaskPipeline fgSkyMaskPipeline;
    private boolean renderSizeSvgfEnabled;
    // NRD/REBLUR: the denoiser's own input/output pair + the combined (decoded + summed) radiance
    // the upscale stage consumes, plus the validation overlay target and the combine pipeline.
    private RtImage nrdDiffOut;
    private RtImage nrdSpecOut;
    private RtImage nrdCombined;
    private RtImage nrdValidation;
    private RtNrdCombinePipeline nrdCombinePipeline;
    private boolean renderSizeNrdEnabled;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;
    private final RtExposure exposure = new RtExposure();
    // Experimental SHaRC (Spatially Hashed Radiance Cache). Shader-only — the host only owns the
    // persistent cache buffer and publishes its device address (no native lib, no extra binding).
    private final RtSharc sharc = RtSharc.INSTANCE;
    // Debug-state tracking for the SHaRC console logging (enable/disable + periodic summary).
    private boolean sharcDebugWasActive;
    private long sharcDebugLastLogFrame;
    // Scene identity the SHaRC cache was last warmed in. The cache is keyed by world coordinates,
    // which repeat across dimensions and world reloads, so a scene change must drop it (NVIDIA's
    // integration checklist: clear cache resources on scene reload) instead of leaking up to
    // FRAME_LIFETIME frames of the old scene's light into the new one.
    private ClientLevel sharcPrevLevel;
    private int sharcPrevDimension;

    // Trace + guide buffers run at render res; composite (display-mapping) runs at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;
    // What ensureOutput last sized the render/guide images for, so a quality change (or RR being
    // toggled) at a fixed window size is noticed even though displayW/displayH didn't change.
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;
    // FSR 3 occupies the same upscale slot as RR (never both): its state joins the render-size key
    // so switching upscaler or FSR quality rebuilds the trace targets exactly like RR does.
    private boolean renderSizeFsrEnabled;
    private int renderSizeFsrQuality = Integer.MIN_VALUE;
    // XeSS shares that same slot (never with RR nor FSR): same keying contract.
    private boolean renderSizeXessEnabled;
    private int renderSizeXessQuality = Integer.MIN_VALUE;

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

    // Previous captured camera position, for the FSR discontinuity reset (teleport / respawn /
    // world change jumps FSR's reprojection history cannot survive).
    private double prevFsrCamX;
    private double prevFsrCamY;
    private double prevFsrCamZ;
    private boolean fsrCamValid;

    // Same discontinuity bookkeeping for XeSS (its temporal history is equally jump-fragile).
    private double prevXessCamX;
    private double prevXessCamY;
    private double prevXessCamZ;
    private boolean xessCamValid;

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
        var encoder = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice()
                .createCommandEncoder()).backend();
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
            syncSharcResources(ctx);
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
                    pushRing[i] = new PushSlot(ctx.createBuffer(WORLD_PUSH_BUFFER_SIZE,
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
        // NRD signals: always bound (the layout carries them); only written under FEATURE_NRD.
        worldPipeline.setExtraStorageImage(6, gViewZ.view);
        worldPipeline.setExtraStorageImage(7, gNrdDiff.view);
        worldPipeline.setExtraStorageImage(8, gNrdSpec.view);
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
        if (gViewZ != null) {
            gViewZ.destroy();
            gViewZ = null;
        }
        if (gNrdDiff != null) {
            gNrdDiff.destroy();
            gNrdDiff = null;
        }
        if (gNrdSpec != null) {
            gNrdSpec.destroy();
            gNrdSpec = null;
        }
        if (svgfHistoryPing != null) {
            svgfHistoryPing.destroy();
            svgfHistoryPing = null;
        }
        if (svgfHistoryPong != null) {
            svgfHistoryPong.destroy();
            svgfHistoryPong = null;
        }
        if (svgfMomentsPing != null) {
            svgfMomentsPing.destroy();
            svgfMomentsPing = null;
        }
        if (svgfMomentsPong != null) {
            svgfMomentsPong.destroy();
            svgfMomentsPong = null;
        }
        if (svgfFilterPing != null) {
            svgfFilterPing.destroy();
            svgfFilterPing = null;
        }
        if (svgfFilterPong != null) {
            svgfFilterPong.destroy();
            svgfFilterPong = null;
        }
        if (svgfPrevViewZ != null) {
            svgfPrevViewZ.destroy();
            svgfPrevViewZ = null;
        }
        if (svgfPrevNormal != null) {
            svgfPrevNormal.destroy();
            svgfPrevNormal = null;
        }
        if (nrdDiffOut != null) {
            nrdDiffOut.destroy();
            nrdDiffOut = null;
        }
        if (nrdSpecOut != null) {
            nrdSpecOut.destroy();
            nrdSpecOut = null;
        }
        if (nrdCombined != null) {
            nrdCombined.destroy();
            nrdCombined = null;
        }
        if (nrdValidation != null) {
            nrdValidation.destroy();
            nrdValidation = null;
        }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
    }

    /**
     * Match the persistent ReSTIR allocation to the live player toggle. A state transition is deliberately
     * synchronous and rare: waiting idle first makes it impossible for a recorded BDA load to outlive the
     * buffers, then both ping-pong halves are either destroyed or freshly allocated and zero-filled. Thus
     * OFF releases the VRAM (rather than merely hiding it), and ON can never observe stale reservoirs.
     */
    private void syncRestirResources(RtContext ctx) {
        boolean desired = CausticaConfig.Rt.Lights.RESTIR_SAMPLING.value()
                && CausticaConfig.Rt.Lights.RIS_CANDIDATES.value() > 0
                && renderW > 0 && renderH > 0;
        boolean completePair = restirReservoirs[0] != null && restirReservoirs[1] != null;
        if (desired == restirResourcesEnabled && desired == completePair) {
            return;
        }

        ctx.waitIdle();
        destroyRestirResources();
        if (!desired) {
            return;
        }

        long pixels = Math.multiplyExact((long) renderW, (long) renderH);
        long bytes = Math.multiplyExact(pixels, RESTIR_RECORD_BYTES);
        int usage = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        try {
            restirReservoirs[0] = ctx.createBuffer(bytes, usage, false,
                    "ReSTIR reservoir history A " + renderW + "x" + renderH);
            restirReservoirs[1] = ctx.createBuffer(bytes, usage, false,
                    "ReSTIR reservoir history B " + renderW + "x" + renderH);
            restirWriteIndex = 0;
            ctx.submitSync(cmd -> {
                VK10.vkCmdFillBuffer(cmd, restirReservoirs[0].handle, 0L, bytes, 0);
                VK10.vkCmdFillBuffer(cmd, restirReservoirs[1].handle, 0L, bytes, 0);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                }
            });
            restirResourcesEnabled = true;
        } catch (Throwable failure) {
            destroyRestirResources();
            throw failure;
        }
    }

    private void destroyRestirResources() {
        for (int i = 0; i < restirReservoirs.length; i++) {
            if (restirReservoirs[i] != null) {
                restirReservoirs[i].destroy();
                restirReservoirs[i] = null;
            }
        }
        restirWriteIndex = 0;
        restirResourcesEnabled = false;
    }

    private long restirPreviousAddress() {
        return restirResourcesEnabled ? restirReservoirs[restirWriteIndex ^ 1].deviceAddress : 0L;
    }

    private long restirCurrentAddress() {
        return restirResourcesEnabled ? restirReservoirs[restirWriteIndex].deviceAddress : 0L;
    }

    /** Explicit shader mode uniform; unlike the descriptive feature bit this is tied to real bindings. */
    private int restirMode() {
        return restirResourcesEnabled && CausticaConfig.Rt.Lights.RESTIR_SAMPLING.value() ? 1 : 0;
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        boolean rrEnabled = RtDlssRr.enabled();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        // FSR 3 only takes the upscale slot when RR is not running (the selector makes them
        // mutually exclusive, but a hand-edited config could enable both — RR wins).
        boolean fsrEnabled = !rrEnabled && RtFsrUpscaler.enabled();
        int fsrQuality = fsrEnabled ? RtFsrUpscaler.quality() : Integer.MIN_VALUE;
        // XeSS shares the slot under the same rules; if a hand-edit stacks them, RR > FSR > XeSS.
        boolean xessEnabled = !rrEnabled && !fsrEnabled && RtXessUpscaler.enabled();
        int xessQuality = xessEnabled ? RtXessUpscaler.quality() : Integer.MIN_VALUE;
        // The denoise slot. Exactly one denoiser ever runs on a frame, in this order:
        //   DLSS-RR (denoises internally, so nothing else may touch the image)
        //   > NRD/REBLUR (opt-in, needs bundled natives)
        //   > SVGF (the renderer's own; the default for every non-DLSS path).
        // Two temporal denoisers in series would fight over the same history and reintroduce exactly
        // the ghosting this rework removes, so they are strictly exclusive.
        // RtNrdDenoiser.active() rather than enabled(): if the NRD integration has latched off after
        // a failure, the slot goes back to SVGF, so SVGF's targets have to exist. Keying the
        // allocation on the option alone left BOTH denoisers inert on a failure, which is why
        // toggling NRD appeared to do nothing at all.
        boolean nrdEnabled = !rrEnabled && RtNrdDenoiser.active();
        boolean svgfEnabled = !rrEnabled && !nrdEnabled && CausticaConfig.Rt.Denoise.ENABLED.value();
        if (output != null && continuationQueue != null
                && displayImage != null && hdrDisplayImage != null && rrOutput != null && exposure.ready()
                && displayW == width && displayH == height
                && renderSizeRrEnabled == rrEnabled && renderSizeRrQuality == rrQuality
                && renderSizeFsrEnabled == fsrEnabled && renderSizeFsrQuality == fsrQuality
                && renderSizeXessEnabled == xessEnabled && renderSizeXessQuality == xessQuality
                && renderSizeSvgfEnabled == svgfEnabled
                && renderSizeNrdEnabled == nrdEnabled) {
            syncRestirResources(ctx);
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        // Reaching here with RR off can mean the denoising filter was just turned off. Nothing calls
        // ensureFeature again in that state, so the RR feature (and its history buffers) would stay
        // allocated for the rest of the session; the device is idle right now, so release it here.
        RtDlssRr.INSTANCE.releaseIfDisabled();
        // Same reasoning for the FSR context (its history textures) when the upscaler switches away.
        RtFsrUpscaler.INSTANCE.releaseIfDisabled();
        // And for the XeSS upscaler (pipelines + history) on the same switch-away event.
        RtXessUpscaler.INSTANCE.releaseIfDisabled();
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
        destroyRestirResources();
        destroyGuideImages();

        displayW = width;
        displayH = height;
        // The path tracer + its guide buffers run at render res; the active upscaler — DLSS-RR
        // (denoise + upscale) or FSR 3 (upscale only) — or a fallback blit brings the image to
        // display res. With neither active there is no reconstruction pass, so trace at 1:1 for a
        // faithful reference. With one active, ask IT what render resolution its chosen quality
        // mode actually expects rather than assuming a fixed ratio: different quality modes (and
        // driver/SDK versions) use different ratios, and each upscaler's own query is the source
        // of truth for what its dispatch will accept.
        int[] optimal;
        if (rrEnabled) {
            optimal = RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height);
        } else if (fsrEnabled) {
            optimal = RtFsrUpscaler.INSTANCE.queryRenderSize(width, height);
        } else if (xessEnabled) {
            optimal = RtXessUpscaler.INSTANCE.queryRenderSize(width, height);
        } else {
            optimal = null;
        }
        renderW = optimal != null ? optimal[0] : width;
        renderH = optimal != null ? optimal[1] : height;
        renderSizeRrEnabled = rrEnabled;
        renderSizeRrQuality = rrQuality;
        renderSizeFsrEnabled = fsrEnabled;
        renderSizeFsrQuality = fsrQuality;
        renderSizeXessEnabled = xessEnabled;
        renderSizeXessQuality = xessQuality;
        renderSizeSvgfEnabled = svgfEnabled;
        renderSizeNrdEnabled = nrdEnabled;

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
        syncRestirResources(ctx);
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
        // gViewZ is live on every denoised path (SVGF's sky cutoff and NRD's IN_VIEWZ). The per-lobe
        // signal images are bound unconditionally because the world pipeline's descriptor layout
        // carries their bindings, but the tracer only writes them under FEATURE_NRD.
        gViewZ = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "nrd viewZ " + renderW + "x" + renderH);
        gNrdDiff = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "nrd diffuse radiance+hitdist " + renderW + "x" + renderH);
        gNrdSpec = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "nrd specular radiance+hitdist " + renderW + "x" + renderH);
        // SVGF working set: colour/frame-count history, luminance moments, and the à-trous
        // ping-pong (whose alpha carries variance), plus copies of last frame's depth/normal guides
        // so the reprojection can validate history against the geometry it came from.
        if (svgfEnabled) {
            svgfHistoryPing = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf history ping " + renderW + "x" + renderH);
            svgfHistoryPong = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf history pong " + renderW + "x" + renderH);
            // rgba16f, not rg16f: the moments texture also carries the accumulated frame count
            // (see svgf_reproject.comp — it cannot live in the history's alpha, which the à-trous
            // feedback copy overwrites).
            svgfMomentsPing = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf moments ping " + renderW + "x" + renderH);
            svgfMomentsPong = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf moments pong " + renderW + "x" + renderH);
            svgfFilterPing = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf filter ping " + renderW + "x" + renderH);
            svgfFilterPong = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf filter pong " + renderW + "x" + renderH);
            svgfPrevViewZ = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT, "svgf prev viewZ " + renderW + "x" + renderH);
            svgfPrevNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "svgf prev normal " + renderW + "x" + renderH);
            if (svgfDenoiser == null) {
                svgfDenoiser = RtSvgfDenoiser.create(ctx);
                CausticaMod.LOGGER.info("SVGF denoiser active ({} a-trous passes, {} frame window)",
                        RtSvgfDenoiser.ATROUS_PASSES, (int) SVGF_MAX_FRAMES);
            } else {
                // The images above are new. A recycled view handle can compare equal to the one that
                // was just destroyed, so the descriptor cache must be dropped rather than trusted.
                svgfDenoiser.invalidateBindings();
            }
            // Fresh buffers hold nothing the reprojection may read.
            svgfHasHistory = false;
            svgfWriteToPing = true;
        }
        // Denoiser outputs + the decoded/summed image the upscale stage consumes exist only while
        // NRD actually runs; the combine pipeline is created lazily with them.
        if (nrdEnabled) {
            nrdDiffOut = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "nrd denoised diffuse " + renderW + "x" + renderH);
            nrdSpecOut = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "nrd denoised specular " + renderW + "x" + renderH);
            nrdCombined = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "nrd combined radiance " + renderW + "x" + renderH);
            // Allocated unconditionally (cheap RGBA8) so toggling nrdValidation live needs no rebuild;
            // REBLUR only writes it when the validation flag is set.
            nrdValidation = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R8G8B8A8_UNORM, "nrd validation overlay " + renderW + "x" + renderH);
            if (nrdCombinePipeline == null) {
                nrdCombinePipeline = RtNrdCombinePipeline.create(ctx);
            } else {
                nrdCombinePipeline.invalidateBindings(); // same recycled-handle hazard as SVGF above
            }
            // Re-modulation reads the same guides the tracer demodulated with (see nrd_combine.comp),
            // and the raw trace supplies the sky, which REBLUR does not denoise.
            nrdCombinePipeline.setImages(nrdDiffOut.view, nrdSpecOut.view, nrdCombined.view,
                    output.view, gAlbedo.view, gViewZ.view, gSpecAlbedo.view, gNormal.view);
            // NRD's own temporal history cannot survive a resolution change either.
            RtNrdDenoiser.INSTANCE.resetHistory();
        }
        // Display-res RT image the display mapper reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "DLSS-RR output " + width + "x" + height);
        exposure.ensureResources(ctx);

        mvHasPrev = false; // recreated images -> first MV frame is zero
        waterWaveTimeValid = false;
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view, hdrDisplayImage.view);
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
        var encoder = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
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
            // The active upscaler drives the frame: trace + jitter at render res, then DLSS-RR
            // (denoise+upscale) or FSR 3 (upscale only) brings it to display res. They occupy one
            // slot — RR wins if both are somehow on — and jitter is suppressed for the no-upscaler
            // reference and for the debug guide views (raw inspection).
            boolean rrPath = RtDlssRr.enabled() && debugView == 0;
            boolean fsrPath = !rrPath && RtFsrUpscaler.enabled() && debugView == 0;
            boolean xessPath = !rrPath && !fsrPath && RtXessUpscaler.enabled() && debugView == 0;
            // The denoise slot, in the same priority order ensureOutput allocated for:
            // RR > NRD > SVGF. Both denoisers want a jittered trace — their temporal stage
            // integrates the sub-pixel sequence, which is what resolves detail below the pixel grid
            // and lets the upscaler reconstruct it — and both are told the exact jitter used.
            boolean nrdPath = !rrPath && RtNrdDenoiser.active() && debugView == 0;
            // SVGF is the fallback as well as the primary: if NRD is selected but its denoise call
            // fails this frame, the gate below (svgfPath && !nrdDone) lets SVGF take the slot
            // instead of presenting the raw trace. Its resources are allocated whenever
            // RtNrdDenoiser.active() is false, which includes the latched-off case.
            // Debug views 10-12 inspect the SVGF denoiser's own internal state (history length,
            // variance, luminance sigma), so unlike the guide views they must keep it RUNNING.
            // They exist because four rounds of fixes reasoned from the source produced no visible
            // change for the user; the filter's state has to be measured in the actual frame.
            boolean svgfDebugView = debugView >= SVGF_DEBUG_FIRST && debugView <= SVGF_DEBUG_LAST;
            boolean svgfPath = !rrPath && CausticaConfig.Rt.Denoise.ENABLED.value()
                    && (debugView == 0 || svgfDebugView);
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                CausticaJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            } else if (fsrPath) {
                // Same Halton(2,3) sequence, FSR 3's own phase-count rule (see CausticaJitter).
                CausticaJitter.INSTANCE.prepareFsr(renderW, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            } else if (xessPath) {
                // Same Halton(2,3) sequence, Intel's fixed 32-phase cycle (see CausticaJitter).
                CausticaJitter.INSTANCE.prepareXess();
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            } else if (nrdPath || svgfPath) {
                // A denoiser with no upscaler: still jitter, because the denoiser's temporal stage
                // integrates the sequence into sub-pixel detail. FSR's phase-count rule is the
                // renderer's long-standing default for this case.
                CausticaJitter.INSTANCE.prepareFsr(renderW, displayW);
                jitterX = CausticaJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = CausticaJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }
            // FG reads the frame's jitter at present time (PREPARE wants the offset the rays used).
            fgJitterX = jitterX;
            fgJitterY = jitterY;

            boolean rrDone = false;
            // Optional coarse LOD proxy (Distant Horizons / Voxy). A no-op when neither mod is present.
            RtDistantHorizonsTerrain.INSTANCE.frame(ctx, terrain.blockX, terrain.blockY, terrain.blockZ);
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including entity addresses and block-breaking entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            PushSlot selectedPushSlot = pushRing[pushSlot];
            graphicsUseWaiter.await(selectedPushSlot.graphicsUse);
            selectedPushSlot.graphicsUse.mark(graphicsUse);
            RtBuffer pushBuf = selectedPushSlot.buffer;
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            // Exact per-section vanilla-readiness hand-off mask for world.rahit's DH/Voxy suppression.
            ByteBuffer readyMask = MemoryUtil.memByteBuffer(
                    pushBuf.mapped + READY_MASK_OFFSET, READY_MASK_CAPACITY)
                    .order(ByteOrder.nativeOrder());
            int readyMaskBytes = RtTerrain.writeDistantReadyMask(readyMask);
            long readyMaskAddress = readyMaskBytes == 0
                    ? 0L : pushBuf.deviceAddress + READY_MASK_OFFSET;
            // Vanilla's authored cloud shape for the classic deck (RtCloudCells), re-published into
            // whichever ring slot this frame uses. 8 KiB of words — the copy is rounding error next to
            // the push itself; doing it every frame keeps all six slots valid instead of tracking which
            // slot last received the map. Address 0 when no usable clouds.png exists, which the shader
            // reads as "fall back to the noise deck" — a resource pack can never remove the clouds.
            int[] cloudCells = RtCloudCells.INSTANCE.cells();
            long cloudCellsAddress = 0L;
            if (cloudCells != null) {
                ByteBuffer cellsBuf = MemoryUtil.memByteBuffer(
                        pushBuf.mapped + CLOUD_CELLS_OFFSET, RtCloudCells.MAP_BYTES)
                        .order(ByteOrder.nativeOrder());
                cellsBuf.asIntBuffer().put(cloudCells, 0, RtCloudCells.MAP_WORDS);
                cloudCellsAddress = pushBuf.deviceAddress + CLOUD_CELLS_OFFSET;
            }
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            // flags: camera-in-water (so the path tracer starts in the water medium when the eye is
            // submerged, fixing the air→water first-segment orientation) + W1 geometric waves. Bit 1 used to
            // gate a Lambertian fallback BRDF that nothing ever turned off; the GGX path is unconditional
            // now, so that bit is unused rather than reassigned, to avoid a stale reader elsewhere.
            // This word describes the frame's PHYSICAL state; player-facing effect toggles live in the
            // separate featureFlags word (see featureFlags()).
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
                flags |= 0b10000; // W1: animated geometric water waves
            }
            if (CausticaConfig.Rt.Composite.PARALLAX_SMOOTHING.value()) {
                flags |= 0b100000; // bit5: bilinear LabPBR normal/surface sampling (POM columns stay texel-exact)
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
            var staticInstances = RtDistantHorizonsTerrain.INSTANCE.appendInstances(
                    terrain.staticInstances(), terrain.blockX, terrain.blockY, terrain.blockZ);
            RtEntities.FrameEntities fe = RtEntities.INSTANCE.beginFrame(ctx, staticInstances,
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ, frameProjection, frameViewRotation);
            frameEntities = fe;
            // Block-breaking overlay: resolves each destroy-stage RenderType's texture into the
            // SAME bindless entity-texture array (destroy_stage_N.png is a standalone Sampler0 texture,
            // not a block-atlas sprite — see ModelBakery.BREAKING_LOCATIONS/DESTROY_TYPES), so any newly
            // resolved slot rides along with the uploadPending() call right below.
            BreakEntry[] breaking = breakingEntries(terrain);
            // Dimension + weather drive the sky model and the celestial light, so both are resolved
            // together, once, from the same level and partial tick.
            int dimension = dimensionId(level);
            WeatherState weather = weatherState(level,
                    Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
            SkyPush sky = skyPush(dimension, weather);
            // Two lanes, resolved together from the same weather + camera state the sky above used.
            CloudPush clouds = cloudState(dimension, weather, camY);
            // Analytic held-item light: position + intensity lane and the item's RGB tint; w == 0
            // disables the shader term (toggle off, no luminous item, or no player).
            HandLightState hand = handLightState(terrain);
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
                    new Float4(CausticaConfig.Rt.Lights.BLOCK_INTENSITY.value(),
                            CausticaConfig.Rt.Lights.DYNAMIC_INTENSITY.value(),
                            0.0f, 0.0f),
                    new Float4(weather.rain(), weather.thunder(), weather.skyDarken(),
                            weather.lightAttenuation()),
                    clouds.clouds(),
                    clouds.anchor(),
                    clouds.color(),
                    cloudCellsAddress,
                    // Shader-only POM: x relief depth (blocks), y max texel crossings, w fade distance.
                    parallaxParams(),
                    dimension,
                    featureFlags(),
                    // Analytic held-item light: xyz rebased position, w intensity (0 = none held),
                    // then the item's RGB tint lane.
                    hand.light(),
                    hand.color(),
                    // Water lanes (WorldPush.waterOpacity): x = extra neutral per-block extinction
                    // scale (0 = default clarity); y/z/w = live Animated Water tuning the spectrum
                    // multiplies in (height scale, speed scale, wave count) — 1/1/7 = authored look.
                    new Float4(CausticaConfig.Rt.Composite.WATER_OPACITY.value(),
                            CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH.value(),
                            CausticaConfig.Rt.Composite.WATER_WAVE_SPEED.value(),
                            (float) CausticaConfig.Rt.Composite.WATER_WAVE_DETAIL.value()),
                    // Material appearance lane: x is the optional metallic polish amount. It is read
                    // every frame so dragging the slider needs neither a material rebuild nor reload.
                    new Float4(CausticaConfig.Rt.Composite.METALLIC_SHININESS.value(), 0.0f, 0.0f, 0.0f),
                    // Experimental SHaRC lanes (see sharcParams/sharcParams2/sharcParams3/sharcGridOrigin):
                    // the cache buffer address plus the world-space caching and tuning parameters the
                    // shader reads.
                    sharcCacheAddress(),
                    sharcParams(),
                    sharcParams2(),
                    sharcParams3(),
                    sharcGridOrigin(terrain),
                    // Trailing field (WorldPush.restirTuning): the live ReSTIR anti-flicker knobs
                    // lighting.slang resolves against its compiled RESTIR_* caps.
                    new Int4(CausticaConfig.Rt.Lights.RESTIR_TEMPORAL_HISTORY.value(),
                            CausticaConfig.Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS.value(),
                            CausticaConfig.Rt.Lights.RESTIR_MAX_AGE.value(), 0),
                    // Volumetric fog (WorldPush.fogParams): density lane zero when the toggle is
                    // off, so "off" costs the shader one comparison — see fogParams() above.
                    fogParams(),
                    // Biome/weather tint for the fog's scatter (WorldPush.fogTint): the game's
                    // own FOG_COLOR attribute, blended by the slider — see fogTint() above.
                    fogTint()
            ).write(push);
            int flushBytes = Math.max(WORLD_PUSH_SIZE, READY_MASK_OFFSET + readyMaskBytes);
            if (cloudCellsAddress != 0L) {
                flushBytes = Math.max(flushBytes, CLOUD_CELLS_OFFSET + RtCloudCells.MAP_BYTES);
            }
            pushBuf.flush(0L, flushBytes);
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
                    RtDistantHorizonsTerrain.INSTANCE.tableAddress(), readyMaskAddress,
                    RtMaterialRegistry.INSTANCE.tableAddress(),
                    terrain.lightBufferAddress(), terrain.lightAliasBufferAddress(),
                    terrain.lightLocalAliasBufferAddress(), terrain.lightGridCellBufferAddress(),
                    terrain.lightGridSpanBufferAddress(), continuationQueue.deviceAddress,
                    restirPreviousAddress(), restirCurrentAddress(),
                    // The SVGF debug ids are consumed by the denoiser, not the tracer: forwarding
                    // them would make the raygen paint a guide overlay over the very image we are
                    // trying to inspect. The tracer sees 0 (normal shading) for those.
                    (int) frameCounter, svgfDebugView ? 0 : debugView,
                    terrain.lightGeneration(), restirMode()).write(pushConstants);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world primary trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.tracePrimary")) {
                active.trace(cmd, renderW, renderH, pushConstants, 0);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // continuation/guide writes visible to pass B
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world indirect trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.traceIndirect")) {
                active.trace(cmd, renderW, renderH, pushConstants, 1);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to the temporal/upscale reads
            // A FOV change does NOT need to restart accumulation, so nothing here does.
            //
            // The history is fetched through the motion vectors, and the tracer builds those with
            // prevViewProj -- the PREVIOUS frame's projection, carrying the previous frame's FOV.
            // A zoom therefore appears in the motion vector as the on-screen displacement it
            // actually is (a 1 degree step moves an edge pixel 5.9 px, a 7 degree step 38 px), the
            // bilinear fetch follows it, and the geometry gate validates the result. There is no
            // stale-projection error left for a reset to protect against.
            //
            // Resetting instead cost the whole screen at once: every pixel dropped from the
            // 48-frame window (14% residual noise) to a single sample (100%), and vanilla eases
            // the sprint FOV over about three frames, so it fired three times in a row. That flash
            // is what remained when starting/stopping a sprint and when toggling flight, after the
            // bob-invariant test correctly stopped firing during steady movement.
            //
            // Two real discontinuities still restart it, and they are handled where they arise
            // rather than by inspecting the matrix: the first frame after (re)allocation, via
            // svgfHasHistory below, and a terrain rebase, which RtNrdDenoiser compensates against
            // the anchor. Resolution changes reallocate, which takes the same path.

            // ---- NRD / REBLUR (opt-in). Consumes the tracer's demodulated per-lobe signals plus
            // the guides at render res; the combine pass re-modulates and sums the denoised pair
            // into nrdCombined. When it runs it owns the denoise slot: SVGF steps aside below,
            // because two temporal denoisers in series fight over the same history.
            boolean nrdDone = false;
            RtImage denoisedSource = null;
            if (nrdPath && gViewZ != null && nrdDiffOut != null) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "NRD denoise");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.nrd")) {
                    // The camera goes in as ABSOLUTE world coordinates plus the terrain anchor the
                    // signals live in. That pair is what lets the denoiser compensate a rebase
                    // instead of seeing it as a teleport (see RtNrdDenoiser): the old code passed
                    // only anchor-relative coordinates, so every rebase silently invalidated
                    // REBLUR's history mid-motion. No FOV-driven restart is passed: the motion
                    // vectors already carry a zoom as screen displacement (see the SVGF path).
                    nrdDone = RtNrdDenoiser.INSTANCE.denoise(cmd.address(), renderW, renderH,
                            gMotion, gNormal, gViewZ, gNrdDiff, gNrdSpec, nrdDiffOut, nrdSpecOut,
                            nrdValidation,
                            frameProjection, frameViewRotation,
                            camX, camY, camZ,
                            terrain.blockX, terrain.blockY, terrain.blockZ,
                            jitterX, jitterY, (int) frameCounter, false);
                }
                if (nrdDone) {
                    VulkanCommandEncoder.memoryBarrier(cmd, stack); // NRD outputs visible to the combine
                    try (RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.nrdCombine")) {
                        nrdCombinePipeline.dispatch(cmd, renderW, renderH, NRD_DENOISING_RANGE);
                    }
                    VulkanCommandEncoder.memoryBarrier(cmd, stack); // combine output visible downstream
                    denoisedSource = nrdCombined;
                }
            }

            // Validation mode: REBLUR's 16-viewport diagnostic overlay replaces the image (set the
            // upscaler to Off for a crisp readout). Nothing downstream may filter the overlay.
            boolean nrdValidationOn = nrdDone && CausticaConfig.Rt.Nrd.VALIDATION.value();
            if (nrdValidationOn) {
                denoisedSource = nrdValidation;
            }

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

            // Whatever the upscale stage will read. The denoisers replace the raw trace here.
            RtImage upscaleSource = denoisedSource != null ? denoisedSource : output;

            // ---- SVGF: the renderer's own denoiser (temporal reprojection with luminance moments,
            // then a variance-guided à-trous cascade). Runs on every non-DLSS path unless NRD took
            // the slot. Its temporal stage integrates the jitter sequence, so — exactly like the
            // stack it replaces — the upscaler downstream is told the input is already converged.
            boolean svgfRan = false;
            if (svgfPath && !nrdDone && !nrdValidationOn
                    && svgfDenoiser != null && svgfHistoryPing != null && gViewZ != null) {
                int svgfParity = svgfWriteToPing ? 0 : 1;
                RtImage historyIn = svgfWriteToPing ? svgfHistoryPong : svgfHistoryPing;
                RtImage historyOut = svgfWriteToPing ? svgfHistoryPing : svgfHistoryPong;
                RtImage momentsIn = svgfWriteToPing ? svgfMomentsPong : svgfMomentsPing;
                RtImage momentsOut = svgfWriteToPing ? svgfMomentsPing : svgfMomentsPong;
                // Only a genuine absence of history restarts accumulation: the first frame after
                // (re)allocation, which includes a resolution change. Camera movement does not,
                // and neither does an FOV change -- the motion vectors are built against the
                // previous frame's projection, so a zoom arrives as ordinary screen displacement
                // that the reprojection follows and the geometry gate validates.
                boolean svgfReset = !svgfHasHistory;
                // How far the camera travelled ALONG THE VIEW AXIS since the previous frame. The
                // reprojection gate uses it to predict what a static surface's previous view depth
                // should have been; without it, walking forward changes every nearby surface's
                // depth by more than the 5% tolerance and the gate throws the history away every
                // frame (at 36 fps, ~0.12 blocks/frame already exceeds the tolerance inside
                // ~2.5 blocks), which is both the noise and the blur reported while moving.
                float svgfCamForwardDelta = 0.0f;
                if (svgfHasHistory && !svgfReset) {
                    // Row 2 of the rotation-only view matrix is the view-space +Z axis, and view
                    // space looks down -Z -- the tracer relies on exactly that when it treats
                    // curClip.w (= -z_view) as a positive depth growing forward. So row 2 is the
                    // BACKWARD axis and the dot product below must be negated to get the camera's
                    // forward travel.
                    //
                    // Without the negation the term did not cancel the camera's motion, it DOUBLED
                    // it: the predicted previous depth moved one step the wrong way, leaving an
                    // error of 2x the per-frame travel. At 4.3 blocks/s and 36 fps that is 0.239
                    // blocks against a 5% tolerance, so every surface closer than ~4.8 blocks
                    // failed the depth gate on EVERY frame while walking, and the history was
                    // rebuilt from a single sample each frame. Measured with debug view 10:
                    // white (converged) standing still, black (no history) the moment the camera
                    // moved. Negated, the prediction is exact -- the residual is 0.0000 blocks at
                    // every distance and every off-axis angle.
                    double fx = frameViewRotation.m02();
                    double fy = frameViewRotation.m12();
                    double fz = frameViewRotation.m22();
                    svgfCamForwardDelta = (float) -((camX - svgfPrevCamX) * fx
                            + (camY - svgfPrevCamY) * fy
                            + (camZ - svgfPrevCamZ) * fz);
                    if (!Float.isFinite(svgfCamForwardDelta)) {
                        svgfCamForwardDelta = 0.0f;
                    }
                }
                // FG amplifies residual per-frame sky noise into visible flicker (it interpolates
                // between presented frames), so the sky smooths harder while it is presenting.
                int extraSkySmooth = RtFramePresenter.INSTANCE.isActive() ? 1 : 0;
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // trace + guides visible to the denoiser
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "SVGF denoise");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.svgf")) {
                    svgfDenoiser.reproject(cmd, renderW, renderH, svgfParity,
                            upscaleSource.view, historyIn.view, momentsIn.view,
                            historyOut.view, momentsOut.view, svgfFilterPing.view,
                            gMotion.view, gViewZ.view, gNormal.view,
                            svgfPrevViewZ.view, svgfPrevNormal.view, gAlbedo.view,
                            svgfReset, SVGF_MAX_FRAMES, svgfCamForwardDelta);
                    VulkanCommandEncoder.memoryBarrier(cmd, stack); // reprojection visible to the wavelet

                    // À-trous cascade with doubling tap spacing. The first iteration's output is
                    // what feeds next frame's temporal history (SVGF's own choice: the raw
                    // accumulation is noisier and converges more slowly, while feeding back the
                    // fully filtered image compounds its blur into permanent smearing).
                    RtImage src = svgfFilterPing;
                    RtImage dst = svgfFilterPong;
                    for (int pass = 0; pass < RtSvgfDenoiser.ATROUS_PASSES; pass++) {
                        // The cascade runs in DEMODULATED lighting space so its kernels never
                        // average across albedo detail (filtering modulated radiance flattens
                        // texture contrast — measured 3.00:1 down to 1.04:1, the "everything looks
                        // like flat poster paint" failure). Only the LAST iteration multiplies the
                        // albedo guide back in, which is also why the history feedback below must
                        // be taken from an earlier, still-demodulated pass.
                        boolean lastPass = pass == RtSvgfDenoiser.ATROUS_PASSES - 1;
                        svgfDenoiser.atrous(cmd, renderW, renderH, pass, svgfParity,
                                src.view, dst.view, gViewZ.view, gNormal.view, momentsOut.view,
                                gAlbedo.view,
                                SVGF_PHI_LUMINANCE, SVGF_PHI_NORMAL, SVGF_PHI_DEPTH,
                                extraSkySmooth, lastPass, svgfDebugView ? debugView : 0);
                        VulkanCommandEncoder.memoryBarrier(cmd, stack);
                        if (pass == RtSvgfDenoiser.HISTORY_FEEDBACK_PASS) {
                            // Copy this iteration's colour into the history the next frame reads.
                            // Still demodulated (the feedback pass is never the last one — see the
                            // assert in RtSvgfDenoiser), which is required: next frame's
                            // reprojection blends it against freshly demodulated samples.
                            copyImage(cmd, stack, dst, historyOut);
                            VulkanCommandEncoder.memoryBarrier(cmd, stack);
                        }
                        RtImage swap = src;
                        src = dst;
                        dst = swap;
                    }
                    upscaleSource = src; // the last iteration wrote here before the final swap

                    // Snapshot this frame's depth/normal guides: next frame's reprojection validates
                    // its history against the geometry that produced it, which is what lets it
                    // accept history under motion instead of clamping colour and smearing.
                    copyImage(cmd, stack, gViewZ, svgfPrevViewZ);
                    copyImage(cmd, stack, gNormal, svgfPrevNormal);
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                }
                svgfWriteToPing = !svgfWriteToPing;
                svgfHasHistory = true;
                svgfRan = true;
                // Camera snapshot for next frame's forward-travel prediction (see above).
                svgfPrevCamX = camX;
                svgfPrevCamY = camY;
                svgfPrevCamZ = camZ;
            }
            if (!rrDone && fsrPath && RtFsrUpscaler.INSTANCE.ensureFeature(displayW, displayH)) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "FSR upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.fsr")) {
                    // Camera discontinuity (teleport / respawn / world change): FSR is a temporal
                    // upscaler like the others — a jump bigger than the NRD rebase threshold leaves
                    // its reprojection history pointing at a world that no longer matches, reading
                    // as smear until it decays. Drop the history on the jump frame.
                    if (fsrCamValid) {
                        double fdx = camX - prevFsrCamX;
                        double fdy = camY - prevFsrCamY;
                        double fdz = camZ - prevFsrCamZ;
                        if (fdx * fdx + fdy * fdy + fdz * fdz > 32.0 * 32.0) {
                            RtFsrUpscaler.INSTANCE.requestReset();
                        }
                    }
                    prevFsrCamX = camX;
                    prevFsrCamY = camY;
                    prevFsrCamZ = camZ;
                    fsrCamValid = true;
                    // Vertical FOV from the (unjittered) level projection, for FSR's depth heuristic.
                    // abs(): Minecraft's Vulkan projection carries the NDC y-flip (negative m11),
                    // which would hand FSR a negative FOV.
                    float fovY = (float) (2.0 * Math.atan(1.0 / Math.abs(frameProjection.m11())));
                    // reactive parameter: unused since the reactive-mask experiment was reverted
                    // (the shim ignores it); null keeps the call honest.
                    rrDone = RtFsrUpscaler.INSTANCE.evaluate(cmd.address(), upscaleSource, gDepth, gMotion,
                            null, rrOutput,
                            renderW, renderH, displayW, displayH, -jitterX, -jitterY, fovY);
                }
            }

            // Intel XeSS occupies the slot when neither RR nor FSR is running: same inputs as FSR
            // (denoised-or-raw color + depth + motion vectors), output straight into rrOutput. The
            // ML reconstruction replaces FSR's analytic pass — same upscale slot, same consumers.
            if (!rrDone && xessPath && RtXessUpscaler.INSTANCE.ensureFeature(displayW, displayH)) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "XeSS upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.xess")) {
                    // Camera discontinuity reset, same rule as the FSR path above.
                    if (xessCamValid) {
                        double xdx = camX - prevXessCamX;
                        double xdy = camY - prevXessCamY;
                        double xdz = camZ - prevXessCamZ;
                        if (xdx * xdx + xdy * xdy + xdz * xdz > 32.0 * 32.0) {
                            RtXessUpscaler.INSTANCE.requestReset();
                        }
                    }
                    prevXessCamX = camX;
                    prevXessCamY = camY;
                    prevXessCamZ = camZ;
                    xessCamValid = true;
                    // XeSS takes the applied image-space jitter as-is (no negation — see
                    // RtXessUpscaler.evaluate), unlike FSR/DLSS which take the negated offsets.
                    // EXCEPT when a temporal denoiser just ran: its output is already the integral
                    // of the whole jitter sequence, so telling XeSS a per-frame jitter that is no
                    // longer in the content makes its reprojection chase a phantom offset (the
                    // noisy/shimmery output). Converged input -> zero jitter.
                    boolean jitterAlreadyIntegrated = svgfRan || nrdDone;
                    float xessJitterX = jitterAlreadyIntegrated ? 0.0f : jitterX;
                    float xessJitterY = jitterAlreadyIntegrated ? 0.0f : jitterY;
                    rrDone = RtXessUpscaler.INSTANCE.evaluate(cmd.address(), upscaleSource, gDepth, gMotion,
                            rrOutput,
                            renderW, renderH, displayW, displayH, xessJitterX, xessJitterY);
                }
            }

            // When no upscaler produced the display-res image (disabled, debug view, or a runtime
            // failure), bring the render-res trace up to display res with a linear blit so the display mapper
            // always has a display-res RT image. With no upscaler render == display, so this is a 1:1 copy.
            if (!rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, upscaleSource, rrOutput);
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

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                displayPipeline.dispatch(cmd, displayW, displayH, CausticaConfig.Rt.Hdr.enabled(),
                        CausticaConfig.Rt.Hdr.paperWhiteNits(), CausticaConfig.Rt.Hdr.headroom(),
                        CausticaConfig.Rt.Tonemapping.operatorIndex(),
                        CausticaConfig.Rt.Tonemapping.EXPOSURE_EV.value(),
                        CausticaConfig.Rt.Tonemapping.GAMMA.value(),
                        CausticaConfig.Rt.Tonemapping.SATURATION.value(),
                        CausticaConfig.Rt.Tonemapping.CONTRAST.value());
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
        // Submission order on the one graphics queue is the history dependency: next frame reads the half
        // this frame just wrote and writes the other half. Advance only after execute accepted the command.
        if (restirResourcesEnabled) {
            restirWriteIndex ^= 1;
        }
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
     * This frame's weather, resolved once on the CPU and pushed to the shaders.
     *
     * <p>{@code rain} and {@code thunder} are vanilla's own interpolated 0..1 levels. The two derived
     * multipliers exist so the sky shader and the NEE light cannot disagree about how dark a storm is:
     * {@code skyDarken} scales the atmosphere in-scatter in {@code world.rmiss}, {@code lightAttenuation}
     * scales the sun/moon radiance in {@link #skyPush}, and both are computed here, from the same two
     * levels, in one place.
     *
     * @param rain             vanilla rain level, 0 clear .. 1 fully raining
     * @param thunder          vanilla thunder level, 0 .. 1 (only ever non-zero while it is also raining)
     * @param skyDarken        multiplier on the sky's own radiance
     * @param lightAttenuation multiplier on the direct sun/moon radiance
     */
    private record WeatherState(float rain, float thunder, float skyDarken, float lightAttenuation) {
        static final WeatherState CLEAR = new WeatherState(0f, 0f, 1f, 1f);
    }

    /**
     * The three {@code WorldPush} cloud lanes, resolved together by {@link #cloudState} so a caller
     * cannot push a deck's parameters with a mismatched anchor or a mismatched weather fill.
     *
     * @param clouds x player coverage (the slider — the shader folds the weather in itself), y opacity,
     *               z shadow strength, w camera-relative deck height
     * @param anchor xy wrapped sample anchor, z slab thickness, w view limit
     * @param color  xyz vanilla CLOUD_COLOR in linear space, w weather overcast fill 0..1
     */
    private record CloudPush(Float4 clouds, Float4 anchor, Float4 color) {
        /** No deck at all: a zeroed coverage/opacity pair short-circuits every cloud path in the shader. */
        static final CloudPush NONE =
                new CloudPush(new Float4(0f, 0f, 0f, 0f), new Float4(0f, 0f, 0f, 0f),
                        new Float4(1f, 1f, 1f, 0f));
    }

    /**
     * Read vanilla's interpolated rain/thunder levels and turn them into the sky/light multipliers.
     *
     * <p>The curve: overcast rain keeps about 35% of the clear-sky light and 45% of the sky's own
     * radiance, and a full thunderstorm roughly halves each of those again. Those numbers are picked to
     * match how vanilla treats the two states — rain drops the effective sky light level from 15 to 12
     * and a thunderstorm to 10, which is a much larger perceptual drop than the raw light levels suggest
     * because the sun disc is also gone — while staying well clear of zero, since a path tracer with no
     * sun and no sky fill has no light left at all and a daytime storm would render as night.
     *
     * <p>Thunder is folded in as an additional factor rather than a separate branch: vanilla only ever
     * raises the thunder level while it is already raining, so the two multiply into one continuous ramp
     * from clear to storm with no discontinuity at the transition.
     *
     * <p>Dimensions without weather (Nether, End) always report clear — {@code getRainLevel} is already
     * zero there, but returning the shared constant keeps the fast path allocation-free.
     */
    private static WeatherState weatherState(ClientLevel level, float partial) {
        if (level == null || !CausticaConfig.Rt.Composite.WEATHER_LIGHTING.value()) {
            return WeatherState.CLEAR;
        }
        float rain = Math.clamp(level.getRainLevel(partial), 0f, 1f);
        if (rain <= 0f) {
            return WeatherState.CLEAR;
        }
        // getThunderLevel already includes the rain level as a factor in vanilla; clamp defensively so a
        // datapack or mod that drives it independently cannot push the multipliers negative.
        float thunder = Math.clamp(level.getThunderLevel(partial), 0f, 1f);
        // Written out rather than via a lerp helper so each ramp's endpoints are readable inline:
        // at full rain the direct light keeps 35% and the sky 45%; a full thunderstorm then halves
        // each again (to ~18% and ~25% of clear).
        float rainLight = 1.0f - 0.65f * rain;
        float stormLight = 1.0f - 0.50f * thunder;
        float rainSky = 1.0f - 0.55f * rain;
        float stormSky = 1.0f - 0.45f * thunder;
        return new WeatherState(rain, thunder, rainSky * stormSky, rainLight * stormLight);
    }

    /** Baseline radiance*area product of the analytic held-item light at light level 15. Calibrated so
     * a default-scale torch in hand reads close to a placed torch on nearby blocks; the Video Settings
     * held-item slider (lights.dynamic-intensity / {@code lightScales.y}) scales it live. */
    private static final float HAND_LIGHT_POWER = 0.6f;

    /** Per-item held-light tints (linear RGB). Mirrors and extends the well-known luminous sprite list
     * in {@code RtEntityCollector.itemSpriteEmission}, so the analytic light matches the colour the
     * captured item geometry already suggests. */
    private static final float[] TINT_SOUL = {0.35f, 0.85f, 1.0f};        // soul torch / lantern / campfire
    private static final float[] TINT_REDSTONE = {1.0f, 0.25f, 0.15f};    // redstone torch
    private static final float[] TINT_AQUA = {0.65f, 0.90f, 1.0f};        // sea lantern
    private static final float[] TINT_PICKLE = {0.75f, 0.95f, 0.55f};     // sea pickle
    private static final float[] TINT_TORCH = {1.0f, 0.62f, 0.30f};       // torch / lantern / campfire / candles
    private static final float[] TINT_LAVA = {1.0f, 0.35f, 0.10f};        // lava, magma
    private static final float[] TINT_GLOWSTONE = {1.0f, 0.78f, 0.50f};
    private static final float[] TINT_SHROOMLIGHT = {1.0f, 0.55f, 0.35f};
    private static final float[] TINT_VERDANT = {0.70f, 1.0f, 0.60f};     // verdant froglight
    private static final float[] TINT_PEARLESCENT = {0.95f, 0.70f, 1.0f}; // pearlescent froglight
    private static final float[] TINT_OCHRE = {1.0f, 0.85f, 0.50f};       // ochre froglight
    private static final float[] TINT_END_ROD = {1.0f, 0.95f, 0.85f};
    private static final float[] TINT_BEACON = {0.85f, 0.95f, 1.0f};
    private static final float[] TINT_AMBER = {1.0f, 0.60f, 0.30f};       // redstone lamp, jack o'lantern
    private static final float[] TINT_CRYING = {0.70f, 0.40f, 1.0f};      // crying obsidian
    private static final float[] TINT_BLAZE = {1.0f, 0.50f, 0.20f};
    private static final float[] TINT_DEFAULT = {1.0f, 0.75f, 0.55f};     // any other luminous block

    /** Per-frame held-light push pair: position + intensity lane, and the item's tint. */
    private record HandLightState(Float4 light, Float4 color) {
        static final HandLightState NONE = new HandLightState(
                new Float4(0.0f, 0.0f, 0.0f, 0.0f), new Float4(0.0f, 0.0f, 0.0f, 0.0f));
    }

    /**
     * The analytic point light a luminous held item casts (WorldPush.handLight +
     * WorldPush.handLightColor). Held-item geometry never enters the RIS emitter light buffer —
     * that buffer collects terrain quads only — so its captured flame would light the scene through
     * rare indirect bounce hits alone: a torch in hand barely brightened the blocks right in front
     * of it. The shader therefore NEE-samples this light at every diffuse receiver, exactly like
     * the celestial light.
     *
     * <p>Position follows the player's (partial-tick interpolated) view, pushed forward and slightly
     * below eye level — roughly where the held item sits in both first and third person. Intensity
     * and tint come from the brighter of main/off hand. The feature toggle, a missing player or no
     * luminous item all push {@code w == 0}, which disables the shader term entirely.
     */
    private static HandLightState handLightState(RtTerrain terrain) {
        if (!CausticaConfig.Rt.Lights.HELD_ITEM_LIGHT.value()) {
            return HandLightState.NONE;
        }
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return HandLightState.NONE;
        }
        HeldLight main = heldLight(player.getMainHandItem());
        HeldLight off = heldLight(player.getOffhandItem());
        HeldLight best = off.level() > main.level() ? off : main;
        if (best.level() <= 0) {
            return HandLightState.NONE;
        }
        float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 eye = player.getEyePosition(partial);
        Vec3 look = player.getViewVector(partial);
        double hx = eye.x + look.x * 0.7;
        double hy = eye.y + look.y * 0.7 - 0.35;
        double hz = eye.z + look.z * 0.7;
        float intensity = (best.level() / 15.0f) * HAND_LIGHT_POWER;
        return new HandLightState(
                new Float4((float) (hx - terrain.blockX), (float) (hy - terrain.blockY),
                        (float) (hz - terrain.blockZ), intensity),
                new Float4(best.r(), best.g(), best.b(), 0.0f));
    }

    /** A held item's light: vanilla block-light level plus the flame's tint. Block items carry their
     * state's emission; the few luminous non-block items mirror
     * {@code RtEntityCollector.itemSpriteEmission}'s well-known list. */
    private record HeldLight(int level, float r, float g, float b) {
        static final HeldLight NONE = new HeldLight(0, 0.0f, 0.0f, 0.0f);
    }

    private static HeldLight heldLight(ItemStack stack) {
        if (stack.isEmpty()) {
            return HeldLight.NONE;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        int level;
        if (stack.getItem() instanceof BlockItem blockItem) {
            level = blockItem.getBlock().defaultBlockState().getLightEmission();
        } else if (path.contains("lava")) {
            level = 15; // lava bucket
        } else if (path.contains("blaze_rod")) {
            level = 10;
        } else {
            level = 0;
        }
        if (level <= 0) {
            return HeldLight.NONE;
        }
        float[] tint = heldLightTint(path);
        return new HeldLight(level, tint[0], tint[1], tint[2]);
    }

    /** Ordered substring matching: soul/redstone variants before the generic torch check, sea lantern
     * before lantern, froglight variants before the ochre catch-all. */
    private static float[] heldLightTint(String path) {
        if (path.contains("soul_torch") || path.contains("soul_lantern")
                || path.contains("soul_campfire")) {
            return TINT_SOUL;
        }
        if (path.contains("redstone_torch")) {
            return TINT_REDSTONE;
        }
        if (path.contains("sea_lantern")) {
            return TINT_AQUA;
        }
        if (path.contains("sea_pickle")) {
            return TINT_PICKLE;
        }
        if (path.contains("torch") || path.contains("lantern") || path.contains("campfire")) {
            return TINT_TORCH;
        }
        if (path.contains("lava") || path.contains("magma")) {
            return TINT_LAVA;
        }
        if (path.contains("glowstone")) {
            return TINT_GLOWSTONE;
        }
        if (path.contains("shroomlight")) {
            return TINT_SHROOMLIGHT;
        }
        if (path.contains("verdant_froglight")) {
            return TINT_VERDANT;
        }
        if (path.contains("pearlescent_froglight")) {
            return TINT_PEARLESCENT;
        }
        if (path.contains("froglight")) { // ochre
            return TINT_OCHRE;
        }
        if (path.contains("end_rod")) {
            return TINT_END_ROD;
        }
        if (path.contains("beacon")) {
            return TINT_BEACON;
        }
        if (path.contains("crying_obsidian")) {
            return TINT_CRYING;
        }
        if (path.contains("redstone_lamp") || path.contains("jack_o_lantern")) {
            return TINT_AMBER;
        }
        if (path.contains("blaze_rod")) {
            return TINT_BLAZE;
        }
        return TINT_DEFAULT;
    }

    /**
     * Map the client level's dimension onto the shader's sky model. Uses {@code level.dimension()} —
     * the dimension's {@code ResourceKey}, which is what the server actually tells the client it is in —
     * rather than sniffing dimension type flags, so a datapack dimension that merely reuses the Nether's
     * or the End's type still renders as the Overworld unless it really is that dimension.
     */
    private static int dimensionId(ClientLevel level) {
        if (level == null) {
            return DIMENSION_OVERWORLD;
        }
        var dimension = level.dimension();
        if (Level.NETHER.equals(dimension)) {
            return DIMENSION_NETHER;
        }
        if (Level.END.equals(dimension)) {
            return DIMENSION_END;
        }
        return DIMENSION_OVERWORLD;
    }

    /**
     * Resolve this frame's cloud deck into the two {@link WorldPushData} lanes {@code clouds.slang}
     * reads: {@code clouds} (coverage, opacity, shadow strength, camera-relative deck height) and
     * {@code cloudAnchor} (wind-scrolled sample anchor, slab thickness, view limit).
     *
     * <p><b>Coverage and weather.</b> Rain pushes coverage toward fully overcast on top of the
     * configured clear-sky value, and thunder finishes closing it. That is the same rain/thunder pair
     * the sky darkening and the light attenuation come from, so a storm's dark sky, its dimmer sunlight
     * and its solid cloud cover are three readings of one state and cannot drift apart — the invariant
     * {@link #weatherState} already establishes for the rest of the weather look. It also fixes the
     * thing the sky shader always claimed but could never show: it hides the sun "behind the cloud
     * deck" during rain, and now there is an actual deck there to hide it.
     *
     * <p><b>The anchor.</b> Clouds drift with world time, so the sample offset grows without bound; the
     * camera can also stand 30M blocks out at the world border. Either alone would destroy float
     * precision in the shader's noise lookup (visible as the pattern coarsening into stripes and then
     * freezing). The anchor is therefore reduced modulo the cloud field's exact repeat period, which is
     * seamless precisely because {@code clouds.slang} wraps its cell hash to that same period, so the
     * wrapped anchor selects the identical pattern the unwrapped one would have.
     *
     * <p><b>Height.</b> Pushed camera-relative, matching every other position in the push (the terrain
     * rebase means absolute world coordinates are not meaningful in the shader).
     */
    private CloudPush cloudState(int dimension, WeatherState weather, double cameraY) {
        float coverage = CausticaConfig.Rt.Composite.CLOUD_COVERAGE.value();
        float opacity = CausticaConfig.Rt.Composite.CLOUD_OPACITY.value();
        float shadow = CausticaConfig.Rt.Composite.CLOUD_SHADOW_STRENGTH.value();
        if (dimension != DIMENSION_OVERWORLD) {
            // Neither the Nether nor the End has a sky to put clouds in; both draw a closed skybox.
            return CloudPush.NONE;
        }
        // Weather FILL, kept separate from the player's coverage slider all the way to the shader:
        // rain alone must be able to close the sky completely (the old 0.85/0.15 split topped out
        // short of full cover in a plain rainstorm — the reported bug 3 — and the classic style's
        // threshold then read the shortfall as punched holes). Thunder implies rain in vanilla, so it
        // only ever reinforces the ramp. The two classic/volumetric styles consume this differently
        // (authored cells fill progressively vs. the noise threshold dropping), which is why it rides
        // its own lane instead of being pre-merged into the coverage value here.
        float fill = Math.min(1f, weather.rain() + weather.thunder());
        float height = CausticaConfig.Rt.Composite.CLOUD_HEIGHT.value();
        // Wind drift, in blocks, from world time. Wrapped with the anchor below.
        double gameTime = 0.0;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            gameTime = level.getGameTime()
                    + Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        }
        double drift = gameTime * CLOUD_WIND_BLOCKS_PER_TICK;
        // camX/camZ place the deck in world space; the shader adds the ray's camera-relative offset back
        // on, so the pattern stays pinned to the world while the camera moves through it. The fixed Z
        // offset matches vanilla's own (cameraZ + 3.96 in CloudRenderer.render).
        double anchorX = camX + drift;
        double anchorZ = camZ + CLOUD_Z_OFFSET_BLOCKS;
        // Player-controlled thickness. At 0 the shader takes its flat-plane path (see
        // CLOUD_FLAT_EPSILON in clouds.slang), so the slider bottoming out is genuinely a flat deck
        // rather than a degenerate zero-length march — that stays true for the volumetric style. The
        // classic style floors at vanilla's own 4-block box height instead: its shapes come from the
        // authored clouds.png cells, and the slider scales box HEIGHT from the vanilla baseline up.
        float thickness = Math.clamp(CausticaConfig.Rt.Composite.CLOUD_THICKNESS.value(), 0f, 1f)
                * CLOUD_MAX_THICKNESS_BLOCKS;
        if (CausticaConfig.Rt.Composite.cloudStyleIndex() != CLOUD_STYLE_VOLUMETRIC) {
            thickness = Math.max(CLOUD_CLASSIC_MIN_THICKNESS, thickness);
        }
        // The slider sets the deck's BASE, but the shader's slab is centred on the pushed height, so the
        // half-thickness is added back here. Pushing the base directly would make the clouds appear to
        // sink as the thickness slider is raised (the slab would grow downward as well as upward), which
        // would make the two sliders fight each other — the base is the edge the player actually sees
        // and judges the height by.
        float deckCentre = height + thickness * 0.5f;
        return new CloudPush(
                new Float4(Math.clamp(coverage, 0f, 1f), Math.clamp(opacity, 0f, 1f),
                        Math.clamp(shadow, 0f, 1f), (float) (deckCentre - cameraY)),
                new Float4(wrapCloudAnchor(anchorX), wrapCloudAnchor(anchorZ),
                        thickness, cloudViewLimit(deckCentre - (float) cameraY)),
                cloudColorState(fill));
    }

    /**
     * The deck's albedo and the weather's overcast fill, in one lane.
     *
     * <p>The colour is {@code EnvironmentAttributes.CLOUD_COLOR} read through the same camera probe
     * {@link #skyPush} already uses for the sun and star angles — the value the game itself resolves
     * per dimension and per weather. That is the whole point of reading it instead of ramping by hand:
     * the storm-grey deck is vanilla's own grey, on vanilla's own curve, and a dimension or pack that
     * tints cloud colour gets the tint for free. Early-boot frames without a probe fall back to white,
     * which is the Overworld's clear-day value anyway.
     */
    private static Float4 cloudColorState(float weatherFill) {
        float r = 1f, g = 1f, b = 1f;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameRenderer != null) {
                float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                Vector4fc cloud = mc.gameRenderer.mainCamera().attributeProbe()
                        .getValue(EnvironmentAttributes.CLOUD_COLOR, partial);
                r = srgbToLinear(cloud.x());
                g = srgbToLinear(cloud.y());
                b = srgbToLinear(cloud.z());
            }
        } catch (Throwable ignored) {
            // Probe unavailable (early boot / unsupported context): white is the correct default.
        }
        return new Float4(r, g, b, Math.clamp(weatherFill, 0f, 1f));
    }

    /**
     * Standard sRGB-to-linear decode — the same curve the material compiler uses
     * (RtMaterialTextureData keeps it package-private, so the one duplicate lives here rather than
     * widening that class's visibility for a single caller). The 26.3 environment color attributes
     * carry sRGB-encoded 0..1 vectors (raw /255 of the same hex colors), so decoding still applies.
     */
    private static float srgbToLinear(float v) {
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4f);
    }

    /**
     * How far out clouds stay visible, in blocks of horizontal distance.
     *
     * <p>Scales with the deck's height above the camera so a high deck does not fade out while still
     * well up in the sky — see {@link #CLOUD_VIEW_LIMIT_HEIGHT_MULTIPLE}. A deck at or below the camera
     * falls back to the flat limit.
     */
    private static float cloudViewLimit(float deckAboveCamera) {
        return Math.max(CLOUD_VIEW_LIMIT_BLOCKS,
                Math.abs(deckAboveCamera) * CLOUD_VIEW_LIMIT_HEIGHT_MULTIPLE);
    }

    /** Reduce a world coordinate into the cloud field's exact repeat period — see {@link #cloudState}. */
    private static float wrapCloudAnchor(double blocks) {
        double period = CLOUD_FIELD_PERIOD_BLOCKS;
        double wrapped = blocks % period;
        if (wrapped < 0.0) {
            wrapped += period;
        }
        return (float) wrapped;
    }

    /**
     * Derive the celestial light from Minecraft's time of day as typed values for {@link WorldPushData}.
     * Celestial angles come from the camera's {@link EnvironmentAttributeProbe} (partial-tick
     * interpolated). {@code caustica.rt.sunNoonSouthDeg} tilts the east-west arc toward south (+Z) at
     * noon.
     *
     * <p>{@code weather} attenuates the resulting radiance, and a dimension with no celestial cycle
     * (Nether, End) zeroes it outright: neither has a sun or a moon, so a directional NEE light there
     * would be light arriving from nothing. The raygen skips the whole NEE block — shadow ray included —
     * when the radiance is zero, so those dimensions also stop paying for a light they do not have.
     */
    private SkyPush skyPush(int dimension, WeatherState weather) {
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
        // Stars: use Minecraft's actual celestial rotation + brightness (the same values vanilla's
        // SkyRenderer uses), so the starfield wheels about the celestial pole tied to world time and
        // fades in/out at dusk/dawn exactly like vanilla. STAR_ANGLE is in degrees -> radians.
        starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * (float) (Math.PI / 180.0);
        starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
        dayFactor = smoothstep(-0.08f, 0.10f, sunY);
        float[] trans = new float[3];
        if (sunY > -0.05f) {
            // Sun stays the NEE light through the whole sunset: its colour/intensity is the atmosphere's
            // own transmittance (same Rayleigh+Mie+ozone march as the sky shader — see
            // atmosphereTransmittance), so it whitens overhead and reddens+dims into the horizon on
            // exactly the curve the visible sky follows. The old hand-tuned warmth ramp switched to the
            // moon at sunY == 0 while the sun was still at ~16% strength, which read as a hard light pop
            // at sunset/sunrise; transmittance is already near zero at the horizon, and the short
            // smoothstep below carries the remainder to exactly zero before the moon takes over.
            atmosphereTransmittance(sunX, sunY, sunZ, trans);
            float fade = smoothstep(-0.05f, 0.005f, sunY);
            float sunPeak = 21.0f;
            lx = sunX; ly = sunY; lz = sunZ;
            rr = sunPeak * trans[0] * fade;
            rg = sunPeak * trans[1] * fade;
            rb = sunPeak * trans[2] * fade;
            lightRadius = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value();
        } else {
            // Moon: dim cool light, ramping up from zero at the sun→moon handoff (sunY = -0.05, where
            // the sun fade also reaches zero) so the switch is invisible. Scaled by the lit fraction so
            // a new moon gives near-zero moonlight, and tinted by the same transmittance so a low moon
            // is warm amber, silver once high (or zero while it is below the horizon).
            atmosphereTransmittance(moonX, moonY, moonZ, trans);
            float moonStrength = smoothstep(0.04f, 0.22f, -sunY);
            float litFraction = 1.0f - Math.abs(moonPhase - 4.0f) / 4.0f; // 0 new .. 1 full
            float moonPeak = 0.20f * (0.15f + 0.85f * litFraction);
            lx = moonX; ly = moonY; lz = moonZ;
            rr = 0.30f * moonPeak * moonStrength * trans[0];
            rg = 0.36f * moonPeak * moonStrength * trans[1];
            rb = 0.55f * moonPeak * moonStrength * trans[2];
            lightRadius = CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS.value();
        }
        // Weather attenuation, applied to whichever body is currently the NEE light. Overcast does not
        // just dim the sun, it replaces it with a diffuse source — the sky term in world.rmiss carries
        // that half — so the directional component drops further than the sky does (see weatherState).
        float lightAttenuation = weather.lightAttenuation();
        // No celestial cycle in the Nether/End: no sun, no moon, no directional light, no stars.
        if (dimension != DIMENSION_OVERWORLD) {
            lightAttenuation = 0f;
            starBrightness = 0f;
        }
        rr *= lightAttenuation;
        rg *= lightAttenuation;
        rb *= lightAttenuation;
        // Stars are behind the cloud deck during rain; the sky shader fades them out on the same ramp.
        starBrightness *= 1.0f - weather.rain();

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
        // Final single sky: vibrant and blue (Minecraft RTX style) - aggressive vivid
        final double planetR = 6371000.0, atmosR = 6471000.0;
        final double[] rayBeta = {3.2e-6, 8.5e-6, 24.5e-6}; // pure saturated vibrant blue
        final double mieBeta = 6.0e-6 * 1.1; // RADICALLY lowered to kill gray haze (was 38/24/18)
        final double[] ozoneBeta = {0.650e-6, 1.881e-6, 0.085e-6};
        final double oy = planetR + 2000.0;
        double b = oy * dy;
        double tEnd = -b + Math.sqrt(Math.max(b * b - (oy * oy - atmosR * atmosR), 0.0));
        double seg = tEnd / 8.0;
        double odR = 0.0, odM = 0.0, odO = 0.0;
        for (int i = 0; i < 8; i++) {
            double t = seg * (i + 0.5);
            double px = dx * t, py = oy + dy * t, pz = dz * t;
            double h = Math.sqrt(px * px + py * py + pz * pz) - planetR;
            odR += Math.exp(-h / 9000.0) * seg;
            odM += Math.exp(-h / 900.0) * seg;
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
        sharc.destroy(RtContext.currentOrNull());
        // Unconditional: RtDlssRr.enabled() reflects the CURRENT toggles, and the denoiser toggle can
        // have been turned off after a feature was already created. destroy() is a no-op when nothing
        // was ever allocated, so asking it every time is what guarantees the feature is released.
        RtDlssRr.INSTANCE.destroy();
        // Same contract for the FSR context (no-op when it was never created).
        RtFsrUpscaler.INSTANCE.destroy();
        // And the XeSS upscaler (no-op when it was never initialized).
        RtXessUpscaler.INSTANCE.destroy();
        // Tear down the NRD integration (wraps the Vulkan device via NRI) only after its images are
        // released below; destroyGuideImages runs after this in the teardown sequence.
        RtNrdDenoiser.INSTANCE.destroy();
        if (nrdCombinePipeline != null) {
            nrdCombinePipeline.destroy();
            nrdCombinePipeline = null;
        }
        if (svgfDenoiser != null) {
            svgfDenoiser.destroy();
            svgfDenoiser = null;
        }
        if (fgSkyMaskPipeline != null) {
            fgSkyMaskPipeline.destroy();
            fgSkyMaskPipeline = null;
        }
        if (fgUiCompositePipeline != null) {
            fgUiCompositePipeline.destroy();
            fgUiCompositePipeline = null;
        }
        svgfHasHistory = false;
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
        destroyRestirResources();
        destroyGuideImages();
        exposure.destroy();
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
        if (fgBackbufferCopy != null) {
            fgBackbufferCopy.destroy();
            fgBackbufferCopy = null;
        }
        fgBackbufferCopyW = -1;
        fgBackbufferCopyH = -1;
        if (nativeFgPipeline != null) {
            nativeFgPipeline.destroy();
            nativeFgPipeline = null;
        }
        if (fgPrevFrame != null) {
            fgPrevFrame.destroy();
            fgPrevFrame = null;
        }
        fgPrevFrameW = -1;
        fgPrevFrameH = -1;
        fgPrevFrameFormat = Integer.MIN_VALUE;
        fgPrevFrameValid = false;
        nativeFgCamValid = false;
        fgNativeLastUseFrame = -1;
        fgNativeSeededTick = false;
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

            // FG "hudless" capture: hdrDisplayImage right now holds the RT world before the combined
            // UI overlay is blended in. Snapshot it before that composite overwrites it in place, mirroring
            // captureFgHudless's SDR pattern (pre-UI copy) but reusing this frame's already-open command
            // buffer. Both DLSS-FG (hudless resource) and the native engine (interpolation source +
            // UI re-composite) consume it.
            if (fgHudlessNeeded()) {
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
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit converted PQ image (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
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
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
        return true;
    }

    /**
     * Linear-filtered blit of the full render-res image into the full display-res image. Used as the
     * non-RR / fallback upscale so display mapping always sees a display-res RT image; a no-op stretch when
     * the two are the same size (RR disabled -> render == display).
     */
    /**
     * Same-size image copy between two GENERAL-layout storage images (SVGF's history feedback and
     * its previous-frame guide snapshots). A copy rather than a ping-pong of yet more images: the
     * two consumers need the data at a fixed binding across frames, and vkCmdCopyImage on identical
     * formats is the cheapest way to get it without another descriptor rewrite per frame.
     */
    private static void copyImage(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0)
                .baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(Math.min(src.width, dst.width), Math.min(src.height, dst.height), 1);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
    }

    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1); // srcOffsets[0] zeroed by calloc
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_LINEAR);
    }

    /**
     * DLSS Frame Generation quality: capture a copy of {@code main} (the main render target) into
     * {@link #fgHudlessImage} for {@link #fgInterpolate} to feed DLSSG as the "hudless" resource. Call from
     * {@code GameRendererMixin} right after {@code GuiRenderer.render()} but BEFORE
     * {@link RtUiOverlay#compositeIfUsed()} — at that point, when the UI overlay redirect is active, {@code
     * main} still has no combined UI baked in (world overlays, hand/screen effects and GUI went to the
     * overlay target instead). No-op (and {@link #fgInterpolate} passes 0/0/0 for hudless, same as always)
     * unless both FG and the UI overlay redirect are active — capturing this without the redirect would just
     * copy the ALREADY-composited backbuffer, which is useless as a distinct hudless input.
     */
    public void captureFgHudless(RenderTarget main) {
        if (!fgHudlessNeeded() || !RtUiOverlay.enabled() || main == null || main.getColorTexture() == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        long srcImage;
        try {
            srcImage = vkImage(main.getColorTexture());
        } catch (IllegalStateException e) {
            return; // not a Vulkan-backed texture (shouldn't happen on this backend)
        }
        if (fgHudlessImage == null || fgHudlessImage.width != main.width || fgHudlessImage.height != main.height) {
            if (fgHudlessImage != null) {
                fgHudlessImage.destroy();
            }
            fgHudlessImage = ctx.createStorageImage(main.width, main.height, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                    "FG hudless capture " + main.width + "x" + main.height);
        }
        var encoder = (VulkanCommandEncoder) ((FrontendCommandEncoder) RenderSystem.getDevice().createCommandEncoder()).backend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make writes into `main` visible to the copy (the combined UI has not touched `main` yet this
            // frame — it went to the UI overlay target instead).
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            VK10.vkCmdCopyImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    fgHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, main.width, main.height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        encoder.execute(cmd);
    }

    /**
     * HDR counterpart of {@link #captureFgHudless} — copies {@code src} (this frame's {@code hdrDisplayImage},
     * before the combined UI overlay is blended in) into {@link #fgHdrHudlessImage} for {@link
     * #fgInterpolate}'s HDR path to feed DLSSG as the "hudless" resource. A plain copy, not a format
     * conversion: both images are
     * already PQ-encoded (the display-ready EOTF-encoded [0,1] signal DLSS-FG's programming guide requires),
     * so no encode step is needed. Called from {@link #presentHdr} using its already-open {@code cmd}/
     * {@code stack}, right before that method's own combined-UI composite dispatch overwrites
     * {@code hdrDisplayImage} in place — same "capture before the UI gets baked back in" timing as the SDR
     * version, just within a single method instead of split across a mixin hook.
     */
    private void captureFgHdrHudless(VkCommandBuffer cmd, MemoryStack stack, RtImage src) {
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        if (fgHdrHudlessImage == null || fgHdrHudlessImage.width != src.width || fgHdrHudlessImage.height != src.height) {
            if (fgHdrHudlessImage != null) {
                fgHdrHudlessImage.destroy();
            }
            fgHdrHudlessImage = ctx.createStorageImage(src.width, src.height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "FG HDR hudless capture (PQ) " + src.width + "x" + src.height);
        }
        // Make composite()'s writes to hdrDisplayImage (an earlier submit this frame) visible to this copy;
        // the copy's write is then made visible to the UI-composite dispatch that follows (and to DLSSG's
        // read, in a later command buffer) by the same idiom.
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                fgHdrHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, src.width, src.height));
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    /**
     * DLSS Frame Generation: record the DLSSG evaluate for generated frame {@code index} of {@code count}
     * (backbuffer = the final frame; HW depth = {@code gDepth}; motion = {@code gMotion}) into Minecraft's
     * command encoder, returning the interpolated output image (backbuffer size) for {@link RtFramePresenter}
     * to blit into a generated swapchain image. On {@code index == 1} it ensures the feature (created in its
     * own synchronous submit), the per-index output images, and the jitter-free reprojection matrices.
     * Returns {@code null} (caller falls back to duplicating the real frame for this one frame, no session
     * impact) when there's simply no captured RT frame to interpolate from right now — routine and expected
     * on menu/loading/transition frames, since {@link RtFramePresenter#isActive} only gates on being in a
     * world, not on RT having actually produced a frame this tick. Throws instead for failures that should
     * never happen once RT is actively producing frames (DLSSG feature creation failing, an out-of-range
     * index, the evaluate itself failing) — the caller treats those as fatal and disables FG for the
     * session, same as any other FG present-record failure, rather than silently degrading to duplicated
     * (non-interpolated) frames forever with no visible sign anything is wrong. Rotation-only matrices;
     * camera translation is carried by the mvecs (cameraMotionIncluded).
     *
     * <p>{@code hdrBackbuffer} selects the HDR path. Per the DLSS-FG programming guide's HDR section, scRGB is
     * explicitly unsupported as a DLSS-FG input ("not suitable as inputs to DLSS-FG" — it wants a
     * display-ready, EOTF-encoded [0,1] signal, recommending HDR10/ST.2084) — since the renderer's whole HDR
     * pipeline is natively PQ-encoded, every image fed to {@code RtDlssFg.evaluate} in HDR mode is already in
     * that format with no extra conversion needed: the backbuffer is the raw {@code backbufferView}/
     * {@code backbufferImage} the caller passed in ({@link #hdrBackbufferView()}, already PQ + UI-composited
     * by {@link #presentHdr}); the hudless resource is {@link #fgHdrHudlessImage} (copied by {@link
     * #presentHdr} <em>before</em> its own UI composite ran, mirroring {@link #captureFgHudless}'s pre-UI
     * timing); and DLSSG's own (also PQ-encoded) output is returned as-is, since the swapchain itself is
     * PQ-native and can blit it directly. The UI resource itself needs no HDR-specific handling — it's the
     * same combined {@link RtUiOverlay} texture used by both present paths (only the *compositing* math that
     * consumes it differs, done separately by {@code presentHdr}/{@code RtUiOverlay}, not here).
     */
    /**
     * Generated-frame count the active FG backend will actually produce this frame: the native
     * engine clamps to its cap (up to 3 generated = 4x); FSR 3.1 is hard-capped at 1 generated
     * frame (= 2x) because that is all the FFX runtime writes (see RtFsrFrameGen.MAX_GENERATED_FRAMES)
     * — presenting more would show never-written images; DLSS clamps to the driver-reported MFG
     * maximum. Called by the present hooks ({@code VulkanGpuSurfaceMixin}) instead of the
     * DLSS-only getter.
     */
    public static int fgGeneratedCount() {
        if (RtNativeFrameGen.enabled()) {
            return RtNativeFrameGen.INSTANCE.effectiveGeneratedCount();
        }
        if (RtFsrFrameGen.enabled()) {
            return RtFsrFrameGen.INSTANCE.effectiveGeneratedCount();
        }
        return RtDlssFg.INSTANCE.effectiveMultiFrameCount();
    }

    public RtImage fgInterpolate(VulkanCommandEncoder enc, long backbufferView, long backbufferImage,
            int swapW, int swapH, int index, int count, boolean hdrBackbuffer) {
        if (failed || gDepth == null || gMotion == null || !frameCaptured) {
            return null;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        final int fmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (RtNativeFrameGen.enabled()) {
            return fgInterpolateNative(ctx, enc, backbufferImage, swapW, swapH, index, count, hdrBackbuffer, fmt);
        }
        if (RtFsrFrameGen.enabled()) {
            return fgInterpolateFsr(ctx, enc, backbufferImage, swapW, swapH, index, count, hdrBackbuffer, fmt);
        }
        if (index == 1) {
            if (!ensureFgFeature(ctx, swapW, swapH, renderW, renderH, fmt)) {
                throw new IllegalStateException("DLSSG feature not ready (ensureFgFeature failed)");
            }
            ensureFgInterp(ctx, count, swapW, swapH, fmt);
            // clipToPrevClip = prevVP * inverse(curVP); prevClipToClip = curVP * inverse(prevVP). Both from
            // the (rotation-only, camera-relative) MV view-projections, so jitter-free.
            fgMatTmp.set(mvCurProjView).invert();
            fgClipToPrev.set(mvPrevProjView).mul(fgMatTmp);
            fgMatTmp.set(mvPrevProjView).invert();
            fgPrevToClip.set(mvCurProjView).mul(fgMatTmp);
        }
        if (index < 1 || index > fgInterp.length || fgInterp[index - 1] == null) {
            throw new IllegalStateException(
                    "fgInterpolate index " + index + " out of range for fgInterp[" + fgInterp.length + "]");
        }
        RtImage out = fgInterp[index - 1];
        // Only feed hudless/ui when they exist AND match this frame's backbuffer size — a stale or mismatched
        // size (e.g. mid-resize) is worse than skipping, so fall back to 0/0/0 (DLSSG just does without).
        RtImage hudlessSrc = hdrBackbuffer ? fgHdrHudlessImage : fgHudlessImage;
        boolean hudlessReady = hudlessSrc != null && hudlessSrc.width == swapW && hudlessSrc.height == swapH;
        long hudlessView = hudlessReady ? hudlessSrc.view : 0L;
        long hudlessImg = hudlessReady ? hudlessSrc.image : 0L;
        int hudlessFmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        boolean uiReady = RtUiOverlay.overlayWidth() == swapW && RtUiOverlay.overlayHeight() == swapH
                && RtUiOverlay.overlayColorView() != 0L && RtUiOverlay.overlayColorImage() != 0L;
        long uiView = uiReady ? RtUiOverlay.overlayColorView() : 0L;
        long uiImg = uiReady ? RtUiOverlay.overlayColorImage() : 0L;

        VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
        boolean ok = RtDlssFg.INSTANCE.evaluate(cmd.address(),
                backbufferView, backbufferImage, fmt,
                gDepth.view, gDepth.image, VK10.VK_FORMAT_R32_SFLOAT,
                gMotion.view, gMotion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                hudlessView, hudlessImg, hudlessReady ? hudlessFmt : 0,
                uiView, uiImg, uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                out.view, out.image, fmt,
                swapW, swapH, renderW, renderH, count, index, 1.0f, 1.0f,
                true /* depthInverted (reversed-Z) */, hdrBackbuffer /* colorBuffersHDR */,
                true /* cameraMotionIncluded (in mvecs) */, fgReset,
                fgClipToPrev, fgPrevToClip);
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg interpolate) failed");
        }
        fgReset = false;
        if (!ok) {
            throw new IllegalStateException("ngxshim_evaluate_dlssg failed (RtDlssFg.evaluate returned false)");
        }
        enc.execute(cmd);
        return out;
    }

    /**
     * FSR 3.1 branch of {@link #fgInterpolate}: one PREPARE + one GENERATE dispatch produce ALL
     * requested interpolated frames at {@code index == 1} (the FFX API generates them in a single
     * dispatch into {@code outputs[1..4]}); later indices just hand back the already-generated image.
     * Same fatal-on-failure / null-on-no-captured-frame contract as the DLSS branch. Camera position
     * and the three view-space axes come straight from the captured camera (rotation matrix rows:
     * Minecraft's +Z-forward view space makes row0/row1/row2 = right/up/forward in world space).
     */
    private RtImage fgInterpolateFsr(RtContext ctx, VulkanCommandEncoder enc, long backbufferImage,
            int swapW, int swapH, int index, int count, boolean hdrBackbuffer, int fmt) {
        if (index == 1) {
            if (!RtFsrFrameGen.INSTANCE.ensureFeature(swapW, swapH, renderW, renderH, fmt)) {
                throw new IllegalStateException("FSR FG feature not ready (ensureFeature failed)");
            }
            ensureFgInterp(ctx, count, swapW, swapH, fmt);
            if (fgInterp.length != count) {
                throw new IllegalStateException("FSR FG interp targets mismatch: " + fgInterp.length + " != " + count);
            }
            float fovY = (float) (2.0 * Math.atan(1.0 / Math.abs(frameProjection.m11())));
            long[] outputs = new long[count];
            for (int i = 0; i < count; i++) {
                outputs[i] = fgInterp[i].image;
            }
            // Rotation-only view matrix rows = the view-space axes in world coordinates
            // (Minecraft's +Z-forward view space: row0 right, row1 up, row2 forward).
            setVec3(camPosF, camX, camY, camZ);
            setVec3(camRightF, frameViewRotation.m00(), frameViewRotation.m01(), frameViewRotation.m02());
            setVec3(camUpF, frameViewRotation.m10(), frameViewRotation.m11(), frameViewRotation.m12());
            setVec3(camForwardF, frameViewRotation.m20(), frameViewRotation.m21(), frameViewRotation.m22());
            // FFX FG's GENERATE reads presentColor as GENERAL with the declared format taken
            // literally. The HDR backbuffer is already our own rgba16f GENERAL image, but the SDR
            // path feeds Minecraft's main target — TRANSFER_SRC layout (MC's own blit barrier ran
            // first in this encoder) and no contractually known format — so it gets blitted into
            // an owned RGBA8 GENERAL copy first. Feeding the target directly was undefined reads
            // (the flickering generated frames).
            long presentImage = backbufferImage;
            int presentFmt = fmt;
            if (!hdrBackbuffer) {
                recordFgBackbufferCopy(ctx, enc, backbufferImage, swapW, swapH);
                presentImage = fgBackbufferCopy.image;
                presentFmt = VK10.VK_FORMAT_R8G8B8A8_UNORM;
            }
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
            // Same jitter sign convention as the FSR upscale dispatch (negated).
            boolean ok = RtFsrFrameGen.INSTANCE.prepareAndGenerate(cmd.address(),
                    gDepth.image, gMotion.image, renderW, renderH,
                    -fgJitterX, -fgJitterY, fovY,
                    camPosF, camUpF, camRightF, camForwardF,
                    presentImage, presentFmt, outputs, count, swapW, swapH, hdrBackbuffer);
            // Sky mask right after the generate, in the same command buffer: FG's block-based
            // interpolation breaks on sky pixels (~0 depth + textureless gradients + 1-SPP hot
            // samples) and emits black blocks and gray smudges there — the sky flicker the player
            // sees whenever the sky is on screen. Every sky pixel of each generated frame is
            // replaced with the real frame's sky (see RtFgSkyMaskPipeline). The barrier makes the
            // FFX output writes visible to the mask's read-modify-write.
            ensureFgSkyMask(ctx);
            if (ok && fgSkyMaskPipeline != null) {
                try (MemoryStack maskStack = MemoryStack.stackPush()) {
                    VulkanCommandEncoder.memoryBarrier(cmd, maskStack);
                    long maskPresentView = hdrBackbuffer ? hdrBackbufferView() : fgBackbufferCopy.view;
                    for (int i = 0; i < count; i++) {
                        fgSkyMaskPipeline.dispatch(cmd, fgInterp[i].view, maskPresentView, gDepth.view,
                                swapW, swapH, renderW, renderH, hdrBackbuffer);
                    }
                }
            }
            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(fsr fg) failed");
            }
            if (!ok) {
                throw new IllegalStateException("fsrshim FG prepare/generate failed");
            }
            enc.execute(cmd);
        }
        if (index < 1 || index > fgInterp.length || fgInterp[index - 1] == null) {
            throw new IllegalStateException(
                    "FSR fgInterpolate index " + index + " out of range for fgInterp[" + fgInterp.length + "]");
        }
        return fgInterp[index - 1];
    }

    /**
     * Caustica native FG branch of {@link #fgInterpolate}: motion-vector interpolation between the
     * previous presented frame ({@link #fgPrevFrame}) and this frame's final image. At {@code index == 1}
     * it records ALL {@code count} generated frames at times t = (k+1)/(count+1) between prev (t=0)
     * and current (t=1); later indices just hand back the already-generated image. The renderer's
     * jitter-free motion vectors are exact per pixel (entities included), so no optical flow is
     * needed — the shader back-warps both real frames and blends them, with a forward/backward
     * consistency check falling back toward the current frame at occlusion boundaries.
     *
     * <p>The seeding tick (no previous frame captured yet — first frame, resize, teleport) returns
     * {@code null} for every index, which makes the presenter duplicate the real frame for just
     * that tick; interpolation starts on the next one. Same routine-fallback contract as the other
     * branches.
     */
    private RtImage fgInterpolateNative(RtContext ctx, VulkanCommandEncoder enc, long backbufferImage,
            int swapW, int swapH, int index, int count, boolean hdrBackbuffer, int fmt) {
        if (index == 1) {
            fgNativeSeededTick = false;
            ensureNativeFgPipeline(ctx);
            if (nativeFgPipeline == null) {
                throw new IllegalStateException("native FG pipeline not ready");
            }
            ensureFgInterp(ctx, count, swapW, swapH, fmt);
            if (fgInterp.length != count) {
                throw new IllegalStateException("native FG interp targets mismatch: " + fgInterp.length + " != " + count);
            }
            // Source selection — HUD-LESS whenever the UI overlay redirect captured one this tick
            // (hand, screen effects and GUI live on the overlay, not in the world image): the
            // interpolation then never sees screen-fixed content, so it cannot wobble the hotbar /
            // hand / overlays — the UI is stamped back onto every generated frame afterwards (see
            // the composite passes below). Without a hudless capture (overlay latched off), fall
            // back to interpolating the final frame as-is.
            RtImage hudless = hdrBackbuffer ? fgHdrHudlessImage : fgHudlessImage;
            boolean hudlessReady = hudless != null && hudless.width == swapW && hudless.height == swapH;
            long curView;
            long curImage;
            if (hudlessReady) {
                curView = hudless.view;
                curImage = hudless.image;
            } else if (!hdrBackbuffer) {
                // SDR fallback: main target (TRANSFER_SRC, no contractual format) into the owned copy.
                recordFgBackbufferCopy(ctx, enc, backbufferImage, swapW, swapH);
                curView = fgBackbufferCopy.view;
                curImage = fgBackbufferCopy.image;
            } else {
                curView = hdrBackbufferView();
                curImage = backbufferImage;
            }
            ensureFgPrevFrame(ctx, swapW, swapH, fmt);
            // Staleness guard: if FG hasn't interpolated for a couple of composite frames (menu /
            // loading gap / FG just toggled on), the stored previous frame no longer neighbours the
            // current one — drop it instead of blending across the gap.
            if (fgNativeLastUseFrame >= 0 && frameCounter - fgNativeLastUseFrame > 2) {
                fgPrevFrameValid = false;
            }
            fgNativeLastUseFrame = frameCounter;
            // Camera discontinuity (teleport / respawn / world change): the previous frame depicts a
            // different world; drop it so nothing of the old one leaks into the blend (same 32-block
            // rule the upscaler resets use).
            if (nativeFgCamValid) {
                double ndx = camX - prevNativeFgCamX;
                double ndy = camY - prevNativeFgCamY;
                double ndz = camZ - prevNativeFgCamZ;
                if (ndx * ndx + ndy * ndy + ndz * ndz > 32.0 * 32.0) {
                    fgPrevFrameValid = false;
                }
            }
            prevNativeFgCamX = camX;
            prevNativeFgCamY = camY;
            prevNativeFgCamZ = camZ;
            nativeFgCamValid = true;

            boolean hadPrev = fgPrevFrameValid;
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (hadPrev) {
                    // Make the cur source (captured earlier this frame) and last tick's prev-frame
                    // write visible.
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    for (int k = 0; k < count; k++) {
                        float t = (k + 1.0f) / (count + 1.0f);
                        nativeFgPipeline.dispatch(cmd, fgInterp[k].view, curView, fgPrevFrame.view, gMotion.view,
                                swapW, swapH, renderW, renderH, t, hdrBackbuffer);
                    }
                    // Sky mask on the generated frames (same protection as the FSR path): sky pixels
                    // copy the real frame's sky instead of trusting the blend at the horizon/sun edge.
                    ensureFgSkyMask(ctx);
                    if (fgSkyMaskPipeline != null) {
                        VulkanCommandEncoder.memoryBarrier(cmd, stack);
                        for (int k = 0; k < count; k++) {
                            fgSkyMaskPipeline.dispatch(cmd, fgInterp[k].view, curView, gDepth.view,
                                    swapW, swapH, renderW, renderH, hdrBackbuffer);
                        }
                    }
                    // UI re-composite (hudless path only): stamp this tick's overlay — hand, GUI,
                    // screen effects, mod overlays — onto every generated frame, identical across the
                    // group, which is what stops screen-fixed content from wobbling.
                    long overlayView = hudlessReady ? RtUiOverlay.overlayColorView() : 0L;
                    boolean uiReady = overlayView != 0L
                            && RtUiOverlay.overlayWidth() == swapW && RtUiOverlay.overlayHeight() == swapH;
                    if (uiReady && ensureUiSampler(ctx)) {
                        VulkanCommandEncoder.memoryBarrier(cmd, stack);
                        if (hdrBackbuffer) {
                            ensureHdrUiResources();
                            if (hdrCompositePipeline != null) {
                                for (int k = 0; k < count; k++) {
                                    hdrCompositePipeline.setImages(fgInterp[k].view, overlayView, hdrUiSampler);
                                    hdrCompositePipeline.dispatch(cmd, swapW, swapH,
                                            CausticaConfig.Rt.Hdr.paperWhiteNits());
                                }
                            }
                        } else {
                            ensureFgUiComposite(ctx);
                            if (fgUiCompositePipeline != null) {
                                for (int k = 0; k < count; k++) {
                                    fgUiCompositePipeline.dispatch(cmd, fgInterp[k].view, overlayView,
                                            hdrUiSampler, swapW, swapH);
                                }
                            }
                        }
                    }
                    // Interp reads of fgPrevFrame are done; the advance below may overwrite it.
                    VulkanCommandEncoder.memoryBarrier(cmd, stack);
                }
                // Advance the history for the next tick: this frame becomes the previous frame.
                VK10.vkCmdCopyImage(cmd, curImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        fgPrevFrame.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, swapW, swapH));
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // prev write visible to the next tick's read
            }
            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(native fg) failed");
            }
            enc.execute(cmd);
            fgPrevFrameValid = true;
            if (!hadPrev) {
                fgNativeSeededTick = true; // every index this tick falls back to the real frame
                return null;
            }
        }
        if (fgNativeSeededTick) {
            return null; // seeding tick: no generated content for any slot yet
        }
        if (index < 1 || index > fgInterp.length || fgInterp[index - 1] == null) {
            throw new IllegalStateException(
                    "native fgInterpolate index " + index + " out of range for fgInterp[" + fgInterp.length + "]");
        }
        return fgInterp[index - 1];
    }

    // Native FG camera-jump bookkeeping (see fgInterpolateNative).
    private double prevNativeFgCamX;
    private double prevNativeFgCamY;
    private double prevNativeFgCamZ;
    private boolean nativeFgCamValid;
    // Set when the current tick seeded the previous-frame history: every fgInterpolate index of
    // this tick returns null so the presenter duplicates the real frame (no blend against garbage).
    private boolean fgNativeSeededTick;

    private final float[] camPosF = new float[3];
    private final float[] camRightF = new float[3];
    private final float[] camUpF = new float[3];
    private final float[] camForwardF = new float[3];

    /** Fill a scratch float[3] (avoids per-frame allocation at present time). */
    private static void setVec3(float[] dst, double x, double y, double z) {
        dst[0] = (float) x;
        dst[1] = (float) y;
        dst[2] = (float) z;
    }

    private boolean ensureFgFeature(RtContext ctx, int w, int h, int rw, int rh, int fmt) {
        if (RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt)) {
            return true;
        }
        // Create the feature in its own submit + wait (not folded into MC's frame submit).
        ctx.submitSync(c -> RtDlssFg.INSTANCE.ensureFeature(c.address(), w, h, rw, rh, fmt));
        fgReset = true; // fresh feature has no temporal history
        return RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt);
    }

    private void ensureFgInterp(RtContext ctx, int count, int w, int h, int fmt) {
        if (fgInterp.length == count && fgInterpW == w && fgInterpH == h && fgInterpFormat == fmt
                && (count == 0 || fgInterp[0] != null)) {
            return;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[count];
        for (int i = 0; i < count; i++) {
            fgInterp[i] = ctx.createStorageImage(w, h, fmt, "FG interp " + i + " " + w + "x" + h);
        }
        fgInterpW = w;
        fgInterpH = h;
        fgInterpFormat = fmt;
    }

    /** Owned GENERAL/RGBA8 target for the SDR FG backbuffer copy (see fgBackbufferCopy's docs). */
    private void ensureFgBackbufferCopy(RtContext ctx, int w, int h) {
        if (fgBackbufferCopy != null && fgBackbufferCopyW == w && fgBackbufferCopyH == h) {
            return;
        }
        if (fgBackbufferCopy != null) {
            fgBackbufferCopy.destroy();
        }
        fgBackbufferCopy = ctx.createStorageImage(w, h, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                "FG backbuffer copy " + w + "x" + h);
        fgBackbufferCopyW = w;
        fgBackbufferCopyH = h;
    }

    /**
     * Record the SDR main-target -&gt; {@link #fgBackbufferCopy} blit into the encoder (own command
     * buffer, executed immediately). Minecraft's main target arrives in TRANSFER_SRC layout with no
     * contractually known format; the FG consumers read GENERAL with the format taken literally, so
     * this copy makes both certain (see fgBackbufferCopy's docs). Shared by the FSR and native FG
     * branches.
     */
    private void recordFgBackbufferCopy(RtContext ctx, VulkanCommandEncoder enc, long backbufferImage,
            int swapW, int swapH) {
        ensureFgBackbufferCopy(ctx, swapW, swapH);
        VkCommandBuffer copyCmd = enc.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack copyStack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, copyStack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(fgBackbufferCopy.image);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            // The main target's TRANSFER_SRC state + contents were made available by MC's
            // barrier earlier in this encoder; a global memory dependency chains this blit
            // after it (same pattern the FG present blits use).
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, copyStack).sType$Default();
            srcVis.get(0).srcStageMask(1024L | 4096L).srcAccessMask(256L | 8L)
                    .dstStageMask(4096L).dstAccessMask(8L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(copyStack).sType$Default()
                    .pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(copyCmd, dep1);
            // Straight (non-flipped) full-rect blit: orientation stays as-is here; the
            // Y-flip for the display happens when the generated frames hit the swapchain.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, copyStack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(swapW, swapH, 1);
            region.get(0).dstOffsets(1).set(swapW, swapH, 1);
            VK10.vkCmdBlitImage(copyCmd, backbufferImage, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    fgBackbufferCopy.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region,
                    VK10.VK_FILTER_NEAREST);
            VkImageMemoryBarrier2.Buffer toGeneral = VkImageMemoryBarrier2.calloc(1, copyStack).sType$Default();
            toGeneral.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(98304L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(fgBackbufferCopy.image);
            toGeneral.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(copyStack).sType$Default().pImageMemoryBarriers(toGeneral);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(copyCmd, dep2);
        }
        if (VK10.vkEndCommandBuffer(copyCmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg backbuffer copy) failed");
        }
        enc.execute(copyCmd);
    }

    /** Set when the native FG pipeline failed to create; native FG is skipped for the session. */
    private void ensureNativeFgPipeline(RtContext ctx) {
        if (nativeFgPipeline != null || nativeFgFailed) {
            return;
        }
        try {
            nativeFgPipeline = RtNativeFrameGenPipeline.create(ctx);
            CausticaMod.LOGGER.info("Caustica native frame generation active (motion-vector interpolation)");
        } catch (Throwable t) {
            nativeFgFailed = true;
            CausticaMod.LOGGER.error("native FG pipeline creation failed; frame generation disabled", t);
        }
    }

    /** Lazily create the SDR UI re-composite pipeline for native FG's generated frames. */
    private void ensureFgUiComposite(RtContext ctx) {
        if (fgUiCompositePipeline != null || fgUiCompositeFailed) {
            return;
        }
        try {
            fgUiCompositePipeline = RtFgUiCompositePipeline.create(ctx);
        } catch (Throwable t) {
            fgUiCompositeFailed = true;
            CausticaMod.LOGGER.error("FG UI composite pipeline creation failed; generated frames stay HUD-less", t);
        }
    }

    /**
     * Whether the FG stack needs the pre-UI ("hudless") snapshot of the frame this tick: DLSS-FG
     * consumes it as its hudless resource, and the native engine interpolates it (then stamps the
     * UI overlay back onto every generated frame) so screen-fixed content doesn't wobble.
     */
    private static boolean fgHudlessNeeded() {
        return RtDlssFg.enabled() || RtNativeFrameGen.enabled();
    }

    /** Owned previous-frame history target for the native FG interpolator (see fgPrevFrame's docs). */
    private void ensureFgPrevFrame(RtContext ctx, int w, int h, int fmt) {
        if (fgPrevFrame != null && fgPrevFrameW == w && fgPrevFrameH == h && fgPrevFrameFormat == fmt) {
            return;
        }
        if (fgPrevFrame != null) {
            fgPrevFrame.destroy();
        }
        fgPrevFrame = ctx.createStorageImage(w, h, fmt, "native FG prev frame " + w + "x" + h);
        fgPrevFrameW = w;
        fgPrevFrameH = h;
        fgPrevFrameFormat = fmt;
        fgPrevFrameValid = false; // fresh image: no history to blend against
    }

    /** Set when the sky-mask pipeline failed to create; the mask is skipped for the session. */
    private boolean fgSkyMaskFailed;

    /**
     * Lazily create the FG sky-mask pipeline on first FG use (FG toggles at runtime, so it can't
     * ride the resource-creation block). Failure degrades gracefully: generated frames keep the
     * raw FG sky (the pre-mask artifact state) instead of killing FG entirely.
     */
    private void ensureFgSkyMask(RtContext ctx) {
        if (fgSkyMaskPipeline != null || fgSkyMaskFailed) {
            return;
        }
        try {
            fgSkyMaskPipeline = RtFgSkyMaskPipeline.create(ctx);
            CausticaMod.LOGGER.info("FG sky mask active (generated frames copy the real frame's sky)");
        } catch (Throwable t) {
            fgSkyMaskFailed = true;
            CausticaMod.LOGGER.error("FG sky mask pipeline creation failed; generated frames keep raw FG sky", t);
        }
    }
}
