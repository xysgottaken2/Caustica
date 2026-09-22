package dev.comfyfluffy.caustica.rt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression guards for the optional authored-metal polish control. */
final class RtMetallicShininessShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();

    @Test
    void polishIsPushedAndOnlyChangesAuthoredMetalRoughness() throws IOException {
        String common = source("shaders/world/world_common.slang");
        String core = source("shaders/world/world_core.slang");
        String composite = source("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");

        assertTrue(common.contains("public float4   materialParams;"),
                "WorldPush needs a material parameter lane for live slider updates");
        assertTrue(composite.contains("METALLIC_SHININESS.value()"),
                "RtComposite must push the Metallic Shininess setting every frame");
        assertTrue(core.contains("float polish = saturate(worldPush.materialParams.x) * saturate(metalness);"),
                "polish must be gated by authored metalness, not affect dielectric materials");
        assertTrue(core.contains("lerp(1.0, 0.15, polish)"),
                "maximum polish should retain a finite glossy lobe rather than forcing a noisy delta mirror");
    }

    @Test
    void polishedRoughnessFeedsBothRadianceAndGuidePaths() throws IOException {
        String indirect = source("shaders/world/world.rgen.slang");
        String primary = source("shaders/world/world_primary.rgen.slang");
        String guides = source("shaders/world/guides.slang");

        assertTrue(indirect.contains("metallicShininessRoughness(clamp(payloadRoughness(), 0.0, 1.0), metal)"),
                "the indirect GGX radiance path must use polished roughness");
        assertTrue(primary.contains("metallicShininessRoughness(clamp(payloadRoughness(), 0.0, 1.0), metal)"),
                "the primary guide must agree with the radiance path");
        assertTrue(guides.contains("metallicShininessRoughness(clamp(payloadRoughness(), 0.0, 1.0), endpointMetalness)"),
                "transmission-endpoint guides must agree with the radiance path");
    }

    private static String source(String path) throws IOException {
        return Files.readString(REPO_ROOT.resolve(path));
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (Path candidate = dir; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("shaders/world"))
                    && Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find repository root");
    }
}
