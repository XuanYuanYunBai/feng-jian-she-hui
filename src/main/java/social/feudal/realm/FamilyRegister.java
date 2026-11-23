package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

/** People survive account logouts. Only the current representative can exercise a personal seal. */
final class FamilyRegister {
    record Person(int id,int family,String name,int birth,String home,int branch,int parent,int other,int sibling,boolean alive,String sex) {
        int age(int season){return Math.max(0,Math.floorDiv(season-birth,4));}
    }
    record Right(String kind,String key,String name,String destination,String basis) {}
    static final List<String> LAWS=List.of("普通分割","优先分割","联盟分割","长嗣","幼嗣","年长者");
    final RealmStore s;
    final FamilyHoldings holdings;
    final FamilyLineage lineage;

    FamilyRegister(RealmStore store)throws SQLException{
        s=store;
        s.update("""
            CREATE TABLE IF NOT EXISTS family(id INTEGER PRIMARY KEY,account TEXT NOT NULL,name TEXT NOT NULL,
            emblem TEXT NOT NULL,rep INTEGER,preferred INTEGER,law TEXT NOT NULL DEFAULT '普通分割',ended INTEGER)""");
        s.update("CREATE UNIQUE INDEX IF NOT EXISTS living_family ON family(account) WHERE ended IS NULL");
        s.update("""
            CREATE TABLE IF NOT EXISTS kin(id INTEGER PRIMARY KEY,family INTEGER NOT NULL REFERENCES family(id),name TEXT NOT NULL,
            birth INTEGER NOT NULL,home TEXT REFERENCES manor(id),branch INTEGER NOT NULL DEFAULT 0,
            parent INTEGER REFERENCES kin(id),other INTEGER REFERENCES kin(id),sibling INTEGER REFERENCES kin(id),death INTEGER)""");
        s.update("CREATE INDEX IF NOT EXISTS kin_house ON kin(family,death)");
        s.update("CREATE TABLE IF NOT EXISTS life_receipt(person INTEGER PRIMARY KEY REFERENCES kin(id),season INTEGER,summary TEXT NOT NULL)");
        s.update("CREATE TABLE IF NOT EXISTS family_asset(kind TEXT,asset TEXT,person INTEGER REFERENCES kin(id),PRIMARY KEY(kind,asset))");
        s.update("CREATE TABLE IF NOT EXISTS family_nomination(title TEXT PRIMARY KEY REFERENCES title(id),person INTEGER REFERENCES kin(id))");
        s.update("CREATE TABLE IF NOT EXISTS family_branch(id INTEGER PRIMARY KEY,family INTEGER REFERENCES family(id),name TEXT,home TEXT REFERENCES manor(id))");
        s.update("""
            CREATE TABLE IF NOT EXISTS family_match(id INTEGER PRIMARY KEY,a INTEGER REFERENCES kin(id),b INTEGER REFERENCES kin(id),
            children INTEGER REFERENCES family(id),home TEXT REFERENCES manor(id),dowry INTEGER NOT NULL,paid INTEGER NOT NULL DEFAULT 0,
            support INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL,raising INTEGER NOT NULL DEFAULT 0,last_year INTEGER)""");
        s.update("CREATE TABLE IF NOT EXISTS family_purse(bill TEXT PRIMARY KEY,manor TEXT REFERENCES manor(id),x INTEGER,y INTEGER,z INTEGER,packed INTEGER DEFAULT 0)");
        s.update("CREATE TABLE IF NOT EXISTS family_famine(person INTEGER PRIMARY KEY REFERENCES kin(id),season INTEGER)");
        s.update("CREATE TABLE IF NOT EXISTS family_payment(bill TEXT PRIMARY KEY,marriage INTEGER REFERENCES family_match(id),manor TEXT,amount INTEGER)");
        s.update("CREATE TABLE IF NOT EXISTS family_duty(person INTEGER PRIMARY KEY REFERENCES kin(id),kind TEXT NOT NULL,manor TEXT REFERENCES manor(id),start INTEGER,baseline INTEGER,qualified INTEGER DEFAULT 0)");
        holdings=new FamilyHoldings(this);
        s.update("CREATE TABLE IF NOT EXISTS family_mandate(title TEXT REFERENCES title(id),issuer INTEGER REFERENCES kin(id),delegate INTEGER REFERENCES kin(id),task TEXT,until INTEGER,PRIMARY KEY(title,task))");
        s.update("CREATE TABLE IF NOT EXISTS family_steward(person INTEGER PRIMARY KEY REFERENCES kin(id),manor TEXT REFERENCES manor(id),kind TEXT,issuer TEXT,UNIQUE(manor,kind))");
        s.update("CREATE TABLE IF NOT EXISTS family_notice(person INTEGER PRIMARY KEY REFERENCES kin(id))");
        s.update("CREATE TABLE IF NOT EXISTS family_guard(ward INTEGER PRIMARY KEY REFERENCES kin(id),guardian INTEGER REFERENCES kin(id))");
        s.update("CREATE TABLE IF NOT EXISTS family_journey(person INTEGER PRIMARY KEY REFERENCES kin(id),family INTEGER UNIQUE REFERENCES family(id),source TEXT,dest TEXT,escort INTEGER REFERENCES kin(id),x INTEGER,y INTEGER,z INTEGER)");
        s.update("CREATE TABLE IF NOT EXISTS family_pending(family INTEGER PRIMARY KEY REFERENCES family(id),predecessor INTEGER REFERENCES kin(id))");
        lineage=new FamilyLineage(this);
        s.atomic(()->{
            for(int f:ids("SELECT f.id FROM family f JOIN kin k ON k.id=f.rep WHERE f.ended IS NULL AND k.sex='女'")){
                int old=rep(f);String holder=account(f),token=personal(old);
                syncAssets(holder,old);
                s.update("UPDATE title SET lawful=CASE WHEN lawful=? THEN ? ELSE lawful END,seal=CASE WHEN seal=? THEN ? ELSE seal END WHERE lawful=? OR seal=?",holder,token,holder,token,holder,holder);
                for(var pair:List.of(new String[]{"庄产","manor"},new String[]{"商站","stall"}))
                    s.update("UPDATE "+pair[1]+" SET owner=? WHERE owner=? AND id IN (SELECT asset FROM family_asset WHERE kind=? AND person=?)",token,holder,pair[0],old);
                s.update("INSERT OR REPLACE INTO family_pending VALUES(?,?)",f,old);
                s.update("UPDATE family SET rep=NULL,preferred=NULL WHERE id=?",f);
            }
            reconcilePending();return null;
        });
    }
    private void reconcilePending()throws SQLException{
        for(int f:ids("SELECT f.id FROM family f WHERE f.ended IS NULL AND f.rep IS NULL AND NOT EXISTS(SELECT 1 FROM ai_house h WHERE h.family=f.id)")){
            if(choices(f).isEmpty())s.update("UPDATE family SET ended=? WHERE id=?",season(),f);
        }
    }
    List<Integer> ids(String query,Object...args)throws SQLException{
        var out=new ArrayList<Integer>();try(var q=s.sql(query,args);var r=q.executeQuery()){while(r.next())out.add(r.getInt(1));}return out;
    }
    int season()throws SQLException{return s.number("SELECT v FROM setting WHERE k='season'");}
    int family(String account)throws SQLException{return s.number("SELECT id FROM family WHERE account=? AND ended IS NULL",account);}
    int rep(int family)throws SQLException{return s.number("SELECT rep FROM family WHERE id=?",family);}
    String account(int family)throws SQLException{return s.value("SELECT account FROM family WHERE id=? AND ended IS NULL",family);}
    static String personal(int id){return "族人·"+id;}
    static String label(String name){
        require(name!=null && name.matches("[\\p{L}\\p{N}·_-]{1,24}"),"名称限 1–24 个文字、数字或间隔号");return name;
    }
    Person person(int id)throws SQLException{
        try(var q=s.sql("SELECT * FROM kin WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"查无此人");return new Person(id,r.getInt("family"),r.getString("name"),r.getInt("birth"),r.getString("home"),
                r.getInt("branch"),r.getInt("parent"),r.getInt("other"),r.getInt("sibling"),r.getObject("death")==null,r.getString("sex"));
        }
    }
    int addPerson(int f,String name,int birth,String home,Integer parent,Integer other,Integer sibling,String sex)throws SQLException{
        s.update("INSERT INTO kin(family,name,birth,home,parent,other,sibling,sex) VALUES(?,?,?,?,?,?,?,?)",f,label(name),birth,home,parent,other,sibling,sex);
        return s.number("SELECT last_insert_rowid()");
    }
    int found(String actor,String familyName,String founder,String emblem)throws SQLException{
        return s.atomic(()->{
            require(family(actor)==0,"本账号已有在世家族");int season=season();
            s.update("INSERT INTO family(account,name,emblem) VALUES(?,?,?)",actor,label(familyName),label(emblem));
            int f=s.number("SELECT last_insert_rowid()");
            String home=s.value("SELECT id FROM manor WHERE owner=? AND residents>=2 ORDER BY id LIMIT 1",actor);
            int first=addPerson(f,founder,season-28*4,home,null,null,null,"男");
            addPerson(f,"阿黛尔",season-23*4,home,null,null,first,"女");
            s.update("UPDATE family SET rep=? WHERE id=?",first,f);syncAssets(actor,first);
            return f;
        });
    }
    void syncAssets(String actor,int person)throws SQLException{
        for(String[] pair:List.of(new String[]{"庄产","manor"},new String[]{"商站","stall"}))
            s.update("INSERT OR IGNORE INTO family_asset SELECT ?,id,? FROM "+pair[1]+" WHERE owner=?",pair[0],person,actor);
    }
    List<Integer> living(int f)throws SQLException{return ids("SELECT id FROM kin WHERE family=? AND death IS NULL ORDER BY birth,id",f);}
    boolean inTransit(int person)throws SQLException{return s.number("SELECT count(*) FROM family_journey WHERE person=?",person)>0;}
    void own(int f,int id)throws SQLException{var p=person(id);require(p.family()==f && p.alive(),"须选本家在世成员");}
    List<Integer> successors(int id)throws SQLException{
        return lineage.playable(id);
    }
    void mandate(int f,String title,String task,int seasons)throws SQLException{
        require(List.of("亲收税箱","交印").contains(task) && seasons>=1 && seasons<=4,"请选择亲收税箱或交印，授权一至四季");
        String seal=s.title(title).seal();require(seal.startsWith("族人·"),"授权须由另一个持印亲属出具");
        int issuer=Integer.parseInt(seal.substring(3));own(f,issuer);
        require(person(issuer).age(season())>=18 && rep(f)!=issuer,"授权须由现任成年持印亲属办理");
        s.update("INSERT OR REPLACE INTO family_mandate VALUES(?,?,?,?,?)",title,issuer,rep(f),task,season()+seasons);
    }
    boolean delegated(String actor,String title,String task)throws SQLException{
        return s.number("""
            SELECT count(*) FROM family_mandate m JOIN title t ON t.id=m.title JOIN kin k ON k.id=m.issuer
            JOIN family f ON f.rep=m.delegate WHERE m.title=? AND m.task=? AND f.account=? AND f.ended IS NULL
            AND k.death IS NULL AND k.family=f.id AND t.seal=('族人·'||m.issuer) AND m.until>?""",title,task,actor,season())==1;
    }
    void prefer(int f,int pick)throws SQLException{
        require(successors(rep(f)).contains(pick),"须从本家男嗣、本家兄弟或合资格的外孙中依序选择");
        s.update("UPDATE family SET preferred=? WHERE id=?",pick,f);
    }
    String actorFor(int person)throws SQLException{
        if(person==0)return Succession.VACANT;
        var p=person(person);String a=account(p.family());
        return a!=null && rep(p.family())==person?a:personal(person);
    }
    List<Integer> bloodHeirs(int id)throws SQLException{
        var descendants=new ArrayList<Integer>();
        maleDescendants(id,descendants,new HashSet<>(),0);
        if(descendants.isEmpty())descendants.addAll(lineage.brothers(id));
        return descendants;
    }
    List<Integer> heirs(int id)throws SQLException{
        var eligible=bloodHeirs(id);
        if(eligible.isEmpty())eligible.addAll(lineage.grandsons(id,true));
        String law=s.value("SELECT law FROM family WHERE id=?",person(id).family());
        if(law.equals("年长者")){
            var agnates=lineage.agnates(id);
            if(!agnates.isEmpty())eligible=agnates;
            byAge(eligible);
        }
        if(law.equals("幼嗣"))Collections.reverse(eligible);
        if(!eligible.isEmpty() && List.of("长嗣","幼嗣","年长者").contains(law))return List.of(eligible.getFirst());
        return eligible;
    }
    private void byAge(List<Integer> people)throws SQLException{
        var births=new HashMap<Integer,Integer>();
        for(int id:people)births.put(id,person(id).birth());
        people.sort(Comparator.comparingInt(births::get));
    }
    void maleDescendants(int id,List<Integer> heirs,Set<Integer> visited,int family)throws SQLException{
        if(!visited.add(id))return;
        for(int child:ids("SELECT id FROM kin WHERE sex='男' AND (parent=? OR other=?) ORDER BY birth,id",id,id)){
            var p=person(child);
            if(family!=0 && p.family()!=family)continue;
            if(p.alive()){if(!heirs.contains(child))heirs.add(child);}
            else maleDescendants(child,heirs,visited,family);
        }
    }
    long[] portions(int who,List<Integer> heirs,long value,String law)throws SQLException{
        var allocation=new LinkedHashMap<Integer,Long>();
        divideBranch(who,new HashSet<>(heirs),value,law.equals("优先分割"),allocation);
        long[] result=new long[heirs.size()];
        if(!allocation.isEmpty()){
            for(int i=0;i<heirs.size();i++)result[i]=allocation.getOrDefault(heirs.get(i),0L);
        }else{
            long other=law.equals("优先分割") && heirs.size()>1?value/(2L*(heirs.size()-1)):value/heirs.size();
            Arrays.fill(result,other);result[0]=value-other*(heirs.size()-1);
        }
        return result;
    }
    private void divideBranch(int parent,Set<Integer> heirs,long value,boolean priority,Map<Integer,Long> result)throws SQLException{
        var branches=new ArrayList<Integer>();
        for(int child:ids("SELECT id FROM kin WHERE parent=? OR other=? ORDER BY birth,id",parent,parent)){
            if(heirs.contains(child))branches.add(child);
            else if(!person(child).alive() && person(child).sex().equals("男")){
                var below=new ArrayList<Integer>();maleDescendants(child,below,new HashSet<>(),0);
                if(below.stream().anyMatch(heirs::contains))branches.add(child);
            }
        }
        if(branches.isEmpty())return;
        long junior=priority && branches.size()>1?value/(2L*(branches.size()-1)):value/branches.size();
        for(int i=0;i<branches.size();i++){
            int child=branches.get(i);long part=i==0?value-junior*(branches.size()-1):junior;
            if(heirs.contains(child))result.merge(child,part,Long::sum);
            else divideBranch(child,heirs,part,false,result);
        }
    }
    List<Right> preview(int id)throws SQLException{
        var p=person(id);var heirs=heirs(id);String actor=actorFor(id);var out=new ArrayList<Right>();
        String law=s.value("SELECT law FROM family WHERE id=?",p.family());
        for(var award:holdings.divide(id,heirs,law)){
            var lot=award.lot();var shares=new ArrayList<String>();
            for(var entry:award.shares().entrySet())shares.add(person(entry.getKey()).name()+" "+(entry.getValue()/100.0)+"%");
            out.add(new Right(lot.kind(),lot.key(),lot.name(),personal(award.manager()),law+(shares.size()>1?" · "+String.join("，",shares):"")));
        }
        var partitionIndex=new HashMap<String,Integer>();
        var partitionSize=new HashMap<String,Integer>();
        for(var t:s.titles())if(actor.equals(t.lawful()) && !t.id().equals("crown") && !t.charter().equals("流官")
            && (t.charter().equals("收益封契") || s.number("SELECT hereditary FROM office WHERE id=?",t.id())==1 || t.parent()==null))
            partitionSize.merge(lineage.order(t.id(),id),1,Integer::sum);
        for(var t:s.titles()){
            if(!actor.equals(t.lawful()) && !actor.equals(t.seal()))continue;
            String dest=Succession.VACANT,basis="等待重新任命";
            if(t.id().equals("crown"))basis="选侯另议帝位";
            else if(!actor.equals(t.lawful())){dest=t.lawful();basis="归还法定持有人";}
            else if(t.charter().equals("流官") || (!t.charter().equals("收益封契") && (t.rank().equals("修院") || t.rank().equals("主教"))))basis="官印、公库交新任";
            else if(t.charter().equals("收益封契") || s.number("SELECT hereditary FROM office WHERE id=?",t.id())==1 || t.parent()==null){
                String order=lineage.order(t.id(),id);
                var eligible=lineage.titleHeirs(id,t.id());
                var ordered=new ArrayList<>(eligible);
                if(order.equals("幼嗣"))Collections.reverse(ordered);
                if(order.equals("年长者"))byAge(ordered);
                int candidate=s.number("SELECT person FROM family_nomination WHERE title=?",t.id());
                String legacy=s.value("SELECT heir FROM office WHERE id=?",t.id());
                if(eligible.contains(candidate))dest=personal(candidate);
                else if(legacy!=null && !legacy.startsWith("族人·") && eligible.contains(s.number("SELECT rep FROM family WHERE account=? AND ended IS NULL",legacy)))dest=legacy;
                else if(!ordered.isEmpty()){
                    int position=partitionIndex.getOrDefault(order,0);
                    if(order.equals("优先分割") && ordered.size()>1){
                        int primary=(partitionSize.getOrDefault(order,1)+1)/2;
                        position=position<primary?0:1+(position-primary)%(ordered.size()-1);
                    }
                    dest=personal(ordered.get(List.of("长嗣","幼嗣","年长者").contains(order)?0:position%ordered.size()));
                }
                partitionIndex.merge(order,1,Integer::sum);
                basis=lineage.law(t.id())+" · "+order+(dest.equals(Succession.VACANT)?" · 缺合资格男嗣":" · 依封契继任");
            }
            if(basis.equals("等待重新任命") && actor.equals(t.seal()) && !heirs.isEmpty() &&
                s.number("SELECT count(*) FROM endorsement WHERE id=?",t.id())>=2){
                dest=personal(heirs.getFirst());basis="职位待任 · 地方暂管官印";
            }
            out.add(new Right("职权",t.id(),t.name(),dest,basis));
        }
        if(law.equals("联盟分割") && heirs.size()>1){
            var claims=ids("""
                SELECT c.id FROM claim c JOIN family_asset a ON a.kind='庄产' AND a.asset=c.manor
                WHERE a.person=? AND c.state='公示中' AND c.type IN ('入帝','独立') ORDER BY c.id""",id);
            for(int i=0;i<claims.size();i++)out.add(new Right("待建领",String.valueOf(claims.get(i)),"建领公示 #"+claims.get(i),
                    personal(heirs.get(1+i%(heirs.size()-1))),"须现场复核人口、资格和公议后设领"));
        }
        return out;
    }
    String nameOf(String dest)throws SQLException{
        if(dest.equals(personal(0)))return "遗产待领";
        if(dest.startsWith("族人·"))return person(Integer.parseInt(dest.substring(3))).name();
        return dest;
    }
    String report(List<Right> rights)throws SQLException{
        var lines=new ArrayList<String>();
        for(var r:rights)lines.add(r.kind()+" · "+r.name()+" → "+nameOf(r.destination())+"（"+r.basis()+"）");
        return lines.isEmpty()?"尚无登记庄产、商站或官印":String.join("\n",lines);
    }
    private void apply(Right r,int dead,String actor)throws SQLException{
        String dest=r.destination();int next=dest.startsWith("族人·")?Integer.parseInt(dest.substring(3)):0;
        if(next!=0)dest=actorFor(next);
        var old=s.title(r.key());
        String legal=actor.equals(old.lawful())?(r.basis().contains("暂管官印")?Succession.VACANT:dest):old.lawful();
        String seal=actor.equals(old.seal())?dest:old.seal();
        s.update("UPDATE title SET lawful=?,seal=? WHERE id=?",legal,seal,r.key());
        s.update("DELETE FROM family_nomination WHERE title=?",r.key());
        s.update("UPDATE office SET heir=NULL,petition=0,start=? WHERE id=?",s.number("SELECT v FROM setting WHERE k='period'"),r.key());
        s.update("DELETE FROM endorsement WHERE id=?",r.key());
        s.update("INSERT INTO instrument(title,period,event) VALUES(?,?,'人物身故 · '||?)",r.key(),s.number("SELECT v FROM setting WHERE k='period'"),r.basis());
    }
    String die(String actor,int id)throws SQLException{
        return s.atomic(()->{
            var p=person(id);require(actor.equals(s.value("SELECT account FROM family WHERE id=?",p.family())),"不属本家");
            String saved=s.value("SELECT summary FROM life_receipt WHERE person=?",id);if(saved!=null)return saved;
            int f=p.family();require(p.alive() && rep(f)==id,"此次生涯已经结束");
            syncAssets(actor,id);var rights=preview(id);String summary=report(rights);
            var awards=holdings.divide(id,heirs(id),s.value("SELECT law FROM family WHERE id=?",f));
            var candidates=successors(id);int primary=s.number("SELECT preferred FROM family WHERE id=?",f);
            if(!candidates.contains(primary))primary=candidates.isEmpty()?0:candidates.getFirst();
            s.update("UPDATE kin SET death=? WHERE id=?",season(),id);
            s.update("UPDATE family SET rep=NULL,preferred=? WHERE id=?",primary==0?null:primary,f);
            s.update("INSERT OR REPLACE INTO family_pending VALUES(?,?)",f,id);
            for(var award:awards)holdings.assign(id,award);
            for(var right:rights){
                if(right.kind().equals("职权"))apply(right,id,actor);
                else if(right.kind().equals("待建领"))s.update("INSERT OR REPLACE INTO partition_claim VALUES(?,?)",Integer.parseInt(right.key()),Integer.parseInt(right.destination().substring(3)));
            }
            s.update("UPDATE signatory SET signer=NULL WHERE signer=? AND treaty IN (SELECT id FROM treaty WHERE state='待批准')",actor);
            s.update("UPDATE organisation_signature SET signer=NULL WHERE signer=? AND treaty IN (SELECT id FROM treaty WHERE state='待批准')",actor);
            s.update("DELETE FROM pact WHERE accepted=0 AND (proposer=? OR partner=?)",actor,actor);
            s.update("DELETE FROM endorsement WHERE supporter=?",actor);
            s.update("UPDATE family_match SET state='草案失效' WHERE state='议婚' AND (a=? OR b=?)",id,id);
            s.update("UPDATE family_match SET state='遗属' WHERE state='已婚' AND (a=? OR b=?)",id,id);
            if(p.home()!=null)s.update("UPDATE manor SET residents=max(0,residents-1) WHERE id=?",p.home());
            s.update("UPDATE tax_remission SET state='授权失效' WHERE state IN ('已核准','待会签') AND (issuer=? OR signer=?)",actor,actor);
            s.update("DELETE FROM pit_contract WHERE issuer=? OR operator=?",actor,actor);
            s.update("DELETE FROM family_journey WHERE person=?",id);
            if(primary==0)s.update("UPDATE family SET ended=? WHERE id=?",season(),f);
            s.update("DELETE FROM family_famine WHERE person=?",id);
            s.update("INSERT INTO life_receipt VALUES(?,?,?)",id,season(),summary);
            return summary;
        });
    }
    // Called within the manor season transaction. Aggregate famine victims must not be subtracted twice.
    void famine(String home,int closing,int season)throws SQLException{
        int excess=s.number("SELECT count(*) FROM kin WHERE death IS NULL AND home=?",home)-closing;
        if(excess<=0)return;
        for(int who:ids("""
            SELECT k.id FROM kin k JOIN family f ON f.id=k.family WHERE k.home=? AND k.death IS NULL
            ORDER BY (k.id=coalesce(f.rep,-1)),k.birth,k.id LIMIT ?""",home,excess)){
            var p=person(who);
            if(rep(p.family())==who){
                s.update("INSERT OR IGNORE INTO family_famine VALUES(?,?)",who,season);
            }else{
                var rights=preview(who);var awards=holdings.divide(who,heirs(who),s.value("SELECT law FROM family WHERE id=?",p.family()));
                for(var a:awards)holdings.assign(who,a);
                for(var r:rights)if(r.kind().equals("职权"))apply(r,who,personal(who));
                s.update("UPDATE kin SET death=? WHERE id=?",season,who);
                s.update("UPDATE family_match SET state=CASE WHEN state='议婚' THEN '草案失效' ELSE '遗属' END WHERE (a=? OR b=?) AND state IN ('议婚','已婚')",who,who);
                // Receipts of representatives determine the next playable life; collateral deaths stay in the pedigree.
            }
            s.update("UPDATE kin SET home=NULL WHERE id=?",who);
        }
        reconcilePending();
    }
    List<Integer> choices(int f)throws SQLException{
        int last=s.number("SELECT predecessor FROM family_pending WHERE family=?",f);
        if(last==0)last=s.number("SELECT k.id FROM kin k JOIN life_receipt l ON l.person=k.id WHERE k.family=? ORDER BY l.season DESC,l.rowid DESC LIMIT 1",f);
        return last==0?List.of():successors(last);
    }
    void assume(int f,int chosen)throws SQLException{
        s.atomic(()->{
            require(account(f)!=null && rep(f)==0 && choices(f).contains(chosen),"请从本次接续名单选择");
            lineage.receive(f,chosen);activate(f,chosen);reconcilePending();return null;
        });
    }
    private void activate(int f,int chosen)throws SQLException{
        String actor=account(f),token=personal(chosen);
        s.update("UPDATE family SET rep=?,preferred=NULL WHERE id=?",chosen,f);
        s.update("DELETE FROM family_pending WHERE family=?",f);
        s.update("UPDATE title SET lawful=CASE WHEN lawful=? THEN ? ELSE lawful END,seal=CASE WHEN seal=? THEN ? ELSE seal END WHERE lawful=? OR seal=?",token,actor,token,actor,token,token);
    }
    void retire(int f,int target)throws SQLException{
        s.atomic(()->{
            int old=rep(f);var here=person(old);var there=person(target);
            require(there.family()==f && here.age(season())>=18 && successors(old).contains(target) && there.age(season())>=18 && here.home()!=null && here.home().equals(there.home()),"两名成年交接人须同住本庄");
            // The living former representative keeps their individual offices.
            String actor=account(f),token=personal(old);
            s.update("UPDATE title SET lawful=CASE WHEN lawful=? THEN ? ELSE lawful END,seal=CASE WHEN seal=? THEN ? ELSE seal END WHERE lawful=? OR seal=?",actor,token,actor,token,actor,actor);
            s.update("UPDATE tax_remission SET state='授权失效' WHERE state IN ('已核准','待会签') AND (issuer=? OR signer=?)",actor,actor);
            activate(f,target);return null;
        });
    }
}
