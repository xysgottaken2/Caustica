package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the world-space reprojection contract — the one piece of the renderer where a mistake is
 * invisible at compile time and shows up as a still image that never settles.
 *
 * <p>Motion vectors used to be built inside the guide pass by projecting the shaded point through
 * {@code prevViewProj}. They are now built from the depth G-buffer by {@code world_reproject.comp}.
 * The two properties that must stay true, and that nothing in the toolchain enforces:
 *
 * <ul>
 *   <li>the tracer must not go back to writing {@code gMotion} — a second writer would race this pass
 *       and reintroduce exactly the per-sample scatter the change removes;</li>
 *   <li>the reprojection must derive the 3D position from the depth guide through the shared
 *       camera-relative helper, not from an independently re-derived near/far convention. A sign or
 *       handedness disagreement with the tracer projects to a mirrored/offset point, which every
 *       temporal consumer happily accepts.</li>
 * </ul>
 */
final class RtWorldReprojectionTest {
    private static final Path REPO_ROOT = repoRoot();
    private static final Path GUIDES = REPO_ROOT.resolve("shaders/world/guides.slang");
    private static final Path REPROJECT = REPO_ROOT.resolve("shaders/world/world_reproject.comp.slang");
    private static final Path WORLD_COMMON = REPO_ROOT.resolve("shaders/world/world_common.slang");
    private static final Path RT_COMPOSITE =
            REPO_ROOT.resolve("src/main/java/dev/comfyfluffy/caustica/rt/RtComposite.java");
    private static final Path PIPELINE = REPO_ROOT.resolve(
            "src/main/java/dev/comfyfluffy/caustica/rt/pipeline/RtWorldReprojectPipeline.java");

    /**
     * The guide pass must not write the motion texture any more. It still writes gViewZ (the depth
     * the reprojection reconstructs from) and the correction guide, and it still writes gSpecMotion
     * — that one is DLSS-RR's reflection vector, a genuinely different quantity that a depth buffer
     * cannot express.
     */
    @Test
    void guidePassStopsProducingScreenSpaceMotionVectors() throws IOException {
        String guides = Files.readString(GUIDES);

        assertFalse(guides.contains("gMotion[pix]"),
                "guides.slang must not write gMotion: world_reproject.comp is its only producer");
        assertFalse(guides.contains("prevClip"),
                "the old 2D reprojection (prevViewProj on the shaded point) must stay removed");
        assertTrue(guides.contains("gReprojDelta[pix] = float4(lateral, 0.0);"),
                "the guide pass must publish the lateral world-space offset");
        assertTrue(guides.contains("gReprojDisp[pix] = float4(ownMove, 0.0);"),
                "the guide pass must publish the surface's own motion separately");
        assertTrue(guides.contains("float guideViewZ = isSky ? 1.0e6 :"),
                "gViewZ must keep its sky sentinel: it is the reprojection's depth buffer now");
        assertTrue(guides.contains("gViewZ[pix] = guideViewZ;"),
                "gViewZ must be written unconditionally — it is the reprojection's depth source");
        assertTrue(guides.contains("gSpecMotion[pix] = specMotion;"),
                "reflection motion vectors are still owned by the guide pass (DLSS-RR needs them)");
    }

    /**
     * The reprojection must reconstruct the position from the depth guide and the shared helper, then
     * project it with the PREVIOUS view-projection and report (previous - current) in render pixels.
     */
    @Test
    void reprojectionRebuildsTheWorldPointFromTheDepthBuffer() throws IOException {
        String shader = Files.readString(REPROJECT);

        assertTrue(shader.contains("public RWTexture2D<float>  viewZ;"),
                "the depth G-buffer must be a bound input of the reprojection");
        assertTrue(shader.contains("cameraRelativePointAtViewDepth(jndc, depth, worldPush.invViewProj)"),
                "the 3D position must come from the shared camera-relative reconstruction");
        assertTrue(shader.contains("float3 shown = depthPoint + lateral;"),
                "the CURRENT end must be the world point the pixel shows, not the pixel's own ndc");
        assertTrue(shader.contains("float3 prev = shown - ownMove + worldPush.camDelta;"),
                "the same world point must be moved into the previous camera's frame by its own motion");
        assertTrue(shader.contains("mul(worldPush.prevViewProj, float4(prev, 1.0))"),
                "the previous position must be projected with the PREVIOUS view-projection");
        assertTrue(shader.contains("float4 curClip = mul(worldPush.curViewProj, float4(shown, 1.0));"),
                "the current position must be projected too: subtracting the pixel's ndc instead makes "
                        + "every refracted pixel carry its whole refraction offset as fake motion");
        assertTrue(shader.contains("mv = (prevClip.xy / prevClip.w - curClip.xy / curClip.w) * 0.5 * size;"),
                "output convention: (previous - current) in render pixels, both ends projected");
        assertTrue(shader.contains("if (curClip.w > 0.0 && prevClip.w > 0.0) {"),
                "a point behind either camera has no valid projection; report no motion");
    }

