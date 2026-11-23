package com.pixelblock.autoplay.strategy;

import com.pixelblock.autoplay.vision.PixelAnalyzer;
import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class PlannerRulesTest {
    private static final PixelAnalyzer.BoardBox BOARD = new PixelAnalyzer.BoardBox(40, 180, 400, 540, 9);

    private PixelAnalyzer.Piece piece(int[][] tiles) {
        int cols = 0, rows = 0;
        java.util.ArrayList<PixelAnalyzer.Tile> elements = new java.util.ArrayList<>();
        for (int[] rc : tiles) {
            elements.add(new PixelAnalyzer.Tile(rc[0], rc[1]));
            cols = Math.max(cols, rc[0] + 1);
            rows = Math.max(rows, rc[1] + 1);
        }
        return new PixelAnalyzer.Piece(elements, 120, 665, 0, 0, cols, rows, .99);
    }
    private PixelAnalyzer.Snapshot snapshot(boolean[][] board, PixelAnalyzer.Piece... pieces) {
        return new PixelAnalyzer.Snapshot(BOARD, board, Arrays.asList(pieces), .98, "fixture");
    }

    @Test public void emptyBoardSearchesBottomFirst() {
        PixelAnalyzer.Piece single = piece(new int[][]{{0,0}});
        StrategyEngine.Move best = new StrategyEngine().bestMove(snapshot(new boolean[9][9], single));
        assertNotNull(best);
        assertTrue("bottom-first expected", best.row >= 6);
    }

    @Test public void picksLaterTrayPieceWhenFirstCannotFit() {
        boolean[][] board = new boolean[9][9];
        for (boolean[] row : board) Arrays.fill(row, true);
        board[7][7] = false; board[7][8] = false;
        board[8][7] = false; board[8][8] = false;
        PixelAnalyzer.Piece tooBig = piece(new int[][]{{0,0},{1,0},{2,0},{0,1},{1,1},{2,1},{0,2},{1,2},{2,2}});
        PixelAnalyzer.Piece small = piece(new int[][]{{0,0}});
        StrategyEngine.Move best = new StrategyEngine().bestMove(snapshot(board, tooBig, small));
        assertNotNull(best);
        assertEquals(1, best.pieceIndex);
    }

    @Test public void prefersImmediateCompletedLineOverBottomBonus() {
        boolean[][] board = new boolean[9][9];
        Arrays.fill(board[0], true);
        board[0][8] = false;
        StrategyEngine.Move best = new StrategyEngine().bestMove(snapshot(board, piece(new int[][]{{0,0}})));
        assertNotNull(best);
        assertEquals(0, best.row);
        assertEquals(8, best.col);
        assertEquals(1, best.linesCleared);
    }

    @Test public void avoidsNewSeparatedEmptyRegionsWithoutLineClear() {
        boolean[][] board = new boolean[9][9];
        // Wall almost divides the board; filling the opening would isolate spaces.
        for (int row = 0; row < 9; row++) if (row != 5) board[row][4] = true;
        PixelAnalyzer.Piece single = piece(new int[][]{{0,0}});
        StrategyEngine.Move best = new StrategyEngine().bestMove(snapshot(board, single));
        assertNotNull(best);
        if (best.linesCleared == 0) {
            boolean[][] after = new boolean[9][9];
            for (int y = 0; y < 9; y++) after[y] = board[y].clone();
            after[best.row][best.col] = true;
            assertTrue("must not seal an empty region",
                    StrategyEngine.countEmptyRegions(after) <= StrategyEngine.countEmptyRegions(board));
        }
    }

    @Test public void allThreeRecognizedShapesAreSupportedInAnyOrder() {
        boolean[][] board = new boolean[9][9];
        board[5][1] = true; board[7][6] = true;
        board[8][3] = true; board[8][4] = true; board[8][7] = true;
        PixelAnalyzer.Piece first = piece(new int[][]{{1,0},{0,1},{1,1},{0,2}});
        PixelAnalyzer.Piece second = piece(new int[][]{{1,0},{2,0},{0,1},{1,1}});
        PixelAnalyzer.Piece third = piece(new int[][]{{0,0},{0,1},{1,1},{2,1}});
        StrategyEngine.Move best = new StrategyEngine().bestMove(snapshot(board, first, second, third));
        assertNotNull(best);
        assertTrue(best.pieceIndex >= 0 && best.pieceIndex <= 2);
        assertTrue(best.row >= 0 && best.col >= 0);
    }
}
