package social.feudal.realm;

import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;
import org.bukkit.map.MapFont;
import org.bukkit.map.MinecraftFont;

/** MapCanvas uses bitmap glyphs; the stock map font only includes Latin characters. */
final class MapLegend {
    static final MapFont FONT = load();
    private static MapFont load() {
        String glyphs = "帝国疆域图地方勘界北上领王城伯主教驻区块授予合计";
        MapFont font = new MapFont();
        for (char c : "0123456789/x".toCharArray()) {
            var original = MinecraftFont.Font.getChar(c);
            int width = original.getWidth();
            boolean[] pixels = new boolean[width*12];
            for(int y=0;y<original.getHeight();y++) for(int x=0;x<width;x++)
                pixels[(y+2)*width+x]=original.get(y,x);
            font.setChar(c,new MapFont.CharacterSprite(width,12,pixels));
        }
        font.setChar(' ',new MapFont.CharacterSprite(3,12,new boolean[36]));
        try (var stream = MapLegend.class.getResourceAsStream("/maps/legend.png")) {
            var sheet = ImageIO.read(Objects.requireNonNull(stream));
            if (sheet.getWidth()!=glyphs.length()*12) throw new IllegalStateException("地图字形表不完整");
            for (int i=0; i<glyphs.length(); i++) {
                boolean[] pixels = new boolean[12*12];
                for (int y=0;y<12;y++) for(int x=0;x<12;x++)
                    pixels[y*12+x] = (sheet.getRGB(i*12+x,y)&255)>0;
                font.setChar(glyphs.charAt(i),new MapFont.CharacterSprite(12,12,pixels));
            }
        } catch(IOException ex) { throw new IllegalStateException("无法读取地图字形",ex); }
        return font;
    }
}
