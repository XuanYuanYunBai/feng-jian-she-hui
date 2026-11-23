package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import social.feudal.transport.*;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

/** A carpenter can fit the smith's actual brace and a plank into a missing registered deck cell. */
final class SmithyOffice {
    private final RealmService r;private final RealmStore s;
    private final FootNavigation walking;
    private final NamespacedKey input,output,brace;
    SmithyOffice(RealmService r)throws SQLException{
        this.r=r;s=r.store;walking=new FootNavigation(r.plugin);
        input=new NamespacedKey(r.plugin,"forge_charge");output=new NamespacedKey(r.plugin,"forge_receipt");brace=new NamespacedKey(r.plugin,"bridge_brace");
        s.update("CREATE TABLE IF NOT EXISTS smithy(manor TEXT PRIMARY KEY REFERENCES manor(id),x INTEGER,y INTEGER,z INTEGER,UNIQUE(x,y,z))");
        s.update("CREATE TABLE IF NOT EXISTS smith_order(id INTEGER PRIMARY KEY,manor TEXT REFERENCES smithy(manor),stage INTEGER DEFAULT 0,blows INTEGER DEFAULT 0,status TEXT)");
        s.update("CREATE UNIQUE INDEX IF NOT EXISTS smith_busy ON smith_order(manor) WHERE stage<3");
        Bukkit.getScheduler().runTaskTimer(r.plugin,this::tick,80,15);
    }
    private record Forge(Block anvil,Block bin,Location bench,Location loading){}
    private Forge fixtures(SettlementRegister.Manor m,Block anvil){
        require(anvil!=null && Tag.ANVIL.isTagged(anvil.getType()) && m.estate().contains(anvil.getX(),anvil.getZ()),"请瞄准本庄铁砧");
        var bin=anvil.getRelative(BlockFace.EAST);var fire=anvil.getRelative(BlockFace.WEST);
        require(bin.getState() instanceof Barrel && fire.getType()==Material.BLAST_FURNACE &&
                m.estate().contains(bin.getX(),bin.getZ()) && m.estate().contains(fire.getX(),fire.getZ()),"铁砧东设料桶、西设高炉，三者均须在庄内");
        var pantry=r.manors.foodBarrel(m);
        require(pantry==null || !pantry.getBlock().equals(bin),"铁工料桶须与粮仓分开");
        var front=anvil.getRelative(BlockFace.NORTH);var loading=bin.getRelative(BlockFace.NORTH);
        for(var b:List.of(front,loading))require(b.isPassable() && !b.isLiquid() && b.getRelative(BlockFace.UP).isPassable() &&
                b.getRelative(BlockFace.DOWN).getType().isSolid(),"铁砧与仓桶北侧须有可走的地板与净空");
        return new Forge(anvil,bin,front.getLocation().add(.5,0,.5),loading.getLocation().add(.5,0,.5));
    }
    private Forge existing(SettlementRegister.Manor m)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM smithy WHERE manor=?",m.id());var row=q.executeQuery()){
            require(row.next(),"先在庄内登记铁匠铺");
            return fixtures(m,r.world.getBlockAt(row.getInt(1),row.getInt(2),row.getInt(3)));
        }
    }
    void run(Player p,String[] a)throws SQLException{
        r.inWorld(p);
        if(a[0].equals("mend")){mend(p);return;}
        RealmService.usage(a.length==2 || a.length==3,"smith 庄园 [登记|派工]");
        var m=r.manors.register.manor(a[1]);
        if(a.length==3){
            require(r.manors.register.manages(m,p.getUniqueId().toString()) || (a[2].equals("派工") && r.families.life.stewards(p.getUniqueId().toString(),m.id(),"铁工")),"请由业主、公产持印人或受托的出师铁匠派工");
            if(a[2].equals("登记")){
                var f=fixtures(m,p.getTargetBlockExact(6));var b=f.anvil;
                require(r.rooms.site(m.id(),"私柜")==null || !r.rooms.site(m.id(),"私柜").equals(f.bin),"家用私柜不能兼作料桶");
                require(s.number("SELECT count(*) FROM smith_order WHERE manor=? AND stage<3",m.id())==0,"本班交割后方可改建");
                s.update("INSERT INTO smithy VALUES(?,?,?,?) ON CONFLICT(manor) DO UPDATE SET x=excluded.x,y=excluded.y,z=excluded.z",m.id(),b.getX(),b.getY(),b.getZ());
            }else{
                require(a[2].equals("派工"),"请选择登记或派工");var f=existing(m);
                require(p.getLocation().distanceSquared(f.bench)<=64,"请到铁匠铺开单");
                var inv=((Barrel)f.bin.getState()).getInventory();
                require(inv.containsAtLeast(new ItemStack(Material.IRON_INGOT),1) && inv.containsAtLeast(new ItemStack(Material.CHARCOAL),1),"料桶须存普通铁锭与木炭各一");
                require(s.number("SELECT count(*) FROM smith_order WHERE manor=? AND stage<3",m.id())==0,"上一批尚未交割");
                s.atomic(()->{
                    s.update("INSERT INTO smith_order(manor,status) VALUES(?,'待领料')",m.id());
                    r.labor.reserve(m.id(),4,"锻造/"+s.number("SELECT last_insert_rowid()"));return null;
                });
            }
        }
        String state=s.value("SELECT status FROM smith_order WHERE manor=? ORDER BY id DESC LIMIT 1",m.id());
        show(p,m.name()+" · 铁匠铺",List.of(text("铁砧东设料桶、西设高炉，北侧留通道。一锭铁、一份木炭、四个本庄工时可打一份桥路铁件。"),
                text("最近订单　"+(state==null?"尚未派工":state)),text("修路时瞄准已登记货线缺口的支撑面，主手持铁件，副手持木板。")),
                List.of(),List.of(command("登记眼前铁砧","realm smith "+m.id()+" 登记"),command("派一班打铁","realm smith "+m.id()+" 派工"),command("修补眼前路面","realm mend"),command("公共养路簿","realm works")),2);
    }
    ItemStack brace(){
        var item=new ItemStack(Material.IRON_INGOT);
        item.editMeta(meta->{meta.getPersistentDataContainer().set(brace,PersistentDataType.BYTE,(byte)1);meta.customName(Component.text("桥路铁件"));});
        return item;
    }
    HaulNetwork.Point gap(Player p){
        var hit=p.rayTraceBlocks(5);require(hit!=null && hit.getHitBlock()!=null && hit.getHitBlockFace()!=null,"请瞄准缺口旁的支撑面");
        var b=hit.getHitBlock().getRelative(hit.getHitBlockFace());require(!b.equals(p.getLocation().getBlock()),"请站在缺口旁办理");var point=new HaulNetwork.Point(b.getX(),b.getY(),b.getZ());
        require(b.isEmpty() && r.publicRoutes.values().stream().anyMatch(route->!route.sea() && route.path().contains(point)),"请瞄准登记陆路缺口旁的支撑面");
        return point;
    }
    private void mend(Player p)throws SQLException{
        var point=gap(p);var b=r.roads.block(point);
        String title=s.owner(b.getX()>>4,b.getZ()>>4);require(title==null || s.title(title).seal().equals(p.getUniqueId().toString()),"领地工程须由本领实际持印人验收");
        var inv=p.getInventory();var iron=inv.getItemInMainHand();var wood=inv.getItemInOffHand();
        require(iron.getType()==Material.IRON_INGOT && iron.getItemMeta().getPersistentDataContainer().has(brace,PersistentDataType.BYTE) && Tag.PLANKS.isTagged(wood.getType()),"主手持锻造铁件，副手持木板");
        var material=wood.getType();b.setType(material,false);
        if(!r.roads.road(point)){b.setType(Material.AIR,false);throw new IllegalArgumentException("请先修好桥墩、邻侧桥面和通行净空");}
        iron.subtract();wood.subtract();inv.setItemInMainHand(iron);inv.setItemInOffHand(wood);
        r.works.materials(p,point,material);
        p.sendMessage("消耗一份铁件和一块木板，路面已验通。");
    }
    private void tick(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try{
            for(String id:r.diplomacy.column("SELECT id FROM smith_order WHERE stage<3 ORDER BY id")){
                try{work(Integer.parseInt(id));}catch(IllegalArgumentException e){
                    s.update("UPDATE smith_order SET status=? WHERE id=? AND status<>?",e.getMessage(),id,e.getMessage());
                }
            }
        }catch(SQLException ex){r.plugin.getLogger().log(Level.WARNING,"铁匠订单待复核",ex);}
    }
    private void work(int id)throws SQLException{
        String manor;int stage,blows;
        try(var q=s.sql("SELECT manor,stage,blows FROM smith_order WHERE id=?",id);var row=q.executeQuery()){
            if(!row.next())return;manor=row.getString(1);stage=row.getInt(2);blows=row.getInt(3);
        }
        var m=r.manors.register.manor(manor);if(!r.world.isChunkLoaded(m.x()>>4,m.z()>>4))return;
        var f=existing(m);String crew="smith/"+manor;
        var home=f.anvil.getChunk();r.crews.staff(home,crew,"庄园铁匠",f.loading);var team=r.crews.team(home,crew);
        require(team!=null,"等候原工班");var npc=team.porter();
        Location target=stage==1?f.bench:f.loading;
        if(npc.getLocation().distanceSquared(target)>.35){walking.toward(npc,target,1);return;}
        var bin=(Barrel)f.bin.getState();var bag=new PorterCargo(npc,null);
        if(stage==0){
            if(bin.getPersistentDataContainer().getOrDefault(input,PersistentDataType.INTEGER,-1)!=id){
                var inv=bin.getSnapshotInventory();require(inv.removeItem(new ItemStack(Material.IRON_INGOT),new ItemStack(Material.CHARCOAL)).isEmpty(),"料桶缺铁锭或木炭");
                bin.getPersistentDataContainer().set(input,PersistentDataType.INTEGER,id);require(bin.update(false,false),"料桶已改建");
            }
            bag.setItem(0,new ItemStack(Material.IRON_INGOT));r.world.save(true);
            s.update("UPDATE smith_order SET stage=1,status='工人锻造中' WHERE id=?",id);return;
        }
        if(stage==1){
            require(bag.getItem(0)!=null,"等候本班铁料");npc.swingMainHand();
            r.world.playSound(f.anvil.getLocation(),Sound.BLOCK_ANVIL_USE,.6f,1f);
            r.world.spawnParticle(Particle.CRIT,f.anvil.getLocation().add(.5,1,.5),8,.1,.1,.1);
            if(blows<2)s.update("UPDATE smith_order SET blows=blows+1 WHERE id=?",id);
            else{
                var finished=brace();
                bag.setItem(0,finished);r.world.save(true);s.update("UPDATE smith_order SET stage=2,blows=3,status='搬运铁件' WHERE id=?",id);
            }
            return;
        }
        if(bin.getPersistentDataContainer().getOrDefault(output,PersistentDataType.INTEGER,-1)!=id){
            var finished=bag.getItem(0);require(finished!=null && finished.getItemMeta().getPersistentDataContainer().has(brace),"等候本班铁件");
            require(bin.getSnapshotInventory().addItem(finished.clone()).isEmpty(),"料桶已满");
            bin.getPersistentDataContainer().set(output,PersistentDataType.INTEGER,id);require(bin.update(false,false),"料桶已改建");
        }
        bag.setItem(0,null);r.world.save(true);s.update("UPDATE smith_order SET stage=3,status='铁件已交仓' WHERE id=?",id);
    }
}
