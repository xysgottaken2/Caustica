package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cirrus sheet above the volumetric cloud deck.
 *
 * <p>Cirrus are the thin, fibrous ice clouds that streak across the sky well above the cumulus. They
 * are a SECOND LAYER — not part of the marched volume — so most of what can go wrong here is a wiring
 * mistake that still compiles, still renders, and still looks like clouds:
 *
 * <ul>
 *   <li><b>Scope.</b> They must exist only above the <b>volumetric</b> deck. The classic deck is one
 *       flat authored texture with nothing above it, so a cirrus sheet there would be a sky layer the
 *       style was never meant to have.</li>
 *   <li><b>Altitude.</b> The sheet is a fixed gap above the deck's CROWN, not above its centre, or a
 *       thicker deck (or a storm's vertical growth) would push the cumulus up through it.</li>
 *   <li><b>Depth order.</b> The two layers are composited far-to-near off their analytic distances.
 *       Hardcoding an order works on the ground and breaks above the sheet, where the cirrus is the
 *       near layer — cumulus would then be drawn through a veil that should hide them.</li>
 *   <li><b>Periodicity.</b> Every cirrus lattice must repeat inside the anchor wrap, or the sheet
 *       snaps to a different pattern whenever the wind scroll rolls the wrapped anchor over.</li>
 *   <li><b>No shadow.</b> Ice that thin cannot darken the ground, and the Cloud Shadows slider must
 *       keep describing the cumulus footprint it was tuned against.</li>
 * </ul>
 */
final class RtCirrusCloudTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path CLOUDS = REPO_ROOT.resolve("shaders/world/clouds.slang");
    private static final Path WORLD_COMMON = REPO_ROOT.resolve("shaders/world/world_common.slang");
    private static final Path RT_COMPOSITE =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
    private static final Path SUB_SCREENS =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/client/gui/RtSubScreens.java");
    private static final Path OPTIONS =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");
    private static final Path LANG_EN =
            REPO_ROOT.resolve("src/main/resources/assets/caustica/lang/en_us.json");
    private static final Path LANG_PT =
            REPO_ROOT.resolve("src/main/resources/assets/caustica/lang/pt_br.json");

    // ---------------------------------------------------------------- configuration

    /**
     * The toggle is the player-facing half of the feature: on by default (so the sheet exists unless it
     * is turned off), persisted under {@code [composite]}, and registered on the clouds page so that
     * page's "Reset to Defaults" restores it along with the rest of the deck.
     */
    @Test
    void cirrusDefaultsToOnAndIsPartOfTheCloudsPage() throws IOException {
        CausticaConfig.BooleanSetting cirrus = CausticaConfig.Rt.Composite.CIRRUS;

        assertTrue(cirrus.defaultValue(), "cirrus must default to ON — the setting is the opt-OUT");
        assertTrue(cirrus.key().startsWith("caustica.rt."),
                cirrus.key() + " must use the caustica.rt.* property namespace");
        assertEquals("composite.cirrus", cirrus.tomlPath(),
                "the toggle must persist under [composite] in caustica.toml");
        assertTrue(Files.readString(SUB_SCREENS).contains("CausticaConfig.Rt.Composite.CIRRUS"),
                "the toggle must be in the clouds page's reset list, or Reset to Defaults lies about it");
    }

    /**
     * The feature bit is only ever set for the volumetric style, and only when the player's toggle is
     * on. Both halves matter: without the style gate the classic deck grows a layer it has no geometry
     * for, and without the toggle gate the row in the UI would do nothing.
     */
    @Test
    void cirrusFlagIsOnlySetForTheVolumetricStyleAndOnlyWhenEnabled() throws IOException {
        String source = Files.readString(RT_COMPOSITE);
        String body = slice(source, "private static int featureFlags()",
                "// Reports what the pipeline is ACTUALLY doing");

        assertInOrder(body,
                "if (CausticaConfig.Rt.Composite.CLOUDS.value()) {",
                "flags |= FEATURE_CLOUDS;",
                "if (CausticaConfig.Rt.Composite.cloudStyleIndex() == CLOUD_STYLE_VOLUMETRIC) {",
                "flags |= FEATURE_CLOUDS_VOLUMETRIC;",
                "if (CausticaConfig.Rt.Composite.CIRRUS.value()) {",
                "flags |= FEATURE_CIRRUS;");

        // The bit itself is hand-maintained on both sides of the ABI; RtShaderConstantMirrorTest
        // compares the values, this only pins that the shader-side name exists at all.
        assertTrue(Files.readString(WORLD_COMMON).contains("FEATURE_CIRRUS"),
                "world_common.slang must declare FEATURE_CIRRUS");
    }

    /** The screen offers the row for the volumetric style only, and the value survives a style flip. */
    @Test
    void theToggleRowIsOfferedOnlyForTheVolumetricStyle() throws IOException {
        String source = Files.readString(OPTIONS);
        String body = slice(source, "public static OptionInstance<?>[] cloudOptions(",
                "The volumetric fog rows");

        int opacity = body.indexOf("options.add(cloudOpacity());");
        int cirrus = body.indexOf("options.add(cirrus());");
        assertTrue(opacity >= 0, "cloudOptions must still add the opacity row");
        assertTrue(cirrus > opacity, "the cirrus row belongs with the rest of the deck's look controls");
        assertTrue(body.substring(opacity, cirrus).contains("if (!classic) {"),
                "the cirrus row must sit inside the volumetric-only branch, or a classic sky offers a"
                        + " knob with nothing to do");
        assertTrue(source.contains("private static OptionInstance<Boolean> cirrus()"),
                "cloudOptions must build its row from a cirrus() option");
    }

    @Test
    void bothTranslationsExist() throws IOException {
        for (Path lang : new Path[] {LANG_EN, LANG_PT}) {
            String json = Files.readString(lang);
            assertTrue(json.contains("\"caustica.options.rt.cirrus\""),
                    "missing label in " + lang.getFileName());
            assertTrue(json.contains("\"caustica.options.rt.cirrus.tooltip\""),
                    "missing tooltip in " + lang.getFileName());
        }
    }

    // ---------------------------------------------------------------- shader wiring

    /** The shader gate: the deck must be on at all, and the flag must be set. */
    @Test
    void cirrusIsGatedOnTheDeckAndTheFeatureBit() throws IOException {
        String body = slice(Files.readString(CLOUDS),
                "public bool cirrusEnabled(", "public float cloudCirrusHeight(");

        assertTrue(body.contains("cloudsEnabled(push)"),
                "cirrus must follow the deck: coverage 0 or opacity 0 has to remove the sheet too");
        assertTrue(body.contains("worldFeature(push, FEATURE_CIRRUS)"),
                "cirrus must be gated on its own feature bit, never rendered unconditionally");
    }

    /**
     * The sheet sits above the deck's CROWN ({@code clouds.w + thickness * 0.5}), not above its centre:
     * the centre moves up with the deck's own thickness, and a storm grows that thickness further.
     */
    @Test
    void cirrusSitsAboveTheDeckCrown() throws IOException {
        Map<String, Double> c = floats(Files.readString(CLOUDS));
        String body = slice(Files.readString(CLOUDS),
                "public float cloudCirrusHeight(", "public float cloudCirrusCoverage(");

        assertTrue(body.contains(
                        "push.clouds.w + max(push.cloudAnchor.z, 0.0) * 0.5 + CLOUD_CIRRUS_GAP_BLOCKS"),
                "the sheet's altitude must be the deck's crown plus the gap, or a thicker deck (and a"
                        + " storm's growth) pushes the cumulus up through the cirrus");
        assertTrue(c.get("CLOUD_CIRRUS_GAP_BLOCKS") >= 32.0,
                "a gap of " + c.get("CLOUD_CIRRUS_GAP_BLOCKS") + " blocks is inside the deck's own slab;"
                        + " the two layers would intersect");
        assertTrue(c.get("CLOUD_CIRRUS_THICKNESS_BLOCKS") > 0.0,
                "the sheet needs a thickness for the grazing path-length gain");
    }

    /**
     * Every cirrus lattice must be a power of two no coarser than the anchor wrap's own weather lattice,
     * which is what makes each period in blocks divide the wrap exactly.
     */
    @Test
    void everyCirrusLatticeRepeatsInsideTheAnchorWrap() throws IOException {
        Map<String, Double> c = floats(Files.readString(CLOUDS));
        double coarse = c.get("CLOUD_WEATHER_DIV");
        double cells = c.get("CLOUD_CELL_BLOCKS");
        double periodCells = c.get("CLOUD_PERIOD_CELLS");
        double wrap = cells * periodCells * coarse;

        assertTrue(wrap > 0.0, "the anchor wrap period must be resolvable from the shader constants");
        for (String name : new String[] {
                "CLOUD_CIRRUS_STRETCH_DIV", "CLOUD_CIRRUS_FIBRE_DIV", "CLOUD_CIRRUS_PATCH_DIV"}) {
            double div = c.get(name);
            assertTrue(div > 0.0, name + " must be positive");
            assertTrue(isPowerOfTwo(div),
                    name + " = " + div + " is not a power of two; the anchors are reduced modulo the"
                            + " coarsest lattice, so a non-power-of-two divisor would make the sheet snap"
                            + " to a new pattern on rollover");
            assertTrue(div <= coarse,
                    name + " = " + div + " is coarser than CLOUD_WEATHER_DIV (" + coarse + "), so its"
                            + " period in blocks would EXCEED the anchor wrap");
            assertTrue(divides(coarse, div),
                    name + " = " + div + " does not divide the wrap's own lattice divisor " + coarse);
        }
    }

    /**
     * The filaments are stretched along the wind and are NOT a resampling of the cumulus coverage field:
     * a cirrus sheet that merely repeated the deck's footprint would read as a blurred copy of the
     * clouds below instead of a separate high layer.
     */
    @Test
    void cirrusFibresAreStretchedAlongTheWindAndIndependentOfTheDeck() throws IOException {
        String body = slice(Files.readString(CLOUDS),
                "public float cloudCirrusCoverage(", "float cloudCirrusDistance(");
        Map<String, Double> c = floats(Files.readString(CLOUDS));

        assertTrue(body.contains("samplePos.x / (CLOUD_CELL_BLOCKS * CLOUD_CIRRUS_STRETCH_DIV)"),
                "the filaments must be stretched on X (the direction the deck drifts)");
        assertTrue(body.contains("samplePos.y / (CLOUD_CELL_BLOCKS * CLOUD_CIRRUS_FIBRE_DIV)"),
                "the filaments must stay fine on Z, which is what makes them threads");
        assertTrue(c.get("CLOUD_CIRRUS_STRETCH_DIV") / c.get("CLOUD_CIRRUS_FIBRE_DIV") >= 8.0,
                "an anisotropy below 8:1 reads as a smeared sheet, not as cirrus fibres");
        assertFalse(body.contains("cloudCoverageField("),
                "cirrus must be located by their own field, not by the cumulus weather field");
    }

    /**
     * Far-to-near compositing, decided from the two analytic distances. Both branches are pinned: with
     * only the "deck in front" branch present the sheet is invisible above the clouds, and with only the
     * other one the deck is invisible below them.
     */
    @Test
    void theTwoLayersAreCompositedInDepthOrder() throws IOException {
        String body = slice(Files.readString(CLOUDS),
                "public CloudVolume cloudSegment(", "// THE BUG THIS FIXES");

        assertInOrder(body,
                "CloudVolume deck = cloudDeckSegment(",
                "bool deckInFront = cloudDeckDistance(push, originRel, dir) <= cloudCirrusDistance(push, originRel, dir);",
                "CloudVolume cirrus = cloudCirrusSegment(",
                "if (deckInFront) {",
                "result.scatter = deck.scatter + deck.transmittance * cirrus.scatter;");
        assertTrue(body.contains("result.scatter = cirrus.scatter + cirrus.transmittance * deck.scatter;"),
                "with the camera above the sheet the cirrus must become the NEAR layer");
        assertTrue(body.contains("if (!cirrusEnabled(push)) {"),
                "with cirrus off the deck's own result must be returned untouched");
    }

    /** Ice clouds that thin cannot darken the ground; the shadow query stays a cumulus query. */
    @Test
    void cirrusCastsNoGroundShadow() throws IOException {
        String body = slice(Files.readString(CLOUDS), "public float cloudSunShadow(", "// ---- Classic");

        assertFalse(body.contains("cloudCirrus"),
                "the cirrus sheet must not enter the cloud-shadow query: Cloud Shadows describes the"
                        + " cumulus footprint, and a second shadowing layer would silently change it");
    }

    // ---------------------------------------------------------------- helpers

    /** All {@code public static const float NAME = value;} declarations, by name. */
    private static Map<String, Double> floats(String source) {
        Map<String, Double> values = new LinkedHashMap<>();
        Matcher m = Pattern.compile(
                "public static const float\\s+(\\w+)\\s*=\\s*(-?[\\d.]+)\\s*;").matcher(source);
        while (m.find()) {
            values.put(m.group(1), Double.parseDouble(m.group(2)));
        }
        m = Pattern.compile("public static const int\\s+(\\w+)\\s*=\\s*(\\d+)\\s*;").matcher(source);
        while (m.find()) {
            values.putIfAbsent(m.group(1), Double.parseDouble(m.group(2)));
        }
        return values;
    }

    private static String slice(String source, String startNeedle, String endNeedle) {
        int start = source.indexOf(startNeedle);
        assertTrue(start >= 0, "missing snippet start: " + startNeedle);
        int end = source.indexOf(endNeedle, start);
        assertTrue(end > start, "missing snippet end after '" + startNeedle + "': " + endNeedle);
        return source.substring(start, end);
    }

    private static void assertInOrder(String source, String... needles) {
        int at = -1;
        for (String needle : needles) {
            int next = source.indexOf(needle, at + 1);
            assertTrue(next > at, "expected snippet after index " + at + ": " + needle);
            at = next;
        }
    }

    private static boolean isPowerOfTwo(double value) {
        double log = Math.log(value) / Math.log(2.0);
        return Math.abs(log - Math.rint(log)) < 1.0e-9 && value >= 1.0;
    }

    private static boolean divides(double whole, double part) {
        double ratio = whole / part;
        return Math.abs(ratio - Math.rint(ratio)) < 1.0e-9;
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
