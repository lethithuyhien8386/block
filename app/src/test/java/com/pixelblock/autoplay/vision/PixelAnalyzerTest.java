package com.pixelblock.autoplay.vision;

import org.junit.Test;
import static org.junit.Assert.*;

/** Android local JVM tests: intentionally do NOT import java.awt or javax.imageio. */
public final class PixelAnalyzerTest {
    private static final int W = 450, H = 750;
    private static final int BG = 0xffdab887, DARK = 0xff4b301d, LINE = 0xff785d44;
    private static final int TILE = 0xffe8c487;

    private static void fill(int[] px, int x1, int y1, int x2, int y2, int c) {
        for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) {
            if (x >= 0 && y >= 0 && x < W && y < H) px[y * W + x] = c;
        }
    }
    static int[] sample(boolean occupied) {
        int[] p = new int[W * H];
        java.util.Arrays.fill(p, BG);
        fill(p, 48, 181, 402, 535, DARK);
        for (int k = 0; k <= 9; k++) {
            int x = 48 + k * 39;
            int y = 181 + k * 39;
            fill(p, x - 1, 181, x + 2, 535, LINE);
            fill(p, 48, y - 1, 402, y + 2, LINE);
        }
        if (occupied) fill(p, 48 + 2 * 39 + 5, 181 + 3 * 39 + 5,
                48 + 3 * 39 - 5, 181 + 4 * 39 - 5, TILE);
        fill(p, 48, 612, 402, 718, DARK);
        // A singleton, a horizontal domino and a diagonal pair.
        fill(p, 116, 654, 129, 667, TILE);
        fill(p, 210, 654, 223, 667, TILE);
        fill(p, 226, 654, 239, 667, TILE);
        fill(p, 315, 647, 328, 660, TILE);
        fill(p, 331, 663, 344, 676, TILE);
        return p;
    }

    @Test public void detectsNineByNineWithThreePieces() {
        PixelAnalyzer.Snapshot s = new PixelAnalyzer().analyze(sample(false), W, H);
        assertNotNull(s.board);
        assertEquals(9, s.board.size);
        assertEquals(3, s.pieces.size());
        assertEquals(1, s.pieces.get(0).tiles.size());
        assertEquals(2, s.pieces.get(1).tiles.size());
        assertEquals(2, s.pieces.get(2).tiles.size());
        assertTrue(s.playable());
    }
    @Test public void seesFilledCell() {
        PixelAnalyzer.Snapshot s = new PixelAnalyzer().analyze(sample(true), W, H);
        assertTrue(s.occupied[3][2]);
        assertFalse(s.occupied[0][0]);
    }
    @Test public void handlesEightAndTenCellGrids() {
        for (int n : new int[]{8, 10}) {
            int[] p = new int[W * H];
            java.util.Arrays.fill(p, BG);
            fill(p, 48, 181, 402, 535, DARK);
            for (int k = 0; k <= n; k++) {
                int x = 48 + Math.round(k * 354f / n);
                int y = 181 + Math.round(k * 354f / n);
                fill(p, x - 1, 181, x + 2, 535, LINE);
                fill(p, 48, y - 1, 402, y + 2, LINE);
            }
            fill(p, 48, 612, 402, 718, DARK);
            fill(p, 116, 654, 129, 667, TILE);
            PixelAnalyzer.Snapshot s = new PixelAnalyzer().analyze(p, W, H);
            assertNotNull(s.board);
            assertEquals(n, s.board.size);
            assertEquals(1, s.pieces.size());
        }
    }
    @Test public void refusesNonGameScreenshot() {
        int[] p = new int[W * H]; java.util.Arrays.fill(p, BG);
        assertFalse(new PixelAnalyzer().analyze(p, W, H).playable());
    }
}
