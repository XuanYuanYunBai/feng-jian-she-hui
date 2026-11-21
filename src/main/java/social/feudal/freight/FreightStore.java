package social.feudal.freight;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

final class FreightStore implements AutoCloseable {
    enum Phase { LOADING, TRAVEL, ARRIVED, UNLOADING, DELIVERED, CANCELLED }

    record Route(String name, String world, int sx, int y, int sz, int dx, int dz, String sourceSeal, String destSeal) {
        Route {
            int distance = Math.abs(dx - sx) + Math.abs(dz - sz);
            if ((sx != dx && sz != dz) || distance < 1 || distance > 256)
                throw new IllegalArgumentException("两站须在同一条水平直线上，相距 1 至 256 格");
        }
        int length() { return Math.abs(dx - sx) + Math.abs(dz - sz); }
        int x(int step) { return sx + Integer.compare(dx, sx) * step; }
        int z(int step) { return sz + Integer.compare(dz, sz) * step; }
    }

    record Trip(String id, Route route, String sender, String recipient, byte[] cargo, String material, int amount,
                Phase phase, int step, String signer, String deliveredAt) {}

    private final Connection db;
    FreightStore(Path path) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (var st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS routes(
                    name TEXT PRIMARY KEY, world TEXT NOT NULL, sx INTEGER NOT NULL, y INTEGER NOT NULL,
                    sz INTEGER NOT NULL, dx INTEGER NOT NULL, dz INTEGER NOT NULL, source_seal TEXT NOT NULL, dest_seal TEXT NOT NULL)
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS trips(
                    id TEXT PRIMARY KEY, route TEXT NOT NULL REFERENCES routes(name), sender TEXT NOT NULL, recipient TEXT NOT NULL,
                    cargo BLOB NOT NULL, material TEXT NOT NULL, amount INTEGER NOT NULL CHECK(amount BETWEEN 1 AND 64),
                    phase TEXT NOT NULL CHECK(phase IN ('LOADING','TRAVEL','ARRIVED','UNLOADING','DELIVERED','CANCELLED')),
                    step INTEGER NOT NULL DEFAULT 0, signer TEXT, delivered_at TEXT)
                    """);
            st.execute("CREATE TABLE IF NOT EXISTS crew_return(route TEXT PRIMARY KEY REFERENCES routes(name),step INTEGER NOT NULL)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_cart ON trips((1)) WHERE phase NOT IN ('DELIVERED','CANCELLED')");
        } catch (SQLException e) { db.close(); throw e; }
    }

    void register(Route r) throws SQLException {
        try (var st = db.prepareStatement("INSERT INTO routes VALUES(?,?,?,?,?,?,?,?,?)")) {
            st.setString(1, r.name); st.setString(2, r.world);
            st.setInt(3, r.sx); st.setInt(4, r.y); st.setInt(5, r.sz);
            st.setInt(6, r.dx); st.setInt(7, r.dz);
            st.setString(8, r.sourceSeal); st.setString(9, r.destSeal);
            st.executeUpdate();
        }
    }

    private Route readRoute(ResultSet r) throws SQLException {
        return new Route(r.getString("name"), r.getString("world"), r.getInt("sx"), r.getInt("y"), r.getInt("sz"),
                r.getInt("dx"), r.getInt("dz"), r.getString("source_seal"), r.getString("dest_seal"));
    }

    Route route(String name) throws SQLException {
        try (var st = db.prepareStatement("SELECT * FROM routes WHERE name=?")) {
            st.setString(1, name);
            try (var r = st.executeQuery()) { return r.next() ? readRoute(r) : null; }
        }
    }

    List<Route> routes() throws SQLException {
        var result = new ArrayList<Route>();
        try (var st = db.createStatement(); var r = st.executeQuery("SELECT * FROM routes ORDER BY name")) {
            while (r.next()) result.add(readRoute(r));
        }
        return result;
    }

    void book(String id, Route route, String sender, String recipient, byte[] cargo, String material, int amount) throws SQLException {
        try (var st = db.prepareStatement("""
                INSERT INTO trips(id,route,sender,recipient,cargo,material,amount,phase) VALUES(?,?,?,?,?,?,?,'LOADING')""")) {
            st.setString(1, id); st.setString(2, route.name); st.setString(3, sender); st.setString(4, recipient);
            st.setBytes(5, cargo); st.setString(6, material); st.setInt(7, amount);
            st.executeUpdate();
        }
    }

    Trip active() throws SQLException { return query(null); }
    Trip trip(String id) throws SQLException { return query(id); }

    private Trip query(String id) throws SQLException {
        String where = id == null ? "phase NOT IN ('DELIVERED','CANCELLED')" : "id = ?";
        try (var st = db.prepareStatement("SELECT * FROM trips JOIN routes ON trips.route=routes.name WHERE " + where)) {
            if (id != null) st.setString(1, id);
            try (var r = st.executeQuery()) {
                if (!r.next()) return null;
                return new Trip(r.getString("id"), readRoute(r), r.getString("sender"), r.getString("recipient"),
                        r.getBytes("cargo"), r.getString("material"), r.getInt("amount"), Phase.valueOf(r.getString("phase")),
                        r.getInt("step"), r.getString("signer"), r.getString("delivered_at"));
            }
        }
    }

    void cancelUnloaded(Trip t) throws SQLException { transition(t, Phase.LOADING, Phase.CANCELLED, 0, null); }

    void loaded(Trip t) throws SQLException { transition(t, Phase.LOADING, Phase.TRAVEL, t.step, null); }

    void move(Trip t) throws SQLException {
        int next = t.step + 1;
        if (next > t.route.length()) throw new IllegalStateException("已到达终点");
        transition(t, Phase.TRAVEL, next == t.route.length() ? Phase.ARRIVED : Phase.TRAVEL, next, null);
    }

    void accept(Trip t, String signer) throws SQLException { transition(t, Phase.ARRIVED, Phase.UNLOADING, t.step, signer); }
    void delivered(Trip t)throws SQLException{
        db.setAutoCommit(false);
        try(var q=db.prepareStatement("INSERT INTO crew_return VALUES(?,?)")){
            transition(t,Phase.UNLOADING,Phase.DELIVERED,t.step,t.signer);
            q.setString(1,t.route.name);q.setInt(2,t.step);q.executeUpdate();db.commit();
        }catch(SQLException | RuntimeException ex){db.rollback();throw ex;}
        finally{db.setAutoCommit(true);}
    }
    record Return(Route route,int step){}
    Return returning()throws SQLException{
        try(var q=db.prepareStatement("SELECT route,step FROM crew_return LIMIT 1");var r=q.executeQuery()){
            return r.next()?new Return(route(r.getString(1)),r.getInt(2)):null;
        }
    }
    void walkedBack(Return trip)throws SQLException{
        try(var q=db.prepareStatement(trip.step<=1?"DELETE FROM crew_return WHERE route=?":"UPDATE crew_return SET step=step-1 WHERE route=?")){
            q.setString(1,trip.route.name);q.executeUpdate();
        }
    }

    private void transition(Trip t, Phase from, Phase to, int step, String signer) throws SQLException {
        if (t.phase != from) throw new IllegalStateException("货单状态不符");
        try (var st = db.prepareStatement("""
                UPDATE trips SET phase=?,step=?,signer=?,delivered_at=CASE WHEN ?='DELIVERED' THEN CURRENT_TIMESTAMP END
                WHERE id=? AND phase=? AND step=?""")) {
            st.setString(1, to.name()); st.setInt(2, step); st.setString(3, signer); st.setString(4, to.name());
            st.setString(5, t.id); st.setString(6, from.name()); st.setInt(7, t.step);
            if (st.executeUpdate() != 1) throw new SQLException("货单检查点已变化");
        }
    }

    @Override public void close() throws SQLException { db.close(); }
}
