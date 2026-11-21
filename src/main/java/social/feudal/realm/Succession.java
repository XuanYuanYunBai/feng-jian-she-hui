package social.feudal.realm;

import java.sql.SQLException;
import java.util.Set;
import static social.feudal.realm.RealmStore.require;

final class Succession {
    static final String VACANT="待任";
    private final RealmStore s;
    private final Charters law;
    private final FiscalLedger fiscal;
    Succession(RealmStore s,Charters law,FiscalLedger fiscal)throws SQLException{
        this.s=s;this.law=law;this.fiscal=fiscal;
        s.update("""
            CREATE TABLE IF NOT EXISTS office(id TEXT PRIMARY KEY REFERENCES title(id),start INTEGER NOT NULL,
            heir TEXT,hereditary INTEGER NOT NULL DEFAULT 0,petition INTEGER NOT NULL DEFAULT 0)""");
        s.update("""
            CREATE TABLE IF NOT EXISTS endorsement(id TEXT REFERENCES title(id),supporter TEXT,
            PRIMARY KEY(id,supporter))""");
        s.update("""
            CREATE TABLE IF NOT EXISTS pact(id TEXT PRIMARY KEY REFERENCES title(id),kind TEXT NOT NULL,
            terms TEXT NOT NULL,proposer TEXT NOT NULL,partner TEXT NOT NULL,accepted INTEGER NOT NULL DEFAULT 0)""");
        s.update("CREATE TABLE IF NOT EXISTS instrument(id INTEGER PRIMARY KEY,title TEXT,period INTEGER,event TEXT NOT NULL)");
        sync();
    }
    void sync()throws SQLException{
        s.update("INSERT OR IGNORE INTO office(id,start) SELECT id,? FROM title",fiscal.period());
    }
    void record(String title,String event)throws SQLException{
        s.update("INSERT INTO instrument(title,period,event) VALUES(?,?,?)",title,fiscal.period(),event);
    }
    private RealmStore.Title superior(String id,String actor)throws SQLException{
        var t=s.title(id);require(t.parent()!=null,"没有直属上级");
        require(!t.charter().equals("收益封契"),"家产依继承文书交接");
        law.acts(t.parent(),actor);return t;
    }
    int support(String id)throws SQLException{
        int start=s.number("SELECT start FROM office WHERE id=?",id);
        return Math.min(2,Math.max(0,fiscal.period()-start))+s.number("SELECT count(*) FROM endorsement WHERE id=?",id);
    }
    void sponsor(String id,String actor,int x,int z)throws SQLException{
        var t=s.title(id);
        require(id.equals(s.owner(x,z)),"须在该领境内表达支持");
        require(!t.seal().equals(actor) && !VACANT.equals(t.lawful()),"须由其他人支持在任官员");
        require(s.update("INSERT OR IGNORE INTO endorsement VALUES(?,?)",id,actor)==1,"已经表达支持");
    }
    void nominate(String id,String actor,String heir)throws SQLException{
        law.acts(id,actor);require(!actor.equals(heir),"须提名另一位继承人");
        s.update("UPDATE office SET heir=?,petition=0 WHERE id=?",heir,id);
    }
    void petition(String id,String actor)throws SQLException{
        var t=s.title(id);require(t.seal().equals(actor),"须由持印人请袭");
        require(t.parent()!=null && !t.charter().equals("收益封契"),"此职位不适用请袭");
        require(s.value("SELECT heir FROM office WHERE id=?",id)!=null,"须先提名家族继承人");
        require(support(id)>=2,"须积累两点地方支持或任职资历");
        s.update("UPDATE office SET petition=1 WHERE id=?",id);
    }
    void confirm(String id,String actor)throws SQLException{
        s.atomic(()->{
            superior(id,actor);
            require(s.number("SELECT petition FROM office WHERE id=?",id)==1,"尚无请袭文书");
            s.update("UPDATE office SET hereditary=1,petition=0 WHERE id=?",id);
            record(id,"君主确认家族世袭权");return null;
        });
    }
    private void capacity(RealmStore.Title t,String who)throws SQLException{
        if(t.charter().equals("流官") && !who.equals(t.seal()) && !who.equals(t.lawful()))
            law.capacity(who,s.number("SELECT count(*) FROM land WHERE title=?",t.id()));
    }
    private void install(String id,String successor)throws SQLException{
        s.update("UPDATE title SET lawful=?,seal=? WHERE id=?",successor,successor,id);
        s.update("UPDATE office SET start=?,heir=NULL,petition=0 WHERE id=?",fiscal.period(),id);
        s.update("DELETE FROM endorsement WHERE id=?",id);
    }
    void vacate(String id,String actor)throws SQLException{
        s.atomic(()->{
            var t=law.acts(id,actor);
            String heir=s.value("SELECT heir FROM office WHERE id=?",id);
            require(heir!=null,"请先登记暂管人或家产继承人");
            capacity(t,heir);
            if(t.charter().equals("收益封契") || s.number("SELECT hereditary FROM office WHERE id=?",id)==1){
                install(id,heir);record(id,"依文书继任");
            }else{
                if(t.parent()==null){install(id,heir);record(id,"君主交位");}
                else{
                    s.update("UPDATE title SET lawful=?,seal=? WHERE id=?",VACANT,heir,id);
                    record(id,"职位出缺，家族暂管官印");
                }
            }
            // Private estates of the outgoing official pass separately from the public seal.
            for(var estate:s.titles())if(estate.charter().equals("收益封契") && estate.seal().equals(actor)){
                String successor=s.value("SELECT heir FROM office WHERE id=?",estate.id());
                install(estate.id(),successor==null?heir:successor);record(estate.id(),"家产继承");
            }
            return null;
        });
    }
    void appoint(String id,String actor,String next)throws SQLException{
        s.atomic(()->{
            var t=superior(id,actor);
            require(s.number("SELECT hereditary FROM office WHERE id=?",id)==0 || next.equals(t.seal()),"既有世袭封契须履行");
            capacity(t,next);
            if(next.equals(t.seal()))install(id,next);
            else s.update("UPDATE title SET lawful=? WHERE id=?",next,id);
            record(id,"颁发任命书");return null;
        });
    }
    void seal(String id,String actor,boolean surrender)throws SQLException{
        s.atomic(()->{
            var t=s.title(id);
            require(!t.seal().equals(t.lawful()) && !t.lawful().equals(VACANT),"尚无待执行的任命书");
            boolean npc=!isPlayer(t.seal());
            if(npc){
                superior(id,actor);require(surrender==(support(id)<2),"当地家族依现有支持决定是否交印");
            }else require(t.seal().equals(actor),"只有实际持印人能交印或抗命");
            if(surrender)install(id,t.lawful());
            record(id,surrender?"持印人移交官印":"持印人拒绝交印");return null;
        });
    }
    static boolean isPlayer(String id){
        try{java.util.UUID.fromString(id);return true;}catch(IllegalArgumentException e){return false;}
    }
    void offer(String id,String actor,String kind,String terms)throws SQLException{
        s.atomic(()->{
            var t=s.title(id);require(t.parent()!=null,"须有帝国上级");
            var empire=s.title(t.parent());require(empire.rank().equals("皇帝"),"限帝国直接封臣");
            require(Set.of("王号","独立").contains(kind) && !terms.isBlank() && terms.length()<=120,"请写明王号或独立及交换条件（1–120字）");
            boolean crown=kind.equals("王号");
            require(t.rank().equals(crown?"公爵":"国王"),crown?"仅直属公爵可议帝内王号":"仅帝内王国可议独立和约");
            law.acts(crown?empire.id():id,actor);
            var other=law.acts(crown?id:empire.id(),crown?t.seal():empire.seal());
            require(!actor.equals(other.seal()),"协议须有两方签署");
            s.update("INSERT OR REPLACE INTO pact VALUES(?,?,?,?,?,0)",id,kind,terms,actor,other.seal());
            record(id,"拟约 "+kind+" · "+terms);return null;
        });
    }
    void ratify(String id,String actor)throws SQLException{
        s.atomic(()->{
            var t=s.title(id);require(t.parent()!=null,"该领已独立");
            var empire=s.title(t.parent());
            try(var q=s.sql("SELECT kind,terms,proposer,partner FROM pact WHERE id=? AND accepted=0",id);var r=q.executeQuery()){
                require(r.next(),"没有待签协议");boolean crown=r.getString(1).equals("王号");
                require(actor.equals(r.getString(4)),"须由另一方签署");
                law.acts(crown?id:empire.id(),actor);law.acts(crown?empire.id():id,r.getString(3));
                require(t.rank().equals(crown?"公爵":"国王") && empire.rank().equals("皇帝"),"缔约地位已改变");
                if(crown)s.update("UPDATE title SET rank='国王' WHERE id=?",id);
                else s.update("UPDATE title SET parent=NULL WHERE id=?",id);
                record(id,r.getString(1)+"生效 · "+r.getString(2));
            }
            s.update("UPDATE pact SET accepted=1 WHERE id=?",id);return null;
        });
    }
}
