package social.feudal.realm;

import java.sql.SQLException;
import java.util.List;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import social.feudal.transport.FootNavigation;
import social.feudal.transport.PorterCargo;
import static social.feudal.realm.RealmStore.require;

/** A standing food order consumes an ordinary work shift. Ingredients travel in the baker's hand barrel. */
final class KitchenDuty {
    private final RealmService realm;
    private final Bakehouse recipes;
    private final FootNavigation navigation;
    private final NamespacedKey strokes,ovenJob,ovenSlot,ovenTime;
    private final RealmStore s;

    KitchenDuty(RealmService realm,Bakehouse recipes,FootNavigation navigation)throws SQLException{
        this.realm=realm;this.recipes=recipes;this.navigation=navigation;s=realm.store;
        strokes=new NamespacedKey(realm.plugin,"kneading_strokes");
        ovenJob=new NamespacedKey(realm.plugin,"baker_job");
        ovenSlot=new NamespacedKey(realm.plugin,"baker_slot");
        ovenTime=new NamespacedKey(realm.plugin,"baker_ticks");
        s.update("""
            CREATE TABLE IF NOT EXISTS kitchen(mill TEXT REFERENCES mill(id),kind TEXT,x INTEGER,y INTEGER,z INTEGER,
            PRIMARY KEY(mill,kind),UNIQUE(x,y,z))""");
        s.update("CREATE TABLE IF NOT EXISTS kitchen_duty(mill TEXT PRIMARY KEY REFERENCES mill(id))");
    }

    void register(Player p,MillOffice.Mill mill,String kind)throws SQLException{
        var m=realm.manors.register.manor(mill.manor());
        var b=p.getTargetBlockExact(6);
        require(b!=null && m.estate().contains(b.getX(),b.getZ()),"请在本庄瞄准要登记的设施");
        require(kind.equals("木案")?recipes.boardReady(b):kind.equals("营火") && b.getState() instanceof Campfire,
                "木案使用邻水木台阶且案面留空，烤炉使用营火");
        require(approach(b)!=null,"设施旁须留两格高的平地供工人站立");
        require(s.number("SELECT count(*) FROM farmhand WHERE mill=? AND role='口粮' AND status<>'结工'",mill.id())==0,"请在本班完工后改登记");
        String owner=s.value("SELECT mill FROM kitchen WHERE x=? AND y=? AND z=?",b.getX(),b.getY(),b.getZ());
        require(owner==null || owner.equals(mill.id()),"设施已由另一磨坊使用");
        s.update("""
            INSERT INTO kitchen VALUES(?,?,?,?,?) ON CONFLICT(mill,kind)
            DO UPDATE SET x=excluded.x,y=excluded.y,z=excluded.z""",mill.id(),kind,b.getX(),b.getY(),b.getZ());
    }

