package social.feudal.realm;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MarketBidsTest {
    @TempDir Path dir;

    @Test void legacyBidsKeepTheirPriceAndCompeteByValuePerItem() throws Exception {
        try(var s=new RealmStore(dir.resolve("realm.sqlite"))){
            s.update("CREATE TABLE bid(id INTEGER PRIMARY KEY,stall TEXT,goods TEXT,lot INTEGER,price INTEGER,remaining INTEGER)");
            s.update("INSERT INTO bid VALUES(1,'west','小麦',8,2,4)");
            var bids=new MarketBids(s);
            s.update("INSERT INTO stall VALUES('west','a','西市',1,2,3),('east','b','东市',4,5,6)");
            s.update("INSERT INTO bid VALUES(2,'east','小麦',4,13,2,'SILVER'),(3,'east','小麦',8,50,0,'SILVER')");
            var offers=bids.listed(null,"小麦",1).offers();
            assertEquals(2,offers.size());
            assertEquals(2,offers.getFirst().id());
            assertEquals(TollCoin.GOLD,offers.getLast().currency());
            assertEquals(1,bids.listed("west",null,1).offers().size());
            s.update("UPDATE stall SET x=NULL WHERE id='east'");
            assertEquals(1,bids.listed(null,null,1).offers().size());
        }
    }

    @Test void stablePagesRetainTiedOffersAndHandleTheLastPage() throws Exception {
        try(var s=new RealmStore(dir.resolve("realm.sqlite"))){
            var bids=new MarketBids(s);
            s.update("INSERT INTO stall VALUES('east','a','码头',1,2,3)");
            for(int i=0;i<6;i++)s.update("INSERT INTO bid(stall,goods,lot,price,remaining) VALUES('east','羊毛',8,2,4)");
            var first=bids.listed(null,null,1);var last=bids.listed(null,null,2);
            assertTrue(first.more());assertFalse(last.more());
            assertEquals(4,first.offers().getLast().id());assertEquals(5,last.offers().getFirst().id());
            assertThrows(IllegalArgumentException.class,()->bids.listed(null,null,3));
        }
    }
}
