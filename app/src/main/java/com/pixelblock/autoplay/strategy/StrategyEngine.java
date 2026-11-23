package com.pixelblock.autoplay.strategy;

import com.pixelblock.autoplay.vision.PixelAnalyzer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Offline, deterministic planning over all visible tray pieces in any order.
 * Rules: prefer clearing lines; search bottom-up; avoid splitting empty space
 * into isolated pockets unless the move completes a row or column (which clears).
 */
public final class StrategyEngine {
    private static final int BEAM_WIDTH = 32;
    public static final class Move {
        public final int pieceIndex, col, row, linesCleared;
        public final double score, confidence;
        Move(int index, int x, int y, int cleared, double score, double confidence) {
            pieceIndex = index; col = x; row = y;
            linesCleared = cleared; this.score = score; this.confidence = confidence;
        }
    }
    private static final class Node {
        final boolean[][] cells;
        final int mask, depth;
        final int cleared;
        final Move first;
        final double score;
        Node(boolean[][] board, int mask, int depth, int cleared, Move first, double score) {
            this.cells = board; this.mask = mask; this.depth = depth;
            this.cleared = cleared; this.first = first; this.score = score;
        }
        double ranking() { return score + boardQuality(cells) * .48; }
    }
    private static final class Result {
        final boolean[][] cells;
        final int cleared;
        Result(boolean[][] cells, int cleared) { this.cells = cells; this.cleared = cleared; }
    }
    private static final class EmptyAnalysis {
        int regions, isolatedTiles, deadEnds, smallRegions;
    }

    /** The first step of the best sequence using as many of the 3 shown pieces as possible. */
    public Move bestMove(PixelAnalyzer.Snapshot state) {
        if (state == null || !state.playable()) return null;
        List<PixelAnalyzer.Piece> pieces = state.pieces;
        int count = Math.min(3, pieces.size());
        List<Node> beam = new ArrayList<>();
        beam.add(new Node(copy(state.occupied), 0, 0, 0, null, 0));
        Node best = null;
        for (int depth = 0; depth < count; depth++) {
            List<Node> candidates = new ArrayList<>();
            for (Node node : beam) {
                int n = node.cells.length;
                int beforeRegions = countEmptyRegions(node.cells);
                for (int p = 0; p < count; p++) {
                    if ((node.mask & (1 << p)) != 0) continue;
                    PixelAnalyzer.Piece piece = pieces.get(p);
                    int remainingMask = node.mask | (1 << p);
                    int smallestRemaining = Integer.MAX_VALUE;
                    for (int j = 0; j < count; j++) {
                        if ((remainingMask & (1 << j)) == 0) {
                            smallestRemaining = Math.min(smallestRemaining, pieces.get(j).tiles.size());
                        }
                    }
                    if (smallestRemaining == Integer.MAX_VALUE) smallestRemaining = 2;
                    // Iteration order is bottom-to-top by design; no preference for tray index.
                    for (int row = n - piece.rows; row >= 0; row--) {
                        for (int col = 0; col <= n - piece.columns; col++) {
                            if (!canPlace(node.cells, piece, col, row)) continue;
                            Result placed = apply(node.cells, piece, col, row);
                            EmptyAnalysis spaces = inspectEmpty(placed.cells, smallestRemaining);
                            // Strict user rule: never seal off a previously connected space
                            // without clearing a line. Clearing removes the blocking cells.
                            if (placed.cleared == 0 && spaces.regions > beforeRegions) continue;
                            double value = node.score
                                    + 300.0 * placed.cleared
                                    + 3.0 * piece.tiles.size()
                                    + 3.0 * row
                                    - 40.0 * spaces.smallRegions
                                    - 8.0 * spaces.deadEnds
                                    - 14.0 * Math.max(0, spaces.regions - 1);
                            Move first = node.first;
                            if (first == null) first = new Move(p, col, row, placed.cleared, value,
                                    Math.min(1, state.confidence * piece.confidence));
                            candidates.add(new Node(placed.cells, remainingMask, node.depth + 1,
                                    node.cleared + placed.cleared, first, value));
                        }
                    }
                }
            }
            if (candidates.isEmpty()) break;
            candidates.sort(Comparator.comparingDouble(Node::ranking).reversed());
            HashSet<String> seen = new HashSet<>();
            List<Node> next = new ArrayList<>();
            for (Node candidate : candidates) {
                if (seen.add(key(candidate.cells) + ":" + candidate.mask)) next.add(candidate);
                if (next.size() >= BEAM_WIDTH) break;
            }
            beam = next;
            for (Node candidate : next) {
                // Placing all visible pieces takes priority over greedy single moves.
                if (best == null || candidate.depth > best.depth
                        || (candidate.depth == best.depth && candidate.ranking() > best.ranking())) {
                    best = candidate;
                }
            }
        }
        return best == null ? null : best.first;
    }

