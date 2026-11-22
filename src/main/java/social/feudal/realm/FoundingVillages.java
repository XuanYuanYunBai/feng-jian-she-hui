package social.feudal.realm;

import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Only new worlds, or worlds explicitly enrolled by an administrator, receive the three finite endowments. */
final class FoundingVillages {
    private record Offset(int x,int z){}
    private static final List<Offset> CANDIDATES=candidates();
    private static List<Offset> candidates(){
        var offsets=new ArrayList<Offset>();
        for(int x=-8;x<8;x++)for(int z=-8;z<8;z++)offsets.add(new Offset(x,z));
        offsets.sort(Comparator.comparingInt(p->p.x()*p.x()+p.z()*p.z()));
        return List.copyOf(offsets);
    }
    private final RealmService realm;
    private final RealmStore store;
    private final NamespacedKey stamp;

    FoundingVillages(RealmService realm)throws SQLException{
        this.realm=realm;
        store=realm.store;
        stamp=new NamespacedKey(realm.plugin,"founding_village");
        store.update("""
            CREATE TABLE IF NOT EXISTS founding_village(title TEXT PRIMARY KEY REFERENCES title(id),name TEXT,
            candidate INTEGER NOT NULL DEFAULT 0,x INTEGER,y INTEGER,z INTEGER,done INTEGER NOT NULL DEFAULT 0)""");
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::advance,60,40);
    }

    void enroll()throws SQLException{
        store.atomic(()->{
            store.update("INSERT OR IGNORE INTO founding_village(title,name) VALUES('crown','王城近郊'),('march','东境农庄'),('abbey','修院佃庄')");
            return null;
        });
    }

    void selectSite(String title,int x,int z)throws SQLException{
        RealmStore.require(store.number("SELECT count(*) FROM founding_village WHERE title=? AND done=0 AND x IS NULL",title)==1,
                "请选择已登记且尚未落址的初始聚落");
        var yard=FirstHolding.yard(x,z);
        var seat=store.title(title);
        for(int cx=yard.west()>>4;cx<=yard.east()>>4;cx++)for(int cz=yard.north()>>4;cz<=yard.south()>>4;cz++){
            RealmStore.require(title.equals(store.owner(cx,cz)),"整个院落须位于指定辖区内");
            RealmStore.require((cx!=seat.x() || cz!=seat.z()) &&
                    store.number("SELECT count(*) FROM depot WHERE x=? AND z=?",cx,cz)==0,"请避开治所和税站");
        }
        realm.manors.register.available(seat.seal(),yard);
        var site=survey(x,z);
        RealmStore.require(site!=null,"请准备一处九格见方的平缓干地，地面和净空须适合搭建民房");
        store.update("UPDATE founding_village SET x=?,y=?,z=?,candidate=0 WHERE title=?",x,site.floor(),z,title);
    }

    private void advance(){
        try(var q=store.sql("SELECT * FROM founding_village WHERE done=0 AND candidate<256 ORDER BY candidate,title LIMIT 1");
            var row=q.executeQuery()){
            if(!row.next())return;
            String title=row.getString("title"),name=row.getString("name");
            int index=row.getInt("candidate");
            Storehouse.Site site;
            if(row.getObject("x")!=null){
                site=new Storehouse.Site(row.getInt("x"),row.getInt("y"),row.getInt("z"));
            }else{
                var seat=store.title(title);
                // Inside the original 16x16 jurisdiction, outside its seat and tax stations.
                var offset=CANDIDATES.get(index);
                int cx=seat.x()+offset.x(),cz=seat.z()+offset.z();
                if((cx==seat.x() && cz==seat.z()) || !title.equals(store.owner(cx,cz)) ||
                        store.number("SELECT count(*) FROM depot WHERE x=? AND z=?",cx,cz)>0){
                    next(title);return;
                }
                site=findSite(cx,cz);
                if(site==null){next(title);return;}
                try{realm.manors.register.available(seat.seal(),bounds(site));}
                catch(IllegalArgumentException ex){next(title);return;}
                store.update("UPDATE founding_village SET x=?,y=?,z=? WHERE title=?",site.x(),site.floor(),site.z(),title);
            }
            String id="village_"+title;
            var m=new SettlementRegister.Manor(id,name,"公产·"+title,site.x(),site.floor()+1,site.z()+2,bounds(site),100,0,false);
            var chunk=realm.world.getChunkAt(m.x()>>4,m.z()>>4);
            if(!id.equals(chunk.getPersistentDataContainer().get(stamp,PersistentDataType.STRING))){
                if(!usable(site)){next(title);return;}
                build(m);
                chunk.getPersistentDataContainer().set(stamp,PersistentDataType.STRING,id);
                realm.world.save(true);
            }
            int housing=ManorHousing.capacity(realm.world,m);
            if(housing<100)throw new SQLException("初始民房验收未通过 "+id);
            store.atomic(()->{
                var b=m.estate();
                store.update("""
                    INSERT INTO manor(id,name,owner,x,y,z,west,north,east,south,residents,housing,last_season)
                    VALUES(?,?,?,?,?,?,?,?,?,?,100,?,?)""",id,name,m.owner(),m.x(),m.y(),m.z(),b.west(),b.north(),
                        b.east(),b.south(),housing,realm.manors.register.season());
                store.update("UPDATE founding_village SET done=1 WHERE title=?",title);
                return null;
            });
            realm.changed();
        }catch(SQLException ex){realm.plugin.getLogger().log(Level.WARNING,"初始聚落登记待复核",ex);}
    }

    private Storehouse.Site findSite(int cx,int cz){
        for(int dx:new int[]{8,4,11})for(int dz:new int[]{8,4,11}){
            var site=survey(cx*16+dx,cz*16+dz);
            if(site!=null)return site;
        }
        return null;
    }
    private Storehouse.Site survey(int x,int z){
        var world=realm.world;
        int floor=world.getMinHeight();
        for(int a=-4;a<=4;a++)for(int b=-4;b<=4;b++)
            floor=Math.max(floor,world.getHighestBlockYAt(x+a,z+b,HeightMap.MOTION_BLOCKING_NO_LEAVES));
        if(floor+5>=world.getMaxHeight())return null;
        var site=new Storehouse.Site(x,floor,z);
        return usable(site)?site:null;
    }
    private void next(String title)throws SQLException{
        store.update("UPDATE founding_village SET candidate=candidate+1,x=NULL,y=NULL,z=NULL WHERE title=?",title);
    }
    private Charters.Bounds bounds(Storehouse.Site site){return FirstHolding.yard(site.x(),site.z());}
    private boolean usable(Storehouse.Site site){
        var world=realm.world;
        // A dry, shallow foundation and clear volume. Leave existing structures and deep water intact.
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            int x=site.x()+dx,z=site.z()+dz;
            if(!world.getWorldBorder().isInside(new Location(world,x+.5,site.floor(),z+.5)))return false;
            int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if(y>site.floor() || site.floor()-y>4)return false;
            var ground=world.getBlockAt(x,y,z).getType();
            if(!Tag.DIRT.isTagged(ground) && !List.of(Material.STONE,Material.SAND,Material.GRAVEL).contains(ground))return false;
            for(int level=y+1;level<=site.floor()+4;level++){
                var type=world.getBlockAt(x,level,z).getType();
                if(!type.isAir() && type!=Material.SHORT_GRASS && type!=Material.TALL_GRASS && !Tag.FLOWERS.isTagged(type))return false;
            }
        }
        return true;
    }
    private void build(SettlementRegister.Manor m){
        var hall=realm.world.getBlockAt(m.x(),m.y(),m.z()-2);
        for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++){
            var foot=hall.getRelative(x,-1,z);
            for(int y=0;y>=-4 && !foot.getRelative(0,y,0).getType().isSolid();y--)
                foot.getRelative(0,y,0).setType(Material.COBBLESTONE,false);
            foot.setType(Material.SPRUCE_PLANKS,false);
            for(int y=0;y<=3;y++)hall.getRelative(x,y,z).setType(Material.AIR,false);
            if(Math.abs(x)==4 || Math.abs(z)==4)hall.getRelative(x,0,z).setType(Material.SPRUCE_FENCE);
            if(Math.abs(x)<=2 && z>=-3 && z<=0){
                if(Math.abs(x)==2 || z==-3 || z==0)
                    for(int y=0;y<=2;y++)hall.getRelative(x,y,z).setType(Material.OAK_PLANKS,false);
                hall.getRelative(x,3,z).setType(Material.SPRUCE_PLANKS,false);
            }
        }
        hall.getRelative(0,0,4).setType(Material.SPRUCE_FENCE_GATE);
        for(var half:Bisected.Half.values()){
            var door=(Door)Material.OAK_DOOR.createBlockData();
            door.setFacing(BlockFace.SOUTH);door.setHalf(half);
            hall.getRelative(-1,half==Bisected.Half.TOP?1:0,0).setBlockData(door,false);
        }
        for(var part:Bed.Part.values()){
            var bed=(Bed)Material.RED_BED.createBlockData();
            bed.setFacing(BlockFace.NORTH);bed.setPart(part);
            hall.getRelative(0,0,part==Bed.Part.HEAD?-2:-1).setBlockData(bed,false);
        }
        hall.getRelative(0,0,2).setType(Material.LECTERN,false);
        var bin=hall.getRelative(1,0,2);bin.setType(Material.BARREL,false);
        ((Barrel)bin.getState()).getInventory().setItem(0,new ItemStack(Material.BREAD,16));
    }
}
