package social.feudal.realm;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

/** A pledge reserves no virtual grain. Only a signed, delivered freight receipt can discharge it. */
final class Contributions {
    final RealmStore s;
    final Path freight;
    Contributions(RealmStore s,Path freight)throws SQLException{
        this.s=s;this.freight=freight;
        s.update("""
            CREATE TABLE IF NOT EXISTS contribution(id INTEGER PRIMARY KEY,org TEXT REFERENCES association(id),
            nation TEXT REFERENCES nation(id),route TEXT NOT NULL,goods TEXT NOT NULL,quantity INTEGER NOT NULL,
            due INTEGER NOT NULL,promised TEXT NOT NULL,received INTEGER NOT NULL DEFAULT 0)""");
        s.update("CREATE TABLE IF NOT EXISTS contribution_receipt(trip TEXT PRIMARY KEY,pledge INTEGER REFERENCES contribution(id),amount INTEGER NOT NULL)");
    }
    private Connection manifest()throws SQLException{
        require(Files.isRegularFile(freight),"货运账本尚未开放");
        return DriverManager.getConnection("jdbc:sqlite:"+freight.toUri()+"?mode=ro");
    }
    int pledge(String org,String country,String actor,String route,String goods,int amount,int due)throws SQLException{
        s.politics.active(org);s.politics.represents(country,actor);
        require(s.politics.member(org,country),"本国不是成员");
        require(Set.of("WHEAT","OAK_LOG","WHITE_WOOL").contains(goods) && amount>0 && amount<=4096,"请承诺 1–4096 件小麦、橡木或白羊毛");
        require(due>=s.number("SELECT v FROM setting WHERE k='season'"),"交期已过");
        try(var db=manifest();var q=db.prepareStatement("SELECT world,dx,dz FROM routes WHERE name=?")){
            q.setString(1,route);try(var r=q.executeQuery()){
                require(r.next(),"请先登记实体货线");
                require(r.getString(1).equals(s.value("SELECT v FROM setting WHERE k='world'")),"货线属于另一世界");
                var owner=s.owner(r.getInt(2)>>4,r.getInt(3)>>4);
                require(owner!=null && (s.politics.member(org,s.politics.nation(owner)) ||
                        org.equals(Politics.EMPIRE) && s.politics.nation(owner)==null),"目的仓须在本组织范围内");
            }
        }
        return s.atomic(()->{
            s.update("INSERT INTO contribution(org,nation,route,goods,quantity,due,promised) VALUES(?,?,?,?,?,?,strftime('%Y-%m-%dT%H:%M:%fZ','now'))",
                    org,country,route,goods,amount,due);
            int n=s.number("SELECT last_insert_rowid()");s.politics.log(org,country,"认缴 #"+n+" · "+amount+" "+goods);return n;
        });
    }
    void credit(int id,String actor,String trip)throws SQLException{
        try(var db=manifest();var q=db.prepareStatement("SELECT route,material,amount,phase,sender,signer,delivered_at FROM trips WHERE id=?")){
            q.setString(1,trip);
            try(var r=q.executeQuery()){
                require(r.next() && "DELIVERED".equals(r.getString("phase")) && r.getString("signer")!=null,"货物尚未实际签收");
                String route=r.getString("route"),goods=r.getString("material"),sender=r.getString("sender"),time=r.getString("delivered_at");
                int amount=r.getInt("amount");
                s.atomic(()->{
                    String country=s.value("SELECT nation FROM contribution WHERE id=?",id);
                    require(country!=null,"没有这份认缴");s.politics.represents(country,actor);
                    require(sender.equals(actor),"须由本国持印人承运或发货");
                    require(s.value("SELECT trip FROM contribution_receipt WHERE trip=?",trip)==null,"本运单已入账");
                    require(s.update("""
                        UPDATE contribution SET received=received+? WHERE id=? AND route=? AND goods=?
                        AND received+?<=quantity AND julianday(?)>=julianday(promised)""",amount,id,route,goods,amount,time)==1,"货线、货种、签收时间或余额不符");
                    s.update("INSERT INTO contribution_receipt VALUES(?,?,?)",trip,id,amount);
                    s.politics.log(s.value("SELECT org FROM contribution WHERE id=?",id),country,"货单 "+trip+" 冲抵 #"+id+" 共 "+amount+" 件");return null;
                });
            }
        }
    }
}
