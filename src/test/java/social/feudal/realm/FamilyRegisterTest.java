package social.feudal.realm;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FamilyRegisterTest {
    @TempDir Path dir;
    private static final String A="cfe7f76d-104c-4ee7-a3d3-c253c3448765", B="6c024848-6627-4f14-b354-b47555a1bf4d";
    private RealmStore init()throws Exception{
        var s=new RealmStore(dir.resolve("realm.sqlite"));s.initialize("world",0,0,"npc");
        new SettlementRegister(s);new MarketBids(s);
        var fiscal=new FiscalLedger(s);new Succession(s,new Charters(s),fiscal);
        s.update("CREATE TABLE pit_contract(pit TEXT,issuer TEXT,operator TEXT)");
        s.update("INSERT INTO manor(id,name,owner,x,y,z,west,north,east,south,residents,housing) VALUES('oak','橡庄',?,0,70,0,-8,-8,8,8,100,600),('ash','梣庄',?,80,70,0,70,-8,88,8,100,600)",A,B);
        return s;
    }
    @Test void deathThenReopenSeparatesSealCargoAndHome()throws Exception{
        int f,first,sib;
        try(var s=init()){
            var book=new FamilyRegister(s);f=book.found(A,"河西","马丁","麦穗");first=book.rep(f);sib=book.addPerson(f,"于格",-80,"oak",null,null,first,"男");
            s.update("UPDATE title SET lawful=?,seal=?,charter='流官' WHERE id='march'",A,A);
            s.update("UPDATE title SET lawful=?,seal=?,charter='收益封契' WHERE id='abbey'",A,A);
            s.update("INSERT INTO treasury VALUES('march',31)");
            var forecast=book.report(book.preview(first));assertTrue(forecast.contains("于格"));assertTrue(forecast.contains("待任"));
            assertEquals(forecast,book.die(A,first));assertEquals(forecast,book.die(A,first));
            assertEquals(0,book.rep(f));assertEquals("待任",s.title("march").seal());assertEquals(FamilyRegister.personal(sib),s.title("abbey").seal());
            assertEquals(31,s.number("SELECT grain FROM treasury WHERE title='march'"));assertEquals(99,s.number("SELECT residents FROM manor WHERE id='oak'"));
        }
        try(var s=new RealmStore(dir.resolve("realm.sqlite"))){
            var book=new FamilyRegister(s);book.assume(f,sib);
            assertEquals(A,s.title("abbey").seal());assertEquals(List.of(),book.successors(sib));
            book.die(A,sib);assertEquals(0,book.family(A));assertTrue(s.value("SELECT owner FROM manor WHERE id='oak'").startsWith("遗产·"));
            int next=book.found(A,"新家","新来者","白石");assertNotEquals(f,next);
            assertEquals(0,s.number("SELECT count(*) FROM family_asset WHERE person=?",book.rep(next)));
        }
    }
    @Test void marriageWithActualPeopleAndIndependentSignatures()throws Exception{
        try(var s=init()){
            var book=new FamilyRegister(s);var life=new FamilyLife(book);int fa=book.found(A,"西家","甲","船"),fb=book.found(B,"东家","乙","树");
            int a=book.rep(fa),b=book.rep(fb);int bride=book.living(fa).getLast();int match=life.marriage(fa,bride,b,"oak",3,1);
            assertThrows(IllegalArgumentException.class,()->life.sign(fa,match));life.sign(fb,match);
            assertThrows(IllegalArgumentException.class,()->life.sign(fb,match));
            assertThrows(IllegalArgumentException.class,()->life.marriage(fa,bride,0,"oak",0,0));
            assertThrows(IllegalArgumentException.class,()->life.raising(fa,match,true));life.raising(fb,match,true);
            int local=life.marriage(fa,a,0,"oak",0,0);life.raising(fa,local,true);
            assertEquals(100,s.number("SELECT residents FROM manor WHERE id='oak'"));
            life.yearAt("oak",4,0);assertEquals(1,s.number("SELECT last_year FROM family_match WHERE id=?",local));life.yearAt("oak",4,9);
            assertEquals(0,s.number("SELECT count(*) FROM kin WHERE parent IS NOT NULL"));
            book.die(B,b);assertEquals("遗属",s.value("SELECT state FROM family_match WHERE id=?",match));
        }
    }
    @Test void childEligibilityHouseAndAgeAreIndependent()throws Exception{
        try(var s=init()){
            var book=new FamilyRegister(s);var life=new FamilyLife(book);int fa=book.found(A,"西家","甲","船"),fb=book.found(B,"东家","乙","树");
            int a=book.rep(fa),kin=book.living(fa).getLast();int child=book.addPerson(fa,"儿子",-4,"oak",a,null,null,"男");
            int foreignChild=book.addPerson(fb,"儿子",-3,"ash",book.rep(fb),a,null,"男");
            assertEquals(List.of(child),book.successors(a));assertEquals(List.of(child,foreignChild),book.heirs(a));
            assertThrows(IllegalArgumentException.class,()->book.prefer(fa,kin));life.guard(fa,child,kin);
            assertEquals(kin,life.regent(child));book.die(A,a);book.assume(fa,child);assertEquals(1,book.person(child).age(book.season()));
            assertThrows(IllegalArgumentException.class,()->book.assume(fa,foreignChild));
        }
    }
    @Test void threeGenerationsPartitionIncomeAndOneBirthPerYear()throws Exception{
        try(var s=init()){
            var book=new FamilyRegister(s);var life=new FamilyLife(book,()->0,()->true);
            int f=book.found(A,"桥西","木匠","桥"),a=book.rep(f);
            int firstMarriage=life.marriage(f,a,0,"oak",0,0);life.raising(f,firstMarriage,true);
            life.yearAt("oak",4,1);life.yearAt("oak",4,9);life.yearAt("oak",8,1);
            s.update("INSERT OR REPLACE INTO setting VALUES('season',8)");
            var children=book.successors(a);assertEquals(2,children.size());
            book.holdings.value(a,"庄产","oak",40);
            assertEquals(List.of(5000,5000),book.holdings.divide(a,children,"普通分割").getFirst().shares().values().stream().toList());
            book.die(A,a);book.assume(f,children.getFirst());int second=book.rep(f);
            assertEquals(5000,book.holdings.lots(second).getFirst().deceasedShare());
            assertEquals(book.living(f).getFirst(),life.regent(second));assertThrows(IllegalArgumentException.class,()->life.marriage(f,second,0,"oak",0,0));
            s.update("INSERT OR REPLACE INTO setting VALUES('season',84)");
            int marriage=life.marriage(f,second,0,"oak",0,0);life.raising(f,marriage,true);
            s.update("INSERT OR REPLACE INTO setting VALUES('season',88)");
            life.yearAt("oak",88,1);var third=book.successors(second).getFirst();
            book.die(A,second);book.assume(f,third);
            assertEquals(5000,book.holdings.lots(third).getFirst().deceasedShare());
            assertEquals(10000,s.number("SELECT sum(units) FROM family_interest WHERE asset='oak'"));
            assertEquals(2,s.number("SELECT count(*) FROM life_receipt"));
        }
    }
    @Test void retiredOfficeMandateEndsWhenRepresentativeChanges()throws Exception{
        try(var s=init()){
            var book=new FamilyRegister(s);int f=book.found(A,"东家","总督","树"),first=book.rep(f);
            int brother=book.addPerson(f,"于格",-90,"oak",null,null,first,"男");
            s.update("UPDATE title SET lawful=?,seal=?,charter='流官' WHERE id='march'",A,A);
            book.retire(f,brother);assertEquals(FamilyRegister.personal(first),s.title("march").seal());
            assertFalse(book.delegated(A,"march","亲收税箱"));book.mandate(f,"march","亲收税箱",1);
            assertTrue(book.delegated(A,"march","亲收税箱"));assertFalse(book.delegated(B,"march","亲收税箱"));
            assertFalse(book.delegated(A,"march","交印"));s.update("INSERT OR REPLACE INTO setting VALUES('season',1)");
            assertFalse(book.delegated(A,"march","亲收税箱"));
        }
    }
    @Test void predeceasedBranchAndLiquidEstateUseExactShares()throws Exception{
        try(var s=init()){
            var b=new FamilyRegister(s);int f=b.found(A,"庄家","父","橡"),p=b.rep(f);
            int first=b.addPerson(f,"长子",-50,"oak",p,null,null,"男"),younger=b.addPerson(f,"次子",-40,"oak",p,null,null,"男");
            int grand=b.addPerson(f,"长孙",-8,"oak",first,null,null,"男"),sibling=b.addPerson(f,"次孙",-5,"oak",first,null,null,"男");
            s.update("UPDATE kin SET death=0 WHERE id=?",first);
            var heirs=b.heirs(p);assertEquals(List.of(grand,sibling,younger),heirs);
            assertArrayEquals(new long[]{2500,2500,5000},b.portions(p,heirs,10000,"普通分割"));
            s.update("INSERT INTO family_cash(id,owner,amount,manor,packed) VALUES('coin',?,30,'oak',1)",p);
            s.update("INSERT INTO family_asset VALUES('存银','coin',?)",p);
            b.holdings.value(p,"庄产","oak",30);
            var awards=b.holdings.divide(p,heirs,"普通分割");assertEquals(2,awards.size());
            assertEquals(Map.of(younger,10000),awards.getFirst().shares());
            assertEquals(Map.of(grand,5000,sibling,5000),awards.getLast().shares());
        }
    }
    @Test void titlesChooseLocallyAndNeverTakeAnotherPlayersRepresentative()throws Exception{
        try(var s=init()){
            var b=new FamilyRegister(s);
            int f=b.found(A,"西家","父","麦"),foreign=b.found(B,"东家","婿","舟");
            int father=b.rep(f),daughter=b.addPerson(f,"女儿",-84,"oak",father,null,null,"女");
            int grandson=b.addPerson(foreign,"外孙",-8,"ash",daughter,b.rep(foreign),null,"男");
            s.update("UPDATE title SET charter='收益封契',lawful=?,seal=? WHERE id IN ('march','abbey')",A,A);
            b.lineage.setLaw("abbey",A,"女系递传","沿用家法");
            s.update("INSERT INTO family_nomination VALUES('march',?)",daughter);
            assertThrows(IllegalArgumentException.class,()->b.prefer(f,daughter));
            for(String law:FamilyRegister.LAWS){
                s.update("UPDATE family SET law=? WHERE id=?",law,f);
                assertTrue(b.successors(father).isEmpty());assertTrue(b.heirs(father).isEmpty());
                var rights=b.preview(father);
                assertTrue(rights.stream().anyMatch(r->r.key().equals("march") && r.destination().equals(Succession.VACANT)));
                assertTrue(rights.stream().anyMatch(r->r.key().equals("abbey") && r.destination().equals(FamilyRegister.personal(grandson))));
            }
            b.die(A,father);assertEquals(0,b.family(A));assertEquals(foreign,b.person(grandson).family());
            assertThrows(IllegalArgumentException.class,()->b.assume(f,grandson));
        }
    }
    @Test void onlyAfterSonsAndBrothersCanAnAiGrandsonJoin()throws Exception{
        try(var s=init()){
            var b=new FamilyRegister(s);var life=new FamilyLife(b,()->0,()->true);
            int f=b.found(A,"河家","祖父","柳"),p=b.rep(f);
            int d=b.addPerson(f,"长女",-80,"oak",p,null,null,"女");
            int marriage=life.marriage(f,d,0,"oak",0,0);life.raising(f,marriage,true);
            life.yearAt("oak",4,1);int grandson=s.number("SELECT id FROM kin WHERE parent=?",d);
            int natal=b.person(grandson).family();assertTrue(b.lineage.ai(natal));
            int brother=b.addPerson(f,"弟",-85,"oak",null,null,p,"男");
            int son=b.addPerson(f,"长子",-82,"oak",p,null,null,"男");
            assertEquals(List.of(son),b.successors(p));
            s.update("UPDATE kin SET death=1 WHERE id=?",son);
            assertEquals(List.of(brother),b.successors(p));
            s.update("UPDATE kin SET death=2 WHERE id=?",brother);
            assertEquals(List.of(grandson),b.successors(p));b.die(A,p);b.assume(f,grandson);
            assertEquals(f,b.person(grandson).family());assertEquals(natal,s.number("SELECT birth_house FROM family_adoption WHERE person=?",grandson));
            assertEquals(natal,b.person(b.person(grandson).other()).family());
        }
    }
    @Test void legacyFemaleRepresentativeRetainsHerRightsAndCannotBePlayed()throws Exception{
        try(var s=init()){
            var b=new FamilyRegister(s);int f=b.found(A,"柳家","初代","柳"),founder=b.rep(f),sister=b.living(f).getLast();
            s.update("UPDATE family SET rep=? WHERE id=?",sister,f);
            s.update("UPDATE family_asset SET person=? WHERE person=?",sister,founder);
            s.update("UPDATE kin SET death=2 WHERE id=?",founder);
            s.update("UPDATE title SET seal=?,lawful=? WHERE id='march'",A,A);
            new FamilyRegister(s);
            assertEquals(0,b.family(A));assertTrue(b.person(sister).alive());
            assertEquals(FamilyRegister.personal(sister),s.title("march").seal());
            assertEquals(FamilyRegister.personal(sister),s.value("SELECT owner FROM manor WHERE id='oak'"));
        }
    }
    @Test void eachTitleMayUseItsOwnBirthOrder()throws Exception{
        try(var s=init()){
            var b=new FamilyRegister(s);int f=b.found(A,"河家","祖父","柳"),p=b.rep(f);
            int older=b.addPerson(f,"伯",-72,"oak",p,null,null,"男");
            int younger=b.addPerson(f,"季",-66,"oak",p,null,null,"男");
            s.update("UPDATE title SET charter='收益封契',lawful=?,seal=? WHERE id IN ('march','abbey')",A,A);
            b.lineage.setLaw("march",A,"父系男嗣","幼嗣");b.lineage.setLaw("abbey",A,"父系男嗣","长嗣");
            var rights=b.preview(p);
            assertTrue(rights.stream().anyMatch(r->r.key().equals("march") && r.destination().equals(FamilyRegister.personal(younger))));
            assertTrue(rights.stream().anyMatch(r->r.key().equals("abbey") && r.destination().equals(FamilyRegister.personal(older))));
            b.prefer(f,younger);b.die(A,p);b.assume(f,younger);
            assertEquals(FamilyRegister.personal(older),s.title("abbey").seal());assertEquals(A,s.title("march").seal());
        }
    }
    @Test void oldLocalMarriageAcquiresPaternalHouseOnceWhenUpgraded()throws Exception{
        try(var s=init()){
            var b=new FamilyRegister(s);int f=b.found(A,"橡家","甲","叶"),p=b.rep(f),d=b.living(f).getLast();
            int husband=b.addPerson(f,"旧乡人",-100,"oak",null,null,null,"男");
            int child=b.addPerson(f,"旧子嗣",-2,"oak",husband,d,null,"男");
            s.update("INSERT INTO family_match(a,b,children,home,dowry,state) VALUES(?,?,?,'oak',0,'已婚')",d,husband,f);
            try(var q=s.sql("ALTER TABLE kin DROP COLUMN sex")){q.execute();}
            var migrated=new FamilyRegister(s);int paternal=migrated.person(husband).family();
            assertTrue(migrated.lineage.ai(paternal));assertEquals("女",migrated.person(d).sex());
            assertEquals(paternal,migrated.person(child).family());assertEquals(paternal,s.number("SELECT children FROM family_match"));
            String sex=migrated.person(child).sex();
            var again=new FamilyRegister(s);assertEquals(sex,again.person(child).sex());
            assertEquals(paternal,again.person(child).family());assertEquals(p,again.rep(f));
            assertEquals(1,s.number("SELECT count(*) FROM ai_house"));
        }
    }
}
