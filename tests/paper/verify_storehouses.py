"""核对停服存档里的税仓建筑、牌匾与唯一账台。先在实服加载要核对的全部税仓区块。"""
import argparse
from pathlib import Path
import sqlite3

from verify_saved_ores import block_id, read_chunk


def block_at(sections, x, y, z):
    states = sections[y // 16]["block_states"]
    palette = states["palette"]
    if len(palette) == 1:
        return block_id(palette[0])
    bits = max(4, (len(palette) - 1).bit_length())
    per_word = 64 // bits
    i = (y % 16) * 256 + (z % 16) * 16 + x % 16
    word = int(states["data"][i // per_word])
    return block_id(palette[(word >> ((i % per_word) * bits)) & ((1 << bits) - 1)])


def verify(server, world):
    regions = server / world / "dimensions/minecraft/overworld/region"
    ledger = server / "plugins/FeudalSurvey/realm.sqlite"
    with sqlite3.connect(ledger.resolve().as_uri() + "?mode=ro", uri=True) as db:
        depots = db.execute("SELECT id, name, x, z FROM depot ORDER BY id").fetchall()
    if not depots:
        raise ValueError("没有登记税仓")
    for depot, name, cx, cz in depots:
        chunk = read_chunk(regions, cx, cz)
        site = chunk.get("ChunkBukkitValues", {}).get("feudalsurvey:granary_site")
        if site is None:
            raise ValueError(f"{name}所在区块还没有已保存的仓房")
        x, floor, z = map(int, site)
        sections = {int(s["Y"]): s for s in chunk["sections"]}
        desks = [t for t in chunk["block_entities"]
                 if t.get("PublicBukkitValues", {}).get("feudalsurvey:realm_depot") == depot]
        if len(desks) != 1 or tuple(int(desks[0][k]) for k in ("x","y","z")) != (x, floor + 1, z + 1):
            raise ValueError(f"{name}账台重复或错位")
        for dx in range(-3, 4):
            roof = "minecraft:dark_oak_planks" if dx == 0 else "minecraft:spruce_stairs"
            for dz in range(-3, 4):
                if block_at(sections, x+dx, floor+6-abs(dx), z+dz) != roof:
                    raise ValueError(f"{name}屋顶缺失")
        for dx in range(-2, 3):
            for dz in range(-2, 3):
                if block_at(sections, x+dx, floor, z+dz) != "minecraft:spruce_planks":
                    raise ValueError(f"{name}地板缺失")
        if not any(t["id"] == "minecraft:sign" and name in str(t.get("front_text", "")) for t in chunk["block_entities"]):
            raise ValueError(f"{name}缺少中文牌匾")
        print(f"{name} · 账台 ({x}, {floor+1}, {z+1}) · 49 格屋顶、25 格地板、中文牌匾已核实")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    parser.add_argument("world")
    args = parser.parse_args()
    verify(args.server, args.world)
