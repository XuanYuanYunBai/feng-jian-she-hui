"""Read-only check of the stopped two-family Paper 0.13.0 river-world test."""
import argparse
import io
from pathlib import Path
import sqlite3
import zlib
import nbtlib
from verify_saved_ores import read_chunk


def dropped_crates(directory, x, z):
    with (directory / f"r.{x // 32}.{z // 32}.mca").open("rb") as region:
        region.seek(4 * (x % 32 + 32 * (z % 32)))
        offset = int.from_bytes(region.read(4), "big") >> 8
        if offset < 2:
            return []
        region.seek(offset * 4096)
        length = int.from_bytes(region.read(4), "big")
        assert region.read(1) == b"\x02"
        chunk = nbtlib.File.parse(io.BytesIO(zlib.decompress(region.read(length - 1))))
    assert list(map(int, chunk["Position"])) == [x, z]
    return [crate(e["Item"]) for e in chunk["Entities"] if str(e["id"]) == "minecraft:item"]


def crate(item):
    data = item.get("components", {}).get("minecraft:custom_data", {}).get("PublicBukkitValues", {})
    return str(data.get("feudalsurvey:tax_crate", ""))


def verify(server):
    saved = server / "plugins/FeudalSurvey/realm.sqlite"
    world = server / "feudal-world"
    with sqlite3.connect(saved.resolve().as_uri() + "?mode=ro", uri=True) as db:
        key, account, rep = db.execute("SELECT id,account,rep FROM family WHERE name='柳岸家'").fetchone()
        dead, = db.execute("SELECT id FROM kin WHERE family=? AND name='奥托' AND death IS NOT NULL", (key,)).fetchone()
        assert db.execute("SELECT sibling,death FROM kin WHERE id=?", (rep,)).fetchone() == (dead, None)
        assert db.execute("SELECT count(*) FROM life_receipt WHERE person=?", (dead,)).fetchone() == (1,)
        assert db.execute("SELECT person FROM family_asset WHERE kind='庄产' AND asset='willows'").fetchone() == (rep,)
        assert db.execute("SELECT state,paid FROM family_match WHERE id=1").fetchone() == ("遗属", 0)
        assert db.execute("SELECT lawful,seal FROM title WHERE id='willows'").fetchone() == (account, account)
        seal, received = db.execute("SELECT id,received FROM crate WHERE depot='taxridge' AND period=7").fetchone()
        assert received == 0
    inventory = nbtlib.load(world / "players/data" / (account + ".dat"))["Inventory"]
    assert all(crate(item) != seal for item in inventory)
    directory = world / "dimensions/minecraft/overworld/entities"
    drops = [item for x in range(12, 15) for z in range(1, 4) for item in dropped_crates(directory, x, z)]
    assert drops.count(seal) == 1
    print("通过 · 婚书留档、身故仅一次、族人接掌私产与领印，死者税箱在地面且公库未签收")


def verify_finance(server):
    saved = server / "plugins/FeudalSurvey/realm.sqlite"
    regions = server / "feudal-world/dimensions/minecraft/overworld/region"

    def slots(x, y, z):
        chunk = read_chunk(regions, x // 16, z // 16)
        barrel = next(b for b in chunk["block_entities"]
                      if tuple(int(b[k]) for k in "xyz") == (x, y, z))
        assert str(barrel["id"]) == "minecraft:barrel"
        return {int(i["Slot"]): i for i in barrel["Items"]}

    def stamps(item):
        return item["components"]["minecraft:custom_data"]["PublicBukkitValues"]

    def silver(item):
        assert str(item["id"]) == "minecraft:iron_nugget" and int(stamps(item)["feudalsurvey:silver_coin"]) == 1
        return int(item["count"])

    with sqlite3.connect(saved.resolve().as_uri() + "?mode=ro", uri=True) as db:
        assert db.execute("SELECT state,paid FROM family_match WHERE id=1").fetchone() == ("遗属", 3)
        assert db.execute("SELECT marriage,manor,amount FROM family_payment WHERE bill='dowry/1'").fetchall() == [(1, "willows", 3)]
        assert db.execute("SELECT amount,packed,closed FROM family_cash WHERE id='fallcoin'").fetchone() == (30, 1, 0)

    source, destination = slots(319, 75, 52), slots(197, 67, 35)
    purse = source[8]
    assert 0 not in source and str(stamps(purse)["feudalsurvey:estate_cash"]) == "fallcoin"
    inside, = purse["components"]["minecraft:container"]
    remaining, delivered = silver(inside["item"]), silver(destination[8])
    assert (remaining, delivered, remaining + delivered) == (30, 3, 33)
    assert str(destination[2]["id"]) == "minecraft:bread" and int(destination[2]["count"]) == 6
    print("通过 · 两座实柜中原有的 33 枚银币逐枚对账：3 枚嫁资已签收，30 枚仍在原庄家库封银内")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    parser.add_argument("--finance", action="store_true", help="复核第二阶段的实柜与嫁资付款")
    args = parser.parse_args()
    (verify_finance if args.finance else verify)(args.server)
