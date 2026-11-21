package social.feudal.realm;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FiscalTest {
    @TempDir Path temp;
    @Test void preparingAndRestartingNeverCreditsAnotherWarehouse()throws Exception{
        var file=temp.resolve("physical");String id;
        try(var s=new RealmStore(file)){
            s.initialize("world",0,0,"king");var f=new FiscalLedger(s);f.begin(1);f.begin(1);
            assertEquals(0,f.legacy("crown"));assertEquals(16,f.account("mill",1).remaining());
            f.prepare(f.depot("mill"),1);f.prepare(f.depot("mill"),1);
            var ids=f.pending("mill");assertEquals(2,ids.size());id=ids.getFirst();
            assertEquals(8,f.crate(id).amount());assertEquals(8,f.crate(ids.getLast()).amount());
            f.prepare(f.depot("ford"),1);
            assertEquals(16,f.crate(f.pending("ford").getFirst()).amount());assertEquals(0,f.quota("ford",1));
        }
        try(var s=new RealmStore(file)){
            var f=new FiscalLedger(s);assertEquals(id,f.pending("mill").getFirst());
            assertEquals(8,f.receive(id));assertThrows(IllegalArgumentException.class,()->f.receive(id));
            assertEquals(0,f.legacy("crown"));
        }
    }
    @Test void aBasePorterCanTakeTheFullNearQuotaInTwoRealLoads()throws Exception{
        try(var s=new RealmStore(temp.resolve("loads"))){
            s.initialize("world",0,0,"king");var f=new FiscalLedger(s);f.begin(1);
            f.prepare(f.depot("town"),1);
            var parcels=f.pending("town");assertEquals(2,parcels.size());
            for(var id:parcels)assertEquals(8,f.crate(id).amount());
            assertEquals(0,f.legacy("crown"));
        }
    }
    @Test void upgradesOnlyStageUncollectedOldTax()throws Exception{
        try(var s=new RealmStore(temp.resolve("upgrade"))){
            s.initialize("world",0,0,"king");var f=new FiscalLedger(s);
            s.update("INSERT INTO assessment VALUES('mill',1,'crown',16,8,3)");
            s.update("INSERT INTO treasury VALUES('crown',27)");
            f.begin(1);f.prepare(f.depot("mill"),1);assertEquals(27,f.legacy("crown"));
            assertEquals(5,f.crate(f.pending("mill").getFirst()).amount());
            assertEquals(0,f.quota("mill",1));
        }
    }
    @Test void distanceIsFixedAtAssessmentAndOnlyCapsAutomatedTransport()throws Exception{
        try(var s=new RealmStore(temp.resolve("quota"))){
            s.initialize("world",0,0,"king");var f=new FiscalLedger(s);f.begin(1);
            assertArrayEquals(new int[]{100,75,50,25,0,0},java.util.stream.IntStream.of(3,4,5,6,7,10000).map(FiscalLedger::rate).toArray());
            s.foundDepot("king","south","南仓",0,6);
            assertThrows(IllegalArgumentException.class,()->s.foundDepot("king","other","另一仓",0,6));
            assertThrows(IllegalArgumentException.class,()->s.foundDepot("king","eastgate","东仓",64,1));
            var wider=new FiscalLedger(s,2);wider.begin(1);assertEquals(8,wider.quota("mill",1));
            assertThrows(IllegalArgumentException.class,()->wider.account("south",1));
            wider.begin(2);assertEquals(16,wider.quota("south",2));assertEquals(12,wider.quota("ford",2));
            assertEquals(16,wider.account("ford",2).remaining());
        }
    }
    @Test void visitsRequireConsentAndLeaveAReserveAtSource()throws Exception{
        try(var s=new RealmStore(temp.resolve("visit"))){
            s.initialize("world",0,0,"king");var f=new FiscalLedger(s);f.begin(1);
            assertThrows(IllegalArgumentException.class,()->f.visit("march","another",0,16));
            assertEquals(4,f.visit("march","king",0,16));
            assertThrows(IllegalArgumentException.class,()->f.visit("march","king",0,16));
            assertEquals(0,f.visit("abbey","king",0,12));
        }
    }
}
