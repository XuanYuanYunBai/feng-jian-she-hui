package social.feudal.transport;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import net.kyori.adventure.text.Component;
import social.feudal.transport.HaulNetwork.Point;
import static social.feudal.ui.Menus.*;

/** Player hauling affects movement, never deletes or rewrites the cargo. */
public final class PlayerTravel implements Listener {
    enum Ground {
        ROAD("铺装道路",HandCargo.ROAD,.20), FIELD("野地",HandCargo.FIELD,-.20),
        RIVER("深水",HandCargo.FIELD,-.45), BOAT("乘船",HandCargo.ROAD,0);
        final String label;
        final int limit;
        final double speed;
        Ground(String label,int limit,double speed){this.label=label;this.limit=limit;this.speed=speed;}
        boolean accepts(long load){return load<=limit;}
    }

    private final JavaPlugin plugin;
    private final HaulNetwork roads;
    private final World world;
    private final NamespacedKey speedKey,bankKey,freightBoatKey;
    private final Map<UUID,Long> loads=new HashMap<>();
    private final Map<UUID,Integer> messages=new HashMap<>();

    public PlayerTravel(JavaPlugin plugin,World world,HaulNetwork roads){
        this.world=world;this.roads=roads;this.plugin=plugin;
        speedKey=new NamespacedKey(plugin,"travel_speed");bankKey=new NamespacedKey(plugin,"river_bank");
        freightBoatKey=new NamespacedKey(plugin,"consignment");
        Bukkit.getPluginManager().registerEvents(this,plugin);
        Bukkit.getScheduler().runTaskTimer(plugin,()->{
            loads.clear();
            for(var p:world.getPlayers()){
                if(applies(p))updateSpeed(p,ground(p,p.getLocation()));
                else clearSpeed(p);
            }
        },1,10);
    }

    private boolean applies(Player p){
        return p.getWorld().equals(world) && !p.isDead() &&
                (p.getGameMode()==GameMode.SURVIVAL || p.getGameMode()==GameMode.ADVENTURE);
    }

    private static boolean water(Block b){
        if(b.getType().isAir() || !b.isPassable())return false;
        var fluid=b.getWorld().getFluidData(b.getX(),b.getY(),b.getZ()).getFluidType();
        return fluid==Fluid.WATER || fluid==Fluid.FLOWING_WATER;
    }

    private boolean deep(Location location){
        var b=location.getBlock();
        // Include the surface and a swimmer breaking the surface, but stop at a solid bridge deck.
        for(int i=0;i<3;i++,b=b.getRelative(0,-1,0)){
            if(water(b))return water(b.getRelative(0,-1,0)) || water(b.getRelative(0,1,0));
            if(!b.isPassable())return false;
        }
        return false;
    }

    private boolean pier(Point p){
        if(!roads.loaded(p))return false;
        var material=roads.block(p).getType();
        if(material!=Material.BARREL && !Tag.PLANKS.isTagged(material))return false;
        for(var dir:HaulNetwork.DIR)for(int along=0;along<=2;along++){
            var station=p.offset(-along*dir[0],0,-along*dir[1]);
            if(roads.port(station,station.offset(3*dir[0],-1,3*dir[1])))return true;
        }
        return false;
    }

