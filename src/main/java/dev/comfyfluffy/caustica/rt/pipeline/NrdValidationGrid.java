package dev.comfyfluffy.caustica.rt.pipeline;

/**
 * Geometry of REBLUR's validation overlay: which rectangle of the overlay image one viewport
 * occupies.
 *
 * <p>NRD writes a 4x4 grid — {@code viewportId = floor(pixelUv / 0.25)} and
 * {@code viewportIndex = viewportId.y / 0.25 + viewportId.x}, i.e. row-major — and each cell is a
 * quarter of the resource in each axis. That layout is reproduced here rather than approximated,
 * because a half-cell error would show the neighbouring viewport's data: plausible-looking
 * content attributed to the wrong question, which is worse than no overlay at all.
 *
 * <p>The composite uses this to magnify a single cell to the whole frame; the unit tests pin the
 * mapping so it cannot drift from NRD's own indexing.
 */
public final class NrdValidationGrid {
    public static final int COLUMNS = 4;
    public static final int ROWS = 4;
    public static final int CELLS = COLUMNS * ROWS;

    /** Setting value meaning "show the whole grid" (the overlay's normal behaviour). */
    public static final int WHOLE_GRID = -1;

    private NrdValidationGrid() {
    }

    /** True for a value the overlay can honour: {@link #WHOLE_GRID} or an in-grid cell index. */
    public static boolean isValidCell(int cell) {
        return cell == WHOLE_GRID || (cell >= 0 && cell < CELLS);
    }

    /**
     * Source rectangle of one cell inside a {@code width x height} overlay, as {@code {x, y, w, h}}.
     *
     * <p>Out-of-grid indices are clamped into the grid rather than rejected, so a stale value in a
     * config file cannot produce an empty blit region; the result is always at least one pixel wide
     * and tall.
     */
    public static int[] cellRect(int cell, int width, int height) {
        int index = Math.clamp(cell, 0, CELLS - 1);
        int column = index % COLUMNS;
        int row = index / COLUMNS;
        int x0 = width * column / COLUMNS;
        int x1 = width * (column + 1) / COLUMNS;
        int y0 = height * row / ROWS;
        int y1 = height * (row + 1) / ROWS;
        return new int[] {x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)};
    }
}
