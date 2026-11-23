package social.feudal.realm;

import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** Every quote belongs to a physical player stall. Indexed orders follow settled fixed-price trades. */
final class MarketBids {
    static final int PAGE_SIZE=4;
    record Offer(int id,String stall,String name,int x,int y,int z,String goods,int lot,int price,
                 TollCoin currency,int remaining,boolean ask,boolean floating,int limit,int samples){
        String kind(){return ask?"出售":"收购";}
        String action(){return ask?"buy":"sell";}
        String deal(){return "realm "+action()+" "+id+" "+price;}
        String quote(){return kind()+(floating?"（随市）":"")+" "+goods+" ×"+lot+"　每批 "+price+" "+currency.label+"　余 "+remaining+" 批";}
        String terms(){
            if(!floating)return "业主定价";
            return "随全服成交浮动 · "+(ask?"最低":"最高")+limit+" "+currency.label+
                    (samples==0?" · 暂无成交，采用业主起价":" · 参考 "+samples+" 对交易者");
        }
    }
    void withdraw(int id,String owner)throws SQLException{
        RealmStore.require(s.update("""
            UPDATE bid SET remaining=0 WHERE id=? AND remaining>0
            AND stall IN (SELECT id FROM stall WHERE owner=? AND x IS NOT NULL)""",id,owner)==1,"只有现任业主能撤下尚有余批的订单");
    }
    record Page(List<Offer> offers,boolean more){}
    private final RealmStore s;

    MarketBids(RealmStore s)throws SQLException{
        this.s=s;
        s.update("CREATE TABLE IF NOT EXISTS stall(id TEXT PRIMARY KEY,owner TEXT,name TEXT,x INTEGER,y INTEGER,z INTEGER,UNIQUE(x,y,z))");
        s.update("CREATE TABLE IF NOT EXISTS bid(id INTEGER PRIMARY KEY,stall TEXT REFERENCES stall(id),goods TEXT,lot INTEGER,price INTEGER,remaining INTEGER)");
        if(s.number("SELECT count(*) FROM pragma_table_info('bid') WHERE name='currency'")==0)
            s.update("ALTER TABLE bid ADD COLUMN currency TEXT NOT NULL DEFAULT 'GOLD'");
        if(s.number("SELECT count(*) FROM pragma_table_info('bid') WHERE name='ask'")==0)
            s.update("ALTER TABLE bid ADD COLUMN ask INTEGER NOT NULL DEFAULT 0");
        if(s.number("SELECT count(*) FROM pragma_table_info('bid') WHERE name='floating'")==0)
            s.update("ALTER TABLE bid ADD COLUMN floating INTEGER NOT NULL DEFAULT 0");
        s.update("CREATE TABLE IF NOT EXISTS trade(id TEXT PRIMARY KEY,bid INTEGER REFERENCES bid(id),seller TEXT NOT NULL)");
        if(s.number("SELECT count(*) FROM pragma_table_info('trade') WHERE name='paid'")==0)
            s.update("ALTER TABLE trade ADD COLUMN paid INTEGER");
        if(s.number("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='market_sample'")==0)
            installReference();
    }

