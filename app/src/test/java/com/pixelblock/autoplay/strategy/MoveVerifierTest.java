package com.pixelblock.autoplay.strategy;

import com.pixelblock.autoplay.vision.PixelAnalyzer;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class MoveVerifierTest {
    private PixelAnalyzer.Piece piece(int... xy) {
        List<PixelAnalyzer.Tile> tiles = new ArrayList<>();
        int w=0,h=0;
        for(int i=0;i<xy.length;i+=2) {
            tiles.add(new PixelAnalyzer.Tile(xy[i],xy[i+1]));
            w=Math.max(w,xy[i]+1);h=Math.max(h,xy[i+1]+1);
        }
        return new PixelAnalyzer.Piece(tiles, 105, 660, 0, 0, w, h, .98);
    }
    private PixelAnalyzer.Snapshot snap(boolean[][] board, PixelAnalyzer.Piece... shapes) {
        return new PixelAnalyzer.Snapshot(
                new PixelAnalyzer.BoardBox(46, 180, 403, 537, board.length),
                board, Arrays.asList(shapes), .99, "ready");
    }
    @Test public void verifiesExpectedGridAndRejectsUnchangedOrUnrelatedImage() {
        boolean[][] before=new boolean[9][9];
        PixelAnalyzer.Piece a=piece(0,0,1,0),b=piece(0,0);
        PixelAnalyzer.Snapshot old=snap(before,a,b);
        StrategyEngine.Move move=new StrategyEngine.Move(0,3,7,0,1,.9);
        MoveVerifier.Prediction p=MoveVerifier.predict(old,move);
        assertNotNull(p);assertEquals(1,p.expectedRemainingPieces);
        assertTrue(p.expected[7][3]);assertTrue(p.expected[7][4]);
        assertFalse("Only a UI animation is not proof",MoveVerifier.matches(p,old));
        PixelAnalyzer.Snapshot success=snap(p.expected,b);
        assertTrue(MoveVerifier.matches(p,success));
        boolean[][] unrelated=new boolean[9][9];unrelated[0][0]=true;
        assertFalse(MoveVerifier.matches(p,snap(unrelated,b)));
    }
    @Test public void simultaneouslyClearsCompletedRowAndColumn() {
        boolean[][] before=new boolean[9][9];
        for (int c=0;c<8;c++) before[8][c]=true;
        for (int r=0;r<8;r++) before[r][8]=true;
        PixelAnalyzer.Piece single=piece(0,0);
        MoveVerifier.Prediction p=MoveVerifier.predict(snap(before,single),
                new StrategyEngine.Move(0,8,8,2,1,.9));
        assertNotNull(p);assertEquals(2,p.linesCleared);
        for(boolean[] row:p.expected) for(boolean cell:row) assertFalse(cell);
        // The three-piece tray may refill as soon as its last piece is placed.
        assertTrue(MoveVerifier.matches(p,snap(p.expected,single,single,single)));
    }
    @Test public void rejectsIllegalOccupiedOrOutOfBoundsPlacement() {
        boolean[][] before=new boolean[8][8];before[3][4]=true;
        PixelAnalyzer.Snapshot s=snap(before,piece(0,0));
        assertNull(MoveVerifier.predict(s,new StrategyEngine.Move(0,4,3,0,0,.9)));
        assertNull(MoveVerifier.predict(s,new StrategyEngine.Move(0,9,3,0,0,.9)));
    }
}
