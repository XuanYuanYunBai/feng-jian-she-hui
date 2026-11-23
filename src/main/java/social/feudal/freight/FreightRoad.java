package social.feudal.freight;

import java.util.HashSet;
import java.util.Set;
import social.feudal.transport.HaulNetwork;
import social.feudal.transport.HaulNetwork.Point;
import social.feudal.realm.RealmService;
import org.bukkit.entity.Boat;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import social.feudal.transport.TransportCrew;
import social.feudal.transport.TransportCrew.Team;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import static social.feudal.freight.FreightStore.*;

// All methods run on Paper's server thread.
final class FreightRoad {
    record Goods(byte[] bytes, String material, int amount, Integer relief) {}
    private record Cell(int x, int z) {}
    final RealmService realm;
    final HaulNetwork net;
    final World world;
    private final Plugin plugin;
    private final NamespacedKey station, receipt;
    private final java.util.Map<String,Set<Cell>> routeTickets=new java.util.HashMap<>();
    private final java.util.Map<Cell,Integer> ticketUsers=new java.util.HashMap<>();
    private final TransportCrew crews;
    private final java.util.Map<String,java.util.Deque<Point>> approaches=new java.util.HashMap<>();
    private final NamespacedKey consignment, relief;

    FreightRoad(Plugin plugin, World world,TransportCrew crews,RealmService realm) {
        relief=new NamespacedKey(plugin,"relief");this.realm=realm;net=realm.roads;
        this.crews=crews;consignment=new NamespacedKey(plugin,"consignment");
        this.plugin = plugin;
        this.world = world;
        station = new NamespacedKey(plugin, "freight_station");
        receipt = new NamespacedKey(plugin, "freight_receipt");
    }

    void checkWorld(Route r) {
        if (!world.getUID().toString().equals(r.world())) throw new IllegalArgumentException("货线属于另一个主世界");
    }

    Route register(String name, Point start, Point end, boolean sea) {
        for(Point p:java.util.List.of(start,end)){
            if(Math.abs((long)p.x())>29_999_980 || Math.abs((long)p.z())>29_999_980 || !net.loaded(p))
                throw new IllegalArgumentException("请先到实地加载两座货站及沿线区块");
        }
        var source=barrel(start.x(),start.y(),start.z(),null);
        var dest=barrel(end.x(),end.y(),end.z(),null);
        Point first=sea?net.berth(start):start,last=sea?net.berth(end):end;
        var path=net.survey(first,last,sea,!sea);
        if(path.size()<2 || (!sea && path.size()<3))throw new IllegalArgumentException("未勘通连续道路或航道，请检查路基、桥亭及泊位");
        var r=new Route(name,world.getUID().toString(),start.x(),start.y(),start.z(),end.x(),end.y(),end.z(),
                seal(source),seal(dest),sea,path);
        if(sea)assignBoat(r,first);
        staff(r,r.sourceSeal());world.save(true);return r;
    }

    private void assignBoat(Route r,Point berth){
        var data=home(r).getPersistentDataContainer();var key=field(r,"boat");
        if(data.has(key)){vessel(r);return;}
        var boats=world.getNearbyEntitiesByType(Boat.class,berth.feet(world),1.8).stream()
                .filter(b->b.getPassengers().isEmpty() && !b.getPersistentDataContainer().has(consignment)).toList();
        if(boats.size()!=1)throw new IllegalArgumentException("起点泊位须停有一艘空闲实体船");
        Boat boat=boats.getFirst();
        boat.getPersistentDataContainer().set(consignment,PersistentDataType.STRING,r.sourceSeal());
        data.set(key,PersistentDataType.STRING,boat.getUniqueId().toString());
    }
    private Boat vessel(Route r){
        String id=home(r).getPersistentDataContainer().get(field(r,"boat"),PersistentDataType.STRING);
        if(id!=null && world.getEntity(UUID.fromString(id)) instanceof Boat boat && !boat.isDead())return boat;
        throw new IllegalArgumentException("等候原登记船只及载货区块加载");
    }
    private Boat board(Route r,Team team){
        Boat boat=vessel(r);
        if(team.porter().getVehicle()==boat)return boat;
        if(!boat.getPassengers().isEmpty() || boat.getLocation().distanceSquared(team.porter().getLocation())>16 ||
                !boat.addPassenger(team.porter()))throw new IllegalArgumentException("船只和船工须在同一泊位");
        return boat;
    }

