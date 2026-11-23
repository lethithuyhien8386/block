package com.pixelblock.autoplay.vision;

import com.pixelblock.autoplay.strategy.StrategyEngine;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression against the exact screenshot that v3 mistakenly rejected.
 * Uses a compressed RGB fixture; no java.awt or javax.imageio dependencies. */
public final class RealScreenshotRegressionTest {
    private static class ImagePixels {
        final int width, height;
        final int[] pixels;
        ImagePixels(int w, int h, int[] pixels) { width = w; height = h; this.pixels = pixels; }
    }

    private static ImagePixels fixture() throws IOException {
        InputStream src = RealScreenshotRegressionTest.class.getClassLoader()
                .getResourceAsStream("dense-game-448x793.rgb.gz");
        assertNotNull("Dense-board screenshot fixture missing", src);
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(src))) {
            int w = in.readInt(), h = in.readInt();
            assertEquals(448, w);
            assertEquals(793, h);
            byte[] rgb = new byte[w * h * 3];
            in.readFully(rgb);
            int[] argb = new int[w * h];
            for (int i = 0; i < argb.length; i++) {
                int off = i * 3;
                argb[i] = 0xff000000 | ((rgb[off] & 255) << 16)
                        | ((rgb[off + 1] & 255) << 8) | (rgb[off + 2] & 255);
            }
            return new ImagePixels(w, h, argb);
        }
    }

    @Test public void detectsHighlyOccupiedBoardAndEveryTrayPiece() throws Exception {
        ImagePixels image = fixture();
        PixelAnalyzer.Snapshot snapshot = new PixelAnalyzer().analyze(image.pixels, image.width, image.height);
        assertTrue("Regression: v3 returned 'Chưa thấy bàn cờ': " + snapshot.status,
                snapshot.playable());
        assertEquals(9, snapshot.board.size);
        assertTrue(snapshot.board.left > 30 && snapshot.board.left < 60);
        assertTrue(snapshot.board.right > 385 && snapshot.board.right < 420);
        int occupied = 0;
        for (boolean[] row : snapshot.occupied) for (boolean cell : row) if (cell) occupied++;
        assertEquals(26, occupied);
        assertEquals(3, snapshot.pieces.size());
        assertEquals(4, snapshot.pieces.get(0).tiles.size());
        assertEquals(3, snapshot.pieces.get(1).tiles.size());
        assertEquals(3, snapshot.pieces.get(2).tiles.size());
        assertNotNull("Must find a legal move, not only recognize board",
                new StrategyEngine().bestMove(snapshot));
    }

    @Test public void findsPiecesEvenOnPhoneWithExtraBottomPadding() throws Exception {
        ImagePixels image = fixture();
        int paddedHeight = image.height + 200;
        int[] padded = new int[image.width * paddedHeight];
        Arrays.fill(padded, 0xffe1b57b);
        System.arraycopy(image.pixels, 0, padded, 0, image.pixels.length);
        PixelAnalyzer.Snapshot snapshot = new PixelAnalyzer().analyze(padded, image.width, paddedHeight);
        assertTrue(snapshot.status, snapshot.playable());
        assertEquals(3, snapshot.pieces.size());
    }

    @Test public void recognizesMultipleScreenResolutions() throws Exception {
        ImagePixels im = fixture();
        for (int outputWidth : new int[]{320, 360, 600, 720}) {
            int outputHeight = Math.round(im.height * outputWidth / (float) im.width);
            int[] px = new int[outputWidth * outputHeight];
            // Pure-Java nearest-neighbor sample, like a scaled MediaProjection frame.
            for (int y = 0; y < outputHeight; y++) {
                int sy = Math.min(im.height - 1, y * im.height / outputHeight);
                for (int x = 0; x < outputWidth; x++) {
                    int sx = Math.min(im.width - 1, x * im.width / outputWidth);
                    px[y * outputWidth + x] = im.pixels[sy * im.width + sx];
                }
            }
            PixelAnalyzer.Snapshot result = new PixelAnalyzer().analyze(px, outputWidth, outputHeight);
            assertTrue(outputWidth + "px: " + result.status, result.playable());
            assertEquals(outputWidth + "px board", 9, result.board.size);
            assertEquals(outputWidth + "px pieces", 3, result.pieces.size());
        }
    }
}
