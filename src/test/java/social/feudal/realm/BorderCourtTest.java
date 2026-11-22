package social.feudal.realm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BorderCourtTest {
    @TempDir Path dir;
    @Test void ownerChoosesCurrencyWithoutRepricingExistingReceipts()throws Exception{
        try(var s=new RealmStore(dir.resolve("prices"))){
            s.initialize("w",0,0,"摄政");var c=new BorderCourt(s);
            c.bridge("东境伯家","ford","东桥","march",1024,70,0,3);
            s.update("INSERT INTO passage(bridge,owner,payer,paid,at) VALUES('ford','march','客商',3,10)");
            assertThrows(IllegalArgumentException.class,()->c.price("主教座堂","ford",TollCoin.SILVER,4));
            c.price("东境伯家","ford",TollCoin.SILVER,4);
            assertEquals("GOLD",s.value("SELECT currency FROM passage"));assertEquals(3,s.number("SELECT paid FROM passage"));
            assertEquals("SILVER",s.value("SELECT currency FROM crossing"));assertEquals(4,s.number("SELECT fee FROM crossing"));
            c.price("东境伯家","ford",TollCoin.GOLD,0);assertEquals(0,s.number("SELECT fee FROM crossing"));
        }
    }
    @Test void noticesContestedSecondsCounterclaimsAndRecordedTollsAreDistinct()throws Exception{
        try(var s=new RealmStore(dir.resolve("court"))){
            s.initialize("w",0,0,"npc-皇廷署理");var c=new BorderCourt(s);
            s.titleRow("coast","海岸","伯爵",null,"建领","alice",20,20);
            s.politics.newNation("coast",false,false);
            c.bridge("东境伯家","ford","东桥","march",64*16,80,0,2);
            long start=BorderCourt.DAY;
            assertThrows(IllegalArgumentException.class,()->c.declare("alice","coast","ford","通航争议",50,0));
            int n=c.declare("alice","coast","ford","通航争议",start,0);
            assertThrows(IllegalArgumentException.class,()->c.enlist(n,"主教座堂","abbey","守方"));
            c.enlist(n,"alice","coast","原告");
            c.observe(n,start,true,true,true);
            assertEquals(0,s.number("SELECT progress FROM feud WHERE id=?",n));
            s.update("UPDATE feud SET progress=1198 WHERE id=?",n);
            c.observe(n,start+1200,true,true,false);
            assertEquals("march",s.value("SELECT owner FROM crossing"));
            c.observe(n,start+1200,true,true,false); // replay the same tick
            assertEquals(1199,s.number("SELECT progress FROM feud WHERE id=?",n));
            c.observe(n,start+1201,true,true,false);
            assertEquals("coast",s.value("SELECT owner FROM crossing"));
            s.update("INSERT INTO passage(bridge,owner,payer,paid,at) VALUES('ford','coast','merchant',2,?)",start+2400);
            s.update("UPDATE feud SET progress=1199 WHERE id=?",n);
            c.observe(n,start+2401,true,false,true);
            assertEquals("march",s.value("SELECT owner FROM crossing"));
            assertEquals("coast",s.value("SELECT owner FROM passage"));
            assertEquals("结案",s.value("SELECT state FROM feud WHERE id=?",n));
            int next=c.declare("alice","coast","ford","重新提出",3*start,start);
            c.observe(next,3*start+BorderCourt.WINDOW+1,false,false,false);
            assertEquals(4*start,s.number("SELECT start FROM feud WHERE id=?",next));
        }
    }
}
