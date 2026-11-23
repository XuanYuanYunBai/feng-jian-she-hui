package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

/** Family events consume the same settlement population and production receipts as the manor. */
final class FamilyLife {
    private final FamilyRegister book;
    private final RealmStore s;
    private final java.util.function.IntSupplier annualRoll;
    FamilyLife(FamilyRegister book){this(book,()->java.util.concurrent.ThreadLocalRandom.current().nextInt(4));}
    FamilyLife(FamilyRegister book,java.util.function.IntSupplier roll){this.book=book;s=book.s;annualRoll=roll;}
    int freeResidents(String home)throws SQLException{
        return s.number("SELECT residents-(SELECT count(*) FROM kin WHERE home=manor.id AND death IS NULL) FROM manor WHERE id=?",home);
    }
    void settle(int family,int person,String manor)throws SQLException{
        book.own(family,person);
        require(s.number("SELECT count(*) FROM manor WHERE id=? AND owner=?",manor,book.account(family))==1,"只能在本家私庄安家");
        require(book.person(person).home()==null && freeResidents(manor)>0,"须为尚未落籍的成员，并有未具名居民名额");
        s.update("UPDATE kin SET home=? WHERE id=?",manor,person);
    }
    private void eligible(int id,String home)throws SQLException{
        var p=book.person(id);require(p.alive() && p.age(book.season())>=18 && home.equals(p.home()),"双方须成年、在世且已在约定庄园居住");
        require(s.number("SELECT count(*) FROM family_match WHERE (a=? OR b=?) AND state IN ('议婚','已婚')",id,id)==0,"已有婚约或待议草案");
    }
    private boolean closeKin(FamilyRegister.Person a,FamilyRegister.Person b)throws SQLException{
        if(a.id()==b.id() || a.sibling()==b.id() || b.sibling()==a.id())return true;
        if(a.parent()!=0 && (a.parent()==b.parent() || a.parent()==b.other()))return true;
        if(a.other()!=0 && (a.other()==b.parent() || a.other()==b.other()))return true;
        return ancestor(a.id(),b.id()) || ancestor(b.id(),a.id());
    }
    private boolean ancestor(int id,int possible)throws SQLException{
        return s.number("""
            WITH RECURSIVE parents(id) AS (SELECT ? UNION SELECT k.parent FROM kin k JOIN parents p ON k.id=p.id WHERE k.parent IS NOT NULL
            UNION SELECT k.other FROM kin k JOIN parents p ON k.id=p.id WHERE k.other IS NOT NULL)
            SELECT count(*) FROM parents WHERE id=?""",id,possible)>0;
    }
    int marriage(int family,int a,int b,String home,boolean otherHouse,int dowry,int support)throws SQLException{
        return s.atomic(()->{
            book.own(family,a);eligible(a,home);
            require(dowry>=0 && dowry<=64 && support>=0 && support<=64,"银币数量限 0–64");
            boolean local=b==0;int partner=b;
            if(local){
                require(freeResidents(home)>0 && !otherHouse && dowry==0,"地方婚配须有本地人，初约免嫁资、子女入本家");
                partner=book.addPerson(family,"乡人"+(1+s.number("SELECT max(id) FROM kin")),book.season()-25*4,home,null,null,null);
            }else{
                // Non-local parties meet in person to sign. Family and domicile never move on signature.
                var p=book.person(partner);require(p.home()!=null,"对方须先落籍");eligible(partner,p.home());
                require(p.family()!=family && !closeKin(book.person(a),p),"须与不同家族的非近亲议婚");
            }
            s.update("INSERT INTO family_match(a,b,children,home,dowry,support,state) VALUES(?,?,?,?,?,?,?)",
                a,partner,otherHouse?book.person(partner).family():family,home,dowry,support,local?"已婚":"议婚");
            return s.number("SELECT last_insert_rowid()");
        });
    }
    void sign(int family,int match)throws SQLException{
        int b=s.number("SELECT b FROM family_match WHERE id=? AND state='议婚'",match);
        require(b>0,"草案已结或不存在");book.own(family,b);
        require(s.update("UPDATE family_match SET state='已婚' WHERE id=? AND state='议婚'",match)==1,"婚约已办理");
    }
    void raising(int f,int match,boolean on)throws SQLException{
        require(s.update("UPDATE family_match SET raising=? WHERE id=? AND children=? AND state='已婚'",on?1:0,match,f)==1,"须由约定子女入籍的一家办理");
    }
    void yearAt(String manor,int season,int births)throws SQLException{
        if(season%4!=0)return;
        int year=season/4;
        var list=book.ids("""
            SELECT m.id FROM family_match m JOIN kin a ON a.id=m.a JOIN kin b ON b.id=m.b
            WHERE m.state='已婚' AND m.raising=1 AND m.home=? AND a.home=m.home AND b.home=m.home
            AND a.death IS NULL AND b.death IS NULL AND (m.last_year IS NULL OR m.last_year<?) ORDER BY m.id""",manor,year);
        for(int id:list){
            s.update("UPDATE family_match SET last_year=? WHERE id=?",year,id);
            if(births<=0 || annualRoll.getAsInt()!=0)continue;
            int a=s.number("SELECT a FROM family_match WHERE id=?",id),b=s.number("SELECT b FROM family_match WHERE id=?",id);
            int f=s.number("SELECT children FROM family_match WHERE id=?",id);
            int number=s.number("SELECT count(*) FROM kin WHERE parent=? AND other=?",a,b)+1;
            int child=book.addPerson(f,"子嗣"+number,season,manor,a,b,null);
            int parent=book.person(a).family()==f?a:b;
            s.update("UPDATE kin SET branch=(SELECT branch FROM kin WHERE id=?) WHERE id=?",parent,child);
            births--; // This child names one actual birth already recorded in population_season.
        }
    }
    void branch(int f,int who,String home,String name)throws SQLException{
        s.atomic(()->{
            book.own(f,who);var p=book.person(who);
            require(p.age(book.season())>=18 && home.equals(p.home()),"分房人须成年并住在此庄");
            require(s.number("SELECT count(*) FROM family_asset WHERE person=? AND kind='庄产' AND asset=?",who,home)==1,"分房须已持有此处私产");
            require(s.number("SELECT count(*) FROM family_branch WHERE home=?",home)==0,"此庄已有房支");
            s.update("INSERT INTO family_branch(family,name,home) VALUES(?,?,?)",f,FamilyRegister.label(name),home);
            s.update("UPDATE kin SET branch=? WHERE id=?",s.number("SELECT last_insert_rowid()"),who);return null;
        });
    }
    void guard(int f,int ward,int guardian)throws SQLException{
        book.own(f,ward);book.own(f,guardian);
        require(book.person(ward).age(book.season())<18 && book.person(guardian).age(book.season())>=18,"须为幼主选择成年监护人");
        s.update("INSERT OR REPLACE INTO family_guard VALUES(?,?)",ward,guardian);
    }
    int regent(int id)throws SQLException{
        int designated=s.number("SELECT guardian FROM family_guard JOIN kin ON id=guardian WHERE ward=? AND death IS NULL",id);
        if(designated>0)return designated;
        return s.number("SELECT id FROM kin WHERE family=? AND death IS NULL AND birth<=? ORDER BY CASE WHEN id IN (SELECT parent FROM kin WHERE id=? UNION SELECT other FROM kin WHERE id=?) THEN 0 ELSE 1 END,birth,id LIMIT 1",book.person(id).family(),book.season()-72,id,id);
    }
    void steward(int issuer,int worker,String manor,String kind)throws SQLException{
        require(book.account(issuer).equals(s.value("SELECT owner FROM manor WHERE id=?",manor)),"须由私庄业主签托");
        var p=book.person(worker);require(p.alive() && manor.equals(p.home()),"承办人须住在本庄");
        require(s.number("SELECT count(*) FROM family_duty WHERE person=? AND kind=? AND qualified=1",worker,kind)==1,"承办人须先出师");
        String actor=book.actorFor(worker);require(s.number("SELECT count(*) FROM title WHERE lawful=? OR seal=?",actor,actor)==0,"在任官员不能兼全时管事");
        s.update("INSERT OR REPLACE INTO family_steward VALUES(?,?,?,?)",worker,manor,kind,book.account(issuer));
    }
    boolean stewards(String account,String manor,String kind)throws SQLException{
        return s.number("""
            SELECT count(*) FROM family_steward d JOIN manor m ON m.id=d.manor JOIN kin k ON k.id=d.person JOIN family f ON f.id=k.family
            WHERE m.owner=d.issuer AND d.manor=? AND d.kind=? AND k.home=m.id AND k.death IS NULL AND f.account=? AND f.rep IS NOT NULL AND f.ended IS NULL
            AND NOT EXISTS(SELECT 1 FROM title WHERE seal=('族人·'||k.id) OR lawful=('族人·'||k.id)
              OR (f.rep=k.id AND (seal=f.account OR lawful=f.account)))""",manor,kind,account)>0;
    }
    int receipts(String manor,String kind)throws SQLException{
        if(kind.equals("铁工"))return s.number("SELECT count(*) FROM smith_order WHERE manor=? AND stage=3",manor);
        return s.number("SELECT count(*) FROM population_season WHERE manor=?",manor);
    }
    void apprentice(int f,int id,String manor,String kind)throws SQLException{
        book.own(f,id);var p=book.person(id);
        require(manor.equals(p.home()) && List.of("铁工","账房").contains(kind),"须选本地的铁工或账房");
        require(s.number("SELECT count(*) FROM title WHERE lawful=? OR seal=?",book.actorFor(id),book.actorFor(id))==0,"任官期间不能兼任全时学徒");
        if(kind.equals("铁工"))require(s.number("SELECT count(*) FROM smithy WHERE manor=?",manor)>0,"本庄尚无登记铁匠铺");
        else require(s.number("SELECT count(*) FROM manor_room WHERE manor=? AND kind='议事厅'",manor)>0,"本庄须有登记议事厅");
        require(s.update("INSERT OR IGNORE INTO family_duty(person,kind,manor,start,baseline) VALUES(?,?,?,?,?)",id,kind,manor,book.season(),receipts(manor,kind))==1,"已有学业记录");
    }
    void graduate(int f,int id)throws SQLException{
        book.own(f,id);
        try(var q=s.sql("SELECT * FROM family_duty WHERE person=? AND qualified=0",id);var r=q.executeQuery()){
            require(r.next(),"没有待验学业");require(book.season()>r.getInt("start") && receipts(r.getString("manor"),r.getString("kind"))>=r.getInt("baseline")+2,"须跨季且有两份新回执");
        }
        s.update("UPDATE family_duty SET qualified=1 WHERE person=?",id);
    }
}
