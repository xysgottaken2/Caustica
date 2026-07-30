package dev.comfyfluffy.caustica.client;

import com.mojang.serialization.Codec;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.BooleanSetting;
import dev.comfyfluffy.caustica.CausticaConfig.FloatSetting;
import dev.comfyfluffy.caustica.CausticaConfig.IntSetting;
import dev.comfyfluffy.caustica.CausticaConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link CausticaConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dcaustica.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** General runtime-tunable RT options. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            spp(),
            maxBounces(),
            sunSize(),
            entities(),
            particles(),
            waterWaves(),
            handFov(),
            dlssQuality(),
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
            debugView(),
        };
    }

    /**
     * Light-emission options. Only the emissive-block multiplier is surfaced: it controls the brightness of
     * torches, lanterns, glowstone, lava and every other emitter placed in the world. Held-item dynamic
     * lighting keeps working exactly as Caustica ships it — its multiplier
     * ({@code CausticaConfig.Rt.Lights.DYNAMIC_INTENSITY}) is simply not given a slider here and stays at the
     * config/system-property default.
     */
    public static OptionInstance<?>[] lightOptions() {
        return new OptionInstance<?>[] {
            blockEmissiveIntensity(),
        };
    }

    /** Tonemapping/exposure options for the display-mapping pass. */
    public static OptionInstance<?>[] tonemapOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            tonemapOperator(),
            tonemapExposure(),
            tonemapGamma(),
            tonemapSaturation(),
            tonemapContrast(),
        };
    }

    public static OptionInstance<?>[] bloomOptions() {
        return new OptionInstance<?>[] {
            bloomEnabled(),
            bloomIntensity(),
            bloomThreshold(),
            bloomRadius(),
            moonIntensity(),
        };
    }

    private static OptionInstance<Boolean> bloomEnabled() {
        return bool("caustica.options.rt.bloomEnabled", CausticaConfig.Rt.Bloom.ENABLED);
    }

    private static OptionInstance<Integer> bloomIntensity() {
        return hundredths("caustica.options.rt.bloomIntensity", CausticaConfig.Rt.Bloom.INTENSITY, 0, 300);
    }

    private static OptionInstance<Integer> bloomThreshold() {
        return hundredths("caustica.options.rt.bloomThreshold", CausticaConfig.Rt.Bloom.THRESHOLD, 10, 500);
    }

    private static OptionInstance<Integer> bloomRadius() {
        return hundredths("caustica.options.rt.bloomRadius", CausticaConfig.Rt.Bloom.RADIUS, 20, 400);
    }

    private static OptionInstance<Integer> moonIntensity() {
        return hundredths("caustica.options.rt.moonIntensity", CausticaConfig.Rt.DayNight.MOON_INTENSITY, 0, 200);
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

    private static final List<String> TONEMAP_OPERATORS =
            List.of("agx", "pbr_neutral", "aces", "filmic", "linear");

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

    private static OptionInstance<Boolean> waterWaves() {
        return bool("caustica.options.rt.waterWaves", CausticaConfig.Rt.Composite.WATER_WAVES);
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
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7), Codec.INT),
            Math.clamp(setting.value(), 0, 7),
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

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
