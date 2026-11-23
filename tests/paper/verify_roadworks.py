"""Check the two road budgets in the stopped 0.15.0 River Manor test save."""
import argparse
import json
from pathlib import Path
import sqlite3

import nbtlib

from verify_saved_ores import read_chunk
from verify_storehouses import block_at


def verify(server):
    dim = server / "feudal-world/dimensions/minecraft/overworld"
    settings = nbtlib.load(dim / "data/minecraft/world_gen_settings.dat")["data"]
    assert settings["dimensions"]["minecraft:overworld"]["generator"]["type"] == "minecraft:noise"

    def ledger(name):
        path = server / "plugins/FeudalSurvey" / (name + ".sqlite")
        return sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)

    def contents(x, y, z):
        chunk = read_chunk(dim / "region", x >> 4, z >> 4)
        block = next(b for b in chunk["block_entities"] if tuple(int(b[k]) for k in ("x", "y", "z")) == (x, y, z))
        return {int(i["Slot"]): (str(i["id"]), int(i["count"])) for i in block["Items"]}

    with ledger("realm") as db:
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        assert not db.execute("PRAGMA foreign_key_check").fetchall()
        works = db.execute("""SELECT w.id,w.bid,w.sponsor,w.inspector,w.result,b.goods,b.price,b.remaining,b.currency
            FROM road_work w JOIN bid b ON w.bid=b.id WHERE w.route='northlane' ORDER BY w.id""").fetchall()
        paid, withdrawn = works
        case, bid, holder, inspector, result, *offer = paid
        assert holder == inspector and result == "现场验通" and offer == ["桥路铁件", 3, 0, "GOLD"]
        donor = withdrawn[2]
        assert donor != holder and withdrawn[4:] == ("已撤单", "桥路铁件", 3, 0, "GOLD")
        assert db.execute("SELECT seller,paid FROM trade WHERE bid=?", (bid,)).fetchall() == [(donor, 3)]
        assert not db.execute("SELECT id FROM trade WHERE bid=?", (withdrawn[1],)).fetchall()
        assert db.execute("SELECT fitter,plank,uses FROM road_work_material WHERE work=?", (case,)).fetchall() == [
            (holder, "OAK_PLANKS", 1)]
        assert not db.execute("SELECT 1 FROM road_work_material WHERE work=?", (withdrawn[0],)).fetchall()
        assert db.execute("SELECT stage,blows FROM smith_order").fetchall() == [(3, 3)]
        def till(name):
            return contents(*db.execute("SELECT x,y,z FROM stall WHERE id=?", (name,)).fetchone())
        assert till("bankfair") == {3: ("minecraft:gold_nugget", 1)}
        assert till("silverpost") == {0: ("minecraft:iron_ingot", 1), 1: ("minecraft:gold_nugget", 3)}

    deck = read_chunk(dim / "region", -420 >> 4, -285 >> 4)
    sections = {int(section["Y"]): section for section in deck["sections"]}
    assert block_at(sections, -420, 77, -285) == "minecraft:oak_planks"
    with ledger("freight") as db:
        assert db.execute("SELECT phase,step,material,amount,signer FROM trips WHERE id='roadwheat'").fetchone() == (
            "DELIVERED", 25, "WHEAT", 4, donor)
        assert not contents(-430, 77, -285)
        assert contents(-405, 77, -285) == {0: ("minecraft:wheat", 4)}

    sets = json.loads((server / "bluemap/web/maps/feudal_world/live/markers.json").read_text())
    assert not sets["feudal-roadworks"]["markers"]
    assert any("northlane" in marker["label"] for marker in sets["feudal-network"]["markers"].values())
    print(f"Seed {int(settings['seed'])}. One forged brace fitted, 3 gold paid once, 4 wheat delivered, "
          "3 gold and plain iron retained at the withdrawn order's till.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    verify(parser.parse_args().server.resolve())