    private boolean onRoad(Location location){
        // A dirt path sits 1/16 below the top of its block. Follow the surface during an ordinary jump.
        int feet=(int)Math.floor(location.getY()+.08);
        for(int d=1;d<=3;d++){
            var p=new Point(location.getBlockX(),feet-d,location.getBlockZ());
            if(!roads.loaded(p))return false;
            if(roads.road(p) || pier(p))return true;
            var block=roads.block(p);
            if(!block.isPassable() || water(block))return false;
        }
        return false;
    }
    private Ground ground(Player p,Location at){
        if(p.getVehicle() instanceof Boat)return Ground.BOAT;
        if(deep(at))return Ground.RIVER;
        return onRoad(at)?Ground.ROAD:Ground.FIELD;
    }
    private long load(Player p){return loads.computeIfAbsent(p.getUniqueId(),unused->HandCargo.count(p));}
    private void updateSpeed(Player p,Ground ground){
        var attr=p.getAttribute(Attribute.MOVEMENT_SPEED);
        if(attr==null)return;
        var previous=attr.getModifier(speedKey);
        if(previous!=null && previous.getAmount()==ground.speed)return;
        if(previous!=null){
            attr.removeModifier(speedKey);
            if(ground==Ground.ROAD)explain(p,"踏上铺装道路 · 步速 +20% · 运力 "+ground.limit+" 份");
            if(ground==Ground.FIELD)explain(p,"野外步行 · 步速 −20% · 运力 "+ground.limit+" 份");
        }
        if(ground.speed!=0)attr.addTransientModifier(new AttributeModifier(speedKey,ground.speed,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1,EquipmentSlotGroup.ANY));
    }
    private void clearSpeed(Player p){
        var attr=p.getAttribute(Attribute.MOVEMENT_SPEED);
        if(attr!=null && attr.getModifier(speedKey)!=null)attr.removeModifier(speedKey);
    }
    private void explain(Player p,String message){
        int now=Bukkit.getCurrentTick(),last=messages.getOrDefault(p.getUniqueId(),now-40);
        if(now-last>=30){p.sendActionBar(Component.text(message));messages.put(p.getUniqueId(),now);}
    }
    private static double distance(Location point,int[] bank){
        return Math.hypot(point.getX()-(bank[0]+.5),point.getZ()-(bank[1]+.5));
    }
    static boolean canSwim(double before,double after){
        return after<=3 || after<=before+.000001;
    }

    /** A sailor who fell or logged in midstream may move toward an actual shore. */
    private int[] closestBank(Location from){
        int x=from.getBlockX(),y=from.getBlockY(),z=from.getBlockZ();
        int[] nearest=new int[0];
        int best=Integer.MAX_VALUE;
        for(int radius=1;radius<=24 && radius*radius<best;radius++){
            for(int offset=-radius;offset<=radius;offset++){
                for(var dir:HaulNetwork.DIR){
                    int dx=dir[0]*radius-dir[1]*offset,dz=dir[1]*radius+dir[0]*offset;
                    int squared=dx*dx+dz*dz;
                    if(squared>=best || !world.isChunkLoaded((x+dx)>>4,(z+dz)>>4))continue;
                    for(int dy=-1;dy<=2;dy++){
                        var floor=world.getBlockAt(x+dx,y+dy-1,z+dz);
                        if(floor.getType().isSolid() && floor.getRelative(0,1,0).getType().isAir() &&
                                floor.getRelative(0,2,0).getType().isAir()){
                            best=squared;nearest=new int[]{x+dx,z+dz};break;
                        }
                    }
                }
            }
        }
        return nearest;
    }

    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void move(PlayerMoveEvent event){
        Player p=event.getPlayer();Location from=event.getFrom(),to=event.getTo();
        if(event instanceof PlayerTeleportEvent || !applies(p) || p.isInsideVehicle() || to==null ||
                from.getX()==to.getX() && from.getZ()==to.getZ())return;
        Ground dest=ground(p,to);
        var data=p.getPersistentDataContainer();
        int[] bank=data.get(bankKey,PersistentDataType.INTEGER_ARRAY);
        if(dest==Ground.RIVER){
            if(bank==null){
                bank=deep(from)?closestBank(from):new int[]{from.getBlockX(),from.getBlockZ()};
                data.set(bankKey,PersistentDataType.INTEGER_ARRAY,bank);
            }
            if(bank.length==2 && !canSwim(distance(from,bank),distance(to,bank))){
                blockStep(event);explain(p,"已到涉水极限，请回到入水岸，走桥或在渡口乘船");return;
            }
            // Recovery takes precedence over encumbrance: someone who fell in can always get back to shore.
            explain(p,"深水难渡，可退回岸边寻找桥梁或渡口");
        }else{
            boolean landing=bank!=null && deep(from);
            if(!landing && !dest.accepts(load(p))){
                blockStep(event);explain(p,dest.label+"最多可运 "+dest.limit+" 份，现有 "+load(p)+" 份，请就地卸货");return;
            }
            if(bank!=null && !to.getBlock().getRelative(0,-1,0).isPassable())data.remove(bankKey);
        }
        updateSpeed(p,dest);
    }
    private static void blockStep(PlayerMoveEvent event){
        var corrected=event.getTo().clone();
        corrected.setX(event.getFrom().getX());corrected.setZ(event.getFrom().getZ());
        event.setTo(corrected);
    }

