package social.feudal.realm;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductionToolsTest {
    @Test void plainToolsHaveFiniteWorkingLivesAcrossTiers() {
        assertEquals(4, ProductionTools.wear(Material.WOODEN_AXE));
        assertEquals(8, ProductionTools.wear(Material.STONE_SHOVEL));
        assertEquals(8, ProductionTools.wear(Material.COPPER_PICKAXE));
        assertEquals(10, ProductionTools.wear(Material.IRON_PICKAXE));
        assertEquals(10, ProductionTools.wear(Material.IRON_HOE));
        assertEquals(2, ProductionTools.wear(Material.GOLDEN_AXE));
        assertEquals(32, ProductionTools.wear(Material.DIAMOND_PICKAXE));
        assertEquals(32, ProductionTools.wear(Material.NETHERITE_PICKAXE));
    }

    @Test void preservesVanillaDamageEventsAndUnrelatedEquipment() {
        assertEquals(0, ProductionTools.damage(0, 10));
        assertEquals(20, ProductionTools.damage(2, 10));
        assertEquals(Integer.MAX_VALUE, ProductionTools.damage(Integer.MAX_VALUE, 32));
        assertEquals(1, ProductionTools.wear(Material.IRON_SWORD));
        assertEquals(1, ProductionTools.wear(Material.FISHING_ROD));
        assertEquals(1, ProductionTools.wear(Material.IRON_CHESTPLATE));
    }
}
