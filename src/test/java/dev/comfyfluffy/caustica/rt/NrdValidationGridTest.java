package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.rt.pipeline.NrdValidationGrid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the mapping between a viewport index and the rectangle of REBLUR's validation overlay that
 * it lives in.
 *
 * <p>This is the part of the "magnify one cell" option that can be wrong in a way nobody notices:
 * an off-by-one row or column still produces a perfectly plausible image, just of the neighbouring
 * viewport — the classic "I read the wrong gauge" failure. The layout is NRD's own
 * ({@code viewportIndex = row + column}, quarter-resource cells), so it is asserted against values
 * taken from {@code REBLUR_Validation.cs.hlsl} rather than against a reimplementation.
 */
final class NrdValidationGridTest {

    @Test
    void cellsAreRowMajorQuarters() {
        int w = 1920;
        int h = 1080;

        // Cell 0: top-left, where NORMALS is drawn.
        assertArrayEquals(new int[] {0, 0, 480, 270}, NrdValidationGrid.cellRect(0, w, h));
        // Cell 3: top-right, the MV viewport — the one the motion-vector question is asked of.
        assertArrayEquals(new int[] {1440, 0, 480, 270}, NrdValidationGrid.cellRect(3, w, h));
        // Cell 8: third row, first column, DIFF FRAMES.
        assertArrayEquals(new int[] {0, 540, 480, 270}, NrdValidationGrid.cellRect(8, w, h));
        // Cell 11: third row, last column, SPEC FRAMES.
        assertArrayEquals(new int[] {1440, 540, 480, 270}, NrdValidationGrid.cellRect(11, w, h));
        // Cell 15: bottom-right, SPEC HITT.
        assertArrayEquals(new int[] {1440, 810, 480, 270}, NrdValidationGrid.cellRect(15, w, h));
    }

    /** Every cell must land inside the image and the sixteen of them must tile it exactly. */
    @Test
    void cellsTileTheWholeImageWithoutOverlap() {
        for (int w : new int[] {1920, 1280, 853}) {
            for (int h : new int[] {1080, 720, 479}) {
                boolean[][] covered = new boolean[h][w];
                int coveredPixels = 0;
                for (int cell = 0; cell < NrdValidationGrid.CELLS; cell++) {
                    int[] r = NrdValidationGrid.cellRect(cell, w, h);
                    for (int y = r[1]; y < r[1] + r[3]; y++) {
                        for (int x = r[0]; x < r[0] + r[2]; x++) {
                            assertEquals(false, covered[y][x],
                                    "cell " + cell + " overlaps another at " + x + "," + y
                                            + " for " + w + "x" + h);
                            covered[y][x] = true;
                            coveredPixels++;
                        }
                    }
                }
                assertEquals(w * h, coveredPixels, "cells must cover every pixel of " + w + "x" + h);
            }
        }
    }

    /** A stale config value must not be able to produce an empty blit region. */
    @Test
    void outOfGridIndicesAreClampedAndNeverEmpty() {
        assertArrayEquals(NrdValidationGrid.cellRect(0, 1920, 1080), NrdValidationGrid.cellRect(-5, 1920, 1080));
        assertArrayEquals(NrdValidationGrid.cellRect(15, 1920, 1080), NrdValidationGrid.cellRect(99, 1920, 1080));

        int[] tiny = NrdValidationGrid.cellRect(3, 2, 2);
        assertTrue(tiny[2] >= 1 && tiny[3] >= 1, "a degenerate size must still blit one pixel");
    }

    @Test
    void onlyTheSentinelAndRealCellsAreValid() {
        assertTrue(NrdValidationGrid.isValidCell(NrdValidationGrid.WHOLE_GRID));
        for (int cell = 0; cell < NrdValidationGrid.CELLS; cell++) {
            assertTrue(NrdValidationGrid.isValidCell(cell));
        }
        assertFalse(NrdValidationGrid.isValidCell(NrdValidationGrid.CELLS));
        assertFalse(NrdValidationGrid.isValidCell(-2));
    }
}
