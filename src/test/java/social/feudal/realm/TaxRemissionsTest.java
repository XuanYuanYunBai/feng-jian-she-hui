package social.feudal.realm;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TaxRemissionsTest {
    @TempDir Path dir;
    private FiscalLedger setup(RealmStore s)throws Exception {
        s.initialize("world",0,0,"king");
        new SettlementRegister(s);
        s.update("INSERT INTO manor(id,name,owner,x,y,z,west,north,east,south) VALUES('oak','橡庄','family',0,64,0,-4,-4,4,4)");
        s.update("CREATE TABLE relief(id INTEGER PRIMARY KEY,dest TEXT,title TEXT,stage INTEGER)");
        s.update("INSERT INTO relief VALUES(1,'oak','crown',0)");
        var f=new FiscalLedger(s);f.begin(1,Map.of());
        s.update("UPDATE title SET seal='abbot' WHERE id='abbey'");
        s.update("INSERT INTO tax_right VALUES('mill','abbey')");
        return f;
    }
    @Test void splitRightsRatifyNetWheatAndReplayDoesNotTaxExemptStock()throws Exception {
        Path file=dir.resolve("tax.sqlite");
        try(var s=new RealmStore(file)){
            var f=setup(s);var grants=f.remissions;
            assertThrows(IllegalArgumentException.class,()->grants.propose(1,"mill",2,50,"abbot"));
            int id=grants.propose(1,"mill",2,50,"king");
            assertThrows(IllegalArgumentException.class,()->grants.propose(1,"mill",2,100,"king"));
            assertThrows(IllegalArgumentException.class,()->grants.sign(id,"king"));
            grants.sign(id,"abbot");
            s.update("UPDATE relief SET stage=3");
        }
        try(var s=new RealmStore(file)){
            var f=new FiscalLedger(s);
            f.begin(2,Map.of("mill",21));f.prepare(f.depot("mill"),2);
            assertEquals(11,f.account("mill",2).due());assertEquals(0,f.quota("mill",2));
            assertEquals(10,f.remissions.latest("mill",2).spared());
            assertEquals(11,f.crate(f.pending("mill").getFirst()).amount());
            f.begin(2,Map.of("mill",100));assertEquals(11,f.account("mill",2).due());
            f.begin(3,Map.of("mill",12));assertEquals(12,f.account("mill",3).due());
        }
    }
    @Test void fullWaiverRecordsZeroDueAndHasNoManifest()throws Exception {
        try(var s=new RealmStore(dir.resolve("full.sqlite"))){
            var f=setup(s);var g=f.remissions;
            int old=g.propose(1,"town",2,33,"king");g.revoke(old,"king");
            assertThrows(IllegalArgumentException.class,()->g.sign(old,"king"));
            int id=g.propose(1,"town",2,100,"king");
            assertEquals("已核准",g.get(id).state());
            f.begin(2,Map.of());f.begin(2,Map.of("town",24));f.prepare(f.depot("town"),2);
            assertEquals(24,g.get(id).spared());assertEquals(0,f.account("town",2).due());
            assertTrue(f.pending("town").isEmpty());
            assertThrows(IllegalArgumentException.class,()->g.revoke(id,"king"));
        }
    }
    @Test void unsignedChangedOrCancelledAuthorityCannotReduceTaxes()throws Exception {
        try(var s=new RealmStore(dir.resolve("lapse.sqlite"))){
            var f=setup(s);var g=f.remissions;
            int unsigned=g.propose(1,"mill",2,50,"king");
            int revoked=g.propose(1,"town",2,100,"king");
            int reassigned=g.propose(1,"ford",2,100,"king");
            s.update("INSERT INTO tax_right VALUES('ford','abbey')");
            f.begin(2,Map.of("mill",16,"ford",16));assertEquals("未获会签",g.get(unsigned).state());
            assertEquals("授权失效",g.get(reassigned).state());
            s.update("UPDATE relief SET stage=4");f.begin(2,Map.of("town",16));
            assertEquals("授权失效",g.get(revoked).state());
            for(String d:java.util.List.of("town","mill","ford"))assertEquals(16,f.account(d,2).due());
        }
    }
}
