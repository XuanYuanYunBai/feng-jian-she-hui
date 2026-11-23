package social.feudal.realm;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import social.feudal.transport.HaulNetwork.Point;
import static social.feudal.realm.RealmStore.require;

/** A direct land journey between two registered stations. Residents leave and arrive exactly once. */
final class RefugeOffice {
    private final RealmService realm;
    private final RealmStore s;
    RefugeOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;
        s.update("CREATE TABLE IF NOT EXISTS asylum(manor TEXT PRIMARY KEY REFERENCES manor(id))");
        s.update("""
            CREATE TABLE IF NOT EXISTS migration(id INTEGER PRIMARY KEY,source TEXT REFERENCES manor(id),
              dest TEXT REFERENCES manor(id),season INTEGER,people INTEGER CHECK(people>0),route TEXT,
              reverse INTEGER,step INTEGER DEFAULT 0,arrived INTEGER DEFAULT 0,status TEXT,
              UNIQUE(source,season))""");
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::tick,100,5);
    }
    void policy(Player p,String id,boolean open)throws SQLException{
        var m=realm.manors.register.manor(id);
        require(realm.manors.register.manages(m,p.getUniqueId().toString()),"请由庄主或公产持印人签署收容令");
        require(p.getWorld()==realm.world && p.getLocation().distanceSquared(realm.world.getBlockAt(m.x(),m.y(),m.z()).getLocation())<=64,"请在厅堂签署");
        if(open)s.update("INSERT OR IGNORE INTO asylum VALUES(?)",id);else s.update("DELETE FROM asylum WHERE manor=?",id);
    }
    private void tick(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try{
            if(realm.world.getGameTime()%200<5)bookings();
            for(var id:realm.diplomacy.column("SELECT id FROM migration WHERE arrived=0 ORDER BY id"))travel(Integer.parseInt(id));
        }catch(SQLException ex){realm.plugin.getLogger().log(Level.WARNING,"迁居账待复核",ex);}
    }
    private void bookings()throws SQLException{
        var open=realm.diplomacy.column("SELECT manor FROM asylum ORDER BY manor");
        if(open.isEmpty())return;
        for(var id:realm.diplomacy.column("""
            SELECT m.id FROM manor m WHERE shortages>=2 AND residents>0 AND last_season=?
            AND NOT EXISTS(SELECT 1 FROM migration j WHERE j.source=m.id AND j.season=m.last_season)
            ORDER BY m.id""",realm.manors.register.season())){
            var origin=realm.manors.register.manor(id);
            for(String dest:open)if(!dest.equals(id) && book(origin,realm.manors.register.manor(dest)))break;
        }
    }
    private boolean book(SettlementRegister.Manor origin, SettlementRegister.Manor host)throws SQLException{
        int waiting=s.number("SELECT coalesce(sum(people),0) FROM migration WHERE dest=? AND arrived=0",host.id());
        int spare=s.number("SELECT housing-residents FROM manor WHERE id=?",host.id())-waiting;
        if(spare<=0)return false;
        int season=realm.manors.register.season();
        int missing=s.number("SELECT shortfall FROM population_season WHERE manor=? AND season=?",origin.id(),season);
        for(var entry:realm.publicRoutes.entrySet()){
            var route=entry.getValue();var path=route.path();
            if(route.sea() || path.size()<3)continue;
            boolean reverse=station(origin,path.getLast()) && station(host,path.getFirst());
            if(!reverse && !(station(origin,path.getFirst()) && station(host,path.getLast())))continue;
            var start=reverse?path.getLast():path.getFirst();var end=reverse?path.getFirst():path.getLast();
            // A registered line can close. Check the actual decks before a family commits.
            if(!available(start,true) || !available(end,true))continue;
            boolean intact=true;
            for(int i=1;i<path.size()-1;i++)if(!available(path.get(i),false)){intact=false;break;}
            if(!intact)continue;
            var pantry=realm.manors.foodBarrel(host);
            if(pantry==null)return false;
            int bread=0;
            for(var stack:pantry.getInventory().getContents())if(ReliefOffice.plain(stack))bread+=stack.getAmount();
            int capacity=Math.min(spare,bread*100-host.residents()-waiting);
            int people=Math.min(Math.min(100,realm.families.life.freeResidents(origin.id())),Math.min(missing,capacity));
            if(people<=0)return false;
            s.atomic(()->{
                s.update("INSERT INTO migration(source,dest,season,people,route,reverse,status) VALUES(?,?,?,?,?,?,'候发')",
                        origin.id(),host.id(),season,people,entry.getKey(),reverse);
                require(s.update("UPDATE manor SET residents=residents-? WHERE id=? AND residents>=?",people,origin.id(),people)==1,"迁出人口已变化");
                return null;
            });
            realm.changed();return true;
        }
        return false;
    }
    private boolean station(SettlementRegister.Manor manor,Point p){
        return manor.estate().contains(p.x(),p.z()) && Math.abs(manor.y()-p.y())<=4;
    }
    private boolean available(Point p,boolean station){
        var w=realm.world;
        if(!w.isChunkLoaded(p.x()>>4,p.z()>>4) && !w.loadChunk(p.x()>>4,p.z()>>4,false))return false;
        if(!station)return realm.roads.road(p);
        var b=realm.roads.block(p);
        return b.getType()==Material.BARREL && b.getRelative(BlockFace.UP).isPassable() && b.getRelative(0,2,0).isPassable();
    }
    private void status(int id,String state)throws SQLException{
        s.update("UPDATE migration SET status=? WHERE id=? AND status<>?",state,id,state);
    }
    private void travel(int id)throws SQLException{
        String source,dest,route;int people,step;boolean reverse;
        try(var q=s.sql("SELECT source,dest,people,route,reverse,step FROM migration WHERE id=?",id);var row=q.executeQuery()){
            if(!row.next())return;
            source=row.getString(1);dest=row.getString(2);people=row.getInt(3);
            route=row.getString(4);reverse=row.getBoolean(5);step=row.getInt(6);
        }
        var link=realm.publicRoutes.get(route);
        if(link==null || link.sea()){status(id,"等候原驿道");return;}
        List<Point> path=reverse?link.path().reversed():link.path();
        if(path.size()<3 || step>=path.size() || !station(realm.manors.register.manor(source),path.getFirst())
                || !station(realm.manors.register.manor(dest),path.getLast())){status(id,"等候原起讫站");return;}
        var current=path.get(step);var start=path.getFirst();
        if(!available(current,step==0 || step==path.size()-1)){status(id,"道路中断");return;}
        var w=realm.world;var home=w.getChunkAt(start.x()>>4,start.z()>>4);
        String crew="migration/"+id;
        if(!realm.crews.registered(home,crew)){
            if(step!=0){status(id,"等候原居民队伍");return;}
            realm.crews.staff(home,crew,"迁居居民 "+people+" 人",start.feet(w));
            w.save(true);
        }
        int nextIndex=Math.min(step+1,path.size()-1);
        Point next=path.get(nextIndex);
        boolean nextOpen=available(next,nextIndex==path.size()-1);
        var team=realm.crews.team(home,crew);
        if(team==null){
            // The world and the SQLite cursor may have different last save times after power loss.
            // Locate the original party along its short registered line before attempting to resume.
            for(var point:path){
                if(!w.isChunkLoaded(point.x()>>4,point.z()>>4))w.loadChunk(point.x()>>4,point.z()>>4,false);
            }
            team=realm.crews.team(home,crew);
        }
        if(team==null){status(id,"等候队伍所在区块");return;}
        if(!nextOpen || !realm.roads.traversable(current,next)){team.halt();status(id,"道路中断");return;}
        if(!team.near(realm.roads.footing(next),.42)){
            if(team.porter().getLocation().distanceSquared(realm.roads.footing(current))>5){
                team.halt();
                for(int i=0;i<path.size();i++)if(team.near(realm.roads.footing(path.get(i)),.75)){
                    s.update("UPDATE migration SET step=? WHERE id=?",i,id);return;
                }
                status(id,"队伍偏离驿道");return;
            }
            try{realm.clearance(next,crew,team);}catch(IllegalArgumentException ex){team.halt();status(id,"等候路费");return;}
            team.onRoad(realm.roads.footing(next),realm.roads.footing(next),realm.roads.pace(next));status(id,"途中");return;
        }
        if(nextIndex<path.size()-1){s.update("UPDATE migration SET step=? WHERE id=?",nextIndex,id);return;}
        team.halt();
        if(realm.manors.capacity(realm.manors.register.manor(dest))-
                realm.manors.register.manor(dest).residents()<people){status(id,"到站等候修复住房");return;}
        s.atomic(()->{
            if(s.update("UPDATE migration SET arrived=1,status='抵达',step=? WHERE id=? AND arrived=0",nextIndex,id)==1)
                s.update("UPDATE manor SET residents=residents+? WHERE id=?",people,dest);
            return null;
        });
        team.porter().customName(Component.text("已安置居民 · "+people+" 人"));
        // Return remaining fare at the actual destination, where the host can collect it.
        var purse=team.porter().getEquipment().getItemInOffHand();
        if(!purse.isEmpty())w.dropItem(team.porter().getLocation(),purse);
        team.porter().remove();realm.changed();
    }
}