    private String seal(Barrel barrel) {
        String id = barrel.getPersistentDataContainer().get(station, PersistentDataType.STRING);
        if (id == null) {
            id = UUID.randomUUID().toString();
            barrel.getPersistentDataContainer().set(station, PersistentDataType.STRING, id);
            if (!barrel.update()) throw new IllegalStateException("站桶登记失败");
        }
        return id;
    }

    private Barrel barrel(int x, int y, int z, String seal) {
        if (!world.isChunkGenerated(x >> 4, z >> 4) || !(world.getBlockAt(x, y, z).getState() instanceof Barrel b))
            throw new IllegalArgumentException("该位置缺少站桶 " + x + "," + y + "," + z);
        if (seal != null && !seal.equals(b.getPersistentDataContainer().get(station, PersistentDataType.STRING)))
            throw new IllegalArgumentException("原登记站桶已更换");
        return b;
    }

    static String goodsName(String material) {
        return switch (material) {
            case "BREAD" -> "面包";
            case "IRON_INGOT" -> "铁锭";
            case "WHEAT" -> "小麦";
            case "WHITE_WOOL" -> "白色羊毛";
            case "OAK_LOG" -> "橡木原木";
            default -> material;
        };
    }

    boolean registered(Block block) {
        return block.getWorld().equals(world) && block.getType() == Material.BARREL
                && ((Barrel) block.getState(false)).getPersistentDataContainer().has(station, PersistentDataType.STRING);
    }

    Goods manifest(Route r) {
        checkWorld(r);
        inspect(r,0);inspect(r,r.length());
        var b = barrel(r.sx(), r.y(), r.sz(), r.sourceSeal());
        ItemStack stack = b.getSnapshotInventory().getItem(0);
        if (stack == null || !Set.of(Material.WHEAT, Material.WHITE_WOOL, Material.OAK_LOG, Material.BREAD, Material.IRON_INGOT).contains(stack.getType())
                || stack.getAmount() < 1 || stack.getAmount() > 64)
            throw new IllegalArgumentException("请将 1–64 件小麦、面包、铁锭、白色羊毛或橡木原木放入起点桶第一格");
        Team team=team(r);
        if(stack.getAmount()>team.capacity())throw new IllegalArgumentException("本队每趟上限 "+team.capacity()+" 件，请分批或在起点升级装备");
        world.save(true);
        return new Goods(stack.serializeAsBytes(), stack.getType().name(), stack.getAmount(),stack.getPersistentDataContainer().get(relief,PersistentDataType.INTEGER));
    }

    boolean staged(Route route, byte[] cargo) {
        var source=barrel(route.sx(),route.y(),route.sz(),route.sourceSeal());
        return ItemStack.deserializeBytes(cargo).equals(source.getInventory().getItem(0));
    }

