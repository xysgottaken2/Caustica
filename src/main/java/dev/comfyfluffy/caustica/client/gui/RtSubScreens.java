package dev.comfyfluffy.caustica.client.gui;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Caustica sub-screen directory: one factory per settings sub-screen, each opening from a button on
 * the hub ({@link RtVideoOptionsScreen}) and each starting with its own "Reset to Defaults" row.
 *
 * <p>Every factory declares exactly the {@link CausticaConfig.RuntimeSetting}s its page exposes; that
 * list drives the page-local reset above, and {@link #allResettableSettings()} — the union of all of
 * them — drives the hub's global reset, so "reset everything" always means "press every page's reset".
 * Settings that size buffers at allocation time (worker threads, entity capacities, table sizes) are
 * deliberately not exposed in any sub-screen and therefore not reset either; they stay on the
 * {@code -Dcaustica.*} / config-file surface.
 */
public final class RtSubScreens {
    private RtSubScreens() {
    }

    private static Options options() {
        return Minecraft.getInstance().options;
    }

    /**
     * A hub button that opens the given sub-screen. The label is the group's header suffixed with
     * "..." (the same nested-menu convention vanilla's own "Video Settings..." uses), and the factory
     * receives the hub as its parent so the sub-screen's Done button lands back on the hub.
     */
    public static Button openButton(Screen parent, String headerKey, Function<Screen, Screen> factory) {
        return Button.builder(
                Component.translatable("caustica.options.rt.menuButton", Component.translatable(headerKey)),
                clicked -> Minecraft.getInstance().gui.setScreen(factory.apply(parent)))
                .width(310).build();
    }

    // ===== Sub-screens =====

    /** Path-tracing quality + upscaler basics, plus the opacity-micromap controls. */
    public static RtSettingsSubScreen quality(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.qualityHeader"),
                qualitySettings(), RtSubScreens::quality,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.qualityOptions(reopen))));
    }

    /** The upscaler backend and every per-backend knob: DLSS RR, FSR 3 or XeSS. */
    public static RtSettingsSubScreen upscaling(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.upscalingHeader"),
                upscalingSettings(), RtSubScreens::upscaling,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.upscalingOptions(reopen))));
    }

    /** Frame generation engine + latency (Reflex). Rows appear only where the backend supports them. */
    public static RtSettingsSubScreen frameGen(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.frameGenHeader"),
                frameGenSettings(), RtSubScreens::frameGen,
                reopen -> List.of(
                        RtSettingsSubScreen.Section.buttons(
                                RtVideoOptions.frameGenerationButton(reopen),
                                RtVideoOptions.fgMultiplierButton(),
                                RtVideoOptions.fgEngineButton(reopen)),
                        RtSettingsSubScreen.Section.of(
                                Component.translatable("caustica.options.rt.reflexHeader"),
                                RtVideoOptions.reflexOptions(),
                                RtVideoOptions.reflexButton())));
    }

    /** Direct lighting, ReSTIR sampling and the live anti-flicker tuning. */
    public static RtSettingsSubScreen lighting(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.lightsHeader"),
                lightingSettings(), RtSubScreens::lighting,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.lightOptions())));
    }

    /** Sun/moon shape and the first-person viewmodel. */
    public static RtSettingsSubScreen sky(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.skyHeader"),
                skySettings(), RtSubScreens::sky,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.skyOptions())));
    }

    /** Traced entities, particles, weather and the block/name-tag overlays. */
    public static RtSettingsSubScreen world(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.worldHeader"),
                worldSettings(), RtSubScreens::world,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.worldOptions())));
    }

    /** SSS, weather lighting and metallic polish. */
    public static RtSettingsSubScreen effects(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.effectsHeader"),
                effectsSettings(), RtSubScreens::effects,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.effectsOptions())));
    }

    /** Animated Water: toggle, clarity and the wave-spectrum knobs. */
    public static RtSettingsSubScreen water(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.waterHeader"),
                waterSettings(), RtSubScreens::water,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.waterOptions())));
    }

    /** Parallax Occlusion Mapping: depth, sampling level, smoothing and fade distance. */
    public static RtSettingsSubScreen pom(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.pomHeader"),
                pomSettings(), RtSubScreens::pom,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.pomOptions())));
    }

    /**
     * The cloud deck: style, coverage, altitude, thickness, shadow and opacity. Flipping the style
     * reopens the screen: the coverage slider only drives the volumetric deck, so in classic mode it
     * swaps to {@link RtVideoOptions#cloudCoverageDisabledHint()}'s disabled placeholder (null in
     * volumetric mode — the row simply vanishes).
     */
    public static RtSettingsSubScreen clouds(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.cloudsHeader"),
                cloudsSettings(), RtSubScreens::clouds,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.cloudOptions(reopen),
                        RtVideoOptions.cloudCoverageDisabledHint())));
    }

    /**
     * Volumetric fog: the master switch, density, reach, scattering directionality and the valley
     * profile. Everything here rides the same four-lane per-frame push as the other composite
     * options, so any change takes effect on the next frame without a reload or a rebuild.
     */
    public static RtSettingsSubScreen fog(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.fogHeader"),
                fogSettings(), RtSubScreens::fog,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.fogOptions())));
    }

    /** Manual/auto exposure and the whole auto-exposure internal state. */
    public static RtSettingsSubScreen exposure(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.exposureHeader"),
                exposureSettings(), RtSubScreens::exposure,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.exposureOptions())));
    }

    /** Tonemap curve and final look controls. */
    public static RtSettingsSubScreen tonemap(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.tonemapHeader"),
                tonemapSettings(), RtSubScreens::tonemap,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.tonemapOptions())));
    }

    /** HDR output and the scene-to-display nit mapping. */
    public static RtSettingsSubScreen hdr(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.hdrHeader"),
                hdrSettings(), RtSubScreens::hdr,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.hdrOptions())));
    }

    /** Terrain streaming budgets and BLAS flags (rebuild-on-change where needed). */
    public static RtSettingsSubScreen streaming(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.streamingHeader"),
                streamingSettings(), RtSubScreens::streaming,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.streamingOptions())));
    }

    /** Buffer visualizers and debug logging. */
    public static RtSettingsSubScreen debug(Screen parent) {
        return new RtSettingsSubScreen(parent, options(),
                Component.translatable("caustica.options.rt.debugHeader"),
                debugSettings(), RtSubScreens::debug,
                reopen -> List.of(RtSettingsSubScreen.Section.of(null, RtVideoOptions.debugOptions())));
    }

    // ===== Per-page resettable setting lists =====

    private static List<CausticaConfig.RuntimeSetting<?>> qualitySettings() {
        return List.of(
                CausticaConfig.Rt.Composite.SPP,
                CausticaConfig.Rt.Composite.MAX_BOUNCES,
                CausticaConfig.Rt.DlssRr.ENABLED,
                CausticaConfig.Rt.DlssRr.QUALITY,
                CausticaConfig.Rt.DlssRr.PRESET,
                CausticaConfig.Rt.Fsr.ENABLED,
                CausticaConfig.Rt.Fsr.QUALITY,
                CausticaConfig.Rt.Xess.ENABLED,
                CausticaConfig.Rt.Xess.QUALITY,
                CausticaConfig.Rt.Denoise.ENABLED,
                CausticaConfig.Rt.Omm.ENABLED,
                CausticaConfig.Rt.Omm.SUBDIVISION);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> upscalingSettings() {
        return List.of(
                CausticaConfig.Rt.DlssRr.ENABLED,
                CausticaConfig.Rt.DlssRr.QUALITY,
                CausticaConfig.Rt.DlssRr.PRESET,
                CausticaConfig.Rt.Composite.DENOISER,
                CausticaConfig.Rt.Fsr.ENABLED,
                CausticaConfig.Rt.Fsr.QUALITY,
                CausticaConfig.Rt.Xess.ENABLED,
                CausticaConfig.Rt.Xess.QUALITY,
                CausticaConfig.Rt.Denoise.ENABLED);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> frameGenSettings() {
        return List.of(
                CausticaConfig.Rt.Fg.ENABLED,
                CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT,
                CausticaConfig.Rt.Fg.NATIVE_ENGINE,
                CausticaConfig.Rt.Reflex.ENABLED,
                CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST,
                CausticaConfig.Rt.Reflex.MINIMUM_INTERVAL_US);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> lightingSettings() {
        return List.of(
                CausticaConfig.Rt.Lights.HELD_ITEM_LIGHT,
                CausticaConfig.Rt.Lights.BLOCK_INTENSITY,
                CausticaConfig.Rt.Lights.DYNAMIC_INTENSITY,
                CausticaConfig.Rt.Lights.MIN_FILL_RATIO,
                CausticaConfig.Rt.Lights.RIS_CANDIDATES,
                CausticaConfig.Rt.Lights.RESTIR_SAMPLING,
                CausticaConfig.Rt.Lights.RESTIR_TEMPORAL_HISTORY,
                CausticaConfig.Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS,
                CausticaConfig.Rt.Lights.RESTIR_MAX_AGE);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> skySettings() {
        return List.of(
                CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS,
                CausticaConfig.Rt.Composite.MOON_ANGULAR_RADIUS,
                CausticaConfig.Rt.Composite.SUN_NOON_SOUTH_TILT,
                CausticaConfig.Rt.Hand.FOV_FOLLOWS_CAMERA);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> worldSettings() {
        return List.of(
                CausticaConfig.Rt.Entities.ENABLED,
                CausticaConfig.Rt.Entities.PARTICLES_ENABLED,
                CausticaConfig.Rt.Entities.WEATHER_ENABLED,
                CausticaConfig.Rt.Entities.RAIN_DENSITY,
                CausticaConfig.Rt.Entities.GLOW_ENABLED,
                CausticaConfig.Rt.Entities.NAME_TAGS_ENABLED,
                CausticaConfig.Rt.Overlay.BLOCK_OUTLINE_ENABLED);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> effectsSettings() {
        return List.of(
                CausticaConfig.Rt.Composite.SSS,
                CausticaConfig.Rt.Composite.WEATHER_LIGHTING,
                CausticaConfig.Rt.Composite.METALLIC_SHININESS);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> waterSettings() {
        return List.of(
                CausticaConfig.Rt.Composite.WATER_WAVES,
                CausticaConfig.Rt.Composite.WATER_OPACITY,
                CausticaConfig.Rt.Composite.WATER_WAVE_STRENGTH,
                CausticaConfig.Rt.Composite.WATER_WAVE_SPEED,
                CausticaConfig.Rt.Composite.WATER_WAVE_DETAIL);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> pomSettings() {
        return List.of(
                CausticaConfig.Rt.Composite.PARALLAX_ENABLED,
                CausticaConfig.Rt.Composite.PARALLAX_STRENGTH,
                CausticaConfig.Rt.Composite.PARALLAX_QUALITY,
                CausticaConfig.Rt.Composite.PARALLAX_SMOOTHING,
                CausticaConfig.Rt.Composite.PARALLAX_DISTANCE);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> cloudsSettings() {
        return List.of(
                CausticaConfig.Rt.Composite.CLOUDS,
                CausticaConfig.Rt.Composite.CLOUD_STYLE,
                CausticaConfig.Rt.Composite.CLOUD_COVERAGE,
                CausticaConfig.Rt.Composite.CLOUD_HEIGHT,
                CausticaConfig.Rt.Composite.CLOUD_THICKNESS,
                CausticaConfig.Rt.Composite.CLOUD_SHADOW_STRENGTH,
                CausticaConfig.Rt.Composite.CLOUD_OPACITY);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> fogSettings() {
        return List.of(
                CausticaConfig.Rt.Composite.FOG_ENABLED,
                CausticaConfig.Rt.Composite.FOG_DENSITY,
                CausticaConfig.Rt.Composite.FOG_DISTANCE,
                CausticaConfig.Rt.Composite.FOG_ANISOTROPY,
                CausticaConfig.Rt.Composite.FOG_HEIGHT_FALLOFF);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> exposureSettings() {
        return List.of(
                CausticaConfig.Rt.Exposure.MODE,
                CausticaConfig.Rt.Exposure.MANUAL_EV,
                CausticaConfig.Rt.Exposure.KEY,
                CausticaConfig.Rt.Exposure.MIN_EV,
                CausticaConfig.Rt.Exposure.MAX_EV,
                CausticaConfig.Rt.Exposure.ADAPT_UP,
                CausticaConfig.Rt.Exposure.ADAPT_DOWN);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> tonemapSettings() {
        return List.of(
                CausticaConfig.Rt.Tonemapping.OPERATOR,
                CausticaConfig.Rt.Tonemapping.EXPOSURE_EV,
                CausticaConfig.Rt.Tonemapping.GAMMA,
                CausticaConfig.Rt.Tonemapping.SATURATION,
                CausticaConfig.Rt.Tonemapping.CONTRAST);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> hdrSettings() {
        return List.of(
                CausticaConfig.Rt.Hdr.ENABLED,
                CausticaConfig.Rt.Hdr.PAPER_WHITE_NITS,
                CausticaConfig.Rt.Hdr.PEAK_NITS);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> streamingSettings() {
        return List.of(
                CausticaConfig.Rt.Terrain.ASYNC_DISPATCH_PER_PASS,
                CausticaConfig.Rt.Terrain.COMPLETION_RESULTS_PER_PASS,
                CausticaConfig.Rt.Terrain.MAX_INFLIGHT_SECTIONS,
                CausticaConfig.Rt.Terrain.BLAS_COMPACTION);
    }

    private static List<CausticaConfig.RuntimeSetting<?>> debugSettings() {
        return List.of(
                CausticaConfig.Rt.Composite.DEBUG_VIEW,
                CausticaConfig.Rt.FrameStats.ENABLED,
                CausticaConfig.Rt.Omm.STATS,
                CausticaConfig.Rt.Lights.STATS,
                CausticaConfig.Rt.Lights.DUMP,
                CausticaConfig.Rt.Lights.DUMP_RADIUS);
    }

    /**
     * The union of every page's resettable list (including SHaRC's), in declaration order. The hub's
     * global reset is, by construction, exactly "press every sub-screen's own Reset to Defaults".
     */
    public static List<CausticaConfig.RuntimeSetting<?>> allResettableSettings() {
        List<CausticaConfig.RuntimeSetting<?>> all = new ArrayList<>();
        List<List<CausticaConfig.RuntimeSetting<?>>> groups = List.of(
                qualitySettings(),
                upscalingSettings(),
                frameGenSettings(),
                lightingSettings(),
                skySettings(),
                worldSettings(),
                effectsSettings(),
                waterSettings(),
                pomSettings(),
                cloudsSettings(),
                exposureSettings(),
                tonemapSettings(),
                hdrSettings(),
                streamingSettings(),
                debugSettings(),
                RtSharcOptionsScreen.resettableSettings());
        for (List<CausticaConfig.RuntimeSetting<?>> group : groups) {
            for (CausticaConfig.RuntimeSetting<?> setting : group) {
                if (!all.contains(setting)) {
                    all.add(setting);
                }
            }
        }
        return all;
    }
}
