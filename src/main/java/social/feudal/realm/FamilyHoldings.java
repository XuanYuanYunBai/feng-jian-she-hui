package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

/** Partition each owner's actual interest. A co-owner's share survives another owner's death. */
final class FamilyHoldings {
    record Lot(String kind,String key,String name,int deceasedShare,int value) {}
    record Award(Lot lot,int manager,Map<Integer,Integer> shares) {}
    private final FamilyRegister book;
    private final RealmStore s;
    FamilyHoldings(FamilyRegister book)throws SQLException{
        this.book=book;s=book.s;
        s.update("CREATE TABLE IF NOT EXISTS family_interest(kind TEXT,asset TEXT,person INTEGER REFERENCES kin(id),units INTEGER CHECK(units BETWEEN 1 AND 10000),PRIMARY KEY(kind,asset,person))");
        s.update("CREATE TABLE IF NOT EXISTS family_loan(id INTEGER PRIMARY KEY,lender INTEGER REFERENCES family(id),borrower INTEGER REFERENCES family(id),principal TEXT,pledge TEXT,term INTEGER,due INTEGER,state TEXT)");
        s.update("CREATE TABLE IF NOT EXISTS family_cash(id TEXT PRIMARY KEY,owner INTEGER REFERENCES kin(id),amount INTEGER,manor TEXT,x INTEGER,y INTEGER,z INTEGER,packed INTEGER DEFAULT 0,closed INTEGER DEFAULT 0)");
        s.update("CREATE TABLE IF NOT EXISTS family_value(kind TEXT,asset TEXT,value INTEGER CHECK(value BETWEEN 1 AND 1000000),PRIMARY KEY(kind,asset))");
    }
    List<Lot> lots(int who)throws SQLException{
        var lots=new ArrayList<Lot>();
        try(var q=s.sql("""
            SELECT a.kind,a.asset,coalesce(m.name,t.name,CASE WHEN c.id IS NOT NULL THEN c.amount||' 枚库银 · '||a.asset END,a.asset),coalesce(i.units,10000),coalesce(c.amount,v.value,1)
            FROM family_asset a LEFT JOIN manor m ON a.kind='庄产' AND m.id=a.asset
            LEFT JOIN stall t ON a.kind='商站' AND t.id=a.asset
            LEFT JOIN family_cash c ON a.kind='存银' AND c.id=a.asset
            LEFT JOIN family_value v ON v.kind=a.kind AND v.asset=a.asset
            LEFT JOIN family_interest i ON i.kind=a.kind AND i.asset=a.asset AND i.person=?
            WHERE (i.person IS NOT NULL OR (a.person=? AND NOT EXISTS(SELECT 1 FROM family_interest j WHERE j.kind=a.kind AND j.asset=a.asset)))
            AND NOT EXISTS(SELECT 1 FROM family_loan l WHERE a.kind='存银' AND l.pledge=a.asset AND l.state='在借')
            ORDER BY (a.kind='存银'),coalesce(v.value,1) DESC,a.kind,a.asset""",who,who);var r=q.executeQuery()){
            while(r.next())lots.add(new Lot(r.getString(1),r.getString(2),r.getString(3),r.getInt(4),r.getInt(5)));
        }return lots;
    }
    List<Award> divide(int who,List<Integer> heirs,String law)throws SQLException{
        var lots=lots(who);var awards=new ArrayList<Award>();
        if(heirs.isEmpty()){
            for(var lot:lots)awards.add(new Award(lot,0,Map.of()));return awards;
        }
        long value=0;for(var lot:lots)value+=(long)lot.value()*lot.deceasedShare();
        long[] left=book.portions(who,heirs,value,law);
        for(var lot:lots){
            var parts=new LinkedHashMap<Integer,Integer>();int units=lot.deceasedShare();
            while(units>0){
                int best=0;for(int i=1;i<left.length;i++)if(left[i]>left[best])best=i;
                int grant=(int)Math.min(units,Math.max(1,(left[best]+lot.value()-1)/lot.value()));
                parts.merge(heirs.get(best),grant,Integer::sum);left[best]-=(long)grant*lot.value();units-=grant;
            }
            int manager=parts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            awards.add(new Award(lot,manager,parts));
        }
        return awards;
    }
    void assign(int who,Award award)throws SQLException{
        var lot=award.lot();
        s.update("DELETE FROM family_interest WHERE kind=? AND asset=? AND person=?",lot.kind(),lot.key(),who);
        for(var part:award.shares().entrySet())s.update("""
            INSERT INTO family_interest VALUES(?,?,?,?) ON CONFLICT(kind,asset,person) DO UPDATE SET units=units+excluded.units""",
            lot.kind(),lot.key(),part.getKey(),part.getValue());
        if(award.shares().isEmpty())s.update("INSERT INTO family_interest VALUES(?,?,?,?)",lot.kind(),lot.key(),who,lot.deceasedShare());
        int steward=s.number("""
            SELECT i.person FROM family_interest i JOIN kin k ON k.id=i.person WHERE i.kind=? AND i.asset=?
            AND k.death IS NULL AND (i.person<>? OR ?=0) ORDER BY i.units DESC,i.person LIMIT 1""",lot.kind(),lot.key(),who,award.shares().isEmpty()?1:0);
        String table=lot.kind().equals("庄产")?"manor":"stall";
        if(steward==0){
            if(!lot.kind().equals("存银"))s.update("UPDATE "+table+" SET owner=? WHERE id=?","遗产·"+who,lot.key());
            s.update("UPDATE family_asset SET person=? WHERE kind=? AND asset=?",who,lot.kind(),lot.key());
        }else{
            s.update("UPDATE family_asset SET person=? WHERE kind=? AND asset=?",steward,lot.kind(),lot.key());
            if(!lot.kind().equals("存银"))s.update("UPDATE "+table+" SET owner=? WHERE id=?",book.account(book.person(steward).family()),lot.key());
        }
    }
    void deed(int f,int who,int next,String kind,String asset)throws SQLException{
        book.own(f,next);
        s.atomic(()->{
            var lot=lots(who).stream().filter(l->kind.equals(l.kind()) && asset.equals(l.key())).findFirst().orElse(null);
            require(lot!=null,"只能处分本人持有的份额");assign(who,new Award(lot,next,Map.of(next,lot.deceasedShare())));return null;
        });
    }
    void value(int who,String kind,String asset,int value)throws SQLException{
        require(!kind.equals("存银"),"实银按枚计值");
        require(value>0 && value<=1000000,"估值须为 1–1000000");
        require(lots(who).stream().anyMatch(l->kind.equals(l.kind()) && asset.equals(l.key()) && l.deceasedShare()==10000),"只可估定本人独有的产业");
        s.update("INSERT OR REPLACE INTO family_value VALUES(?,?,?)",kind,asset,value);
    }
}
