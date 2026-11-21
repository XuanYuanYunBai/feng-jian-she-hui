package social.feudal.transport;

import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Mule;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/** Human cargo lives inside the barrel equipped in the mannequin's hand. Mules use their vanilla chest slots. */
public record PorterCargo(Mannequin body,Mule mule){
    public int getSize(){return mule!=null?mule.getInventory().getSize():8;}
    public ItemStack getItem(int slot){
        if(mule!=null)return mule.getInventory().getItem(slot);
        var hand=body.getEquipment().getItemInMainHand();
        if(hand.getType()!=Material.BARREL)return null;
        return ((Barrel)((BlockStateMeta)hand.getItemMeta()).getBlockState()).getSnapshotInventory().getItem(slot);
    }
    public void setItem(int slot,ItemStack item){
        if(mule!=null){mule.getInventory().setItem(slot,item);return;}
        var pack=body.getEquipment().getItemInMainHand();
        if(pack.getType()!=Material.BARREL)pack=new ItemStack(Material.BARREL);
        var meta=(BlockStateMeta)pack.getItemMeta();var barrel=(Barrel)meta.getBlockState();
        var inv=barrel.getSnapshotInventory();inv.setItem(slot,item);
        if(inv.isEmpty()){body.getEquipment().setItemInMainHand(null);return;}
        meta.setBlockState(barrel);pack.setItemMeta(meta);body.getEquipment().setItemInMainHand(pack);
    }
}
