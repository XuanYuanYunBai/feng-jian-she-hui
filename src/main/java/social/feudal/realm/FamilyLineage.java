package social.feudal.realm;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Person eligibility is separate from the holder of a particular hereditary title. */
final class FamilyLineage {
    static final String AGNATIC = "父系男嗣", MATERNAL = "女系递传";
    private final FamilyRegister book;
    private final RealmStore s;

    FamilyLineage(FamilyRegister book) throws SQLException {
        this.book = book;
        s = book.s;
        s.update("CREATE TABLE IF NOT EXISTS ai_house(family INTEGER PRIMARY KEY REFERENCES family(id))");
        s.update("CREATE TABLE IF NOT EXISTS family_adoption(person INTEGER PRIMARY KEY REFERENCES kin(id),birth_house INTEGER REFERENCES family(id),house INTEGER REFERENCES family(id))");
        s.update("CREATE TABLE IF NOT EXISTS territorial_succession(title TEXT PRIMARY KEY REFERENCES title(id),law TEXT NOT NULL)");
        if(s.number("SELECT count(*) FROM pragma_table_info('territorial_succession') WHERE name='ordering'")==0)
            s.update("ALTER TABLE territorial_succession ADD COLUMN ordering TEXT NOT NULL DEFAULT '沿用家法'");
        s.update("CREATE INDEX IF NOT EXISTS kin_parent ON kin(parent)");
        s.update("CREATE INDEX IF NOT EXISTS kin_other ON kin(other)");
        s.update("CREATE INDEX IF NOT EXISTS kin_sibling ON kin(sibling)");
        if (s.number("SELECT count(*) FROM pragma_table_info('kin') WHERE name='sex'") == 0) {
            s.atomic(() -> {
                try(var alter=s.sql("ALTER TABLE kin ADD COLUMN sex TEXT NOT NULL DEFAULT '男' CHECK(sex IN ('男','女'))")){alter.execute();}
                // The earlier release always created a sister. Roles identify her even after a rename.
                s.update("UPDATE kin SET sex='女' WHERE sibling IS NOT NULL");
                s.update("UPDATE kin SET sex=CASE WHEN (random() & 1)=0 THEN '男' ELSE '女' END WHERE parent IS NOT NULL");
                s.update("""
                    UPDATE kin AS local SET sex=(SELECT CASE WHEN partner.sex='男' THEN '女' ELSE '男' END
                    FROM family_match m JOIN kin partner ON partner.id=m.a WHERE m.b=local.id LIMIT 1)
                    WHERE local.parent IS NULL AND local.sibling IS NULL AND EXISTS(SELECT 1 FROM family_match m
                    JOIN kin partner ON partner.id=m.a WHERE m.b=local.id AND partner.family=local.family)""");
                for(int id:book.ids("""
                    SELECT b.id FROM family_match m JOIN kin a ON a.id=m.a JOIN kin b ON b.id=m.b
                    WHERE a.family=b.family AND b.parent IS NULL AND b.sibling IS NULL""")){
                    int house=newLocalHouse(id);
                    s.update("UPDATE kin SET family=? WHERE id=?",house,id);
                }
                s.update("""
                    UPDATE family_match AS m SET children=(SELECT CASE WHEN a.sex='男' THEN a.family ELSE b.family END
                    FROM kin a JOIN kin b ON b.id=m.b WHERE a.id=m.a AND a.sex<>b.sex)
                    WHERE state IN ('已婚','议婚') AND EXISTS(SELECT 1 FROM kin a JOIN kin b ON b.id=m.b WHERE a.id=m.a AND a.sex<>b.sex)""");
                // Parent IDs precede child IDs. Preserve representatives of human houses and their settled rights.
                for(int child:book.ids("SELECT id FROM kin WHERE parent IS NOT NULL AND other IS NOT NULL ORDER BY id")){
                    var k=book.person(child);var a=book.person(k.parent());var b=book.person(k.other());
                    if(a.sex().equals(b.sex()) || book.rep(k.family())==child)continue;
                    int father=a.sex().equals("男")?a.family():b.family();
                    if(k.family()!=father){
                        s.update("UPDATE kin SET family=?,branch=0 WHERE id=?",father,child);
                        for(String[] asset:List.of(new String[]{"庄产","manor"},new String[]{"商站","stall"}))
                            s.update("UPDATE "+asset[1]+" SET owner=? WHERE id IN (SELECT asset FROM family_asset WHERE kind=? AND person=?)",FamilyRegister.personal(child),asset[0],child);
                    }
                }
                // Existing settled rights remain in force. Only pending handovers are re-evaluated.
                s.update("UPDATE family SET preferred=NULL WHERE preferred IN (SELECT id FROM kin WHERE sex='女')");
                return null;
            });
        }
        s.update("""
            CREATE TABLE IF NOT EXISTS marriage_parties(marriage INTEGER PRIMARY KEY REFERENCES family_match(id),
            first_house INTEGER NOT NULL REFERENCES family(id),second_house INTEGER NOT NULL REFERENCES family(id))""");
        s.update("""
            INSERT OR IGNORE INTO marriage_parties SELECT m.id,a.family,b.family FROM family_match m
            JOIN kin a ON a.id=m.a JOIN kin b ON b.id=m.b""");
    }

    boolean ai(int family) throws SQLException {
        return s.number("SELECT count(*) FROM ai_house WHERE family=?",family) != 0;
    }

