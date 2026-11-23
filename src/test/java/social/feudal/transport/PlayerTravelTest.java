package social.feudal.transport;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static social.feudal.transport.PlayerTravel.Ground.*;

class PlayerTravelTest {
    @Test void walkingHasAnInclusiveCapacityOnEachSurface(){
        assertTrue(FIELD.accepts(32));
        assertFalse(FIELD.accepts(33));
        assertTrue(ROAD.accepts(128));
        assertFalse(ROAD.accepts(129));
        assertFalse(BOAT.accepts(129));
        assertTrue(ROAD.speed>0 && FIELD.speed<0);
    }

    @Test void shapedPavingKeepsItsMaterialLoadAndSpeed(){
        assertEquals(RoadSurface.COBBLE,RoadSurface.of(Material.MOSSY_COBBLESTONE_STAIRS));
        assertEquals(RoadSurface.COBBLE,RoadSurface.of(Material.COBBLESTONE_SLAB));
        assertTrue(EARTH.accepts(64));assertFalse(EARTH.accepts(65));
        assertTrue(GRAVEL.accepts(96));assertFalse(GRAVEL.accepts(97));
        assertTrue(MASONRY.accepts(160));assertFalse(MASONRY.accepts(161));
        assertTrue(RoadSurface.EARTH.crewPace()<RoadSurface.MASONRY.crewPace());
    }

    @Test void deepWaterAllowsLimitedWadingAndRetreatFromAnyDistance(){
        assertTrue(PlayerTravel.canSwim(2.9,3));
        assertFalse(PlayerTravel.canSwim(3,3.01));
        assertTrue(PlayerTravel.canSwim(9,8.5));
        assertFalse(PlayerTravel.canSwim(9,9.1));
    }

    @Test void bulkyGoodsCountEvenWhenCompressedAndPersonalEquipmentIsExempt(){
        assertEquals(1,HandCargo.units(Material.COBBLESTONE,64));
        assertEquals(1,HandCargo.units(Material.WHEAT,64));
        assertEquals(9,HandCargo.units(Material.HAY_BLOCK,64));
        assertEquals(9,HandCargo.units(Material.IRON_BLOCK,64));
        assertEquals(0,HandCargo.units(Material.IRON_PICKAXE,1));
    }
}
