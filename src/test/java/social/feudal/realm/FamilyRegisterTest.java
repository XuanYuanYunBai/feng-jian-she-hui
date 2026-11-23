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
            var book=new FamilyRegister(s);f=book.found(A,"河西","马丁","麦穗");first=book.rep(f);sib=book.successors(first).getFirst();
            s.update("UPDATE title SET lawful=?,seal=?,charter='流官' WHERE id='march'",A,A);
            s.update("UPDATE title SET lawful=?,seal=?,charter='收益封契' WHERE id='abbey'",A,A);
            s.update("INSERT INTO treasury VALUES('march',31)");
            var forecast=book.report(book.preview(first));assertTrue(forecast.contains("阿黛尔"));assertTrue(forecast.contains("待任"));
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
            int a=book.rep(fa),b=book.rep(fb);int match=life.marriage(fa,a,b,"oak",true,3,1);
            assertThrows(IllegalArgumentException.class,()->life.sign(fa,match));life.sign(fb,match);
            assertThrows(IllegalArgumentException.class,()->life.sign(fb,match));
            assertThrows(IllegalArgumentException.class,()->life.marriage(fa,a,0,"oak",false,0,0));
            assertThrows(IllegalArgumentException.class,()->life.raising(fa,match,true));life.raising(fb,match,true);
            int relative=book.successors(a).getFirst();int local=life.marriage(fa,relative,0,"oak",false,0,0);life.raising(fa,local,true);
            assertEquals(100,s.number("SELECT residents FROM manor WHERE id='oak'"));
            life.yearAt("oak",4,0);assertEquals(1,s.number("SELECT last_year FROM family_match WHERE id=?",local));life.yearAt("oak",4,9);
            assertEquals(0,s.number("SELECT count(*) FROM kin WHERE parent IS NOT NULL"));
            book.die(B,b);assertEquals("遗属",s.value("SELECT state FROM family_match WHERE id=?",match));
        }
    }
    @Test void childEligibilityHouseAndAgeAreIndependent()throws Exception{
        try(var s=init()){
            var book=new FamilyRegister(s);var life=new FamilyLife(book);int fa=book.found(A,"西家","甲","船"),fb=book.found(B,"东家","乙","树");
            int a=book.rep(fa),kin=book.successors(a).getFirst();int child=book.addPerson(fa,"女儿",-4,"oak",a,null,null);
            int foreignChild=book.addPerson(fb,"儿子",-3,"ash",book.rep(fb),a,null);
            assertEquals(List.of(child),book.successors(a));assertEquals(List.of(child,foreignChild),book.heirs(a));
            assertThrows(IllegalArgumentException.class,()->book.prefer(fa,kin));life.guard(fa,child,kin);
            assertEquals(kin,life.regent(child));book.die(A,a);book.assume(fa,child);assertEquals(1,book.person(child).age(book.season()));
            assertThrows(IllegalArgumentException.class,()->book.assume(fa,foreignChild));
        }
    }
    @Test void threeGenerationsPartitionIncomeAndOneBirthPerYear()throws Exception{
        try(var s=init()){
            var book=new FamilyRegister(s);var life=new FamilyLife(book,()->0);
            int f=book.found(A,"桥西","木匠","桥"),a=book.rep(f),sister=book.successors(a).getFirst();
            int firstMarriage=life.marriage(f,a,0,"oak",false,0,0);life.raising(f,firstMarriage,true);
            life.yearAt("oak",4,1);life.yearAt("oak",4,9);life.yearAt("oak",8,1);
            s.update("INSERT OR REPLACE INTO setting VALUES('season',8)");
            var children=book.successors(a);assertEquals(2,children.size());
            book.holdings.value(a,"庄产","oak",40);
            assertEquals(List.of(5000,5000),book.holdings.divide(a,children,"普通分割").getFirst().shares().values().stream().toList());
            book.die(A,a);book.assume(f,children.getFirst());int second=book.rep(f);
            assertEquals(5000,book.holdings.lots(second).getFirst().deceasedShare());
            assertEquals(book.person(second).other(),life.regent(second));assertThrows(IllegalArgumentException.class,()->life.marriage(f,second,0,"oak",false,0,0));
            s.update("INSERT OR REPLACE INTO setting VALUES('season',84)");
            int marriage=life.marriage(f,second,0,"oak",false,0,0);life.raising(f,marriage,true);
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
            int sister=book.successors(first).getFirst();
            s.update("UPDATE title SET lawful=?,seal=?,charter='流官' WHERE id='march'",A,A);
            book.retire(f,sister);assertEquals(FamilyRegister.personal(first),s.title("march").seal());
            assertFalse(book.delegated(A,"march","亲收税箱"));book.mandate(f,"march","亲收税箱",1);
            assertTrue(book.delegated(A,"march","亲收税箱"));assertFalse(book.delegated(B,"march","亲收税箱"));
            assertFalse(book.delegated(A,"march","交印"));s.update("INSERT OR REPLACE INTO setting VALUES('season',1)");
            assertFalse(book.delegated(A,"march","亲收税箱"));
        }
    }
    @Test void predeceasedBranchAndLiquidEstateUseExactShares()throws Exception{
        try(var s=init()){
            var b=new FamilyRegister(s);int f=b.found(A,"庄家","父","橡"),p=b.rep(f);
            int first=b.addPerson(f,"长女",-50,"oak",p,null,null),younger=b.addPerson(f,"次女",-40,"oak",p,null,null);
            int grand=b.addPerson(f,"长孙",-8,"oak",first,null,null),sibling=b.addPerson(f,"次孙",-5,"oak",first,null,null);
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
}
