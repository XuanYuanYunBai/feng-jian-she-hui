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
            s.update("INSERT INTO bid(id,stall,goods,lot,price,remaining,currency) VALUES(2,'east','小麦',4,13,2,'SILVER'),(3,'east','小麦',8,50,0,'SILVER')");
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

    @Test void indexedOrdersUseSettlementsAndTheOwnersBoundsOnEitherSide() throws Exception {
        try(var s=new RealmStore(dir.resolve("prices.sqlite"))){
            var bids=new MarketBids(s);
            s.update("INSERT INTO stall VALUES('west','A','西',1,2,3),('east','B','东',4,5,6)");
            s.update("INSERT INTO bid VALUES(1,'west','小麦',4,1,5,'GOLD',0,0),(2,'east','小麦',4,7,5,'SILVER',1,1),(3,'east','小麦',4,11,5,'SILVER',0,1)");
            assertEquals(7,bids.offer(2).price());assertEquals(11,bids.offer(3).price());
            s.update("INSERT INTO trade VALUES('old',1,'C',NULL)");
            assertEquals(12,bids.offer(2).price());assertEquals(11,bids.offer(3).price());
            s.update("INSERT INTO trade VALUES('indexed',2,'D',12)");
            s.update("INSERT INTO trade VALUES('repeat',1,'C',1)");
            assertEquals(1,bids.offer(2).samples());
            s.update("INSERT INTO bid VALUES(4,'east','小麦',4,4,1,'SILVER',1,0)");
            s.update("INSERT INTO trade VALUES('other',4,'D',4)");
            assertEquals(8,bids.offer(2).price());assertEquals(8,bids.offer(3).price());
            String record="INSERT INTO trade(id,bid,seller,paid) VALUES('renew',1,'C',?) ON CONFLICT(id) DO NOTHING";
            assertEquals(1,s.update(record,2));
            assertEquals(0,s.update(record,20));
            assertEquals(14,bids.offer(2).price());assertEquals(11,bids.offer(3).price());
            assertEquals(2,bids.offer(3).samples());
        }
    }

    @Test void sampleWindowSurvivesRestartAndCannotBeFilledByOnePair() throws Exception {
        Path path=dir.resolve("window.sqlite");
        try(var s=new RealmStore(path)){
            new MarketBids(s);
            s.update("INSERT INTO stall VALUES('quay','owner','港市',1,2,3)");
            s.update("INSERT INTO bid VALUES(1,'quay','羊毛',1,8,100,'SILVER',0,0)");
            for(int i=1;i<=40;i++)s.update("INSERT INTO trade VALUES(?,1,?,?)","r"+i,"visitor"+i,i);
            for(int i=0;i<40;i++)s.update("INSERT INTO trade VALUES(?,1,'visitor40',63)","again"+i);
        }
        try(var s=new RealmStore(path)){
            var bids=new MarketBids(s);
            assertEquals(31,bids.offer(1).samples());
            assertEquals(25.0,Double.parseDouble(s.value("SELECT silver FROM market_reference WHERE goods='羊毛'")));
            assertEquals(31,s.number("SELECT count(*) FROM market_sample"));
        }
    }
    @Test void onlyCurrentStallOwnerCanWithdrawRemainingLots()throws Exception{
        try(var s=new RealmStore(dir.resolve("market.sqlite"))){
            var bids=new MarketBids(s);
            s.update("INSERT INTO stall VALUES('quay','old','河台柜',1,2,3)");
            s.update("INSERT INTO bid(id,stall,goods,lot,price,remaining) VALUES(1,'quay','小麦',2,1,3)");
            s.update("INSERT INTO trade VALUES('paid',1,'客',1)");
            s.update("UPDATE stall SET owner='new' WHERE id='quay'");
            assertThrows(IllegalArgumentException.class,()->bids.withdraw(1,"old"));
            bids.withdraw(1,"new");
            assertEquals(0,bids.offer(1).remaining());assertEquals(1,s.number("SELECT count(*) FROM trade"));
            assertThrows(IllegalArgumentException.class,()->bids.withdraw(1,"new"));
        }
    }
}
