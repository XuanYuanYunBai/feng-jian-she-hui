package social.feudal.transport;

import java.util.*;
import social.feudal.npc.NpcSkins;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.*;
import org.bukkit.plugin.Plugin;

/** One resident worker per home. The home keeps the identities even while the team is unloaded. */
public final class TransportCrew implements Listener {
    public enum Upgrade {
        SPEED("步鞋", Map.of(Material.LEATHER_BOOTS,1), "皮革靴 ×1"),
        PACK("背架", Map.of(Material.LEATHER,4,Material.STICK,4), "皮革 ×4、木棍 ×4"),
        MULE("驮具", Map.of(Material.CHEST,1,Material.LEAD,1,Material.SADDLE,1), "箱子、拴绳、鞍各 ×1");
        public final String name,cost;
        final Map<Material,Integer> materials;
        Upgrade(String name,Map<Material,Integer> materials,String cost){this.name=name;this.materials=materials;this.cost=cost;}
        public static Upgrade parse(String name){
            for(var u:values())if(u.name().equalsIgnoreCase(name))return u;
            throw new IllegalArgumentException("装备可选 speed（步鞋）、pack（背架）或 mule（驮具）");
        }
    }
    public record Team(Mannequin porter,Mule mule,int capacity,double speed,FootNavigation navigation) {
        public LivingEntity loadBearer(){return mule==null?porter:mule;}
        public PorterCargo bags(){return new PorterCargo(porter,mule);}
        public boolean near(Location at,double squared){
            return porter.getLocation().distanceSquared(at)<=squared && loadBearer().getLocation().distanceSquared(at)<=squared;
        }
        public void move(Location goal){move(goal,goal);}
        public void move(Location goal,Location packGoal){
            if(mule!=null && porter.getLocation().distanceSquared(mule.getLocation())>16){
                if(porter.getLocation().distanceSquared(goal)<mule.getLocation().distanceSquared(packGoal)){
                    navigation.stop(porter);walkMule(porter.getLocation());
                }else{
                    mule.getPathfinder().stopPathfinding();mule.setAI(false);
                    navigation.toward(porter,mule.getLocation(),speed);
                }
                return;
            }
            navigation.toward(porter,goal,speed);
            if(mule!=null)walkMule(packGoal);
        }
        public void onRoad(Location goal,Location lane){
            navigation.onRoad(porter,goal,speed);
            if(mule!=null){
                if(!mule.hasAI())Bukkit.getMobGoals().removeAllGoals(mule);
                mule.setAI(true);mule.setCollidable(false);mule.getPathfinder().stopPathfinding();
                navigation.onRoad(mule,lane,speed);
            }
        }
        private void walkMule(Location goal){
            if(mule.getLocation().distanceSquared(goal)<.34){
                mule.getPathfinder().stopPathfinding();mule.setAI(false);return;
            }
            if(!mule.hasAI())Bukkit.getMobGoals().removeAllGoals(mule);
            mule.setAI(true);mule.getPathfinder().moveTo(goal,speed);
        }
        public void halt(){
            navigation.stop(porter);
            if(mule!=null){navigation.stop(mule);mule.getPathfinder().stopPathfinding();mule.setAI(false);}
        }
    }
    private final Plugin plugin;
    private final FootNavigation navigation;
    private final NamespacedKey worker, busy;
    public TransportCrew(Plugin plugin){
        this.plugin=plugin;navigation=new FootNavigation(plugin);worker=key("crew_member");busy=key("crew_busy");
        Bukkit.getPluginManager().registerEvents(this,plugin);
    }
    private NamespacedKey key(String name){return new NamespacedKey(plugin,name);}
    private NamespacedKey field(String id,String field){return key("crew/"+id+"/"+field);}
    private static void require(boolean b,String reason){if(!b)throw new IllegalArgumentException(reason);}
    public boolean busy(Team team){return team.porter().getPersistentDataContainer().has(busy);}
    public void active(Team team,boolean value){
        if(value)team.porter.getPersistentDataContainer().set(busy,PersistentDataType.BYTE,(byte)1);
        else team.porter.getPersistentDataContainer().remove(busy);
    }
    private <T extends Entity> T find(World w,String id,Class<T> type){
        if(id==null)return null;
        var entity=w.getEntity(UUID.fromString(id));return type.isInstance(entity)?type.cast(entity):null;
    }
    public boolean registered(Chunk home,String id){return home.getPersistentDataContainer().has(field(id,"porter"));}
    public Team team(Chunk home,String id){
        var data=home.getPersistentDataContainer();
        var roster=field(id,"porter");
        var resident=find(home.getWorld(),data.get(roster,PersistentDataType.STRING),LivingEntity.class);
        if(resident instanceof Villager old && id.equals(old.getPersistentDataContainer().get(worker,PersistentDataType.STRING))){
            // Convert only the recorded 0.4.0 worker at its actual location, carrying its original load.
            var upgraded=spawn(old.getLocation(),id);
            old.getPersistentDataContainer().copyTo(upgraded.getPersistentDataContainer(),true);
            upgraded.customName(old.customName());upgraded.getEquipment().setBoots(old.getEquipment().getBoots());
            if(id.startsWith("station/"))upgraded.getEquipment().setItemInMainHand(old.getInventory().getItem(0));
            else{
                var bag=new PorterCargo(upgraded,null);
                for(int i=0;i<old.getInventory().getSize();i++)if(old.getInventory().getItem(i)!=null)bag.setItem(i,old.getInventory().getItem(i));
            }
            data.set(roster,PersistentDataType.STRING,upgraded.getUniqueId().toString());
            old.getInventory().clear();old.remove();home.getWorld().save(true);resident=upgraded;
        }
        if(!(resident instanceof Mannequin v))return null;
        String animal=data.get(field(id,"mule"),PersistentDataType.STRING);
        Mule m=find(home.getWorld(),animal,Mule.class);
        if(animal!=null && m==null)return null;
        return new Team(v,m,m!=null?64:data.has(field(id,"pack"))?16:8,data.has(field(id,"speed"))?1.15:.72,navigation);
    }
    public void staff(Chunk home,String id,String label,Location yard){
        var data=home.getPersistentDataContainer();var roster=field(id,"porter");
        if(data.has(roster) || yard==null)return;
        var v=spawn(yard,id);v.customName(Component.text(label+" · 脚夫"));
        data.set(roster,PersistentDataType.STRING,v.getUniqueId().toString());
    }
    private Mannequin spawn(Location at,String id){
        return at.getWorld().spawn(at,Mannequin.class,e->{
            e.setImmovable(false);e.setCollidable(false);e.setInvulnerable(true);e.setPersistent(true);
            e.setDescription(null);e.setCustomNameVisible(true);e.getPersistentDataContainer().set(worker,PersistentDataType.STRING,id);
            NpcSkins.apply(e,id.startsWith("tax/")?"granary":"freight");
        });
    }
    public String status(Chunk home,String id){
        var data=home.getPersistentDataContainer();
        return (data.has(field(id,"mule"))?"驮队 · 64":data.has(field(id,"pack"))?"背架脚夫 · 16":"步行脚夫 · 8")
                +" 份／趟　｜　"+(data.has(field(id,"speed"))?"已配步鞋":"普通步速");
    }
    public void equip(Player p,Block source,String id,Upgrade u){
        require(p.getWorld()==source.getWorld() && p.getLocation().distanceSquared(source.getLocation().add(.5,.5,.5))<=36,"请到本地仓箱六格内装备");
        Chunk home=source.getChunk();var data=home.getPersistentDataContainer();
        var mark=field(id,u.name().toLowerCase(Locale.ROOT));require(!data.has(mark),"该队已经配备"+u.name);
        Team team=team(home,id);
        require(team!=null && !busy(team) && team.near(source.getLocation().add(.5,.5,.5),36),"请等待脚夫回仓后交付装备");
        Mule animal=null;
        if(u==Upgrade.MULE){
            require(data.has(field(id,"pack")),"先为脚夫配备背架");
            var target=p.getTargetEntity(6);
            require(target instanceof Mule,"请瞄准现场已有的成年驯骡");
            animal=(Mule)target;
            require(animal.isAdult() && p.getUniqueId().equals(animal.getOwnerUniqueId()),"请使用自己驯服的成年骡子");
            require(!animal.getPersistentDataContainer().has(worker) && animal.getPassengers().isEmpty()
                    && animal.getInventory().isEmpty(),"这匹骡子已经编队、载人或载货");
        }
        for(var cost:u.materials.entrySet())
            require(p.getInventory().containsAtLeast(new ItemStack(cost.getKey()),cost.getValue()),"缺少装备材料："+u.cost);
        if(animal!=null){
            require(animal.getLocation().distanceSquared(source.getLocation().add(.5,.5,.5))<=36,"请把骡子带到仓箱六格内");
        }
        for(var cost:u.materials.entrySet())p.getInventory().removeItem(new ItemStack(cost.getKey(),cost.getValue()));
        if(animal!=null){
            animal.setCollidable(false);animal.setCarryingChest(true);animal.setPersistenceRequired(true);animal.setInvulnerable(true);animal.setAI(false);
            animal.getInventory().setSaddle(new ItemStack(Material.SADDLE));
            animal.getPersistentDataContainer().set(worker,PersistentDataType.STRING,id);
            data.set(mark,PersistentDataType.STRING,animal.getUniqueId().toString());
        }else{
            data.set(mark,PersistentDataType.BYTE,(byte)1);
            if(u==Upgrade.SPEED)team.porter.getEquipment().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        }
        p.saveData();home.getWorld().save(true);
        p.sendMessage("已交付"+u.cost+"，配备"+u.name+"。"+status(home,id));
    }
    @EventHandler public void interact(PlayerInteractEntityEvent e){
        if(e.getRightClicked().getPersistentDataContainer().has(worker))e.setCancelled(true);
    }
    @EventHandler public void mount(EntityMountEvent e){if(e.getMount().getPersistentDataContainer().has(worker))e.setCancelled(true);}
}
