"""核对河港联运验收副本。停服后运行，需要 nbtlib。"""
import argparse
import sqlite3
from pathlib import Path
from verify_saved_ores import read_chunk


def connect(server, name):
    path = server / "plugins/FeudalSurvey" / (name + ".sqlite")
    return sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)


def barrel(server, x):
    regions = server / "feudal-world/dimensions/minecraft/overworld/region"
    chunk = read_chunk(regions, x // 16, 52 // 16)
    return next(block["Items"] for block in chunk["block_entities"]
                if tuple(int(block[axis]) for axis in "xyz") == (x, 75, 52))


def verify(server):
    with connect(server, "realm") as realm:
        assert realm.execute("SELECT source,dest,stage,quantity FROM relief WHERE id=1").fetchone() == (
            "farmquay", "eastquay", 3, 3)
        consumed = dict(realm.execute("SELECT manor,sum(bread) FROM population_season GROUP BY manor"))
    with connect(server, "freight") as freight:
        rows = freight.execute("""
            SELECT l.route,t.phase,t.amount,t.cargo=r.cargo,t.signer
            FROM waybill r JOIN waybill_leg l USING(relief) LEFT JOIN trips t ON t.id=l.bill
            WHERE r.relief=1 ORDER BY l.seq""").fetchall()
        assert [row[0] for row in rows] == ["fieldport", "estuary", "dockmarket"]
        assert all(row[1:] == ("DELIVERED", 3, 1, "货站交接") for row in rows)
    def bread(x):
        return [(int(i["count"]), str(i.get("components", ""))) for i in barrel(server, x)
                if str(i["id"]) == "minecraft:bread"]
    assert sum(n for n, _ in bread(266)) + consumed.get("farmquay", 0) == 1
    assert not bread(274) and not bread(308)
    assert all(not meta for _, meta in bread(318))
    assert sum(n for n, _ in bread(318)) + consumed.get("eastquay", 0) == 3
    print("通过 · 一张赈单、三段同批实货、两次站桶换装、三份面包开封入粮，供粮庄自留的一份与季粮账相符。")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    verify(parser.parse_args().server)
