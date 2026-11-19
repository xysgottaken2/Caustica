package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the frame-stats stage registry against the one failure mode it has: an unregistered name.
 *
 * <p>{@code RtFrameStats.FRAME.stage(...)} resolves the name through a map and throws
 * {@link IllegalArgumentException} on a miss — deliberately, so a typo cannot silently record zeros.
 * The cost is that the whole composite dies the moment the overlay is switched on, and the code
 * compiles either way. That had already happened: {@code frame.svgf}, {@code frame.nrd},
 * {@code frame.nrdCombine}, {@code frame.dof}, {@code frame.tracePrimary},
 * {@code frame.traceIndirect}, {@code frame.fsr}, {@code frame.xess}, {@code entity.viewmodel},
 * {@code viewmodel.capture} and {@code terrain.lightGridPublish} were all timed but never registered,
 * so the debug overlay crashed the renderer on the first traced frame.
 *
 * <p>Every call site in {@code src/main/java} is therefore checked against the registry here, which is
 * also what keeps the new {@code frame.dlssSr} slot honest.
 */
final class RtFrameStatsStageRegistryTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path MAIN_JAVA = REPO_ROOT.resolve("src/main/java");
    private static final Path RT_FRAME_STATS = MAIN_JAVA.resolve("dev/comfyfluffy/caustica/rt/RtFrameStats.java");

    private static final Pattern SCOPE_STAGE =
            Pattern.compile("RtFrameStats\\.FRAME\\.stage\\(\\s*\"([^\"]+)\"");
    private static final Pattern RAW_STAGE =
            Pattern.compile("RtFrameStats\\.FRAME\\.(?:startStage|endStage)\\(\\s*\"([^\"]+)\"");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"]+)\"");

    @Test
    void everyStageTheRendererTimesIsRegistered() throws IOException {
        Set<String> registered = registeredFrameStages();
        List<String> missing = new ArrayList<>();
        for (String used : usedFrameStages()) {
            if (!registered.contains(used)) {
                missing.add(used);
            }
        }
        assertTrue(missing.isEmpty(),
                "RtFrameStats.FRAME throws on an unknown stage name, so enabling the frame-stats "
                        + "overlay would crash the composite; register these in the FRAME profile: " + missing);
    }

    private static Set<String> usedFrameStages() throws IOException {
        Set<String> used = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                collect(SCOPE_STAGE, source, used);
                collect(RAW_STAGE, source, used);
            }
        }
        return used;
    }

    /**
     * The stage names of the FRAME profile: the first {@code String[]} literal inside
     * {@code new Profile("frame", ...)}. The second literal block holds the counters, which live in
     * their own namespace, so it must not be read as part of this one.
     */
    private static Set<String> registeredFrameStages() throws IOException {
        String source = Files.readString(RT_FRAME_STATS);
        int profile = source.indexOf("new Profile(\"frame\"");
        assertTrue(profile > 0, "the FRAME profile definition moved; update this test with it");
        int stagesStart = source.indexOf("new String[] {", profile);
        int stagesEnd = source.indexOf("},", stagesStart);
        assertTrue(stagesStart > 0 && stagesEnd > stagesStart, "could not delimit the FRAME stage list");
        Set<String> names = new LinkedHashSet<>();
        collect(LITERAL, source.substring(stagesStart, stagesEnd), names);
        return names;
    }

    private static void collect(Pattern pattern, String source, Set<String> out) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            out.add(matcher.group(1));
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
