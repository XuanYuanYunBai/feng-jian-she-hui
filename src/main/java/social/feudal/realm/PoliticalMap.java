package social.feudal.realm;

import java.awt.Color;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.map.*;

/** North-up projections of the land register. A personal preview never changes another viewer's chart. */
final class PoliticalMap extends MapRenderer {
    private static final Color PAPER=new Color(235,217,165),INK=new Color(50,44,43);
    private static final Color[] COLORS={new Color(162,83,60),new Color(59,112,81),new Color(106,87,141),
            new Color(80,128,155),new Color(159,119,54),new Color(105,118,67)};
    private record Lens(Charters.Bounds area,Charters.Bounds selection,String caption){}
    private final Map<UUID,Lens> lenses=new HashMap<>();
    private final Map<UUID,Integer> drawn=new HashMap<>();
    private final RealmStore store;
    private int revision;
    PoliticalMap(RealmStore store){super(true);this.store=store;}
    void invalidate(){revision++;}
    void forget(UUID viewer){drawn.remove(viewer);lenses.remove(viewer);}
    void local(UUID viewer,int x,int z){
        lenses.put(viewer,new Lens(new Charters.Bounds(x-12,z-12,x+12,z+12),null,"25 x 25 区块"));
        drawn.remove(viewer);
    }
    void select(UUID viewer,Charters.Bounds b,String caption){
        lenses.put(viewer,new Lens(new Charters.Bounds(b.west()-4,b.north()-4,b.east()+4,b.south()+4),b,caption));
        drawn.remove(viewer);
    }
    private static Charters.Bounds extent(List<RealmStore.Land> lands){
        int west=Integer.MAX_VALUE,north=Integer.MAX_VALUE,east=Integer.MIN_VALUE,south=Integer.MIN_VALUE;
        for(var l:lands){
            west=Math.min(west,l.x());east=Math.max(east,l.x());north=Math.min(north,l.z());south=Math.max(south,l.z());
        }
        return new Charters.Bounds(west,north,east,south);
    }
    @Override public void render(MapView view,MapCanvas c,Player player){
        UUID uuid=player.getUniqueId();
        if(Objects.equals(drawn.get(uuid),revision))return;
        try {
            var lands=store.lands();var titles=store.titles();if(lands.isEmpty())return;
            var lens=lenses.get(uuid);var area=lens==null?extent(lands):lens.area();
            int w=area.east()-area.west()+1,h=area.south()-area.north()+1;
            double scale=Math.min(116.0/w,72.0/h);
            int left=(int)((128-w*scale)/2),top=20+(int)((72-h*scale)/2);
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)c.setPixelColor(x,y,PAPER);
            c.drawText(6,4,MapLegend.FONT,lens==null?"帝国疆域图":"地方勘界图");
            Map<String,Integer> key=new HashMap<>();
            for(int i=0;i<titles.size();i++)key.put(titles.get(i).id(),i);
            for(var l:lands){
                if(!area.contains(l.x(),l.z()))continue;
                int x=left+(int)((l.x()-area.west())*scale),y=top+(int)((l.z()-area.north())*scale);
                int xEnd=Math.max(x+1,left+(int)((l.x()-area.west()+1)*scale));
                int yEnd=Math.max(y+1,top+(int)((l.z()-area.north()+1)*scale));
                var fill=COLORS[key.get(l.title())%COLORS.length];
                for(int j=y;j<yEnd;j++)for(int i=x;i<xEnd;i++)c.setPixelColor(i,j,fill);
            }
            for(int i=0;i<titles.size();i++){
                var t=titles.get(i);
                if(!area.contains(t.x(),t.z()) || t.charter().equals("收益封契"))continue;
                int x=left+(int)((t.x()-area.west())*scale),y=top+(int)((t.z()-area.north())*scale);
                c.setPixelColor(x,y,INK);
                c.drawText(Math.min(112,x),Math.max(16,y-10),MinecraftFont.Font,Integer.toString(i+1));
            }
            if(lens!=null && lens.selection()!=null){
                var b=lens.selection();
                int x0=left+(int)((b.west()-area.west())*scale),x1=left+(int)((b.east()-area.west()+1)*scale);
                int y0=top+(int)((b.north()-area.north())*scale),y1=top+(int)((b.south()-area.north()+1)*scale);
                for(int x=x0;x<=x1;x++){c.setPixelColor(x,y0,INK);c.setPixelColor(x,y1,INK);}
                for(int y=y0;y<=y1;y++){c.setPixelColor(x0,y,INK);c.setPixelColor(x1,y,INK);}
            }
            c.drawText(6,98,MapLegend.FONT,lens==null?"1王城  2伯领":"北上");
            c.drawText(6,113,MapLegend.FONT,lens==null?"3主教驻地":lens.caption());
            drawn.put(uuid,revision);
        }catch(SQLException ex){throw new IllegalStateException(ex);}
    }
}