    /** Both boat and passenger must be alongside the same physically intact quay. */
    private Point landing(Player p,Boat boat){
        Location hull=boat.getLocation(),person=p.getLocation();
        if(person.distanceSquared(hull)>25)return null;
        Point origin=Point.of(hull.getBlock());
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=-2;dy<=0;dy++){
            var berth=origin.offset(dx,dy,dz);
            if(!roads.water(berth))continue;
            var surface=berth.feet(world);
            if(Math.hypot(surface.getX()-hull.getX(),surface.getZ()-hull.getZ())>1.6 ||
                    Math.abs(surface.getY()-hull.getY())>1.25)continue;
            for(var dir:HaulNetwork.DIR){
                var store=berth.offset(-3*dir[0],1,-3*dir[1]);
                if(roads.port(store,berth) && person.distanceSquared(surface)<=20.25)
                    return store.offset(2*dir[0],0,2*dir[1]);
            }
        }
        return null;
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void board(EntityMountEvent e){
        if(!(e.getEntity() instanceof Player p) || !(e.getMount() instanceof Boat boat) || !applies(p))return;
        if(boat.getPersistentDataContainer().has(freightBoatKey)){
            e.setCancelled(true);explain(p,"此船已配给货运船工，请另备渡船");
        }else if(landing(p,boat)==null || !Ground.BOAT.accepts(load(p))){
            e.setCancelled(true);explain(p,"请在完整渡口登船，随身货物上限 "+HandCargo.ROAD+" 份");
        }else p.getPersistentDataContainer().remove(bankKey);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void leave(EntityDismountEvent e){
        if(!(e.getEntity() instanceof Player p) || !(e.getDismounted() instanceof Boat boat) || !applies(p) || !e.isCancellable())return;
        Point pier=landing(p,boat);
        if(pier==null){
            e.setCancelled(true);explain(p,"请把船靠到完整栈桥的泊位，再下船");return;
        }
        // Vanilla can choose the water on the far side of the hull. Step onto the actual dock.
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!p.isOnline() || !applies(p) || p.isInsideVehicle() || !boat.isValid() ||
                    !boat.getWorld().equals(world) || !pier.equals(landing(p,boat)))return;
            var target=pier.feet(world);target.setDirection(p.getLocation().getDirection());
            p.teleport(target);
        });
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void breakBoat(VehicleDestroyEvent e){
        if(e.getVehicle() instanceof Boat boat && e.getAttacker()!=null)
            for(var rider:boat.getPassengers())if(rider instanceof Player p && applies(p) && landing(p,boat)==null){
                e.setCancelled(true);explain(p,"载人船请先靠泊再拆");return;
            }
    }
    @EventHandler public void inventory(PlayerInventorySlotChangeEvent e){loads.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void click(InventoryClickEvent e){loads.remove(e.getWhoClicked().getUniqueId());}
    @EventHandler public void drag(InventoryDragEvent e){loads.remove(e.getWhoClicked().getUniqueId());}
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void teleport(PlayerTeleportEvent e){
        // A short correction from Paper when movement was denied must preserve the entry bank.
        var to=e.getTo();
        if(to!=null && (!e.getFrom().getWorld().equals(to.getWorld()) || e.getFrom().distanceSquared(to)>9))
            e.getPlayer().getPersistentDataContainer().remove(bankKey);
    }
    @EventHandler public void death(PlayerDeathEvent e){e.getEntity().getPersistentDataContainer().remove(bankKey);}
    @EventHandler public void worldChange(PlayerChangedWorldEvent e){clearSpeed(e.getPlayer());}
    @EventHandler public void quit(PlayerQuitEvent e){
        clearSpeed(e.getPlayer());loads.remove(e.getPlayer().getUniqueId());messages.remove(e.getPlayer().getUniqueId());
    }
    public void close(){for(var p:world.getPlayers())clearSpeed(p);loads.clear();messages.clear();}

    public void report(Player p){
        var ground=ground(p,p.getLocation());long cargo=HandCargo.count(p);
        show(p,"行旅与货运",List.of(text(ground.label+"　｜　随身货物 "+cargo+" / "+ground.limit+" 份"),
                text("铺装道路与完整栈桥　128 份，步速 +20%\n野外步行　32 份，步速 −20%"),
                text("可堆叠物品每件一份。干草块、金属块等按九份计。工具和盔甲可随身携带，收纳袋与箱中的货物照计。"),
                text("超载可就地卸货。深水只宜靠岸涉行；远渡请走桥，或到有港仓、木栈桥和系缆桩的泊位乘船。")),
                List.of(),List.of(command("返回纹章院","realm atlas")),1);
    }
}
