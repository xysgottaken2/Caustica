package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaConfig.RuntimeSetting;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtPerformanceProfilesTest {
    private static final Path REPO_ROOT = repoRoot();

    @Test
    void profilesSetRayBudgetsWithoutChangingUpscalerRestirOrCloudSettings() {
        CausticaConfig.ensureRegistered();
        CausticaConfig.BooleanSetting restir = CausticaConfig.Rt.Lights.RESTIR_SAMPLING;
        CausticaConfig.BooleanSetting dlss = CausticaConfig.Rt.DlssRr.ENABLED;
        CausticaConfig.IntSetting dlssQuality = CausticaConfig.Rt.DlssRr.QUALITY;
        CausticaConfig.StringSetting cloudStyle = CausticaConfig.Rt.Composite.CLOUD_STYLE;
        List<RuntimeSetting<?>> changed = List.of(
                CausticaConfig.Rt.Composite.SPP,
                CausticaConfig.Rt.Composite.MAX_BOUNCES,
                CausticaConfig.Rt.Lights.RIS_CANDIDATES,
                CausticaConfig.Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS,
                restir,
                CausticaConfig.Rt.Composite.SSS,
                CausticaConfig.Rt.Composite.PARALLAX_ENABLED,
                CausticaConfig.Rt.Composite.WATER_WAVES,
                dlss,
                dlssQuality,
                cloudStyle);
        try {
            // Deliberately use non-default values: neither profile should silently pick an upscaler
            // or enable/disable ReSTIR (which may allocate/free persistent GPU history buffers).
            restir.set(false);
            dlss.set(false);
            dlssQuality.set(5);
            cloudStyle.set("volumetric");

            RtPerformanceProfiles.apply(RtPerformanceProfiles.Profile.PERFORMANCE);
            assertEquals(1, CausticaConfig.Rt.Composite.SPP.value());
            assertEquals(2, CausticaConfig.Rt.Composite.MAX_BOUNCES.value());
            assertEquals(2, CausticaConfig.Rt.Lights.RIS_CANDIDATES.value());
            assertEquals(0, CausticaConfig.Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS.value());
            assertFalse(CausticaConfig.Rt.Composite.SSS.value());
            assertFalse(CausticaConfig.Rt.Composite.PARALLAX_ENABLED.value());
            assertFalse(CausticaConfig.Rt.Composite.WATER_WAVES.value());
            assertFalse(restir.value());
            assertFalse(dlss.value());
            assertEquals(5, dlssQuality.value());
            assertEquals("volumetric", cloudStyle.get());

            RtPerformanceProfiles.apply(RtPerformanceProfiles.Profile.BALANCED);
            assertEquals(1, CausticaConfig.Rt.Composite.SPP.value());
            assertEquals(3, CausticaConfig.Rt.Composite.MAX_BOUNCES.value());
            assertEquals(4, CausticaConfig.Rt.Lights.RIS_CANDIDATES.value());
            assertEquals(2, CausticaConfig.Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS.value());
            assertTrue(CausticaConfig.Rt.Composite.SSS.value());
            assertTrue(CausticaConfig.Rt.Composite.PARALLAX_ENABLED.value());
            assertTrue(CausticaConfig.Rt.Composite.WATER_WAVES.value());
            assertFalse(restir.value());
            assertFalse(dlss.value());
            assertEquals(5, dlssQuality.value());
            assertEquals("volumetric", cloudStyle.get());
        } finally {
            changed.forEach(setting -> setting.resetToDefault());
        }
    }

    @Test
    void quickProfilesAndTheirTuningControlsAreWiredIntoTheQualityScreen() throws IOException {
        String videoOptions = Files.readString(REPO_ROOT.resolve(
                "src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java"));
        String subScreens = Files.readString(REPO_ROOT.resolve(
                "src/main/java/dev/comfyfluffy/caustica/client/gui/RtSubScreens.java"));

        assertTrue(videoOptions.contains("public static OptionInstance<?>[] performanceOptions()"));
        assertTrue(videoOptions.contains("performanceProfileButton(RtPerformanceProfiles.Profile profile"));
        assertTrue(subScreens.contains("RtPerformanceProfiles.Profile.PERFORMANCE"));
        assertTrue(subScreens.contains("RtPerformanceProfiles.Profile.BALANCED"));
        assertTrue(subScreens.contains("RtVideoOptions.performanceOptions()"));
        assertTrue(subScreens.contains("CausticaConfig.Rt.Lights.RIS_CANDIDATES"));
        assertTrue(subScreens.contains("CausticaConfig.Rt.Composite.PARALLAX_ENABLED"));
        assertTrue(subScreens.contains("CausticaConfig.Rt.Composite.WATER_WAVES"));
    }

    @Test
    void profileLabelsAndTooltipsExistInEveryBundledLocale() throws IOException {
        List<String> keys = List.of(
                "caustica.options.rt.performanceProfilesHeader",
                "caustica.options.rt.performanceControlsHeader",
                "caustica.options.rt.performanceProfile.performance",
                "caustica.options.rt.performanceProfile.performance.tooltip",
                "caustica.options.rt.performanceProfile.balanced",
                "caustica.options.rt.performanceProfile.balanced.tooltip");
        Path localeDir = REPO_ROOT.resolve("src/main/resources/assets/caustica/lang");
        try (var locales = Files.list(localeDir)) {
            for (Path locale : locales.filter(path -> path.toString().endsWith(".json")).toList()) {
                String json = Files.readString(locale);
                for (String key : keys) {
                    assertTrue(json.contains("\"" + key + "\""),
                            () -> locale.getFileName() + " is missing localization key " + key);
                }
            }
        }
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("shaders/world"))
                    && Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not locate the repository root from " + dir);
    }
}
