"""Verify stopped saves from the two-client 0.13.1 succession and escorted-migration fixture."""
import argparse
from pathlib import Path
import sqlite3


def open_save(path):
    return sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)


def verify(waiting, final):
    with open_save(waiting) as db:
        traveler, escort, x, y, z = db.execute("SELECT person,escort,x,y,z FROM family_journey").fetchone()
        assert (traveler, escort, y, z) == (2, 1, 76, 49) and 285 <= x <= 291
        assert db.execute("SELECT rep FROM family WHERE id=1").fetchone() == (5,)
        assert db.execute("SELECT home FROM kin WHERE id=2").fetchone() == (None,)
        assert db.execute("SELECT residents FROM manor WHERE id='farmquay'").fetchone() == (100,)
        assert db.execute("SELECT residents FROM manor WHERE id='eastquay'").fetchone() == (298,)
    with open_save(final) as db:
        def row(q, *args):
            return db.execute(q, args).fetchone()

        assert row("SELECT COUNT(*) FROM life_receipt WHERE person=1") == (1,)
        assert row("SELECT name,sex,home,death FROM kin WHERE id=2") == ("阿黛尔", "女", "farmquay", None)
        assert row("SELECT COUNT(*) FROM family_journey") == (0,)
        # In the later title-law fixture two subsequent deaths each reduce the deceased's home by one.
        assert row("SELECT residents+(SELECT count(*) FROM kin WHERE id=5 AND death IS NOT NULL AND home='eastquay') FROM manor WHERE id='eastquay'") == (298,)
        assert row("SELECT residents+(SELECT count(*) FROM kin WHERE name='雷蒙' AND death IS NOT NULL AND home='farmquay') FROM manor WHERE id='farmquay'") == (101,)
        old = row("SELECT id FROM family WHERE name='柳岸家' AND rep IS NULL AND ended IS NOT NULL")
        assert old and row("SELECT death FROM kin WHERE family=? AND name='阿黛尔'", *old) == (None,)
        human, human_house = row("SELECT id,family FROM kin WHERE name='东岸外孙'")
        chosen, received, natal = row("""SELECT k.id,k.family,a.birth_house FROM kin k
            JOIN family_adoption a ON a.person=k.id WHERE k.name='乡户外孙'""")
        assert row("SELECT rep FROM family WHERE id=?", received) == (chosen,)
        assert row("SELECT COUNT(*) FROM ai_house WHERE family=?", natal) == (1,)
        junior, = row("SELECT id FROM kin WHERE name='亨利幼子'")
        assert row("SELECT rep FROM family WHERE id=?", human_house) == (junior,)
        account, = row("SELECT account FROM family WHERE id=?", human_house)
        assert row("SELECT lawful,seal FROM title WHERE id='flood'") == (account, account)
        assert row("SELECT lawful,seal FROM title WHERE id='upland'") == (f"族人·{human}",) * 2
        assert row("SELECT lawful,seal FROM title WHERE id='millright'") == (f"族人·{human}",) * 2
        assert row("SELECT lawful,seal FROM title WHERE id='harbor'") == ("待任", "待任")
        assert row("SELECT law FROM territorial_succession WHERE title='millright'") == ("女系递传",)
        assert row("SELECT ordering FROM territorial_succession WHERE title='upland'") == ("长嗣",)
        assert row("SELECT ordering FROM territorial_succession WHERE title='flood'") == ("幼嗣",)
        assert row("PRAGMA integrity_check") == ("ok",)
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
    print("通过 · 途中换代、两庄人口、外孙家籍、两种领法与各领长幼次序")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("waiting", type=Path, help="停服时亲属仍在途的 realm.sqlite")
    parser.add_argument("final", type=Path, help="全部场景结束后停服的 realm.sqlite")
    args = parser.parse_args()
    verify(args.waiting, args.final)
