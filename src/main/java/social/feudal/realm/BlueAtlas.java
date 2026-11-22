package social.feudal.realm;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.*;
import de.bluecolored.bluemap.api.math.*;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;

/** Publish public survey data only. Runs on the Paper thread, where territorial writes are serialized. */
final class BlueAtlas implements Runnable {
    private final RealmService realm;
    private int published=-1;
    private BlueMapAPI previous;
    BlueAtlas(RealmService realm){this.realm=realm;}
    @Override public void run(){
        var api=BlueMapAPI.getInstance().orElse(null);
        if(api==null || (previous==api && published==realm.mapRevision))return;
        var world=api.getWorld(realm.world).orElse(null);if(world==null || world.getMaps().isEmpty())return;
        try{
            var s=realm.store;
            var countries=new MarkerSet("领邦疆界",true,false);var empire=new MarkerSet("帝国成员及帝位领产",true,true);
            var hearings=new MarkerSet("边疆公示",true,true);var estates=new MarkerSet("私人庄园",true,true);
            var names=new HashMap<String,String>();var inside=new HashMap<String,Boolean>();
            try(var q=s.sql("SELECT id,name FROM title");var r=q.executeQuery()){
                while(r.next()){names.put(r.getString(1),r.getString(2));inside.put(r.getString(1),s.politics.imperial(r.getString(1)));}
            }
            // One strip per continuous span. Gaps, exclaves and different countries keep their edges.
            try(var q=s.sql("""
                    SELECT l.x,l.z,coalesce(a.nation,'crown') FROM land l JOIN affiliation a ON a.title=l.title
                    ORDER BY l.z,l.x""");var r=q.executeQuery()){
                String id=null;int west=0,east=0,z=0;
                while(r.next()){
                    int x=r.getInt(1),row=r.getInt(2);String next=r.getString(3);
                    if(id!=null && (x!=east+1 || row!=z || !next.equals(id))){
                        span(countries,empire,id,names.get(id),inside.get(id),west,east,z);id=null;
                    }
                    if(id==null){id=next;west=x;z=row;}
                    east=x;
                }
                if(id!=null)span(countries,empire,id,names.get(id),inside.get(id),west,east,z);
            }
            try(var q=s.sql("SELECT id,name,west,north,east,south FROM manor");var r=q.executeQuery()){
                while(r.next())put(estates,r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5)+1,r.getInt(6)+1,0x4e9b72);
            }
            try(var q=s.sql("SELECT id,type,west,north,east,south FROM claim WHERE state='公示中'");var r=q.executeQuery()){
                while(r.next())put(hearings,r.getString(1),r.getString(2)+"公示 #"+r.getInt(1),16*r.getInt(3),16*r.getInt(4),16*(r.getInt(5)+1),16*(r.getInt(6)+1),0xb55c37);
            }
            var tolls=bridgeMarkers(s);
            var markets=new MarkerSet("商站与收购",true,false);
            try(var q=s.sql("""
                    SELECT t.id,t.name,t.x,t.y,t.z,count(b.id) FROM stall t
                    LEFT JOIN bid b ON b.stall=t.id AND b.remaining>0 WHERE t.x IS NOT NULL
                    GROUP BY t.id""");var r=q.executeQuery()){
                while(r.next()){
                    String name=escape(r.getString(2));
                    markets.put(r.getString(1),POIMarker.builder().label(name)
                            .detail("<strong>"+name+"</strong><br>在榜收购单　"+r.getInt(6)+" 份<br>游戏内输入 /realm prices 比价")
                            .position(r.getInt(3)+.5,r.getInt(4)+1.5,r.getInt(5)+.5).defaultIcon().build());
                }
            }
            var deeds=new MarkerSet("待签割地文书",true,false);
            try(var q=s.sql("""
                    SELECT c.treaty,t.name,c.donor,c.recipient,c.west,c.north,c.east,c.south
                    FROM land_cession c JOIN treaty t ON t.id=c.treaty WHERE t.state='待批准'""");
                    var r=q.executeQuery()){
                while(r.next()){
                    String label=escape(r.getString(2)+" · "+names.get(r.getString(3))+" → "+names.get(r.getString(4))+"（待签）");
                    var box=Shape.createRect(r.getInt(5)*16,r.getInt(6)*16,(r.getInt(7)+1)*16,(r.getInt(8)+1)*16);
                    deeds.put(r.getString(1),ShapeMarker.builder().label(label).shape(box,65).centerPosition()
                            .depthTestEnabled(false).lineWidth(3).lineColor(new Color(0xe9ab38,.95f))
                            .fillColor(new Color(0xe9ab38,.24f)).build());
                }
            }
            var ferries=new MarkerSet("公共航线泊位",true,false);
            var network=new MarkerSet("驿道与航线",true,false);
            for(var entry:realm.publicRoutes.entrySet()){
                var route=entry.getValue();
                if(route.sea() && !route.path().isEmpty()){
                    for(var berth:List.of(route.path().getFirst(),route.path().getLast())){
                        String key=berth.x()+"_"+berth.y()+"_"+berth.z();
                        ferries.put(key,POIMarker.builder().label("渡口泊位")
                                .detail("已勘测的公共航线泊位。请驾船靠近木栈桥的系缆桩上下船。")
                                .position(berth.x()+.5,berth.y()+1,berth.z()+.5).defaultIcon().build());
                    }
                }
                var points=route.path().stream().map(p->new com.flowpowered.math.vector.Vector3d(p.x()+.5,p.y()+1.15,p.z()+.5)).toList();
                network.put(entry.getKey(),LineMarker.builder().label((route.sea()?"航线 · ":"驿道 · ")+escape(entry.getKey()))
                        .line(new Line(points)).centerPosition().depthTestEnabled(false).lineWidth(4)
                        .lineColor(new Color(route.sea()?0x46cdef:0xe99844,.95f)).build());
            }
            for(var map:world.getMaps()){
                var sets=map.getMarkerSets();sets.put("feudal-countries",countries);sets.put("feudal-empire",empire);
                sets.put("feudal-estates",estates);sets.put("feudal-hearings",hearings);
                sets.put("feudal-crossings",tolls);sets.put("feudal-deeds",deeds);sets.put("feudal-network",network);sets.put("feudal-markets",markets);sets.put("feudal-ferries",ferries);
            }
            previous=api;published=realm.mapRevision;
        }catch(SQLException e){realm.plugin.getLogger().log(Level.WARNING,"重建公共疆域图层失败",e);}
    }
    private static MarkerSet bridgeMarkers(RealmStore s)throws SQLException{
        var bridges=new MarkerSet("桥亭与通行特许",true,false);
        var privileges=new HashMap<String,List<String>>();
        try(var q=s.sql("""
                SELECT g.bridge,t.name,g.remaining FROM bridge_privilege g JOIN title t ON t.id=g.holder
                WHERE g.remaining>0 ORDER BY g.bridge,g.id""");var r=q.executeQuery()){
            while(r.next())privileges.computeIfAbsent(r.getString(1),unused->new ArrayList<>())
                    .add(escape(r.getString(2))+"　余 "+r.getInt(3)+" 次");
        }
        try(var q=s.sql("""
                SELECT c.id,c.name,t.name,c.fee,c.x,c.y,c.z,c.currency FROM crossing c JOIN title t ON t.id=c.owner""");
                var r=q.executeQuery()){
            while(r.next()){
                String id=r.getString(1),name=escape(r.getString(2));
                var lines=new ArrayList<String>();
                lines.add("<strong>"+name+"</strong>");
                lines.add("收费权　"+escape(r.getString(3)));
                lines.add("每次 "+r.getInt(4)+" "+TollCoin.parse(r.getString(8)).label);
                var grants=privileges.getOrDefault(id,List.of());
                lines.add(grants.isEmpty()?"当前无免税特许":"免税特许");
                lines.addAll(grants);
                String detail="<div style='min-width:12em;max-width:22em;width:max-content'>"
                        +String.join("<br>",lines)+"</div>";
                bridges.put(id,POIMarker.builder().label(name).detail(detail)
                        .position(r.getInt(5)+.5,r.getInt(6)+1.5,r.getInt(7)+.5).defaultIcon().build());
            }
        }
        return bridges;
    }
    private static void span(MarkerSet countries,MarkerSet empire,String id,String name,boolean inside,int west,int east,int z){
        String key=west+"_"+z;int x1=west*16,x2=(east+1)*16,z1=z*16,z2=z1+16;
        put(countries,key,name,x1,z1,x2,z2,0x404040|(id.hashCode()&0x9f9f9f));
        if(inside)put(empire,key,"河湾帝国 · "+name,x1,z1,x2,z2,0x8661a9);
    }
    private static void put(MarkerSet set,String id,String name,int west,int north,int east,int south,int rgb){
        String safe=escape(name);
        set.put(id,ShapeMarker.builder().label(safe).shape(Shape.createRect(west,north,east,south),64).centerPosition()
                .depthTestEnabled(false).lineWidth(0).fillColor(new Color(rgb,.42f)).build());
    }
    private static String escape(String name){
        return name.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
