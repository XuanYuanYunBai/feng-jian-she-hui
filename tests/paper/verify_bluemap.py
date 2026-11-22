"""Compare the running BlueMap public atlas with a read-only snapshot of the realm register."""
import argparse
import html
import json
import sqlite3
import time
from pathlib import Path
from urllib.request import urlopen


def bounds(marker):
    xs = [point["x"] for point in marker["shape"]]
    zs = [point["z"] for point in marker["shape"]]
    return min(xs), min(zs), max(xs), max(zs)


def compare(db, layers):
    for suffix in ("countries", "empire", "estates", "hearings", "crossings", "deeds"):
        if "feudal-" + suffix not in layers:
            raise ValueError("BlueMap 缺少图层 " + suffix)
    expected_deeds = {}
    for doc, west, north, east, south in db.execute("""
            SELECT c.treaty,c.west,c.north,c.east,c.south FROM land_cession c
            JOIN treaty t ON t.id=c.treaty WHERE t.state='待批准'"""):
        expected_deeds[doc] = (16 * west, 16 * north, 16 * (east + 1), 16 * (south + 1))
    actual_deeds = {key: bounds(marker) for key, marker in layers["feudal-deeds"]["markers"].items()}
    if expected_deeds != actual_deeds:
        raise ValueError("待签文书与地图地籍附件不一致")

    expected_land = {(x, z): name for x, z, name in db.execute("""
            SELECT l.x,l.z,n.name FROM land l JOIN affiliation a ON a.title=l.title
            JOIN title n ON n.id=coalesce(a.nation,'crown')""")}
    mapped_land = {}
    for marker in layers["feudal-countries"]["markers"].values():
        west, north, east, south = bounds(marker)
        if any(v % 16 for v in (west, north, east, south)) or south - north != 16 or east <= west:
            raise ValueError("领邦疆界没有沿区块绘制")
        for x in range(int(west) // 16, int(east) // 16):
            key = (x, int(north) // 16)
            if key in mapped_land:
                raise ValueError("领邦疆界重叠")
            mapped_land[key] = html.unescape(marker["label"])
    if mapped_land != expected_land:
        raise ValueError("领邦疆界与实际管辖国不一致")

    bridges = layers["feudal-crossings"]["markers"]
    seen = set()
    for bridge, owner, fee, x, y, z in db.execute("""
            SELECT c.id,t.name,c.fee,c.x,c.y,c.z FROM crossing c JOIN title t ON t.id=c.owner"""):
        seen.add(bridge)
        marker = bridges.get(bridge, {})
        if marker.get("position") != {"x": x + .5, "y": y + 1.5, "z": z + .5}:
            raise ValueError("地图桥亭位置不符 " + bridge)
        lines = marker["detail"].split("<br>")
        grants = ["免税特许"] + [f"{name}　余 {remaining} 次" for name, remaining in db.execute("""
            SELECT t.name,g.remaining FROM bridge_privilege g JOIN title t ON t.id=g.holder
            WHERE g.bridge=? AND g.remaining>0 ORDER BY g.id""", (bridge,))]
        expected = [f"收费权　{owner}", f"每次 {fee} 金粒"] + (grants if len(grants) > 1 else ["当前无免税特许"])
        if [html.unescape(line.removesuffix("</div>")) for line in lines[1:]] != expected:
            raise ValueError("桥亭公示与在册权利不一致 " + bridge)
    if set(bridges) != seen:
        raise ValueError("地图包含未登记的桥亭")
    return f"通过 · {len(mapped_land)} 个区块，{len(bridges)} 座桥亭，{len(expected_deeds)} 份待签文书"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    parser.add_argument("markers_url")
    args = parser.parse_args()
    with sqlite3.connect(args.database.resolve().as_uri() + "?mode=ro", uri=True) as db:
        for attempt in range(9):
            try:
                with urlopen(args.markers_url, timeout=5) as response:
                    markers = json.load(response)
                db.execute("BEGIN")
                print(compare(db, markers))
                break
            except (ValueError, KeyError, OSError) as error:
                if attempt == 8:
                    raise SystemExit(str(error))
                time.sleep(2)
            finally:
                db.rollback()
