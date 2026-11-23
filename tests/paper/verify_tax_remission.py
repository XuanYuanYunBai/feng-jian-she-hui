"""核对 0.12.2 山道税仓验收副本的减征令、税箱和停服实仓。需要 nbtlib。"""
import argparse
import sqlite3
from pathlib import Path
from verify_saved_ores import read_chunk


def wheat(server, x):
    regions = server / "feudal-world/dimensions/minecraft/overworld/region"
    chunk = read_chunk(regions, x >> 4, 42 >> 4)
    barrel = next(be for be in chunk["block_entities"]
                  if tuple(int(be[axis]) for axis in "xyz") == (x, 84, 42))
    return sum(int(item["count"]) for item in barrel["Items"] if str(item["id"]) == "minecraft:wheat")


def verify(server):
    path = server / "plugins/FeudalSurvey/realm.sqlite"
    with sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True) as db:
        writ = db.execute("""
            SELECT relief,depot,period,percent,land,beneficiary,issuer<>signer,state,spared
            FROM tax_remission WHERE id=1""").fetchone()
        assert writ == (2, "taxridge", 8, 50, "march", "willows", 1, "已减征", 10)
        assert db.execute("""
            SELECT due,automatic+packed FROM assessment WHERE depot='taxridge' AND period=8
            """).fetchone() == (11, 11)
        assert db.execute("""
            SELECT amount,received FROM crate WHERE depot='taxridge' AND period=8 ORDER BY amount
            """).fetchall() == [(3, 1), (8, 1)]
        assert db.execute("SELECT due FROM assessment WHERE depot='taxridge' AND period=7").fetchone() == (1,)
        assert db.execute("SELECT quantity,stage FROM relief WHERE id=2").fetchone() == (3, 0)
    assert wheat(server, 218) == 10
    assert wheat(server, 198) == 11
    print("通过 · 两人会签，实麦 21 份中产地留 10、公库收 11，上一期和赈粮单分别保留")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    verify(parser.parse_args().server)
