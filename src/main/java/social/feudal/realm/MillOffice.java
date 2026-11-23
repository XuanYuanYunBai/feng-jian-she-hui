package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import social.feudal.npc.NpcSkins;
import social.feudal.transport.*;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.realm.RealmService.usage;
import static social.feudal.ui.Menus.*;

/** Work orders keep their crew, grain and seed in the world. A stopped worker keeps the barrel in hand. */
final class MillOffice implements Listener {
    record Mill(String id,String manor,int x,int y,int z,int tier){
        int radius(){return 16+tier*16;}
        int workers(){return 1<<(tier-1);}
        int capacity(){return workers()*64;}
    }
    private final RealmService realm;
    private final RealmStore s;
    private final FootNavigation navigation;
    private final NamespacedKey loadTarget;
    final Bakehouse baking;
    private final KitchenDuty kitchen;
    MillOffice(RealmService r)throws SQLException{
        realm=r;s=r.store;navigation=new FootNavigation(r.plugin);loadTarget=new NamespacedKey(r.plugin,"field_target");
        s.update("CREATE TABLE IF NOT EXISTS mill(id TEXT PRIMARY KEY,manor TEXT REFERENCES manor(id),x INTEGER,y INTEGER,z INTEGER,tier INTEGER,UNIQUE(x,y,z))");
        s.update("CREATE TABLE IF NOT EXISTS mill_permit(mill TEXT PRIMARY KEY REFERENCES mill(id),title TEXT REFERENCES title(id),issuer TEXT)");
        s.update("CREATE TABLE IF NOT EXISTS field(x INTEGER,y INTEGER,z INTEGER,mill TEXT REFERENCES mill(id),PRIMARY KEY(x,y,z))");
        s.update("""
            CREATE TABLE IF NOT EXISTS farmhand(id INTEGER PRIMARY KEY,mill TEXT REFERENCES mill(id),season INTEGER,
            role TEXT,quota INTEGER NOT NULL,status TEXT NOT NULL,worker TEXT,UNIQUE(worker))""");
        if(s.number("SELECT count(*) FROM pragma_table_info('farmhand') WHERE name='delivered_flour'")==0)
            s.update("ALTER TABLE farmhand ADD COLUMN delivered_flour INTEGER NOT NULL DEFAULT 0");
        baking=new Bakehouse(r);kitchen=new KitchenDuty(r,baking,navigation);
        Bukkit.getPluginManager().registerEvents(this,r.plugin);
        Bukkit.getScheduler().runTaskTimer(r.plugin,this::tick,20,10);
    }
    private Mill get(String id)throws SQLException{
        try(var q=s.sql("SELECT * FROM mill WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"未知磨坊");return new Mill(id,r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6));
        }
    }
    private Block wheel(Mill m){return realm.world.getBlockAt(m.x(),m.y(),m.z());}
    private Location door(Mill m){return wheel(m).getRelative(BlockFace.NORTH).getLocation().add(.5,0,.5);}
    private Inventory bin(Mill m,boolean seed){
        var block=wheel(m).getRelative(seed?BlockFace.WEST:BlockFace.EAST);
        return block.getType()==Material.BARREL && block.getState(false) instanceof Barrel b?b.getInventory():null;
    }
    private boolean usable(Mill m){
        if(wheel(m).getType()!=Material.GRINDSTONE || bin(m,false)==null || bin(m,true)==null)return false;
        boolean water=false,paddle=false;
        for(int dx=-2;dx<=2;dx++)for(int dz=0;dz<=2;dz++)for(int dy=-1;dy<=0;dy++){
            var b=wheel(m).getRelative(dx,dy,dz);water|=b.getType()==Material.WATER;paddle|=Tag.TRAPDOORS.isTagged(b.getType());
        }
        return water && paddle && door(m).getBlock().isPassable() && door(m).getBlock().getRelative(BlockFace.DOWN).getType().isSolid();
    }
    private void local(Player p,Mill m)throws SQLException{
        require(p.getLocation().distanceSquared(door(m))<100,"请到磨坊办理");
        require(realm.manors.register.manages(realm.manors.register.manor(m.manor()),p.getUniqueId().toString()),"须由房主派工");
    }
    private int count(String sql,Object...args)throws SQLException{return s.number(sql,args);}
    void run(Player p,String[] a)throws SQLException{
        realm.inWorld(p);
        switch(a[0]){
            case "mill" -> {usage(a.length==2,"mill 磨坊ID");page(p,get(a[1]));}
            case "watermill" -> {
                usage(a.length==3,"watermill 庄园ID 磨坊ID");Politics.id(a[2]);
                var m=realm.manors.register.manor(a[1]);var b=p.getTargetBlockExact(6);
                require(b!=null && m.estate().contains(b.getX(),b.getZ()) && realm.manors.register.manages(m,p.getUniqueId().toString()),"请在本人庄内瞄准磨石");
                var site=new Mill(a[2],m.id(),b.getX(),b.getY(),b.getZ(),1);
                require(usable(site),"磨石东侧设粮桶、西侧设种桶、北侧留作业门，南侧两格内设水和活板轮叶");
                require(count("SELECT count(*) FROM mill WHERE id=? OR (x=? AND y=? AND z=?)",site.id(),site.x(),site.y(),site.z())==0,"磨坊代号或磨石已登记");
                s.update("INSERT INTO mill VALUES(?,?,?,?,?,1)",site.id(),m.id(),site.x(),site.y(),site.z());page(p,site);
            }
            case "millpermit" -> {
                usage(a.length==2,"millpermit 磨坊");var m=get(a[1]);String district=s.owner(m.x()>>4,m.z()>>4);
                String actor=p.getUniqueId().toString();
                require(district!=null && s.title(district).seal().equals(actor),"请由本领持印人签发磨坊特许");
                s.update("INSERT OR REPLACE INTO mill_permit VALUES(?,?,?)",m.id(),district,actor);
                p.sendMessage("已批准本磨坊扩建，业主须亲自增设工位和附仓。");
            }
            case "millup" -> {
                usage(a.length==2,"millup 磨坊");var m=get(a[1]);local(p,m);
                require(m.tier()<3 && usable(m),"先修复磨坊，最高三级");
                String district=s.owner(m.x()>>4,m.z()>>4);
                require(district!=null && (s.title(district).seal().equals(p.getUniqueId().toString()) || count("SELECT count(*) FROM mill_permit WHERE mill=? AND title=? AND issuer=?",m.id(),district,s.title(district).seal())>0),"多工班水磨须位于已建领土地，并由本领持印人批准扩建");
                int tier=m.tier()+1;
                for(int n=1;n<tier;n++)require(wheel(m).getRelative(2,n-1,0).getType()==Material.BARREL
                        && wheel(m).getRelative(-2,n-1,0).getType()==Material.COMPOSTER,"扩建东侧附仓和西侧农具工位，升三级时再加一层");
                s.update("UPDATE mill SET tier=? WHERE id=?",tier,m.id());page(p,get(m.id()));
            }
            case "fieldform" -> {
                usage(a.length==2,"fieldform 磨坊");var m=get(a[1]);var l=p.getLocation();
                String corner=l.getBlockX()+" "+l.getBlockZ();
                DiplomacyDesk.form(p,"登记磨坊托管田地","到田边填写两角的方块坐标。仅登记本庄内、磨工能步行到达的耕地。","realm field "+m.id(),
                        List.of(new DiplomacyDesk.Field("estate","地主庄园代号",m.manor(),50),
                                new DiplomacyDesk.Field("area","西 北 东 南",corner+" "+corner,60),
                                new DiplomacyDesk.Field("level","耕地方块的高度",Integer.toString((l.getBlock().getType()==Material.FARMLAND?l.getBlockY():l.getBlockY()-1)),5)));
            }
            case "field" -> {
                usage(a.length==8,"field 磨坊 地主庄园 西 北 东 南 耕地Y");var m=get(a[1]);var estate=realm.manors.register.manor(a[2]);
                require(realm.manors.register.manages(estate,p.getUniqueId().toString()),"须持田产办理托管");var b=new Charters.Bounds(Integer.parseInt(a[3]),Integer.parseInt(a[4]),Integer.parseInt(a[5]),Integer.parseInt(a[6]));
                int y=Integer.parseInt(a[7]);
                require(b.area()<=1024 && estate.estate().contains(b.west(),b.north()) && estate.estate().contains(b.east(),b.south()),"请在本庄选田，单次最多勘测 1024 平方格");
                require(usable(m) && p.getLocation().distanceSquared(door(m))<=10000,"请到磨坊附近勘界");
                var area=FootNavigation.serviceArea(door(m),m.radius());
                int n=s.atomic(()->{
                    int added=0;
                    for(var cell:area)if(cell.getY()==y+1 && b.contains(cell.getX(),cell.getZ())){
                        String owner=s.value("SELECT mill FROM field WHERE x=? AND y=? AND z=?",cell.getX(),cell.getY(),cell.getZ());
                        require(owner==null || owner.equals(m.id()),"选区已有其他磨坊的田务委托");
                        if(owner==null){s.update("INSERT INTO field VALUES(?,?,?,?)",cell.getX(),cell.getY(),cell.getZ(),m.id());added++;}
                    }
                    require(added>0,"选区没有新增的可达耕地");
                    require(count("SELECT count(*) FROM field WHERE mill=?",m.id())<=m.capacity(),"超过登记田格上限");
                    return added;
                });p.sendMessage("实际走路范围内，新登记 "+n+" 格耕地");page(p,m);
            }
            case "kitchen" -> {
                usage(a.length==3,"kitchen 磨坊 木案|营火|常设|停办");var m=get(a[1]);local(p,m);
                if(a[2].equals("常设")){
                    require(count("SELECT count(*) FROM kitchen WHERE mill=?",m.id())==2,"请先登记木案和营火");
                    s.update("INSERT OR IGNORE INTO kitchen_duty VALUES(?)",m.id());
                    standing(m);
                }else if(a[2].equals("停办"))s.update("DELETE FROM kitchen_duty WHERE mill=?",m.id());
                else kitchen.register(p,m,a[2]);
                page(p,m);
            }
            case "farmjob" -> {
                usage(a.length==3 && Set.of("收麦","补种","磨粉").contains(a[2]),"farmjob 磨坊 收麦|补种|磨粉");
                var m=get(a[1]);local(p,m);require(usable(m),"设施损坏，请复验入口、仓桶与动力");
                int season=realm.manors.register.season();
                if(a[2].equals("收麦"))require(season%4==2,"秋季可派收割工，当前为"+seasonName(season));
                if(a[2].equals("补种"))require(season%4==0,"春季可派播种工，当前为"+seasonName(season));
                require(count("SELECT count(*) FROM farmhand WHERE mill=? AND season=? AND "+(a[2].equals("磨粉")?"role='磨粉'":"role<>'磨粉'"),m.id(),season)<(a[2].equals("磨粉")?1:m.workers()),"本季相应工位已分配");
                int quota=remainingHours(m,season);
                require(quota>0,"庄内本季已无空闲工时");
                s.update("INSERT INTO farmhand(mill,season,role,quota,status) VALUES(?,?,?,?,?)",m.id(),season,a[2],quota,"已派工");page(p,m);
            }
        }
    }
    @EventHandler(ignoreCancelled=true) public void inspect(PlayerInteractEvent e)throws SQLException{
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        var b=e.getClickedBlock();if(b==null || b.getWorld()!=realm.world || b.getType()!=Material.GRINDSTONE)return;
        Player p=e.getPlayer();
        if(p.isSneaking() && p.getInventory().getItemInMainHand().getType()==Material.WHEAT){
            e.setCancelled(true);
            baking.handMill(p,b);return;
        }
        var id=s.value("SELECT id FROM mill WHERE x=? AND y=? AND z=?",b.getX(),b.getY(),b.getZ());
        if(id!=null){e.setCancelled(true);page(e.getPlayer(),get(id));}
    }
    private static String seasonName(int index){return List.of("春","夏","秋","冬").get(Math.floorMod(index,4))+"季";}
    private void page(Player p,Mill m)throws SQLException{
        var body=new ArrayList<io.papermc.paper.registry.data.dialog.body.DialogBody>();
        body.add(text(seasonName(realm.manors.register.season())+"　｜　庄园　"+realm.manors.register.manor(m.manor()).name()+"　｜　"+m.tier()+" 级水磨"));
        body.add(text("田务路程 "+m.radius()+" 格　｜　登记 "+count("SELECT count(*) FROM field WHERE mill=?",m.id())+" / "+m.capacity()+" 格　｜　田工 "+m.workers()+" 席"));
        for(var line:realm.diplomacy.column("SELECT role || ' · ' || status || '　剩余工时 ' || quota FROM farmhand WHERE mill=? AND season=? ORDER BY id",m.id(),realm.manors.register.season()))body.add(text(line));
        boolean standing=count("SELECT count(*) FROM kitchen_duty WHERE mill=?",m.id())>0;
        body.add(text("常设工班将口粮送回厅旁木桶，储足两季及三份赈粮后春播秋收。木案与营火旁须留通道。"));
        show(p,"水磨田务 · "+m.id(),body,List.of(),List.of(command("登记托管田地","realm fieldform "+m.id()),command("派一班收麦","realm farmjob "+m.id()+" 收麦"),command("派一班补种","realm farmjob "+m.id()+" 补种"),
                command("派磨工制粉","realm farmjob "+m.id()+" 磨粉"),command("登记眼前木案","realm kitchen "+m.id()+" 木案"),
                command("登记眼前营火","realm kitchen "+m.id()+" 营火"),command(standing?"下季停办口粮":"常设口粮工班","realm kitchen "+m.id()+(standing?" 停办":" 常设")),command("签发领地扩建许可","realm millpermit "+m.id()),command("验收扩建","realm millup "+m.id()),command("庄园档案","realm manor "+m.manor()),command("安家手册","realm start")),2);
    }
    private int remainingHours(Mill m,int season)throws SQLException{
        return Math.min(64,realm.labor.remaining(m.manor(),season));
    }
    private void standing(Mill m)throws SQLException{
        int season=realm.manors.register.season();
        if(count("SELECT count(*) FROM farmhand WHERE mill=? AND role='口粮' AND (season=? OR status<>'结工')",m.id(),season)>0)return;
        int hours=remainingHours(m,season);
        if(hours>0)s.update("INSERT INTO farmhand(mill,season,role,quota,status) VALUES(?,?,'口粮',?,'已派工')",m.id(),season,hours);
    }
    private void status(int id,String value)throws SQLException{
        s.update("UPDATE farmhand SET status=? WHERE id=? AND status<>?",value,id,value);
    }
    private static boolean ripe(Block b){return b.getType()==Material.WHEAT && b.getBlockData() instanceof Ageable crop && crop.getAge()==crop.getMaximumAge();}
    private static boolean sowable(Block b){return b.isEmpty() && b.getRelative(BlockFace.DOWN).getType()==Material.FARMLAND;}
    private void tick(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try{
            if(realm.world.getGameTime()%200<10)for(var key:realm.diplomacy.column("SELECT mill FROM kitchen_duty ORDER BY mill"))standing(get(key));
            for(var key:realm.diplomacy.column("SELECT id FROM farmhand WHERE status<>'结工' ORDER BY id"))work(Integer.parseInt(key));
        }catch(SQLException ex){realm.plugin.getLogger().log(Level.WARNING,"磨坊派工失败",ex);}
    }
    private void work(int id)throws SQLException{
        Mill m;String role,uuid;int quota,season;
        try(var q=s.sql("SELECT mill,role,worker,quota,season FROM farmhand WHERE id=?",id);var r=q.executeQuery()){
            if(!r.next())return;m=get(r.getString(1));role=r.getString(2);uuid=r.getString(3);quota=r.getInt(4);season=r.getInt(5);
        }
        if(!realm.world.isChunkLoaded(m.x()>>4,m.z()>>4))return;
        if(!usable(m)){status(id,"设施待修");return;}
        final String assignedRole=role;
        Mannequin npc;
        if(uuid==null){
            npc=realm.world.spawn(door(m),Mannequin.class,e->{
                e.setPersistent(true);e.setImmovable(false);e.setInvulnerable(true);e.setCollidable(false);e.setDescription(null);
                e.customName(Component.text(assignedRole.equals("口粮")?"庄园烘焙工":assignedRole.equals("磨粉")?"庄园磨工":"庄园田工"));e.setCustomNameVisible(true);NpcSkins.apply(e,"granary");
            });
            s.update("UPDATE farmhand SET worker=? WHERE id=?",npc.getUniqueId().toString(),id);
        }else if(realm.world.getEntity(UUID.fromString(uuid)) instanceof Mannequin worker)npc=worker;else return;
        var cargo=new PorterCargo(npc,null);var grain=bin(m,false);var seed=bin(m,true);
        boolean finished=quota<=0 || season!=realm.manors.register.season();
        if(role.equals("口粮")){
            var load=cargo.getItem(0);
            boolean farmLoad=load!=null && (load.getType()==Material.WHEAT || load.getType()==Material.WHEAT_SEEDS);
            if(farmLoad){
                role=season%4==0?"补种":"收麦";
            }else{
                var result=npc.getPersistentDataContainer().has(loadTarget) && !finished?"口粮储备充足":kitchen.work(id,m,npc,grain,wheel(m),finished);
                if(!result.equals("口粮储备充足") && !result.equals("缺麦与面粉")){status(id,result);return;}
                boolean harvest=season%4==2;
                if(finished || (!harvest && (season%4!=0 || !seed.contains(Material.WHEAT_SEEDS))) || next(m,harvest)==null){
                    status(id,result);return;
                }
                role=harvest?"收麦":"补种";
            }
        }
        if(cargo.getItem(0)!=null && !role.equals("补种") || finished){
            if(!arrive(npc,door(m),id,"返仓"))return;
            for(int slot=0;slot<cargo.getSize();slot++){
                var item=cargo.getItem(slot);if(item==null)continue;
                var left=(item.getType()==Material.WHEAT_SEEDS?seed:grain).addItem(item);
                cargo.setItem(slot,left.get(0));
                if(role.equals("磨粉") && item.isSimilar(baking.flour) && left.isEmpty())
                    s.update("UPDATE farmhand SET delivered_flour=1 WHERE id=? AND delivered_flour=0",id);
                if(!left.isEmpty()){status(id,"仓满待卸");return;}
            }
            if(finished){npc.remove();status(id,"结工");}return;
        }
        if(role.equals("磨粉")){
            if(!arrive(npc,door(m),id,"前往磨盘"))return;
            var wheat=new ItemStack(Material.WHEAT);
            if(!grain.containsAtLeast(wheat,1)){status(id,"缺制粉谷物");return;}
            baking.animateGrinding(npc,wheel(m));
            grain.removeItem(wheat);cargo.setItem(0,baking.flour.clone());debit(id);return;
        }
        if(role.equals("补种") && cargo.getItem(0)==null){
            if(!arrive(npc,door(m),id,"回种柜"))return;
            var unit=new ItemStack(Material.WHEAT_SEEDS);
            if(!seed.containsAtLeast(unit,1)){status(id,"缺种待播");return;}
            seed.removeItem(unit);cargo.setItem(0,unit);
        }
        Block target=null;var data=npc.getPersistentDataContainer();var xyz=data.get(loadTarget,PersistentDataType.INTEGER_ARRAY);
        if(xyz!=null && realm.world.isChunkLoaded(xyz[0]>>4,xyz[2]>>4))target=realm.world.getBlockAt(xyz[0],xyz[1],xyz[2]);
        boolean harvest=role.equals("收麦");
        if(target==null || !(harvest?ripe(target):sowable(target))){
            data.remove(loadTarget);
            target=next(m,harvest);
            if(target==null){status(id,harvest?"等候成熟或通路":"田已播或道路中断");return;}
            data.set(loadTarget,PersistentDataType.INTEGER_ARRAY,new int[]{target.getX(),target.getY(),target.getZ()});
        }
        if(!arrive(npc,target.getLocation().add(.5,0,.5),id,"去田间"))return;
        var point=target.getLocation().add(.5,.35,.5);
        var rotation=npc.getLocation().setDirection(point.toVector().subtract(npc.getEyeLocation().toVector()));
        npc.setRotation(rotation.getYaw(),rotation.getPitch());npc.swingMainHand();
        target.getWorld().spawnParticle(Particle.ITEM,point,8,.15,.12,.15,.03,new ItemStack(harvest?Material.WHEAT:Material.WHEAT_SEEDS));
        target.getWorld().playSound(point,harvest?Sound.BLOCK_CROP_BREAK:Sound.ITEM_CROP_PLANT,.55f,1f);
        if(harvest){
            int slot=0;for(var drop:target.getDrops())if(!drop.isEmpty() && slot<cargo.getSize())cargo.setItem(slot++,drop);
            target.setType(Material.AIR,false);
        }else{target.setType(Material.WHEAT,false);cargo.setItem(0,null);}
        data.remove(loadTarget);debit(id);
    }
    private Block next(Mill m,boolean harvest)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM field WHERE mill=? ORDER BY (abs(x-?)+abs(z-?)),x,z",m.id(),m.x(),m.z());var r=q.executeQuery()){
            Set<Block> accessible=null;
            while(r.next()){
                int x=r.getInt(1),z=r.getInt(3);if(!realm.world.isChunkLoaded(x>>4,z>>4))continue;
                var cell=realm.world.getBlockAt(x,r.getInt(2),z);
                if(!(harvest?ripe(cell):sowable(cell)))continue;
                if(accessible==null)accessible=FootNavigation.serviceArea(door(m),m.radius());
                if(accessible.contains(cell))return cell;
            }return null;
        }
    }
    private boolean arrive(Mannequin npc,Location target,int id,String description)throws SQLException{
        if(npc.getLocation().distanceSquared(target)<1.6)return true;
        navigation.toward(npc,target,1);status(id,description);return false;
    }
    private void debit(int id)throws SQLException{s.update("UPDATE farmhand SET quota=quota-1,status='作业中' WHERE id=? AND quota>0",id);}
}
