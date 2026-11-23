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
            store.cancelUnloaded(store.activeTrips().getFirst());
        }
        try(var store=new FreightStore(file)){
            var order=store.waybill(7);
            assertEquals(java.util.List.of(7),store.waitingRelays());
            assertArrayEquals(stack,order.cargo());assertEquals(2,order.legs().size());
            store.dispatch(order,order.legs().getFirst());store.loaded(store.activeTrips().getFirst());
            store.move(store.activeTrips().getFirst());store.move(store.activeTrips().getFirst());store.accept(store.activeTrips().getFirst(),"货站交接");
            store.delivered(store.activeTrips().getFirst());
            var resumed=store.waybill(7);
            assertEquals("DELIVERED",resumed.legs().getFirst().phase());
            assertNull(resumed.legs().getLast().phase());
            store.setDispatchClosed(7,true);assertTrue(store.waitingRelays().isEmpty());
            store.setDispatchClosed(7,false);assertEquals(java.util.List.of(7),store.waitingRelays());
        }
    }

    @Test void sharedDestinationDoesNotBlockAnotherCrewOrReuseTheSamePorter()throws Exception{
        var file=temp.resolve("teams.sqlite");
        var other=new Route("quarry","world",-19,75,4,-17,4,"quarry","destination");
        var alternate=new Route("alternate","world",-15,75,4,-10,4,"source","another");
        try(var db=new FreightStore(file)){
            db.register(road);db.register(other);db.register(alternate);
            db.book("grain",road,"farmer","abbot",stack,"WHEAT",4);
            db.book("timber",other,"forester","bailiff",stack,"OAK_LOG",3);
            assertThrows(SQLException.class,()->db.book("duplicate",alternate,"a","b",stack,"WHEAT",4));
            db.loaded(db.trip("grain"));db.loaded(db.trip("timber"));
            db.move(db.trip("grain"));
            db.move(db.trip("timber"));db.move(db.trip("timber"));db.accept(db.trip("timber"),"bailiff");
        }
        try(var db=new FreightStore(file)){
            assertEquals(2,db.activeTrips().size());assertEquals(1,db.trip("grain").step());
            db.delivered(db.trip("timber"));
            assertNotNull(db.unavailable(other));assertNotNull(db.unavailable(alternate));
            assertEquals("bailiff",db.trip("timber").signer());
            db.walkedBack(db.returns().getFirst());db.walkedBack(db.returns().getFirst());
            assertNull(db.unavailable(other));assertNotNull(db.unavailable(road));
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
            store.register(r);store.book("bill",r,"a","b",stack,"WHEAT",1);store.loaded(store.activeTrips().getFirst());store.move(store.activeTrips().getFirst());
        }
        try(var store=new FreightStore(file)){
            var trip=store.activeTrips().getFirst();assertEquals(1,trip.step());assertEquals(r,trip.route());
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
            db.cancelUnloaded(db.activeTrips().getFirst());
            assertTrue(db.activeTrips().isEmpty());
            assertEquals(Phase.CANCELLED, db.trip("old").phase());
            db.book("new", road, "sender", "recipient", stack, "WHEAT", 17);
            db.loaded(db.activeTrips().getFirst());
            assertThrows(IllegalStateException.class, () -> db.cancelUnloaded(db.activeTrips().getFirst()));
            assertThrows(SQLException.class, () -> db.book("old", road, "sender", "recipient", stack, "WHEAT", 17));
        }
    }

    @Test void rebootAndDuplicateActionsKeepOneStackAndOneReceipt() throws Exception {
        Path file = temp.resolve("freight.sqlite");
        try (var db = new FreightStore(file)) {
            db.register(road);
            db.book("one", road, "merchant", "abbot", stack, "WHEAT", 17);
            assertThrows(SQLException.class, () -> db.book("two", road, "merchant", "abbot", stack, "WHEAT", 17));
            Trip before = db.activeTrips().getFirst();
            assertThrows(IllegalStateException.class, () -> db.accept(before, "abbot"));
            db.loaded(before);
            assertThrows(SQLException.class, () -> db.loaded(before));
            db.move(db.activeTrips().getFirst());
        }
        try (var db = new FreightStore(file)) {
            Trip halfway = db.activeTrips().getFirst();
            assertEquals(1, halfway.step());
            assertEquals(road, halfway.route());
            assertArrayEquals(stack, halfway.cargo());
            db.move(halfway);
            assertThrows(SQLException.class, () -> db.move(halfway));
            assertEquals(Phase.ARRIVED, db.activeTrips().getFirst().phase());
            db.accept(db.activeTrips().getFirst(), "abbot");
        }
        try (var db = new FreightStore(file)) {
            Trip unloading = db.activeTrips().getFirst();
            assertEquals("abbot", unloading.signer());
            db.delivered(unloading);
            assertThrows(SQLException.class, () -> db.delivered(unloading));
            assertTrue(db.activeTrips().isEmpty());
            assertEquals(2,db.returns().getFirst().step());
            db.walkedBack(db.returns().getFirst());assertEquals(1,db.returns().getFirst().step());
            db.walkedBack(db.returns().getFirst());assertTrue(db.returns().isEmpty());
            Trip receipt = db.trip("one");
            assertEquals(17, receipt.amount());
            assertEquals(Phase.DELIVERED, receipt.phase());
            assertNotNull(receipt.deliveredAt());
            assertThrows(SQLException.class, () -> db.book("one", road, "merchant", "abbot", stack, "WHEAT", 17));
            db.book("two", road, "merchant", "abbot", stack, "WHEAT", 17);
            assertEquals("two", db.activeTrips().getFirst().id());
        }
    }
}
