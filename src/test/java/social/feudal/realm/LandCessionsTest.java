package social.feudal.realm;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LandCessionsTest {
    @TempDir Path dir;
    private final Charters.Bounds river = new Charters.Bounds(65,0,66,0);
    private final String donor = "东境伯家";
    private final String recipient = "主教座堂";

    private RealmStore open() throws Exception {
        var store = new RealmStore(dir.resolve("realm.sqlite"));
        store.initialize("world",0,0,"署理");
        new FiscalLedger(store);
        return store;
    }

    @Test void transferCarriesJurisdictionButPreservesPrivateAndAccruedRights() throws Exception {
        try (var s = open()) {
            var registers = new SettlementRegister(s);
            registers.estate("shore","河岸庄",donor,1041,64,1,new Charters.Bounds(1040,0,1050,10));
            s.foundDepot(donor,"bank","河岸仓",65,0);
            s.foundDepot(donor,"ferry","渡口仓",66,0);
            var f = new FiscalLedger(s);
            new Charters(s).estate(donor,"march","地租","parish","教区粮租","堂区",f.depot("ferry"));
            f.begin(2);f.prepare(f.depot("bank"),2);
            s.politics.cessions.draft("banks","march","abbey",donor,river,"河岸协定");
            assertEquals("march",s.owner(65,0));
        }
        try (var s = new RealmStore(dir.resolve("realm.sqlite"))) {
            s.politics.sign("banks","abbey",recipient);
            assertEquals("abbey",s.owner(65,0));assertEquals("abbey",s.owner(66,0));
            assertEquals(donor,new SettlementRegister(s).manor("shore").owner());
            assertThrows(IllegalArgumentException.class,()->s.politics.sign("banks","abbey",recipient));
            var f = new FiscalLedger(s);f.begin(3);
            assertEquals("march",f.account("bank",2).title());
            assertEquals("march",s.value("SELECT DISTINCT title FROM crate WHERE depot='bank'"));
            assertEquals("abbey",f.account("bank",3).title());
            assertEquals("parish",f.account("ferry",3).title());
            assertNull(s.value("SELECT id FROM association WHERE id='banks'"));
        }
    }

    @Test void competingTransfersFailAsAWholeAndCanBeWithdrawn() throws Exception {
        try (var s = open()) {
            var c = s.politics.cessions;
            c.draft("first","march","abbey",donor,river,"两岸移交");
            c.draft("second","march","abbey",donor,new Charters.Bounds(66,0,66,0),"一岸移交");
            s.politics.sign("second","abbey",recipient);
            assertThrows(IllegalArgumentException.class,()->s.politics.sign("first","abbey",recipient));
            assertEquals("march",s.owner(65,0));
            assertEquals("待批准",s.value("SELECT state FROM treaty WHERE id='first'"));
            assertEquals(1,s.politics.unsigned("first"));
            c.cancel("first","march",donor);
            assertThrows(IllegalArgumentException.class,()->s.politics.sign("first","abbey",recipient));
            assertThrows(IllegalArgumentException.class,()->c.cancel("second","march",donor));
        }
    }

    @Test void protectCapitalVassalsAndSeals() throws Exception {
        try (var s = open()) {
            var c = s.politics.cessions;
            assertThrows(IllegalArgumentException.class,()->c.draft("x","march","abbey",donor,new Charters.Bounds(64,0,64,0),"首都"));
            assertThrows(IllegalArgumentException.class,()->c.draft("x","crown","abbey","署理",river,"越权"));
            assertThrows(IllegalArgumentException.class,()->c.draft("x","march","march",donor,river,"自让"));
            assertThrows(IllegalArgumentException.class,()->c.draft("x","march","abbey",donor,new Charters.Bounds(65,1,71,7),"范围过大"));
            c.draft("banks","march","abbey",donor,river,"移交");
            s.update("UPDATE title SET seal='新任',lawful='新任' WHERE id='march'");
            assertThrows(IllegalArgumentException.class,()->s.politics.sign("banks","abbey",recipient));
            assertThrows(IllegalArgumentException.class,()->c.cancel("banks","march",donor));
            c.cancel("banks","abbey",recipient);
            assertEquals("已谢绝",s.value("SELECT state FROM treaty WHERE id='banks'"));
            assertEquals("march",s.owner(65,0));
        }
    }
}
