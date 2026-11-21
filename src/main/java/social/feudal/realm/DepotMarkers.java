package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

/** Display entities mark virtual stores without replacing blocks in existing worlds. */
final class DepotMarkers implements Listener {
    private final RealmService realm;
    private final NamespacedKey marker,crate;
    private List<RealmStore.Depot> depots=List.of();
    DepotMarkers(RealmService realm)throws SQLException{
        this.realm=realm;marker=new NamespacedKey(realm.plugin,"realm_depot");crate=new NamespacedKey(realm.plugin,"tax_crate");
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);refresh();
    }
    void refresh()throws SQLException{
        depots=realm.store.depots();
        for(var d:depots)if(realm.world.isChunkLoaded(d.x(),d.z()))
            mark(realm.world.getChunkAt(d.x(),d.z()),d);
    }
    @EventHandler public void load(EntitiesLoadEvent event){
        var chunk=event.getChunk();if(!chunk.getWorld().equals(realm.world))return;
        for(var d:depots)if(chunk.getX()==d.x() && chunk.getZ()==d.z())
            Bukkit.getScheduler().runTask(realm.plugin,()->{if(chunk.isLoaded())mark(chunk,d);});
    }
    private void mark(Chunk chunk,RealmStore.Depot d){
        var ids=new HashSet<String>();
        for(var e:chunk.getEntities())ids.add(e.getPersistentDataContainer().get(marker,PersistentDataType.STRING));
        if(ids.contains(d.id()) && ids.contains(d.id()+".label") && ids.contains(d.id()+".touch"))return;
        int x=d.x()*16+8,z=d.z()*16+8;
        int y=realm.world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1;
        var pos=new Location(realm.world,x,y,z);
        if(!ids.contains(d.id()))realm.world.spawn(pos,BlockDisplay.class,e->{
            e.setBlock(Material.BARREL.createBlockData());e.getPersistentDataContainer().set(marker,PersistentDataType.STRING,d.id());
        });
        if(!ids.contains(d.id()+".touch"))realm.world.spawn(pos.clone().add(.5,0,.5),Interaction.class,e->{
            e.setInteractionWidth(1.03f);e.setInteractionHeight(1.1f);
            e.getPersistentDataContainer().set(marker,PersistentDataType.STRING,d.id()+".touch");
        });
        if(!ids.contains(d.id()+".label"))realm.world.spawn(pos.clone().add(.5,1.35,.5),TextDisplay.class,e->{
            e.text(Component.text(d.name(),NamedTextColor.GOLD).append(Component.text("\n"+d.id()+"  ·  税仓",NamedTextColor.WHITE)));
            e.setBillboard(Display.Billboard.CENTER);e.setSeeThrough(false);
            e.getPersistentDataContainer().set(marker,PersistentDataType.STRING,d.id()+".label");
        });
    }
    @EventHandler public void openDepot(PlayerInteractEntityEvent e)throws SQLException{
        if(e.getHand()!=EquipmentSlot.HAND || !(e.getRightClicked() instanceof Interaction target))return;
        String id=target.getPersistentDataContainer().get(marker,PersistentDataType.STRING);
        if(id!=null && id.endsWith(".touch")){
            e.setCancelled(true);realm.books.accounts(e.getPlayer(),realm.taxes.ledger.period(),id.substring(0,id.length()-6));
        }
    }
    @EventHandler public void openMap(PlayerInteractEvent e)throws SQLException{
        if(e.getHand()==EquipmentSlot.HAND && e.getAction().isRightClick() && realm.isAtlas(e.getItem())){
            e.setCancelled(true);realm.books.directory(e.getPlayer());
        }
    }
    @EventHandler public void placing(BlockPlaceEvent e){
        var item=e.getItemInHand();
        if(item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(crate)){
            e.setCancelled(true);e.getPlayer().sendMessage("税箱已封缄，请在所属公库使用 /realm deposit");
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e){realm.clearPreview(e.getPlayer());}
}
