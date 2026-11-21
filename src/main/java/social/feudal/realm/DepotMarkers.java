package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;

final class DepotMarkers implements Listener {
    private final RealmService realm;
    private final NamespacedKey marker, crate, siteKey;
    private List<RealmStore.Depot> depots=List.of();
    DepotMarkers(RealmService realm)throws SQLException{
        this.realm=realm;marker=new NamespacedKey(realm.plugin,"realm_depot");crate=new NamespacedKey(realm.plugin,"tax_crate");
        siteKey=new NamespacedKey(realm.plugin,"granary_site");
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
    private Storehouse.Site existing(Chunk chunk){
        int[] xyz=chunk.getPersistentDataContainer().get(siteKey,PersistentDataType.INTEGER_ARRAY);
        return xyz==null?null:new Storehouse.Site(xyz[0],xyz[1],xyz[2]);
    }
    Location counter(int x,int z){
        for(var d:depots)if(d.x()==x && d.z()==z){
            Block desk=realm.fixtures.lookup(x,z,"desk",d.id());
            return desk==null?null:desk.getLocation();
        }
        return null;
    }
    private void mark(Chunk chunk,RealmStore.Depot d){
        var site=existing(chunk);
        if(site==null){
            if(realm.fixtures.custom(chunk,d.id()))return;
            site=Storehouse.find(chunk,d.id().equals("mill"));
            if(site==null)return; // Leave occupied sites and their old markers intact.
            Storehouse.build(realm.world,site,d);
            var desk=(Lectern)site.counter(realm.world).getBlock().getState();
            desk.getPersistentDataContainer().set(marker,PersistentDataType.STRING,d.id());
            desk.update();
            chunk.getPersistentDataContainer().set(siteKey,PersistentDataType.INTEGER_ARRAY,new int[]{site.x(),site.floor(),site.z()});
        }
        try{realm.fixtures.starter(chunk,site,d);}catch(SQLException ex){throw new IllegalStateException(ex);}
        for(var e:chunk.getEntities()){
            String id=e.getPersistentDataContainer().get(marker,PersistentDataType.STRING);
            if(d.id().equals(id) || (d.id()+".touch").equals(id) || (d.id()+".label").equals(id))e.remove();
        }
    }
    boolean built(RealmStore.Depot d){
        var chunk=realm.world.getChunkAt(d.x(),d.z());
        return existing(chunk)!=null || realm.fixtures.custom(chunk,d.id());
    }
    void complete(RealmStore.Depot d){
        mark(realm.world.getChunkAt(d.x(),d.z()),d);
        RealmStore.require(built(d),"此区块缺少合适的空地。清理七格见方的地面后，可使用 /realm build 补建");
    }
    @EventHandler public void oldDesk(PlayerInteractEntityEvent e)throws SQLException{
        var id=e.getRightClicked().getPersistentDataContainer().get(marker,PersistentDataType.STRING);
        if(e.getHand()==EquipmentSlot.HAND && id!=null && id.endsWith(".touch")){
            e.setCancelled(true);realm.dialogs.accounts(e.getPlayer(),realm.taxes.ledger.period(),id.substring(0,id.length()-6));
        }
    }
    @EventHandler public void interact(PlayerInteractEvent e)throws SQLException{
        if(e.getHand()!=EquipmentSlot.HAND || !e.getAction().isRightClick())return;
        Block block=e.getClickedBlock();
        if(block!=null && block.getType()==Material.LECTERN){
            if(block.getWorld().equals(realm.world))for(var d:depots)
                if(block.equals(realm.fixtures.lookup(block.getX()>>4,block.getZ()>>4,"desk",d.id()))){
                    e.setCancelled(true);realm.dialogs.accounts(e.getPlayer(),realm.taxes.ledger.period(),d.id());return;
                }
        }
        if(block!=null && block.getWorld().equals(realm.world) && block.getType()==Material.DARK_OAK_WALL_SIGN){
            var site=existing(block.getChunk());
            if(site!=null && block.getY()==site.floor()+3 && block.getZ()==site.z()+3){
                for(var d:depots)if(d.x()==(block.getX()>>4) && d.z()==(block.getZ()>>4)
                        && block.getX()==site.x()+(d.id().equals("mill")?2:0)){
                    e.setCancelled(true);realm.dialogs.accounts(e.getPlayer(),realm.taxes.ledger.period(),d.id());return;
                }
            }
        }
        if(realm.isAtlas(e.getItem())){
            e.setCancelled(true);realm.dialogs.directory(e.getPlayer(),1);
        }
    }
    @EventHandler public void placing(BlockPlaceEvent e){
        var item=e.getItemInHand();
        if(item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(crate)){
            e.setCancelled(true);e.getPlayer().sendMessage("税箱已封缄，请在公库账台办理交割");
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e){realm.clearPreview(e.getPlayer());}
}
