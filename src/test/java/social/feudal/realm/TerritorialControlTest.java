package social.feudal.realm;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TerritorialControlTest {
    @TempDir Path dir;
    @Test void acceptingSpecificSealTransfersResidentsAndSoleControl()throws Exception{
        try(var s=new RealmStore(dir.resolve("realm.sqlite"))){
            s.initialize("overworld",0,0,"emperor");
            var register=new SettlementRegister(s);
            s.update("UPDATE title SET seal='east',lawful='east' WHERE id='march'");
            s.update("UPDATE title SET seal='abbot',lawful='abbot' WHERE id='abbey'");
            s.update("""
                INSERT INTO manor(id,name,owner,x,y,z,west,north,east,south,residents,housing)
                VALUES('orchard','果庄','private',1024,72,0,1020,-4,1028,4,240,300)""");
            TerritorialControl.offer(s,"march","abbey","east");
            assertEquals(240,population(register,"march"));
            assertThrows(IllegalArgumentException.class,()->TerritorialControl.accept(s,"march","third"));
            TerritorialControl.accept(s,"march","abbot");
            assertEquals("east",s.title("march").lawful());
            assertEquals("abbot",s.title("march").seal());
            assertEquals(0,population(register,"march"));assertEquals(240,population(register,"abbey"));
            assertEquals("private",register.manor("orchard").owner());
            assertThrows(IllegalArgumentException.class,()->TerritorialControl.accept(s,"march","abbot"));
            s.update("UPDATE title SET seal=lawful WHERE id='march'");
            assertEquals(240,population(register,"march"));assertEquals(0,population(register,"abbey"));
        }
    }
    private int population(SettlementRegister register,String id)throws Exception{
        return register.census().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow().population();
    }
    @Test void proposalAndEnactmentBothRequireTwoHundredRealResidents()throws Exception{
        try(var s=new RealmStore(dir.resolve("founding.sqlite"))){
            s.initialize("world",0,0,"the-crown");
            var reg=new SettlementRegister(s);var bounds=new Charters.Bounds(-32,-32,-32,-32);
            s.update("""
                INSERT INTO manor(id,name,owner,x,y,z,west,north,east,south,residents,housing,service)
                VALUES('oak','橡庄','reeve',-512,64,-512,-516,-516,-508,-508,199,300,1)""");
            assertThrows(IllegalArgumentException.class,()->reg.propose("reeve","oak","oak","入帝",bounds,1,60));
            s.update("UPDATE manor SET residents=200 WHERE id='oak'");
            int claim=reg.propose("reeve","oak","oak","入帝",bounds,1,60);
            s.update("INSERT OR REPLACE INTO setting VALUES('season',1)");
            s.update("UPDATE manor SET residents=199 WHERE id='oak'");
            assertThrows(IllegalArgumentException.class,()->reg.resolve(claim,"reeve",100));
            s.update("UPDATE manor SET residents=200 WHERE id='oak'");
            reg.resolve(claim,"reeve",100);
            assertEquals("reeve",s.title("oak").seal());assertEquals(200,population(reg,"oak"));
        }
    }
}
