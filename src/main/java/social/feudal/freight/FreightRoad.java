package social.feudal.freight;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import social.feudal.transport.TransportCrew;
import social.feudal.transport.TransportCrew.Team;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import static social.feudal.freight.FreightStore.*;

// All methods run on Paper's server thread.
final class FreightRoad {
    record Goods(byte[] bytes, String material, int amount) {}
    private record Cell(int x, int z) {}
    final World world;
    private final Plugin plugin;
    private final NamespacedKey station, receipt;
    private final Set<Cell> tickets = new HashSet<>();
    private final TransportCrew crews;
    private final NamespacedKey consignment;

    FreightRoad(Plugin plugin, World world,TransportCrew crews) {
        this.crews=crews;consignment=new NamespacedKey(plugin,"consignment");
        this.plugin = plugin;
        this.world = world;
        station = new NamespacedKey(plugin, "freight_station");
        receipt = new NamespacedKey(plugin, "freight_receipt");
    }

    void checkWorld(Route r) {
        if (!world.getUID().toString().equals(r.world())) throw new IllegalArgumentException("货线属于另一个主世界");
    }

    Route register(String name, int sx, int y, int sz, int dx, int dz) {
        for (int v : new int[]{sx, sz, dx, dz})
            if (Math.abs((long)v) > 29_999_980) throw new IllegalArgumentException("坐标超过世界范围");
        if (y < world.getMinHeight() || (long)y + 3 >= world.getMaxHeight())
            throw new IllegalArgumentException("站台高度超过世界范围");
        var r = new Route(name, world.getUID().toString(), sx, y, sz, dx, dz, "", "");
        var source = barrel(r.sx(), r.y(), r.sz(), null);
        var dest = barrel(r.dx(), r.y(), r.dz(), null);
        String a = seal(source), b = seal(dest);
        staff(r,a);
        world.save(true);
        return new Route(name, r.world(), sx, y, sz, dx, dz, a, b);
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
        var b = barrel(r.sx(), r.y(), r.sz(), r.sourceSeal());
        ItemStack stack = b.getSnapshotInventory().getItem(0);
        if (stack == null || !Set.of(Material.WHEAT, Material.WHITE_WOOL, Material.OAK_LOG).contains(stack.getType())
                || stack.getAmount() < 1 || stack.getAmount() > 64)
            throw new IllegalArgumentException("请将 1–64 件小麦、白色羊毛或橡木原木放入起点桶第一格");
        Team team=team(r);
        if(stack.getAmount()>team.capacity())throw new IllegalArgumentException("本队每趟上限 "+team.capacity()+" 件，请分批或在起点升级装备");
        world.save(true);
        return new Goods(stack.serializeAsBytes(), stack.getType().name(), stack.getAmount());
    }

