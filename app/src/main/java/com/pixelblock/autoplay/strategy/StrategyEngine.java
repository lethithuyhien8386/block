package com.pixelblock.autoplay.strategy;

import com.pixelblock.autoplay.vision.PixelAnalyzer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Searches all legal drag destinations, keeps promising boards, and looks ahead up to 3 pieces. */
public final class StrategyEngine {
    public static final class Move {
        public final int pieceIndex, col, row, linesCleared;
        public final double score;
        public final double confidence;
        Move(int i, int x, int y, int cleared, double score, double confidence) {
            pieceIndex = i; col = x; row = y; linesCleared = cleared;
            this.score = score; this.confidence = confidence;
        }
    }
    private static final class Node {
        final boolean[][] cells;
        final int usedMask, depth;
        final Move first;
        final double score;
        Node(boolean[][] c, int mask, int d, Move f, double v) {
            cells = c; usedMask = mask; depth = d; first = f; score = v;
        }
    }
    private static final class Result {
        final boolean[][] cells;
        final int cleared;
        Result(boolean[][] c, int lines) { cells = c; cleared = lines; }
    }

    public Move bestMove(PixelAnalyzer.Snapshot state) {
        if (state == null || !state.playable()) return null;
        List<PixelAnalyzer.Piece> pieces = state.pieces;
        int total = pieces.size();
        List<Node> beam = new ArrayList<>();
        beam.add(new Node(copy(state.occupied), 0, 0, null, 0));
        Node best = null;
        for (int depth = 0; depth < total; depth++) {
            List<Node> next = new ArrayList<>();
            for (Node node : beam) {
                for (int index = 0; index < total; index++) {
                    if ((node.usedMask & (1 << index)) != 0) continue;
                    PixelAnalyzer.Piece piece = pieces.get(index);
                    int n = node.cells.length;
                    for (int row = 0; row <= n - piece.rows; row++) {
                        for (int col = 0; col <= n - piece.columns; col++) {
                            if (!canPlace(node.cells, piece, col, row)) continue;
                            Result outcome = apply(node.cells, piece, col, row);
                            double quality = boardQuality(outcome.cells);
                            // Clearing lines is worth much more than just placing a block.
                            double gain = outcome.cleared * 250.0 + piece.tiles.size() * 2.0;
                            double score = node.score + gain + quality * .28;
                            Move first = node.first;
                            if (first == null) first = new Move(index, col, row, outcome.cleared, score,
                                    Math.min(1, state.confidence * piece.confidence));
                            next.add(new Node(outcome.cells, node.usedMask | (1 << index), depth + 1,
                                    first, score));
                        }
                    }
                }
            }
            if (next.isEmpty()) break;
            next.sort(Comparator.comparingDouble((Node n) -> n.score).reversed());
            // Diversity: save states with different boards / used subsets, not just identical branches.
            List<Node> pruned = new ArrayList<>();
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (Node n : next) {
                String key = boardKey(n.cells) + '/' + n.usedMask;
                if (seen.add(key)) pruned.add(n);
                if (pruned.size() >= 16) break;
            }
            beam = pruned;
            for (Node node : beam) {
                double finalScore = node.score + boardQuality(node.cells) * .6 + node.depth * 110;
                if (best == null || node.depth > best.depth || (node.depth == best.depth
                        && finalScore > best.score + boardQuality(best.cells) * .6 + best.depth * 110)) {
                    best = node;
                }
            }
        }
        return best == null ? null : best.first;
    }

    private static boolean canPlace(boolean[][] board, PixelAnalyzer.Piece piece, int x, int y) {
        int n = board.length;
        for (PixelAnalyzer.Tile t : piece.tiles) {
            int xx = x + t.col, yy = y + t.row;
            if (xx < 0 || yy < 0 || xx >= n || yy >= n || board[yy][xx]) return false;
        }
        return true;
    }
    private static Result apply(boolean[][] input, PixelAnalyzer.Piece p, int x, int y) {
        boolean[][] board = copy(input);
        for (PixelAnalyzer.Tile t : p.tiles) board[y + t.row][x + t.col] = true;
        int n = board.length;
        boolean[] clearR = new boolean[n], clearC = new boolean[n];
        int lines = 0;
        for (int row = 0; row < n; row++) {
            boolean filled = true;
            for (int col = 0; col < n; col++) if (!board[row][col]) { filled = false; break; }
            if (filled) { clearR[row] = true; lines++; }
        }
        for (int col = 0; col < n; col++) {
            boolean filled = true;
            for (int row = 0; row < n; row++) if (!board[row][col]) { filled = false; break; }
            if (filled) { clearC[col] = true; lines++; }
        }
        for (int row = 0; row < n; row++) for (int col = 0; col < n; col++) {
            if (clearR[row] || clearC[col]) board[row][col] = false;
        }
        return new Result(board, lines);
    }
    private static double boardQuality(boolean[][] board) {
        int n = board.length;
        int free = 0, singles = 0, maxRow = 0, maxCol = 0, almost = 0;
        for (int row = 0; row < n; row++) {
            int filled = 0;
            for (int col = 0; col < n; col++) if (board[row][col]) filled++; else free++;
            if (filled == n - 1 || filled == n - 2) almost++;
            maxRow = Math.max(maxRow, filled);
        }
        for (int col = 0; col < n; col++) {
            int filled = 0;
            for (int row = 0; row < n; row++) if (board[row][col]) filled++;
            if (filled == n - 1 || filled == n - 2) almost++;
            maxCol = Math.max(maxCol, filled);
        }
        for (int row = 0; row < n; row++) for (int col = 0; col < n; col++) if (!board[row][col]) {
            int neighbors = 0;
            if (row > 0 && !board[row-1][col]) neighbors++;
            if (row+1 < n && !board[row+1][col]) neighbors++;
            if (col > 0 && !board[row][col-1]) neighbors++;
            if (col+1 < n && !board[row][col+1]) neighbors++;
            if (neighbors == 0) singles++;
        }
        // Empty space is valuable; nearly finished rows/columns are valuable too.
        return free * 1.8 + almost * 9.0 + (maxRow + maxCol) * 1.0 - singles * 15.0;
    }
    private static boolean[][] copy(boolean[][] arr) {
        boolean[][] out = new boolean[arr.length][];
        for (int r = 0; r < arr.length; r++) out[r] = Arrays.copyOf(arr[r], arr[r].length);
        return out;
    }
    private static String boardKey(boolean[][] board) {
        StringBuilder sb = new StringBuilder(board.length * board.length);
        for (boolean[] row : board) for (boolean c : row) sb.append(c ? '1' : '0');
        return sb.toString();
    }
}
