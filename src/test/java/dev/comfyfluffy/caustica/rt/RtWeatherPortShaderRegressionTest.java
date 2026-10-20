package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the PR #21 weather/underwater-haze port onto the 26.3 base: the new WorldPush lanes
 * must stay trailing (no established offset moves), the shader features must stay wired, and
 * the port must never drag the 26.2 downgrade or the cloud/water reverts back in.
 */
final class RtWeatherPortShaderRegressionTest {
    private static final Path REPO_ROOT = repoRoot();

    @Test
    void weatherLanesTrailFogTintInPushOrder() throws IOException {
        String common = read("shaders/world/world_common.slang");
        String composite = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        int fogTint = common.indexOf("public float4   fogTint;");
        int wetness = common.indexOf("public float4   surfaceWetness;");
        int rain = common.indexOf("public float4   rainEffects;");
        int underwater = common.indexOf("public float4   underwaterEffects;");
        int lightning = common.indexOf("public float4   lightning;");
        assertTrue(fogTint >= 0 && wetness > fogTint && rain > wetness
                        && underwater > rain && lightning > underwater,
                "weather lanes must be appended after fogTint in declaration order");
        // The generated WorldPushData follows the Slang declaration order, so the Java
        // constructor call must append the same four lanes after fogTint().
        int javaFog = composite.indexOf("fogTint(),");
        assertTrue(javaFog >= 0
                        && composite.indexOf("new Float4(surfaceWetness", javaFog) > javaFog
                        && composite.indexOf("lightning.intensity())", javaFog) > javaFog,
                "RtComposite must push the weather lanes after fogTint in the same order");
    }

    @Test
    void underwaterHazeIsSourceLitWithStableBlueChromaticity() throws IOException {
        String medium = read("shaders/world/medium.slang");
        String world = read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
        assertTrue(medium.contains("public float3 waterHazeScattering(float3 rayDir, float segmentDistance)"));
        assertTrue(medium.contains("float3 lightRadiance = max(worldPush.lightRadiance.xyz"),
                "haze brightness must come from the frame's real celestial light");
        assertTrue(medium.contains("float lightEnergy = dot(lightRadiance, float3(0.2126, 0.7152, 0.0722));"),
                "source hue must be reduced to a scalar before applying the blue haze colour");
        assertTrue(medium.contains("float3 hazeColor = lerp(float3(0.035, 0.095, 0.22),"));
        assertTrue(world.contains("waterHazeScattering(rd, payload.hitT)"),
                "submerged segments must accumulate in-scatter before Beer-Lambert attenuation");
        assertTrue(world.contains("sky = waterColumnScatter + farWaterBlue * fallbackOpacity;"),
                "underwater TLAS misses must converge to far water, not leak the air sky");
    }

    @Test
    void lightningIsWiredFromSchedulerToSkyAndPointLight() throws IOException {
        assertTrue(read("shaders/world/lighting.slang").contains("public float3 lightningIrradiance("));
        assertTrue(read("shaders/world/world.rgen.slang").contains("lightningIrradiance(p, hitPos, n, isDynamicEntityEarly)"));
        assertTrue(read("shaders/world/world.rmiss.slang").contains("lightningBoltCoverage(WorldRayOrigin(), dir, worldPush.lightning.xyz)"));
        assertTrue(read("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java").contains("LIGHTNING.update(level, camX, camY, camZ, weather.rain(),"));
    }

    @Test
    void rainRipplesRideTheWaveFieldTemporally() throws IOException {
        String water = read("shaders/world/water.slang");
        assertTrue(water.contains("float2 rainRippleGrad(float2 worldXZ, float timeSeconds, float rainAmount, float footprint)"));
        assertTrue(water.contains("grad += rainRippleGrad(domain, currentT, rainAmount, footprint);"));
        assertTrue(water.contains("previousGrad += rainRippleGrad(domain, previousT, rainAmount, footprint);"),
                "both temporal phases need the rings or reprojection smears them");
    }

    @Test
    void portDoesNotRegressThe263BaseOrLocalFixes() throws IOException {
        assertTrue(read("gradle.properties").contains("minecraft_version=26.3"),
                "the port must stay on Minecraft 26.3");
        assertTrue(read("shaders/world/clouds.slang").contains("CLOUD_PERIOD_CELLS = 1024"),
                "the local volumetric cloud rework must not be reverted");
        assertTrue(read("shaders/world/water.slang").contains("origin.xz = waves.planePos.xz;"),
                "the water's-edge refraction fix must not be reverted");
        assertFalse(read("src/main/resources/fabric.mod.json").contains("\"minecraft\": \"26.2\""));
    }

    private static String read(String relative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(relative));
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
