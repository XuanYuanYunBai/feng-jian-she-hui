package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

/** Mark a room by standing on its floor. Furniture can be rearranged between inspections. */
final class ManorRooms implements Listener {
    private final RealmService realm;
    private final RealmStore s;
    private static final List<BlockFace> SIDES=List.of(BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST);
    private final Map<Block,String> cabinets=new HashMap<>();
    ManorRooms(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;
        s.update("""
            CREATE TABLE IF NOT EXISTS manor_room(manor TEXT REFERENCES manor(id),kind TEXT,x INTEGER,y INTEGER,z INTEGER,
            PRIMARY KEY(manor,kind))""");
        try(var q=s.sql("SELECT r.x,r.y,r.z,m.owner FROM manor_room r JOIN manor m ON m.id=r.manor WHERE kind='私柜'");var row=q.executeQuery()){
            while(row.next())cabinets.put(realm.world.getBlockAt(row.getInt(1),row.getInt(2),row.getInt(3)),row.getString(4));
        }
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }
    Block site(String id,String kind)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM manor_room WHERE manor=? AND kind=?",id,kind);var row=q.executeQuery()){
            return row.next()?realm.world.getBlockAt(row.getInt(1),row.getInt(2),row.getInt(3)):null;
        }
    }
    private void mark(String id,String kind,Block b)throws SQLException{
        s.update("""
            INSERT INTO manor_room VALUES(?,?,?,?,?) ON CONFLICT(manor,kind) DO UPDATE SET
            x=excluded.x,y=excluded.y,z=excluded.z""",id,kind,b.getX(),b.getY(),b.getZ());
    }
    record Room(Set<Block> floor,Set<Block> furniture,Set<Block> doors){}
    private Room inspect(Block start,SettlementRegister.Manor m,int clear){
        require(start!=null && m.estate().contains(start.getX(),start.getZ()),"请站在庄界内的室内地板上");
        Set<Block> floor=new HashSet<>(),furniture=new HashSet<>(),doors=new HashSet<>();
        var queue=new ArrayDeque<Block>();queue.add(start);floor.add(start);
        while(!queue.isEmpty()){
            var b=queue.remove();
            require(b.isPassable() && b.getRelative(BlockFace.DOWN).getType().isSolid(),"室内需有连续地板");
            int roof=1;
            while(roof<=8 && b.getRelative(0,roof,0).isPassable())roof++;
            require(roof>=clear && roof<=8,"房间净高或屋顶不合格："+b.getX()+"，"+b.getZ());
            for(var side:SIDES){
                var n=b.getRelative(side);
                require(m.estate().contains(n.getX(),n.getZ()),"房间敞向院外，请补墙和门");
                if(n.getBlockData() instanceof Door door){
                    if(door.getFacing()==side || door.getFacing()==side.getOppositeFace()){
                        var beyond=n.getRelative(side);
                        if(beyond.isPassable() && beyond.getRelative(BlockFace.UP).isPassable() &&
                                beyond.getRelative(BlockFace.DOWN).getType().isSolid())doors.add(n);
                    }
                }else if(!n.isPassable() || Tag.BEDS.isTagged(n.getType())){
                    furniture.add(n);furniture.add(n.getRelative(BlockFace.UP));
                }else if(floor.add(n))queue.add(n);
            }
        }
        require(!doors.isEmpty(),"须有一扇通向可行走地面的门");
        return new Room(floor,furniture,doors);
    }
    private Room hall(Block start,SettlementRegister.Manor m){
        var room=inspect(start,m,3);
        require(room.floor.size()>=35,"大厅需 35 格可走地面，现场 "+room.floor.size()+" 格");
        return room;
    }
    private boolean walkable(Block b){
        return (b.isPassable() || b.getBlockData() instanceof Door) && !b.isLiquid();
    }
    private boolean reaches(Room from,Room to,SettlementRegister.Manor m){
        var queue=new ArrayDeque<Block>(from.doors());var seen=new HashSet<Block>(from.doors());
        while(!queue.isEmpty()){
            var here=queue.remove();
            if(to.floor.contains(here))return true;
            for(var side:SIDES)for(int dy:new int[]{0,1,-1}){
                var b=here.getRelative(side).getRelative(0,dy,0);
                if(!m.estate().contains(b.getX(),b.getZ()) || Math.abs(b.getY()-m.y())>12 ||
                    !walkable(b) || !walkable(b.getRelative(BlockFace.UP)) || !b.getRelative(BlockFace.DOWN).getType().isSolid())continue;
                if(dy>0 && !walkable(here.getRelative(0,2,0)))continue;
                if(seen.add(b))queue.add(b);
                break;
            }
        }
        return false;
    }
    void council(SettlementRegister.Manor m)throws SQLException{
        var room=hall(site(m.id(),"议事厅"),m);
        boolean desk=false,bell=false,archive=false;int seats=0;
        for(var b:room.furniture){
            switch(b.getType()){case LECTERN -> desk=true;case BELL -> bell=true;case BARREL -> archive=true;default -> {}}
            if(Tag.STAIRS.isTagged(b.getType()))seats++;
        }
        require(desk && bell && archive && seats>=2,"议事厅须有讲台、钟、案卷桶及两处楼梯方块旁听席，均须贴近可走地面");
    }
    void register(Player p,SettlementRegister.Manor m,String kind,boolean confirm)throws SQLException{
        require(m.owner().equals(p.getUniqueId().toString()),"请由庄主登记房间");
        Block start=p.getLocation().getBlock();
        if(kind.equals("大厅") || kind.equals("议事厅")){
            var main=hall(start,m);
            var bed=site(m.id(),"私床");
            require(bed==null || !main.furniture.contains(bed),"大厅和私室须有墙门分隔");
            if(kind.equals("议事厅")){
                s.atomic(()->{mark(m.id(),kind,start);council(m);return null;});
            }else mark(m.id(),kind,start);
        }else{
            require(kind.equals("私室"),"请选择大厅、私室或议事厅");
            var main=hall(site(m.id(),"大厅"),m);var privateRoom=inspect(start,m,2);
            require(!main.floor.contains(start) && privateRoom.floor.size()>=9,"独立私室须有至少 9 格可走地面");
            require(reaches(main,privateRoom,m),"请在大厅和私室之间修出可步行的门廊或楼梯");
            var bed=privateRoom.furniture.stream().filter(b->b.getBlockData() instanceof Bed data && data.getPart()==Bed.Part.HEAD).toList();
            var bins=privateRoom.furniture.stream().filter(b->b.getType()==Material.BARREL).toList();
            require(bed.size()==1 && bins.size()==1,"私室登记一张家族床和一只家用木桶");
            var pantry=realm.manors.foodBarrel(m);
            require(pantry==null || !pantry.getBlock().equals(bins.getFirst()),"家用私柜与赈济桶须分开");
            if(!confirm){
                int after=ManorHousing.capacity(realm.world,m,bed.getFirst());
                show(p,"家族私室",List.of(text("入住后，这张床供玩家睡眠。居民住房将为 "+after+" 人。")),List.of(),
                    List.of(command("确认入住","realm room "+m.id()+" 私室 确认")),1);return;
            }
            Block old=site(m.id(),"私柜");
            s.atomic(()->{mark(m.id(),"私床",bed.getFirst());mark(m.id(),"私柜",bins.getFirst());return null;});
            if(old!=null)cabinets.remove(old);cabinets.put(bins.getFirst(),m.owner());
            s.update("UPDATE manor SET housing=? WHERE id=?",realm.manors.capacity(m),m.id());
        }
        realm.changed();realm.manors.archive(p,m.id());
    }
    private boolean locked(Inventory inventory){
        var l=inventory.getLocation();return l!=null && cabinets.containsKey(l.getBlock());
    }
    @EventHandler(ignoreCancelled=true) public void open(InventoryOpenEvent e){
        var l=e.getInventory().getLocation();if(l==null)return;
        String owner=cabinets.get(l.getBlock());
        if(owner!=null && !owner.equals(e.getPlayer().getUniqueId().toString())){e.setCancelled(true);e.getPlayer().sendMessage("这是家族私柜。");}
    }
    @EventHandler(ignoreCancelled=true) public void move(InventoryMoveItemEvent e){
        if(locked(e.getSource()) || locked(e.getDestination()))e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void dismantle(BlockBreakEvent e){
        String owner=cabinets.get(e.getBlock());
        if(owner!=null && !owner.equals(e.getPlayer().getUniqueId().toString()))e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void blast(EntityExplodeEvent e){e.blockList().removeIf(cabinets::containsKey);}
    @EventHandler(ignoreCancelled=true) public void blast(BlockExplodeEvent e){e.blockList().removeIf(cabinets::containsKey);}
}
