package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import dev.comfyfluffy.caustica.client.gui.RtSharcOptionsScreen;
import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import dev.comfyfluffy.caustica.compat.VoxyCompat;
import dev.comfyfluffy.caustica.rt.RtSharc;
import dev.comfyfluffy.caustica.rt.terrain.RtDistantHorizonsTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown by the Ray Tracing settings hub and its sub-screens
 * ({@code RtVideoOptionsScreen} / {@code RtSubScreens} / {@code RtSettingsSubScreen}, reached from
 * Video Settings through {@code VideoSettingsScreenMixin}'s "Ray Tracing Settings..." button). Each
 * option is bound straight to a {@link CausticaConfig} runtime setting: the initial value is read
 * from the current config, and the value-update listener writes back through {@code set(...)} so
 * changes take effect on the next frame.
 *
 * <p>The hub is a pure directory: every feature group (Quality, Upscaling/DLSS-RR, Frame Generation,
 * Lighting &amp; ReSTIR, Sky, World &amp; Entities, Effects, Water, POM, Clouds, Exposure,
 * Tonemapping, HDR, Terrain Streaming, Debug, SHaRC) opens its own sub-screen, and every sub-screen
 * starts with a "Reset to Defaults" row covering exactly the settings its group exposes (the hub
 * offers the matching global reset — see {@code RtSubScreens.allResettableSettings()}).
 *
 * <p>Only settings the renderer re-reads per-frame — or can apply through a bounded rebuild — are
 * exposed here. Toggles that would require a device or buffer-pool rebuild (worker threads,
 * max-entity capacities, PBR material flags) are intentionally left to the {@code -Dcaustica.*}
 * startup surface. A few exceptions are handled specially: DLSS-RR quality recreates the RR feature
 * live on change (see {@code RtDlssRr}), and the opacity-micromap toggle/subdivision plus
 * BLAS-compaction are read at section BUILD time and answered with a full terrain rebuild, so all
 * of them are safe to expose.
 *
 * <p>Upscaling group: {@link #qualityOptions(Runnable)} starts with the upscaler selector
 * (Off / DLSS, plus FSR 3/XeSS where bundled — see {@code RtUpscalerSupport}), and the rows that
 * depend on that selection are built from the currently selected upscaler. Changing the selector
 * reopens the screen through the supplied refresh callback so those rows switch together with the
 * upscaler instead of lagging one screen behind.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    // ===== Organized groups for the dedicated RT settings screen and its sub-screens =====
    //
    // Every group is one sub-screen's option block (see RtSubScreens); each sub-screen opens from a
    // button on the hub screen and starts with a "Reset to Defaults" row covering exactly the
    // settings its group touches. Only settings the renderer re-reads per frame — or that apply
    // through a bounded rebuild (the OMM and BLAS-compaction rows request a full terrain rebuild) —
    // are exposed: worker threads, entity buffer capacities and other allocation-time sizing stay
    // on the -Dcaustica.* / config-file surface.

    /**
     * Quality section, headed by the upscaler selector. The selector's dependent rows — the quality
     * slider (DLSS modes today, FSR modes once that backend lands) and, via
     * {@link #frameGenerationButton()}, the Frame Generation toggle — are derived from the currently
     * selected upscaler; {@code upscalerChanged} reopens the screen so they switch together with it.
     * (The old standalone "Denoising Filter" toggle folded into the selector: Off is exactly the raw
     * full-resolution view the toggle used to select.)
     */
    public static OptionInstance<?>[] qualityOptions(Runnable upscalerChanged) {
        List<OptionInstance<?>> options = new ArrayList<>();
        options.add(spp());
        options.add(maxBounces());
        options.add(upscaler(upscalerChanged));
        String mode = RtUpscalerSupport.currentUpscalerMode();
        if (RtUpscalerSupport.MODE_DLSS.equals(mode)) {
            options.add(dlssQuality());
            options.add(dlssPreset());
        } else if (RtUpscalerSupport.MODE_FSR3.equals(mode)) {
            options.add(fsrQuality());
        } else if (RtUpscalerSupport.MODE_XESS.equals(mode)) {
            options.add(xessQuality());
        }
        // Denoiser rows, for every path except DLSS (Ray Reconstruction denoises internally, so it
        // owns the slot and neither row would do anything).
        //
        // The built-in SVGF is the only denoiser offered. REBLUR is no longer exposed: it kept its
        // own temporal history inside the library, so the reprojection fixes that cleaned up the
        // built-in path could not reach it, and it stayed blobby where SVGF is now stable. The
        // integration is left in the tree (the CI still builds the shim) but nothing turns it on.
        if (!RtUpscalerSupport.MODE_DLSS.equals(mode)) {
            options.add(svgfDenoiser());
        }
        options.add(omm());
        options.add(ommSubdivision());
        return options.toArray(OptionInstance<?>[]::new);
    }

    /**
     * The upscaling sub-screen's rows: the same selector that heads the Quality section, then every
     * knob of the selected backend — DLSS Ray Reconstruction gets quality, model preset and its
     * denoise filter; FSR 3 / XeSS get their quality slider and the built-in denoiser. Changing the
     * selector reopens the sub-screen (the qualityOptions pattern), so the rows below it always
     * match the active backend.
     */
    public static OptionInstance<?>[] upscalingOptions(Runnable upscalerChanged) {
        List<OptionInstance<?>> options = new ArrayList<>();
        options.add(upscaler(upscalerChanged));
        String mode = RtUpscalerSupport.currentUpscalerMode();
        if (RtUpscalerSupport.MODE_DLSS.equals(mode)) {
            options.add(dlssQuality());
            options.add(dlssPreset());
            options.add(denoiser());
        } else if (RtUpscalerSupport.MODE_FSR3.equals(mode)) {
            options.add(fsrQuality());
            options.add(svgfDenoiser());
        } else if (RtUpscalerSupport.MODE_XESS.equals(mode)) {
            options.add(xessQuality());
            options.add(svgfDenoiser());
        } else {
            options.add(svgfDenoiser());
        }
        return options.toArray(OptionInstance<?>[]::new);
    }

    /** Sky / celestial and first-person viewmodel rows. */
    public static OptionInstance<?>[] skyOptions() {
        return new OptionInstance<?>[] {
            sunSize(),
            moonSize(),
            sunSouthTilt(),
            handFov(),
        };
    }

    /** Entity, particle, weather and overlay rows. */
    public static OptionInstance<?>[] worldOptions() {
        return new OptionInstance<?>[] {
            entities(),
            particles(),
            weatherParticles(),
            rainDensity(),
            entityGlow(),
            nameTags(),
            blockOutline(),
        };
    }

    /** Animated Water rows: the geometric wave toggle, the extinction slider and the spectrum knobs. */
    public static OptionInstance<?>[] waterOptions() {
        return new OptionInstance<?>[] {
            waterWaves(),
            waterOpacity(),
            waterWaveStrength(),
            waterWaveSpeed(),
            waterWaveDetail(),
        };
    }

    /**
     * Terrain streaming + BLAS rows. The three per-pass budgets are read by RtTerrain's scheduler
     * every pass, so a slider drag takes effect immediately; the BLAS flags are read at section
     * build time, so their change handlers request a full terrain rebuild (same mechanism as the
     * OMM toggle / F3+A) to make the new state uniform.
     */
    public static OptionInstance<?>[] streamingOptions() {
        return new OptionInstance<?>[] {
            asyncDispatchPerPass(),
            resultsPerPass(),
            maxInflightSections(),
            blasCompaction(),
        };
    }

    public static OptionInstance<?>[] effectsOptions() {
        return new OptionInstance<?>[] {
            subsurfaceScattering(),
            weatherLighting(),
            metallicShininess(),
        };
    }

    public static OptionInstance<?>[] pomOptions() {
        return new OptionInstance<?>[] {
            parallaxEnabled(),
            parallaxStrength(),
            parallaxQuality(),
            parallaxSmoothing(),
            parallaxDistance(),
        };
    }

    /**
     * The clouds sub-screen rows. {@code styleChanged} reopens the screen when the deck style flips,
     * and the classic deck's coverage slider is left out entirely: coverage is baked into the flat
     * texture in that style, so showing the row would offer a knob that changes nothing — the
     * sub-screen replaces it with {@link #cloudCoverageDisabledHint()}'s greyed-out explanation.
     * Volumetric reads the live coverage field, so the slider stays.
     */
    public static OptionInstance<?>[] cloudOptions(Runnable styleChanged) {
        List<OptionInstance<?>> options = new ArrayList<>();
        options.add(clouds());
        options.add(cloudStyle(styleChanged));
        if (!"classic".equals(CausticaConfig.Rt.Composite.CLOUD_STYLE.get())) {
            options.add(cloudCoverage());
        }
        options.add(cloudHeight());
        options.add(cloudThickness());
        options.add(cloudShadowStrength());
        options.add(cloudOpacity());
        return options.toArray(OptionInstance<?>[]::new);
    }

    /**
     * The volumetric fog rows: the master toggle first (off costs one comparison in the shaders,
     * and every other row still tunes a live lane so the fog can be dialled in without reopening
     * the switch). See fog.slang for the method and CausticaConfig for the ranges.
     */
    public static OptionInstance<?>[] fogOptions() {
        return new OptionInstance<?>[] {
            fogEnabled(),
            fogDensity(),
            fogDistance(),
            fogAnisotropy(),
            fogHeightFalloff(),
            fogBiomeTint(),
        };
    }

    public static OptionInstance<?>[] hdrOptions() {
        return new OptionInstance<?>[] {
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
        };
    }

    /** Debug tooling rows: the buffer visualizer plus the logging/capture switches. */
    public static OptionInstance<?>[] debugOptions() {
        return new OptionInstance<?>[] {
            debugView(),
            frameStats(),
            ommStats(),
            lightStats(),
            lightDump(),
            lightDumpRadius(),
        };
    }

    /** Exposure rows: the mode picker and manual EV, then the auto-exposure internals. */
    public static OptionInstance<?>[] exposureOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            exposureKey(),
            exposureMinEv(),
            exposureMaxEv(),
            adaptUp(),
            adaptDown(),
        };
    }

    /**
     * Light-emission and sampling options. {@code heldItemLight} toggles the analytic light a luminous
     * held item casts; {@code dynamicIntensity} scales that light and other dynamic emitters. Both take
     * effect the frame after they change. The ReSTIR rows include the live anti-flicker tuning pushed
     * in {@code WorldPush.restirTuning} (more history / neighbours = steadier light at the cost of
     * ghosting and overhead, max-age = how long history may live).
     */
    public static OptionInstance<?>[] lightOptions() {
        return new OptionInstance<?>[] {
            heldItemLight(),
            blockEmissiveIntensity(),
            dynamicIntensity(),
            minFillRatio(),
            risCandidates(),
            restirSampling(),
            restirHistory(),
            restirSpatialNeighbours(),
            restirMaxAge(),
        };
    }

    /** Tonemapping options (without exposure, which now has its own section) */
    public static OptionInstance<?>[] tonemapOptions() {
        return new OptionInstance<?>[] {
            tonemapOperator(),
            tonemapExposure(),
            tonemapGamma(),
            tonemapSaturation(),
            tonemapContrast(),
        };
    }

    /** Reflex advanced rows for the Frame Generation & Latency sub-screen (Reflex ≤ 20000 µs cap). */
    public static OptionInstance<?>[] reflexOptions() {
        return new OptionInstance<?>[] {
            bool("caustica.options.rt.reflexBoost", CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST),
            reflexMinInterval(),
        };
    }

    private static OptionInstance<Integer> reflexMinInterval() {
        IntSetting setting = CausticaConfig.Rt.Reflex.MINIMUM_INTERVAL_US;
        return new OptionInstance<>(
            "caustica.options.rt.reflexMinInterval",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.reflexMinInterval.tooltip")),
            (caption, micros) -> Options.genericValueLabel(caption, micros == 0
                    ? Component.translatable("caustica.options.rt.reflexMinInterval.off")
                    : Component.literal(micros + " us")),
            new OptionInstance.IntRange(0, 20000),
            Math.clamp(setting.value(), 0, 20000),
            setting::set);
    }

    // ===== New rows shared by the sub-screens (sky/world/water/POM/clouds/exposure/ReSTIR/streaming) =====

    /** Angular radius of the moon disc, in tenths of a degree like {@link #sunSize()}. */
    private static OptionInstance<Integer> moonSize() {
        FloatSetting setting = CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 80);
        return new OptionInstance<>(
            "caustica.options.rt.moonSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.moonSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 80),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    /** How far south of the zenith the noon sun sits, in whole degrees (stored in radians). */
    private static OptionInstance<Integer> sunSouthTilt() {
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT;
        int initialDegrees = Math.clamp(Math.round((float) Math.toDegrees(setting.value())), -80, 80);
        return new OptionInstance<>(
            "caustica.options.rt.sunSouthTilt",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSouthTilt.tooltip")),
            (caption, degrees) -> {
                String sign = degrees > 0 ? "+" : "";
                return Options.genericValueLabel(caption, Component.literal(sign + degrees + "°"));
            },
            new OptionInstance.IntRange(-80, 80),
            initialDegrees,
            degrees -> setting.set(degrees.floatValue()));
    }

    private static OptionInstance<Boolean> entityGlow() {
        return bool("caustica.options.rt.glow", CausticaConfig.Rt.Entities.GLOW_ENABLED);
    }

    private static OptionInstance<Boolean> nameTags() {
        return bool("caustica.options.rt.nameTags", CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED);
    }

    private static OptionInstance<Boolean> blockOutline() {
        return bool("caustica.options.rt.blockOutline", CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED);
    }

    /** Wave height multiplier: 0x flattens the sea even with Animated Water on, up to 4x for a storm. */
    private static OptionInstance<Integer> waterWaveStrength() {
        return multiplier("caustica.options.rt.waterWaveStrength",
                CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH, 0, 40);
    }

    /** Wave animation speed: 0x freezes the sea in place, up to 4x for a time-lapse ocean. */
    private static OptionInstance<Integer> waterWaveSpeed() {
        return multiplier("caustica.options.rt.waterWaveSpeed",
                CausticaConfig.Rt.Composite.WATER_WAVE_SPEED, 0, 40);
    }

    /**
     * How many wave-spectrum components Animated Water evaluates. The authored maximum is 7 (28 m
     * swell down to ~1.6 m chop); lowering it drops the short-wave detail first and is the cheapest
     * way to speed up large ocean views.
     */
    private static OptionInstance<Integer> waterWaveDetail() {
        IntSetting setting = CausticaConfig.Rt.Composite.WATER_WAVE_DETAIL;
        return new OptionInstance<>(
            "caustica.options.rt.waterWaveDetail",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.waterWaveDetail.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.waterWaveDetail.count", value)),
            new OptionInstance.IntRange(1, 7),
            Math.clamp(setting.value(), 1, 7),
            setting::set);
    }

    /**
     * POM sampling level: multiplies the per-hit texel-crossing budget on top of the depth-derived
     * base (0.3x–4.0x; the config clamps the stored value to 0.25x–4.0x). Higher keeps fine relief
     * intact at grazing angles on high-resolution packs; lower saves height-field samples.
     */
    private static OptionInstance<Integer> parallaxQuality() {
        return multiplier("caustica.options.rt.parallaxQuality",
                CausticaConfig.Rt.Composite.PARALLAX_QUALITY, 3, 40);
    }

    /** Clear-weather sky coverage; rain drives the deck toward overcast on top of this. */
    private static OptionInstance<Integer> cloudCoverage() {
        return percent("caustica.options.rt.cloudCoverage", CausticaConfig.Rt.Composite.CLOUD_COVERAGE);
    }

    private static OptionInstance<Integer> risCandidates() {
        IntSetting setting = CausticaConfig.Rt.Lights.RIS_CANDIDATES;
        return new OptionInstance<>(
            "caustica.options.rt.risCandidates",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.risCandidates.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value == 0
                    ? Component.translatable("options.off")
                    : Component.literal(value + "")),
            new OptionInstance.IntRange(0, 32),
            Math.clamp(setting.value(), 0, 32),
            setting::set);
    }

    /** Drops sparse emissive footprints from the light buffer (0% keeps everything). */
    private static OptionInstance<Integer> minFillRatio() {
        return percent("caustica.options.rt.minFillRatio", CausticaConfig.Rt.Lights.MIN_FILL_RATIO);
    }

    // ---- ReSTIR anti-flicker knobs (pushed in WorldPush.restirTuning) ----

    /** Temporal effective-sample cap: more history = steadier light, more ghosting on moving lights. */
    private static OptionInstance<Integer> restirHistory() {
        IntSetting setting = CausticaConfig.Rt.Lights.RESTIR_TEMPORAL_HISTORY;
        return new OptionInstance<>(
            "caustica.options.rt.restirHistory",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.restirHistory.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.restirHistory.samples", value)),
            new OptionInstance.IntRange(1, 16),
            Math.clamp(setting.value(), 1, 16),
            setting::set);
    }

    /** Spatial neighbour reservoirs queried per pixel; 0 keeps the purely temporal ReSTIR path. */
    private static OptionInstance<Integer> restirSpatialNeighbours() {
        IntSetting setting = CausticaConfig.Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS;
        return new OptionInstance<>(
            "caustica.options.rt.restirSpatial",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.restirSpatial.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.restirSpatial.count", value)),
            new OptionInstance.IntRange(0, 8),
            Math.clamp(setting.value(), 0, 8),
            setting::set);
    }

    /** How many frames a reservoir may live before history rejects it. */
    private static OptionInstance<Integer> restirMaxAge() {
        IntSetting setting = CausticaConfig.Rt.Lights.RESTIR_MAX_AGE;
        return new OptionInstance<>(
            "caustica.options.rt.restirMaxAge",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.restirMaxAge.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.restirMaxAge.frames", value)),
            new OptionInstance.IntRange(4, 240),
            Math.clamp(setting.value(), 4, 240),
            setting::set);
    }

    // ---- Auto-exposure internals ----

    /** The key value auto exposure targets (scene middle grey), as a 0.01–1.00 slider. */
    private static OptionInstance<Integer> exposureKey() {
        return hundredths("caustica.options.rt.exposureKey", CausticaConfig.Rt.Exposure.KEY, 1, 100);
    }

    private static OptionInstance<Integer> exposureEvSlider(String captionKey, FloatSetting setting) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-80, 80),
            Math.clamp(Math.round(setting.value() * 10.0f), -80, 80),
            tenths -> setting.set(tenths / 10.0f));
    }

    /** Darkest exposure the auto mode may reach (the two sliders swap roles if crossed). */
    private static OptionInstance<Integer> exposureMinEv() {
        return exposureEvSlider("caustica.options.rt.exposureMinEv", CausticaConfig.Rt.Exposure.MIN_EV);
    }

    /** Brightest exposure the auto mode may reach. */
    private static OptionInstance<Integer> exposureMaxEv() {
        return exposureEvSlider("caustica.options.rt.exposureMaxEv", CausticaConfig.Rt.Exposure.MAX_EV);
    }

    /** How fast auto exposure brightens toward its target (scale/second). */
    private static OptionInstance<Integer> adaptUp() {
        return hundredths("caustica.options.rt.adaptUp", CausticaConfig.Rt.Exposure.ADAPT_UP, 1, 400);
    }

    /** How fast auto exposure darkens toward its target (scale/second). */
    private static OptionInstance<Integer> adaptDown() {
        return hundredths("caustica.options.rt.adaptDown", CausticaConfig.Rt.Exposure.ADAPT_DOWN, 1, 400);
    }

    // ---- Terrain streaming / BLAS rows ----

    private static OptionInstance<Integer> intSlider(String captionKey, IntSetting setting, int min, int max) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(min, max),
            Math.clamp(setting.value(), min, max),
            setting::set);
    }

    private static OptionInstance<Integer> asyncDispatchPerPass() {
        return intSlider("caustica.options.rt.asyncDispatch", CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_PASS, 0, 256);
    }

    private static OptionInstance<Integer> resultsPerPass() {
        return intSlider("caustica.options.rt.resultsPerPass", CausticaConfig.Rt.Terrain.COMPLETION_RESULTS_PER_PASS, 0, 256);
    }

    private static OptionInstance<Integer> maxInflightSections() {
        return intSlider("caustica.options.rt.maxInflight", CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS, 4, 128);
    }

    /**
     * BLAS compaction halves acceleration-structure memory at a slightly longer build. Read at
     * section build time, so the change handler requests a full rebuild (same mechanism as the OMM
     * toggle) instead of leaving old sections in the previous state.
     */
    private static OptionInstance<Boolean> blasCompaction() {
        return OptionInstance.createBoolean(
            "caustica.options.rt.blasCompaction",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.blasCompaction.tooltip")),
            CausticaConfig.Rt.Terrain.BLAS_COMPACTION.value(),
            value -> {
                CausticaConfig.Rt.Terrain.BLAS_COMPACTION.set(value);
                RtTerrain.requestFullClear();
            });
    }

    /**
     * OMM micro-triangle subdivision level, read when a section BLAS is built (see RtTerrainOmm).
     * Like the toggle below it, a change only takes hold for NEW builds, so the handler requests a
     * full terrain rebuild to make the level uniform immediately.
     */
    private static OptionInstance<Integer> ommSubdivision() {
        IntSetting setting = CausticaConfig.Rt.Omm.SUBDIVISION;
        return new OptionInstance<>(
            "caustica.options.rt.ommSubdivision",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.ommSubdivision.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(0, 6),
            Math.clamp(setting.value(), 0, 6),
            value -> {
                setting.set(value);
                RtTerrain.requestFullClear();
            });
    }

    // ---- Debug rows ----

    private static OptionInstance<Boolean> frameStats() {
        return bool("caustica.options.rt.frameStats", CausticaConfig.Rt.FrameStats.ENABLED);
    }

    private static OptionInstance<Boolean> ommStats() {
        return bool("caustica.options.rt.ommStats", CausticaConfig.Rt.Omm.STATS);
    }

    private static OptionInstance<Boolean> lightStats() {
        return bool("caustica.options.rt.lightStats", CausticaConfig.Rt.Lights.STATS);
    }

    private static OptionInstance<Boolean> lightDump() {
        return bool("caustica.options.rt.lightDump", CausticaConfig.Rt.Lights.DUMP);
    }

    private static OptionInstance<Integer> lightDumpRadius() {
        return intSlider("caustica.options.rt.lightDumpRadius", CausticaConfig.Rt.Lights.DUMP_RADIUS, 1, 64);
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = CausticaConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "caustica.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = CausticaConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "caustica.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = CausticaConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "caustica.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = CausticaConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "caustica.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "caustica.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }


    private static OptionInstance<Integer> blockEmissiveIntensity() {
        return multiplier("caustica.options.rt.blockEmissiveIntensity",
                CausticaConfig.Rt.Lights.BLOCK_INTENSITY, 0, 160);
    }

    /** Held-item / dynamic emitter brightness. Same 0x..16x domain as the block-emissive slider. */
    private static OptionInstance<Integer> dynamicIntensity() {
        return multiplier("caustica.options.rt.dynamicIntensity",
                CausticaConfig.Rt.Lights.DYNAMIC_INTENSITY, 0, 160);
    }

    /** Master toggle for the analytic light a luminous held item casts (torch in a cave, ...). */
    private static OptionInstance<Boolean> heldItemLight() {
        return bool("caustica.options.rt.heldItemLight", CausticaConfig.Rt.Lights.HELD_ITEM_LIGHT);
    }

    private static OptionInstance<Boolean> restirSampling() {
        return bool("caustica.options.rt.restirSampling", CausticaConfig.Rt.Lights.RESTIR_SAMPLING);
    }

    private static final List<String> TONEMAP_OPERATORS =
            List.of("agx", "pbr_neutral", "aces", "filmic", "linear", "psychov");

    private static OptionInstance<String> tonemapOperator() {
        StringSetting setting = CausticaConfig.Rt.Tonemapping.OPERATOR;
        return new OptionInstance<>(
            "caustica.options.rt.tonemapOperator",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.tonemapOperator.tooltip")),
            (caption, value) -> Component.translatable("caustica.options.rt.tonemapOperator." + value),
            new OptionInstance.Enum<>(TONEMAP_OPERATORS, Codec.STRING),
            TONEMAP_OPERATORS.contains(setting.get()) ? setting.get() : "agx",
            setting::set);
    }

    private static OptionInstance<Integer> tonemapExposure() {
        FloatSetting setting = CausticaConfig.Rt.Tonemapping.EXPOSURE_EV;
        return new OptionInstance<>(
            "caustica.options.rt.tonemapExposure",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.tonemapExposure.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> tonemapGamma() {
        return hundredths("caustica.options.rt.tonemapGamma",
                CausticaConfig.Rt.Tonemapping.GAMMA, 50, 300);
    }

    private static OptionInstance<Integer> tonemapSaturation() {
        return hundredths("caustica.options.rt.tonemapSaturation",
                CausticaConfig.Rt.Tonemapping.SATURATION, 0, 300);
    }

    private static OptionInstance<Integer> tonemapContrast() {
        return hundredths("caustica.options.rt.tonemapContrast",
                CausticaConfig.Rt.Tonemapping.CONTRAST, 0, 300);
    }

    private static OptionInstance<Boolean> entities() {
        return bool("caustica.options.rt.entities", CausticaConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("caustica.options.rt.particles", CausticaConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    /**
     * Ray-traced rain and snow. Caustica cancels vanilla's world renderer, and vanilla draws weather
     * inside it, so this switch is what puts precipitation in the world at all — the same relationship
     * the clouds toggle has. Weather shares the particle mesh, so it also needs Ray Traced Particles on.
     */
    private static OptionInstance<Boolean> weatherParticles() {
        return bool("caustica.options.rt.weatherParticles", CausticaConfig.Rt.Entities.WEATHER_ENABLED);
    }

    private static OptionInstance<Integer> rainDensity() {
        return percent("caustica.options.rt.rainDensity", CausticaConfig.Rt.Entities.RAIN_DENSITY);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
    }

    /**
     * How opaque ray-traced water is, as a percentage. 0% keeps the default near-crystal clarity;
     * 100% makes a one-block column transmit under 2% of what is behind it. Mostly a mitigation
     * control: DLSS Ray Reconstruction can draw a halo around translucent water in dark scenes,
     * and dimming the transmitted light through the water is what removes it.
     */
    private static OptionInstance<Integer> waterOpacity() {
        return percent("caustica.options.rt.waterOpacity", CausticaConfig.Rt.Composite.WATER_OPACITY);
    }

    /**
     * LabPBR subsurface scattering: backlit foliage glow. Only materials that author an SSS channel are
     * affected, but each eligible shading vertex costs an extra shadow ray, so this is a real
     * performance lever in dense vegetation.
     */
    private static OptionInstance<Boolean> subsurfaceScattering() {
        return bool("caustica.options.rt.sss", CausticaConfig.Rt.Composite.SSS);
    }

    /** Rain/thunderstorm sun-and-sky dimming. Off keeps clear-sky lighting in every weather state. */
    private static OptionInstance<Boolean> weatherLighting() {
        return bool("caustica.options.rt.weatherLighting", CausticaConfig.Rt.Composite.WEATHER_LIGHTING);
    }

    /**
     * The ray-traced cloud deck. Caustica cancels vanilla's world renderer, and vanilla's clouds are
     * drawn inside it, so this switch is what puts clouds in the sky at all.
     */
    private static OptionInstance<Boolean> clouds() {
        return bool("caustica.options.rt.clouds", CausticaConfig.Rt.Composite.CLOUDS);
    }

    private static final List<String> CLOUD_STYLES = List.of("classic", "volumetric");

    // Cloud-height slider bounds, in world Y. Must stay inside CausticaConfig's own clamp on
    // CLOUD_HEIGHT, which is what actually guards the value.
    private static final int CLOUD_HEIGHT_MIN = 128;
    private static final int CLOUD_HEIGHT_MAX = 1024;
    private static final int CLOUD_HEIGHT_STEP = 8;

    // Fog slider bounds. Same rule as the cloud-height block: CausticaConfig clamps are the real
    // guard; these only pick how the slider's steps map onto that range.
    private static final int FOG_DISTANCE_MIN = 16;   // blocks
    private static final int FOG_DISTANCE_STEP = 16;
    private static final int FOG_DISTANCE_STEPS = (1024 - FOG_DISTANCE_MIN) / FOG_DISTANCE_STEP;
    private static final int FOG_FALLOFF_STEP = 8;    // blocks of exponential height scale
    private static final int FOG_FALLOFF_STEPS = 512 / FOG_FALLOFF_STEP;
    // HG anisotropy steps: -0.95 + step * 0.05, i.e. 38 steps spanning the config's clamp.
    private static final int FOG_G_STEPS = 38;

    /**
     * Cloud rendering style: vanilla's flat blocky deck, or a ray-marched volumetric slab.
     *
     * <p>Both styles are two readings of one shared coverage field, so switching does not move the
     * clouds — the same cloud is simply drawn flat or with depth — and the cloud shadows are unchanged
     * between them. Volumetric costs real GPU time (it marches the slab and light-marches for
     * self-shadowing); classic is nearly free.
     *
     * <p>{@code styleChanged} reopens the screen so the coverage slider swaps to its disabled
     * placeholder (or back) as soon as the style flips — classic decks bake coverage into the
     * texture, so the slider has no effect there.
     */
    private static OptionInstance<String> cloudStyle(Runnable styleChanged) {
        StringSetting setting = CausticaConfig.Rt.Composite.CLOUD_STYLE;
        return new OptionInstance<>(
            "caustica.options.rt.cloudStyle",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.cloudStyle.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself, so this must
            // return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.cloudStyle." + value),
            new OptionInstance.Enum<>(CLOUD_STYLES, Codec.STRING),
            CLOUD_STYLES.contains(setting.get()) ? setting.get() : "classic",
            value -> {
                setting.set(value);
                styleChanged.run();
            });
    }

    /**
     * The coverage slider's classic-mode placeholder. The classic deck is one flat sprite whose
     * coverage is baked into the texture (like vanilla's clouds.png), so in that style the slider
     * changes nothing: the clouds sub-screen swaps it for this greyed-out row, which also tells the
     * player why. Returns {@code null} when the volumetric style is selected — i.e. "coverage is
     * usable, no placeholder row".
     */
    public static Button cloudCoverageDisabledHint() {
        if (!"classic".equals(CausticaConfig.Rt.Composite.CLOUD_STYLE.get())) {
            return null;
        }
        Button button = Button.builder(
                Component.translatable("caustica.options.rt.cloudCoverage.classicUnavailable"), clicked -> {})
            .width(310).build();
        button.active = false;
        button.setTooltip(Tooltip.create(Component.translatable(
                "caustica.options.rt.cloudCoverage.classicUnavailable.tooltip")));
        return button;
    }

    /**
     * World Y the base of the cloud deck sits at, in blocks.
     *
     * <p>Stepped in 8-block increments: the slider spans nearly 900 blocks, and single-block precision
     * on a deck hundreds of blocks overhead is below what the eye can resolve, so coarser steps make it
     * far easier to land on a value.
     */
    private static OptionInstance<Integer> cloudHeight() {
        FloatSetting setting = CausticaConfig.Rt.Composite.CLOUD_HEIGHT;
        int steps = (CLOUD_HEIGHT_MAX - CLOUD_HEIGHT_MIN) / CLOUD_HEIGHT_STEP;
        int initial = Math.clamp(
                Math.round((setting.value() - CLOUD_HEIGHT_MIN) / CLOUD_HEIGHT_STEP), 0, steps);
        return new OptionInstance<>(
            "caustica.options.rt.cloudHeight",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.cloudHeight.tooltip")),
            (caption, step) -> Options.genericValueLabel(caption,
                    Component.literal("Y " + (CLOUD_HEIGHT_MIN + step * CLOUD_HEIGHT_STEP))),
            new OptionInstance.IntRange(0, steps),
            initial,
            step -> setting.set((float) (CLOUD_HEIGHT_MIN + step * CLOUD_HEIGHT_STEP)));
    }

    /**
     * Cloud thickness, as a percentage of the maximum deck depth. Applies to both styles: 0% is a flat
     * sheet, 100% is a deep bank you can fly into. Classic clouds become real boxes with lit tops and
     * darker sides, the way vanilla's cloud geometry looks; volumetric clouds gain the depth their
     * shading needs. Thicker clouds cost more to march.
     */
    private static OptionInstance<Integer> cloudThickness() {
        return percent("caustica.options.rt.cloudThickness",
                CausticaConfig.Rt.Composite.CLOUD_THICKNESS);
    }

    /**
     * How strongly the cloud deck shadows the world beneath it, as a percentage. 0% draws clouds that
     * cast nothing; 100% lets a solid cloud block the sun outright.
     */
    private static OptionInstance<Integer> cloudShadowStrength() {
        return percent("caustica.options.rt.cloudShadowStrength",
                CausticaConfig.Rt.Composite.CLOUD_SHADOW_STRENGTH);
    }

    /**
     * How opaque the deck is, as a percentage: 0% is fully transparent (invisible, and the cloud path is
     * skipped entirely), 100% completely hides the sky behind it.
     */
    private static OptionInstance<Integer> cloudOpacity() {
        return percent("caustica.options.rt.cloudOpacity", CausticaConfig.Rt.Composite.CLOUD_OPACITY);
    }

    /**
     * Volumetric fog master switch. Off writes a zero density lane into the per-frame push, which
     * is the single gate every fog term checks — the "no fog" frame is the pre-feature frame, not
     * a frame that computed fog and hid it.
     */
    private static OptionInstance<Boolean> fogEnabled() {
        return bool("caustica.options.rt.fog", CausticaConfig.Rt.Composite.FOG_ENABLED);
    }

    /** Scattering density, as a percentage of the shader's maximum per-block cross-section. */
    private static OptionInstance<Integer> fogDensity() {
        return percent("caustica.options.rt.fogDensity", CausticaConfig.Rt.Composite.FOG_DENSITY);
    }

    /**
     * Biome/weather tint strength. The colour itself is never ours: it is vanilla's FOG_COLOR
     * camera attribute (biome blend, rain, dimension — all resolved by the game), and this
     * slider only says how far the scatter lerps toward it from the module's neutral tint.
     */
    private static OptionInstance<Integer> fogBiomeTint() {
        return percent("caustica.options.rt.fogBiomeTint", CausticaConfig.Rt.Composite.FOG_BIOME_TINT);
    }

    /**
     * How far the fog integrates along a ray, in blocks. Stepped by 16 (half a chunk): the horizon
     * this sets is hundreds of blocks away, and 1-block precision there is below what the eye can
     * resolve, so coarse steps make it far easier to land on a value. Same shape as
     * {@link #cloudHeight()}'s slider.
     */
    private static OptionInstance<Integer> fogDistance() {
        FloatSetting setting = CausticaConfig.Rt.Composite.FOG_DISTANCE;
        int initial = Math.clamp(Math.round((setting.value() - FOG_DISTANCE_MIN) / FOG_DISTANCE_STEP),
                0, FOG_DISTANCE_STEPS);
        return new OptionInstance<>(
            "caustica.options.rt.fogDistance",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogDistance.tooltip")),
            (caption, step) -> Options.genericValueLabel(caption,
                    Component.literal((FOG_DISTANCE_MIN + step * FOG_DISTANCE_STEP) + " blocks")),
            new OptionInstance.IntRange(0, FOG_DISTANCE_STEPS),
            initial,
            step -> setting.set((float) (FOG_DISTANCE_MIN + step * FOG_DISTANCE_STEP)));
    }

    /**
     * Forward-scattering bias of the fog, HG anisotropy g. Higher concentrates the glow around
     * whatever the eye is looking toward — sun shafts and the far end of torch cones — while 0
     * makes the fog glow evenly from every direction.
     */
    private static OptionInstance<Integer> fogAnisotropy() {
        FloatSetting setting = CausticaConfig.Rt.Composite.FOG_ANISOTROPY;
        int initial = Math.clamp(Math.round((setting.value() + 0.95f) / 0.05f), 0, FOG_G_STEPS);
        return new OptionInstance<>(
            "caustica.options.rt.fogAnisotropy",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogAnisotropy.tooltip")),
            (caption, step) -> Options.genericValueLabel(caption, Component.literal(
                    String.format(java.util.Locale.ROOT, "%.2f", -0.95f + step * 0.05f))),
            new OptionInstance.IntRange(0, FOG_G_STEPS),
            initial,
            step -> setting.set(-0.95f + step * 0.05f));
    }

    /**
     * Valley mist: the exponential falloff scale above world Y 90, in blocks. 0 (Off) keeps the
     * medium uniform — fog at mountain tops included; any height is the e-folding distance, so
     * 32 blocks means the density has dropped to a third of its floor value 32 blocks above the
     * fog line.
     */
    private static OptionInstance<Integer> fogHeightFalloff() {
        FloatSetting setting = CausticaConfig.Rt.Composite.FOG_HEIGHT_FALLOFF;
        int initial = Math.clamp(Math.round(setting.value() / FOG_FALLOFF_STEP), 0, FOG_FALLOFF_STEPS);
        return new OptionInstance<>(
            "caustica.options.rt.fogHeightFalloff",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.fogHeightFalloff.tooltip")),
            (caption, step) -> Options.genericValueLabel(caption, Component.literal(
                    step == 0 ? "Off" : (step * FOG_FALLOFF_STEP) + " blocks")),
            new OptionInstance.IntRange(0, FOG_FALLOFF_STEPS),
            initial,
            step -> setting.set((float) (step * FOG_FALLOFF_STEP)));
    }

    /**
     * The denoising filter (DLSS Ray Reconstruction). Turning it off shows the raw path-traced image —
     * a correct but noisy reference view — and, because RR also owns the upscale, moves the trace to
     * full display resolution. Safe to toggle live: {@code RtComposite.ensureOutput} re-sizes the trace
     * targets on the next frame.
     */
    private static OptionInstance<Boolean> denoiser() {
        return bool("caustica.options.rt.denoiser", CausticaConfig.Rt.Composite.DENOISER);
    }

    /**
     * ON makes the first-person viewmodel share the camera's FOV (raising FOV pushes the arm away, lowering
     * it pulls the arm closer); OFF restores vanilla's fixed, FOV-isolated hand projection.
     */
    private static OptionInstance<Boolean> handFov() {
        return bool("caustica.options.rt.handFov", CausticaConfig.Rt.Hand.FOV_FOLLOWS_CAMERA);
    }

    // NVSDK_NGX_PerfQuality_Value, ordered performance -> quality for the slider. Per NVIDIA's DLSS-RR
    // programming guide, Ray Reconstruction only supports Performance(0), Balanced(1), Quality(2),
    // Ultra-Performance(3), and DLAA(5) — Ultra Quality(4) is not a valid PerfQualityValue for RR (its
    // optimal-settings query returns a zeroed render size for it) and is deliberately excluded here.
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    private static final List<Integer> DLSS_PRESET_ORDER = List.of(0, 6);

    private static OptionInstance<Integer> dlssPreset() {
        IntSetting setting = CausticaConfig.Rt.DlssRr.PRESET;
        int initialPreset = DLSS_PRESET_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_PRESET_ORDER.indexOf(initialPreset);
        return new OptionInstance<>(
            "caustica.options.rt.dlssPreset",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.dlssPreset.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssPreset." + DLSS_PRESET_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_PRESET_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_PRESET_ORDER.get(position)));
    }

    /**
     * The upscaler selector: Off (native-resolution trace) / DLSS (Ray Reconstruction denoise + upscale),
     * plus FSR 3 once its backend reports available (see {@code RtUpscalerSupport.fsrUpscalingAvailable}).
     * Selection maps onto the existing backend switches ({@code dlss-rr.enabled} / {@code fsr.enabled})
     * rather than a new setting, so every renderer-side check keeps one source of truth, and the screen
     * rebuilds through {@code onChanged} so the quality slider and Frame Generation toggle switch together
     * with the selected upscaler.
     */
    private static OptionInstance<String> upscaler(Runnable onChanged) {
        List<String> values = RtUpscalerSupport.upscalerValues();
        String initial = RtUpscalerSupport.currentUpscalerMode();
        if (!values.contains(initial)) {
            // e.g. fsr.enabled hand-set in the config while no FSR backend is bundled yet: display the
            // backend that is actually driving the image (DLSS if it is on), not a mode that cannot run.
            initial = CausticaConfig.Rt.DlssRr.ENABLED.value()
                    ? RtUpscalerSupport.MODE_DLSS : RtUpscalerSupport.MODE_NONE;
        }
        return new OptionInstance<>(
            "caustica.options.rt.upscaler",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.upscaler.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself, so this must return
            // only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.upscaler." + value),
            new OptionInstance.Enum<>(values, Codec.STRING),
            initial,
            value -> {
                RtUpscalerSupport.applyUpscalerMode(value);
                onChanged.run();
            });
    }

    // Same PerfQuality vocabulary as DLSS (the FSR 3 upscaler uses the same mode scale), so this row
    // reuses the dlssQuality value-name keys. Reachable only once the selector offers FSR 3 —
    // RtUpscalerSupport.fsrUpscalingAvailable() (the bundled FidelityFX runtime) decides that.
    private static final List<Integer> FSR_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> fsrQuality() {
        IntSetting setting = CausticaConfig.Rt.Fsr.QUALITY;
        int initialQuality = FSR_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 2;
        int initialPosition = FSR_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.fsrQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.fsrQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + FSR_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, FSR_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(FSR_QUALITY_ORDER.get(position)));
    }

    // Same shared PerfQuality vocabulary as DLSS/FSR (XeSS maps the same ratios onto its own
    // xess_quality_settings_t). Reachable only once the selector offers XeSS — the bundled Intel
    // runtime + the XeSS device features on the GPU decide that (RtUpscalerSupport).
    private static final List<Integer> XESS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> xessQuality() {
        IntSetting setting = CausticaConfig.Rt.Xess.QUALITY;
        int initialQuality = XESS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 2;
        int initialPosition = XESS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "caustica.options.rt.xessQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.xessQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("caustica.options.rt.dlssQuality." + XESS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, XESS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(XESS_QUALITY_ORDER.get(position)));
    }

    /**
     * The built-in denoiser (SVGF) toggle. Live-safe like the other per-frame toggles: flipping it
     * re-keys {@code RtComposite.ensureOutput}, which allocates or releases the history/moment
     * targets on the rebuild, and the tracer picks the feature flag up on the next frame.
     */
    private static OptionInstance<Boolean> svgfDenoiser() {
        return bool("caustica.options.rt.denoise", CausticaConfig.Rt.Denoise.ENABLED);
    }

    /**
     * The Frame Generation toggle (EXPERIMENTAL). Rides on the selected upscaler: DLSS mode toggles
     * DLSS-G (hardware-gated: unsupported GPUs get a greyed-out button that stays visible so its
     * tooltip can explain the requirement), FSR 3 AND XeSS modes toggle FSR 3.1 FG (no hardware
     * gate beyond the bundled FSR runtime — Intel's own XeSS-FG is D3D12-only, so the FFX engine
     * is the frame-gen for the XeSS path too). Returns null on every other path.
     */
    public static Button frameGenerationButton(Runnable onChanged) {
        String mode = RtUpscalerSupport.currentUpscalerMode();
        if (RtUpscalerSupport.MODE_FSR3.equals(mode) || RtUpscalerSupport.MODE_XESS.equals(mode)) {
            CausticaConfig.BooleanSetting setting = CausticaConfig.Rt.Fg.ENABLED;
            Button button = Button.builder(frameGenerationLabel(), clicked -> {
                setting.set(!setting.value());
                clicked.setMessage(frameGenerationLabel());
                // The multiplier row only exists while FG is on (and the engine's cap changes with
                // the active backend): rebuild the screen around the new state instead of making the
                // player close and reopen the menu to see/hide it.
                onChanged.run();
            }).width(310).build();
            button.setTooltip(Tooltip.create(
                    Component.translatable("caustica.options.rt.frameGeneration.fsr.tooltip")));
            return button;
        }
        if (!RtUpscalerSupport.MODE_DLSS.equals(mode)) {
            return null;
        }
        CausticaConfig.BooleanSetting setting = CausticaConfig.Rt.Fg.ENABLED;
        boolean supported = RtUpscalerSupport.dlssFrameGenerationSupported();
        // DLSS-G hardware (RTX 40/50) uses the vendor engine; every other GPU falls back to the
        // Caustica native engine instead of greying the toggle out (the old "unsupported" state).
        Button button = Button.builder(frameGenerationLabel(), clicked -> {
            setting.set(!setting.value());
            clicked.setMessage(frameGenerationLabel());
            onChanged.run(); // same rebuild reason as above
        }).width(310).build();
        button.setTooltip(Tooltip.create(Component.translatable(supported
                ? "caustica.options.rt.frameGeneration.tooltip"
                : "caustica.options.rt.frameGeneration.nativeFallback.tooltip")));
        return button;
    }

    private static Component frameGenerationLabel() {
        return Options.genericValueLabel(
                Component.translatable("caustica.options.rt.frameGeneration"),
                Component.translatable(CausticaConfig.Rt.Fg.ENABLED.value() ? "options.on" : "options.off"));
    }

    /**
     * Generated-frame counts the ACTIVE backend can really produce. DLSS-G offers 1..driver MFG cap
     * (probed from NGX). The Caustica native engine (default on the FSR 3 / XeSS paths) offers
     * 1..its cap — it interpolates with the tracer's own motion vectors, so every slot is a real
     * frame. The FSR 3.1 runtime fallback offers exactly one: it only ever writes outputs[0] per
     * dispatch (see RtFsrFrameGen.MAX_GENERATED_FRAMES) — offering more there was presenting
     * never-written frames (the black blink + colorful corruption of the 3x+ era).
     */
    private static int[] fgMultiplierOptions() {
        String mode = RtUpscalerSupport.currentUpscalerMode();
        if (RtUpscalerSupport.MODE_DLSS.equals(mode)) {
            int max = dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.multiFrameCountMax();
            if (max > 1) {
                int[] options = new int[max];
                for (int i = 0; i < max; i++) {
                    options[i] = i + 1;
                }
                return options;
            }
        }
        if (dev.comfyfluffy.caustica.rt.pipeline.RtNativeFrameGen.enabled()) {
            int max = dev.comfyfluffy.caustica.rt.pipeline.RtNativeFrameGen.MAX_GENERATED_FRAMES;
            int[] options = new int[max];
            for (int i = 0; i < max; i++) {
                options[i] = i + 1;
            }
            return options;
        }
        return new int[] { 1 };
    }

    /**
     * The FG multiplier selector: how many frames the display receives per rendered frame. Visible
     * alongside {@link #frameGenerationButton()} ONLY when the active backend genuinely supports
     * more than one generated frame (DLSS Multi Frame Generation on supporting drivers) — on the
     * FSR 3 / XeSS path the multiplier is fixed at 2x by the engine, so the button is omitted
     * instead of pretending there's a choice.
     */
    public static Button fgMultiplierButton() {
        String mode = RtUpscalerSupport.currentUpscalerMode();
        if (!RtUpscalerSupport.MODE_DLSS.equals(mode) && !RtUpscalerSupport.MODE_FSR3.equals(mode)
                && !RtUpscalerSupport.MODE_XESS.equals(mode)) {
            return null;
        }
        int[] options = fgMultiplierOptions();
        if (options.length < 2) {
            return null; // fixed multiplier on this backend — nothing to cycle through
        }
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT;
        Button button = Button.builder(fgMultiplierLabel(), clicked -> {
            int current = setting.value();
            int idx = 0;
            for (int i = 0; i < options.length; i++) {
                if (options[i] == current) {
                    idx = i;
                    break;
                }
            }
            setting.set(options[(idx + 1) % options.length]);
            clicked.setMessage(fgMultiplierLabel());
        }).width(310).build();
        button.setTooltip(Tooltip.create(Component.translatable("caustica.options.rt.fgMultiplier.tooltip")));
        return button;
    }

    private static Component fgMultiplierLabel() {
        // Show the EFFECTIVE multiplier (config clamped to what the backend can produce), so a stale
        // high config value from a previous build doesn't mislead. Native engine first: it wins
        // wherever it's active (FSR 3 / XeSS default, and the DLSS-path fallback on non-DLSS-G GPUs).
        int effective;
        if (dev.comfyfluffy.caustica.rt.pipeline.RtNativeFrameGen.enabled()) {
            effective = dev.comfyfluffy.caustica.rt.pipeline.RtNativeFrameGen.INSTANCE.effectiveGeneratedCount();
        } else if (RtUpscalerSupport.MODE_DLSS.equals(RtUpscalerSupport.currentUpscalerMode())) {
            effective = dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.effectiveMultiFrameCount();
        } else {
            effective = dev.comfyfluffy.caustica.rt.pipeline.RtFsrFrameGen.INSTANCE.effectiveGeneratedCount();
        }
        return Options.genericValueLabel(
                Component.translatable("caustica.options.rt.fgMultiplier"),
                Component.literal((effective + 1) + "x"));
    }

    /**
     * NVIDIA Reflex (VK_NV_low_latency2) toggle: driver-paced frame submission that cuts input
     * latency — the natural pairing with frame generation (FG always costs ~one rendered frame of
     * latency; Reflex removes the rest of the pipeline queue). Greyed out with a tooltip on devices
     * without the extension; engages live (the pacing loop self-applies sleep mode on the next
     * frame), no restart needed.
     */
    public static Button reflexButton() {
        CausticaConfig.BooleanSetting setting = CausticaConfig.Rt.Reflex.ENABLED;
        boolean supported = dev.comfyfluffy.caustica.rt.RtDeviceBringup.reflexEnabled();
        Button button = Button.builder(reflexLabel(), clicked -> {
            setting.set(!setting.value());
            clicked.setMessage(reflexLabel());
        }).width(310).build();
        button.active = supported;
        button.setTooltip(Tooltip.create(Component.translatable(supported
                ? "caustica.options.rt.reflex.tooltip"
                : "caustica.options.rt.reflex.unsupported.tooltip")));
        return button;
    }

    private static Component reflexLabel() {
        return Options.genericValueLabel(
                Component.translatable("caustica.options.rt.reflex"),
                Component.translatable(CausticaConfig.Rt.Reflex.ENABLED.value() ? "options.on" : "options.off"));
    }

    /**
     * The Frame Generation engine selector for the FSR 3 / XeSS paths: Caustica's own motion-vector
     * interpolation engine (any GPU, multi-frame up to 4x) versus the signed AMD FSR 3.1 runtime
     * (fixed at 2x — it writes exactly one interpolated frame per dispatch). Flipping it changes
     * what the multiplier selector can offer, so the click rebuilds the screen. Returns null on
     * other paths: DLSS mode has its own hardware engine (or the native fallback on non-DLSS-G
     * GPUs), and with no upscaler the toggle would do nothing.
     */
    public static Button fgEngineButton(Runnable onChanged) {
        String mode = RtUpscalerSupport.currentUpscalerMode();
        if (!RtUpscalerSupport.MODE_FSR3.equals(mode) && !RtUpscalerSupport.MODE_XESS.equals(mode)) {
            return null;
        }
        CausticaConfig.BooleanSetting setting = CausticaConfig.Rt.Fg.NATIVE_ENGINE;
        Button button = Button.builder(fgEngineLabel(), clicked -> {
            setting.set(!setting.value());
            clicked.setMessage(fgEngineLabel());
            onChanged.run();
        }).width(310).build();
        button.setTooltip(Tooltip.create(Component.translatable("caustica.options.rt.fgEngine.tooltip")));
        return button;
    }

    private static Component fgEngineLabel() {
        return Options.genericValueLabel(
                Component.translatable("caustica.options.rt.fgEngine"),
                Component.translatable(CausticaConfig.Rt.Fg.NATIVE_ENGINE.value()
                        ? "caustica.options.rt.fgEngine.native" : "caustica.options.rt.fgEngine.fsr31"));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("caustica.options.rt.hdr", CausticaConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "caustica.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = CausticaConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "caustica.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("caustica.options.rt.debugView." + value),
            // 10-12 inspect the SVGF denoiser's internal state and, unlike 1-7, leave the denoiser
            // running (see RtComposite.SVGF_DEBUG_FIRST). 8/9 are not exposed here. 13 is the SHaRC
            // cache query overlay (see sharc.slang): pass B paints it so it reflects real queries.
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 13), Codec.INT),
            Math.clamp(setting.value(), 0, 13),
            setting::set);
    }

    private static OptionInstance<Integer> multiplier(String captionKey, FloatSetting setting, int minTenths, int maxTenths) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1fx", tenths / 10.0f))),
            new OptionInstance.IntRange(minTenths, maxTenths),
            Math.clamp(Math.round(setting.value() * 10.0f), minTenths, maxTenths),
            tenths -> setting.set(tenths / 10.0f));
    }

    /**
     * A 0..1 float exposed as a 0..100% slider. The setting itself is clamped to [0,1] in the config, so
     * the slider range and the stored domain are the same thing expressed in different units.
     */
    private static OptionInstance<Integer> metallicShininess() {
        return percent("caustica.options.rt.metallicShininess", CausticaConfig.Rt.Composite.METALLIC_SHININESS);
    }

    private static OptionInstance<Integer> percent(String captionKey, FloatSetting setting) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.literal(value + "%")),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            value -> setting.set(value / 100.0f));
    }

    private static OptionInstance<Integer> hundredths(String captionKey, FloatSetting setting, int min, int max) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", hundredths / 100.0f))),
            new OptionInstance.IntRange(min, max),
            Math.clamp(Math.round(setting.value() * 100.0f), min, max),
            value -> setting.set(value / 100.0f));
    }

    private static OptionInstance<Boolean> parallaxEnabled() {
        return bool("caustica.options.rt.parallax", CausticaConfig.Rt.Composite.PARALLAX_ENABLED);
    }

    private static OptionInstance<Integer> parallaxStrength() {
        FloatSetting setting = CausticaConfig.Rt.Composite.PARALLAX_STRENGTH;
        return new OptionInstance<>(
            "caustica.options.rt.parallaxStrength",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.parallaxStrength.tooltip")),
            (caption, percent) -> Options.genericValueLabel(caption, Component.literal(percent + "%")),
            new OptionInstance.IntRange(0, 400),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 400),
            percent -> setting.set(percent / 100.0f));
    }

    private static OptionInstance<Boolean> parallaxSmoothing() {
        return bool("caustica.options.rt.parallaxSmoothing",
                CausticaConfig.Rt.Composite.PARALLAX_SMOOTHING);
    }

    private static OptionInstance<Integer> parallaxDistance() {
        FloatSetting setting = CausticaConfig.Rt.Composite.PARALLAX_DISTANCE;
        return new OptionInstance<>(
            "caustica.options.rt.parallaxDistance",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("caustica.options.rt.parallaxDistance.tooltip")),
            (caption, blocks) -> Options.genericValueLabel(caption, Component.literal(blocks + " blocks")),
            new OptionInstance.IntRange(16, 256),
            Math.clamp(Math.round(setting.value()), 16, 256),
            blocks -> setting.set(blocks.floatValue()));
    }

    /** Rebuild DH's render cache and Caustica's RT proxy after changing DH quality settings. */
    public static Button distantHorizonsRefreshButton() {
        Button button = Button.builder(Component.translatable("caustica.options.rt.dhRefresh"), clicked -> {
            boolean dhReloaded = DistantHorizonsCompat.reloadRenderDataCache();
            RtDistantHorizonsTerrain.INSTANCE.requestFullRefresh();
            clicked.setMessage(Component.translatable(dhReloaded
                    ? "caustica.options.rt.dhRefresh.queued"
                    : "caustica.options.rt.dhRefresh.rtOnly"));
        }).width(310).build();
        button.active = DistantHorizonsCompat.enabled() && Minecraft.getInstance().level != null;
        return button;
    }

    /** Live settings supplied by the bundled no-Sodium Voxy bridge. */
    public static OptionInstance<?>[] voxyOptions() {
        return new OptionInstance<?>[] {
            OptionInstance.createBoolean(
                    "caustica.options.voxy.enabled",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("caustica.options.voxy.enabled.tooltip")),
                    VoxyCompat.active(),
                    enabled -> {
                        if (VoxyCompat.setActive(enabled)) {
                            RtDistantHorizonsTerrain.INSTANCE.requestFullRefresh();
                        }
                    }),
            OptionInstance.createBoolean(
                    "caustica.options.voxy.ingest",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("caustica.options.voxy.ingest.tooltip")),
                    VoxyCompat.ingestEnabled(),
                    VoxyCompat::setIngestEnabled),
            new OptionInstance<>(
                    "caustica.options.voxy.distance",
                    OptionInstance.cachedConstantTooltip(
                            Component.translatable("caustica.options.voxy.distance.tooltip")),
                    (caption, sections) -> Options.genericValueLabel(caption,
                            Component.translatable("caustica.options.voxy.chunks", sections * 32)),
                    // Voxy stores this value in 32-block sections. Exposing the native steps avoids
                    // hundreds of config writes and full desired-set recalculations during one drag.
                    new OptionInstance.IntRange(1, 16),
                    Math.clamp(VoxyCompat.configuredRenderDistanceChunks() / 32, 1, 16),
                    sections -> VoxyCompat.setConfiguredRenderDistanceChunks(sections * 32))
        };
    }

    /** Drop Voxy's generated proxy cache and start a bounded rebuild around the current camera. */
    public static Button voxyRefreshButton() {
        Button button = Button.builder(Component.translatable("caustica.options.voxy.refresh"), clicked -> {
            boolean reset = VoxyCompat.reset();
            RtDistantHorizonsTerrain.INSTANCE.requestFullRefresh();
            clicked.setMessage(Component.translatable(reset
                    ? "caustica.options.voxy.refresh.queued"
                    : "caustica.options.voxy.refresh.unavailable"));
        }).width(310).build();
        button.active = VoxyCompat.enabled() && Minecraft.getInstance().level != null;
        return button;
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }

    /**
     * Opacity micromaps for terrain cutout (grass, leaves...). Unlike the plain {@link #bool} toggles,
     * flipping this cannot take effect "next frame": already-built BLASes keep the state they were
     * built with, so the change handler requests a full terrain rebuild and every section re-meshes
     * under the new gate — the same mechanism F3+A uses, so nothing about it is OMM-specific.
     *
     * <p>One asymmetry the tooltip spells out: the underlying Vulkan feature is latched at RT device
     * bring-up, so a toggle that was OFF at launch only gains device support once the device is
     * recreated (re-entering a world). Turning it OFF works immediately in every case.
     */
    private static OptionInstance<Boolean> omm() {
        return OptionInstance.createBoolean(
            "caustica.options.rt.omm",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.omm.tooltip")),
            CausticaConfig.Rt.Omm.ENABLED.value(),
            value -> {
                CausticaConfig.Rt.Omm.ENABLED.set(value);
                RtTerrain.requestFullClear();
            });
    }

    // ===== Experimental SHaRC (Spatially Hashed Radiance Cache) =====

    /**
     * All SHaRC tuning rows. They ride in the dedicated {@link RtSharcOptionsScreen} so the main RT
     * screen stays compact: the experimental enable toggle + the "SHaRC Settings..." button live there,
     * while this list holds the fine-grained cache knobs.
     */
    public static OptionInstance<?>[] sharcOptions() {
        return new OptionInstance<?>[] {
            sharcCellSize(),
            sharcCacheEntries(),
            sharcUpdateCoverage(),
            sharcTemporalBlend(),
            sharcStartBounce(),
            sharcStrength(),
            sharcFrameLifetime(),
            sharcNormalThreshold(),
            sharcStableFrames(),
            sharcDebug(),
        };
    }

    private static OptionInstance<Boolean> sharcDebug() {
        return bool("caustica.options.sharc.debug", CausticaConfig.Rt.Sharc.DEBUG);
    }

    private static OptionInstance<Integer> sharcCellSize() {
        FloatSetting setting = CausticaConfig.Rt.Sharc.CELL_SIZE;
        return new OptionInstance<>(
            "caustica.options.sharc.cellSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.sharc.cellSize.tooltip")),
            (caption, blocks) -> Options.genericValueLabel(caption, Component.literal(blocks + " blocks")),
            new OptionInstance.IntRange(1, 64),
            Math.clamp(Math.round(setting.value()), 1, 64),
            blocks -> setting.set(blocks.floatValue()));
    }

    private static OptionInstance<Integer> sharcCacheEntries() {
        IntSetting setting = CausticaConfig.Rt.Sharc.CACHE_ENTRIES;
        int minShift = 11; // 2048
        int maxShift = 20; // 1048576
        int initial = Math.clamp(31 - Integer.numberOfLeadingZeros(setting.value()), minShift, maxShift);
        return new OptionInstance<>(
            "caustica.options.sharc.cacheEntries",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.sharc.cacheEntries.tooltip")),
            (caption, shift) -> Options.genericValueLabel(caption,
                    Component.literal((1 << shift) + " entries")),
            new OptionInstance.IntRange(minShift, maxShift),
            initial,
            shift -> setting.set(1 << shift));
    }

    private static OptionInstance<Integer> sharcUpdateCoverage() {
        return percent("caustica.options.sharc.updateCoverage", CausticaConfig.Rt.Sharc.UPDATE_COVERAGE);
    }

    private static OptionInstance<Integer> sharcTemporalBlend() {
        return percent("caustica.options.sharc.temporalBlend", CausticaConfig.Rt.Sharc.TEMPORAL_BLEND);
    }

    private static OptionInstance<Integer> sharcStartBounce() {
        IntSetting setting = CausticaConfig.Rt.Sharc.START_BOUNCE;
        return new OptionInstance<>(
            "caustica.options.sharc.startBounce",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.sharc.startBounce.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 6),
            Math.clamp(setting.value(), 1, 6),
            setting::set);
    }

    private static OptionInstance<Integer> sharcStrength() {
        return percent("caustica.options.sharc.strength", CausticaConfig.Rt.Sharc.STRENGTH);
    }

    private static OptionInstance<Integer> sharcFrameLifetime() {
        IntSetting setting = CausticaConfig.Rt.Sharc.FRAME_LIFETIME;
        return new OptionInstance<>(
            "caustica.options.sharc.frameLifetime",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.sharc.frameLifetime.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.literal(value + " frames")),
            new OptionInstance.IntRange(1, 240),
            Math.clamp(setting.value(), 1, 240),
            setting::set);
    }

    private static OptionInstance<Integer> sharcNormalThreshold() {
        return percent("caustica.options.sharc.normalThreshold", CausticaConfig.Rt.Sharc.NORMAL_THRESHOLD);
    }

    private static OptionInstance<Integer> sharcStableFrames() {
        IntSetting setting = CausticaConfig.Rt.Sharc.STABLE_FRAMES;
        return new OptionInstance<>(
            "caustica.options.sharc.stableFrames",
            OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.sharc.stableFrames.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.literal(value + " samples")),
            new OptionInstance.IntRange(0, 30),
            Math.clamp(setting.value(), 0, 30),
            setting::set);
    }

    /** Big experimental enable/disable toggle, shown on both the RT screen and the SHaRC sub-screen. */
    public static Button sharcToggleButton() {
        return sharcToggleButton(null);
    }

    /**
     * Same toggle with an optional {@code onChanged} callback. The main RT screen passes a rebuild so
     * the button label (and any dependent rows) stay in sync; the SHaRC sub-screen passes nothing and
     * simply updates its own label.
     */
    public static Button sharcToggleButton(Runnable onChanged) {
        Button button = Button.builder(sharcToggleLabel(), clicked -> {
            CausticaConfig.BooleanSetting setting = CausticaConfig.Rt.Sharc.ENABLED;
            setting.set(!setting.value());
            clicked.setMessage(sharcToggleLabel());
            if (onChanged != null) {
                onChanged.run();
            }
        }).width(310).build();
        button.setTooltip(Tooltip.create(
                Component.translatable("caustica.options.sharc.enabled.tooltip")));
        return button;
    }

    private static Component sharcToggleLabel() {
        return Options.genericValueLabel(
                Component.translatable("caustica.options.sharc.enabled"),
                Component.translatable(CausticaConfig.Rt.Sharc.ENABLED.value() ? "options.on" : "options.off"));
    }

    /** Opens the dedicated SHaRC customization sub-screen (same pattern as "Ray Tracing Settings..."). */
    public static Button sharcSettingsButton(Screen parent) {
        return Button.builder(Component.translatable("caustica.options.sharc.settingsButton"), clicked -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new RtSharcOptionsScreen(parent, minecraft.options));
        }).width(310).build();
    }

    /** Drops every cached radiance entry. The shader feature bit simply reads a cleared buffer next frame. */
    public static Button sharcResetButton() {
        Button button = Button.builder(Component.translatable("caustica.options.sharc.reset"), clicked -> {
            RtSharc.INSTANCE.requestClear();
            clicked.setMessage(Component.translatable("caustica.options.sharc.reset.queued"));
        }).width(310).build();
        button.setTooltip(Tooltip.create(
                Component.translatable("caustica.options.sharc.reset.tooltip")));
        return button;
    }
}
