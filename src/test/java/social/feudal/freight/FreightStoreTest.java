package social.feudal.freight;

import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static social.feudal.freight.FreightStore.*;

class FreightStoreTest {
    @TempDir Path temp;
    final Route road = new Route("mill", "world", -15, 75, 4, -17, 4, "source", "destination");
    final byte[] stack = { 1, 2, 3 };

    @Test void relayKeepsItsManifestAcrossRestartAndRetry() throws Exception {
        var file=temp.resolve("relay.sqlite");
        var sea=new Route("sea","world",-17,75,4,-20,4,"destination","quay");
        var wrong=new Route("wrong","world",-17,75,4,-20,4,"replacement","quay");
        assertThrows(IllegalArgumentException.class,()->checkChain(java.util.List.of(road,wrong)));
        assertThrows(IllegalArgumentException.class,()->checkChain(java.util.List.of(sea,road)));
        try(var store=new FreightStore(file)){
            store.register(road);store.register(sea);
            store.relay(7,"sender",stack,2,java.util.List.of(road,sea));
            assertThrows(SQLException.class,()->store.relay(7,"another",stack,2,java.util.List.of(road)));
            var order=store.waybill(7);store.dispatch(order,order.legs().getFirst());
            store.cancelUnloaded(store.active());
        }
        try(var store=new FreightStore(file)){
            var order=store.waybill(7);
            assertEquals(java.util.List.of(7),store.waitingRelays());
            assertArrayEquals(stack,order.cargo());assertEquals(2,order.legs().size());
            store.dispatch(order,order.legs().getFirst());store.loaded(store.active());
            store.move(store.active());store.move(store.active());store.accept(store.active(),"货站交接");
            store.delivered(store.active());
            var resumed=store.waybill(7);
            assertEquals("DELIVERED",resumed.legs().getFirst().phase());
            assertNull(resumed.legs().getLast().phase());
            store.setDispatchClosed(7,true);assertTrue(store.waitingRelays().isEmpty());
            store.setDispatchClosed(7,false);assertEquals(java.util.List.of(7),store.waitingRelays());
        }
    }

    @Test void straightRoadUsesActualBlockPositions() {
        assertEquals(-16, road.x(1));
        assertEquals(4, road.z(1));
        assertEquals(2, road.length());
        Route north = new Route("n", "w", 1, 70, 3, 1, -3, "x", "y");
        assertEquals(-2, north.z(5));
        assertThrows(IllegalArgumentException.class, () -> new Route("d", "w", 0, 70, 0, 2, 2, "x", "y"));
        assertThrows(IllegalArgumentException.class, () -> new Route("d", "w", 0, 70, 0, 0, 0, "x", "y"));
    }

    @Test void surveyedBendsAndHeightsSurviveReloadAndResume()throws Exception{
        var path=java.util.List.of(new social.feudal.transport.HaulNetwork.Point(1,68,3),
                new social.feudal.transport.HaulNetwork.Point(1,69,4),new social.feudal.transport.HaulNetwork.Point(2,69,4));
        var r=new Route("pass","world",1,68,3,2,69,4,"depot","quay",false,path);
        var file=temp.resolve("bend.sqlite");
        try(var store=new FreightStore(file)){
            store.register(r);store.book("bill",r,"a","b",stack,"WHEAT",1);store.loaded(store.active());store.move(store.active());
        }
        try(var store=new FreightStore(file)){
            var trip=store.active();assertEquals(1,trip.step());assertEquals(r,trip.route());
            assertEquals(69,trip.route().path().get(trip.step()).y());assertEquals(69,trip.route().dy());
        }
        assertThrows(IllegalArgumentException.class,()->new Route("air","world",1,68,3,2,69,4,"s","d",false,
                java.util.List.of(path.getFirst(),path.getLast())));
    }
    @Test void preSurveyLedgerKeepsOriginalCheckpoints()throws Exception{
        var file=temp.resolve("legacy.sqlite");
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+file);var q=db.createStatement()){
            q.execute("CREATE TABLE routes(name TEXT PRIMARY KEY,world TEXT,sx INT,y INT,sz INT,dx INT,dz INT,source_seal TEXT,dest_seal TEXT)");
            q.execute("INSERT INTO routes VALUES('legacy','w',1,68,3,1,6,'s','d')");
        }
        try(var store=new FreightStore(file)){
            var r=store.route("legacy");assertEquals(3,r.length());assertEquals(5,r.path().get(2).z());assertEquals(68,r.dy());
            assertFalse(r.sea());
        }
    }
    @Test void changedSourceReleasesOnlyUnloadedCartAndKeepsOriginalBillNumber() throws Exception {
        try (var db = new FreightStore(temp.resolve("freight.sqlite"))) {
            db.register(road);
            db.book("old", road, "sender", "recipient", stack, "WHEAT", 17);
            db.cancelUnloaded(db.active());
            assertNull(db.active());
            assertEquals(Phase.CANCELLED, db.trip("old").phase());
            db.book("new", road, "sender", "recipient", stack, "WHEAT", 17);
            db.loaded(db.active());
            assertThrows(IllegalStateException.class, () -> db.cancelUnloaded(db.active()));
            assertThrows(SQLException.class, () -> db.book("old", road, "sender", "recipient", stack, "WHEAT", 17));
        }
    }

    @Test void rebootAndDuplicateActionsKeepOneStackAndOneReceipt() throws Exception {
        Path file = temp.resolve("freight.sqlite");
        try (var db = new FreightStore(file)) {
            db.register(road);
            db.book("one", road, "merchant", "abbot", stack, "WHEAT", 17);
            assertThrows(SQLException.class, () -> db.book("two", road, "merchant", "abbot", stack, "WHEAT", 17));
            Trip before = db.active();
            assertThrows(IllegalStateException.class, () -> db.accept(before, "abbot"));
            db.loaded(before);
            assertThrows(SQLException.class, () -> db.loaded(before));
            db.move(db.active());
        }
        try (var db = new FreightStore(file)) {
            Trip halfway = db.active();
            assertEquals(1, halfway.step());
            assertEquals(road, halfway.route());
            assertArrayEquals(stack, halfway.cargo());
            db.move(halfway);
            assertThrows(SQLException.class, () -> db.move(halfway));
            assertEquals(Phase.ARRIVED, db.active().phase());
            db.accept(db.active(), "abbot");
        }
        try (var db = new FreightStore(file)) {
            Trip unloading = db.active();
            assertEquals("abbot", unloading.signer());
            db.delivered(unloading);
            assertThrows(SQLException.class, () -> db.delivered(unloading));
            assertNull(db.active());
            assertEquals(2,db.returning().step());
            db.walkedBack(db.returning());assertEquals(1,db.returning().step());
            db.walkedBack(db.returning());assertNull(db.returning());
            Trip receipt = db.trip("one");
            assertEquals(17, receipt.amount());
            assertEquals(Phase.DELIVERED, receipt.phase());
            assertNotNull(receipt.deliveredAt());
            assertThrows(SQLException.class, () -> db.book("one", road, "merchant", "abbot", stack, "WHEAT", 17));
            db.book("two", road, "merchant", "abbot", stack, "WHEAT", 17);
            assertEquals("two", db.active().id());
        }
    }
}
