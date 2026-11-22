package social.feudal.realm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ResidentSeasonTest {
    @TempDir Path dir;
    @Test void foodGrowthAndSevereFamine(){
        var full=ResidentSeason.close(100,200,1,0,0,0);
        assertEquals(1,full.bread());assertEquals(2,full.births());
        assertEquals(0,ResidentSeason.close(100,100,4,0,0,0).births());
        var shortSeason=ResidentSeason.close(300,400,2,0,0,0);
        assertEquals(2,shortSeason.bread());assertEquals(100,shortSeason.shortfall());assertEquals(0,shortSeason.deaths());
        assertEquals(0,ResidentSeason.close(300,400,0,0,0,1).deaths());
        assertEquals(15,ResidentSeason.close(300,400,0,0,0,2).deaths());
        var partial=ResidentSeason.close(102,200,2,0,0,0);
        assertEquals(98,partial.credit());
        assertEquals(1,ResidentSeason.close(104,200,1,partial.credit(),partial.growthRemainder(),0).bread());
    }
    @Test void migrationAndCensusFollowResidence()throws Exception{
        var path=dir.resolve("realm.sqlite");
        try(var s=new RealmStore(path)){
            s.initialize("w",0,0,"npc-皇廷署理");
            s.update("""
                CREATE TABLE manor(id TEXT PRIMARY KEY,name TEXT,owner TEXT,x INTEGER,y INTEGER,z INTEGER,
                west INTEGER,north INTEGER,east INTEGER,south INTEGER,households INTEGER,service INTEGER,settled INTEGER)""");
            s.update("INSERT INTO manor VALUES('v','东村','外地地主',1024,64,1,1020,-4,1028,4,4,0,0)");
        }
        try(var s=new RealmStore(path)){
            var register=new SettlementRegister(s);
            assertEquals(400,register.manor("v").residents());
            assertTrue(java.nio.file.Files.exists(path.resolveSibling("realm.sqlite.pre-0.11.0")));
            register.estate("out","分庄","外地地主",-500,64,0,new Charters.Bounds(-504,-4,-496,4));
            assertEquals(100,register.manor("v").residents());
            assertEquals(300,register.manor("out").residents());
            new SettlementRegister(s);
            assertEquals(100,register.census().stream().filter(row->row.id().equals("march")).findFirst().orElseThrow().population());
            assertEquals(400,register.census().stream().mapToInt(SettlementRegister.Census::population).sum());
            s.update("UPDATE land SET title='abbey' WHERE x=64 AND z=0");
            assertEquals(100,register.census().stream().filter(row->row.id().equals("abbey")).findFirst().orElseThrow().population());
        }
    }
}
