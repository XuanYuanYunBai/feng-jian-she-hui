package social.feudal.realm;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AtlasTest {
    @TempDir Path temp;
    @Test void threeSeatsWithIndependentSuperiorsSurviveRestart() throws Exception {
        Path file=temp.resolve("realm.sqlite");
        try(var db=new RealmStore(file)){
            db.initialize("world",-2,-3,"alice");
            assertEquals(768,db.lands().size());
            assertEquals("crown",db.owner(-10,-11));
            assertNull(db.owner(6,-3));
            assertEquals("march",db.owner(62,-3));
            assertEquals("crown",db.title("abbey").parent());
            assertThrows(IllegalArgumentException.class,()->db.initialize("world",0,0,"other"));
        }
        try(var db=new RealmStore(file)){
            assertThrows(IllegalArgumentException.class,()->db.checkWorld("other"));
            assertEquals("圣弥额尔主教驻地",db.polity("abbey"));
            assertTrue(db.politics.imperial("abbey"));
            assertEquals(5,db.depots().size());
        }
    }
}
