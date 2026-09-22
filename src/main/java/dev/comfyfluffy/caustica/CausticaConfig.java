package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mutable runtime configuration. Each setting resolves its value, in order of precedence, from a
 * {@code -Dcaustica.*} system property, then the {@code config/caustica.toml} file, then a hardcoded
 * default. The settings UI and any other code call the same {@code set(...)} methods, and {@link #save()}
 * writes the current values back to the TOML file.
 *
 * <p>The system property namespace ({@code caustica.rt.foo}) and the TOML layout are independent: the file
 * uses real nested tables (e.g. {@code [omm]} with a {@code subdivision} key) grouped for readability, while
 * the property namespace stays flat and dotted for convenient one-off {@code -D} overrides.
 */
public final class CausticaConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Caustica");
    private static final List<RuntimeSetting<?>> SETTINGS = new CopyOnWriteArrayList<>();

    private static final Path CONFIG_PATH = resolveConfigPath();
    private static final CommentedFileConfig FILE = loadFile(CONFIG_PATH);

    private CausticaConfig() {
    }

    public static List<RuntimeSetting<?>> settings() {
        return List.copyOf(SETTINGS);
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }

    public static void reloadFromSystemProperties() {
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.reloadFromSystemProperties();
        }
    }

    /**
     * Forces every settings holder to class-initialize so all settings are registered (and have applied
     * their file values). Call before {@link #save()} to write a complete file, and once at startup so the
     * file round-trips the full surface even for settings the renderer has not touched yet.
     */
    public static void ensureRegistered() {
        @SuppressWarnings("unused")
        Object[] touch = {
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Composite.SSS,
            Rt.Composite.WEATHER_LIGHTING, Rt.Composite.DENOISER, Rt.Composite.METALLIC_SHININESS,
            Rt.Composite.WATER_WAVE_STRENGTH, Rt.Composite.WATER_WAVE_SPEED, Rt.Composite.WATER_WAVE_DETAIL,
            Rt.Composite.PARALLAX_QUALITY,
            Rt.Terrain.ASYNC_DISPATCH_PER_PASS, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.EntityTextures.MAX_TEXTURES,
            Rt.DlssRr.ENABLED, Rt.DlssRr.PRESET, Rt.DlssRr.QUALITY, Rt.Fg.ENABLED,
            Rt.Fsr.ENABLED, Rt.Fsr.QUALITY, Rt.Xess.ENABLED, Rt.Xess.QUALITY,
            Rt.Denoise.ENABLED, Rt.Nrd.ENABLED, Rt.Nrd.VALIDATION,
            Rt.Sharc.ENABLED, Rt.Sharc.CELL_SIZE, Rt.Sharc.CACHE_ENTRIES, Rt.Sharc.UPDATE_COVERAGE,
            Rt.Sharc.TEMPORAL_BLEND, Rt.Sharc.START_BOUNCE, Rt.Sharc.STRENGTH, Rt.Sharc.MAX_DISTANCE,
            Rt.Sharc.FRAME_LIFETIME, Rt.Sharc.NORMAL_THRESHOLD, Rt.Sharc.STABLE_FRAMES, Rt.Sharc.DEBUG,
            Rt.Reflex.ENABLED, Rt.Lights.HELD_ITEM_LIGHT, Rt.Lights.DYNAMIC_INTENSITY, Rt.Lights.BLOCK_INTENSITY,
            Rt.Lights.RESTIR_SAMPLING, Rt.Lights.RESTIR_TEMPORAL_HISTORY, Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS,
            Rt.Lights.RESTIR_MAX_AGE, Rt.Hand.FOV_FOLLOWS_CAMERA,
            Rt.Exposure.MODE, Rt.Tonemapping.OPERATOR, Rt.FrameStats.ENABLED, Rt.Hdr.ENABLED, Ngx.PATH,
        };
    }

    /** Writes the default config file if it does not exist yet. */
    public static void saveIfMissing() {
        ensureRegistered();
        if (FILE.valueMap().isEmpty()) {
            save();
        }
    }

    /** Serializes all registered settings to the TOML config file. */
    public static synchronized void save() {
        ensureRegistered();
        writeComments();
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(FILE);
        }
        FILE.save();
    }

    private static void writeComments() {
        FILE.setComment("enabled",
                " Caustica RT renderer configuration.\n"
                        + " A matching -Dcaustica.* system property overrides the value below.");
        FILE.setComment("composite",
                " Per-frame path-tracing controls.\n"
                        + " subsurface-scattering: LabPBR SSS (backlit foliage). Costs one extra shadow ray per\n"
                        + " eligible vertex; off disables the effect entirely.\n"
                        + " weather-lighting: attenuate sun/moon light and darken the sky during rain and\n"
                        + " thunderstorms. Off keeps clear-sky lighting in all weather.\n"
                        + " denoiser: the DLSS Ray Reconstruction denoise+upscale filter. Off presents the raw\n"
                        + " path-traced image at full resolution (noisy reference view). Requires dlss-rr.enabled.\n"
                        + " water-wave-strength/-speed/-detail: height multiplier, animation speed and spectrum\n"
                        + " component count for Animated Water. parallax-quality: multiplier on the POM\n"
                        + " texel-crossing budget (sampling level), independent of parallax-strength (relief depth).");
        FILE.setComment("materials",
                " Material appearance controls. metallic-shininess reduces roughness only on authored metallic\n"
                        + " surfaces; 0 preserves resource-pack material data and 1 gives metals their strongest shine.");
        FILE.setComment("terrain",
                " Render-thread terrain work is bounded by dispatch/result counts per streaming pass.\n"
                        + " Buffer fill and BLAS/OMM preparation run on workers. max-inflight-sections bounds\n"
                        + " the complete snapshot -> worker -> GPU build -> publication lifecycle.");
        FILE.setComment("frame-generation",
                " DLSS Frame Generation. Default off; gated additionally by hardware/driver availability\n"
                        + " (the driver's NGX capability query reports FrameGeneration_Available; RTX 40/50 series only).\n"
                        + " multi-frame-count: frames generated per rendered frame (1 = 2x, 2 = 3x, ...), clamped\n"
                        + " at runtime to the driver's reported DLSSG.MultiFrameCountMax.");
        FILE.setComment("fsr",
                " AMD FSR 3 upscaling (experimental): upscales the path-traced image WITHOUT denoising,\n"
                        + " so raw-trace noise is handled by the renderer's denoiser (SVGF or NRD). quality\n"
                        + " mirrors the DLSS PerfQuality numbering (0 Performance, 1 Balanced, 2 Quality,\n"
                        + " 3 Ultra Performance, 5 native AA). The runtime is bundled for Windows only for\n"
                        + " now; elsewhere the upscaler selector does not offer FSR 3.");
        FILE.setComment("denoise",
                " The renderer's own denoiser (SVGF): geometry-validated temporal reprojection with\n"
                        + " luminance moments, then a variance-guided a-trous wavelet cascade. Default on and\n"
                        + " used by every non-DLSS path; DLSS Ray Reconstruction denoises internally instead,\n"
                        + " and switching NRD on hands the slot to REBLUR.");
        FILE.setComment("nrd",
                " NRD (NVIDIA Real-time Denoisers) REBLUR denoising: the optional alternative to the\n"
                        + " built-in denoiser, running on demodulated per-lobe radiance so diffuse and specular\n"
                        + " are filtered separately. Denoises at render resolution; combine with FSR 3 / XeSS\n"
                        + " (or no upscaler) for the display. Requires the bundled NRD natives (Windows) and is\n"
                        + " mutually exclusive with both DLSS and the built-in denoiser.\n"
                        + " validation: REBLUR's diagnostic overlay; replaces the image with NRD's debug grid.");
        FILE.setComment("sharc",
                " Experimental SHaRC (NVIDIA Spatial Hash Radiance Cache). A shader-only world-space\n"
                        + " radiance cache: early bounces warm a voxel hash with the local light they see,\n"
                        + " and later diffuse bounces query it instead of tracing another noisy continuation.\n"
                        + " cell-size is the voxel edge in blocks; cache-entries is the flat hash capacity;\n"
                        + " update-coverage is the fraction of warm paths that write; temporal-blend is how\n"
                        + " much a new estimate changes an existing entry; start-bounce is the first bounce\n"
                        + " allowed to query; strength scales the cached indirect tail; max-distance bounds\n"
                        + " how far from a cell centre a query may use it; frame-lifetime is how long an\n"
                        + " entry stays fresh; normal-threshold is the minimum normal agreement for a hit;\n"
                        + " stable-frames is how many frames a freshly written cell must age before the\n"
                        + " tracer may use it (guards against the new-cell brightness flash).\n"
                        + " debug: log SHaRC state transitions and a periodic parameter summary to the console.");
        FILE.setComment("reflex",
                " NVIDIA Reflex (VK_NV_low_latency2). Default off; gated additionally by device support.\n"
                        + " minimum-interval-us: 0 = no framerate cap (Reflex just paces submission).");
        FILE.setComment("hand",
                " First-person viewmodel (held item / arm) controls.\n"
                        + " fov-follows-camera: false (vanilla) renders the hand through its own fixed 70-degree\n"
                        + " projection, isolated from the FOV setting. true scales that projection to the\n"
                        + " configured FOV instead, so raising the FOV pushes the arm away and lowering it pulls\n"
                        + " the arm closer, the way the rest of the scene reacts.");
        FILE.setComment("lights",
                " Direct lighting controls. held-item-light toggles the analytic light a luminous held\n"
                        + " item casts (torch in hand lighting up a cave); each item casts its own colour.\n"
                        + " dynamic-intensity scales that held-item light and other dynamic emitters;\n"
                        + " block-emissive-intensity scales emissive blocks placed in the world, both their\n"
                        + " direct-hit emission and sampled area-light contribution. The toggle, both intensity\n"
                        + " sliders and ReSTIR sampling are exposed in the Video Settings screen. restir-sampling reuses\n"
                        + " validated light reservoirs across frames and nearby pixels; off keeps the original\n"
                        + " independent RIS estimator. ris-candidates = 0 disables emitter NEE entirely\n"
                        + " (emitters just gather on direct hit). min-fill-ratio drops sparse emissive\n"
                        + " footprints from the light buffer. stats/dump/dump-radius are debug logging.\n"
                        + " restir-temporal-history / restir-spatial-neighbours / restir-max-age are the live\n"
                        + " anti-flicker tuning pushed in WorldPush.restirTuning: more history and more\n"
                        + " neighbours damp sampling flicker at the price of ghosting/overhead; max-age bounds\n"
                        + " how long a reservoir may survive (4..240, the packed age field is 8-bit).");
        FILE.setComment("tonemap",
                " Scene tonemapping after exposure and before writing the vanilla SDR target. operator selects\n"
                        + " the curve; exposure-ev is an extra post-exposure bias; gamma/saturation/contrast\n"
                        + " are final look controls shared by all operators.");
        FILE.setComment("hdr",
                " HDR display output (ST.2084/PQ). When enabled the swapchain is created in PQ automatically\n"
                        + " (falls back to SDR if the surface doesn't advertise it). paper-white-nits / peak-nits\n"
                        + " drive the scene-HDR -> display mapping.");
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve("caustica.toml");
        } catch (Throwable t) {
            return Path.of("config", "caustica.toml");
        }
    }

    private static CommentedFileConfig loadFile(Path path) {
        CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            config.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to read Caustica config {}: {}", path, e.toString());
        }
        return config;
    }

    private static Boolean fileBoolean(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Boolean>get(tomlPath) : null;
    }

    private static Number fileNumber(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Number>get(tomlPath) : null;
    }

    private static String fileString(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<String>get(tomlPath) : null;
    }

    public interface RuntimeSetting<T> {
        /** The {@code -Dcaustica.*} system property name that overrides this setting. */
        String key();

        /** The dotted path of this setting inside the nested {@code config/caustica.toml} tables. */
        String tomlPath();

        T defaultValue();

        T get();

        void set(T value);

        /**
         * Restores the stored value to the hardcoded default. This is NOT {@code set(defaultValue())}:
         * {@code set} treats its argument as EXTERNAL input (for {@link FloatSetting} that means
         * re-applying the input transform, e.g. degrees -&gt; radians), while the stored default already
         * lives in the internal value domain. The settings UI's "Reset to Defaults" buttons call this so
         * every widget class snaps back to the factory value exactly.
         */
        void resetToDefault();

        void reloadFromSystemProperties();

        /** Writes this setting's current value into the given config at {@link #tomlPath()}. */
        void writeToFile(CommentedConfig config);
    }

    public static final class BooleanSetting implements RuntimeSetting<Boolean> {
        private final String key;
        private final String tomlPath;
        private final boolean defaultValue;
        private volatile boolean value;

        private BooleanSetting(String key, String tomlPath, boolean defaultValue) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Boolean defaultValue() {
            return defaultValue;
        }

        @Override
        public Boolean get() {
            return value;
        }

        public boolean value() {
            return value;
        }

        @Override
        public void set(Boolean value) {
            this.value = value != null ? value : defaultValue;
        }

        @Override
        public void resetToDefault() {
            this.value = defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            set(Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue))));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private boolean resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return Boolean.parseBoolean(prop.trim());
            }
            Boolean fromFile = fileBoolean(tomlPath);
            return fromFile != null ? fromFile : defaultValue;
        }
    }

    public static final class IntSetting implements RuntimeSetting<Integer> {
        private final String key;
        private final String tomlPath;
        private final int defaultValue;
        private final IntUnaryOperator sanitize;
        private volatile int value;

        private IntSetting(String key, String tomlPath, int defaultValue, IntUnaryOperator sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.applyAsInt(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Integer defaultValue() {
            return defaultValue;
        }

        @Override
        public Integer get() {
            return value;
        }

        public int value() {
            return value;
        }

        @Override
        public void set(Integer value) {
            this.value = sanitize.applyAsInt(value != null ? value : defaultValue);
        }

        @Override
        public void resetToDefault() {
            this.value = defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = sanitize.applyAsInt(Integer.parseInt(prop.trim()));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private int resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return sanitize.applyAsInt(Integer.parseInt(prop.trim()));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            return fromFile != null ? sanitize.applyAsInt(fromFile.intValue()) : defaultValue;
        }
    }

    public static final class FloatSetting implements RuntimeSetting<Float> {
        private final String key;
        private final String tomlPath;
        private final float defaultValue;
        // Maps a raw external number (system property, file, or the constructor's raw default) into the
        // stored value domain, e.g. degrees -> radians.
        private final DoubleUnaryOperator inputTransform;
        // Inverse of inputTransform: maps the stored value domain back to the raw external domain (e.g.
        // radians -> degrees) for writeToFile, so a value round-trips through the file unchanged instead
        // of having inputTransform re-applied to an already-transformed number on the next load.
        private final DoubleUnaryOperator outputTransform;
        // Idempotent guard on a value-domain number (clamp / finite check); safe to apply to any source.
        private final DoubleUnaryOperator valueClamp;
        private volatile float value;

        private FloatSetting(String key, String tomlPath, float rawDefault, DoubleUnaryOperator inputTransform,
                             DoubleUnaryOperator outputTransform, DoubleUnaryOperator valueClamp) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.inputTransform = inputTransform;
            this.outputTransform = outputTransform;
            this.valueClamp = valueClamp;
            this.defaultValue = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(rawDefault));
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Float defaultValue() {
            return defaultValue;
        }

        @Override
        public Float get() {
            return value;
        }

        public float value() {
            return value;
        }

        @Override
        public void set(Float value) {
            if (value == null) {
                this.value = defaultValue;
            } else {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(value));
            }
        }

        @Override
        public void resetToDefault() {
            // Store the default directly: defaultValue is already in the INTERNAL domain, so routing it
            // through set() would re-apply inputTransform (a degrees default would be converted to
            // radians twice) and the "reset" would land nowhere near the real default.
            this.value = defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            // Round-trip through Float.toString() so the file gets the shortest decimal that reproduces
            // this float (e.g. "0.6"), not outputTransform's raw double with float's binary noise spelled
            // out to 17 digits (e.g. 0.6000000487130328).
            float raw = (float) outputTransform.applyAsDouble(value);
            config.set(tomlPath, Double.parseDouble(Float.toString(raw)));
        }

        private float resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            if (fromFile == null) {
                return defaultValue;
            }
            return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(fromFile.doubleValue()));
        }
    }

    public static final class StringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private final String defaultValue;
        private final UnaryOperator<String> sanitize;
        private volatile String value;

        private StringSetting(String key, String tomlPath, String defaultValue, UnaryOperator<String> sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.apply(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = sanitize.apply(value != null ? value : defaultValue);
        }

        @Override
        public void resetToDefault() {
            this.value = defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            set(System.getProperty(key, defaultValue));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return sanitize.apply(prop);
            }
            String fromFile = fileString(tomlPath);
            return sanitize.apply(fromFile != null ? fromFile : defaultValue);
        }
    }

    public static final class OptionalStringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private volatile String value;

        private OptionalStringSetting(String key, String tomlPath) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return null;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = value;
        }

        @Override
        public void resetToDefault() {
            this.value = null;
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = System.getProperty(key);
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            if (value != null) {
                config.set(tomlPath, value);
            } else {
                config.remove(tomlPath);
            }
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            return prop != null ? prop : fileString(tomlPath);
        }
    }

    public static final class Rt {
        public static final BooleanSetting ENABLED = bool("caustica.rt", "enabled", true);
        public static final IntSetting WORKER_THREADS =
                intAtLeast("caustica.rt.workerThreads", "worker-threads", defaultWorkerThreads(), 1);

        private Rt() {
        }

        public static final class Composite {
            public static final IntSetting DEBUG_VIEW = intValue("caustica.rt.debugView", "composite.debug-view", 0);
            public static final IntSetting SPP = intAtLeast("caustica.rt.spp", "composite.spp", 1, 1);
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("caustica.rt.maxBounces", "composite.max-bounces", 4, 2, 8);
            public static final BooleanSetting WATER_WAVES =
                    bool("caustica.rt.waterWaves", "composite.water-waves", true);
            /**
             * How opaque ray-traced water is, 0..1. 0 keeps the default near-crystal clarity (biome
             * tint absorption only); 1 adds enough neutral per-block extinction that a one-block
             * column transmits under 2% of the light behind it.
             *
             * <p>This is the first-class control for the DLSS Ray Reconstruction water halo: in
             * near-dark scenes RR's reconstruction of the high-dynamic-range transmitted lobe (the
             * torch-lit floor/rock seen through the sheet) draws a bright ring around the water
             * body. Dimming the transmitted lobe is the only empirically verified mitigation, and
             * this slider exposes it without resorting to a resource-pack water-tint hack. Affects
             * the look only — guide/denoiser work is separate.
             */
            public static final FloatSetting WATER_OPACITY =
                    clampedFloat("caustica.rt.waterOpacity", "composite.water-opacity", 0.0f, 0.0f, 1.0f);
            /**
             * Water wave height multiplier, applied on top of the authored spectrum (0 = a perfectly
             * flat sheet even with Animated Water on, 1 = the tuned default, up to 4 = storm swell).
             * Shader-read on the next frame; safe to drag live.
             */
            public static final FloatSetting WATER_WAVE_STRENGTH =
                    clampedFloat("caustica.rt.waterWaveStrength", "composite.water-wave-strength", 1.0f, 0.0f, 4.0f);
            /**
             * Water wave animation speed multiplier on top of the authored deep-water dispersion clock
             * (0 = frozen sea, 1 = default, up to 4 = time-lapse). Changing it mid-session warps the
             * wave phase once; the animation stays continuous afterwards.
             */
            public static final FloatSetting WATER_WAVE_SPEED =
                    clampedFloat("caustica.rt.waterWaveSpeed", "composite.water-wave-speed", 1.0f, 0.0f, 4.0f);
            /**
             * How many spectrum components the wave field evaluates (the authored maximum is 7, from
             * 28 m swell down to ~1.6 m chop). Lower counts cut GPU cost over large water views at the
             * price of losing the short-wave detail.
             */
            public static final IntSetting WATER_WAVE_DETAIL =
                    clampedInt("caustica.rt.waterWaveDetail", "composite.water-wave-detail", 7, 1, 7);
            /**
             * Extra polish applied only to surfaces authored as metallic. 0 preserves the resource
             * pack's LabPBR roughness; 1 reduces metallic roughness to 15% of its authored value.
             * This changes the GGX lobe rather than adding bloom, so it makes reflections sharper
             * without brightening non-metal materials or light sources.
             */
            public static final FloatSetting METALLIC_SHININESS =
                    clampedFloat("caustica.rt.metallicShininess", "materials.metallic-shininess", 0.0f, 0.0f, 1.0f);
            /**
             * Shader-only Parallax Occlusion Mapping from the LabPBR {@code _n} alpha height channel.
             * The closest-hit shader marches a short ray through the height field and shades the
             * displaced texel; geometry, BLASes and terrain streaming are never touched, so toggling
             * this (or dragging the sliders) is always safe mid-game. Resource packs without authored
             * height maps are unaffected — the material FEATURE_NORMAL bit gates the whole path.
             */
            public static final BooleanSetting PARALLAX_ENABLED =
                    bool("caustica.rt.parallax", "composite.parallax", true);
            /** Virtual relief depth multiplier (1.0 = 1/8 block of relief). */
            public static final FloatSetting PARALLAX_STRENGTH =
                    clampedFloat("caustica.rt.parallaxStrength", "composite.parallax-strength", 1.0f, 0.0f, 4.0f);
            /** Bilinear (smooth) vs pixel-aligned LabPBR height/normal sampling. */
            public static final BooleanSetting PARALLAX_SMOOTHING =
                    bool("caustica.rt.parallaxSmoothing", "composite.parallax-smoothing", true);
            /** Camera distance (blocks) at which relief fades out; full through 80% of the range. */
            public static final FloatSetting PARALLAX_DISTANCE =
                    clampedFloat("caustica.rt.parallaxDistance", "composite.parallax-distance", 64.0f, 16.0f, 256.0f);
            /**
             * POM sampling quality: a multiplier on the per-hit texel-crossing budget RtComposite
             * derives from the relief depth (and the shader clamps into its own compiled bounds). The
             * "level" of POM detail per block face: higher keeps fine relief intact at grazing angles
             * and on high-resolution packs; lower saves height-field samples when the GPU is the
             * bottleneck. Does not change the relief depth — pair with {@link #PARALLAX_STRENGTH}.
             */
            public static final FloatSetting PARALLAX_QUALITY =
                    clampedFloat("caustica.rt.parallaxQuality", "composite.parallax-quality", 1.0f, 0.25f, 4.0f);
            /**
             * LabPBR subsurface scattering. Light entering the back of a thin surface (leaves, grass,
             * ice plants) scatters through toward the viewer via a forward-biased Henyey-Greenstein
             * phase, which is what makes foliage glow when you look through it toward the sun.
             *
             * <p>Only materials that actually author an SSS channel are affected, so turning this off
             * costs nothing visually on the rest of the world — but it does remove one shadow ray per
             * eligible vertex, so it is a genuine performance lever in heavily vegetated scenes. The
             * flag reaches the shaders through {@code WorldPush.featureFlags} and zeroes the SSS
             * strength at the payload seam, which switches off the RIS back lobe and the explicit
             * transmission pass together.
             */
            public static final BooleanSetting SSS =
                    bool("caustica.rt.sss", "composite.subsurface-scattering", true);
            /**
             * Weather-driven lighting. Rain and thunderstorms attenuate the sun/moon NEE radiance,
             * darken and desaturate the sky toward an overcast grey, hide the celestial discs and the
             * stars behind the cloud deck, and add a light haze to the air.
             *
             * <p>Off restores clear-sky lighting in all weather (the vanilla-shader look), which is
             * also the safe setting if a server drives rain constantly.
             */
            public static final BooleanSetting WEATHER_LIGHTING =
                    bool("caustica.rt.weatherLighting", "composite.weather-lighting", true);
            /**
             * Denoising filter (DLSS Ray Reconstruction). Off traces and presents the raw path-traced
             * image — a reference view that is correct but visibly noisy at low SPP, and, because RR
             * also owns the upscale, one that renders at full display resolution instead of RR's
             * chosen render size.
             *
             * <p>This is the player-facing name for the same switch {@code dlss-rr.enabled} exposes;
             * both must be on for the filter to run, so a machine without RR support is unaffected by
             * this toggle. Changing it re-sizes the trace targets on the next frame (see
             * {@code RtComposite.ensureOutput}).
             */
            public static final BooleanSetting DENOISER =
                    bool("caustica.rt.denoiser", "composite.denoiser", true);
            /**
             * Flat, vanilla-style cloud deck drawn by the sky shader.
             *
             * <p>Caustica cancels vanilla's {@code LevelRenderer}, and vanilla's cloud pass lives inside
             * it, so without this the ray-traced world has no clouds at all. Off restores that
             * (cloudless) behaviour; the deck also follows the vanilla Clouds video option, so setting
             * that to OFF hides it regardless of this toggle.
             */
            public static final BooleanSetting CLOUDS =
                    bool("caustica.rt.clouds", "composite.clouds", true);
            /**
             * Cloud rendering style.
             *
             * <p>{@code classic} reproduces vanilla's flat, blocky deck: coverage is quantised to the
             * 12-block cell grid so the silhouette is genuinely square-edged, and the slab is shaded
             * with vanilla's distinct top/side/bottom faces. {@code volumetric} extrudes the same
             * coverage map into a ray-marched slab with self-shadowing and forward scattering — the
             * look heavy shaderpacks produce, at a real GPU cost.
             *
             * <p>Both styles read one shared coverage field, so switching does not move the clouds and
             * the cloud shadows stay identical between them.
             */
            public static final StringSetting CLOUD_STYLE =
                    string("caustica.rt.cloudStyle", "composite.cloud-style", "classic",
                            Composite::sanitizeCloudStyle);
            /**
             * Cloud thickness, 0..1, as a fraction of {@link #CLOUD_MAX_THICKNESS_BLOCKS}.
             *
             * <p>Volumetric: 0 is a flat sheet (the deck collapses to a plane and takes the cheap
             * non-marched path); 1 is a deep bank. Classic: the slider scales the HEIGHT of vanilla's
             * authored cell boxes, floored at vanilla's own 4-block extrusion — classic clouds are
             * always real boxes with lit tops and shaded sides, never thinner than the game draws them.
             */
            public static final FloatSetting CLOUD_THICKNESS =
                    clampedFloat("caustica.rt.cloudThickness", "composite.cloud-thickness", 0.5f, 0.0f, 1.0f);
            /**
             * How much the cloud deck darkens the sun/moon light reaching the ground beneath it.
             *
             * <p>0 means clouds are visible in the sky but cast nothing; 1 means a fully opaque cloud
             * blocks the celestial light completely. The shadow is an analytic query against the same
             * density function the visible deck is drawn from, so it costs no extra ray and the shadow
             * on the ground always matches the cloud overhead.
             */
            public static final FloatSetting CLOUD_SHADOW_STRENGTH =
                    clampedFloat("caustica.rt.cloudShadowStrength", "composite.cloud-shadow-strength",
                            0.75f, 0.0f, 1.0f);
            /**
             * Opacity of the cloud deck. 0 is invisible (and skips the whole cloud path), 1 fully hides
             * the sky behind a cloud. Values in between let the sky, sun and stars show through.
             */
            public static final FloatSetting CLOUD_OPACITY =
                    clampedFloat("caustica.rt.cloudOpacity", "composite.cloud-opacity", 0.9f, 0.0f, 1.0f);
            /**
             * World Y the BASE of the cloud deck sits at. Vanilla's clouds sit at 192; the default is
             * higher because Caustica's clouds have real thickness and a deck whose base is at vanilla
             * height reads as much closer to the ground than vanilla's flat sheet does.
             *
             * <p>Exposed as a slider: with volumetric clouds the deck's distance is a strong part of the
             * look, and the right value depends on the world's terrain height and the player's taste.
             * The range comfortably spans from just above build height to far overhead.
             */
            public static final FloatSetting CLOUD_HEIGHT =
                    clampedFloat("caustica.rt.cloudHeight", "composite.cloud-height", 320.0f, 128.0f, 1024.0f);
            /**
             * Fraction of the sky the deck covers in clear weather. Rain drives this toward fully
             * overcast on top of whatever is set here (see {@code RtComposite.cloudState}).
             */
            public static final FloatSetting CLOUD_COVERAGE =
                    clampedFloat("caustica.rt.cloudCoverage", "composite.cloud-coverage", 0.55f, 0.0f, 1.0f);
            /**
             * Ray-traced volumetric fog: participating air whose light cones, sun shafts and valley
             * mist are integrated per path segment (see {@code shaders/world/fog.slang}).
             *
             * <p>Every light the renderer already samples lights the fog — the sun and moon through
             * the celestial NEE's shadow ray, block emitters (torches, glowstone, lava, ...) through
             * the ReSTIR reservoir's winning sample — so cost scales with samples per pixel, not with
             * the number of lights, and occlusion comes from the same BVH test as surface shadows.
             * That is why there is no leak to fix: a cave has no sun in it because the shadow ray
             * says so, not because a volume grid was sealed.
             *
             * <p>Off restores the current cloudless-air behaviour exactly (the shader gates on the
             * density lane, which the toggle zeroes).
             */
            public static final BooleanSetting FOG_ENABLED =
                    bool("caustica.rt.fog", "composite.fog", false);
            /**
             * Scattering density as a fraction of {@code fog.slang}'s FOG_SIGMA_MAX. 0% is no fog at
             * all (and skips the whole path), 100% is thick haze — at the default slider range a
             * 100-block view still transmits roughly a third of the scene at full value.
             *
             * <p>The default sits at 10% on purpose. Forward scatter scales with the sun radiance the
             * sky shaders push (≈21 at noon), so a heavier default stacks a bright wash onto the sky
             * near the sun: past roughly a quarter slider the sun and moon discs sink into their own
             * haze and the frame's auto-exposure dims everything else in answer. 10% reads as aerial
             * perspective with the discs still punching through; the slider is there to go thicker.
             */
            public static final FloatSetting FOG_DENSITY =
                    clampedFloat("caustica.rt.fogDensity", "composite.fog-density", 0.1f, 0.0f, 1.0f);
            /**
             * How far (in blocks) a ray integrates the medium before the fog simply stops dimming.
             * Shorter distances keep near-field shafts strong without burying the horizon; longer
             * ones push the haze out to the render-distance rim. 16..1024, stepped by 16.
             */
            public static final FloatSetting FOG_DISTANCE =
                    clampedFloat("caustica.rt.fogDistance", "composite.fog-distance", 160.0f, 16.0f, 1024.0f);
            /**
             * Henyey-Greenstein anisotropy of the scatter. Low values make fog glow evenly from every
             * direction; high values concentrate it forward, which is what opens up sun shafts and
             * makes torch cones reach toward the eye. -0.95..0.95; 0.55 is dust/haze territory.
             */
            public static final FloatSetting FOG_ANISOTROPY =
                    clampedFloat("caustica.rt.fogAnisotropy", "composite.fog-anisotropy", 0.55f, -0.95f, 0.95f);
            /**
             * Valley mist. 0 keeps the medium uniform (fog everywhere, including at mountain tops);
             * above 0, density holds full at and below world Y 90 and decays exponentially above it
             * with this falloff scale in blocks — a low valley floors into mist while flight above
             * it clears, from one multiply per sample and no spatial structure of any kind.
             */
            public static final FloatSetting FOG_HEIGHT_FALLOFF =
                    clampedFloat("caustica.rt.fogHeightFalloff", "composite.fog-height-falloff",
                            0.0f, 0.0f, 512.0f);
            /**
             * How much the fog's scatter takes on the air colour vanilla assigns at the camera
             * (the {@code FOG_COLOR} camera attribute — biome blend, rain and dimension are all
             * resolved by the game itself, the same source the cloud deck's colour rides). 0% is
             * the module's neutral cool-white haze in every biome; 100% is vanilla's own fog
             * colour, so swamps green the shafts and thunderstorms grey them out for free.
             */
            public static final FloatSetting FOG_BIOME_TINT =
                    clampedFloat("caustica.rt.fogBiomeTint", "composite.fog-biome-tint", 1.0f, 0.0f, 1.0f);
            public static final FloatSetting SUN_ANGULAR_RADIUS =
                    radians("caustica.rt.sunAngularRadius", "composite.sun-angular-radius-deg", 0.6f);
            public static final FloatSetting MOON_ANGULAR_RADIUS =
                    radians("caustica.rt.moonAngularRadius", "composite.moon-angular-radius-deg", 1.5f);
            public static final FloatSetting SUN_NOON_SOUTH_TILT =
                    radians("caustica.rt.sunNoonSouthDeg", "composite.sun-noon-south-tilt-deg", 30.0f);
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("caustica.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("caustica.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);

            private Composite() {
            }

            /** Shader-side style id; mirrors {@code clouds.slang}'s CLOUD_STYLE_* constants. */
            public static int cloudStyleIndex() {
                return "volumetric".equals(CLOUD_STYLE.get()) ? 1 : 0;
            }

            private static String sanitizeCloudStyle(String value) {
                if (value == null) {
                    return "classic";
                }
                return switch (value.toLowerCase(java.util.Locale.ROOT).replace('-', '_')) {
                    case "volumetric", "volumetrics", "realistic", "3d" -> "volumetric";
                    default -> "classic";
                };
            }
        }

        public static final class Terrain {
            // External keys retain their historical "per-tick" names for config compatibility; terrain
            // streaming is render-pass driven and these Java names reflect the actual scheduling unit.
            public static final IntSetting ASYNC_DISPATCH_PER_PASS =
                    intAtLeast("caustica.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 32, 0);
            public static final IntSetting COMPLETION_RESULTS_PER_PASS =
                    intAtLeast("caustica.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 32, 0);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("caustica.rt.maxInflightSections", "terrain.max-inflight-sections", 32, 0);
            public static final IntSetting SECTION_TABLE_INITIAL_CAPACITY =
                    intAtLeast("caustica.rt.sectionTableInitialCapacity", "terrain.section-table-initial-capacity", 512, 1);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("caustica.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);
            public static final BooleanSetting BLAS_COMPACTION =
                    bool("caustica.rt.blasCompaction", "terrain.blas-compaction", true);

            private Terrain() {
            }
        }

        /**
         * First-person viewmodel (held item / arm) rendering.
         *
         * <p>Vanilla draws the hand through {@code GameRenderer.hudProjection}, a separate perspective built
         * from {@code CameraRenderState.hudFov} — a constant 70° (see {@code Camera.calculateHudFov}) that is
         * deliberately isolated from the FOV slider so the arm never changes size. Enabling
         * {@link #FOV_FOLLOWS_CAMERA} scales that constant to the player's configured FOV instead, so a
         * higher FOV pushes the arm away and a lower FOV pulls it closer.
         *
         * <p>It follows the FOV <em>setting</em>, not {@code Camera.getFov()}: the latter also folds in the
         * transient sprint/zoom multiplier, which would make the arm pump while sprinting and balloon while
         * a spyglass is scoped. The death and underwater/lava FOV modulation baked into {@code hudFov} is
         * preserved either way.
         */
        public static final class Hand {
            public static final BooleanSetting FOV_FOLLOWS_CAMERA =
                    bool("caustica.rt.handFov", "hand.fov-follows-camera", false);

            private Hand() {
            }
        }

        /** Runtime light scaling and RIS block-emitter lights. {@code ris-candidates = 0} disables RIS. */
        public static final class Lights {
            /**
             * Master toggle for the analytic light a luminous held item casts (WorldPush.handLight).
             * Default ON; OFF zeroes the pushed light, so the shader term costs nothing. Gated on the
             * CPU side — dynamic entity emission (flames, glowing items) is untouched by this toggle.
             */
            public static final BooleanSetting HELD_ITEM_LIGHT =
                    bool("caustica.rt.heldItemLight", "lights.held-item-light", true);
            /**
             * Scales the analytic lights created from luminous held items (torches, lanterns, lava
             * buckets, ...) and other dynamic emitters. The default 1.0 keeps Caustica's stock held-item
             * dynamic lighting unchanged; the Video Settings screen exposes it alongside
             * {@link #BLOCK_INTENSITY} so the light a held item casts can be brightened (or dimmed) live.
             */
            public static final FloatSetting DYNAMIC_INTENSITY =
                    lightIntensity("caustica.rt.dynamicLightIntensity", "lights.dynamic-intensity", 1.0f);
            public static final FloatSetting BLOCK_INTENSITY =
                    lightIntensity("caustica.rt.blockLightIntensity", "lights.block-emissive-intensity", 2.0f);
            /**
             * Reservoir-based spatio-temporal resampling for emitter NEE. When disabled, every shading
             * vertex uses the original independent per-frame RIS reservoir and no history allocation is
             * retained. The renderer notices live changes, idles before retiring/recreating the two Vulkan
             * history buffers, and starts newly enabled history from zero.
             */
            public static final BooleanSetting RESTIR_SAMPLING =
                    bool("caustica.rt.restir", "lights.restir-sampling", true);
            /**
             * ReSTIR anti-flicker knobs, pushed per frame in {@code WorldPush.restirTuning} and read by
             * {@code lighting.slang} against the compiled RESTIR_* hard caps — the sliders the player
             * touches when reservoir sampling flickers.
             *
             * <p>TEMPORAL_HISTORY is the temporal effective-sample cap: how much last frame's validated
             * reservoirs count for. More history damps flicker at the price of ghosting when lights
             * move; less tracks changes faster but reintroduces sampling noise.
             */
            public static final IntSetting RESTIR_TEMPORAL_HISTORY =
                    clampedInt("caustica.rt.restirTemporalHistory", "lights.restir-temporal-history", 4, 1, 16);
            /**
             * Spatial reservoir neighbours queried per pixel each frame. More neighbours smooth the
             * light estimate (fewer flashes, more overheads); 0 disables spatial reuse and keeps the
             * purely temporal ReSTIR path.
             */
            public static final IntSetting RESTIR_SPATIAL_NEIGHBOURS =
                    clampedInt("caustica.rt.restirSpatialNeighbours", "lights.restir-spatial-neighbours", 4, 0, 8);
            /**
             * Frames a reservoir is allowed to live before history rejects it. Longer ages converge
             * more (less flicker) but can keep stale light around after big lighting changes. Capped
             * at 240 so the packed 8-bit age field can never overflow.
             */
            public static final IntSetting RESTIR_MAX_AGE =
                    clampedInt("caustica.rt.restirMaxAge", "lights.restir-max-age", 30, 4, 240);
            public static final IntSetting RIS_CANDIDATES =
                    intAtLeast("caustica.rt.risCandidates", "lights.ris-candidates", 8, 0);
            public static final FloatSetting MIN_FILL_RATIO =
                    finiteFloat("caustica.rt.lightMinFillRatio", "lights.min-fill-ratio", 0.25f);
            public static final BooleanSetting STATS = bool("caustica.rt.lightStats", "lights.stats", false);
            public static final BooleanSetting DUMP = bool("caustica.rt.lightDump", "lights.dump", false);
            public static final IntSetting DUMP_RADIUS =
                    intAtLeast("caustica.rt.lightDumpRadius", "lights.dump-radius", 12, 1);

            private Lights() {
            }
        }

        public static final class Omm {
            public static final BooleanSetting ENABLED = bool("caustica.rt.omm", "omm.enabled", true);
            public static final IntSetting SUBDIVISION =
                    clampedInt("caustica.rt.ommSubdivision", "omm.subdivision", 4, 0, 6);
            public static final BooleanSetting STATS = bool("caustica.rt.ommStats", "omm.stats", false);

            private Omm() {
            }
        }

        public static final class Entities {
            public static final BooleanSetting ENABLED = bool("caustica.rt.entities", "entities.enabled", true);
            public static final BooleanSetting PARTICLES_ENABLED =
                    bool("caustica.rt.particles", "particles.enabled", true);
            /**
             * Ray-traced rain and snow. Vanilla draws weather in its own {@code WeatherEffectRenderer}
             * pass inside {@code LevelRenderer.render}, which Caustica cancels, so this switch is what
             * puts precipitation back in the world at all. The columns are vanilla's own — position,
             * ground clipping and fall animation all come from the render state the game already
             * extracts each frame — replayed as real geometry in the acceleration structure.
             *
             * <p>Shares the particle budget ({@code particles.max-particles}) and requires
             * {@code particles.enabled}, since weather rides the same mesh/BLAS as particle billboards.
             */
            public static final BooleanSetting WEATHER_ENABLED =
                    bool("caustica.rt.weatherParticles", "particles.weather-enabled", true);
            /**
             * Visual density of the ray-traced rain/snow sheets, 0..1.
             *
             * <p>Scales the per-column alpha the same way distance does — through stochastic coverage,
             * so lowering it thins the streaks into a drizzle rather than dimming their colour. 1 is
             * the full vanilla downpour; 0 hides precipitation entirely (the overcast sky and
             * light attenuation still follow the weather itself, which is a separate toggle).
             */
            public static final FloatSetting RAIN_DENSITY =
                    clampedFloat("caustica.rt.rainDensity", "particles.rain-density", 1.0f, 0.0f, 1.0f);
            public static final BooleanSetting GLOW_ENABLED =
                    bool("caustica.rt.glow", "entities.glow.enabled", true);
            public static final BooleanSetting NAME_TAGS_ENABLED =
                    bool("caustica.rt.nameTags", "entities.name-tags.enabled", true);
            /** Debug-only: render each model submission twice and require bitwise-identical CPU captures. */
            public static final BooleanSetting CAPTURE_PARITY =
                    bool("caustica.rt.entityCaptureParity", "entities.debug.capture-parity", false);
            public static final IntSetting MAX_ORDINARY_ENTITIES =
                    intAtLeast("caustica.rt.maxOrdinaryEntities", "entities.max-ordinary-entities", 1024, 0);
            public static final IntSetting MAX_BLOCK_ENTITIES =
                    intAtLeast("caustica.rt.maxBlockEntities", "entities.block-entities.max-entities", 1024, 0);
            public static final IntSetting MAX_PARTICLES =
                    intAtLeast("caustica.rt.maxParticles", "particles.max-particles", 1024, 0);
            public static final IntSetting BE_VIEW_CHUNKS =
                    intAtLeast("caustica.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME =
                    intAtLeast("caustica.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 64, 0);
            public static final BooleanSetting REFIT_ENABLED =
                    bool("caustica.rt.entityRefit", "entities.refit.enabled", true);

            private Entities() {
            }

            public static int maxEntities() {
                return Math.addExact(Math.addExact(
                        MAX_ORDINARY_ENTITIES.value(), MAX_BLOCK_ENTITIES.value()), MAX_PARTICLES.value());
            }

            public static int entityListCapacity() {
                return Math.max(16, maxEntities());
            }

            public static int entityMapCapacity() {
                // Fastutil expected-size constructors apply their own load-factor headroom.
                return Math.max(16, MAX_ORDINARY_ENTITIES.value());
            }
        }

        public static final class EntityTextures {
            public static final IntSetting MAX_TEXTURES =
                    intAtLeast("caustica.rt.maxEntityTextures", "entities.textures.max-textures", 256, 1);
            public static final BooleanSetting PBR = bool("caustica.rt.entityPbr", "entities.textures.pbr", true);

            private EntityTextures() {
            }
        }

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("caustica.rt.blockOutline", "overlay.block-outline.enabled", true);

            private Overlay() {
            }
        }

        public static final class DlssRr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.dlssRr", "dlss-rr.enabled", true);
            public static final IntSetting PRESET = intValue("caustica.rt.dlssRr.preset", "dlss-rr.preset", 0);
            public static final IntSetting QUALITY = intValue("caustica.rt.dlssRr.quality", "dlss-rr.quality", 0);

            private DlssRr() {
            }
        }

        /** DLSS Frame Generation. Default off; gated additionally by hardware/driver availability. */
        public static final class Fg {
            public static final BooleanSetting ENABLED = bool("caustica.rt.fg", "frame-generation.enabled", false);
            public static final IntSetting MULTI_FRAME_COUNT =
                    intAtLeast("caustica.rt.fg.multiFrameCount", "frame-generation.multi-frame-count", 1, 1);
            /**
             * Use Caustica's own motion-vector interpolation engine on the FSR 3 / XeSS paths
             * instead of the AMD FSR 3.1 runtime. Default ON: the native engine is not limited to
             * one generated frame per dispatch (the FSR runtime only ever writes outputs[0]) and
             * needs no external runtime. Off falls back to FSR FG (capped at 2x there) for
             * comparison. DLSS Frame Generation is unaffected (it has its own hardware path).
             */
            public static final BooleanSetting NATIVE_ENGINE =
                    bool("caustica.rt.fg.nativeEngine", "frame-generation.native-engine", true);

            private Fg() {
            }
        }

        /**
         * AMD FidelityFX Super Resolution 3 (experimental). Drives the FSR 3 upscaler backend
         * ({@code RtFsrUpscaler} over {@code native/fsr_shim} + the signed AMD FidelityFX Vulkan
         * runtime): FSR 3 upscales without denoising, so the raw trace quality follows SPP until the
         * NRD denoiser lands — the UI labels it experimental accordingly. The runtime is bundled for
         * Windows only for now; where it is absent the upscaler selector does not offer FSR 3.
         */
        public static final class Fsr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.fsr", "fsr.enabled", false);
            /** PerfQuality numbering shared with DLSS: 0 Performance, 1 Balanced, 2 Quality, 3 Ultra
             *  Performance, 5 native AA (4 Ultra Quality does not exist for the FSR 3 upscaler either). */
            public static final IntSetting QUALITY = clampedInt("caustica.rt.fsr.quality", "fsr.quality", 2, 0, 5);

            private Fsr() {
            }
        }

        /**
         * Intel XeSS Super Resolution upscaler (EXPERIMENTAL): the ML-based alternative to FSR 3 —
         * a trained neural network (quantized INT8 on DP4a hardware like GeForce RTX, XMX matrix
         * engines on Intel Arc) reconstructs the display-res image from the render-res trace +
         * motion vectors + depth. Occupies the same upscale slot as DLSS-RR/FSR 3 (mutually
         * exclusive; if a hand-edit enables several, RR &gt; FSR &gt; XeSS). Windows-only like FSR 3
         * (Intel ships the libxess runtime for Windows), plus XeSS device features enabled at
         * vkCreateDevice time (RtDeviceBringup) — a GPU lacking them never sees the option.
         */
        public static final class Xess {
            public static final BooleanSetting ENABLED = bool("caustica.rt.xess", "xess.enabled", false);
            /** PerfQuality numbering shared with DLSS: 0 Performance, 1 Balanced, 2 Quality, 3 Ultra
             *  Performance, 5 native AA — mapped onto xess_quality_settings_t by RtXessUpscaler. */
            public static final IntSetting QUALITY = clampedInt("caustica.rt.xess.quality", "xess.quality", 2, 0, 5);

            private Xess() {
            }
        }

        /**
         * The renderer's own denoiser: SVGF (Spatiotemporal Variance-Guided Filtering) — a
         * geometry-validated temporal reprojection that accumulates luminance moments, followed by
         * an à-trous wavelet cascade whose edge-stops are driven by the variance those moments
         * measure. It is the default denoiser for every non-DLSS path (upscaler Off, FSR 3, XeSS)
         * and replaces the previous fixed-sigma spatial blur + neighbourhood-clamped TAA pair,
         * which could neither hold noise down while the camera moved nor keep converged detail
         * crisp. Default ON: at SPP 1 the raw trace is unusable without it. Hidden under DLSS-RR
         * (which denoises internally) and superseded by NRD when that is switched on.
         */
        public static final class Denoise {
            public static final BooleanSetting ENABLED =
                    bool("caustica.rt.denoise", "denoise.enabled", true);

            private Denoise() {
            }
        }

        /**
         * NRD (NVIDIA Real-time Denoisers) REBLUR_DIFFUSE_SPECULAR — the optional alternative
         * denoiser, NVIDIA's production spatio-temporal filter running on the tracer's demodulated
         * per-lobe signals. It denoises diffuse and specular separately with hit-distance-driven
         * kernels, which resolves reflections far better than a single-signal filter can, and adds
         * its own anti-lag, anti-firefly and temporal stabilization stages.
         *
         * <p>Mutually exclusive with the built-in denoiser: whichever runs owns the slot, because
         * two temporal filters in series fight over the same history. Requires the bundled NRD
         * natives (Windows for now); the option is hidden where they are absent. Costs more GPU
         * than SVGF.
         */
        public static final class Nrd {
            public static final BooleanSetting ENABLED = bool("caustica.rt.nrd", "nrd.enabled", false);
            /**
             * REBLUR's 16-viewport validation overlay (OUT_VALIDATION): diagnostics for inputs,
             * accumulation frame counts, disocclusion/occlusion state — the tool for debugging
             * temporal artifacts in-game. When on, the overlay replaces the normal image.
             */
            public static final BooleanSetting VALIDATION = bool("caustica.rt.nrdValidation", "nrd.validation", false);

            private Nrd() {
            }
        }

        /**
         * Experimental SHaRC (NVIDIA Spatial Hash Radiance Cache) integration.
         *
         * <p>SHaRC is a world-space radiance cache following NVIDIA's design
         * (https://github.com/NVIDIA-RTX/SHARC), collapsed into Caustica's single render pass:
         * every shaded diffuse-capable vertex warms a voxel hash with its outgoing radiance AND
         * back-propagates that light into the voxels of previous path vertices (which also learn
         * sky ambient on misses), so entries converge toward the full outgoing radiance of their
         * surfaces. From {@code startBounce} on, diffuse vertices query the cache <i>before</i>
         * shading and, on a hit, the cached value replaces the vertex's entire remaining path —
         * {@code L += throughput * cached}, exactly the RTXGI SDK sample's usage. The result is
         * shorter path tails, much less multi-bounce noise and a performance win once the cache is
         * warm. It is deliberately experimental — it is a Caustica-native Slang implementation of
         * the algorithm rather than NVIDIA's shader headers — so it is off by default and every
         * tuning knob is exposed in the SHaRC settings sub-screen (plus debug view 13, which paints
         * per-pixel cache query results).
         */
        public static final class Sharc {
            public static final BooleanSetting ENABLED = bool("caustica.rt.sharc", "sharc.enabled", false);
            /**
             * Voxel edge length in blocks. Smaller cells are sharper but need more entries and warm
             * more slowly; larger cells blur light across larger regions. Minecraft blocks are a
             * metre, so 2 matches NVIDIA's typical near-camera voxel scale.
             */
            public static final FloatSetting CELL_SIZE =
                    clampedFloat("caustica.rt.sharc.cellSize", "sharc.cell-size", 2.0f, 1.0f, 64.0f);
            /**
             * Flat spatial-hash capacity in entries. Larger caches cover more world before hash
             * collisions start rejecting writes. NVIDIA recommends ~4M entries for their samples;
             * 256K (12 MiB) is a sane default here, and 1M (48 MiB) is available for big scenes.
             */
            public static final IntSetting CACHE_ENTRIES =
                    clampedInt("caustica.rt.sharc.cacheEntries", "sharc.cache-entries", 1 << 18,
                            2048, 1 << 20);
            /** Fraction of shaded paths that actually write a cache entry. */
            public static final FloatSetting UPDATE_COVERAGE =
                    clampedFloat("caustica.rt.sharc.updateCoverage", "sharc.update-coverage", 0.35f, 0.0f, 1.0f);
            /**
             * Inverse temporal accumulation window: the entry mean is a per-frame window of roughly
             * {@code 1 / blend} frames (0.125 = an 8-frame window, like NVIDIA's
             * accumulationFrameNum). Lower is smoother and slower to react; higher tracks lighting
             * changes faster but flickers.
             */
            public static final FloatSetting TEMPORAL_BLEND =
                    clampedFloat("caustica.rt.sharc.temporalBlend", "sharc.temporal-blend", 0.125f, 0.0f, 0.99f);
            /** First bounce that may consult the cache. Earlier bounces still warm it. */
            public static final IntSetting START_BOUNCE =
                    clampedInt("caustica.rt.sharc.startBounce", "sharc.start-bounce", 2, 1, 6);
            /**
             * Scale applied to the cached outgoing radiance that replaces a vertex's shading and the
             * rest of its path. 1.0 is the NVIDIA behaviour (full replacement, unbiased); lower
             * values darken the cached GI tail in exchange for a softer cache transition.
             */
            public static final FloatSetting STRENGTH =
                    clampedFloat("caustica.rt.sharc.strength", "sharc.strength", 1.0f, 0.0f, 1.0f);
            /**
             * Reserved. Kept (and still published in WorldPush.sharcParams.w) so existing configs
             * keep loading, but the shader no longer reads it: a query position is always inside its
             * own cell, so a "distance from cell centre" limit could never reject anything.
             */
            public static final FloatSetting MAX_DISTANCE =
                    clampedFloat("caustica.rt.sharc.maxDistance", "sharc.max-distance", 96.0f, 4.0f, 256.0f);
            /** Frames an entry stays usable before it is treated as stale and allowed to be replaced. */
            public static final IntSetting FRAME_LIFETIME =
                    clampedInt("caustica.rt.sharc.frameLifetime", "sharc.frame-lifetime", 120, 1, 240);
            /**
             * Minimum cosine between the stored and query normals for a cache hit. A reject gate
             * only — the cached radiance is never scaled by the normal agreement, which is what used
             * to silently zero out marginal hits.
             */
            public static final FloatSetting NORMAL_THRESHOLD =
                    clampedFloat("caustica.rt.sharc.normalThreshold", "sharc.normal-threshold", 0.35f, 0.0f, 1.0f);
            /**
             * Minimum number of accumulated samples before an entry may be queried (the config key
             * keeps its historical "stableFrames" name so existing configs keep loading). A fresh
             * entry with a single sample is usable immediately — it is no noisier than the miss it
             * prevents — but raising this holds brand-new cells back if pop-in flicker appears.
             */
            public static final IntSetting STABLE_FRAMES =
                    clampedInt("caustica.rt.sharc.stableFrames", "sharc.stable-frames", 1, 0, 30);
            /**
             * Debug logging. When on, Caustica logs SHaRC state transitions (enable/disable, buffer
             * allocation/resize, cache resets) and a periodic summary of the active tuning parameters
             * so an experimental rendering issue can be traced without having to guess config values.
             */
            public static final BooleanSetting DEBUG =
                    bool("caustica.rt.sharc.debug", "sharc.debug", false);

            private Sharc() {
            }
        }

        /**
         * NVIDIA Reflex ({@code VK_NV_low_latency2}), exposed in the Video Settings screen. Default
         * off; gated additionally by device support ({@code RtDeviceBringup}). The full loop is
         * implemented: per-frame {@code vkLatencySleepNV} + timeline-semaphore wait pacing
         * ({@code RtReflex.sleep}, hooked at the top of the frame), latency markers around
         * sim/render-submit/present, the swapchain {@code VkSwapchainLatencyCreateInfoNV} the spec
         * requires, and {@code VK_KHR_present_id} correlation. minimum-interval-us: 0 = no
         * framerate cap (Reflex just paces submission).
         */
        public static final class Reflex {
            public static final BooleanSetting ENABLED = bool("caustica.rt.reflex", "reflex.enabled", false);
            public static final BooleanSetting LOW_LATENCY_BOOST =
                    bool("caustica.rt.reflex.boost", "reflex.low-latency-boost", false);
            public static final IntSetting MINIMUM_INTERVAL_US =
                    intAtLeast("caustica.rt.reflex.minIntervalUs", "reflex.minimum-interval-us", 0, 0);

            private Reflex() {
            }
        }

        public static final class Exposure {
            public static final StringSetting MODE =
                    string("caustica.rt.exposure.mode", "exposure.mode", "auto", Exposure::sanitizeMode);
            public static final FloatSetting MANUAL_EV =
                    finiteFloat("caustica.rt.exposure.manualEv", "exposure.manual-ev", 0.0f);
            public static final FloatSetting KEY = exposureScale("caustica.rt.exposure.key", "exposure.key", 0.18f);
            public static final FloatSetting MIN_EV =
                    finiteFloat("caustica.rt.exposure.minEv", "exposure.min-ev", -1.5f);
            public static final FloatSetting MAX_EV =
                    finiteFloat("caustica.rt.exposure.maxEv", "exposure.max-ev", 4.0f);
            public static final FloatSetting ADAPT_UP =
                    exposureScale("caustica.rt.exposure.adaptUp", "exposure.adapt-up", 0.12f);
            public static final FloatSetting ADAPT_DOWN =
                    exposureScale("caustica.rt.exposure.adaptDown", "exposure.adapt-down", 0.35f);

            private Exposure() {
            }

            public static float minEv() {
                return Math.min(MIN_EV.value(), MAX_EV.value());
            }

            public static float maxEv() {
                return Math.max(MIN_EV.value(), MAX_EV.value());
            }

            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-4f, 1.0e4f);
            }

            private static String sanitizeMode(String value) {
                if ("auto".equalsIgnoreCase(value)) {
                    return "auto";
                }
                if ("manual".equalsIgnoreCase(value)) {
                    return "manual";
                }
                return "auto";
            }
        }

        /** Tonemap curve and final look controls for the SDR display mapper. */
        public static final class Tonemapping {
            public static final StringSetting OPERATOR =
                    string("caustica.rt.tonemap.operator", "tonemap.operator", "agx", Tonemapping::sanitizeOperator);
            /** Extra exposure bias applied after the auto/manual exposure image, in EV stops. */
            public static final FloatSetting EXPOSURE_EV =
                    clampedFloat("caustica.rt.tonemap.exposureEv", "tonemap.exposure-ev", 0.0f, -5.0f, 5.0f);
            public static final FloatSetting GAMMA =
                    clampedFloat("caustica.rt.tonemap.gamma", "tonemap.gamma", 1.0f, 0.5f, 3.0f);
            public static final FloatSetting SATURATION =
                    clampedFloat("caustica.rt.tonemap.saturation", "tonemap.saturation", 1.0f, 0.0f, 3.0f);
            public static final FloatSetting CONTRAST =
                    clampedFloat("caustica.rt.tonemap.contrast", "tonemap.contrast", 1.0f, 0.0f, 3.0f);

            private Tonemapping() {
            }

            public static int operatorIndex() {
                return switch (OPERATOR.get()) {
                    case "pbr_neutral" -> 1;
                    case "aces" -> 2;
                    case "filmic" -> 3;
                    case "linear" -> 4;
                    case "psychov", "psycho_v", "psychovisual", "psycho" -> 5;
                    default -> 0; // agx
                };
            }

            private static String sanitizeOperator(String value) {
                if (value == null) {
                    return "agx";
                }
                return switch (value.toLowerCase(java.util.Locale.ROOT).replace('-', '_')) {
                    case "agx" -> "agx";
                    case "pbr_neutral", "pbrneutral", "neutral" -> "pbr_neutral";
                    case "aces" -> "aces";
                    case "filmic" -> "filmic";
                    case "linear", "passthrough", "pass_through", "none" -> "linear";
                    case "psychov", "psycho_v", "psychovisual", "psycho" -> "psychov";
                    default -> "agx";
                };
            }
        }

        /** Render-frame timing + hitch logging. See {@code RtFrameStats}. */
        public static final class FrameStats {
            public static final BooleanSetting ENABLED = bool("caustica.rt.frameStats", "frame-stats.enabled", false);

            private FrameStats() {
            }
        }

        /** Startup Vulkan inventory + {@code VK_EXT_device_fault} reporting on device loss. See {@code VulkanDiagnostics}. */
        public static final class Diagnostics {
            /** Heavy driver-side crash diagnostics: vendor diagnostics-config extensions (shader debug
             * info, resource tracking, automatic checkpoints, shader error reporting) and the
             * {@code deviceFaultVendorBinary} feature (vendor-format crash dump on device loss). Off by
             * default: measured ~10x BLAS build time / -20% fps when enabled. Plain {@code deviceFault}
             * reporting (fault addresses + vendor records) is always on and unaffected. Turn on only
             * while chasing a live device-loss crash. */
            public static final BooleanSetting HEAVY_CRASH_DIAGNOSTICS =
                    bool("caustica.rt.heavyCrashDiagnostics", "diagnostics.heavy-crash-diagnostics", false);

            private Diagnostics() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The nit values drive the scene-HDR → display mapping: SDR paper white maps to
         * {@code paperWhiteNits}, and highlights roll off toward {@code peakNits}.
         */
        public static final class Hdr {
            public static final BooleanSetting ENABLED = bool("caustica.rt.hdr", "hdr.enabled", false);
            public static final FloatSetting PAPER_WHITE_NITS =
                    clampedFloat("caustica.rt.hdr.paperWhiteNits", "hdr.paper-white-nits", 200.0f, 80.0f, 500.0f);
            public static final FloatSetting PEAK_NITS =
                    clampedFloat("caustica.rt.hdr.peakNits", "hdr.peak-nits", 1000.0f, 80.0f, 5000.0f);

            // Snapshot of ENABLED as resolved at startup (system property / config file), before any
            // in-session edit from the options screen. The swapchain's pixel format (PQ vs SDR) is fixed
            // at surface-creation time, so flipping ENABLED later cannot change what's actually presented
            // until a restart — every runtime/rendering check reads this frozen value via enabled(),
            // never ENABLED directly, so the live toggle is a no-op for the current session.
            private static final boolean ENABLED_AT_STARTUP = ENABLED.value();

            private Hdr() {
            }

            /** Whether the HDR display path (world HDR + PQ swapchain + UI overlay) is active this session. */
            public static boolean enabled() {
                return ENABLED_AT_STARTUP;
            }

            /** Whether {@link #ENABLED} has been changed since startup and needs a restart to take effect. */
            public static boolean pendingRestart() {
                return ENABLED.value() != ENABLED_AT_STARTUP;
            }

            /** Absolute nits SDR paper white maps to in the PQ encode (ST.2084 is referenced to 10000 nits). */
            public static float paperWhiteNits() {
                return PAPER_WHITE_NITS.value();
            }

            /** Highlight headroom above paper white, in paper-white-referred units ({@code >= 1}). */
            public static float headroom() {
                return Math.max(1.0f, PEAK_NITS.value() / Math.max(1.0f, PAPER_WHITE_NITS.value()));
            }
        }
    }

    public static final class Ngx {
        public static final OptionalStringSetting PATH = optionalString("caustica.ngx.path", "ngx.path");

        private Ngx() {
        }
    }

    private static BooleanSetting bool(String key, String tomlPath, boolean fallback) {
        return new BooleanSetting(key, tomlPath, fallback);
    }

    private static StringSetting string(String key, String tomlPath, String fallback, UnaryOperator<String> sanitize) {
        return new StringSetting(key, tomlPath, fallback, sanitize);
    }

    private static OptionalStringSetting optionalString(String key, String tomlPath) {
        return new OptionalStringSetting(key, tomlPath);
    }

    private static IntSetting intValue(String key, String tomlPath, int fallback) {
        return new IntSetting(key, tomlPath, fallback, v -> v);
    }

    private static IntSetting intAtLeast(String key, String tomlPath, int fallback, int min) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.max(min, v));
    }

    private static IntSetting clampedInt(String key, String tomlPath, int fallback, int min, int max) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting finiteFloat(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Double.isFinite(v) ? v : fallback);
    }

    private static FloatSetting exposureScale(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, 1.0e-4, 1.0e4));
    }

    private static FloatSetting lightIntensity(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, 0.0, 16.0));
    }

    private static FloatSetting clampedFloat(String key, String tomlPath, float fallback, float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting radians(String key, String tomlPath, float fallbackDegrees) {
        return new FloatSetting(key, tomlPath, fallbackDegrees, Math::toRadians, Math::toDegrees, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
