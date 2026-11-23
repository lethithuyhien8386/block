package com.pixelblock.autoplay.strategy;

import com.pixelblock.autoplay.vision.PixelAnalyzer;

/**
 * Pixel-based post-drag verification, shared by Android and JVM tests.
 * A changed floating label / moving highlight is NOT proof that a block landed.
 * We require the exact predicted board after simultaneous row/column clears.
 */
public final class MoveVerifier {
    private MoveVerifier() { }

    public static final class Prediction {
        public final boolean[][] expected;
        public final int expectedRemainingPieces;
        public final int linesCleared;
        public final String beforeFingerprint;
        private Prediction(boolean[][] expected, int left, int lines, String fingerprint) {
            this.expected = expected;
            this.expectedRemainingPieces = left;
            this.linesCleared = lines;
            this.beforeFingerprint = fingerprint;
        }
    }

    public static Prediction predict(PixelAnalyzer.Snapshot before, StrategyEngine.Move move) {
        if (before == null || !before.playable() || move == null || move.pieceIndex < 0
                || move.pieceIndex >= before.pieces.size()) return null;
        int n = before.occupied.length;
        PixelAnalyzer.Piece p = before.pieces.get(move.pieceIndex);
        if (move.row < 0 || move.col < 0 || move.row + p.rows > n || move.col + p.columns > n) return null;
        boolean[][] next = new boolean[n][n];
        for (int r = 0; r < n; r++) System.arraycopy(before.occupied[r], 0, next[r], 0, n);
        for (PixelAnalyzer.Tile t : p.tiles) {
            int x = move.col + t.col, y = move.row + t.row;
            if (x < 0 || x >= n || y < 0 || y >= n || next[y][x]) return null;
            next[y][x] = true;
        }
        boolean[] rows = new boolean[n], cols = new boolean[n];
        int lines = 0;
        for (int r = 0; r < n; r++) {
            boolean complete = true;
            for (int c = 0; c < n; c++) complete &= next[r][c];
            rows[r] = complete;
            if (complete) lines++;
        }
        for (int c = 0; c < n; c++) {
            boolean complete = true;
            for (int r = 0; r < n; r++) complete &= next[r][c];
            cols[c] = complete;
            if (complete) lines++;
        }
        for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) {
            if (rows[r] || cols[c]) next[r][c] = false;
        }
        return new Prediction(next, before.pieces.size() - 1, lines, before.fingerprint());
    }

    public static boolean matches(Prediction prediction, PixelAnalyzer.Snapshot after) {
        if (prediction == null || after == null || !after.playable()) return false;
        if (after.occupied.length != prediction.expected.length) return false;
        int n = prediction.expected.length;
        for (int r = 0; r < n; r++) {
            if (after.occupied[r].length != n) return false;
            for (int c = 0; c < n; c++) {
                if (after.occupied[r][c] != prediction.expected[r][c]) return false;
            }
        }
        // The tray refills immediately after the third block, so do not demand
        // zero visible pieces in that case. For earlier blocks demand one fewer.
        if (prediction.expectedRemainingPieces > 0 && after.pieces.size() != prediction.expectedRemainingPieces)
            return false;
        return !prediction.beforeFingerprint.equals(after.fingerprint());
    }
}
