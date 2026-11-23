package com.pixelblock.autoplay.vision;

import com.pixelblock.autoplay.strategy.StrategyEngine;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Historical game screenshots, before and after several moves. */
public final class MoreScreenshotRegressionTest {
    private PixelAnalyzer.Snapshot load(String name) throws Exception {
        InputStream src=getClass().getClassLoader().getResourceAsStream(name);
        assertNotNull(name,src);
        try(DataInputStream in=new DataInputStream(new GZIPInputStream(src))) {
            int w=in.readInt(),h=in.readInt();
            byte[] bytes=new byte[w*h*3];in.readFully(bytes);
            int[] pixels=new int[w*h];
            for(int i=0;i<pixels.length;i++){
                int k=i*3;
                pixels[i]=0xff000000|((bytes[k]&255)<<16)|((bytes[k+1]&255)<<8)|(bytes[k+2]&255);
            }
            return new PixelAnalyzer().analyze(pixels,w,h);
        }
    }
    private void check(String name,int occupied,int... tileCounts) throws Exception {
        PixelAnalyzer.Snapshot s=load(name);
        assertTrue(s.status,s.playable());
        assertEquals(9,s.occupied.length);
        int found=0;for(boolean[] row:s.occupied)for(boolean c:row)if(c)found++;
        assertEquals(occupied,found);
        assertEquals(tileCounts.length,s.pieces.size());
        for(int i=0;i<tileCounts.length;i++)assertEquals(tileCounts[i],s.pieces.get(i).tiles.size());
        StrategyEngine.Move move=new StrategyEngine().bestMove(s);
        assertNotNull(move);
        assertTrue(move.pieceIndex>=0 && move.pieceIndex<tileCounts.length);
    }
    @Test public void detectsEmptyBoardAndDifferentPieceTypes() throws Exception {
        check("empty-game-445x727.rgb.gz",0,1,2,2);
    }
    @Test public void detectsSparseBoardAndThreeFourTilePieces() throws Exception {
        check("sparse-game-446x725.rgb.gz",5,4,4,4);
    }
}
