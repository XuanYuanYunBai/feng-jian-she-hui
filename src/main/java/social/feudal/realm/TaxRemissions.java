package social.feudal.realm;

import java.sql.ResultSet;
import java.sql.SQLException;
import static social.feudal.realm.RealmStore.require;

/** One season of a depot's wheat income can be remitted by its two actual rights holders. */
final class TaxRemissions {
    record Writ(int id, int relief, String depot, int period, int percent, String land, String right,
                String issuer, String signer, String state, int spared) {}
    private final FiscalLedger fiscal;
    private final RealmStore s;

    TaxRemissions(RealmStore store,FiscalLedger fiscal) throws SQLException {
        this.s=store; this.fiscal=fiscal;
        s.update("""
            CREATE TABLE IF NOT EXISTS tax_remission(id INTEGER PRIMARY KEY, relief INTEGER NOT NULL REFERENCES relief(id),
            depot TEXT NOT NULL REFERENCES depot(id), period INTEGER NOT NULL, percent INTEGER NOT NULL CHECK(percent BETWEEN 1 AND 100),
            land TEXT NOT NULL REFERENCES title(id), beneficiary TEXT NOT NULL REFERENCES title(id), issuer TEXT NOT NULL,
            signer TEXT, state TEXT NOT NULL, spared INTEGER NOT NULL DEFAULT 0 CHECK(spared>=0))""");
        s.update("CREATE UNIQUE INDEX IF NOT EXISTS active_tax_remission ON tax_remission(depot,period) WHERE state<>'已撤回'");
    }

    private static Writ read(ResultSet r)throws SQLException {
        return new Writ(r.getInt("id"),r.getInt("relief"),r.getString("depot"),r.getInt("period"),r.getInt("percent"),
                r.getString("land"),r.getString("beneficiary"),r.getString("issuer"),r.getString("signer"),r.getString("state"),r.getInt("spared"));
    }
    Writ latest(String depot,int period)throws SQLException {
        try(var q=s.sql("SELECT * FROM tax_remission WHERE depot=? AND period=? ORDER BY id DESC LIMIT 1",depot,period);
            var r=q.executeQuery()){return r.next()?read(r):null;}
    }
    Writ get(int id)throws SQLException {
        try(var q=s.sql("SELECT * FROM tax_remission WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"没有这道减征令");return read(r);
        }
    }
    String district(int relief,boolean open)throws SQLException {
        try(var q=s.sql("""
            SELECT r.title,r.stage,l.title FROM relief r JOIN manor m ON m.id=r.dest
            LEFT JOIN land l ON l.x=(m.x>>4) AND l.z=(m.z>>4) WHERE r.id=?""",relief);var row=q.executeQuery()){
            if(!row.next() || row.getInt(2)==4 || (open && row.getInt(2)>=3))return null;
            String issued=row.getString(1);return issued.equals(row.getString(3))?issued:null;
        }
    }
    int propose(int relief,String depot,int period,int percent,String actor)throws SQLException {
        return s.atomic(()->{
            require(period==fiscal.period()+1,"只能为下一税期拟令，请重新查看税仓");
            require(percent>=1 && percent<=100,"减征比例须在 1–100 之间");
            var d=fiscal.depot(depot);String land=district(relief,true);
            require(land!=null && land.equals(s.owner(d.x(),d.z())),"须选未结赈单所在领地的税仓");
            require(s.title(land).seal().equals(actor),"须由缺粮领地的实际持印人拟令");
            var last=latest(depot,period);
            require(last==null || last.state().equals("已撤回"),"同仓同税期已有减征令");
            String right=fiscal.right(d);
            boolean approved=s.title(right).seal().equals(actor);
            s.update("""
                INSERT INTO tax_remission(relief,depot,period,percent,land,beneficiary,issuer,signer,state)
                VALUES(?,?,?,?,?,?,?,?,?)""",relief,depot,period,percent,land,right,actor,approved?actor:null,approved?"已核准":"待会签");
            return s.number("SELECT last_insert_rowid()");
        });
    }
    private boolean rights(Writ w,RealmStore.Depot d)throws SQLException {
        return w.land().equals(s.owner(d.x(),d.z())) && w.land().equals(district(w.relief(),false))
                && w.right().equals(fiscal.right(d)) && w.issuer().equals(s.title(w.land()).seal());
    }
    void sign(int id,String actor)throws SQLException {
        s.atomic(()->{
            var w=get(id);
            require(w.period()==fiscal.period()+1 && w.state().equals("待会签"),"此令不在会签期");
            require(rights(w,fiscal.depot(w.depot())) && s.title(w.right()).seal().equals(actor),"须由现任税权持印人会签，且原领印仍有效");
            s.update("UPDATE tax_remission SET signer=?,state='已核准' WHERE id=?",actor,id);return null;
        });
    }
    void revoke(int id,String actor)throws SQLException {
        var w=get(id);
        require(w.period()==fiscal.period()+1 && (w.state().equals("待会签") || w.state().equals("已核准")),"此令已停止受理");
        require(s.title(w.land()).seal().equals(actor) || s.title(w.right()).seal().equals(actor),"须由领印或税权持印人撤回");
        s.update("UPDATE tax_remission SET state='已撤回' WHERE id=?",id);
    }
    // Called inside the transaction that creates the assessment. Net dues, quota and the writ commit together.
    int settle(RealmStore.Depot d,int period,int gross)throws SQLException {
        var w=latest(d.id(),period);
        if(w==null || w.state().equals("已撤回") || w.state().equals("授权失效"))return 0;
        String state;int spared=0;
        if(!w.state().equals("已核准"))state="未获会签";
        else if(!rights(w,d) || !s.title(w.right()).seal().equals(w.signer()))state="授权失效";
        else {state="已减征";spared=(int)((long)gross*w.percent()/100);}
        s.update("UPDATE tax_remission SET state=?,spared=? WHERE id=?",state,spared,w.id());
        return spared;
    }
    void expireBefore(int period)throws SQLException {
        s.update("UPDATE tax_remission SET state='期满未计征' WHERE period<? AND state IN ('已核准','待会签')",period);
        s.update("UPDATE tax_remission SET state='未获会签' WHERE period=? AND state='待会签'",period);
    }
}
