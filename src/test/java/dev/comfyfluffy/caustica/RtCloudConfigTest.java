package dev.comfyfluffy.caustica;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;

import static dev.comfyfluffy.caustica.CausticaConfig.Rt.Composite.CLOUD_MAX_THICKNESS;
import static dev.comfyfluffy.caustica.CausticaConfig.Rt.Composite.CLOUD_MIN_THICKNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Endpoint invariants plus real TOML save/load across fresh JVMs, without Minecraft or a GPU. */
final class RtCloudConfigTest {
    @TempDir Path directory;
    private int previousMin;
    private int previousMax;

    @BeforeEach
    void rememberSettings() {
        previousMin = CLOUD_MIN_THICKNESS.value();
        previousMax = CLOUD_MAX_THICKNESS.value();
    }

    @AfterEach
    void restoreSettings() {
        CLOUD_MIN_THICKNESS.set(previousMin);
        CLOUD_MAX_THICKNESS.set(previousMax);
    }

    @Test
    void editedEndpointMovesItsPeerAndClampsToBlocks() {
        CLOUD_MIN_THICKNESS.resetToDefault();
        CLOUD_MAX_THICKNESS.resetToDefault();
        assertRange(16, 22);
        CLOUD_MAX_THICKNESS.set(19);
        CLOUD_MIN_THICKNESS.set(21);
        assertRange(21, 21);
        CLOUD_MAX_THICKNESS.set(17);
        assertRange(17, 17);
        CLOUD_MIN_THICKNESS.set(Integer.MAX_VALUE);
        assertRange(22, 22);
        CLOUD_MAX_THICKNESS.set(Integer.MIN_VALUE);
        assertRange(16, 16);
        CLOUD_MIN_THICKNESS.resetToDefault();
        CLOUD_MAX_THICKNESS.resetToDefault();
        assertRange(16, 22);
    }

    @Test
    void everyLegalEqualPairRemainsEqual() {
        for (int blocks = 16; blocks <= 22; blocks++) {
            CLOUD_MIN_THICKNESS.set(blocks);
            CLOUD_MAX_THICKNESS.set(blocks);
            assertRange(blocks, blocks);
        }
    }

    @Test
    void rangesRoundTripThroughTheRealConfigInAnotherJvm() throws Exception {
        for (int[] pair : new int[][] {{16, 22}, {18, 21}, {19, 19}, {22, 22}}) {
            assertTrue(runProbe("write", Integer.toString(pair[0]), Integer.toString(pair[1]))
                    .contains("range=" + pair[0] + "," + pair[1]));
            String file = Files.readString(directory.resolve("config/caustica.toml"));
            assertTrue(file.contains("cloud-min-thickness = " + pair[0]), file);
            assertTrue(file.contains("cloud-max-thickness = " + pair[1]), file);
            assertTrue(runProbe("read").contains("range=" + pair[0] + "," + pair[1] + ";classic=0.37"));
        }
    }

    @Test
    void oldFileGetsNewDefaultsWithoutDestroyingClassicThickness() throws Exception {
        config("[composite]\ncloud-thickness = 0.37\ncloud-style = 'classic'\n");
        assertTrue(runProbe("read").contains("range=16,22;classic=0.37"));
    }

    @Test
    void invalidFileIsClampedAndOrderedBeforeRendering() throws Exception {
        config("[composite]\ncloud-min-thickness = 99\ncloud-max-thickness = -3\n");
        // Maximum is the last-loaded endpoint, so it wins an invalid persisted pair.
        assertTrue(runProbe("read").contains("range=16,16"));
        config("[composite]\ncloud-min-thickness = 22\ncloud-max-thickness = 18\n");
        assertTrue(runProbe("read").contains("range=18,18"));
    }

    @Test
    void systemPropertiesOverrideTheFileAndReloadKeepsThePairValid() throws Exception {
        config("[composite]\ncloud-min-thickness = 16\ncloud-max-thickness = 22\n");
        assertTrue(runProbe("properties", "-Dcaustica.rt.cloudMinThickness=20",
                "-Dcaustica.rt.cloudMaxThickness=21").contains("range=20,21"));
        assertTrue(runProbe("reload").contains("range=17,17"));
    }

    private void config(String text) throws Exception {
        Files.createDirectories(directory.resolve("config"));
        Files.writeString(directory.resolve("config/caustica.toml"), text);
    }

    private static void assertRange(int min, int max) {
        assertEquals(min, CLOUD_MIN_THICKNESS.value());
        assertEquals(max, CLOUD_MAX_THICKNESS.value());
    }

    private String runProbe(String action, String... args) throws Exception {
        // Gradle's test worker may use an isolated loader rather than java.class.path. Resolve the
        // actual production dependencies' code locations; no fake settings or mocked TOML parser.
        var paths = new LinkedHashSet<String>();
        for (Class<?> type : new Class<?>[] {RtCloudConfigProbe.class, CausticaConfig.class,
                CommentedConfig.class, TomlFormat.class, FabricLoader.class, LoggerFactory.class}) {
            paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        var command = new ArrayList<String>();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        for (String arg : args) if (arg.startsWith("-D")) command.add(arg);
        command.add("-cp");
        command.add(String.join(File.pathSeparator, paths));
        command.add(RtCloudConfigProbe.class.getName());
        command.add(action);
        for (String arg : args) if (!arg.startsWith("-D")) command.add(arg);
        Path output = directory.resolve("probe-output.txt");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("config probe timed out");
        }
        String log = Files.readString(output);
        assertEquals(0, process.exitValue(), log);
        return log;
    }
}

/** Separate process: production settings initialize from a fresh file, just as on game restart. */
final class RtCloudConfigProbe {
    public static void main(String[] args) {
        CausticaConfig.ensureRegistered();
        if ("write".equals(args[0])) {
            CLOUD_MIN_THICKNESS.set(Integer.parseInt(args[1]));
            CLOUD_MAX_THICKNESS.set(Integer.parseInt(args[2]));
            CausticaConfig.Rt.Composite.CLOUD_THICKNESS.set(0.37f);
            CausticaConfig.save();
        } else if ("reload".equals(args[0])) {
            System.setProperty("caustica.rt.cloudMinThickness", "22");
            System.setProperty("caustica.rt.cloudMaxThickness", "17");
            CausticaConfig.reloadFromSystemProperties();
        }
        System.out.println("range=" + CLOUD_MIN_THICKNESS.value() + "," + CLOUD_MAX_THICKNESS.value()
                + ";classic=" + CausticaConfig.Rt.Composite.CLOUD_THICKNESS.value());
    }
}