    // The slot and its receipt are written on the same barrel snapshot. Replay after a crash flushes an
    // existing receipt too, in case an earlier save failed after updating the live tile.
    boolean transfer(Trip t, boolean loading) {
        Route r = t.route();
        checkWorld(r);
        Barrel b = loading ? barrel(r.sx(), r.y(), r.sz(), r.sourceSeal()) : barrel(r.dx(), r.dy(), r.dz(), r.destSeal());
        var crewReceipt=new NamespacedKey(plugin,"freight_stamp/"+crewId(r));
        String mark = (loading ? "load/" : "deliver/") + t.id();
        if (!mark.equals(b.getPersistentDataContainer().get(crewReceipt,PersistentDataType.STRING))
                && !mark.equals(b.getPersistentDataContainer().get(receipt,PersistentDataType.STRING))) {
            var inventory = b.getSnapshotInventory();
            ItemStack original = ItemStack.deserializeBytes(t.cargo());
            ItemStack slot = inventory.getItem(0);
            if (loading && !original.equals(slot)) return false;
            if (!loading && slot != null && !slot.isEmpty()) throw new IllegalArgumentException("请清空终点桶第一格");
            Team team=team(r);
            Location dock=location(r,loading?0:r.length());
            inspect(r,loading?0:r.length());
            if(r.sea())board(r,team);
            if (!team.near(dock,4)){
                throw new IllegalArgumentException("等候脚夫与驮畜到达装卸位");
            }
            if (loading){
                if(t.amount()>team.capacity())throw new IllegalArgumentException("货物超过运力，请装备脚夫");
                carry(team,t,original);
            }else{
                if(!original.equals(cargo(team)))throw new IllegalArgumentException("等待原货抵站");
                original=cargo(team);putCargo(team,null);
            }
            inventory.setItem(0, loading ? null : original);
            b.getPersistentDataContainer().set(crewReceipt,PersistentDataType.STRING,mark);
            b.getPersistentDataContainer().set(receipt, PersistentDataType.STRING, mark);
            if (!b.update()) throw new IllegalStateException("站桶更新失败");
        }
        world.save(true);
        return true;
    }

    private Location location(Route r, int step) {
        var p=r.path().get(step);return r.sea()?p.feet(world):net.footing(p);
    }

    // Include clearance around the traveller, including where the surveyed road turns at a chunk edge.
    void hold(Route r, int current, int next) {
        hold(r,r.path().get(current),r.path().get(next));
    }
    private void hold(Route r,Point current,Point next){
        checkWorld(r);
        var needed = new HashSet<Cell>();
        for (var point : new Point[]{current, next}) {
            for (int ox=-1;ox<=1;ox++)for(int oz=-1;oz<=1;oz++){
                int x=point.x()+ox;
                int z=point.z()+oz;
                if (!world.getWorldBorder().isInside(new Location(world, x + .5, r.y(), z + .5)))
                    throw new IllegalArgumentException("路段超出当前世界边界");
                needed.add(new Cell(x >> 4, z >> 4));
            }
        }
        var tickets=routeTickets.computeIfAbsent(r.name(),unused->new HashSet<>());
        for (var c : Set.copyOf(tickets)) if (!needed.contains(c)) {
            drop(c);
            tickets.remove(c);
        }
        for (var c : needed) {
            if (!tickets.contains(c)) {
                if (!world.isChunkGenerated(c.x, c.z)) throw new IllegalArgumentException("前方路段尚未勘通");
                if(ticketUsers.merge(c,1,Integer::sum)==1)world.addPluginChunkTicket(c.x,c.z,plugin);
                tickets.add(c);
            }
            if (world.getChunkAt(c.x, c.z).getLoadLevel() != Chunk.LoadLevel.ENTITY_TICKING)
                throw new IllegalArgumentException("等待路段完成加载");
        }
    }

