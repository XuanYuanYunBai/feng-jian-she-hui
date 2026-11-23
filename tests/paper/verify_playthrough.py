"""Verify the stopped River Manor 0.14.0 two-player playthrough against its physical save."""
import argparse
import json
from pathlib import Path
import sqlite3

import nbtlib

from verify_saved_ores import read_chunk


def verify(server):
    world = server / "feudal-world"
    dimension = world / "dimensions/minecraft/overworld"
    settings = nbtlib.load(dimension / "data/minecraft/world_gen_settings.dat")["data"]
    assert settings["dimensions"]["minecraft:overworld"]["generator"]["type"] == "minecraft:noise"
    with sqlite3.connect((server / "plugins/FeudalSurvey/realm.sqlite").as_uri() + "?mode=ro", uri=True) as db:
        def row(query, *args):
            return db.execute(query, args).fetchone()

        assert row("PRAGMA integrity_check") == ("ok",)
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        home, owner = row("SELECT id,owner FROM manor WHERE name='河台庄'")
        assert row("SELECT opening FROM population_season WHERE manor=? ORDER BY season LIMIT 1", home) == (100,)
        assert row("SELECT max(closing) FROM population_season WHERE manor=?", home) == (200,)
        assert row("SELECT count(*) FROM population_season WHERE closing<>opening+births-deaths") == (0,)
        assert row("""SELECT count(*) FROM population_turnover t JOIN population_season p USING(manor,season)
                      WHERE t.manor=? AND p.opening=p.capacity AND p.births=t.people AND p.deaths=t.people""", home)[0] > 0
        assert row("SELECT state FROM claim WHERE manor=?", home) == ("裁定",)
        assert row("SELECT rank,seal FROM title WHERE id=?", home) == ("伯爵", owner)
        assert row("SELECT count(*) FROM land WHERE title=?", home) == (4,)
        assert row("SELECT count(*) FROM land WHERE title='riverlease'") == (0,)
        visitor, = row("SELECT seal FROM title WHERE id='riverlease' AND parent=?", home)
        assert row("SELECT title FROM tax_right WHERE depot='rivergrain'") == ("riverlease",)
        assert row("SELECT max(delivered_flour) FROM farmhand WHERE mill='riverwheel'") == (1,)
        rep, father, sex, birth = row("""SELECT k.id,k.parent,k.sex,k.birth FROM family f JOIN kin k ON k.id=f.rep
                                         WHERE f.account=? AND f.ended IS NULL""", owner)
        assert sex == "男" and row("SELECT person FROM life_receipt WHERE person=?", father) == (father,)
        assert row("SELECT person FROM family_asset WHERE kind='庄产' AND asset=?", home) == (rep,)
        assert row("SELECT people FROM population_turnover WHERE manor=? AND season=?", home, birth)[0] > 0
        assert row("SELECT count(*),sum(paid) FROM trade WHERE seller=?", visitor) == (1, 1)
        assert row("SELECT remaining FROM bid WHERE stall='bankfair'") == (0,)
        x, y, z = row("SELECT x,y,z FROM stall WHERE id='bankfair'")
        till = next(b for b in read_chunk(dimension / "region", x >> 4, z >> 4)["block_entities"]
                    if (int(b["x"]), int(b["y"]), int(b["z"])) == (x, y, z))
        assert {int(i["Slot"]): (str(i["id"]), int(i["count"])) for i in till["Items"]} == {
            1: ("minecraft:wheat", 12), 3: ("minecraft:gold_nugget", 1)}
        bag = nbtlib.load(world / f"players/data/{visitor}.dat")["Inventory"]
        assert sum(int(i["count"]) for i in bag if i["id"] == "minecraft:wheat") == 4
        assert sum(int(i["count"]) for i in bag if i["id"] == "minecraft:gold_nugget") == 1
        markers = json.loads((server / "bluemap/web/maps/feudal_world/live/markers.json").read_text())
        assert home in markers["feudal-estates"]["markers"] and "bankfair" in markers["feudal-markets"]["markers"]
        print(f"Seed {int(settings['seed'])}, four chunks, one physical sale, family {father} → {rep}, birth season {birth}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    verify(parser.parse_args().server.resolve())