    /**
     * One reconstruction, two users. If {@code world_reproject.comp} grew its own inverse-projection
     * maths while {@code guides.slang} kept the helper's, the correction guide and the position it
     * corrects would be built against different rays, and every pixel would carry a small bogus
     * offset — invisible in review, permanent on screen.
     */
    @Test
    void bothSidesShareOneCameraRelativeReconstruction() throws IOException {
        String common = Files.readString(WORLD_COMMON);
        String guides = Files.readString(GUIDES);
        String shader = Files.readString(REPROJECT);

        assertTrue(common.contains("public float3 cameraRelativePointAtViewDepth("),
                "the reconstruction must live in world_common so every pass can share it");
        assertTrue(guides.contains("cameraRelativePointAtViewDepth(jndc, guideViewZ, worldPush.invViewProj)"),
                "the guide must build its correction from the same helper the reprojection uses");
        assertTrue(shader.contains("import world_common;"),
                "the compute pass must import the shared module, not world_core's raygen bindings");
        assertFalse(shader.contains("import world_core;"),
                "world_core declares the RT guide bindings; importing it would pull them into the "
                        + "compute pipeline layout, which only has the reprojection's five images");
    }

    /** The host must run the reprojection between the trace and every consumer of gMotion. */
    @Test
    void reprojectionRunsBeforeTheDenoisersAndUpscalers() throws IOException {
        String composite = Files.readString(RT_COMPOSITE);

        List<String> needles = List.of(
                "reprojectPipeline = RtWorldReprojectPipeline.create(ctx);",
                "reprojectPipeline.setImages(gViewZ.view, gReprojDelta.view, gReprojDisp.view,",
                "                gMotion.view, output.view);",
                "reprojectPipeline.dispatch(cmd, renderW, renderH, pushConstants);");
        for (String needle : needles) {
            assertTrue(composite.contains(needle), "RtComposite must wire the reprojection: " + needle);
        }

        int dispatch = composite.indexOf("reprojectPipeline.dispatch(");
        int nrd = composite.indexOf("RtNrdDenoiser.INSTANCE.denoise(");
        int svgf = composite.indexOf("svgfDenoiser.reproject(");
        int rr = composite.indexOf("RtDlssRr.INSTANCE.evaluate(");
        assertTrue(dispatch > 0 && nrd > dispatch && svgf > dispatch && rr > dispatch,
                "the motion vectors must be produced before NRD, SVGF and DLSS-RR read them");
    }

    /**
     * The pipeline must push the frame's real {@code WorldPushConstants} block — the same matrices
     * and jitter the trace used — instead of a private copy of prevViewProj/camDelta.
     */
    @Test
    void reprojectionUsesTheFramesPushConstantsBlock() throws IOException {
        String pipeline = Files.readString(PIPELINE);

        assertTrue(pipeline.contains(".size(WorldPushConstantsData.BYTE_SIZE)"),
                "the push range must be the generated WorldPushConstants block size");
        assertTrue(pipeline.contains("VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants)"),
                "the block the world trace built must be the one pushed here");
        assertTrue(pipeline.contains("\"world_reproject.comp.spv\""),
                "the pipeline must load the reprojection SPIR-V the build task emits");
    }

    /** Same root discovery pattern as the other shader regression tests, kept local. */
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
