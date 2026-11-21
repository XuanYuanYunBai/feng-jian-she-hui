package social.feudal.survey;

import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import static social.feudal.survey.Survey.*;

final class SurveyStore implements AutoCloseable {
    private final Connection db;
    private final Rules rules;

    SurveyStore(Path file, Rules rules) throws SQLException {
        this.rules = rules;
        db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = db.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS survey (
                      id INTEGER PRIMARY KEY CHECK(id=1), world TEXT, seed INTEGER, generator TEXT,
                      spawn_x INTEGER, spawn_y INTEGER, spawn_z INTEGER, rules TEXT,
                      inner_radius INTEGER, radius INTEGER, cursor INTEGER, iron INTEGER, copper INTEGER, gold INTEGER, phase TEXT)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS chunks (
                      x INTEGER, z INTEGER, iron INTEGER, copper INTEGER, gold INTEGER, PRIMARY KEY(x,z))
                    """);
        } catch (SQLException e) {
            db.close();
            throw e;
        }
    }

    Progress load(MapIdentity current) throws SQLException {
        try (var st = db.createStatement(); var r = st.executeQuery("SELECT * FROM survey WHERE id=1")) {
            if (!r.next()) return null;
            var saved = new MapIdentity(UUID.fromString(r.getString("world")), r.getLong("seed"), r.getString("generator"),
                    r.getInt("spawn_x"), r.getInt("spawn_y"), r.getInt("spawn_z"));
            if (!saved.matches(current)) throw new IllegalStateException("世界、种子或生成器版本与勘测记录不符");
            if (!rules.key().equals(r.getString("rules"))) throw new IllegalStateException("勘测规则与存档不符，请恢复原配置");
            return new Progress(saved, new Ring(r.getInt("inner_radius"), r.getInt("radius")), r.getLong("cursor"),
                    new Ores(r.getLong("iron"), r.getLong("copper"), r.getLong("gold")), Phase.valueOf(r.getString("phase")));
        }
    }

    Progress start(MapIdentity map) throws SQLException {
        var p = new Progress(map, new Ring(0, rules.initial()), 0, Ores.ZERO, Phase.RUNNING);
        try (var st = db.prepareStatement("INSERT INTO survey VALUES (1,?,?,?,?,?,?,?,0,?,0,0,0,0,'RUNNING')")) {
            st.setString(1, map.id().toString());
            st.setLong(2, map.seed());
            st.setString(3, map.generator());
            st.setInt(4, map.spawnX());
            st.setInt(5, map.spawnY());
            st.setInt(6, map.spawnZ());
            st.setString(7, rules.key());
            st.setInt(8, rules.initial());
            st.executeUpdate();
        }
        return p;
    }

    // Caller serializes commands and samples. The cursor lease also rejects duplicate or stale work.
    void change(Progress before, Progress after) throws SQLException {
        try (var st = db.prepareStatement("""
                UPDATE survey SET inner_radius=?,radius=?,cursor=?,iron=?,copper=?,gold=?,phase=?
                WHERE id=1 AND radius=? AND cursor=? AND phase=?""")) {
            st.setInt(1, after.ring().inner());
            st.setInt(2, after.ring().outer());
            st.setLong(3, after.cursor());
            st.setLong(4, after.total().iron());
            st.setLong(5, after.total().copper());
            st.setLong(6, after.total().gold());
            st.setString(7, after.phase().name());
            st.setInt(8, before.ring().outer());
            st.setLong(9, before.cursor());
            st.setString(10, before.phase().name());
            if (st.executeUpdate() != 1) throw new SQLException("勘测进度已变化");
        }
    }

    Progress record(Progress before, Ores sample, int limit) throws SQLException {
        if (before.phase() != Phase.RUNNING) throw new IllegalStateException("勘测未运行");
        Point pos = before.next();
        Progress after = before.measure(sample, rules, limit);
        db.setAutoCommit(false);
        try (var st = db.prepareStatement("INSERT INTO chunks VALUES (?,?,?,?,?)")) {
            st.setInt(1, pos.x());
            st.setInt(2, pos.z());
            st.setLong(3, sample.iron());
            st.setLong(4, sample.copper());
            st.setLong(5, sample.gold());
            st.executeUpdate();
            change(before, after);
            db.commit();
            return after;
        } catch (SQLException | RuntimeException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }

    @Override public void close() throws SQLException { db.close(); }
}
