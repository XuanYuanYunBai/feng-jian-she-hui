package social.feudal.realm;

import java.sql.SQLException;
import social.feudal.transport.HaulNetwork.Point;
import static social.feudal.realm.RealmStore.require;

/** One physical defect, one purchase of a forged brace, and an independent site inspection. */
final class RoadWorkBook {
    record Order(int id,String title,String route,Point point,int bid,String stall,String sponsor,int price,
                 String currency,String supplier,int paid,String result,String inspector) {
        String state(){
            if(result!=null)return result;
            if(bid==0)return "待筹款";
            return supplier==null?"收购中":"已购料待验";
        }
    }
    private final RealmStore s;
    RoadWorkBook(RealmStore s)throws SQLException{
        this.s=s;
        s.update("""
            CREATE TABLE IF NOT EXISTS road_work(id INTEGER PRIMARY KEY,title TEXT NOT NULL REFERENCES title(id),
              route TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,
              bid INTEGER UNIQUE REFERENCES bid(id),sponsor TEXT,result TEXT,inspector TEXT)""");
        s.update("CREATE UNIQUE INDEX IF NOT EXISTS road_work_gap ON road_work(x,y,z) WHERE result IS NULL");
        s.update("""
            CREATE TABLE IF NOT EXISTS road_work_material(work INTEGER NOT NULL REFERENCES road_work(id),
                fitter TEXT NOT NULL,plank TEXT NOT NULL,season INTEGER NOT NULL,uses INTEGER NOT NULL,
                PRIMARY KEY(work,fitter,plank,season))""");
        // Market recovery writes the physical sale receipt before decreasing the remaining quantity.
        s.update("""
            CREATE TRIGGER IF NOT EXISTS road_work_withdraw AFTER UPDATE OF remaining ON bid
            WHEN NEW.remaining=0 AND NOT EXISTS(SELECT 1 FROM trade WHERE bid=NEW.id)
            BEGIN UPDATE road_work SET result='已撤单' WHERE bid=NEW.id AND result IS NULL; END""");
    }
    int survey(String title,String route,Point p)throws SQLException{
        int prior=s.number("SELECT id FROM road_work WHERE x=? AND y=? AND z=? AND result IS NULL",p.x(),p.y(),p.z());
        if(prior>0)return prior;
        s.update("INSERT INTO road_work(title,route,x,y,z) VALUES(?,?,?,?,?)",title,route,p.x(),p.y(),p.z());
        return s.number("SELECT last_insert_rowid()");
    }
    Order get(int id)throws SQLException{
        try(var q=s.sql("""
                SELECT w.*,b.stall,b.price,b.currency,t.seller,t.paid FROM road_work w LEFT JOIN bid b ON b.id=w.bid
                LEFT JOIN trade t ON t.bid=b.id WHERE w.id=?""",id);var r=q.executeQuery()){
            require(r.next(),"查无这项养路工程");
            return new Order(id,r.getString("title"),r.getString("route"),new Point(r.getInt("x"),r.getInt("y"),r.getInt("z")),
                    r.getInt("bid"),r.getString("stall"),r.getString("sponsor"),r.getInt("price"),r.getString("currency"),
                    r.getString("seller"),r.getInt("paid"),r.getString("result"),r.getString("inspector"));
        }
    }
    void attach(int id,int bid,String actor)throws SQLException{
        require(s.update("UPDATE road_work SET bid=?,sponsor=? WHERE id=? AND bid IS NULL AND result IS NULL",bid,actor,id)==1,
                "本案已筹款或已结案");
    }
    void close(int id,String actor,boolean accepted)throws SQLException{
        var o=get(id);require(o.result()==null,"本案已经结清");
        if(accepted)require(o.bid()==0 || o.supplier()!=null,"请先完成已开采购单的实物交割");
        else require(o.bid()==0 || o.supplier()!=null,"请先到出资柜撤下未成交的订单");
        s.update("UPDATE road_work SET result=?,inspector=? WHERE id=?",accepted?(o.bid()==0?"自备料验通":"现场验通"):"终止工程",actor,id);
    }
}
