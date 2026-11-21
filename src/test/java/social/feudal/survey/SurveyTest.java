package social.feudal.survey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static social.feudal.survey.Survey.*;

class SurveyTest {
    private final Rules rules = new Rules(1, 1, new Ores(2, 4, 6));
    private final MapIdentity map = new MapIdentity(UUID.randomUUID(), 42L, "vanilla-test", -1, 64, -17);
    @TempDir Path dir;
    private Path file() { return dir.resolve("survey.sqlite"); }

    @Test void ringsCoverEachChunkOnceAroundNegativeCoordinates() {
        Set<Point> seen = new HashSet<>();
        for (var ring : List.of(new Ring(0, 2), new Ring(2, 5), new Ring(5, 8))) {
            for (long i = 0; i < ring.size(); i++) assertTrue(seen.add(ring.at(-2, -3, i)));
            for (int x = -ring.outer(); x < ring.outer(); x++) {
                for (int z = -ring.outer(); z < ring.outer(); z++) assertTrue(seen.contains(new Point(x - 2, z - 3)));
            }
            assertEquals(4L * ring.outer() * ring.outer(), seen.size());
        }
        assertEquals(65_536, new Ring(0, 128).size());
        assertEquals(36_864, new Ring(128, 160).size());
    }

    @Test void stopsAtFirstEntirePassingRound() throws Exception {
        try (var store = new SurveyStore(file(), rules)) {
            var p = store.start(map);
            assertEquals(new Point(-2, -3), p.next());
            for (int i = 0; i < 4; i++) p = store.record(p, new Ores(0, 1, 2), 3);
            assertEquals(new Ring(1, 2), p.ring());
            for (int i = 0; i < 12; i++) {
                assertEquals(Phase.RUNNING, p.phase());
                p = store.record(p, new Ores(1, 0, 0), 3);
            }
            assertEquals(Phase.MEASURED, p.phase());
            assertEquals(new Ring(1, 2), p.ring());
            assertEquals(new Ores(12, 4, 8), p.total());
            assertEquals(p, store.load(map));
        }
    }

    @Test void allThreeEarlyHitsStillFinishInitialSquare() throws Exception {
        try (var store = new SurveyStore(file(), rules)) {
            var p = store.record(store.start(map), rules.minimum(), 1);
            for (int i = 1; i < 4; i++) {
                assertEquals(Phase.RUNNING, p.phase());
                p = store.record(p, Ores.ZERO, 1);
            }
            assertEquals(Phase.MEASURED, p.phase());
            assertEquals(new Ring(0, 1), p.ring());
        }
    }

    @Test void pauseSurvivesReopenAndStaleWorkRollsBack() throws Exception {
        try (var store = new SurveyStore(file(), rules)) {
            var start = store.start(map);
            store.change(start, start.phase(Phase.PAUSED));
            assertThrows(SQLException.class, () -> store.record(start, rules.minimum(), 2));
            assertThrows(SQLException.class, () -> store.start(map));
        }
        try (var store = new SurveyStore(file(), rules)) {
            var movedSpawn = new MapIdentity(map.id(), map.seed(), map.generator(), 100, 70, 100);
            var saved = store.load(movedSpawn);
            assertEquals(Phase.PAUSED, saved.phase());
            assertEquals(map, saved.map());
            var active = saved.phase(Phase.RUNNING);
            store.change(saved, active);
            var first = store.record(active, rules.minimum(), 2);
            assertThrows(SQLException.class, () -> store.record(active, rules.minimum(), 2));
            assertEquals(first, store.load(map));
        }
    }

    @Test void budgetCanBeRaisedWithoutRecountingOldChunks() throws Exception {
        try (var store = new SurveyStore(file(), rules)) {
            var p = store.start(map);
            for (int i = 0; i < 4; i++) p = store.record(p, Ores.ZERO, 1);
            assertEquals(Phase.LIMIT, p.phase());
            assertEquals(p, p.expand(rules, 1));
            var next = p.expand(rules, 2);
            store.change(p, next);
            assertEquals(new Ring(1, 2), store.load(map).ring());
            assertEquals(0, next.cursor());
        }
    }

    @Test void interruptedTransactionResumesAtUncommittedChunk() throws Exception {
        try (var store = new SurveyStore(file(), rules)) {
            store.record(store.start(map), new Ores(1, 2, 3), 2);
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + file()); var st = db.createStatement()) {
            db.setAutoCommit(false);
            st.executeUpdate("INSERT INTO chunks VALUES (-1,-3,100,100,100)");
            // Close the session between writing the sample and advancing its cursor.
        }
        try (var store = new SurveyStore(file(), rules)) {
            var p = store.load(map);
            assertEquals(new Point(-1, -3), p.next());
            assertEquals(new Ores(1, 2, 3), p.total());
            assertEquals(new Ores(2, 4, 6), store.record(p, new Ores(1, 2, 3), 2).total());
        }
    }

    @Test void rejectsDifferentWorldOrRules() throws Exception {
        try (var store = new SurveyStore(file(), rules)) {
            store.start(map);
            assertThrows(IllegalStateException.class, () -> store.load(new MapIdentity(map.id(), 43, map.generator(), -1, 64, -17)));
            assertThrows(IllegalStateException.class, () -> store.load(new MapIdentity(UUID.randomUUID(), 42, map.generator(), -1, 64, -17)));
            assertThrows(IllegalStateException.class, () -> store.load(new MapIdentity(map.id(), 42, "other", -1, 64, -17)));
        }
        try (var store = new SurveyStore(file(), new Rules(1, 2, rules.minimum()))) {
            assertThrows(IllegalStateException.class, () -> store.load(map));
        }
    }
}
