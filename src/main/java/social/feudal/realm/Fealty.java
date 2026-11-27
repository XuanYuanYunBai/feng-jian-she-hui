package social.feudal.realm;

import java.sql.SQLException;
import java.util.Objects;
import static social.feudal.realm.RealmStore.require;

/** An oath binds two people for one direct fief. Keep superseded receipts for the court. */
final class Fealty {
    record Bond(RealmStore.Title fief, RealmStore.Title lord, String vassal, String ruler) {
        boolean ready() {
            return vassal!=null && ruler!=null && !vassal.equals(ruler)
                    && fief.lawful().equals(fief.seal()) && lord.lawful().equals(lord.seal());
        }
    }
    record Entry(int id,String state){}
    final RealmStore s;
    Fealty(RealmStore s)throws SQLException {
        this.s=s;
        s.update("""
            CREATE TABLE IF NOT EXISTS fealty(id INTEGER PRIMARY KEY, fief TEXT NOT NULL REFERENCES title(id),
            lord TEXT NOT NULL REFERENCES title(id), vassal TEXT NOT NULL, ruler TEXT NOT NULL,
            state TEXT NOT NULL, offered INTEGER NOT NULL, accepted INTEGER)""");
        s.update("CREATE UNIQUE INDEX IF NOT EXISTS fealty_live ON fealty(fief) WHERE state<>'已终止'");
        // Resolve a player's seal to the character currently holding it, including during old-save migration.
        s.update("""
            CREATE VIEW IF NOT EXISTS fealty_holder AS SELECT t.id,t.parent,
                CASE WHEN t.lawful<>t.seal OR t.seal='待任' THEN NULL
                     WHEN k.id IS NOT NULL THEN '族人·'||k.id
                     WHEN t.seal LIKE '族人·%' OR (length(t.seal)=36 AND substr(t.seal,9,1)='-') THEN NULL
                     ELSE t.seal END person
                FROM title t LEFT JOIN family f ON f.account=t.seal AND f.ended IS NULL
                LEFT JOIN kin k ON k.death IS NULL AND (k.id=f.rep OR ('族人·'||k.id)=t.seal)""");
        s.update("""
            CREATE TRIGGER IF NOT EXISTS fealty_seal AFTER UPDATE OF parent,lawful,seal ON title BEGIN
            UPDATE fealty SET state='已终止' WHERE state<>'已终止' AND (fief=NEW.id OR lord=NEW.id)
            AND NOT EXISTS (SELECT 1 FROM fealty_holder v JOIN fealty_holder l ON l.id=v.parent
            WHERE v.id=fealty.fief AND l.id=fealty.lord AND v.person=fealty.vassal AND l.person=fealty.ruler);
            END""");
        s.update("""
            CREATE TRIGGER IF NOT EXISTS fealty_generation AFTER UPDATE OF rep ON family
            WHEN OLD.rep IS NOT NULL AND OLD.rep IS NOT NEW.rep BEGIN
            UPDATE fealty SET state='已终止' WHERE state<>'已终止' AND ('族人·'||OLD.rep) IN (vassal,ruler);
            END""");
        s.update("""
            CREATE TRIGGER IF NOT EXISTS fealty_death AFTER UPDATE OF death ON kin
            WHEN NEW.death IS NOT NULL BEGIN
            UPDATE fealty SET state='已终止' WHERE state<>'已终止' AND ('族人·'||NEW.id) IN (vassal,ruler);
            END""");
    }
    Bond bond(String id)throws SQLException {
        var t=s.title(id);require(t.parent()!=null,"此领没有直属封君");
        return new Bond(t,s.title(t.parent()),person(id),person(t.parent()));
    }
    private String person(String id)throws SQLException{return s.value("SELECT person FROM fealty_holder WHERE id=?",id);}
    Entry current(Bond b)throws SQLException {
        try(var q=s.sql("SELECT id,state FROM fealty WHERE fief=? AND lord=? AND vassal=? AND ruler=? AND state<>'已终止'",
                b.fief.id(),b.lord.id(),b.vassal,b.ruler);var r=q.executeQuery()){
            return r.next()?new Entry(r.getInt(1),r.getString(2)):new Entry(0,"待宣誓");
        }
    }
    void requireSworn(String fief)throws SQLException {
        if(s.title(fief).parent()==null)return;
        var b=bond(fief);
        if(b.vassal!=null && Objects.equals(b.vassal,b.ruler))return;
        require(b.ready() && current(b).state.equals("已受誓"),"请先从经营簿到直属封君治所完成本代效忠礼");
    }
    void sign(String id,String actor,String expectedVassal,String expectedRuler,int offer)throws SQLException {
        s.atomic(()->{
            var b=bond(id);
            require(b.ready() && b.vassal.equals(expectedVassal) && b.ruler.equals(expectedRuler),"印主或当代人物已变，请重读誓书");
            var live=current(b);
            var v=b.fief.seal();var l=b.lord.seal();
            boolean npcV=!Succession.isPlayer(v),npcL=!Succession.isPlayer(l);
            int season=s.number("SELECT v FROM setting WHERE k='season'");
            if(offer==0){
                require(live.id==0 && (actor.equals(v) || (npcV && actor.equals(l))),"请由当代封臣呈誓，或由封君接见驻地封臣");
                boolean complete=npcL || npcV;
                s.update("INSERT INTO fealty(fief,lord,vassal,ruler,state,offered,accepted) VALUES(?,?,?,?,?,?,?)",
                    id,b.lord.id(),b.vassal,b.ruler,complete?"已受誓":"待受誓",season,complete?season:null);
            }else{
                require(actor.equals(l) && live.id==offer && live.state.equals("待受誓"),"此誓书已失效或并非当代封君");
                s.update("UPDATE fealty SET state='已受誓',accepted=? WHERE id=?",season,offer);
            }
            return null;
        });
    }
}
