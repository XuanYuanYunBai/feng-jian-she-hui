package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

final class FiscalLedger {
    record Account(String depot,int period,String title,int due,int automatic,int packed) {
        int remaining(){return due-automatic-packed;}
    }
    record Crate(String id,String depot,String title,int amount,String carrier,boolean received){}
    private final RealmStore s;
    FiscalLedger(RealmStore store)throws SQLException{
        s=store;
        s.update("""
            CREATE TABLE IF NOT EXISTS assessment(depot TEXT REFERENCES depot(id),period INTEGER,title TEXT REFERENCES title(id),
            due INTEGER NOT NULL,automatic INTEGER NOT NULL DEFAULT 0,packed INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(depot,period),CHECK(automatic>=0 AND packed>=0 AND automatic+packed<=due))""");
        s.update("""
            CREATE TABLE IF NOT EXISTS treasury(title TEXT PRIMARY KEY REFERENCES title(id),
            grain INTEGER NOT NULL CHECK(grain>=0))""");
        s.update("""
            CREATE TABLE IF NOT EXISTS crate(id TEXT PRIMARY KEY,depot TEXT NOT NULL,period INTEGER NOT NULL,
            title TEXT NOT NULL REFERENCES title(id),amount INTEGER NOT NULL CHECK(amount>0),carrier TEXT NOT NULL,
            received INTEGER NOT NULL DEFAULT 0,FOREIGN KEY(depot,period) REFERENCES assessment(depot,period))""");
        s.update("CREATE TABLE IF NOT EXISTS tax_right(depot TEXT PRIMARY KEY REFERENCES depot(id),title TEXT REFERENCES title(id))");
    }
    static int rate(int steps){return Math.max(0,100-25*Math.max(0,Math.min(steps,7)-3));}
    String right(RealmStore.Depot d)throws SQLException{
        String explicit=s.value("SELECT title FROM tax_right WHERE depot=?",d.id());
        return explicit==null?s.owner(d.x(),d.z()):explicit;
    }
    int period()throws SQLException{return Math.max(1,s.number("SELECT v FROM setting WHERE k='period'"));}
    void begin(int period)throws SQLException{
        s.atomic(()->{
            require(period==period() || period==period()+1,"税期须连续");
            for(var d:s.depots()){
                String title=right(d);
                if(s.update("INSERT OR IGNORE INTO assessment(depot,period,title,due) VALUES(?,?,?,?)",
                        d.id(),period,title,d.yield())==0)continue;
                var seat=s.title(title);
                int steps=Math.abs(seat.x()-d.x())+Math.abs(seat.z()-d.z());
                int amount=d.yield()*rate(steps)/100;
                s.update("UPDATE assessment SET automatic=? WHERE depot=? AND period=?",amount,d.id(),period);
                credit(title,amount);
            }
            s.update("INSERT OR REPLACE INTO setting VALUES('period',?)",period);
            return null;
        });
    }
    private void credit(String title,int delta)throws SQLException{
        s.update("INSERT INTO treasury VALUES(?,?) ON CONFLICT(title) DO UPDATE SET grain=grain+excluded.grain",title,delta);
    }
    int balance(String title)throws SQLException{return s.number("SELECT grain FROM treasury WHERE title=?",title);}
    Account account(String depot,int period)throws SQLException{
        try(var q=s.sql("SELECT * FROM assessment WHERE depot=? AND period=?",depot,period);var r=q.executeQuery()){
            require(r.next(),"该税仓在本期没有应税记录");
            return new Account(r.getString(1),r.getInt(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6));
        }
    }
    RealmStore.Depot depot(String id)throws SQLException{
        return s.depots().stream().filter(d->d.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("未知税仓"));
    }
    Crate crate(String id)throws SQLException{
        try(var q=s.sql("SELECT id,depot,title,amount,carrier,received FROM crate WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"无此税箱");return new Crate(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getString(5),r.getBoolean(6));
        }
    }
    Crate collect(String depot,int period,String actor)throws SQLException{
        return s.atomic(()->{
            var a=account(depot,period);
            String recipient=right(depot(depot));
            require(s.title(recipient).seal().equals(actor),"当地持印人掌握该税权");
            require(a.remaining()>0,"本期税款已结清");
            String id=UUID.randomUUID().toString();
            s.update("INSERT INTO crate(id,depot,period,title,amount,carrier) VALUES(?,?,?,?,?,?)",
                    id,depot,period,recipient,a.remaining(),actor);
            s.update("UPDATE assessment SET packed=packed+? WHERE depot=? AND period=?",a.remaining(),depot,period);
            return crate(id);
        });
    }
    Crate outstanding(String depot,int period,String actor)throws SQLException{
        String id=s.value("SELECT id FROM crate WHERE depot=? AND period=? AND carrier=? AND received=0",depot,period,actor);
        require(id!=null,"没有可补领的在途税箱");return crate(id);
    }
    int receive(String id)throws SQLException{
        return s.atomic(()->{
            var c=crate(id);require(!c.received(),"此箱已入库");
            s.update("UPDATE crate SET received=1 WHERE id=?",id);credit(c.title(),c.amount());return c.amount();
        });
    }
    void gift(String title,String actor,int amount)throws SQLException{
        s.atomic(()->{
            var t=s.title(title);require(t.seal().equals(actor),"须由当地持印人决定赠纳");
            require(t.parent()!=null && amount>0,"须向直属上级赠纳正数粮食");
            require(s.update("UPDATE treasury SET grain=grain-? WHERE title=? AND grain>=?",amount,title,amount)==1,"公库余粮不足");
            credit(t.parent(),amount);return null;
        });
    }
}
