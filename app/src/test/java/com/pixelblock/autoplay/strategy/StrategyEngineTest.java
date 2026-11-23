package com.pixelblock.autoplay.strategy;

import com.pixelblock.autoplay.vision.PixelAnalyzer;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public final class StrategyEngineTest {
    @Test public void emptyBoardHasLegalMove() {
        int w = 450, h = 750, wood = 0xffdbb98a, dark = 0xff4b301d;
        int[] px = new int[w * h]; Arrays.fill(px, wood);
        fill(px, w, 48, 181, 402, 535, dark);
        for (int k = 0; k <= 9; k++) {
            int x = 48 + k * 39, y = 181 + k * 39;
            fill(px, w, x - 1, 181, x + 2, 535, 0xff785d44);
            fill(px, w, 48, y - 1, 402, y + 2, 0xff785d44);
        }
        fill(px, w, 48, 612, 402, 718, dark);
        fill(px, w, 116, 654, 129, 667, 0xffe8c487);
        PixelAnalyzer.Snapshot snap = new PixelAnalyzer().analyze(px, w, h);
        assertTrue(snap.playable());
        StrategyEngine.Move move = new StrategyEngine().bestMove(snap);
        assertNotNull(move);
        assertEquals(0, move.pieceIndex);
        assertTrue(move.col >= 0 && move.col < 9);
        assertTrue(move.row >= 0 && move.row < 9);
    }
    private static void fill(int[] px, int w, int l, int t, int r, int b, int color) {
        for (int y = t; y < b; y++) for (int x = l; x < r; x++) px[y * w + x] = color;
    }
}
