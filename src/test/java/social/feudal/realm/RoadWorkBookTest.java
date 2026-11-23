package social.feudal.realm;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import social.feudal.transport.HaulNetwork.Point;
import static org.junit.jupiter.api.Assertions.*;

class RoadWorkBookTest {
    @TempDir Path dir;
    @Test void oneDefectCannotFundTwiceAndWithdrawnMoneyIsNeverRecordedAsSpent()throws Exception{
        try(var s=new RealmStore(dir.resolve("works.sqlite"))){
            s.initialize("w",0,0,"lord");var market=new MarketBids(s);var book=new RoadWorkBook(s);
            s.update("INSERT INTO stall VALUES('forge','payer','锻坊',1,5,1)");
            var point=new Point(1,70,2);
            int id=book.survey("crown","lowroad",point);
            assertEquals(id,book.survey("crown","parallel",point));
            s.update("INSERT INTO bid VALUES(1,'forge','桥路铁件',1,3,1,'GOLD',0,0)");
            book.attach(id,1,"payer");
            assertThrows(IllegalArgumentException.class,()->s.atomic(()->{
                s.update("INSERT INTO bid VALUES(2,'forge','桥路铁件',1,8,1,'SILVER',0,0)");
                book.attach(id,2,"payer");return null;
            }));
            assertEquals(1,s.number("SELECT count(*) FROM bid"));
            assertThrows(IllegalArgumentException.class,()->book.close(id,"lord",true));
            market.withdraw(1,"payer");
            assertEquals("已撤单",book.get(id).state());assertEquals(0,book.get(id).paid());
            assertNotEquals(id,book.survey("crown","lowroad",point));
        }
    }
    @Test void paymentPrecedesAcceptanceAndSurvivesReopening()throws Exception{
        var path=dir.resolve("paid.sqlite");
        try(var s=new RealmStore(path)){
            s.initialize("w",0,0,"lord");new MarketBids(s);var book=new RoadWorkBook(s);
            s.update("INSERT INTO stall VALUES('shop','payer','料行',1,5,1)");
            int id=book.survey("crown","highroad",new Point(5,70,3));
            s.update("INSERT INTO bid VALUES(1,'shop','桥路铁件',1,5,1,'SILVER',0,0)");book.attach(id,1,"payer");
            s.atomic(()->{s.update("INSERT INTO trade VALUES('receipt',1,'smith',5)");s.update("UPDATE bid SET remaining=0 WHERE id=1");return null;});
        }
        try(var s=new RealmStore(path)){
            var book=new RoadWorkBook(s);var o=book.get(1);
            assertEquals("已购料待验",o.state());assertEquals("smith",o.supplier());assertEquals(5,o.paid());
            book.close(1,"new_lord",true);
            assertEquals("现场验通",book.get(1).state());assertEquals("new_lord",book.get(1).inspector());
            assertThrows(IllegalArgumentException.class,()->book.close(1,"new_lord",true));
            assertEquals(1,s.number("SELECT count(*) FROM trade"));
        }
    }
}
