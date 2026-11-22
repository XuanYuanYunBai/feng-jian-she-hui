package social.feudal.realm;

import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.Material;
import org.bukkit.entity.Piglin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/** Apply to every dimension: trade is between player-owned tills and other players. */
final class NonPlayerTrade implements Listener {
    @EventHandler(ignoreCancelled=true) public void merchant(InventoryOpenEvent event){
        if(event.getInventory().getType()==InventoryType.MERCHANT){
            event.setCancelled(true);
            event.getPlayer().sendMessage("买卖请到玩家商站，使用 /realm prices 查看行情");
        }
    }
    @EventHandler(ignoreCancelled=true) public void purchase(PlayerPurchaseEvent event){event.setCancelled(true);}

    private boolean barterItem(Piglin piglin,Material material){
        return material==Material.GOLD_INGOT || piglin.getBarterList().contains(material);
    }
    @EventHandler(ignoreCancelled=true) public void offer(PlayerInteractEntityEvent event){
        if(event.getRightClicked() instanceof Piglin piglin &&
                barterItem(piglin,event.getPlayer().getInventory().getItem(event.getHand()).getType())){
            event.setCancelled(true);event.getPlayer().sendMessage("金锭可留作铸币，交易请到玩家商站");
        }
    }
    @EventHandler(ignoreCancelled=true) public void pickup(EntityPickupItemEvent event){
        if(event.getEntity() instanceof Piglin piglin && barterItem(piglin,event.getItem().getItemStack().getType()))
            event.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void barter(PiglinBarterEvent event){event.setCancelled(true);}
}
