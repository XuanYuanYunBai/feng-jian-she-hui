package social.feudal.survey;

import org.bukkit.ChunkSnapshot;
import static social.feudal.survey.Survey.Ores;

final class OreCounter {
    static Ores count(ChunkSnapshot snapshot, int bottom, int ceiling) {
        long iron = 0, copper = 0, gold = 0;
        for (int y = bottom; y < ceiling; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    switch (snapshot.getBlockType(x, y, z)) {
                        case IRON_ORE, DEEPSLATE_IRON_ORE -> iron++;
                        case COPPER_ORE, DEEPSLATE_COPPER_ORE -> copper++;
                        case GOLD_ORE, DEEPSLATE_GOLD_ORE -> gold++;
                        default -> { }
                    }
                }
            }
        }
        return new Ores(iron, copper, gold);
    }
}
