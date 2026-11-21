"""Compare a stopped Paper 26.3 test world's zlib Anvil regions to FeudalSurvey's SQLite ledger."""

import argparse
import io
from pathlib import Path
import sqlite3
import zlib

import nbtlib


ORE_TYPES = {
    f"minecraft:{prefix}{metal}_ore": index
    for index, metal in enumerate(("iron", "copper", "gold"))
    for prefix in ("", "deepslate_")
}


def read_chunk(regions, x, z):
    with (regions / f"r.{x // 32}.{z // 32}.mca").open("rb") as region:
        region.seek(4 * (x % 32 + 32 * (z % 32)))
        offset = int.from_bytes(region.read(4), "big") >> 8
        if offset < 2:
            raise ValueError(f"Missing saved chunk {(x, z)}")
        region.seek(offset * 4096)
        length = int.from_bytes(region.read(4), "big")
        if region.read(1) != b"\x02":
            raise ValueError(f"Expected default zlib region compression at {(x, z)}")
        chunk = nbtlib.File.parse(io.BytesIO(zlib.decompress(region.read(length - 1))))
        if (int(chunk["xPos"]), int(chunk["zPos"])) != (x, z):
            raise ValueError(f"Region index points to the wrong chunk at {(x, z)}")
        return chunk


def block_id(entry):
    if isinstance(entry, dict):
        # NBT mixed lists wrap a bare string in a compound under the empty key.
        return str(entry["id"] if "id" in entry else entry[""])
    return str(entry)


def count_ores(chunk):
    counts = [0, 0, 0]
    for section in chunk["sections"]:
        states = section.get("block_states")
        if states is None:
            continue
        palette = [block_id(entry) for entry in states["palette"]]
        ores = {i: ORE_TYPES[name] for i, name in enumerate(palette) if name in ORE_TYPES}
        if not ores:
            continue
        if len(palette) == 1:
            counts[ores[0]] += 4096
            continue
        bits = max(4, (len(palette) - 1).bit_length())
        slots = 64 // bits
        mask = (1 << bits) - 1
        remaining = 4096
        for packed in states["data"]:
            word = int(packed)
            used = min(remaining, slots)
            for _ in range(used):
                ore = ores.get(word & mask)
                if ore is not None:
                    counts[ore] += 1
                word >>= bits
            remaining -= used
        if remaining:
            raise ValueError("Incomplete block-state array")
    return tuple(counts)


def verify(server, world):
    regions = server / world / "dimensions/minecraft/overworld/region"
    ledger = server / "plugins/FeudalSurvey/survey.sqlite"
    with sqlite3.connect(ledger.resolve().as_uri() + "?mode=ro", uri=True) as db:
        db.execute("BEGIN")
        if db.execute("PRAGMA integrity_check").fetchone() != ("ok",):
            raise ValueError("SQLite integrity check failed")
        totals = [0, 0, 0]
        count = 0
        for x, z, iron, copper, gold in db.execute("SELECT x,z,iron,copper,gold FROM chunks ORDER BY x,z"):
            actual = count_ores(read_chunk(regions, x, z))
            if actual != (iron, copper, gold):
                raise ValueError(f"Chunk {(x, z)}: ledger {(iron, copper, gold)}, world {actual}")
            totals = [a + b for a, b in zip(totals, actual)]
            count += 1
        survey = db.execute("SELECT iron,copper,gold,inner_radius,cursor FROM survey").fetchone()
        if not survey or count == 0:
            raise ValueError("No measured chunks")
        if tuple(totals) != survey[:3] or count != 4 * survey[3] ** 2 + survey[4]:
            raise ValueError(f"Ledger totals or cursor disagree with {count} saved chunks")
        print(f"Verified {count} saved chunks. Iron {totals[0]}, copper {totals[1]}, gold {totals[2]}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path, help="Stopped test server directory")
    parser.add_argument("--world", default="world", help="World folder, default: world")
    args = parser.parse_args()
    verify(args.server, args.world)
