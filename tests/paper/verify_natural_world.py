"""Check the test server's seed policy and the generator in its actual Paper 26.3 save."""
import argparse
from pathlib import Path

import nbtlib


def verify(server: Path) -> int:
    properties = {}
    for line in (server / "server.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith(("#", "!")):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip().replace(r"\:", ":")
    if properties.get("level-seed", "") or properties.get("level-type", "minecraft:normal") != "minecraft:normal":
        raise ValueError("测试实例应使用普通世界生成器，level-seed 应留空")

    world = server / properties.get("level-name", "world")
    settings = nbtlib.load(world / "dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat")["data"]
    generator = settings["dimensions"]["minecraft:overworld"]["generator"]
    if generator["type"] != "minecraft:noise" or generator.get("settings") != "minecraft:overworld":
        raise ValueError(f"存档的主世界生成器不符合普通地形要求: {generator}")
    if generator["biome_source"].get("preset") != "minecraft:overworld":
        raise ValueError("存档没有使用普通主世界生物群系预设")
    return int(settings["seed"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("server", type=Path)
    print(f"普通主世界已核实，实际种子 {verify(parser.parse_args().server)}")
