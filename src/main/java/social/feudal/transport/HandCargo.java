package social.feudal.transport;

import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

/** Count the same commodities in the hand, a sealed tax barrel, a bundle or a shulker. */
final class HandCargo {
    static final int FIELD=32, ROAD=128;

    static long count(Player player){
        long total=weight(player.getItemOnCursor(),0);
        for(var stack:player.getInventory().getContents())total+=weight(stack,0);
        return total;
    }

    private static long weight(ItemStack stack,int depth){
        if(stack==null || stack.isEmpty())return 0;
        long unit=units(stack.getType(),stack.getMaxStackSize());
        if(stack.hasItemMeta()){
            var meta=stack.getItemMeta();
            if(meta instanceof BundleMeta bundle){
                if(depth>=16)return ROAD+1;
                for(var packed:bundle.getItems())unit+=weight(packed,depth+1);
            }else if(meta instanceof BlockStateMeta block && block.hasBlockState() && block.getBlockState() instanceof Container bin){
                if(depth>=16)return ROAD+1;
                for(var packed:bin.getSnapshotInventory().getContents())unit+=weight(packed,depth+1);
            }
        }
        return Math.min(Integer.MAX_VALUE,unit*stack.getAmount());
    }

    static int units(Material material,int stackSize){
        return switch(material){
            case HAY_BLOCK,DRIED_KELP_BLOCK,COAL_BLOCK,IRON_BLOCK,COPPER_BLOCK,GOLD_BLOCK,
                    RAW_IRON_BLOCK,RAW_COPPER_BLOCK,RAW_GOLD_BLOCK,DIAMOND_BLOCK,EMERALD_BLOCK,
                    LAPIS_BLOCK,REDSTONE_BLOCK,NETHERITE_BLOCK,BONE_BLOCK,SLIME_BLOCK -> 9;
            default -> stackSize>1 ? 1 : 0;
        };
    }
}