    int newLocalHouse(int member) throws SQLException {
        s.update("INSERT INTO family(account,name,emblem) VALUES(?,?,?)","乡户·"+member,"乡户"+member,"麦穗");
        int house = s.number("SELECT last_insert_rowid()");
        s.update("INSERT INTO ai_house VALUES(?)",house);
        return house;
    }

    List<Integer> brothers(int person) throws SQLException {
        return book.ids("""
            SELECT b.id FROM kin k JOIN kin b ON b.family=k.family AND b.id<>k.id
            WHERE k.id=? AND b.sex='男' AND b.death IS NULL AND
              (b.sibling=k.id OR k.sibling=b.id OR b.sibling=k.sibling OR
               b.parent IN (k.parent,k.other) OR b.other IN (k.parent,k.other))
            ORDER BY b.birth,b.id""",person);
    }

    List<Integer> grandsons(int person, boolean aiOnly) throws SQLException {
        return book.ids("""
            SELECT DISTINCT n.id FROM kin p JOIN kin d ON p.id IN (d.parent,d.other)
            JOIN kin n ON d.id IN (n.parent,n.other)
            WHERE p.id=? AND d.sex='女' AND n.sex='男' AND n.death IS NULL
            AND (?=0 OR (n.family<>p.family AND EXISTS(SELECT 1 FROM ai_house h WHERE h.family=n.family)
                 AND NOT EXISTS(SELECT 1 FROM family player WHERE player.rep=n.id AND player.id NOT IN (SELECT family FROM ai_house))))
            ORDER BY n.birth,n.id""",person,aiOnly?1:0);
    }

    String law(String title) throws SQLException {
        String value = s.value("SELECT law FROM territorial_succession WHERE title=?",title);
        return value == null ? AGNATIC : value;
    }

    void setLaw(String title, String actor, String law, String ordering) throws SQLException {
        var t = s.title(title);
        RealmStore.require(t.seal().equals(actor) && t.lawful().equals(actor),"须由本领合法持印人办理");
        RealmStore.require(!title.equals("crown") && !t.charter().equals("流官") &&
                (t.charter().equals("收益封契") || (!List.of("修院","主教").contains(t.rank()) &&
                (t.parent()==null || s.number("SELECT hereditary FROM office WHERE id=?",title)==1))),"该职位没有世袭权");
        RealmStore.require(List.of(AGNATIC,MATERNAL).contains(law),"领法可选父系男嗣或女系递传");
        RealmStore.require(List.of("沿用家法","长嗣","幼嗣","年长者").contains(ordering),"领地次序可选沿用家法、长嗣、幼嗣或年长者");
        s.update("INSERT INTO territorial_succession(title,law,ordering) VALUES(?,?,?) ON CONFLICT(title) DO UPDATE SET law=excluded.law,ordering=excluded.ordering",title,law,ordering);
    }

    String order(String title,int person)throws SQLException{
        String local=s.value("SELECT ordering FROM territorial_succession WHERE title=?",title);
        return local==null || local.equals("沿用家法")?s.value("SELECT law FROM family WHERE id=?",book.person(person).family()):local;
    }

    List<Integer> agnates(int person) throws SQLException {
        return book.ids("""
            WITH RECURSIVE line(id) AS (
              SELECT ? UNION SELECT k.id FROM line JOIN kin member ON member.id=line.id JOIN kin k
              ON k.id IN (member.parent,member.other,member.sibling) OR member.id IN (k.parent,k.other,k.sibling)
                 OR member.sibling=k.sibling
              WHERE k.sex='男')
            SELECT k.id FROM line JOIN kin k ON k.id=line.id
            WHERE k.id<>? AND k.death IS NULL AND k.family=(SELECT family FROM kin WHERE id=?) ORDER BY k.birth,k.id""",person,person,person);
    }

    List<Integer> titleHeirs(int person, String title) throws SQLException {
        var heirs = book.bloodHeirs(person);
        if(heirs.isEmpty() || "年长者".equals(order(title,person))){
            var collateral=agnates(person);
            if(!collateral.isEmpty())heirs=collateral;
        }
        if (heirs.isEmpty() && MATERNAL.equals(law(title))) heirs.addAll(grandsons(person,false));
        return heirs;
    }

    List<Integer> playable(int person) throws SQLException {
        var candidates = new ArrayList<Integer>();
        int family = book.person(person).family();
        book.maleDescendants(person,candidates,new java.util.HashSet<>(),family);
        if (candidates.isEmpty()) candidates.addAll(brothers(person));
        if (candidates.isEmpty()) candidates.addAll(grandsons(person,true));
        return candidates;
    }

    void receive(int family, int person) throws SQLException {
        int oldHouse = book.person(person).family();
        if (oldHouse == family) return;
        RealmStore.require(ai(oldHouse),"有人类玩家的外家族不能被接管");
        s.update("INSERT INTO family_adoption VALUES(?,?,?)",person,oldHouse,family);
        s.update("UPDATE kin SET family=?,branch=0 WHERE id=?",family,person);
        s.update("UPDATE family_match SET children=? WHERE (a=? OR b=?) AND state IN ('议婚','已婚')",family,person,person);
        // The one person joins the receiving house. Existing siblings, offices and household inventory stay put.
        s.update("UPDATE family SET rep=NULL WHERE id=? AND rep=?",oldHouse,person);
        for (String[] asset : List.of(new String[]{"庄产","manor"},new String[]{"商站","stall"}))
            s.update("UPDATE "+asset[1]+" SET owner=? WHERE id IN (SELECT asset FROM family_asset WHERE kind=? AND person=?)",
                    book.account(family),asset[0],person);
    }
}
