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
    private final int chunksPerStep;
    FiscalLedger(RealmStore store)throws SQLException{this(store,1);}
    FiscalLedger(RealmStore store,int chunksPerStep)throws SQLException{
        require(chunksPerStep>=1 && chunksPerStep<=64,"税距步长须为 1–64 区块");
        this.chunksPerStep=chunksPerStep;
        s=store;
        s.update("CREATE TABLE IF NOT EXISTS tendered_tax(depot TEXT,period INTEGER,PRIMARY KEY(depot,period))");
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
        s.update("CREATE TABLE IF NOT EXISTS quota(depot TEXT,period INTEGER,amount INTEGER NOT NULL,PRIMARY KEY(depot,period))");
        s.update("CREATE TABLE IF NOT EXISTS parcel(id TEXT PRIMARY KEY REFERENCES crate(id),automatic INTEGER NOT NULL,staged INTEGER NOT NULL DEFAULT 0)");
        s.update("CREATE TABLE IF NOT EXISTS visit(title TEXT REFERENCES title(id),period INTEGER,grain INTEGER NOT NULL,PRIMARY KEY(title,period))");
        s.update("CREATE TABLE IF NOT EXISTS tax_right(depot TEXT PRIMARY KEY REFERENCES depot(id),title TEXT REFERENCES title(id))");
    }
    static int rate(int steps){return Math.max(0,100-25*Math.max(0,Math.min(steps,7)-3));}
    String right(RealmStore.Depot d)throws SQLException{
        String explicit=s.value("SELECT title FROM tax_right WHERE depot=?",d.id());
        return explicit==null?s.owner(d.x(),d.z()):explicit;
    }
    String collectRight(RealmStore.Depot d,String actor)throws SQLException{
        String title=right(d);
        require(s.title(title).seal().equals(actor),"请由当地税权持印人提运");
        return title;
    }
    int period()throws SQLException{return Math.max(1,s.number("SELECT v FROM setting WHERE k='period'"));}
    void begin(int period)throws SQLException{begin(period,null);}
    void begin(int period,Map<String,Integer> tendered)throws SQLException{
        s.atomic(()->{
            require(period==period() || period==period()+1,"税期须连续");
            if(period>period())s.update("INSERT OR REPLACE INTO setting VALUES('seconds',0)");
            for(var d:s.depots()){
                if(s.number("SELECT v FROM setting WHERE k=?","first:"+d.id())>period)continue;
                int due=tendered==null?d.yield():tendered.getOrDefault(d.id(),0);
                if(due==0)continue;
                String title=right(d);
                if(s.update("INSERT OR IGNORE INTO assessment(depot,period,title,due) VALUES(?,?,?,?)",
                        d.id(),period,title,due)==0)continue;
                var seat=s.title(title);
                int steps=Math.abs(seat.x()-d.x())+Math.abs(seat.z()-d.z());
                int amount=(int)((long)due*rate(Math.ceilDiv(steps,chunksPerStep))/100);
                s.update("INSERT INTO quota VALUES(?,?,?)",d.id(),period,amount);
                if(tendered!=null)s.update("INSERT INTO tendered_tax VALUES(?,?)",d.id(),period);
            }
            s.update("INSERT OR REPLACE INTO setting VALUES('period',?)",period);
            return null;
        });
    }
    int quota(String depot,int period)throws SQLException{return s.number("SELECT amount FROM quota WHERE depot=? AND period=?",depot,period);}
    int legacy(String title)throws SQLException{return s.number("SELECT grain FROM treasury WHERE title=?",title);}
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
    // A manifest is created once. A separate receipt on the source chunk confirms physical stocking.
    void prepare(RealmStore.Depot d,int period)throws SQLException{
        s.atomic(()->{
            var a=account(d.id(),period);
            if(a.remaining()==0)return null;
            String title=right(d);
            int auto=Math.min(a.remaining(),quota(d.id(),period));
            for(boolean courier:List.of(true,false)){
                int amount=courier?auto:a.remaining()-auto;
                while(amount>0){
                    // Eight is the base foot load. A pack or pack animal can carry several sealed lots.
                    int load=courier?Math.min(8,amount):Math.min(64,amount);
                    String id=UUID.randomUUID().toString();
                    s.update("INSERT INTO crate(id,depot,period,title,amount,carrier) VALUES(?,?,?,?,?,'warehouse')",id,d.id(),period,title,load);
                    s.update("INSERT INTO parcel(id,automatic) VALUES(?,?)",id,courier?1:0);
                    amount-=load;
                }
            }
            s.update("UPDATE assessment SET automatic=automatic+?,packed=packed+? WHERE depot=? AND period=?",auto,a.remaining()-auto,d.id(),period);
            return null;
        });
    }
    List<String> pending(String depot)throws SQLException{
        var ids=new ArrayList<String>();
        try(var q=s.sql("SELECT crate.id FROM crate JOIN parcel USING(id) WHERE depot=? AND staged=0 ORDER BY crate.rowid",depot);var r=q.executeQuery()){
            while(r.next())ids.add(r.getString(1));
        }
        return ids;
    }
    int receive(String id)throws SQLException{
        var c=crate(id);
        require(s.update("UPDATE crate SET received=1 WHERE id=? AND received=0",id)==1,"此箱已入库");
        return c.amount();
    }
    int visit(String title,String actor,int localSupport,int stock)throws SQLException{
        var t=s.title(title);
        require(t.parent()!=null && s.title(t.parent()).seal().equals(actor),"只有直属上级持印人能请求赠纳");
        require(!Succession.isPlayer(t.seal()),"请由玩家持印人自行决定赠纳");
        int amount=t.lawful().equals(t.seal())?Math.min(Math.max(0,stock-12),Math.max(0,4-localSupport)):0;
        require(s.update("INSERT OR IGNORE INTO visit VALUES(?,?,?)",title,period(),amount)==1,"本期已经答复过请求");
        return amount;
    }
}
