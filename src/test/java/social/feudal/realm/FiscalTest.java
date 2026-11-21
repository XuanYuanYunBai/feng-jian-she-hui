package social.feudal.realm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FiscalTest {
 @TempDir Path temp;
 @Test void equalYieldAndSingleRedemptionAcrossRestart() throws Exception {
    Path file=temp.resolve("f.db");String id;
    try(var s=new RealmStore(file)){
        s.initialize("w",0,0,"king");var f=new FiscalLedger(s);
        f.begin(1);f.begin(1);
        assertArrayEquals(new int[]{100,75,50,25,0,0},
                java.util.stream.IntStream.of(3,4,5,6,7,10000).map(FiscalLedger::rate).toArray());
        assertEquals(24,f.balance("crown"));assertEquals(8,f.account("mill",1).remaining());
        assertEquals(0,f.account("ford",1).automatic());
        assertThrows(IllegalArgumentException.class,()->f.collect("east",1,"king"));
        assertEquals(8,f.collect("mill",1,"king").amount());
        id=f.collect("ford",1,"king").id();
        assertThrows(IllegalArgumentException.class,()->f.collect("ford",1,"king"));
    }
    try(var s=new RealmStore(file)){
        var f=new FiscalLedger(s);assertEquals(id,f.outstanding("ford",1,"king").id());
        assertEquals(16,f.receive(id));assertEquals(40,f.balance("crown"));
        assertThrows(IllegalArgumentException.class,()->f.receive(id));
        f.gift("march","东境伯家",5);assertEquals(45,f.balance("crown"));
        assertThrows(IllegalArgumentException.class,()->f.gift("march","东境伯家",12));
        assertEquals(11,f.balance("march"));f.begin(2);assertEquals(16,f.account("ford",2).remaining());
    }
 }
}
