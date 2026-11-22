package social.feudal.realm;

import java.sql.SQLException;
import static social.feudal.realm.RealmStore.require;

/** A finite toll concession is charged to a nation, independently of the person carrying its seal. */
final class BridgePrivileges {
    private final RealmStore s;

    BridgePrivileges(RealmStore s) throws SQLException {
        this.s = s;
        s.update("""
            CREATE TABLE IF NOT EXISTS bridge_privilege(
                id INTEGER PRIMARY KEY, bridge TEXT NOT NULL REFERENCES crossing(id),
                issuer TEXT NOT NULL REFERENCES nation(id), holder TEXT NOT NULL REFERENCES nation(id),
                uses INTEGER NOT NULL CHECK(uses BETWEEN 1 AND 64),
                remaining INTEGER NOT NULL CHECK(remaining BETWEEN 0 AND uses))""");
        s.update("""
            CREATE TABLE IF NOT EXISTS privilege_use(
                passage INTEGER PRIMARY KEY REFERENCES passage(id),
                privilege INTEGER NOT NULL REFERENCES bridge_privilege(id))""");
    }

    int grant(String actor, String bridge, String holder, int uses) throws SQLException {
        return s.atomic(() -> {
            String owner = s.value("SELECT owner FROM crossing WHERE id=?", bridge);
            require(owner != null, "桥亭未登记");
            s.politics.represents(owner, actor);
            require(!owner.equals(holder) && s.value("SELECT id FROM nation WHERE id=?",holder) != null, "请选择其他具名国家");
            require(uses >= 1 && uses <= 64, "一份通行特许可免 1–64 次桥税");
            require(s.number("SELECT count(*) FROM bridge_privilege WHERE bridge=? AND holder=? AND remaining>0", bridge, holder) == 0,
                    "该国在本桥的上一份特许尚有余次");
            s.update("INSERT INTO bridge_privilege(bridge,issuer,holder,uses,remaining) VALUES(?,?,?,?,?)",
                    bridge, owner, holder, uses, uses);
            return s.number("SELECT last_insert_rowid()");
        });
    }

    void checkPassage(String bridge, String actor, long now) throws SQLException {
        require(s.value("SELECT id FROM passage WHERE bridge=? AND payer=? AND at>?",bridge,actor,now-10) == null,
                "已签发此次通行回执");
    }

    int redeem(String actor, String bridge, int grant, long now) throws SQLException {
        return s.atomic(() -> {
            String holder = s.value("SELECT holder FROM bridge_privilege WHERE id=? AND bridge=? AND remaining>0",grant,bridge);
            require(holder != null, "这份特许在本桥没有可用次数");
            s.politics.represents(holder, actor);
            checkPassage(bridge, actor, now);
            s.update("UPDATE bridge_privilege SET remaining=remaining-1 WHERE id=?",grant);
            s.update("INSERT INTO passage(bridge,owner,payer,paid,at,currency) SELECT id,owner,?,0,?,currency FROM crossing WHERE id=?",
                    actor,now,bridge);
            int passage = s.number("SELECT last_insert_rowid()");
            s.update("INSERT INTO privilege_use VALUES(?,?)",passage,grant);
            return s.number("SELECT remaining FROM bridge_privilege WHERE id=?",grant);
        });
    }
}