    void inspect(Route r,int step){
        Point p=r.path().get(step);
        if(!r.sea() && r.length()<2)throw new IllegalArgumentException("两站之间须有实际路段");
        if(r.sea()){
            if(!net.water(p) || step==0 && !net.port(r.start(),p) || step==r.length() && !net.port(r.end(),p))
                throw new IllegalArgumentException("泊位或航道中断 "+p.x()+","+p.y()+","+p.z());
        }else if(step==0 || step==r.length()){
            barrel(p.x(),p.y(),p.z(),step==0?r.sourceSeal():r.destSeal());
            if(!net.block(p).getRelative(0,1,0).isPassable() || !net.block(p).getRelative(0,2,0).isPassable())
                throw new IllegalArgumentException("货站装卸位被堵塞");
        }else if(!net.road(p))throw new IllegalArgumentException("道路或桥亭中断 "+p.x()+","+p.y()+","+p.z());
    }
    private String crewId(Route r){return (r.sea()?"boat/":"station/")+r.sourceSeal();}
    private Chunk home(Route r){return world.getChunkAt(r.sx()>>4,r.sz()>>4);}
    private void staff(Route r,String seal){
        crews.staff(home(r),crewId(r),r.sea()?"船工":"货站",r.sea()?r.start().feet(world):location(r,0));
    }
    Team team(Route r){
        staff(r,r.sourceSeal());
        Team team=crews.team(home(r),crewId(r));
        if(team==null)throw new IllegalArgumentException("等待登记脚夫与驮畜所在区块加载");
        return team;
    }
    void equip(Player p,Route r,String kind){
        if(r.sea() && kind.equalsIgnoreCase("mule"))throw new IllegalArgumentException("水运不使用驮骡");
        team(r);
        crews.equip(p,barrel(r.sx(),r.y(),r.sz(),r.sourceSeal()).getBlock(),crewId(r),TransportCrew.Upgrade.parse(kind));
    }
    String capacity(Route r){return crews.status(home(r),crewId(r));}
    private ItemStack cargo(Team team){
        return team.bags().getItem(team.mule()==null?0:2);
    }
    private void putCargo(Team team,ItemStack stack){
        team.bags().setItem(team.mule()==null?0:2,stack);
    }
    private NamespacedKey field(Route r,String name){return new NamespacedKey(plugin,"freight/"+r.sourceSeal()+"/"+(r.sea() && !name.equals("boat")?"sea/":"")+name);}
    private void carry(Team team,Trip t,ItemStack stack){
        var data=home(t.route()).getPersistentDataContainer();
        data.set(field(t.route(),"bill"),PersistentDataType.STRING,t.id());
        data.remove(field(t.route(),"approach"));
        putCargo(team,stack);
        team.porter().customName(Component.text((t.route().sea()?"货船船工":"货站脚夫")+" · "+goodsName(t.material())+" ×"+t.amount()));
        team.porter().getPersistentDataContainer().set(consignment,PersistentDataType.STRING,t.id());
        crews.active(team,true);
    }
    boolean walk(Route r,Team team,int step){
        return walkTo(r,team,r.path().get(step));
    }
    private boolean walkTo(Route r,Team team,Point point){
        Location goal=r.sea()?point.feet(world):net.footing(point);
        if(r.sea()){
            Boat boat=board(r,team);var here=boat.getLocation();var delta=goal.toVector().subtract(here.toVector()).setY(0);
            if(delta.lengthSquared()<.12){boat.setVelocity(new org.bukkit.util.Vector());return true;}
            boat.setVelocity(delta.normalize().multiply(.18));return false;
        }
        Location packGoal=goal;
        boolean animalReady=team.mule()==null || team.mule().getLocation().distanceSquared(packGoal)<2.25;
        if(team.porter().getLocation().distanceSquared(goal)<.48 && animalReady){team.halt();return true;}
        team.onRoad(goal,packGoal,net.pace(point));return false;
    }
    private boolean dockSurface(Point point,Route route){
        if(!net.loaded(point))return false;
        if(route.sea())return net.water(point);
        return net.road(point) || registered(net.block(point)) && net.block(point).getRelative(0,1,0).isPassable()
                && net.block(point).getRelative(0,2,0).isPassable();
    }
    // A crowd or world reload can displace a signed load. Rejoin the recorded route on foot.
    boolean atDock(Trip t)throws java.sql.SQLException{
        var r=t.route();var bin=barrel(r.dx(),r.dy(),r.dz(),r.destSeal());
        String stamp="deliver/"+t.id();
        if(stamp.equals(bin.getPersistentDataContainer().get(receipt,PersistentDataType.STRING))
                || stamp.equals(bin.getPersistentDataContainer().get(new NamespacedKey(plugin,"freight_stamp/"+crewId(r)),PersistentDataType.STRING)))return true;
        var team=team(r);
        if(team.near(location(r,r.length()),4)){approaches.remove(t.id());return true;}
        var loc=team.porter().getLocation();
        var here=new Point(loc.getBlockX(),(int)Math.floor(loc.getY()-.001),loc.getBlockZ());
        if(r.sea())here=new Point(vessel(r).getLocation().getBlockX(),r.path().getLast().y(),vessel(r).getLocation().getBlockZ());
        if(!dockSurface(here,r))throw new IllegalArgumentException("等候原工班脚下路面或航道恢复");
        var path=approaches.get(t.id());
        if(path==null || path.isEmpty() || here.distance(path.getFirst())>4){
            int closest=0;
            for(int i=1;i<r.path().size();i++)
                if(here.distance(r.path().get(i))<here.distance(r.path().get(closest)))closest=i;
            Point join=r.path().get(closest);
            if(here.distance(join)>8)throw new IllegalArgumentException("工班偏离登记货线，请引回沿线");
            path=new java.util.ArrayDeque<>();
            if(!here.equals(join)){
                var link=net.survey(here,join,r.sea(),!r.sea());
                if(link.isEmpty() || link.size()>17)throw new IllegalArgumentException("请接通工班回到原货线的实路");
                path.addAll(link.subList(1,link.size()));
            }
            path.addAll(r.path().subList(closest+1,r.path().size()));
            if(path.isEmpty())path.add(join);
            approaches.put(t.id(),path);
        }
        Point next=path.getFirst();hold(r,here,next);
        if(!dockSurface(next,r) || !r.sea() && !net.traversable(here,next)){
            team.halt();throw new IllegalArgumentException("原工班回站的路面中断");
        }
        if(!r.sea())realm.clearance(next,"货-"+t.id(),team);
        if(walkTo(r,team,next))path.removeFirst();
        return false;
    }
    boolean readyToCarry(Trip t){
        Route r=t.route();
        var data=home(r).getPersistentDataContainer();
        if(t.id().equals(data.get(field(r,"bill"),PersistentDataType.STRING)))return true;
        // Older versions kept cargo in the freight manifest. The worker must collect it at the saved road position.
        if(t.phase()==Phase.UNLOADING){
            var dest=barrel(r.dx(),r.dy(),r.dz(),r.destSeal());
            if(("deliver/"+t.id()).equals(dest.getPersistentDataContainer().get(receipt,PersistentDataType.STRING)))return true;
        }
        int step=Math.min(t.step(),data.getOrDefault(field(r,"approach"),PersistentDataType.INTEGER,0));
        int next=Math.min(t.step(),step+1);
        hold(r,step,next);
        var team=team(r);
        if(t.id().equals(team.porter().getPersistentDataContainer().get(consignment,PersistentDataType.STRING))){
            data.set(field(r,"bill"),PersistentDataType.STRING,t.id());return true;
        }
        if(t.id().startsWith("relief."))throw new IllegalArgumentException("等候本段原班货物复核，请同时恢复货队与地籍检查点");
        if(t.amount()>team.capacity())throw new IllegalArgumentException("请先在起点为旧运单配足运力");
        inspect(r,step);inspect(r,next);
        if(!walk(r,team,next))return false;
        data.set(field(r,"approach"),PersistentDataType.INTEGER,next);
        if(next<t.step())return false;
        carry(team,t,ItemStack.deserializeBytes(t.cargo()));world.save(true);return true;
    }
    void rested(Route route,Team team){crews.active(team,false);team.halt();team.porter().customName(Component.text(route.sea()?"渡口 · 船工":"货站 · 脚夫"));}
    private void drop(Cell c){
        if(ticketUsers.merge(c,-1,Integer::sum)==0){
            ticketUsers.remove(c);world.removePluginChunkTicket(c.x,c.z,plugin);
        }
    }
    void release(Route r){
        var cells=routeTickets.remove(r.name());
        if(cells!=null)cells.forEach(this::drop);
    }
    void release(){
        for(var cell:ticketUsers.keySet())world.removePluginChunkTicket(cell.x,cell.z,plugin);
        routeTickets.clear();ticketUsers.clear();
    }
}
