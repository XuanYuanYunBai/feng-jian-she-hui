package social.feudal.realm;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

/** One political register per overworld. All writes run on the server thread. */
public final class RealmStore implements AutoCloseable {
    final Connection db;
    final Politics politics;
    public record Title(String id, String name, String rank, String parent, String charter,
                        String lawful, String seal, int x, int z) {}
    public record Land(int x, int z, String title) {}
    public record Depot(String id, String name, int x, int z, int yield) {}

    public RealmStore(Path path) throws SQLException {
        db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try {
            update("PRAGMA foreign_keys=ON");
            update("CREATE TABLE IF NOT EXISTS setting(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
            update("""
                CREATE TABLE IF NOT EXISTS title(id TEXT PRIMARY KEY,name TEXT NOT NULL,rank TEXT NOT NULL,
                parent TEXT REFERENCES title(id),charter TEXT NOT NULL,lawful TEXT NOT NULL,seal TEXT NOT NULL,
                x INTEGER NOT NULL,z INTEGER NOT NULL)""");
            update("""
                CREATE TABLE IF NOT EXISTS land(x INTEGER NOT NULL,z INTEGER NOT NULL,title TEXT NOT NULL REFERENCES title(id),
                PRIMARY KEY(x,z))""");
            update("""
                CREATE TABLE IF NOT EXISTS depot(id TEXT PRIMARY KEY,name TEXT NOT NULL,x INTEGER NOT NULL,z INTEGER NOT NULL,
                yield INTEGER NOT NULL CHECK(yield>0),FOREIGN KEY(x,z) REFERENCES land(x,z))""");
            if(ready() && value("SELECT name FROM sqlite_master WHERE name='association'")==null){
                Path backup=path.resolveSibling(path.getFileName()+".pre-0.5.0");
                if(!Files.exists(backup))try(var statement=sql("VACUUM INTO ?",backup.toString())){statement.execute();}
            }
            politics=new Politics(this);
        } catch (SQLException | RuntimeException ex) { db.close(); throw ex; }
    }
    interface Write<T> { T run() throws SQLException; }
    <T> T atomic(Write<T> work) throws SQLException {
        db.setAutoCommit(false);
        try {
            T result = work.run();
            db.commit();
            return result;
        } catch (SQLException | RuntimeException ex) {
            db.rollback();
            throw ex;
        } finally { db.setAutoCommit(true); }
    }
    PreparedStatement sql(String query, Object... args) throws SQLException {
        var q = db.prepareStatement(query);
        for (int i=0; i<args.length; i++) q.setObject(i+1,args[i]);
        return q;
    }
    int update(String query,Object... args) throws SQLException {
        try (var q=sql(query,args)) { return q.executeUpdate(); }
    }
    String value(String query,Object... args) throws SQLException {
        try (var q=sql(query,args); var r=q.executeQuery()) { return r.next()?r.getString(1):null; }
    }
    int number(String query,Object... args) throws SQLException {
        String v=value(query,args);
        return v==null?0:Integer.parseInt(v);
    }
    static void require(boolean ok,String message) { if (!ok) throw new IllegalArgumentException(message); }
    public boolean ready() throws SQLException { return value("SELECT v FROM setting WHERE k='world'")!=null; }
    void checkWorld(String world) throws SQLException {
        String saved=value("SELECT v FROM setting WHERE k='world'");
        require(saved==null || saved.equals(world),"政治账本属于另一个主世界");
    }
    void titleRow(String id,String name,String rank,String parent,String charter,String actor,int x,int z) throws SQLException {
        update("INSERT INTO title VALUES(?,?,?,?,?,?,?,?,?)",id,name,rank,parent,charter,actor,actor,x,z);
    }
    public void initialize(String world,int x,int z,String sovereign) throws SQLException {
        atomic(()->{
            require(!ready(),"已经建立帝国");
            update("INSERT INTO setting VALUES('world',?)",world);
            titleRow("crown","河湾帝国","皇帝",null,"皇室",sovereign,x,z);
            titleRow("march","东境伯领","伯爵","crown","直属特许","东境伯家",x+64,z);
            titleRow("abbey","圣弥额尔主教驻地","修院","crown","直属特许","主教座堂",x+128,z);
            try(var q=db.prepareStatement("INSERT INTO land VALUES(?,?,?)")) {
                for(var t:titles()) for(int dx=-8;dx<8;dx++) for(int dz=-8;dz<8;dz++) {
                    q.setInt(1,t.x()+dx); q.setInt(2,t.z()+dz); q.setString(3,t.id()); q.addBatch();
                }
                q.executeBatch();
            }
            if(Succession.isPlayer(sovereign)){
                titleRow("riverhome","河湾公国","公爵","crown","直属特许",sovereign,x-48,z);
                for(int dx=-2;dx<2;dx++)for(int dz=-2;dz<2;dz++)
                    update("INSERT INTO land VALUES(?,?,?)",x-48+dx,z+dz,"riverhome");
            }
            politics.bootstrap(false);
            depotRow("town","王城市集",x,z);
            depotRow("mill","西磨坊",x-5,z);
            depotRow("ford","东渡仓",x+7,z);
            depotRow("east","伯领公仓",x+64,z);
            depotRow("glebe","主教仓",x+128,z);
            return null;
        });
    }
    private void depotRow(String id,String name,int x,int z) throws SQLException {
        update("INSERT INTO depot VALUES(?,?,?,?,16)",id,name,x,z);
    }
    private static Title readTitle(ResultSet r) throws SQLException {
        return new Title(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                r.getString(6),r.getString(7),r.getInt(8),r.getInt(9));
    }
    public Title title(String id) throws SQLException {
        try(var q=sql("SELECT * FROM title WHERE id=?",id); var r=q.executeQuery()) {
            require(r.next(),"未知头衔 "+id); return readTitle(r);
        }
    }
    public List<Title> titles() throws SQLException {
        var rows=new ArrayList<Title>();
        try(var q=sql("SELECT * FROM title ORDER BY rowid");var r=q.executeQuery()) {
            while(r.next()) rows.add(readTitle(r));
        } return rows;
    }
    public String owner(int x,int z) throws SQLException { return value("SELECT title FROM land WHERE x=? AND z=?",x,z); }
    public List<Land> lands() throws SQLException {
        var rows=new ArrayList<Land>();
        try(var q=sql("SELECT * FROM land");var r=q.executeQuery()){
            while(r.next())rows.add(new Land(r.getInt(1),r.getInt(2),r.getString(3)));
        } return rows;
    }
    public List<Depot> depots() throws SQLException {
        var rows=new ArrayList<Depot>();
        try(var q=sql("SELECT * FROM depot ORDER BY rowid");var r=q.executeQuery()){
            while(r.next())rows.add(new Depot(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5)));
        } return rows;
    }
    void foundDepot(String actor,String id,String name,int x,int z)throws SQLException{
        atomic(()->{
            require(id.matches("[a-z][a-z0-9_-]{0,23}"),"无效税仓代号");
            String owner=owner(x,z);require(owner!=null && title(owner).seal().equals(actor),"须由本区块持印人登记税仓");
            require(value("SELECT id FROM depot WHERE id=? OR (x=? AND z=?)",id,x,z)==null,"代号或税仓区块已登记");
            depotRow(id,name,x,z);
            int first=Math.max(1,number("SELECT v FROM setting WHERE k='period'"))+1;
            update("INSERT INTO setting VALUES(?,?)","first:"+id,first);return null;
        });
    }
    public String polity(String id) throws SQLException {
        String country=politics.nation(id);
        return country==null?title("crown").name():title(country).name();
    }
    @Override public void close() throws SQLException { db.close(); }
}