    static boolean canPlace(boolean[][] board, PixelAnalyzer.Piece p, int col, int row) {
        int n = board.length;
        for (PixelAnalyzer.Tile tile : p.tiles) {
            int x = col + tile.col, y = row + tile.row;
            if (x < 0 || y < 0 || x >= n || y >= n || board[y][x]) return false;
        }
        return true;
    }
    static Result apply(boolean[][] input, PixelAnalyzer.Piece piece, int col, int row) {
        boolean[][] board = copy(input);
        for (PixelAnalyzer.Tile tile : piece.tiles) board[row + tile.row][col + tile.col] = true;
        int n = board.length, cleared = 0;
        boolean[] clearRows = new boolean[n], clearCols = new boolean[n];
        for (int y = 0; y < n; y++) {
            boolean all = true;
            for (int x = 0; x < n; x++) if (!board[y][x]) { all = false; break; }
            if (all) { clearRows[y] = true; cleared++; }
        }
        for (int x = 0; x < n; x++) {
            boolean all = true;
            for (int y = 0; y < n; y++) if (!board[y][x]) { all = false; break; }
            if (all) { clearCols[x] = true; cleared++; }
        }
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            if (clearRows[y] || clearCols[x]) board[y][x] = false;
        }
        return new Result(board, cleared);
    }

    static int countEmptyRegions(boolean[][] board) { return inspectEmpty(board, 1).regions; }

    // 4-connected flood fill: board boundaries and occupied cells are hard walls.
    private static EmptyAnalysis inspectEmpty(boolean[][] board, int minimumArea) {
        int n = board.length;
        boolean[][] seen = new boolean[n][n];
        EmptyAnalysis report = new EmptyAnalysis();
        int[] queue = new int[n * n];
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            if (board[y][x] || seen[y][x]) continue;
            report.regions++;
            int start = 0, end = 0, area = 0;
            queue[end++] = y * n + x; seen[y][x] = true;
            while (start < end) {
                int pos = queue[start++], r = pos / n, c = pos % n, freeNeighbors = 0;
                area++;
                if (r > 0 && !board[r - 1][c]) {
                    freeNeighbors++;
                    if (!seen[r - 1][c]) { seen[r - 1][c] = true; queue[end++] = (r - 1) * n + c; }
                }
                if (r + 1 < n && !board[r + 1][c]) {
                    freeNeighbors++;
                    if (!seen[r + 1][c]) { seen[r + 1][c] = true; queue[end++] = (r + 1) * n + c; }
                }
                if (c > 0 && !board[r][c - 1]) {
                    freeNeighbors++;
                    if (!seen[r][c - 1]) { seen[r][c - 1] = true; queue[end++] = r * n + c - 1; }
                }
                if (c + 1 < n && !board[r][c + 1]) {
                    freeNeighbors++;
                    if (!seen[r][c + 1]) { seen[r][c + 1] = true; queue[end++] = r * n + c + 1; }
                }
                if (freeNeighbors <= 1) report.deadEnds++;
            }
            if (area == 1) report.isolatedTiles++;
            if (area < minimumArea) report.smallRegions++;
        }
        return report;
    }

    private static double boardQuality(boolean[][] board) {
        int n = board.length, free = 0, almost = 0;
        for (int i = 0; i < n; i++) {
            int row = 0, col = 0;
            for (int j = 0; j < n; j++) {
                if (!board[i][j]) free++; else row++;
                if (board[j][i]) col++;
            }
            if (row >= n - 2 && row < n) almost++;
            if (col >= n - 2 && col < n) almost++;
        }
        EmptyAnalysis empty = inspectEmpty(board, 1);
        return free * 1.5 + almost * 10.0 - empty.deadEnds * 10.0
                - Math.max(0, empty.regions - 1) * 42.0;
    }
    private static boolean[][] copy(boolean[][] b) {
        boolean[][] result = new boolean[b.length][];
        for (int i = 0; i < b.length; i++) result[i] = Arrays.copyOf(b[i], b[i].length);
        return result;
    }
    private static String key(boolean[][] cells) {
        StringBuilder s = new StringBuilder(cells.length * cells.length);
        for (boolean[] row : cells) for (boolean b : row) s.append(b ? '1' : '0');
        return s.toString();
    }
}
