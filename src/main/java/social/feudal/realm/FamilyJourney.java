package social.feudal.realm;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerTeleportEvent;
import java.sql.SQLException;
import java.util.logging.Level;
import static social.feudal.realm.RealmStore.require;

/** Named dependants travel with the current player. Population moves at departure and arrival. */
final class FamilyJourney implements Listener {
    private final FamilyOffice office;
    private final RealmStore s;
    FamilyJourney(FamilyOffice office)throws SQLException{
        this.office=office;s=office.book.s;
        s.update("CREATE TABLE IF NOT EXISTS family_journey(person INTEGER PRIMARY KEY REFERENCES kin(id),family INTEGER UNIQUE REFERENCES family(id),source TEXT,dest TEXT,escort INTEGER REFERENCES kin(id),x INTEGER,y INTEGER,z INTEGER)");
        Bukkit.getPluginManager().registerEvents(this,office.realm.plugin);
        Bukkit.getScheduler().runTaskTimer(office.realm.plugin,this::positions,20,20);
    }
    private void positions(){
        try(var q=s.sql("SELECT j.person,f.account FROM family_journey j JOIN family f ON f.id=j.family AND f.rep=j.escort");var r=q.executeQuery()){
            while(r.next()){
                Player p=Bukkit.getPlayer(java.util.UUID.fromString(r.getString(2)));
                if(p!=null && p.getWorld()==office.realm.world && !p.isDead()){
                    var l=p.getLocation();s.update("UPDATE family_journey SET x=?,y=?,z=? WHERE person=? AND (x<>? OR y<>? OR z<>?)",l.getBlockX(),l.getBlockY(),l.getBlockZ(),r.getInt(1),l.getBlockX(),l.getBlockY(),l.getBlockZ());
                }
            }
        }catch(SQLException ex){office.realm.plugin.getLogger().log(Level.WARNING,"陪同亲属位置待复核",ex);}
    }
    @EventHandler(ignoreCancelled=true) public void teleport(PlayerTeleportEvent e)throws SQLException{
        if(e.getFrom().getWorld()!=office.realm.world)return;
        if(s.number("SELECT count(*) FROM family_journey j JOIN family f ON f.id=j.family AND f.rep=j.escort WHERE f.account=?",e.getPlayer().getUniqueId().toString())>0){
            e.setCancelled(true);e.getPlayer().sendMessage("陪同亲属途中请步行过桥或前往渡口乘船。");
        }
    }
    private void hall(Player p,String home)throws SQLException{
        var m=office.realm.manors.register.manor(home);
        require(p.getLocation().distanceSquared(new Location(office.realm.world,m.x()+.5,m.y()+.5,m.z()+.5))<=64,"须在该庄厅位八格内办理");
    }
    void depart(Player p,int f,int id,String dest)throws SQLException{
        var book=office.book;book.own(f,id);var k=book.person(id);
        require(id!=book.rep(f) && k.home()!=null && !k.home().equals(dest),"请选择有居所的另一名族人");
        hall(p,k.home());
        require(office.livelihood.canStay(f,dest) || s.number("SELECT count(*) FROM family_match WHERE (a=? OR b=?) AND home=? AND state='已婚'",id,id,dest)>0,"目的地须有寄居许可、租约或婚约");
        require(s.number("SELECT housing-residents FROM manor WHERE id=?",dest)>0,"目的庄没有空位");
        require(s.number("SELECT count(*) FROM family_journey WHERE family=?",f)==0,"请先办结上一趟");
        var l=p.getLocation();s.atomic(()->{
            require(s.update("UPDATE manor SET residents=residents-1 WHERE id=? AND residents>0",k.home())==1,"本庄人口待核");
            s.update("INSERT INTO family_journey VALUES(?,?,?,?,?,?,?,?)",id,f,k.home(),dest,book.rep(f),l.getBlockX(),l.getBlockY(),l.getBlockZ());
            s.update("UPDATE kin SET home=NULL WHERE id=?",id);return null;
        });
    }
    void resume(Player p,int f)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM family_journey WHERE family=?",f);var r=q.executeQuery()){
            require(r.next(),"本家没有在途者");
            require(p.getLocation().distanceSquared(new Location(office.realm.world,r.getInt(1)+.5,r.getInt(2),r.getInt(3)+.5))<=36,"请到亲属等候地点六格内");
        }
        s.update("UPDATE family_journey SET escort=? WHERE family=?",office.book.rep(f),f);
    }
    void arrive(Player p,int f,boolean back)throws SQLException{
        int escort=office.book.rep(f);String home=s.value("SELECT "+(back?"source":"dest")+" FROM family_journey WHERE family=? AND escort=?",f,escort);
        require(home!=null,"须先在等候处接回在途成员");hall(p,home);
        s.atomic(()->{
            require(s.update("UPDATE manor SET residents=residents+1 WHERE id=? AND (housing>residents OR ?=1)",home,back?1:0)==1,"目的庄已满，可护送返乡");
            s.update("UPDATE kin SET home=? WHERE id=(SELECT person FROM family_journey WHERE family=?)",home,f);
            s.update("DELETE FROM family_journey WHERE family=?",f);return null;
        });
    }
}
