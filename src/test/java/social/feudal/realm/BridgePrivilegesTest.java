package social.feudal.realm;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BridgePrivilegesTest {
    @TempDir Path dir;

    private BorderCourt setUp(RealmStore s) throws Exception {
        s.initialize("test",0,0,"署理");
        var court=new BorderCourt(s);
        court.bridge("东境伯家","ford","东桥","march",1024,70,0,3);
        court.bridge("东境伯家","west","西桥","march",1025,70,0,2);
        return court;
    }

    @Test void concessionRunsWithTheCrossingAndCountryAfterChangesOfHolder() throws Exception {
        Path path=dir.resolve("realm.sqlite");
        int grant;
        try(var s=new RealmStore(path)){
            var c=setUp(s);
            grant=c.privileges.grant("东境伯家","ford","abbey",2);
            assertEquals(1,c.privileges.redeem("主教座堂","ford",grant,100));
            assertThrows(IllegalArgumentException.class,()->c.privileges.redeem("主教座堂","ford",grant,101));
            assertThrows(IllegalArgumentException.class,()->c.privileges.checkPassage("ford","主教座堂",101));
            s.update("UPDATE title SET lawful='新任',seal='新任' WHERE id='abbey'");
            s.update("UPDATE crossing SET owner='abbey' WHERE id='ford'");
        }
        try(var s=new RealmStore(path)){
            var c=new BorderCourt(s);
            assertThrows(IllegalArgumentException.class,()->c.privileges.redeem("主教座堂","ford",grant,115));
            assertThrows(IllegalArgumentException.class,()->c.privileges.redeem("新任","west",grant,115));
            assertEquals(0,c.privileges.redeem("新任","ford",grant,115));
            assertThrows(IllegalArgumentException.class,()->c.privileges.redeem("新任","ford",grant,160));
            assertEquals("march",s.value("SELECT owner FROM passage WHERE at=100"));
            assertEquals("abbey",s.value("SELECT owner FROM passage WHERE at=115"));
            assertEquals(0,s.number("SELECT sum(paid) FROM passage"));
            assertEquals(2,s.number("SELECT count(*) FROM privilege_use WHERE privilege=?",grant));
            assertEquals("march",s.value("SELECT issuer FROM bridge_privilege WHERE id=?",grant));
        }
    }

    @Test void sealScopeLimitsAndPaidTripsAreCheckedBeforeChargingAUse() throws Exception {
        try(var s=new RealmStore(dir.resolve("realm.sqlite"))){
            var c=setUp(s);
            assertThrows(IllegalArgumentException.class,()->c.privileges.grant("主教座堂","ford","abbey",2));
            assertThrows(IllegalArgumentException.class,()->c.privileges.grant("东境伯家","ford","march",2));
            assertThrows(IllegalArgumentException.class,()->c.privileges.grant("东境伯家","ford","abbey",65));
            int grant=c.privileges.grant("东境伯家","ford","abbey",1);
            assertThrows(IllegalArgumentException.class,()->c.privileges.grant("东境伯家","ford","abbey",2));
            s.update("INSERT INTO passage(bridge,owner,payer,paid,at) VALUES('ford','march','主教座堂',3,95)");
            assertThrows(IllegalArgumentException.class,()->c.privileges.redeem("主教座堂","ford",grant,100));
            assertEquals(1,s.number("SELECT remaining FROM bridge_privilege WHERE id=?",grant));
            assertEquals(0,c.privileges.redeem("主教座堂","ford",grant,105));
            assertTrue(c.privileges.grant("东境伯家","ford","abbey",3)>grant);
            assertEquals(3,s.number("SELECT sum(paid) FROM passage"));
        }
    }
}
