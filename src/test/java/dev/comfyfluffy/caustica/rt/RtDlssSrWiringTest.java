package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the DLSS Super Resolution reconstruction slot — the one every non-RR denoiser depends on.
 *
 * <p>Ray Reconstruction denoises and upscales in a single pass, so the render size the trace runs at
 * is derived from "which upscaler is active". Before this slot existed, selecting SVGF, A-SVGF or NRD
 * left that list empty: the trace fell back to 1:1 with the display and the image was blitted across.
 * Nothing failed, nothing logged — the frame just cost ~2.2x the pixels of DLSS Quality, which reads
 * from the outside as "the denoiser destroyed my framerate". Every link in that chain is invisible to
 * the compiler, so each one is pinned here.
 */
final class RtDlssSrWiringTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path RT_COMPOSITE =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
    private static final Path RT_DLSS_SR =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtDlssSr.java");
    private static final Path RT_VIDEO_OPTIONS =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/client/RtVideoOptions.java");
    private static final Path NGX_SHIM =
            REPO_ROOT.resolve("native/ngx_shim/ngx_shim.cpp");

    /**
     * The trace must ask DLSS-SR for a render size. Without this the whole feature degrades silently
     * to "still native resolution", which is exactly the bug that motivated the backend.
     */
    @Test
    void renderSizeIsQueriedFromDlssSrBeforeFallingBackToNative() throws IOException {
        String composite = Files.readString(RT_COMPOSITE);
        assertTrue(composite.contains("boolean dlssSrEnabled = !rrEnabled && RtDlssSr.enabled();"),
                "the render-size pass must give the non-RR reconstruction slot to DLSS-SR");
        assertTrue(composite.contains("} else if (dlssSrEnabled) {\n"
                        + "            optimal = RtDlssSr.INSTANCE.queryOptimalRenderSize(width, height);"),
                "DLSS-SR's own optimal-settings query is the source of truth for its render size");
        assertTrue(composite.contains("renderW = optimal != null ? optimal[0] : width;"),
                "a null query (no DLSS support) must still fall back to a faithful 1:1 trace");
    }

    /**
     * Switching the denoiser changes the render size, so the resize key has to include DLSS-SR's
     * state. Miss it and the trace keeps the previous reconstructor's targets: RR-sized images while
     * SVGF+SR runs, or native-res ones after leaving RR.
     */
    @Test
    void switchingDenoiserResizesTheTraceTargets() throws IOException {
        String composite = Files.readString(RT_COMPOSITE);
        assertTrue(composite.contains("&& renderSizeDlssSrEnabled == dlssSrEnabled "
                        + "&& renderSizeDlssSrQuality == dlssSrQuality"),
                "DLSS-SR must be part of ensureOutput's early-return key");
        assertTrue(composite.contains("renderSizeDlssSrEnabled = dlssSrEnabled;")
                        && composite.contains("renderSizeDlssSrQuality = dlssSrQuality;"),
                "and the key must be recorded after the targets are rebuilt");
        assertTrue(composite.contains("RtDlssSr.INSTANCE.releaseIfDisabled();"),
                "leaving the deep-learning slot must release its NGX history, like RR/FSR/XeSS do");
        assertTrue(composite.contains("RtDlssSr.INSTANCE.destroy();"),
                "teardown must release the feature even if the toggles already changed");
    }

    /**
     * DLSS-SR upscales a denoised image, so it has to run after the denoisers and before the FSR/XeSS
     * slots it now outranks — and it must not be told about a jitter the denoiser already integrated,
     * or its reprojection chases a phantom offset and the image shimmers.
     */
    @Test
    void upscaleRunsAfterTheDenoiserAndDropsTheIntegratedJitter() throws IOException {
        String composite = Files.readString(RT_COMPOSITE);
        int srSlot = composite.indexOf("\"DLSS-SR upscale\"");
        int fsrSlot = composite.indexOf("\"FSR upscale\"");
        assertTrue(srSlot > 0 && fsrSlot > srSlot,
                "the DLSS-SR slot must precede the FSR slot, matching the RR > DLSS-SR > FSR > XeSS priority");
        assertTrue(composite.contains("boolean jitterAlreadyIntegrated = svgfRan || nrdDone;"),
                "a temporal denoiser has integrated the jitter sequence into its output");
        assertTrue(composite.contains("float srJitterX = jitterAlreadyIntegrated ? 0.0f : -jitterX;"),
                "NGX wants the applied offset, negated, and nothing at all once a denoiser integrated it");
        assertTrue(composite.contains("RtDlssSr.INSTANCE.evaluate(cmd.address(), upscaleSource, gDepth, gMotion"),
                "DLSS-SR must read the denoised image plus the depth/motion guides");
    }

    /**
     * The backend must drive the plain DLSS feature, never DLSSD: RR's create/evaluate carry guide
     * buffers this backend does not own, and RR's model-preset enum is a different vocabulary from
     * Super Resolution's. Pinning the two calls and the preset 0 keeps a copy/paste refactor honest.
     */
    @Test
    void backendDrivesSuperSamplingNotRayReconstruction() throws IOException {
        String sr = Files.readString(RT_DLSS_SR);
        assertTrue(sr.contains("lib.createDlss(cmd, renderWidth, renderHeight, displayWidth, displayHeight,"),
                "the shim's plain DLSS create is the one this backend records");
        assertTrue(sr.contains("quality, FEATURE_FLAGS, 0);"),
                "0 = let the DLL pick its per-quality preset; Rt.DlssRr.PRESET is RR's enum");
        assertTrue(sr.contains("lib.evaluate(cmd, feature,"),
                "and its plain DLSS evaluation");
        assertFalse(sr.contains("createDlssd"),
                "DLSS-SR must never create a Ray Reconstruction feature");
        assertTrue(sr.contains("CausticaConfig.Rt.Composite.isDenoiserMode(\n"
                        + "                        CausticaConfig.Rt.Composite.DENOISER_MODE_DLSS_RR)"),
                "the two NGX features are mutually exclusive: SR runs only when RR's mode is not selected");
    }

    /** The Java bindings call these exports unconditionally, so a shim without them fails to load NGX. */
    @Test
    void shimStillExportsTheSuperResolutionEntryPoints() throws IOException {
        String shim = Files.readString(NGX_SHIM);
        for (String export : new String[] { "ngxshim_dlss_available", "ngxshim_query_optimal",
                "ngxshim_create_dlss", "ngxshim_evaluate" }) {
            assertTrue(shim.contains("NGX_SHIM_EXPORT") && shim.contains(export),
                    "the shim must keep exporting " + export);
        }
    }

    /**
     * The model-preset row is RR's; showing it while DLSS-SR reconstructs would offer a model nothing
     * runs. Both DLSS screens build their rows from the same order, so both are pinned.
     */
    @Test
    void presetRowOnlyAppearsForRayReconstruction() throws IOException {
        String options = Files.readString(RT_VIDEO_OPTIONS);
        String guarded =
                "if (CausticaConfig.Rt.Composite.isDenoiserMode(CausticaConfig.Rt.Composite.DENOISER_MODE_DLSS_RR)) {\n"
                        + "                options.add(dlssPreset());\n"
                        + "            }";
        int first = options.indexOf(guarded);
        assertTrue(first > 0, "the DLSS preset row must be gated on the RR denoiser mode");
        assertTrue(options.indexOf(guarded, first + 1) > first,
                "the upscaling sub-screen builds the same rows, so it needs the same gate");
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
