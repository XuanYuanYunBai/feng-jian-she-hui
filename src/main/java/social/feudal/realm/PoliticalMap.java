package social.feudal.realm;

import java.awt.Color;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.map.*;

/** The same projection is used for every player's chart. North is up. */
final class PoliticalMap extends MapRenderer {
    static final Color PAPER=new Color(235,217,165), INK=new Color(50,44,43);
    private static final Color[] COLORS={new Color(162,83,60),new Color(59,112,81),new Color(106,87,141),
            new Color(80,128,155),new Color(159,119,54)};
    private final RealmStore store;
    private boolean dirty=true;
    PoliticalMap(RealmStore store){this.store=store;}
    void invalidate(){dirty=true;}
    @Override public void render(MapView view,MapCanvas c,Player player){
        if(!dirty)return;
        try {
            var lands=store.lands(); var titles=store.titles();
            if(lands.isEmpty())return;
            int left=lands.stream().mapToInt(RealmStore.Land::x).min().orElseThrow();
            int right=lands.stream().mapToInt(RealmStore.Land::x).max().orElseThrow();
            int north=lands.stream().mapToInt(RealmStore.Land::z).min().orElseThrow();
            int south=lands.stream().mapToInt(RealmStore.Land::z).max().orElseThrow();
            double scale=Math.min(116.0/(right-left+1),59.0/(south-north+1));
            for(int y=0;y<128;y++)for(int x=0;x<128;x++) c.setPixelColor(x,y,PAPER);
            c.drawText(6,7,MinecraftFont.Font,"IMPERIAL ATLAS");
            c.drawText(111,19,MinecraftFont.Font,"N");
            Map<String,Integer> key=new HashMap<>();
            for(int i=0;i<titles.size();i++)key.put(titles.get(i).id(),i);
            int top=32+(int)((59-scale*(south-north+1))/2);
            for(var l:lands){
                int x=6+(int)((l.x()-left)*scale),y=top+(int)((l.z()-north)*scale);
                for(int j=y;j<Math.max(y+1,top+(int)((l.z()-north+1)*scale));j++)
                    for(int i=x;i<Math.max(x+1,6+(int)((l.x()-left+1)*scale));i++)
                        c.setPixelColor(i,j,COLORS[key.get(l.title())%COLORS.length]);
            }
            for(int i=0;i<titles.size();i++){
                var t=titles.get(i);int x=6+(int)((t.x()-left)*scale),y=top+(int)((t.z()-north)*scale);
                c.setPixelColor(x,y,INK);
                c.drawText(Math.min(115,x),Math.max(20,y-12),MinecraftFont.Font,Integer.toString(i+1));
            }
            c.drawText(6,94,MinecraftFont.Font,"1 Crown   2 March");
            c.drawText(6,106,MinecraftFont.Font,"3 Bishopric");
            c.drawText(6,118,MinecraftFont.Font,"/realm atlas");
            dirty=false;
        }catch(SQLException ex){throw new IllegalStateException(ex);}
    }
}
