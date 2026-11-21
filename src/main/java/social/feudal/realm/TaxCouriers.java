package social.feudal.realm;

import java.sql.SQLException;
import org.bukkit.entity.Player;
import social.feudal.transport.TransportCrew;
import social.feudal.transport.TransportCrew.Team;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import static social.feudal.realm.RealmStore.require;

final class TaxCouriers implements Listener {
    private final TaxOffice office;
    private final RealmService realm;
    private final NamespacedKey station;
    TaxCouriers(TaxOffice office){
        this.office=office;realm=office.realm;station=new NamespacedKey(realm.plugin,"tax_home");
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }
    // Look for an accessible loading yard. Prefer daylight over the inside of a granary.
    private Location yard(Container bin,boolean animal){
        var w=realm.world;int bx=bin.getX(),by=bin.getY(),bz=bin.getZ();double best=Double.MAX_VALUE;Location result=null;
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++)for(int dy=-2;dy<=2;dy++){
            int x=bx+dx,y=by+dy,z=bz+dz;int width=animal?2:1;Location at=new Location(w,x+width*.5,y,z+width*.5);
            double distance=at.distanceSquared(bin.getLocation().add(.5,.5,.5));if(distance>22)continue;
            if(!w.isChunkLoaded(x>>4,z>>4) || !w.isChunkLoaded((x+1)>>4,(z+1)>>4))continue;
            boolean clear=true;
            for(int i=0;i<width;i++)for(int j=0;j<width;j++){
                var feet=w.getBlockAt(x+i,y,z+j);
                if(!feet.isPassable() || feet.isLiquid() || !feet.getRelative(0,1,0).isPassable()
                        || !feet.getRelative(0,-1,0).getType().isSolid())clear=false;
            }
            if(!clear)continue;
            double cost=distance+(w.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES)>=y?30:0);
            if(cost<best){best=cost;result=at;}
        }
        return result;
    }
    static String crewId(RealmStore.Depot d){return "tax/"+d.id();}
    private void travel(Team team,Location goal){team.move(goal);}
    void tick(RealmStore.Depot d,Container stock)throws SQLException{
        var chunk=stock.getChunk();String crewId=crewId(d);
        Team team=realm.crews.team(chunk,crewId);
        if(team==null){
            if(realm.crews.registered(chunk,crewId))return;
            realm.crews.staff(chunk,crewId,d.name(),yard(stock,false));
            team=realm.crews.team(chunk,crewId);if(team==null)return;
            team.porter().getPersistentDataContainer().set(station,PersistentDataType.STRING,d.id());
        }
        var bags=team.bags();
        for(int i=0;i<bags.getSize();i++){
            var box=bags.getItem(i);String id=office.id(box);if(id==null)continue;
            var c=office.ledger.crate(id);
            if(c.received()){bags.setItem(i,null);continue;}
            var bin=office.vault(realm.store.title(c.title()));if(bin==null){team.halt();return;}
            if(!team.near(bin.getLocation().add(.5,.5,.5),23)){
                var goal=yard(bin,team.mule()!=null);if(goal!=null)travel(team,goal);
                return;
            }
            try{office.deliver(box,bin);bags.setItem(i,null);}
            catch(IllegalArgumentException e){team.halt();return;}
        }
        if(!team.near(stock.getLocation().add(.5,.5,.5),23)){
            var home=yard(stock,team.mule()!=null);if(home!=null)travel(team,home);return;
        }
        team.halt();realm.crews.active(team,false);
        var inv=WarehouseFixtures.inventory(stock);int carried=0;
        for(int slot=0;slot<inv.getSize();slot++){
            var box=inv.getItem(slot);String id=office.id(box);if(id==null)continue;
            if(realm.store.number("SELECT count(*) FROM parcel JOIN crate USING(id) WHERE id=? AND depot=? AND automatic=1 AND received=0",id,d.id())==0)continue;
            var c=office.ledger.crate(id);var t=realm.store.title(c.title());if(office.vault(t)==null)continue;
            if(c.amount()+carried>team.capacity())continue;
            int free=-1;
            for(int j=team.mule()==null?0:2;j<bags.getSize();j++)if(bags.getItem(j)==null){free=j;break;}
            if(free<0)break;
            bags.setItem(free,box);inv.setItem(slot,null);carried+=c.amount();
            realm.store.update("UPDATE crate SET carrier=?,title=? WHERE id=?",team.porter().getUniqueId().toString(),t.id(),id);
        }
        if(carried>0){
            realm.crews.active(team,true);
            team.porter().customName(Component.text(d.name()+"脚夫 · 承运 "+carried+" 份小麦"));
            realm.world.save(true);
        }else team.porter().customName(Component.text(d.name()+" · 脚夫"));
    }
    void equip(Player p,RealmStore.Depot d,String kind)throws SQLException{
        require(realm.store.title(realm.store.owner(d.x(),d.z())).seal().equals(p.getUniqueId().toString()),"请由本地持印人置办装备");
        var bin=realm.fixtures.bin(d.x(),d.z(),"stock",d.id());require(bin!=null,"请先登记产地仓箱");
        realm.crews.equip(p,bin.getBlock(),crewId(d),TransportCrew.Upgrade.parse(kind));
    }
    @EventHandler public void interact(PlayerInteractEntityEvent e)throws SQLException{
        String home=e.getRightClicked().getPersistentDataContainer().get(station,PersistentDataType.STRING);
        if(home==null || e.getHand()!=EquipmentSlot.HAND)return;
        var p=e.getPlayer();e.setCancelled(true);var d=office.ledger.depot(home);
        if(!p.isSneaking()){realm.dialogs.crew(p,d);return;}
        if(!realm.world.isChunkLoaded(d.x(),d.z())){p.sendMessage("产地未加载");return;}
        Team team=realm.crews.team(realm.world.getChunkAt(d.x(),d.z()),crewId(d));
        if(team==null || !team.near(p.getLocation(),36))return;
        var bags=team.bags();
        for(int i=0;i<bags.getSize();i++){
            var item=bags.getItem(i);String id=office.id(item);if(id==null)continue;
            var c=office.ledger.crate(id);
            if(c.received() || !realm.store.title(c.title()).seal().equals(p.getUniqueId().toString()))continue;
            if(p.getInventory().firstEmpty()<0){p.sendMessage("请腾出背包格");return;}
            realm.store.update("UPDATE crate SET carrier=? WHERE id=?",p.getUniqueId().toString(),id);
            p.getInventory().addItem(item);bags.setItem(i,null);p.sendMessage("已接运 "+c.amount()+" 份小麦，请随身运至公库。");
        }
    }
}
