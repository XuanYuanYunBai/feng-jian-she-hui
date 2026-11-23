"""Check the stopped 0.16.0 two-crew River Manor save against its SQLite and physical receipts."""
import argparse
import json
from pathlib import Path
import sqlite3

import nbtlib

from verify_saved_ores import read_chunk
from verify_storehouses import block_at


def verify(server):
    world = server / "feudal-world"
    dim = world / "dimensions/minecraft/overworld"
    settings = nbtlib.load(dim / "data/minecraft/world_gen_settings.dat")["data"]
    assert settings["dimensions"]["minecraft:overworld"]["generator"]["type"] == "minecraft:noise"

    def ledger(name):
        path = server / "plugins/FeudalSurvey" / (name + ".sqlite")
        return sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)

    def contents(x, y, z):
        chunk = read_chunk(dim / "region", x >> 4, z >> 4)
        barrel = next(b for b in chunk["block_entities"] if tuple(int(b[k]) for k in ("x", "y", "z")) == (x, y, z))
        return {int(i["Slot"]): (str(i["id"]), int(i["count"])) for i in barrel["Items"]}

    def carried(player, item):
        bag = nbtlib.load(world / f"players/data/{player}.dat")["Inventory"]
        return sum(int(i["count"]) for i in bag if i["id"] == item)

    with ledger("freight") as db:
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        def bill(route):
            return db.execute("""SELECT amount,material,phase,step,signer FROM trips WHERE route=?""", (route,)).fetchone()
        grain, timber = bill("grainrise"), bill("woodlane")
        assert grain[:4] == (4, "WHEAT", "DELIVERED", 19)
        assert timber[:4] == (3, "OAK_LOG", "DELIVERED", 15)
        assert grain[4] != timber[4]
        assert carried(timber[4], "minecraft:oak_log") == 3
        assert contents(-386, 78, -285) == {0: ("minecraft:wheat", 4)}
        assert not contents(-386, 78, -270)
        for x, y, material in [(-403, 77, "dirt_path"), (-402, 77, "gravel"), (-400, 77, "oak_slab"),
                               (-399, 77, "stone_brick_stairs"), (-398, 78, "stone_brick_slab"), (-395, 78, "stone_bricks")]:
            chunk = read_chunk(dim / "region", x >> 4, -285 >> 4)
            sections = {int(section["Y"]): section for section in chunk["sections"]}
            assert block_at(sections, x, y, -285) == "minecraft:" + material
        assert contents(-405,77,-285) == {0: ("minecraft:iron_ingot", 3)}
        assert carried(grain[4], "minecraft:white_wool") == 2
        assert not db.execute("SELECT route FROM crew_return").fetchall()
        for name, signer, material, amount in [("tolleece",grain[4],"WHITE_WOOL",2),("tolliron",timber[4],"IRON_INGOT",3)]:
            assert db.execute("SELECT phase,signer,material,amount FROM trips WHERE id=?", (name,)).fetchone() == (
                "DELIVERED", signer, material, amount)

    with ledger("realm") as db:
        estate = db.execute("SELECT id,west,north,east,south FROM manor WHERE name='河台庄'").fetchone()
        assert estate[1:] == (-433,-282,-400,-255)
        assert db.execute("SELECT count(*) FROM land WHERE title=?", (estate[0],)).fetchone() == (4,)
        gate, = db.execute("SELECT id FROM crossing WHERE x=-420 AND y=78 AND z=-287 AND fee=1").fetchone()
        tolls = dict(db.execute("SELECT bill,actor FROM carrier_toll WHERE bridge=? AND fee=1 AND currency='GOLD'", (gate,)))
        assert set(tolls) == {"货-tolleece", "返-tolleece", "货-tolliron", "返-tolliron"}
        assert tolls["货-tolleece"] == tolls["返-tolleece"] != tolls["货-tolliron"] == tolls["返-tolliron"]
        assert contents(-420,78,-288) == {1: ("minecraft:gold_nugget",4)}
        assert db.execute("SELECT count(*) FROM passage WHERE bridge=?", (gate,)).fetchone() == (4,)

    markers = json.loads((server / "bluemap/web/maps/feudal_world/live/markers.json").read_text())
    assert {"woodlane","grainrise","tollbranch","northlane"} <= markers["feudal-network"]["markers"].keys()
    print(f"Seed {int(settings['seed'])}. 952 estate blocks, 4 political chunks, 2 crews at each shared stop, "
          "separate recipients, 4 gold matched to outward and return passages.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    verify(parser.parse_args().server.resolve())