    private Block fixture(String mill,String kind)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM kitchen WHERE mill=? AND kind=?",mill,kind);var r=q.executeQuery()){
            if(!r.next() || !realm.world.isChunkLoaded(r.getInt(1)>>4,r.getInt(3)>>4))return null;
            return realm.world.getBlockAt(r.getInt(1),r.getInt(2),r.getInt(3));
        }
    }
    private Location approach(Block b){
        for(var face:List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST)){
            var side=b.getRelative(face);
            if(side.isPassable() && !side.isLiquid() && side.getRelative(BlockFace.UP).isPassable()
                    && side.getRelative(BlockFace.DOWN).getType().isSolid())return side.getLocation().add(.5,0,.5);
        }
        return null;
    }
    private boolean at(Mannequin npc,Block b){
        var target=approach(b);if(target==null)return false;
        if(npc.getLocation().distanceSquared(target)>.9){navigation.toward(npc,target,1);return false;}
        var direction=b.getLocation().add(.5,.6,.5).subtract(npc.getEyeLocation()).toVector();
        var faced=npc.getLocation().setDirection(direction);npc.setRotation(faced.getYaw(),faced.getPitch());
        return true;
    }
    private void action(Mannequin npc,Block b,ItemStack item){
        npc.swingMainHand();
        var point=b.getLocation().add(.5,.65,.5);
        b.getWorld().spawnParticle(Particle.ITEM,point,8,.12,.05,.1,.03,item);
        b.getWorld().playSound(point,Sound.BLOCK_MUD_HIT,.6f,.9f);
    }
    String work(int id,MillOffice.Mill mill,Mannequin npc,Inventory grain,Block wheel,boolean finished)throws SQLException{
        var cargo=new PorterCargo(npc,null);var held=cargo.getItem(0);
        Block fire=fixture(mill.id(),"营火");
        Campfire oven=fire!=null && fire.getState() instanceof Campfire c?c:null;
        if(oven!=null && oven.getPersistentDataContainer().getOrDefault(ovenJob,PersistentDataType.INTEGER,-1)==id)
            return bake(npc,oven,cargo);
        if(held!=null && held.getType()==Material.BREAD){
            var pantry=realm.manors.foodBarrel(realm.manors.register.manor(mill.manor()));
            if(pantry==null || !at(npc,pantry.getBlock()))return "送粮至厅旁木桶";
            if(!pantry.getInventory().addItem(held).isEmpty())return "口粮仓满";
            cargo.setItem(0,null);return "口粮已送达";
        }
        if(held!=null && held.isSimilar(recipes.dough)){
            if(oven==null || !at(npc,fire))return "等待营火与通路";
            if(!((Lightable)fire.getBlockData()).isLit())return "炉火已熄";
            var data=oven.getPersistentDataContainer();
            if(data.has(ovenJob))return "等候炉位";
            for(int slot=0;slot<oven.getSize();slot++)if(oven.getItem(slot)==null || oven.getItem(slot).isEmpty()){
                oven.setItem(slot,held);
                // A newly populated slot has vanilla total=0 and would eject bread on the very next tick.
                // This slot is turned by the baker; the other three keep their normal campfire recipe timers.
                oven.setCookTime(slot,0);oven.setCookTimeTotal(slot,Integer.MAX_VALUE);oven.stopCooking(slot);
                data.set(ovenJob,PersistentDataType.INTEGER,id);data.set(ovenSlot,PersistentDataType.INTEGER,slot);data.set(ovenTime,PersistentDataType.INTEGER,0);
                if(oven.update(false,false)){cargo.setItem(0,null);action(npc,fire,recipes.dough);}
                return "投炉烘焙";
            }
            return "炉位已满";
        }
        if(held!=null && held.isSimilar(recipes.flour)){
            var board=fixture(mill.id(),"木案");
            if(board==null || !recipes.boardReady(board) || !at(npc,board))return "等待案板、清水与通路";
            var data=npc.getPersistentDataContainer();
            int n=data.getOrDefault(strokes,PersistentDataType.INTEGER,0)+1;
            action(npc,board,recipes.flour);
            if(n==3){cargo.setItem(0,recipes.dough.clone());data.remove(strokes);}
            else data.set(strokes,PersistentDataType.INTEGER,n);
            return "案前揉面 "+n+"/3";
        }
        if(finished){npc.remove();return "结工";}
        var board=fixture(mill.id(),"木案");
        if(board==null || !recipes.boardReady(board) || oven==null)return "待修木案或营火";
        var manor=realm.manors.register.manor(mill.manor());
        var reserve=realm.manors.foodBarrel(manor);
        int target=2*Math.ceilDiv(manor.residents(),100)+3;
        if(reserve!=null && reserve.getInventory().contains(Material.BREAD,target))return "口粮储备充足";
        if(!grain.containsAtLeast(recipes.flour,3) && !grain.contains(Material.WHEAT))return "缺麦与面粉";
        if(!at(npc,wheel.getRelative(BlockFace.EAST)))return "前往磨坊粮桶";
        var flour=recipes.flour.asQuantity(3);
        if(grain.containsAtLeast(flour,3)){
            grain.removeItem(flour);cargo.setItem(0,flour);
        }else{
            var wheat=new ItemStack(Material.WHEAT);
            if(!grain.containsAtLeast(wheat,1))return "缺麦与面粉";
            if(grain.firstEmpty()<0 && !grain.containsAtLeast(recipes.flour,1))return "粮桶缺少存粉位";
            grain.removeItem(wheat);
            var left=grain.addItem(recipes.flour.clone());
            if(!left.isEmpty()){grain.addItem(wheat);return "面粉仓满";}
            recipes.animateGrinding(npc,wheel);
        }
        s.update("UPDATE farmhand SET quota=quota-1 WHERE id=? AND quota>0",id);
        return "备办口粮";
    }
    private String bake(Mannequin npc,Campfire oven,PorterCargo cargo){
        var data=oven.getPersistentDataContainer();
        int slot=data.getOrDefault(ovenSlot,PersistentDataType.INTEGER,0);
        var item=oven.getItem(slot);
        if(item!=null && item.isSimilar(recipes.dough)){
            if(!((Lightable)oven.getBlockData()).isLit())return "炉火已熄，面团留炉";
            if(!at(npc,oven.getBlock()))return "返回炉前";
            int ticks=data.getOrDefault(ovenTime,PersistentDataType.INTEGER,0)+10;
            if(ticks<160){data.set(ovenTime,PersistentDataType.INTEGER,ticks);oven.update(false,false);return "烘焙 "+ticks/20+"/8 秒";}
            oven.setItem(slot,null);
        }
        data.remove(ovenJob);data.remove(ovenSlot);data.remove(ovenTime);oven.startCooking(slot);
        if(!oven.update(false,false))return "复核炉位";
        if(item==null || !item.isSimilar(recipes.dough))return "原面团已被取走";
        cargo.setItem(0,new ItemStack(Material.BREAD));action(npc,oven.getBlock(),new ItemStack(Material.BREAD));
        return "出炉";
    }
}
