package social.feudal.realm;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SuccessionTest {
    @TempDir Path temp;
    static final String COUNT="00000000-0000-0000-0000-000000000001", HEIR="00000000-0000-0000-0000-000000000002";
    @Test void familyEstateAndTemporarySealHaveDifferentSuccessors()throws Exception{
        Path path=temp.resolve("r");
        try(var s=new RealmStore(path)){
            s.initialize("world",0,0,"king");var f=new FiscalLedger(s);f.begin(1);var law=new Charters(s);
            var v=new Succession(s,law,f);
            v.appoint("march","king",COUNT);v.seal("march","king",true);
            law.estate(COUNT,"march","庄园","estate","庄园",COUNT,f.depot("east"));v.sync();
            v.nominate("march",COUNT,HEIR);v.nominate("estate",COUNT,"private");
            v.vacate("march",COUNT);assertEquals(Succession.VACANT,s.title("march").lawful());
            assertEquals("private",s.title("estate").seal());
            v.appoint("march","king","official");v.seal("march",HEIR,false);
        }
        try(var s=new RealmStore(path)){
            var f=new FiscalLedger(s);var v=new Succession(s,new Charters(s),f);
            assertEquals("official",s.title("march").lawful());assertEquals(HEIR,s.title("march").seal());
            assertThrows(IllegalArgumentException.class,()->v.seal("march","official",true));
            v.seal("march",HEIR,true);assertEquals("official",s.title("march").seal());
            assertEquals(16,f.balance("march"));assertEquals(0,f.balance("estate"));
        }
    }
    @Test void hereditaryConfirmationAndBilateralKingdomTreaties()throws Exception{
        try(var s=new RealmStore(temp.resolve("r"))){
            s.initialize("world",0,0,"king");var f=new FiscalLedger(s);f.begin(1);var law=new Charters(s);
            law.grant("king","crown","公爵","duke","河南",COUNT,new Charters.Bounds(1,0,7,0),false);
            var v=new Succession(s,law,f);
            v.nominate("duke",COUNT,HEIR);
            assertThrows(IllegalArgumentException.class,()->v.petition("duke",COUNT));
            v.sponsor("duke","one",2,0);v.sponsor("duke","two",3,0);
            v.petition("duke",COUNT);v.confirm("duke","king");v.vacate("duke",COUNT);
            assertEquals(HEIR,s.title("duke").lawful());assertEquals("封契",s.title("duke").charter());
            v.offer("duke","king","王号","支持皇位并保留出席帝国会议义务");
            assertThrows(IllegalArgumentException.class,()->v.ratify("duke","king"));
            v.ratify("duke",HEIR);assertEquals("国王",s.title("duke").rank());assertEquals("crown",s.title("duke").parent());
            v.offer("duke",HEIR,"独立","承认边界并放弃旧贡赋");v.ratify("duke","king");
            assertNull(s.title("duke").parent());assertEquals("河南",s.polity("duke"));
            assertThrows(IllegalArgumentException.class,()->v.ratify("duke","king"));
        }
    }
}
