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
            for(var map:world.getMaps()){
                var sets=map.getMarkerSets();sets.put("feudal-countries",countries);sets.put("feudal-empire",empire);
                sets.put("feudal-estates",estates);sets.put("feudal-hearings",hearings);
            }
            previous=api;published=realm.mapRevision;
        }catch(SQLException e){realm.plugin.getLogger().log(Level.WARNING,"重建公共疆域图层失败",e);}
    }
    private static void span(MarkerSet countries,MarkerSet empire,String id,String name,boolean inside,int west,int east,int z){
        String key=west+"_"+z;int x1=west*16,x2=(east+1)*16,z1=z*16,z2=z1+16;
        put(countries,key,name,x1,z1,x2,z2,0x404040|(id.hashCode()&0x9f9f9f));
        if(inside)put(empire,key,"河湾帝国 · "+name,x1,z1,x2,z2,0x8661a9);
    }
    private static void put(MarkerSet set,String id,String name,int west,int north,int east,int south,int rgb){
        String safe=name.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
        set.put(id,ShapeMarker.builder().label(safe).shape(Shape.createRect(west,north,east,south),64).centerPosition()
                .depthTestEnabled(false).lineWidth(0).fillColor(new Color(rgb,.42f)).build());
    }
}
