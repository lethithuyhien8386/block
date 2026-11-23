package com.pixelblock.autoplay.strategy;

import com.pixelblock.autoplay.vision.PixelAnalyzer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Tries the three visible blocks in ANY order, including placing the rightmost first.
 * Scores complete multi-block outcomes rather than a greedy single drop.
 * The next tray is unknown: optimality for the entire game is not knowable.
 * Work is bounded so screen capture / STOP remain responsive on mobile CPUs.
 */
public final class StrategyEngine {
    public static final class Move {
        public final int pieceIndex, col, row, linesCleared;
        public final double score, confidence;
        Move(int index, int x, int y, int cleared, double score, double confidence) {
            pieceIndex=index; col=x; row=y; linesCleared=cleared;
            this.score=score; this.confidence=confidence;
        }
    }
    private static final class Shape {
        final int index, width, height, tileCount;
        final int[] bits;
        final double confidence;
        Shape(PixelAnalyzer.Piece p, int index) {
            this.index=index; width=p.columns; height=p.rows; tileCount=p.tiles.size();
            confidence=p.confidence; bits=new int[height];
            for (PixelAnalyzer.Tile tile:p.tiles) bits[tile.row] |= 1 << tile.col;
        }
    }
    private static final class State {
        final int[] rows;
        final int cleared;
        State(int[] rows, int cleared) { this.rows=rows; this.cleared=cleared; }
    }
    private static final class Opening {
        int piece, row, col, cleared;
        int[] board;
        double rank;
        Opening(int piece,int row,int col,State state,double rank) {
            this.piece=piece; this.row=row; this.col=col; this.cleared=state.cleared;
            board=state.rows; this.rank=rank;
        }
    }
    private static final class Context {
        Shape[] shapes;
        int n, full, beforeRegions;
        long limitNanos;
        int visits, visitLimit=1800000;
        int bestDepth=-1, bestLines=-1, bestClosed=Integer.MAX_VALUE;
        double bestScore=-1e100;
        Move best;
        double confidence;
        boolean budgetReached;
    }
    /** Recomputes the first move each time the screen state changes. */
    public Move bestMove(PixelAnalyzer.Snapshot snapshot) {
        if(snapshot==null || !snapshot.playable())return null;
        int n=snapshot.occupied.length;
        if(n<4 || n>12)return null;
        int count=Math.min(3,snapshot.pieces.size());
        if(count==0)return null;
        Context ctx=new Context();
        ctx.shapes=new Shape[count];
        for(int i=0;i<count;i++)ctx.shapes[i]=new Shape(snapshot.pieces.get(i),i);
        ctx.n=n; ctx.full=(1<<n)-1; ctx.confidence=snapshot.confidence;
        int[] initial=new int[n];
        for(int y=0;y<n;y++)for(int x=0;x<n;x++)if(snapshot.occupied[y][x])initial[y]|=1<<x;
        ctx.beforeRegions=countRegions(initial,n);
        // Bounded exhaustive DFS. All openings are considered before deeper search;
        // good openings are expanded first if the work/time budget is reached.
        ctx.limitNanos=System.nanoTime()+1_350_000_000L;
        List<Opening> openings=new ArrayList<>();
        for(Shape s:ctx.shapes){
            for(int row=n-s.height;row>=0;row--)for(int col=0;col<=n-s.width;col++){
                if(!fits(initial,s,row,col))continue;
                State applied=place(initial,s,row,col,ctx.full);
                Opening o=new Opening(s.index,row,col,applied,
                        applied.cleared*10000.0+evaluate(applied.rows,ctx.n,ctx.beforeRegions,applied.cleared)+row*.05);
                openings.add(o);
            }
        }
        if(openings.isEmpty())return null;
        openings.sort(Comparator.comparingDouble((Opening o)->o.rank).reversed());
        // First pass: assign a legal fallback from *every* opening before deep search.
        for(Opening o:openings)consider(ctx,o.board,1,o.cleared,o,o.cleared);
        for(Opening o:openings){
            if(ctx.visits>ctx.visitLimit || System.nanoTime()>ctx.limitNanos){ctx.budgetReached=true;break;}
            search(ctx,o.board,1<<o.piece,1,o.cleared,o,o.cleared);
        }
        return ctx.best;
    }
    private static void search(Context ctx,int[] board,int used,int depth,int clears,Opening first,int firstCleared){
        if(depth==ctx.shapes.length)return;
        // The next move can be any of the other visible blocks; bottom-first is only a tie-breaker.
        for(Shape s:ctx.shapes){
            if((used & 1<<s.index)!=0)continue;
            for(int row=ctx.n-s.height;row>=0;row--)for(int col=0;col<=ctx.n-s.width;col++){
                if(!fits(board,s,row,col))continue;
                ctx.visits++;
                if(ctx.visits>ctx.visitLimit || (ctx.visits&8191)==0 && System.nanoTime()>ctx.limitNanos){
                    ctx.budgetReached=true;return;
                }
                State next=place(board,s,row,col,ctx.full);
                int nextClears=clears+next.cleared;
                consider(ctx,next.rows,depth+1,nextClears,first,firstCleared);
                if(depth+1<ctx.shapes.length)
                    search(ctx,next.rows,used|1<<s.index,depth+1,nextClears,first,firstCleared);
                if(ctx.budgetReached)return;
            }
        }
    }
    private static void consider(Context ctx,int[] board,int depth,int cleared,Opening first,int firstCleared){
        if(depth<ctx.bestDepth || depth==ctx.bestDepth && cleared<ctx.bestLines)return;
        int regions=countRegions(board,ctx.n);
        int closed=Math.max(0,regions-ctx.beforeRegions);
        // A trapped pocket without a clearing line is discouraged, not fatal:
        // forced positions must still yield a legal move rather than doing nothing.
        double value=evaluate(board,ctx.n,ctx.beforeRegions,cleared) + first.row*.03;
        boolean better=depth>ctx.bestDepth || (depth==ctx.bestDepth && (cleared>ctx.bestLines ||
                cleared==ctx.bestLines && (closed<ctx.bestClosed || closed==ctx.bestClosed && value>ctx.bestScore)));
        if(better){
            ctx.bestDepth=depth;ctx.bestLines=cleared;ctx.bestClosed=closed;ctx.bestScore=value;
            Shape sh=ctx.shapes[first.piece];
            ctx.best=new Move(first.piece,first.col,first.row,firstCleared,value,
                    Math.min(1.0,ctx.confidence*sh.confidence));
        }
    }
    private static boolean fits(int[] board,Shape s,int row,int col){
        for(int i=0;i<s.height;i++)if((board[row+i] & (s.bits[i]<<col))!=0)return false;
        return true;
    }
    private static State place(int[] board,Shape s,int row,int col,int full){
        int[] after=board.clone();
        for(int i=0;i<s.height;i++)after[row+i]|=s.bits[i]<<col;
        int fullRows=0,common=full;
        for(int bits:after){if(bits==full)fullRows++;common&=bits;}
        int lines=fullRows+Integer.bitCount(common);
        if(lines!=0){for(int i=0;i<after.length;i++)after[i]=after[i]==full?0:after[i]&~common;}
        return new State(after,lines);
    }
    /** Quality of remaining space. Wide open areas make next unknown tray more likely to fit. */
    private static double evaluate(int[] rows,int n,int originalRegions,int cleared){
        int occupied=0,almost=0;
        for(int row:rows){occupied+=Integer.bitCount(row);}
        for(int i=0;i<n;i++){
            int col=0;
            for(int r=0;r<n;r++)col+=(rows[r]>>>i)&1;
            int rowCount=Integer.bitCount(rows[i]);
            if(rowCount==n-1)almost+=5;
            else if(rowCount==n-2)almost+=2;
            if(col==n-1)almost+=5;
            else if(col==n-2)almost+=2;
        }
        int regions=countRegions(rows,n);
        int dead=0,small=0;
        for(int y=0;y<n;y++)for(int x=0;x<n;x++){
            if((rows[y]&(1<<x))!=0)continue;
            int neighbors=0;
            if(y>0&&(rows[y-1]&(1<<x))==0)neighbors++;
            if(y<n-1&&(rows[y+1]&(1<<x))==0)neighbors++;
            if(x>0&&(rows[y]&(1<<(x-1)))==0)neighbors++;
            if(x<n-1&&(rows[y]&(1<<(x+1)))==0)neighbors++;
            if(neighbors<=1)dead++;
            if(neighbors==0)small++;
        }
        // Count unconstrained rectangles: 3×3 and 2×3 are common difficult future pieces.
        int open3=0,open23=0,openLine=0;
        for(int y=0;y<n;y++){
            for(int x=0;x<=n-3;x++){
                int m=7<<x;
                if((rows[y]&m)==0)openLine++;
                if(y+1<n&&(rows[y]&m)==0&&(rows[y+1]&m)==0)open23++;
                if(y+2<n&&(rows[y]&m)==0&&(rows[y+1]&m)==0&&(rows[y+2]&m)==0)open3++;
            }
        }
        return (n*n-occupied)*1.0 + almost*3.0 + open3*1.8 + open23*.7 + openLine*.2
                - dead*9.0 - small*35.0 - Math.max(0,regions-originalRegions)*90.0
                - Math.max(0,regions-1)*9.0 + cleared*1.5;
    }
    /** Four-connected flood fill in row bit masks. */
    private static int countRegions(int[] rows,int n){
        int[] seen=new int[n];
        int[] queue=new int[n*n];int regions=0;
        for(int y=0;y<n;y++)for(int x=0;x<n;x++){
            int bit=1<<x;
            if(((rows[y]|seen[y])&bit)!=0)continue;
            regions++;int head=0,tail=0;queue[tail++]=y*n+x;seen[y]|=bit;
            while(head<tail){
                int p=queue[head++],r=p/n,c=p%n;
                if(r>0&&(rows[r-1]&(1<<c))==0&&(seen[r-1]&(1<<c))==0){seen[r-1]|=1<<c;queue[tail++]=(r-1)*n+c;}
                if(r+1<n&&(rows[r+1]&(1<<c))==0&&(seen[r+1]&(1<<c))==0){seen[r+1]|=1<<c;queue[tail++]=(r+1)*n+c;}
                if(c>0&&(rows[r]&(1<<(c-1)))==0&&(seen[r]&(1<<(c-1)))==0){seen[r]|=1<<(c-1);queue[tail++]=r*n+c-1;}
                if(c+1<n&&(rows[r]&(1<<(c+1)))==0&&(seen[r]&(1<<(c+1)))==0){seen[r]|=1<<(c+1);queue[tail++]=r*n+c+1;}
            }
        }
        return regions;
    }
    // Package-private test helper, including 8×8/9×9/10×10 boards.
    static int countEmptyRegions(boolean[][] b){
        int n=b.length;int[] rows=new int[n];
        for(int y=0;y<n;y++)for(int x=0;x<n;x++)if(b[y][x])rows[y]|=1<<x;
        return countRegions(rows,n);
    }
}
