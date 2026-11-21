package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import social.feudal.npc.NpcSkins;

final class SeatEnvoys implements Listener {
    private final RealmService realm;
    private final NamespacedKey seat;
    SeatEnvoys(RealmService realm){
        this.realm=realm;seat=new NamespacedKey(realm.plugin,"envoy_seat");
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::refresh,10,100);
    }
    private void refresh(){
        try{
            for(var title:realm.store.titles()){
                if(!realm.world.isChunkLoaded(title.x(),title.z()))continue;
                var chunk=realm.world.getChunkAt(title.x(),title.z());
                if(!chunk.isEntitiesLoaded())continue;
                Mannequin envoy=null;
                for(var e:chunk.getEntities())if(e instanceof Mannequin m && title.id().equals(m.getPersistentDataContainer().get(seat,PersistentDataType.STRING))){
                    if(envoy==null)envoy=m;else m.remove();
                }
                if(Succession.isPlayer(title.seal()) || Succession.VACANT.equals(title.seal())){
                    if(envoy!=null)envoy.remove();continue;
                }
                boolean holy=title.rank().equals("修院") || title.rank().equals("主教");
                if(envoy==null){
                    var at=office(title);if(at==null)continue;
                    envoy=realm.world.spawn(at,Mannequin.class,e->{
                        e.setImmovable(true);e.setInvulnerable(true);e.setPersistent(true);e.setCollidable(false);
                        e.setDescription(null);e.setCustomNameVisible(true);
                        e.getPersistentDataContainer().set(seat,PersistentDataType.STRING,title.id());
                        NpcSkins.apply(e,holy?"chaplain":"steward");
                    });
                }
                var name=Component.text(realm.display(title.seal()).replaceFirst("^npc-","")+(holy?" · 驻地教士":" · 驻地执事"));
                if(!name.equals(envoy.customName()))envoy.customName(name);
            }
        }catch(SQLException e){realm.plugin.getLogger().log(Level.WARNING,"驻地署理人登记失败",e);}
    }
    private Location office(RealmStore.Title t){
        Location desk=realm.markers.counter(t.x(),t.z());
        int x=desk==null?t.x()*16+8:desk.getBlockX(),z=desk==null?t.z()*16+8:desk.getBlockZ();
        for(int[] offset:new int[][]{{1,3},{-1,3},{0,3},{0,-1},{-1,-1},{1,0},{-1,0}}){
            int bx=x+offset[0],bz=z+offset[1];
            if((bx>>4)!=t.x() || (bz>>4)!=t.z())continue;
            int floor=desk==null?realm.world.getHighestBlockYAt(bx,bz,HeightMap.MOTION_BLOCKING_NO_LEAVES)+1:desk.getBlockY();
            for(int dy:new int[]{0,-1,1}){
                var block=realm.world.getBlockAt(bx,floor+dy,bz);
                var ground=block.getRelative(0,-1,0);
                if(block.isEmpty() && block.getRelative(0,1,0).isEmpty() && ground.getType().isSolid()
                        && !(ground.getState(false) instanceof org.bukkit.block.TileState)
                        && !Tag.FENCES.isTagged(ground.getType()) && ground.getType()!=Material.LANTERN)
                    return block.getLocation().add(.5,0,.5);
            }
        }
        return null;
    }
    @EventHandler public void interact(PlayerInteractEntityEvent event)throws SQLException{
        if(event.getHand()!=EquipmentSlot.HAND)return;
        var e=event.getRightClicked();String id=e.getPersistentDataContainer().get(seat,PersistentDataType.STRING);
        if(id==null)return;
        event.setCancelled(true);var p=event.getPlayer();var t=realm.store.title(id);
        if(!Succession.isPlayer(t.seal()))realm.dialogs.brief(p,t);
    }
}
