package social.feudal.survey;

import java.util.UUID;

final class Survey {
    static final int RULE_VERSION = 1;
    enum Phase { RUNNING, PAUSED, MEASURED, LIMIT }

    record Point(int x, int z) {}

    record MapIdentity(UUID id, long seed, String generator, int spawnX, int spawnY, int spawnZ) {
        boolean matches(MapIdentity other) {
            return id.equals(other.id) && seed == other.seed && generator.equals(other.generator);
        }
    }

    record Ores(long iron, long copper, long gold) {
        static final Ores ZERO = new Ores(0, 0, 0);
        Ores {
            if (iron < 0 || copper < 0 || gold < 0) throw new IllegalArgumentException("矿量不能为负");
        }
        Ores plus(Ores other) {
            return new Ores(Math.addExact(iron, other.iron), Math.addExact(copper, other.copper),
                    Math.addExact(gold, other.gold));
        }
        boolean covers(Ores need) {
            return iron >= need.iron && copper >= need.copper && gold >= need.gold;
        }
    }

    record Rules(int initial, int step, Ores minimum) {
        Rules {
            if (initial < 1 || step < 1 || minimum.iron < 1 || minimum.copper < 1 || minimum.gold < 1)
                throw new IllegalArgumentException("半径、扩圈步长与各项矿量门槛必须为正");
        }
        String key() {
            return RULE_VERSION + "/" + initial + "/" + step + "/" + minimum.iron + "/" + minimum.copper + "/" + minimum.gold;
        }
    }

    // Half-open square [-outer, outer), enumerated in rows with the old square omitted.
    record Ring(int inner, int outer) {
        Ring {
            if (inner < 0 || outer <= inner || outer > 1_875_000)
                throw new IllegalArgumentException("勘测半径超出范围");
        }
        long size() {
            return 4L * ((long) outer * outer - (long) inner * inner);
        }
        Point at(int centerX, int centerZ, long index) {
            if (index < 0 || index >= size()) throw new IndexOutOfBoundsException();
            int width = outer * 2, band = outer - inner;
            long top = (long) width * band, sides = 4L * inner * band;
            int x, z;
            if (index < top) {
                x = (int) (index % width) - outer;
                z = (int) (index / width) - outer;
            } else if (index < top + sides) {
                long side = index - top;
                int column = (int) (side % (2 * band));
                x = column - outer + (column >= band ? 2 * inner : 0);
                z = (int) (side / (2 * band)) - inner;
            } else {
                long bottom = index - top - sides;
                x = (int) (bottom % width) - outer;
                z = (int) (bottom / width) + inner;
            }
            return new Point(centerX + x, centerZ + z);
        }
    }

    record Progress(MapIdentity map, Ring ring, long cursor, Ores total, Phase phase) {
        Point next() {
            return ring.at(Math.floorDiv(map.spawnX, 16), Math.floorDiv(map.spawnZ, 16), cursor);
        }
        Progress phase(Phase target) {
            return new Progress(map, ring, cursor, total, target);
        }
        Progress expand(Rules rules, int limit) {
            long outer = (long) ring.outer + rules.step;
            if (outer > limit) return phase(Phase.LIMIT);
            return new Progress(map, new Ring(ring.outer, (int) outer), 0, total, Phase.RUNNING);
        }
        Progress measure(Ores sample, Rules rules, int limit) {
            Ores sum = total.plus(sample);
            Progress result = new Progress(map, ring, cursor + 1, sum, Phase.RUNNING);
            if (result.cursor < ring.size()) return result;
            return sum.covers(rules.minimum) ? result.phase(Phase.MEASURED) : result.expand(rules, limit);
        }
        String report(Rules rules) {
            String state = switch (phase) {
                case PAUSED -> "已暂停";
                case LIMIT -> "已达扩圈预算，仍有缺项";
                case MEASURED -> "本阶段三项矿量达标";
                case RUNNING -> "勘测中";
            };
            Ores need = rules.minimum;
            return "%s | 出生点 %d,%d,%d | 半径 %d 区块 | 本圈 %d/%d | 实测/需要：铁 %d/%d，铜 %d/%d，金 %d/%d"
                    .formatted(state, map.spawnX, map.spawnY, map.spawnZ, ring.outer, cursor, ring.size(),
                            total.iron, need.iron, total.copper, need.copper, total.gold, need.gold);
        }
    }
}
