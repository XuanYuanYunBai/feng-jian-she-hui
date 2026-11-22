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
    has_currency = db.execute("SELECT count(*) FROM pragma_table_info('crossing') WHERE name='currency'").fetchone()[0]
    unit = "c.currency" if has_currency else "'GOLD'"
    for bridge, owner, fee, x, y, z, currency in db.execute(f"""
            SELECT c.id,t.name,c.fee,c.x,c.y,c.z,{unit} FROM crossing c JOIN title t ON t.id=c.owner"""):
        seen.add(bridge)
        marker = bridges.get(bridge, {})
        if marker.get("position") != {"x": x + .5, "y": y + 1.5, "z": z + .5}:
            raise ValueError("地图桥亭位置不符 " + bridge)
        lines = marker["detail"].split("<br>")
        grants = ["免税特许"] + [f"{name}　余 {remaining} 次" for name, remaining in db.execute("""
            SELECT t.name,g.remaining FROM bridge_privilege g JOIN title t ON t.id=g.holder
            WHERE g.bridge=? AND g.remaining>0 ORDER BY g.id""", (bridge,))]
        expected = [f"收费权　{owner}", f"每次 {fee} {'银币' if currency == 'SILVER' else '金粒'}"] + (grants if len(grants) > 1 else ["当前无免税特许"])
        if [html.unescape(line.removesuffix("</div>")) for line in lines[1:]] != expected:
            raise ValueError("桥亭公示与在册权利不一致 " + bridge)
    if set(bridges) != seen:
        raise ValueError("地图包含未登记的桥亭")
    return f"通过 · {len(mapped_land)} 个区块，{len(bridges)} 座桥亭，{len(expected_deeds)} 份待签文书"


def check_control(db, markers):
    expected = {f"{x}_{z}": ((16*x, 16*z, 16*(x+1), 16*(z+1)), f"{title} · 实际控制国 {nation}")
                for x, z, title, nation in db.execute("""
                    SELECT l.x,l.z,t.name,n.name FROM land l JOIN occupation o ON o.title=l.title
                    JOIN title t ON t.id=l.title JOIN title n ON n.id=o.nation WHERE o.active=1""")}
    actual = {key: (bounds(marker), html.unescape(marker["label"])) for key, marker in markers.items()}
    if actual != expected:
        raise ValueError("整领实控图层与生效交印不相符")
    return f"，{len(expected)} 个交接实控区块"


def check_markets(db, markers):
    stalls = {row[0]: row[1:] for row in db.execute("""
            SELECT t.id,t.name,t.x,t.y,t.z,count(b.id) FROM stall t
            LEFT JOIN bid b ON b.stall=t.id AND b.remaining>0 WHERE t.x IS NOT NULL GROUP BY t.id""")}
    if set(stalls) != set(markers):
        raise ValueError("商站地图名册与当前柜台不符")
    for key, (name, x, y, z, offers) in stalls.items():
        pin = markers[key]
        if (html.unescape(pin["label"]) != name or pin["position"] != dict(x=x+.5, y=y+1.5, z=z+.5)
                or not any(f"{label}　{offers} 份" in pin["detail"] for label in ("在榜收购单", "在榜订单"))):
            raise ValueError("商站位置或买单公示不符 " + key)
    return f"，{len(stalls)} 座商站"


def check_routes(path, layers):
    with sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True) as freight:
        rows = freight.execute("SELECT name,sx,y,sz,dx,dz,path FROM routes").fetchall()
    markers = layers["feudal-network"]["markers"]
    if set(markers) != {row[0] for row in rows}:
        raise ValueError("公布的货线名册有差异")
    berths = set()
    for name, sx, y, sz, dx, dz, saved in rows:
        if saved is None:
            delta_x, delta_z = (dx > sx) - (dx < sx), (dz > sz) - (dz < sz)
            cells = [(sx + step * delta_x, y, sz + step * delta_z) for step in range(abs(dx-sx) + abs(dz-sz) + 1)]
        else:
            cells = [tuple(map(int, node.split(","))) for node in saved.split("|")[2].split(";")]
            if saved.split("|")[1] == "water":
                berths.update([cells[0], cells[-1]])
        actual = [(v["x"], v["y"], v["z"]) for v in markers[name]["line"]]
        if actual != [(x + .5, y + 1.15, z + .5) for x, y, z in cells]:
            raise ValueError("地图路线与勘测的逐格坐标不符 " + name)
    if "feudal-ferries" in layers:
        actual_berths = {tuple(marker["position"][axis] for axis in "xyz")
                         for marker in layers["feudal-ferries"]["markers"].values()}
        if actual_berths != {(x + .5, y + 1, z + .5) for x, y, z in berths}:
            raise ValueError("地图泊位与公共航线端点不符")
        return f"，{len(rows)} 条实测货线，{len(berths)} 处已勘测泊位"
    return f"，{len(rows)} 条实测货线"


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
                result = compare(db, markers)
                if db.execute("SELECT 1 FROM sqlite_schema WHERE name='occupation'").fetchone():
                    result += check_control(db, markers["feudal-control"]["markers"])
                if "feudal-network" in markers:
                    result += check_routes(args.database.with_name("freight.sqlite"), markers)
                if "feudal-markets" in markers:
                    result += check_markets(db, markers["feudal-markets"]["markers"])
                print(result)
                break
            except (ValueError, KeyError, OSError) as error:
                if attempt == 8:
                    raise SystemExit(str(error))
                time.sleep(2)
            finally:
                db.rollback()
