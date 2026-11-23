package social.feudal.freight;

import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import social.feudal.transport.HaulNetwork.Point;

final class FreightStore implements AutoCloseable {
    enum Phase { LOADING, TRAVEL, ARRIVED, UNLOADING, DELIVERED, CANCELLED }

    record Route(String name,String world,int sx,int y,int sz,int dx,int dy,int dz,String sourceSeal,String destSeal,
                 boolean sea,List<Point> path) {
        Route {
            path=List.copyOf(path);
            if(path.size()<2 || path.size()>513)throw new IllegalArgumentException("运输段长度须为 1–512 格");
            for(int i=1;i<path.size();i++){
                Point a=path.get(i-1),b=path.get(i);
                if(Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z())!=1 || Math.abs(a.y()-b.y())>(sea?0:1))
                    throw new IllegalArgumentException("路线上的相邻路格必须连通");
            }
        }
        Route(String name,String world,int sx,int y,int sz,int dx,int dz,String sourceSeal,String destSeal){
            this(name,world,sx,y,sz,dx,y,dz,sourceSeal,destSeal,false,legacy(sx,y,sz,dx,dz));
        }
        private static List<Point> legacy(int sx,int y,int sz,int dx,int dz){
            int length=Math.abs(sx-dx)+Math.abs(sz-dz);
            if((sx!=dx && sz!=dz)||length<1||length>256)throw new IllegalArgumentException("旧版直线路径无效");
            var cells=new ArrayList<Point>();
            for(int i=0;i<=length;i++)cells.add(new Point(sx+i*Integer.compare(dx,sx),y,sz+i*Integer.compare(dz,sz)));
            return cells;
        }
        int length(){return path.size()-1;}
        int x(int step){return path.get(step).x();}
        int z(int step){return path.get(step).z();}
        Point start(){return new Point(sx,y,sz);}
        Point end(){return new Point(dx,dy,dz);}
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
            try(var columns=st.executeQuery("SELECT count(*) FROM pragma_table_info('routes') WHERE name='path'")){
                if(columns.getInt(1)==0){
                    try(var alter=db.createStatement()){alter.execute("ALTER TABLE routes ADD COLUMN path TEXT");}
                }
            }
            st.execute("CREATE TABLE IF NOT EXISTS waybill(relief INTEGER PRIMARY KEY,sender TEXT NOT NULL,cargo BLOB NOT NULL,amount INTEGER NOT NULL CHECK(amount BETWEEN 1 AND 64),closed INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS waybill_leg(relief INTEGER REFERENCES waybill(relief),seq INTEGER,route TEXT NOT NULL REFERENCES routes(name),bill TEXT NOT NULL UNIQUE,PRIMARY KEY(relief,seq))");
            st.execute("CREATE TABLE IF NOT EXISTS crew_return(route TEXT PRIMARY KEY REFERENCES routes(name),step INTEGER NOT NULL)");
            st.execute("DROP INDEX IF EXISTS one_cart");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS active_route ON trips(route) WHERE phase NOT IN ('DELIVERED','CANCELLED')");
        } catch (SQLException e) { db.close(); throw e; }
    }

    void register(Route r) throws SQLException {
        try (var st = db.prepareStatement("INSERT INTO routes(name,world,sx,y,sz,dx,dz,source_seal,dest_seal,path) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            st.setString(1, r.name); st.setString(2, r.world);
            st.setInt(3, r.sx); st.setInt(4, r.y); st.setInt(5, r.sz);
            st.setInt(6, r.dx); st.setInt(7, r.dz);
            st.setString(8, r.sourceSeal); st.setString(9, r.destSeal);
            var points=new java.util.StringJoiner(";");
            for(var p:r.path)points.add(p.x()+","+p.y()+","+p.z());
            st.setString(10,r.dy+"|"+(r.sea?"water":"road")+"|"+points);
            st.executeUpdate();
        }
    }

    private Route readRoute(ResultSet r) throws SQLException {
        String encoded=r.getString("path");
        if(encoded!=null){
            var parts=encoded.split("[|]",3);var path=new ArrayList<Point>();
            for(String entry:parts[2].split(";")){
                var xyz=entry.split(",");path.add(new Point(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2])));
            }
            return new Route(r.getString("name"),r.getString("world"),r.getInt("sx"),r.getInt("y"),r.getInt("sz"),
                    r.getInt("dx"),Integer.parseInt(parts[0]),r.getInt("dz"),r.getString("source_seal"),r.getString("dest_seal"),
                    parts[1].equals("water"),path);
        }
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
        String wait = unavailable(route);
        if (wait != null) throw new SQLException(wait);
        try (var st = db.prepareStatement("""
                INSERT INTO trips(id,route,sender,recipient,cargo,material,amount,phase) VALUES(?,?,?,?,?,?,?,'LOADING')""")) {
            st.setString(1, id); st.setString(2, route.name); st.setString(3, sender); st.setString(4, recipient);
            st.setBytes(5, cargo); st.setString(6, material); st.setInt(7, amount);
            st.executeUpdate();
        }
    }

    List<Trip> activeTrips() throws SQLException {
        var result = new ArrayList<Trip>();
        try (var q=db.createStatement(); var rows=q.executeQuery("""
                SELECT * FROM trips JOIN routes ON trips.route=routes.name
                WHERE phase NOT IN ('DELIVERED','CANCELLED') ORDER BY trips.rowid""")) {
            while(rows.next()) result.add(readTrip(rows));
        }
        return result;
    }

    private Trip readTrip(ResultSet r) throws SQLException {
        return new Trip(r.getString("id"), readRoute(r), r.getString("sender"), r.getString("recipient"),
                r.getBytes("cargo"), r.getString("material"), r.getInt("amount"), Phase.valueOf(r.getString("phase")),
                r.getInt("step"), r.getString("signer"), r.getString("delivered_at"));
    }
    Trip trip(String id) throws SQLException {
        try(var q=db.prepareStatement("SELECT * FROM trips JOIN routes ON trips.route=routes.name WHERE trips.id=?")){
            q.setString(1,id);
            try(var rows=q.executeQuery()){ return rows.next()?readTrip(rows):null; }
        }
    }

    // Routes leaving the same sealed barrel share one porter per transport mode.
    String unavailable(Route route) throws SQLException {
        for(var trip:activeTrips()){
            if(!route.sourceSeal().equals(trip.route().sourceSeal())) continue;
            if(route.sea()==trip.route().sea()) return "本站工班正在承运 "+trip.id();
            if(trip.phase()==Phase.LOADING) return "本站第一格正在装另一批货";
        }
        for(var crew:returns())
            if(route.sourceSeal().equals(crew.route().sourceSeal()) && route.sea()==crew.route().sea())
                return "本站工班正在沿原线回站";
        return null;
    }

    record RelayLeg(int number, Route route, String bill, String phase) {}
    record Waybill(int relief, String sender, byte[] cargo, int amount, boolean closed, List<RelayLeg> legs) {}

    static void checkChain(List<Route> routes) {
        if (routes.isEmpty() || routes.size() > 8)
            throw new IllegalArgumentException("联运须选择一至八段货线");
        var seen = new java.util.HashSet<String>();
        seen.add(routes.getFirst().sourceSeal());
        Route previous = null;
        for (Route route : routes) {
            if (previous != null && (!previous.world().equals(route.world())
                    || !previous.end().equals(route.start()) || !previous.destSeal().equals(route.sourceSeal())))
                throw new IllegalArgumentException("换装须使用同一只登记站桶：" + route.name());
            if (!seen.add(route.destSeal())) throw new IllegalArgumentException("联运线路重复经过同一货站");
            previous = route;
        }
    }

    void relay(int relief, String sender, byte[] cargo, int amount, List<Route> routes) throws SQLException {
        checkChain(routes);
        db.setAutoCommit(false);
        try (var order = db.prepareStatement("INSERT INTO waybill(relief,sender,cargo,amount) VALUES(?,?,?,?)");
             var leg = db.prepareStatement("INSERT INTO waybill_leg VALUES(?,?,?,?)")) {
            order.setInt(1, relief); order.setString(2, sender); order.setBytes(3, cargo); order.setInt(4, amount);
            order.executeUpdate();
            for (int i = 0; i < routes.size(); i++) {
                leg.setInt(1, relief); leg.setInt(2, i + 1); leg.setString(3, routes.get(i).name());
                // Dots reserve an internal namespace distinct from player-chosen trip IDs.
                leg.setString(4, "relief." + relief + "." + (i + 1)); leg.addBatch();
            }
            leg.executeBatch(); db.commit();
        } catch (SQLException | RuntimeException ex) {
            db.rollback(); throw ex;
        } finally { db.setAutoCommit(true); }
    }

    Waybill waybill(int id) throws SQLException {
        try (var order = db.prepareStatement("SELECT sender,cargo,amount,closed FROM waybill WHERE relief=?");
             var legs = db.prepareStatement("""
                SELECT seq,l.route,bill,t.phase FROM waybill_leg l LEFT JOIN trips t ON t.id=l.bill
                WHERE l.relief=? ORDER BY seq""")) {
            order.setInt(1,id); legs.setInt(1,id);
            try (var r = order.executeQuery(); var rows = legs.executeQuery()) {
                if (!r.next()) return null;
                var items = new ArrayList<RelayLeg>();
                while (rows.next()) items.add(new RelayLeg(rows.getInt(1),route(rows.getString(2)),rows.getString(3),rows.getString(4)));
                return new Waybill(id,r.getString(1),r.getBytes(2),r.getInt(3),r.getBoolean(4),List.copyOf(items));
            }
        }
    }

    List<Integer> waitingRelays() throws SQLException {
        var ids = new ArrayList<Integer>();
        try (var q = db.createStatement(); var rows = q.executeQuery("""
             SELECT DISTINCT l.relief FROM waybill_leg l JOIN waybill w ON w.relief=l.relief LEFT JOIN trips t ON t.id=l.bill
             WHERE w.closed=0 AND (t.id IS NULL OR t.phase='CANCELLED') ORDER BY l.relief""")) {
            while (rows.next()) ids.add(rows.getInt(1));
        }
        return ids;
    }

    void dispatch(Waybill w, RelayLeg leg) throws SQLException {
        if (leg.phase()==null) {
            book(leg.bill(),leg.route(),w.sender(),w.sender(),w.cargo(),"BREAD",w.amount());
        } else {
            String wait=unavailable(leg.route());
            if(wait!=null)throw new SQLException(wait);
            try (var q=db.prepareStatement("UPDATE trips SET phase='LOADING' WHERE id=? AND phase='CANCELLED'")) {
                q.setString(1,leg.bill());
                if (q.executeUpdate()!=1) throw new SQLException("联运装货检查点已变化");
            }
        }
    }

    void setDispatchClosed(int id, boolean closed) throws SQLException {
        try (var q=db.prepareStatement("UPDATE waybill SET closed=? WHERE relief=?")) {
            q.setBoolean(1,closed); q.setInt(2,id); q.executeUpdate();
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
    record Return(Route route,int step,String bill){}
    List<Return> returns()throws SQLException{
        var list=new ArrayList<Return>();
        try(var q=db.prepareStatement("SELECT route,step,(SELECT id FROM trips WHERE route=crew_return.route AND phase='DELIVERED' ORDER BY rowid DESC LIMIT 1) AS bill FROM crew_return ORDER BY route");var r=q.executeQuery()){
            while(r.next())list.add(new Return(route(r.getString(1)),r.getInt(2),r.getString(3)));
            return list;
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