    private void installReference()throws SQLException{
        // Keep at most 31 independent counterparties per commodity, even on a long-running server.
        s.atomic(()->{
            s.update("CREATE TABLE market_sample(goods TEXT,a TEXT,b TEXT,seq INTEGER,unit REAL,PRIMARY KEY(goods,a,b))");
            s.update("CREATE INDEX market_sample_recent ON market_sample(goods,seq DESC)");
            String sales="""
                    SELECT b.goods,min(m.owner,t.seller) AS a,max(m.owner,t.seller) AS b,t.rowid AS seq,
                           coalesce(t.paid,b.price)*1.0*(CASE b.currency WHEN 'GOLD' THEN %d ELSE 1 END)/b.lot AS unit
                    FROM trade t JOIN bid b ON b.id=t.bid JOIN stall m ON m.id=b.stall
                    WHERE b.floating=0 AND t.seller<>m.owner
                    """.formatted(TollCoin.SILVER_PER_GOLD);
            s.update("WITH sales AS ("+sales+"""
                    ), pairs AS (
                       SELECT *, row_number() OVER (PARTITION BY goods,a,b ORDER BY seq DESC) AS n FROM sales
                    ), recent AS (
                       SELECT *, row_number() OVER (PARTITION BY goods ORDER BY seq DESC) AS age FROM pairs WHERE n=1
                    ) INSERT INTO market_sample SELECT goods,a,b,seq,unit FROM recent WHERE age<=31""");
            s.update("""
                    CREATE TRIGGER sample_fixed_trade AFTER INSERT ON trade
                    WHEN (SELECT floating FROM bid WHERE id=NEW.bid)=0 BEGIN
                       INSERT INTO market_sample(goods,a,b,seq,unit)
                    """+sales+"""
                       AND t.rowid=NEW.rowid ON CONFLICT(goods,a,b) DO UPDATE SET seq=excluded.seq,unit=excluded.unit;
                       DELETE FROM market_sample WHERE goods=(SELECT goods FROM bid WHERE id=NEW.bid) AND seq IN (
                          SELECT seq FROM market_sample WHERE goods=(SELECT goods FROM bid WHERE id=NEW.bid)
                          ORDER BY seq DESC LIMIT -1 OFFSET 31);
                    END""");
            s.update("DROP VIEW IF EXISTS market_reference");
            s.update("""
                    CREATE VIEW market_reference AS WITH ranked AS (
                       SELECT goods,unit,row_number() OVER (PARTITION BY goods ORDER BY unit,seq) AS n,
                              count(*) OVER (PARTITION BY goods) AS cnt FROM market_sample
                    ) SELECT goods,avg(unit) AS silver,max(cnt) AS samples FROM ranked
                    WHERE n BETWEEN (cnt+1)/2 AND (cnt+2)/2 GROUP BY goods""");
            return null;
        });
    }

    private static final String PRICED="""
            WITH amounts AS (
                SELECT b.*,t.name,t.x,t.y,t.z,ref.samples,
                       min(64,max(1,cast(round(ref.silver*b.lot/(CASE b.currency WHEN 'GOLD' THEN %d ELSE 1 END)) AS INTEGER))) AS indexed
                FROM bid b JOIN stall t ON b.stall=t.id LEFT JOIN market_reference ref ON b.goods=ref.goods
                WHERE t.x IS NOT NULL
            ), prices AS (
                SELECT *, CASE WHEN floating=0 OR indexed IS NULL THEN price
                    WHEN ask=1 THEN max(price,indexed) ELSE min(price,indexed) END AS payable FROM amounts
            ) SELECT id,stall,name,x,y,z,goods,lot,price,currency,remaining,ask,floating,payable,samples FROM prices
            """.formatted(TollCoin.SILVER_PER_GOLD);

    private List<Offer> offers(String selection,Object... args)throws SQLException{
        var result=new ArrayList<Offer>();
        try(var q=s.sql(PRICED+selection,args);var r=q.executeQuery()){
            while(r.next())result.add(read(r));
        }
        return result;
    }
    private static Offer read(ResultSet r)throws SQLException{
        int lot=r.getInt(8),limit=r.getInt(9);
        var currency=TollCoin.parse(r.getString(10));
        boolean ask=r.getBoolean(12),floating=r.getBoolean(13);
        return new Offer(r.getInt(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5),r.getInt(6),
                r.getString(7),lot,r.getInt(14),currency,
                r.getInt(11),ask,floating,limit,r.getInt(15));
    }
    Offer offer(int id)throws SQLException{
        var entries=offers("WHERE id=?",id);
        RealmStore.require(!entries.isEmpty(),"商站已撤或订单未登记");
        return entries.getFirst();
    }
    Page listed(String stall,String goods,int page)throws SQLException{
        RealmStore.require(page>=1,"页码从一开始");
        var entries=offers("""
                WHERE remaining>0 AND (? IS NULL OR stall=?) AND (? IS NULL OR goods=?)
                ORDER BY goods,ask,(CASE ask WHEN 1 THEN 1.0 ELSE -1.0 END)*payable*
                         (CASE currency WHEN 'GOLD' THEN ? ELSE 1 END)/lot,id
                LIMIT ? OFFSET ?""",stall,stall,goods,goods,TollCoin.SILVER_PER_GOLD,PAGE_SIZE+1,(long)(page-1)*PAGE_SIZE);
        RealmStore.require(page==1 || !entries.isEmpty(),"没有这一页报价");
        boolean more=entries.size()>PAGE_SIZE;
        if(more)entries.removeLast();
        return new Page(List.copyOf(entries),more);
    }
}
