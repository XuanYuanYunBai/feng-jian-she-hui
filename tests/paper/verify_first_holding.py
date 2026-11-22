"""Check the saved outcome of the single-household Paper opening scenario."""
import argparse
import sqlite3
from pathlib import Path


def verify(database, owner, manor_name):
    with sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True) as db:
        (home, x, z, west, north, east, south, households, cottage), = db.execute("""
            SELECT id,x,z,west,north,east,south,households,cottage
            FROM manor WHERE owner=? AND name=?""", (owner, manor_name))
        assert (west, north, east, south) == (x-4, z-4, x+4, z+4)
        assert (households, cottage) == (1, 1)
        assert db.execute("SELECT count(*) FROM manor WHERE owner=?", (owner,)).fetchone() == (1,)
        (wheel,), = db.execute("SELECT id FROM mill WHERE manor=?", (home,))
        (quota, delivered), = db.execute("""
            SELECT quota,delivered_flour FROM farmhand WHERE mill=? AND role='磨粉'""", (wheel,))
        assert (quota, delivered) == (61, 1)
        assert db.execute("SELECT count(*) FROM land WHERE title=?", (home,)).fetchone() == (0,)
    print("通过 · 一户首庄、九格见方围界、三份小麦磨粉并入仓、未新增政治领地")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path, help="测试存档的 realm.sqlite")
    parser.add_argument("owner", help="测试庄主 UUID")
    parser.add_argument("name", help="登记的庄名")
    args = parser.parse_args()
    verify(args.database, args.owner, args.name)