    // The slot and its receipt are written on the same barrel snapshot. Replay after a crash flushes an
    // existing receipt too, in case an earlier save failed after updating the live tile.
    boolean transfer(Trip t, boolean loading) {
        Route r = t.route();
        checkWorld(r);
        Barrel b = loading ? barrel(r.sx(), r.y(), r.sz(), r.sourceSeal()) : barrel(r.dx(), r.y(), r.dz(), r.destSeal());
        String mark = (loading ? "load/" : "deliver/") + t.id();
        if (!mark.equals(b.getPersistentDataContainer().get(receipt, PersistentDataType.STRING))) {
            var inventory = b.getSnapshotInventory();
            ItemStack original = ItemStack.deserializeBytes(t.cargo());
            ItemStack slot = inventory.getItem(0);
            if (loading && !original.equals(slot)) return false;
            if (!loading && slot != null && !slot.isEmpty()) throw new IllegalArgumentException("请清空终点桶第一格");
            Team team=team(r);
            Location dock=location(r,loading?0:r.length());
            if (!team.near(dock,1.5)){
                team.move(dock);
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
            b.getPersistentDataContainer().set(receipt, PersistentDataType.STRING, mark);
            if (!b.update()) throw new IllegalStateException("站桶更新失败");
        }
        world.save(true);
        return true;
    }

    private Location location(Route r, int step) {
        return new Location(world, r.x(step) + 0.5, r.y() + 1.0, r.z(step) + 0.5);
    }

    // At most two adjacent road cross-sections, covering up to four chunks.
    void hold(Route r, int current, int next) {
        checkWorld(r);
        var needed = new HashSet<Cell>();
        for (int step : new int[]{current, next}) {
            for (int offset = -1; offset <= 1; offset++) {
                int x = r.x(step) + (r.sx() == r.dx() ? offset : 0);
                int z = r.z(step) + (r.sz() == r.dz() ? offset : 0);
                if (!world.getWorldBorder().isInside(new Location(world, x + .5, r.y(), z + .5)))
                    throw new IllegalArgumentException("路段超出当前世界边界");
                needed.add(new Cell(x >> 4, z >> 4));
            }
        }
        for (var c : Set.copyOf(tickets)) if (!needed.contains(c)) {
            world.removePluginChunkTicket(c.x, c.z, plugin);
            tickets.remove(c);
        }
        for (var c : needed) {
            if (!tickets.contains(c)) {
                if (!world.isChunkGenerated(c.x, c.z)) throw new IllegalArgumentException("前方路段尚未勘通");
                world.addPluginChunkTicket(c.x, c.z, plugin);
                tickets.add(c);
            }
            if (world.getChunkAt(c.x, c.z).getLoadLevel() != Chunk.LoadLevel.ENTITY_TICKING)
                throw new IllegalArgumentException("等待路段完成加载");
        }
    }

    void inspect(Route r, int step) {
        for (int offset = -1; offset <= 1; offset++) {
            int x = r.x(step) + (r.sx() == r.dx() ? offset : 0);
            int z = r.z(step) + (r.sz() == r.dz() ? offset : 0);
            Block deck = world.getBlockAt(x, r.y(), z);
            if (!deck.getBlockData().isFaceSturdy(BlockFace.UP, BlockSupport.FULL))
                throw new IllegalArgumentException("缺少连续路面 " + x + "," + r.y() + "," + z);
            for (int h = 1; h <= 3; h++) if (!deck.getRelative(0, h, 0).getType().isAir())
                throw new IllegalArgumentException("净空不足 " + x + "," + (r.y() + h) + "," + z);
        }
    }

    private String crewId(String seal){return "station/"+seal;}
    private Chunk home(Route r){return world.getChunkAt(r.sx()>>4,r.sz()>>4);}
    private void staff(Route r,String seal){
        crews.staff(home(r),crewId(seal),"货站",location(r,0));
    }
    Team team(Route r){
        staff(r,r.sourceSeal());
        Team team=crews.team(home(r),crewId(r.sourceSeal()));
        if(team==null)throw new IllegalArgumentException("等待登记脚夫与驮畜所在区块加载");
        return team;
    }
    void equip(Player p,Route r,String kind){
        team(r);
        crews.equip(p,barrel(r.sx(),r.y(),r.sz(),r.sourceSeal()).getBlock(),crewId(r.sourceSeal()),TransportCrew.Upgrade.parse(kind));
    }
    String capacity(Route r){return crews.status(home(r),crewId(r.sourceSeal()));}
    private ItemStack cargo(Team team){
        if(team.mule()!=null)return team.mule().getInventory().getItem(2);
        var box=team.porter().getInventory().getItem(0);
        if(box==null || !(box.getItemMeta() instanceof BlockStateMeta meta) || !(meta.getBlockState() instanceof Barrel barrel))return null;
        return barrel.getSnapshotInventory().getItem(0);
    }
    private void putCargo(Team team,ItemStack stack){
        if(team.mule()!=null){team.mule().getInventory().setItem(2,stack);return;}
        ItemStack box=null;
        if(stack!=null){
            box=new ItemStack(Material.BARREL);
            var meta=(BlockStateMeta)box.getItemMeta();var barrel=(Barrel)meta.getBlockState();
            barrel.getSnapshotInventory().setItem(0,stack);meta.setBlockState(barrel);box.setItemMeta(meta);
        }
        team.porter().getInventory().setItem(0,box);
    }
    private NamespacedKey field(Route r,String name){return new NamespacedKey(plugin,"freight/"+r.sourceSeal()+"/"+name);}
    private void carry(Team team,Trip t,ItemStack stack){
        var data=home(t.route()).getPersistentDataContainer();
        data.set(field(t.route(),"bill"),PersistentDataType.STRING,t.id());
        data.remove(field(t.route(),"approach"));
        putCargo(team,stack);
        team.porter().customName(Component.text("货站脚夫 · "+goodsName(t.material())+" ×"+t.amount()));
        team.porter().getPersistentDataContainer().set(consignment,PersistentDataType.STRING,t.id());
        crews.active(team,true);
    }
    boolean walk(Route r,Team team,int step){
        Location goal=location(r,step);
        if(team.near(goal,.48)){team.halt();return true;}
        team.move(goal);return false;
    }
    boolean readyToCarry(Trip t){
        Route r=t.route();
        var data=home(r).getPersistentDataContainer();
        if(t.id().equals(data.get(field(r,"bill"),PersistentDataType.STRING)))return true;
        // Older versions kept cargo in the freight manifest. The worker must collect it at the saved road position.
        if(t.phase()==Phase.UNLOADING){
            var dest=barrel(r.dx(),r.y(),r.dz(),r.destSeal());
            if(("deliver/"+t.id()).equals(dest.getPersistentDataContainer().get(receipt,PersistentDataType.STRING)))return true;
        }
        int step=Math.min(t.step(),data.getOrDefault(field(r,"approach"),PersistentDataType.INTEGER,0));
        int next=Math.min(t.step(),step+1);
        hold(r,step,next);
        var team=team(r);
        if(t.id().equals(team.porter().getPersistentDataContainer().get(consignment,PersistentDataType.STRING))){
            data.set(field(r,"bill"),PersistentDataType.STRING,t.id());return true;
        }
        if(t.amount()>team.capacity())throw new IllegalArgumentException("请先在起点为旧运单配足运力");
        inspect(r,step);inspect(r,next);
        if(!walk(r,team,next))return false;
        data.set(field(r,"approach"),PersistentDataType.INTEGER,next);
        if(next<t.step())return false;
        carry(team,t,ItemStack.deserializeBytes(t.cargo()));world.save(true);return true;
    }
    void rested(Team team){crews.active(team,false);team.halt();team.porter().customName(Component.text("货站 · 脚夫"));}
    void release() {
        for (var c : tickets) world.removePluginChunkTicket(c.x, c.z, plugin);
        tickets.clear();
    }
}
