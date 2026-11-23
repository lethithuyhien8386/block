import com.pixelblock.autoplay.strategy.StrategyEngine;
import com.pixelblock.autoplay.vision.PixelAnalyzer;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.List;

/** Desktop only (JDK): runs THE SAME pure-Java vision+strategy as the Android APK.
 * NEVER add this to app/src/main: Android has no java.awt/ImageIO.
 * One JSON object per screenshot, ready for a local dashboard. */
public final class AnalyzeScreenshot {
    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '\\' || ch == '"') b.append('\\');
            if (ch == '\n' || ch == '\r') b.append(' ');
            else b.append(ch);
        }
        return b.append('"').toString();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {System.err.println("Usage: AnalyzeScreenshot <png-or-jpg>"); System.exit(2);}
        BufferedImage img = ImageIO.read(new File(args[0]));
        if (img == null) throw new IllegalArgumentException("Ảnh PNG/JPG không hợp lệ");
        int w=img.getWidth(),h=img.getHeight();
        if ((long)w*h > 15_000_000) throw new IllegalArgumentException("Ảnh quá lớn");
        int[] p=img.getRGB(0,0,w,h,null,0,w);
        PixelAnalyzer.Snapshot s=new PixelAnalyzer().analyze(p,w,h);
        StrategyEngine.Move m=new StrategyEngine().bestMove(s);
        StringBuilder json=new StringBuilder("{");
        json.append("\"imageSize\":{").append("\"width\":").append(w).append(",\"height\":").append(h).append("},");
        json.append("\"playable\":").append(s.playable()).append(',');
        json.append("\"status\":").append(quote(s.status)).append(',');
        json.append("\"confidence\":").append(s.confidence).append(',');
        if (s.board==null) json.append("\"boardBox\":null,\"board\":null,");
        else {
            json.append("\"boardBox\":{").append("\"x\":").append(s.board.left).append(",\"y\":").append(s.board.top)
                .append(",\"width\":").append(s.board.right-s.board.left).append(",\"height\":").append(s.board.bottom-s.board.top)
                .append(",\"size\":").append(s.board.size).append("},");
            json.append("\"board\":[");
            for (int r=0;r<s.occupied.length;r++) {
                if(r>0)json.append(',');json.append('[');
                for(int c=0;c<s.occupied[r].length;c++){if(c>0)json.append(',');json.append(s.occupied[r][c]?1:0);}
                json.append(']');
            }
            json.append("],");
        }
        json.append("\"pieces\":[");
        for (int i=0;i<s.pieces.size();i++) {
            if(i>0)json.append(',');
            PixelAnalyzer.Piece b=s.pieces.get(i);
            json.append("{\"touchX\":").append(b.touchX).append(",\"touchY\":").append(b.touchY)
                .append(",\"touchCol\":").append(b.touchCol).append(",\"touchRow\":").append(b.touchRow)
                .append(",\"width\":").append(b.columns).append(",\"height\":").append(b.rows)
                .append(",\"confidence\":").append(b.confidence).append(",\"tiles\":[");
            for(int k=0;k<b.tiles.size();k++) {if(k>0)json.append(',');PixelAnalyzer.Tile t=b.tiles.get(k);
                json.append('[').append(t.col).append(',').append(t.row).append(']');}
            json.append("]}");
        }
        json.append("],\"move\":");
        if(m==null)json.append("null");
        else json.append("{\"piece\":").append(m.pieceIndex).append(",\"row\":").append(m.row)
            .append(",\"col\":").append(m.col).append(",\"linesCleared\":").append(m.linesCleared)
            .append(",\"score\":").append(m.score).append(",\"confidence\":").append(m.confidence).append('}');
        json.append('}');
        System.out.println(json);
    }
}
