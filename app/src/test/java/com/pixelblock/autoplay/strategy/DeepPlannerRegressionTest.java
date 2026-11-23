package com.pixelblock.autoplay.strategy;

import com.pixelblock.autoplay.vision.PixelAnalyzer;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;

/** The best first move can be a small right-side piece that unlocks a larger piece. */
public class DeepPlannerRegressionTest {
    private static PixelAnalyzer.Piece piece(int[][] cells) {
        ArrayList<PixelAnalyzer.Tile> tiles=new ArrayList<>();int w=0,h=0;
        for(int[] t:cells){tiles.add(new PixelAnalyzer.Tile(t[0],t[1]));w=Math.max(w,t[0]+1);h=Math.max(h,t[1]+1);}
        return new PixelAnalyzer.Piece(tiles,100,700,0,0,w,h,.99);
    }
    private static PixelAnalyzer.Snapshot snap(boolean[][] b,PixelAnalyzer.Piece... ps){
        return new PixelAnalyzer.Snapshot(new PixelAnalyzer.BoardBox(40,180,400,540,9),b,Arrays.asList(ps),.99,"ready");
    }
    @Test public void choosesLaterSmallPieceToUnlockBigSquare() {
        boolean[][] b=new boolean[9][9];
        for(int y=0;y<9;y++)if(y!=4&&y!=5)Arrays.fill(b[y],true);
        b[3][8]=false; // single tile clears row 4, making a three-row opening
        PixelAnalyzer.Piece square=piece(new int[][]{
                {0,0},{1,0},{2,0},{0,1},{1,1},{2,1},{0,2},{1,2},{2,2}});
        PixelAnalyzer.Piece single=piece(new int[][]{{0,0}});
        StrategyEngine.Move result=new StrategyEngine().bestMove(snap(b,square,single));
        assertNotNull(result);
        assertEquals("must place single before square",1,result.pieceIndex);
        assertEquals(3,result.row);assertEquals(8,result.col);
        assertTrue(result.linesCleared >= 1);
    }
    @Test public void findsAnyLegalMoveIfEmptyRegionWouldBeSplit() {
        boolean[][] b=new boolean[9][9];
        for(boolean[] r:b)Arrays.fill(r,true);
        b[5][5]=false;
        StrategyEngine.Move result=new StrategyEngine().bestMove(snap(b,piece(new int[][]{{0,0}})));
        assertNotNull(result);assertEquals(5,result.row);assertEquals(5,result.col);
    }
}
