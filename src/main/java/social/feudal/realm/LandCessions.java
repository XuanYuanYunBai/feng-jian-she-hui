package social.feudal.realm;

import java.sql.SQLException;
import java.util.List;
import static social.feudal.realm.RealmStore.require;

/** A deed conveys the two sovereigns' specified demesne, subject to existing private and fiscal rights. */
final class LandCessions {
    static final String KIND = "割地条约";
    private final RealmStore store;
    private final Politics law;

    record Deed(String donor, String recipient, Charters.Bounds bounds) {}

    LandCessions(Politics law) throws SQLException {
        this.law = law;
        this.store = law.s;
        store.update("""
            CREATE TABLE IF NOT EXISTS land_cession(
                treaty TEXT PRIMARY KEY REFERENCES treaty(id), donor TEXT NOT NULL REFERENCES nation(id),
                recipient TEXT NOT NULL REFERENCES nation(id), west INTEGER NOT NULL, north INTEGER NOT NULL,
                east INTEGER NOT NULL, south INTEGER NOT NULL, CHECK(donor <> recipient))""");
    }

    Deed get(String id) throws SQLException {
        try (var q = store.sql("SELECT donor,recipient,west,north,east,south FROM land_cession WHERE treaty=?", id);
             var r = q.executeQuery()) {
            if (!r.next()) return null;
            return new Deed(r.getString(1), r.getString(2),
                    new Charters.Bounds(r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6)));
        }
    }

    private void checkLand(Deed deed) throws SQLException {
        var bounds = deed.bounds();
        int area = bounds.area();
        require(area <= 36, "一份割地文书最多列入 36 个区块");
        var seat = store.title(deed.donor());
        require(!bounds.contains(seat.x(), seat.z()), "请先依法迁都，再议让原治所");
        require(store.number("""
            SELECT count(*) FROM land WHERE title=? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?
            """, deed.donor(), bounds.west(), bounds.east(), bounds.north(), bounds.south()) == area,
                "须由让方直接管辖选区的每一个区块，请核对地籍");
    }

    void draft(String id, String donor, String recipient, String actor, Charters.Bounds bounds, String name) throws SQLException {
        store.atomic(() -> {
            var deed = new Deed(donor, recipient, bounds);
            law.represents(donor, actor);
            require(!donor.equals(recipient), "请选择另一个受让国家");
            checkLand(deed);
            law.registerTreaty(id, name, KIND, "移交选区公权；庄产、独立收益封契与已开税单照原册履行。",
                    List.of(donor, recipient), donor, actor);
            store.update("INSERT INTO land_cession VALUES(?,?,?,?,?,?,?)", id, donor, recipient,
                    bounds.west(), bounds.north(), bounds.east(), bounds.south());
            return null;
        });
    }

    // Called inside the same transaction as the final signature and change of treaty status.
    void convey(String id) throws SQLException {
        var deed = get(id);
        require(deed != null, "文书缺少地籍附件");
        String signer = store.value("SELECT signer FROM signatory WHERE treaty=? AND nation=?", id, deed.donor());
        law.represents(deed.donor(), signer);
        checkLand(deed);
        var b = deed.bounds();
        int changed = store.update("""
            UPDATE land SET title=? WHERE title=? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?
            """, deed.recipient(), deed.donor(), b.west(), b.east(), b.north(), b.south());
        require(changed == b.area(), "地籍已变，请重新核定选区");
        law.log(null, deed.donor(), "依约让地 " + id + " · " + changed + " 区块");
        law.log(null, deed.recipient(), "依约受地 " + id + " · " + changed + " 区块");
    }

    void cancel(String id, String country, String actor) throws SQLException {
        store.atomic(() -> {
            law.represents(country, actor);
            var deed = get(id);
            require(deed != null && (deed.donor().equals(country) || deed.recipient().equals(country)), "须由缔约国办理");
            String state = deed.donor().equals(country) ? "已撤回" : "已谢绝";
            require(store.update("UPDATE treaty SET state=? WHERE id=? AND state='待批准'", state, id) == 1,
                    "仅可撤回或谢绝待批准的割地条约");
            law.log(null, country, state + "割地文书 " + id);
            return null;
        });
    }
}
