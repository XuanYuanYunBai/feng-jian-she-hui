"""Verify census, receipts and BlueMap in the saved resident-economy test world."""
import argparse
import json
import sqlite3
from pathlib import Path


def verify(instance):
    data = instance / "plugins/FeudalSurvey"
    old = data / "realm.sqlite.pre-0.11.0"
    assert old.is_file()
    with sqlite3.connect(old.resolve().as_uri() + "?mode=ro", uri=True) as previous:
        assert "residents" not in {row[1] for row in previous.execute("PRAGMA table_info(manor)")}
    with sqlite3.connect((data / "realm.sqlite").resolve().as_uri() + "?mode=ro", uri=True) as db:
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        balances = db.execute("""
            SELECT opening,births,deaths,closing FROM population_season""").fetchall()
        assert balances and all(a + b - c == d for a, b, c, d in balances)
        assert db.execute("""
            SELECT count(*) FROM population_season WHERE opening=100 AND capacity=200
            AND bread=1 AND births=2 AND closing=102""").fetchone()[0] > 0
        assert db.execute("SELECT count(*) FROM population_season WHERE shortfall>0").fetchone()[0] > 0
        assert db.execute("""
            SELECT count(*) FROM population_season WHERE capacity=0 AND opening=closing AND closing>0
            """).fetchone()[0] > 0
        villages = db.execute("SELECT title,name FROM founding_village WHERE done=1").fetchall()
        assert villages
        for title, _ in villages:
            assert db.execute("""
                SELECT l.title FROM manor m JOIN land l ON l.x=(m.x>>4) AND l.z=(m.z>>4)
                WHERE m.id=?""", ("village_" + title,)).fetchone() == (title,)
        # The controlled road test opened with 95 residents at origin, 106 at destination.
        move = db.execute("""
            SELECT source,dest,season,people,step,arrived,status FROM migration
            WHERE route='courtyard' ORDER BY id LIMIT 1""").fetchone()
        assert move and move[3:] == (94, 16, 1, "抵达")
        source, host, season = move[:3]
        for manor, before, after in ((source, 95, 1), (host, 106, 200)):
            assert db.execute("SELECT closing FROM population_season WHERE manor=? AND season=?",
                              (manor, season)).fetchone() == (before,)
            assert db.execute("SELECT opening FROM population_season WHERE manor=? AND season=?",
                              (manor, season + 1)).fetchone() == (after,)
        assert db.execute("SELECT count(*) FROM population_season WHERE deaths>0").fetchone()[0] > 0
        kitchen = db.execute("SELECT count(*) FROM kitchen WHERE mill='ovenmill'").fetchone()[0]
        assert kitchen == 2
        markers = json.loads((instance / "bluemap/web/maps/feudal_world/live/markers.json").read_text())
        for key, label in db.execute("SELECT id,name||' · '||residents||' 人' FROM manor"):
            assert markers["feudal-estates"]["markers"][key]["label"] == label
        print(f"通过 · {len(balances)} 条人口季报，{len(villages)} 座初始聚落，实路迁居及 BlueMap 人口与庄籍相符")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("instance", type=Path)
    verify(parser.parse_args().instance)
