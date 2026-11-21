package social.feudal.survey;

import java.lang.reflect.Proxy;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OreCounterTest {
    @Test void countsOreVariantsAcrossNegativeHeightsAndSkipsStorageBlocks() {
        Material[] floor = { Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE, Material.GOLD_ORE,
                Material.DEEPSLATE_GOLD_ORE, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
                Material.RAW_IRON_BLOCK, Material.GOLD_BLOCK, Material.COPPER_BLOCK };
        var snapshot = (ChunkSnapshot) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ChunkSnapshot.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getBlockType")) throw new UnsupportedOperationException(method.getName());
                    int x = (int) args[0], y = (int) args[1], z = (int) args[2];
                    assertTrue(x >= 0 && x < 16 && z >= 0 && z < 16 && y >= -2 && y < 2);
                    if (z == 15 && y == -2 && x < floor.length) return floor[x];
                    if (x == 15 && z == 15 && y == 1) return Material.IRON_ORE;
                    return Material.AIR;
                });
        assertEquals(new Survey.Ores(3, 2, 2), OreCounter.count(snapshot, -2, 2));
    }
}
