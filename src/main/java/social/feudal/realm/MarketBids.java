package social.feudal.realm;

import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Published bids are independent of chunk loading. The actual till is checked at settlement. */
final class MarketBids {
    static final int PAGE_SIZE=4;
    record Offer(int id,String stall,String name,int x,int y,int z,String goods,int lot,int price,
                 TollCoin currency,int remaining){
        String quote(){return goods+" ×"+lot+"　每批 "+price+" "+currency.label+"　余 "+remaining+" 批";}
    }
    record Page(List<Offer> offers,boolean more){}
    private final RealmStore s;

    MarketBids(RealmStore s)throws SQLException{
        this.s=s;
        s.update("CREATE TABLE IF NOT EXISTS stall(id TEXT PRIMARY KEY,owner TEXT,name TEXT,x INTEGER,y INTEGER,z INTEGER,UNIQUE(x,y,z))");
        s.update("CREATE TABLE IF NOT EXISTS bid(id INTEGER PRIMARY KEY,stall TEXT REFERENCES stall(id),goods TEXT,lot INTEGER,price INTEGER,remaining INTEGER)");
        if(s.number("SELECT count(*) FROM pragma_table_info('bid') WHERE name='currency'")==0)
            s.update("ALTER TABLE bid ADD COLUMN currency TEXT NOT NULL DEFAULT 'GOLD'");
        s.update("CREATE TABLE IF NOT EXISTS trade(id TEXT PRIMARY KEY,bid INTEGER REFERENCES bid(id),seller TEXT NOT NULL)");
    }
    private static Offer read(ResultSet r)throws SQLException{
        return new Offer(r.getInt(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6),
                r.getString(7),r.getInt(8),r.getInt(9),TollCoin.parse(r.getString(10)),r.getInt(11));
    }
    private static final String SELECT_OFFER="""
            SELECT b.id,t.id,t.name,t.x,t.y,t.z,b.goods,b.lot,b.price,b.currency,b.remaining
            FROM bid b JOIN stall t ON t.id=b.stall WHERE t.x IS NOT NULL
            """;
    Offer offer(int id)throws SQLException{
        try(var q=s.sql(SELECT_OFFER+" AND b.id=?",id);var r=q.executeQuery()){
            RealmStore.require(r.next(),"商站已撤或收购单未登记");
            return read(r);
        }
    }
    Page listed(String stall,String goods,int page)throws SQLException{
        RealmStore.require(page>=1,"页码从一开始");
        var offers=new ArrayList<Offer>();
        try(var q=s.sql(SELECT_OFFER+"""
                AND b.remaining>0 AND (? IS NULL OR t.id=?) AND (? IS NULL OR b.goods=?)
                ORDER BY b.goods,1.0*b.price*(CASE b.currency WHEN 'GOLD' THEN ? ELSE 1 END)/b.lot DESC,b.id
                LIMIT ? OFFSET ?""",stall,stall,goods,goods,TollCoin.SILVER_PER_GOLD,PAGE_SIZE+1,(long)(page-1)*PAGE_SIZE);
                var r=q.executeQuery()){
            while(r.next())offers.add(read(r));
        }
        boolean more=offers.size()>PAGE_SIZE;
        if(more)offers.removeLast();
        RealmStore.require(page==1 || !offers.isEmpty(),"没有这一页报价");
        return new Page(offers,more);
    }
}
