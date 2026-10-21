package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the storm half of the weather system: procedural lightning and the "thunderstorm makes the sky
 * heavy" response that the PR adds on top of plain rain.
 *
 * <p>None of this can run here — the lightning scheduler needs a live {@code ClientLevel} and the
 * shaders need a GPU — so these are source-level contracts about the things that would fail silently:
 *
 * <ul>
 *   <li>a strike published in absolute world coordinates instead of the rebased camera space every
 *       other push position uses (the bolt would render tens of thousands of blocks away, i.e. never);</li>
 *   <li>the storm grey being applied twice, once on the CPU cloud colour and once in
 *       {@code clouds.slang}, which turns a thunderhead into a black smudge;</li>
 *   <li>the slab growth and the shader-side formation growth drifting apart, which clips cloud tops;</li>
 *   <li>the sky and the fog disagreeing about how grey a storm is (a blue horizon behind a grey lid).</li>
 * </ul>
 */
final class RtWeatherStormRegressionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path COMPOSITE =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
    private static final Path CLOUDS = REPO_ROOT.resolve("shaders/world/clouds.slang");
    private static final Path FOG = REPO_ROOT.resolve("shaders/world/fog.slang");
    private static final Path COMMON = REPO_ROOT.resolve("shaders/world/world_common.slang");
    private static final Path MISS = REPO_ROOT.resolve("shaders/world/world.rmiss.slang");
    private static final Path LIGHTING = REPO_ROOT.resolve("shaders/world/lighting.slang");
    private static final Path RGEN = REPO_ROOT.resolve("shaders/world/world.rgen.slang");

    /**
     * The shader rebases every position into camera space; the lightning lane must do the same.
     *
     * <p>{@code terrain.blockX/Y/Z} is the integer origin of that rebase. A strike pushed raw would be
     * correct only while the camera sat within a few blocks of the world origin — everywhere else the
     * bolt, its halo and its point light would all land off-screen, and the feature would look dead
     * without a single error anywhere.
     */
    @Test
    void lightningLaneIsPushedInRebasedCameraSpace() throws IOException {
        String java = Files.readString(COMPOSITE);
        String common = Files.readString(COMMON);

        assertTrue(common.lines().anyMatch(line -> line.matches("\\s*public float4\\s+lightning;.*")),
                "WorldPush must carry a lightning lane for the bolt, flash and point light");
        assertTrue(java.contains("lightning.x() - terrain.blockX")
                        && java.contains("lightning.y() - terrain.blockY")
                        && java.contains("lightning.z() - terrain.blockZ"),
                "the strike position must be rebased by the terrain origin like every other push position");
        assertTrue(java.contains("lightning.intensity()"),
                "the flash envelope must travel in the lane's w component");
    }

    /**
     * The simulation must be driven by the weather, not by the weather-lighting presentation toggle.
     *
     * <p>{@code WEATHER_LIGHTING} exists to restore clear-sky lighting for players who dislike the
     * darkening. Gating the scheduler on it would also stop the bolts, which is not what the option
     * says; the flash and the point light are zeroed per-lane instead.
     *
     * <p>It reads the global storm level on purpose. Which <em>column</em> a bolt may land on is a
     * separate, per-column question answered in {@code RtLightning.chooseStrike} ({@code
     * RtPrecipitation.isLiquidRain} asks that column's biome, the way vanilla's own {@code isRainingAt}
     * does, so there is no thundersnow): gating the scheduler on the camera's biome instead would stop
     * the lightning that a storm keeps throwing into the rainy biome next door.
     */
    @Test
    void lightningIsDrivenByRainNotByThePresentationToggle() throws IOException {
        String java = Files.readString(COMPOSITE);

        assertTrue(java.contains("LIGHTNING.update(level, camX, camY, camZ, weather.rain(),"),
                "the scheduler must read the live, world-wide rain level");
        String lightning = Files.readString(
                REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtLightning.java"));
        assertTrue(lightning.contains("RtPrecipitation.isLiquidRain("),
                "the strike COLUMN, unlike the scheduler, must be a place where rain falls");
        // Ordering: update() runs before the push record is built, so a strike can never be a frame late.
        int update = java.indexOf("LIGHTNING.update(");
        int push = java.indexOf("new Float4((float) (lightning.x() - terrain.blockX)");
        assertTrue(update > 0 && push > update, "the strike must be resolved before the push is written");
    }

    /**
     * Storm darkening has exactly one owner.
     *
     * <p>Vanilla's {@code CLOUD_COLOR} already greys with the weather. On top of that, the deck is
     * darkened once, in {@code cloudWeatherAlbedo}, where every style's shading term reads it. An
     * earlier port also multiplied the CPU-side {@code cloudColorState} colour by a second
     * {@code 1 - 0.22 * storm}: at full storm the two stacked to roughly half brightness, so a
     * thunderhead read as a black smear rather than a heavy lid.
     */
    @Test
    void stormDarkeningIsAppliedOnceInTheShader() throws IOException {
        String clouds = Files.readString(CLOUDS);
        String java = Files.readString(COMPOSITE);

        assertTrue(clouds.contains("return albedo * (1.0 - 0.38 * cloudStormAmount(push));"),
                "clouds.slang owns the storm albedo darkening");
        assertFalse(java.contains("0.22f * storm") || java.contains("stormDarkening"),
                "the CPU cloud colour must not darken a second time: clouds.slang already does it");
        // The CPU helper still decides the fill lane, which the shader multiplies by the density cap.
        assertTrue(java.contains("private static Float4 cloudColorState(float weatherFill)"),
                "cloudColorState must only resolve the colour and the fill lane, not the storm look");
    }

    /**
     * The Java slab and the shader's formation growth must stay the same number.
     *
     * <p>{@code RtComposite.cloudState} grows the marched slab and {@code clouds.slang} grows each
     * formation inside it. If the shader grew further than the slab, storm tops would be clipped flat —
     * the one artefact that reads as "the cloud is cut by an invisible plane".
     */
    @Test
    void stormGrowthMatchesBetweenJavaAndSlang() throws IOException {
        String clouds = Files.readString(CLOUDS);
        String java = Files.readString(COMPOSITE);

        double slang = declaredFloat(clouds, "CLOUD_STORM_GROWTH_BLOCKS");
        double javaValue = declaredFloat(java, "CLOUD_STORM_GROWTH_BLOCKS");
        assertEquals(slang, javaValue, 1.0e-6,
                "CLOUD_STORM_GROWTH_BLOCKS differs between clouds.slang and RtComposite");

        assertTrue(clouds.contains("lerp(minThickness, maxThickness, t) + CLOUD_STORM_GROWTH_BLOCKS"
                        + " * cloudStormAmount(push)"),
                "the shader must add the storm growth to each formation's own extent");
        assertTrue(java.contains("thickness += CLOUD_STORM_GROWTH_BLOCKS * storm;"),
                "the marched slab must grow by the same amount");
    }

    /**
     * Storm severity means the same thing on both sides of the wire.
     *
     * <p>Java derives it from the fill it writes; the shader re-derives it from the two lanes it
     * receives ({@code cloudColor.w} and {@code rainEffects.w}). They agree only while the Java fill
     * stays uncapped and the density cap travels separately — capping the colour lane too would make the
     * smoothstep saturate at a different point than the shader's.
     */
    @Test
    void stormSeverityIsDerivedFromTheSameFillOnBothSides() throws IOException {
        String clouds = Files.readString(CLOUDS);
        String java = Files.readString(COMPOSITE);

        assertTrue(clouds.contains("return clamp(push.cloudColor.w, 0.0, 1.0)"
                        + " * clamp(push.rainEffects.w, 0.0, 1.0);"),
                "the shader's effective fill is the colour lane times the rain-cloud density cap");
        assertTrue(clouds.contains("return fill * fill * (3.0 - 2.0 * fill);"),
                "cloudStormAmount must be the smoothstep of that fill");
        assertTrue(java.contains("float storm = smoothstep(0f, 1f, effectiveFill);"),
                "the Java slab must smoothstep the same effective fill");
        assertTrue(java.contains("cloudColorState(fill),"),
                "the uncapped fill must travel in cloudColor.w");
    }

    /**
     * The sky and the fog must desaturate a storm by the same amount, under the same gate.
     *
     * <p>Two independent constants drift: the sky would keep a little blue while the shafts went
     * neutral, and the horizon would show the seam. Both sites therefore read the shared
     * {@code STORM_GREY_MIX}, and both honour {@code FEATURE_WEATHER_LIGHTING} so the toggle restores
     * the authored colours everywhere at once.
     */
    @Test
    void skyAndFogShareOneStormGreyAmount() throws IOException {
        String common = Files.readString(COMMON);
        String fog = Files.readString(FOG);
        String miss = Files.readString(MISS);

        assertEquals(0.35, declaredFloat(common, "STORM_GREY_MIX"), 1.0e-6,
                "the shared storm grey must be declared once, in world_common");
        assertTrue(fog.contains("public static const float FOG_STORM_GREY = STORM_GREY_MIX;"),
                "fog.slang must reuse the shared amount instead of declaring its own");
        assertTrue(fog.contains("float storm = worldFeature(push, FEATURE_WEATHER_LIGHTING)")
                        && fog.contains("FOG_STORM_GREY * storm"),
                "fog greying must stay behind the weather-lighting feature");
        assertTrue(miss.contains("STORM_GREY_MIX * stormGrey"),
                "the sky must apply the same mix, or horizon and shafts disagree");
        assertTrue(miss.contains("float stormStrength = weatherOn"),
                "the sky's storm lane must be gated on the same feature flag");
        // No stale literal may survive next to the shared constant.
        assertFalse(miss.contains("0.35 * stormGrey"),
                "a hardcoded storm grey next to the shared constant will drift");
    }

    /**
     * Lightning must reach the image through three separate paths, each of which has been the only
     * visible one at some point during the port.
     *
     * <p>The bolt and the atmospheric flash are sky radiance ({@code world.rmiss}); the point light is
     * a shading term reused by both the terrain path and the dedicated early-light block. Dropping any
     * one of them still renders, just with a flash that lights nothing, or a lit ground under a blank
     * sky.
     */
    @Test
    void lightningReachesSkyAndShading() throws IOException {
        String miss = Files.readString(MISS);
        String lighting = Files.readString(LIGHTING);
        String rgen = Files.readString(RGEN);

        assertTrue(miss.contains("float2 lightningBoltCoverage("),
                "the bolt must be a world-space polyline traced against the sky ray");
        assertTrue(miss.contains("const int SEGMENTS = 9;") && miss.contains("48.0 * t"),
                "the bolt must span the storm's own height, not a screen-space sprite");
        assertTrue(miss.contains("if (!rayInWater && flash > 1.0e-4)"),
                "an underwater ray must not see an unattenuated bolt");

        assertTrue(lighting.contains("public float3 lightningIrradiance("),
                "the strike must also light the surface it lands next to");
        assertTrue(lighting.contains("float radiusFade = saturate(1.0 - distanceToLight / 72.0);"),
                "the point light needs a finite, authored radius");
        assertTrue(rgen.contains("lightningIrradiance(handOrigin, hitPos, n, true)")
                        && rgen.contains("lightningIrradiance(p, hitPos, n, isDynamicEntityEarly)"),
                "both the early-light and the main shading path must add the strike term");
    }

    /** {@code public static const float NAME = 1.5;} in Slang, or {@code float NAME = 1.5f;} in Java. */
    private static double declaredFloat(String source, String name) {
        Matcher slang = Pattern.compile(
                "static\\s+const\\s+float\\s+" + Pattern.quote(name) + "\\s*=\\s*(-?[\\d.]+)\\s*;")
                .matcher(source);
        if (slang.find()) {
            return Double.parseDouble(slang.group(1));
        }
        Matcher java = Pattern.compile(
                "static\\s+final\\s+float\\s+" + Pattern.quote(name) + "\\s*=\\s*(-?[\\d.]+)f?\\s*;")
                .matcher(source);
        assertTrue(java.find(), "no float constant named " + name + " in the scanned source");
        return Double.parseDouble(java.group(1));
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
