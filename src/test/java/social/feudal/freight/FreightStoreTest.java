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

    @Test void straightRoadUsesActualBlockPositions() {
        assertEquals(-16, road.x(1));
        assertEquals(4, road.z(1));
        assertEquals(2, road.length());
        Route north = new Route("n", "w", 1, 70, 3, 1, -3, "x", "y");
        assertEquals(-2, north.z(5));
        assertThrows(IllegalArgumentException.class, () -> new Route("d", "w", 0, 70, 0, 2, 2, "x", "y"));
        assertThrows(IllegalArgumentException.class, () -> new Route("d", "w", 0, 70, 0, 0, 0, "x", "y"));
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
