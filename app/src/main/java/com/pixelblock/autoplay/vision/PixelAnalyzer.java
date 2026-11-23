package com.pixelblock.autoplay.vision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Pure Java vision core: no java.awt / javax.imageio / Android Bitmap dependency. */
public final class PixelAnalyzer {
    private int[] data;
    private int width, height;
    private BoardBox cached;
    private int cacheAge;

    public static final class BoardBox {
        public final int left, top, right, bottom, size;
        public BoardBox(int left, int top, int right, int bottom, int size) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
            this.size = size;
        }
        public double pitch() { return (right - left) / (double) size; }
        public float cx(double col) { return (float) (left + (col + 0.5) * pitch()); }
        public float cy(double row) { return (float) (top + (row + 0.5) * (bottom - top) / (double) size); }
    }

    public static final class Tile {
        public final int col, row;
        public Tile(int col, int row) { this.col = col; this.row = row; }
    }
    public static final class Piece {
        public final List<Tile> tiles;
        public final float touchX, touchY;
        public final int touchCol, touchRow;
        public final int columns, rows;
        public final double confidence;
        public Piece(List<Tile> tiles, float x, float y, int col, int row, int columns, int rows, double confidence) {
            this.tiles = Collections.unmodifiableList(tiles);
            this.touchX = x; this.touchY = y; this.touchCol = col; this.touchRow = row;
            this.columns = columns; this.rows = rows; this.confidence = confidence;
        }
    }
    public static final class Snapshot {
        public final BoardBox board;
        public final boolean[][] occupied; // [row][column]
        public final List<Piece> pieces;
        public final double confidence;
        public final String status;
        public Snapshot(BoardBox b, boolean[][] o, List<Piece> p, double c, String s) {
            board = b; occupied = o; pieces = p; confidence = c; status = s;
        }
        public boolean playable() { return board != null && confidence >= 0.57 && !pieces.isEmpty(); }
        public String fingerprint() {
            if (board == null) return "no-board";
            StringBuilder s = new StringBuilder();
            for (boolean[] row : occupied) for (boolean cell : row) s.append(cell ? '1' : '0');
            s.append(':');
            for (Piece p : pieces) {
                s.append(Math.round(p.touchX / 3f)).append(',').append(Math.round(p.touchY / 3f)).append('=');
                for (Tile t : p.tiles) s.append(t.col).append('.').append(t.row).append(';');
                s.append('|');
            }
            return s.toString();
        }
    }

    private static final class RowRun {
        final int y, left, right;
        RowRun(int y, int l, int r) { this.y = y; left = l; right = r; }
        int length() { return right - left; }
    }
    private static final class Component {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, area = 0;
        float cx() { return (minX + maxX) / 2f; }
        float cy() { return (minY + maxY) / 2f; }
        int w() { return maxX - minX + 1; }
        int h() { return maxY - minY + 1; }
    }

    public synchronized Snapshot analyze(int[] argb, int w, int h) {
        if (argb == null || w < 200 || h < 320 || argb.length < w * h) {
            return new Snapshot(null, null, Collections.emptyList(), 0, "Ảnh không hợp lệ");
        }
        data = argb; width = w; height = h;
        BoardBox board = findBoard();
        if (board == null) {
            return new Snapshot(null, null, Collections.emptyList(), 0, "Chưa thấy bàn cờ tối hình vuông");
        }
        int n = board.size;
        int[] values = new int[n * n];
        int k = 0;
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                int x = Math.round(board.cx(col)), y = Math.round(board.cy(row));
                values[k++] = neighborhoodLuma(x, y, Math.max(1, (int) board.pitch() / 10));
            }
        }
        int[] sorted = values.clone(); Arrays.sort(sorted);
        int background = sorted[sorted.length / 3];
        boolean[][] occupied = new boolean[n][n];
        // Detect colored blocks against the *observed* wooden empty-cell background.
        for (int row = 0; row < n; row++) for (int col = 0; col < n; col++) {
            int v = values[row * n + col];
            occupied[row][col] = v > Math.max(135, background + 52);
        }
        List<Piece> pieces = findPieces(board);
        double gridContrast = gridScore(board.left, board.top, board.right, board.bottom, n);
        double gridConfidence = clamp((gridContrast - 5) / 15.0, 0, 1);
        double shapeConfidence = 0;
        for (Piece p : pieces) shapeConfidence = Math.max(shapeConfidence, p.confidence);
        double confidence = 0.57 + gridConfidence * 0.23 + (pieces.isEmpty() ? 0 : shapeConfidence * 0.20);
        if (gridContrast < 7) confidence *= 0.65;
        return new Snapshot(board, occupied, pieces, confidence,
                pieces.isEmpty() ? "Chưa tìm thấy khối trong khay" : "Đã nhận " + n + "×" + n + ", " + pieces.size() + " khối");
    }

    private BoardBox findBoard() {
        if (cached != null && cacheAge < 50) {
            cacheAge++;
            if (validateCached(cached)) return cached;
            cached = null;
        }
        int step = Math.max(2, height / 360);
        List<RowRun> lines = new ArrayList<>();
        int minWidth = Math.max(130, (int) (Math.min(width, height) * 0.52));
        int maxWidth = (int) (width * 0.97);
        for (int y = (int) (height * 0.12); y < (int) (height * 0.86); y += step) {
            // Bridge a colored piece inside a dark row instead of splitting the board.
            int bestL = -1, bestR = -1, bestLen = 0;
            int st = -1, lastDark = -1, darkPixels = 0;
            int maxBridge = Math.max(8, width / 8);
            for (int x = 0; x <= width; x++) {
                boolean dark = x < width && luma(data[y * width + x]) < 146;
                if (dark) {
                    if (st < 0) { st = x; darkPixels = 0; }
                    lastDark = x; darkPixels++;
                }
                if (st >= 0 && (x == width || (!dark && x - lastDark > maxBridge))) {
                    int len = lastDark - st + 1;
                    if (len > bestLen && darkPixels >= len * .53) {
                        bestL = st; bestR = lastDark + 1; bestLen = len;
                    }
                    st = -1; lastDark = -1; darkPixels = 0;
                }
            }
            if (bestLen >= minWidth && bestLen <= maxWidth) lines.add(new RowRun(y, bestL, bestR));
        }
        // Group consecutive scan lines with similarly positioned board edges.
        List<RowRun> best = Collections.emptyList();
        List<RowRun> group = new ArrayList<>();
        for (RowRun r : lines) {
            if (!group.isEmpty()) {
                RowRun prev = group.get(group.size() - 1);
                if (r.y - prev.y > 3 * step || Math.abs(r.left - prev.left) > width * .10
                        || Math.abs(r.right - prev.right) > width * .10) {
                    if (group.size() > best.size()) best = group;
                    group = new ArrayList<>();
                }
            }
            group.add(r);
        }
        if (group.size() > best.size()) best = group;
        if (best.size() < 10) return null;
        int[] ls = new int[best.size()], rs = new int[best.size()];
        for (int i = 0; i < best.size(); i++) { ls[i] = best.get(i).left; rs[i] = best.get(i).right; }
        Arrays.sort(ls); Arrays.sort(rs);
        int l = ls[ls.length / 2], r = rs[rs.length / 2];
        int t = Math.max(0, best.get(0).y - step / 2);
        int b = Math.min(height - 1, best.get(best.size() - 1).y + step + 1);
        // Remove stray thin dark lines at screen edges (otherwise bridging could merge
        // x=0 with the real board at x=48, shifting all gesture targets).
        int[] refined = refineHorizontalEdges(t, b, minWidth);
        if (refined != null) { l = refined[0]; r = refined[1]; }
        double ratio = (b - t) / (double) (r - l);
        if (ratio < .76 || ratio > 1.23) return null;
        int size = bestGrid(l, t, r, b);
        if (size < 0) return null;
        cached = new BoardBox(l, t, r, b, size); cacheAge = 0;
        return cached;
    }


    private int[] refineHorizontalEdges(int top, int bottom, int minWidth) {
        int bestL = -1, bestR = -1, st = -1;
        int samples = 0;
        int stepY = Math.max(3, (bottom - top) / 45);
        for (int y = top + 4; y < bottom - 4; y += stepY) samples++;
        if (samples < 5) return null;
        for (int x = 0; x <= width; x++) {
            int hits = 0;
            if (x < width) for (int y = top + 4; y < bottom - 4; y += stepY) {
                if (luma(data[y * width + x]) < 146) hits++;
            }
            boolean inside = x < width && hits >= samples * .67;
            if (inside && st < 0) st = x;
            if (!inside && st >= 0) {
                if (x - st > bestR - bestL) { bestL = st; bestR = x; }
                st = -1;
            }
        }
        return bestR - bestL >= minWidth ? new int[]{bestL, bestR} : null;
    }

    private boolean validateCached(BoardBox box) {
        if (box.right >= width || box.bottom >= height || box.left < 0 || box.top < 0) return false;
        // Empty wooden cells remain dark even when some brightly colored pieces occupy others.
        int countDark = 0, total = 0;
        for (int row = 0; row < box.size; row++) for (int col = 0; col < box.size; col++) {
            int x = Math.round(box.cx(col)), y = Math.round(box.cy(row));
            if (luma(data[y * width + x]) < 156) countDark++;
            total++;
        }
        return countDark >= total * 0.33;
    }

    private int bestGrid(int l, int t, int r, int b) {
        int best = -1; double score = 7;
        for (int n : new int[]{9, 8, 10}) {
            double s = gridScore(l, t, r, b, n);
            if (s > score) { score = s; best = n; }
        }
        return best;
    }

    private double gridScore(int l, int t, int r, int b, int n) {
        double sum = 0; int count = 0;
        double dx = (r - l) / (double) n, dy = (b - t) / (double) n;
        for (int i = 1; i < n; i++) for (int j = 0; j < n; j += 2) {
            int y = (int) (t + (j + 0.5) * dy);
            int xLine = (int) (l + i * dx);
            int xCell = (int) (l + (i - 0.5) * dx);
            sum += neighborhoodLuma(xLine, y, 1) - neighborhoodLuma(xCell, y, 2);
            int x = (int) (l + (j + .5) * dx);
            int yLine = (int) (t + i * dy);
            int yCell = (int) (t + (i - .5) * dy);
            if (x >= 0 && x < width) {
                sum += neighborhoodLuma(x, yLine, 1) - neighborhoodLuma(x, yCell, 2);
                count++;
            }
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private List<Piece> findPieces(BoardBox board) {
        // Avoid booster buttons just under the board: scan the *lower tray*.
        int startY = Math.min(height - 1, Math.max(board.bottom + (int) (height * .11), (int) (height * .72)));
        int endY = height - Math.max(2, height / 140);
        if (startY >= endY - 10) return Collections.emptyList();
        int trayH = endY - startY;
        byte[] mask = new byte[width * trayH];
        for (int y = startY; y < endY; y++) for (int x = 0; x < width; x++) {
            int color = data[y * width + x];
            int red = (color >>> 16) & 255, green = (color >>> 8) & 255, blue = color & 255;
            if (red > 174 && green > 137 && blue < 226 && red - green > 10 && green - blue > 17) {
                mask[(y - startY) * width + x] = 1;
            }
        }
        List<Component> tiles = new ArrayList<>();
        int[] queue = new int[width * trayH];
        for (int y = 0; y < trayH; y++) for (int x = 0; x < width; x++) {
            int at = y * width + x;
            if (mask[at] != 1) continue;
            int head = 0, tail = 0;
            queue[tail++] = at; mask[at] = 2;
            Component c = new Component();
            while (head < tail) {
                int q = queue[head++], px = q % width, py = q / width;
                c.area++; c.minX = Math.min(c.minX, px); c.maxX = Math.max(c.maxX, px);
                c.minY = Math.min(c.minY, py + startY); c.maxY = Math.max(c.maxY, py + startY);
                if (px > 0 && mask[q - 1] == 1) { mask[q - 1] = 2; queue[tail++] = q - 1; }
                if (px + 1 < width && mask[q + 1] == 1) { mask[q + 1] = 2; queue[tail++] = q + 1; }
                if (py > 0 && mask[q - width] == 1) { mask[q - width] = 2; queue[tail++] = q - width; }
                if (py + 1 < trayH && mask[q + width] == 1) { mask[q + width] = 2; queue[tail++] = q + width; }
            }
            int maxTile = (int) (board.pitch() * .85);
            if (c.w() >= 4 && c.h() >= 4 && c.w() <= maxTile && c.h() <= maxTile
                    && c.area >= 22 && c.area >= c.w() * c.h() * .57
                    && Math.max(c.w(), c.h()) / (double) Math.min(c.w(), c.h()) < 1.8) {
                tiles.add(c);
            }
        }
        if (tiles.isEmpty()) return Collections.emptyList();
        // Adjacent squares in a piece are grouped by close centers (including diagonal corners).
        double medianSize = 0;
        List<Integer> sizes = new ArrayList<>();
        for (Component tile : tiles) sizes.add(Math.max(tile.w(), tile.h()));
        Collections.sort(sizes);
        medianSize = sizes.get(sizes.size() / 2);
        double pitch = medianSize * 1.24;
        double maxNeighbor = Math.max(pitch * 1.35, board.pitch() * .49);
        boolean[] visited = new boolean[tiles.size()];
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < tiles.size(); i++) {
            if (visited[i]) continue;
            ArrayList<Component> connected = new ArrayList<>();
            int[] bfs = new int[tiles.size()]; int head = 0, tail = 0;
            bfs[tail++] = i; visited[i] = true;
            while (head < tail) {
                Component a = tiles.get(bfs[head++]); connected.add(a);
                for (int j = 0; j < tiles.size(); j++) if (!visited[j]) {
                    Component b = tiles.get(j);
                    double xd = Math.abs(a.cx() - b.cx()), yd = Math.abs(a.cy() - b.cy());
                    if (xd <= maxNeighbor && yd <= maxNeighbor && xd + yd > 1) {
                        visited[j] = true; bfs[tail++] = j;
                    }
                }
            }
            if (connected.size() > 25) continue;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -1, maxY = -1;
            for (Component c : connected) {
                minX = Math.min(minX, c.cx()); minY = Math.min(minY, c.cy());
                maxX = Math.max(maxX, c.cx()); maxY = Math.max(maxY, c.cy());
            }
            // Estimate the distance between neighboring rendered mini-tiles from their centers.
            List<Double> diffs = new ArrayList<>();
            for (int a = 0; a < connected.size(); a++) for (int b = a + 1; b < connected.size(); b++) {
                double xd = Math.abs(connected.get(a).cx() - connected.get(b).cx());
                double yd = Math.abs(connected.get(a).cy() - connected.get(b).cy());
                if (xd > medianSize * .75 && xd < medianSize * 1.9) diffs.add(xd);
                if (yd > medianSize * .75 && yd < medianSize * 1.9) diffs.add(yd);
            }
            if (!diffs.isEmpty()) { Collections.sort(diffs); pitch = diffs.get(diffs.size() / 2); }
            else pitch = medianSize * 1.24;
            ArrayList<Tile> shape = new ArrayList<>();
            boolean distinct = true;
            int cols = 0, rows = 0;
            for (Component c : connected) {
                int col = Math.max(0, (int) Math.round((c.cx() - minX) / pitch));
                int row = Math.max(0, (int) Math.round((c.cy() - minY) / pitch));
                for (Tile existing : shape) if (existing.col == col && existing.row == row) distinct = false;
                shape.add(new Tile(col, row)); cols = Math.max(cols, col + 1); rows = Math.max(rows, row + 1);
            }
            if (!distinct || cols > 6 || rows > 6) continue;
            // Pick an actual rendered square as finger anchor, not background between squares.
            Component anchor = connected.get(0);
            float mx = (minX + maxX) / 2, my = (minY + maxY) / 2;
            for (Component c : connected) if (dist2(c.cx(), c.cy(), mx, my) < dist2(anchor.cx(), anchor.cy(), mx, my)) anchor = c;
            int ac = Math.max(0, (int) Math.round((anchor.cx() - minX) / pitch));
            int ar = Math.max(0, (int) Math.round((anchor.cy() - minY) / pitch));
            double solidity = 0;
            for (Component c : connected) solidity += c.area / (double) (c.w() * c.h());
            solidity /= connected.size();
            double confidence = clamp(.60 + .35 * solidity, 0, 1);
            shape.sort((a, b) -> a.row != b.row ? Integer.compare(a.row, b.row) : Integer.compare(a.col, b.col));
            pieces.add(new Piece(shape, anchor.cx(), anchor.cy(), ac, ar, cols, rows, confidence));
        }
        pieces.sort(Comparator.comparingDouble(p -> p.touchX));
        // A real game offers at most three tray pieces. Reject screenshots full of false positives.
        if (pieces.size() > 3) return Collections.emptyList();
        return Collections.unmodifiableList(pieces);
    }

    private static double dist2(float x, float y, float tx, float ty) {
        return (x - tx) * (x - tx) + (y - ty) * (y - ty);
    }
    private int neighborhoodLuma(int x, int y, int radius) {
        int sum = 0, count = 0;
        for (int yy = Math.max(0, y - radius); yy <= Math.min(height - 1, y + radius); yy++) {
            for (int xx = Math.max(0, x - radius); xx <= Math.min(width - 1, x + radius); xx++) {
                sum += luma(data[yy * width + xx]); count++;
            }
        }
        return count == 0 ? 0 : sum / count;
    }
    private static int luma(int color) {
        return (((color >>> 16) & 255) * 299 + ((color >>> 8) & 255) * 587 + (color & 255) * 114) / 1000;
    }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
