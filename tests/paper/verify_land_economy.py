"""核对 0.12.0 两个实测存档的区块、实仓和账本。需要 nbtlib。"""
import argparse
import sqlite3
import struct
from pathlib import Path

from verify_storehouses import block_at, read_chunk


def db(server, name):
    path = server / "plugins/FeudalSurvey" / (name + ".sqlite")
    return sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)


def verify_land(server):
    home = "h_eafd6f46d2a83c0f932b"
    with db(server, "realm") as realm:
        assert realm.execute("SELECT state FROM claim WHERE manor=?", (home,)).fetchone() == ("裁定",)
        assert realm.execute("SELECT count(*) FROM land WHERE title=?", (home,)).fetchone() == (4,)
        assert set(realm.execute("SELECT kind FROM manor_room WHERE manor=?", (home,))) == {
            ("私床",), ("私柜",), ("大厅",), ("议事厅",)}
        assert realm.execute("""
            SELECT m.residents,j.district,t.lawful=t.seal FROM manor m
            JOIN dwelling_jurisdiction j USING(id) JOIN title t ON t.id=j.title WHERE m.id=?
            """, (home,)).fetchone() == (200, home, 1)
        assert realm.execute("SELECT quantity,stage FROM relief WHERE dest=?", (home,)).fetchall() == [(2, 3)]
        assert realm.execute("SELECT stage,status FROM smith_order WHERE manor=?", (home,)).fetchall() == [(3, "铁件已交仓")]
        shifts = realm.execute("""
            SELECT x,y,z,kind FROM pit_shift WHERE pit='rivermine' AND stage=3 AND status='已交矿一份' ORDER BY x
            """).fetchall()
        assert shifts == [(4826, 35, 4823, 1), (4827, 35, 4823, 1)]
        blob, = realm.execute("SELECT sample FROM mineral_map WHERE cx=301 AND cz=301").fetchone()
        survey = {key for key, in struct.iter_unpack(">i", blob)}
        assert len(survey) == 203
        for x, y, z, kind in shifts:
            assert ((((y+64)*16 + z%16)*16 + x%16)*4 + kind) in survey
            assert realm.execute("SELECT count(*) FROM mineral_spent WHERE x=? AND y=? AND z=?",
                                 (x, y, z)).fetchone() == (1,)
    with db(server, "freight") as freight:
        assert freight.execute("SELECT amount,material,phase FROM trips WHERE id='alms12'").fetchone() == (2, "BREAD", "DELIVERED")
    regions = server / "feudal-world/dimensions/minecraft/overworld/region"
    mine = read_chunk(regions, 301, 301)
    sections = {int(s["Y"]): s for s in mine["sections"]}
    assert all(block_at(sections, x, y, z) == "minecraft:air" for x, y, z, _ in shifts)
    barrel = next(b for b in mine["block_entities"] if tuple(int(b[k]) for k in "xyz") == (4823, 35, 4825))
    items = {str(stack["id"]): stack for stack in barrel["Items"]}
    assert int(items["minecraft:raw_iron"]["count"]) == 2
    assert int(items["minecraft:iron_pickaxe"]["components"]["minecraft:damage"]) == 2
    road = read_chunk(regions, -401//16, -262//16)
    assert block_at({int(s["Y"]): s for s in road["sections"]}, -401, 70, -262) == "minecraft:spruce_planks"
    print("通过 · 四区块建领、还印后 200 人、两份赈粮、两处不可重采矿面、实仓粗铁及镐损、铁件补路")


def verify_river(server):
    with db(server, "freight") as freight:
        trips = freight.execute("""
            SELECT t.phase,t.amount,t.material,t.cargo,r.path,r.source_seal,r.dest_seal
            FROM trips t JOIN routes r ON r.name=t.route WHERE t.id IN ('river12leg1','river12leg2','river12leg3')
            ORDER BY t.id""").fetchall()
        assert [row[4].split("|")[1] for row in trips] == ["road", "water", "road"]
        assert all(row[:3] == ("DELIVERED", 3, "BREAD") for row in trips)
        assert len({row[3] for row in trips}) == 1
        assert all(a[6] == b[5] for a, b in zip(trips, trips[1:]))
    print("通过 · 三份同批面包随脚夫、船工、脚夫在两处泊位交接")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manor_server", type=Path)
    parser.add_argument("river_server", type=Path)
    args = parser.parse_args()
    verify_land(args.manor_server)
    verify_river(args.river_server)
