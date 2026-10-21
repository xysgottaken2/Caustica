package dev.comfyfluffy.caustica.rt.entity;

/**
 * The viewmodel's per-vertex world displacement, in the same convention every other capture uses.
 *
 * <p>{@link RtEntities} hands every capture to the guide pass as the surface's world displacement since
 * the previous frame, and {@code world_reproject.comp} moves the vector's <em>previous</em> end by it
 * ({@code prev = shown - ownMove + camDelta}). For a rebased capture that displacement is
 * {@code (cur - prev) + rebaseShift}, exactly what the entity uploader builds.
 *
 * <p>The viewmodel is <em>not</em> rebased. {@link RtViewmodel} captures camera-relative vertices around
 * the eye (vanilla composes the hand pose as {@code V^-1 * bob * model}, so the world-to-view rotation
 * cancels), and the TLAS instance is a bare translation by {@code camera - rebase}. The shift that
 * re-expresses last frame's vertex in this frame's space is therefore the <em>camera's</em> travel, and the
 * terrain anchor cannot move camera-relative geometry at all. An earlier revision did the opposite on both
 * counts — it subtracted the camera delta and added the rebase shift — which is a sign error with a large
 * consequence: the hand sits half a block to a block from the eye, where one block of camera travel is
 * several hundred render pixels, so the previous end landed on the far side of where it belonged and the
 * motion vector grew to ~400 px on a walking step. Denoisers and DLSS-RR then saw a mesh that teleports
 * every frame and smeared its history into the scratches reported on the hand.
 *
 * <p>Two degenerate cases have no per-vertex correspondence at all, and both answer the same way:
 * <ul>
 *   <li><b>no previous capture</b> (first frame): no history exists, so report no motion. The host pairs
 *       that with its own first-frame zero camera delta, so both ends of the vector are the same point;</li>
 *   <li><b>the vertex count changed</b> (an item swap): a new mesh has no predecessor, but the hand is
 *       welded to the eye, so its only honest displacement is the camera's. That reprojects to (almost)
 *       zero motion — what the screen actually shows — instead of dragging a previous item's geometry
 *       across the swap, or claiming the whole scene swept past at hand depth.</li>
 * </ul>
 */
final class ViewmodelDisplacement {
    private ViewmodelDisplacement() {
    }

    /**
     * Build one frame of per-vertex displacement.
     *
     * @param cur           this frame's camera-relative vertices, three floats each
     * @param curSize       number of valid floats in {@code cur}
     * @param prev          last frame's camera-relative vertices (may be shorter than {@code cur})
     * @param prevSize      number of valid floats in {@code prev}
     * @param previousValid whether {@code prev} holds a real previous capture
     * @param camDeltaX     camera travel this frame on x (current - previous), in blocks
     * @param camDeltaY     camera travel this frame on y, in blocks
     * @param camDeltaZ     camera travel this frame on z, in blocks
     * @return four floats per vertex (xyz displacement, w unused), or {@code null} for an empty mesh
     */
    static float[] fill(float[] cur, int curSize, float[] prev, int prevSize, boolean previousValid,
                        double camDeltaX, double camDeltaY, double camDeltaZ) {
        int vertices = curSize / 3;
        if (vertices <= 0) {
            return null;
        }
        if (!previousValid) {
            return new float[vertices * 4];
        }
        boolean sameMesh = prev != null && prevSize == curSize;
        float shiftX = (float) camDeltaX;
        float shiftY = (float) camDeltaY;
        float shiftZ = (float) camDeltaZ;
        float[] disp = new float[vertices * 4];
        for (int i = 0; i < vertices; i++) {
            // (cur - prev) is the hand's own travel in the eye's frame; camDelta is the eye's. Their sum is
            // the world displacement the reprojection subtracts from the previous end.
            disp[i * 4] = (sameMesh ? cur[i * 3] - prev[i * 3] : 0.0f) + shiftX;
            disp[i * 4 + 1] = (sameMesh ? cur[i * 3 + 1] - prev[i * 3 + 1] : 0.0f) + shiftY;
            disp[i * 4 + 2] = (sameMesh ? cur[i * 3 + 2] - prev[i * 3 + 2] : 0.0f) + shiftZ;
        }
        return disp;
    }
}
