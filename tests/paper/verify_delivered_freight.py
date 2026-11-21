"""Audit a just-delivered bill on a stopped Paper test server before the receiver empties the barrel."""

import argparse
from pathlib import Path
import sqlite3

from verify_saved_ores import read_chunk


def verify(server, bill):
    ledger = server / "plugins/FeudalSurvey/freight.sqlite"
    with sqlite3.connect(ledger.resolve().as_uri() + "?mode=ro", uri=True) as db:
        db.row_factory = sqlite3.Row
        db.execute("BEGIN")
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("SQLite integrity check failed")
        row = db.execute(
            "SELECT * FROM trips JOIN routes ON trips.route=routes.name WHERE trips.id=?", (bill,)
        ).fetchone()
        if not row or row["phase"] != "DELIVERED" or not row["signer"] or not row["delivered_at"]:
            raise ValueError(f"Missing delivery receipt for {bill}")
        if row["step"] != abs(row["sx"] - row["dx"]) + abs(row["sz"] - row["dz"]):
            raise ValueError("Cart did not reach the end of the route")
        regions = server / "world/dimensions/minecraft/overworld/region"
        for x, z, stage in ((row["sx"], row["sz"], "load"), (row["dx"], row["dz"], "deliver")):
            pos = (x, row["y"], z)
            chunk = read_chunk(regions, x // 16, z // 16)
            tile = next(be for be in chunk["block_entities"] if tuple(int(be[k]) for k in ("x", "y", "z")) == pos)
            receipt = tile["PublicBukkitValues"]["feudalsurvey:freight_receipt"]
            if str(receipt) != f"{stage}/{bill}":
                raise ValueError(f"Receipt mismatch at {pos}")
            slot = next((item for item in tile["Items"] if int(item["Slot"]) == 0), None)
            if stage == "load":
                if slot is not None:
                    raise ValueError("Source slot is occupied")
            elif slot is None or int(slot["count"]) != row["amount"] or str(slot["id"]) != "minecraft:" + row["material"].lower():
                raise ValueError("Delivered stack does not match the bill")
        print(f"Verified {bill}: {row['amount']} {row['material']}, {row['step']} blocks, signed by {row['signer']}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    parser.add_argument("bill")
    args = parser.parse_args()
    verify(args.server, args.bill)
