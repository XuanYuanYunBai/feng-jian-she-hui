package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.realm.RealmService.usage;
import static social.feudal.ui.Menus.*;

final class ManorOffice implements Listener {
    final RealmService realm;
    final SettlementRegister register;
    final RealmStore s;
    private final int seasonSeconds;
    private final long noticeSeconds;
    ManorOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;register=new SettlementRegister(s);
        seasonSeconds=Math.max(60,realm.plugin.getConfig().getInt("realm.season-seconds",21600));
        noticeSeconds=Math.max(60,realm.plugin.getConfig().getLong("realm.notice-seconds",86400));
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::advance,200,200);
    }
    private void advance(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try{
            if(!s.ready())return;
            int elapsed=s.number("SELECT v FROM setting WHERE k='season-seconds'")+10;
            boolean newSeason=elapsed>=seasonSeconds;
            int season=register.season();
            s.atomic(()->{
                if(newSeason){
                    s.update("INSERT OR REPLACE INTO setting VALUES('season',?)",season+1);
                    s.politics.expiry(season+1);
                }
                s.update("INSERT OR REPLACE INTO setting VALUES('season-seconds',?)",newSeason?0:elapsed);
                for(String id:realm.diplomacy.column("SELECT id FROM manor")){
                    var m=register.manor(id);
                    if(!loaded(m.estate()))continue;
                    var store=foodStore(m);
                    if(store==null || !stocked(store,m.households())){
                        s.update("UPDATE manor SET settled=0,service=0 WHERE id=?",id);continue;
                    }
                    // Count observed active time only. Unloaded manors cannot claim unobserved service.
                    s.update("UPDATE manor SET settled=settled+10,service=(settled+10)/? WHERE id=?",seasonSeconds,id);
                    if(newSeason){
                        store.removeItem(new ItemStack(Material.BREAD,m.households()));
                        int places=beds(m);
                        if(places>m.households() && stocked(store,m.households()+1))
                            s.update("UPDATE manor SET households=households+1 WHERE id=?",id);
                    }
                }
                return null;
            });
        }catch(SQLException ex){realm.plugin.getLogger().log(Level.WARNING,"庄园季账失败",ex);}
    }
    private boolean loaded(Charters.Bounds b){
        for(int x=b.west()>>4;x<=b.east()>>4;x++)for(int z=b.north()>>4;z<=b.south()>>4;z++)
            if(!realm.world.isChunkLoaded(x,z))return false;
        return true;
    }
    private static boolean stocked(Inventory inv,int households){return inv.containsAtLeast(new ItemStack(Material.BREAD),households+3);}
    private Inventory foodStore(SettlementRegister.Manor m){
        var hall=realm.world.getBlockAt(m.x(),m.y(),m.z());
        if(hall.getType()!=Material.LECTERN && !(m.cottage() && hall.getType()==Material.CRAFTING_TABLE))return null;
        for(var face:List.of(BlockFace.NORTH,BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST)){
            var bin=hall.getRelative(face);
            if(m.estate().contains(bin.getX(),bin.getZ()) && bin.getType()==Material.BARREL && bin.getState(false) instanceof Container c)return c.getInventory();
        }
        return null;
    }
    private int beds(SettlementRegister.Manor m){
        // Locate bed heads through live blocks only during registration and season changes.
        int count=0;var b=m.estate();
        for(int x=b.west()+1;x<b.east();x++)for(int z=b.north()+1;z<b.south();z++)
            for(int y=Math.max(realm.world.getMinHeight(),m.y()-2);y<=Math.min(realm.world.getMaxHeight()-1,m.y()+2);y++){
                var block=realm.world.getBlockAt(x,y,z);
                if(Tag.BEDS.isTagged(block.getType()) && block.getBlockData() instanceof Bed bed && bed.getPart()==Bed.Part.HEAD)count++;
            }
        return count;
    }
    private boolean edge(int x,int y,int z){
        for(int dy=-2;dy<=2;dy++){
            var mat=realm.world.getBlockAt(x,y+dy,z).getType();
            if(Tag.FENCES.isTagged(mat)||Tag.FENCE_GATES.isTagged(mat)||Tag.WALLS.isTagged(mat)||Tag.LEAVES.isTagged(mat)||mat==Material.WATER)return true;
        }return false;
    }
    void inspect(SettlementRegister.Manor m){
        var b=m.estate();
        require(b.east()>=b.west() && b.south()>=b.north() && (long)b.east()-b.west()<96 && (long)b.south()-b.north()<96
                && b.contains(m.x(),m.z()),"请在厅内登记不超过 96 格长宽的庄园");
        require(loaded(b),"请走近庄园，待围界区块加载");
        Inventory food=foodStore(m);
        require(food!=null,"在登记的厅位放"+(m.cottage()?"工作台或讲台":"讲台")+"，紧贴其水平一侧放赈济木桶");
        require(stocked(food,m.households()),"赈济桶须存 "+(m.households()+3)+" 个面包（每户一份及三份公粮）");
        boolean gate=false;
        for(int x=b.west();x<=b.east();x++)for(int z=b.north();z<=b.south();z++){
            if(x!=b.west() && x!=b.east() && z!=b.north() && z!=b.south())continue;
            require(edge(x,m.y(),z),"围界缺口位于 "+x+"，"+z);
            if(!gate)for(int h=-2;h<=2;h++)if(Tag.FENCE_GATES.isTagged(realm.world.getBlockAt(x,m.y()+h,z).getType()))gate=true;
        }
        require(gate,"请为庄园设一座可通行的栅栏门");
        require(beds(m)>=m.households(),"每户需要一张实际的床");
    }
    private static Charters.Bounds bounds(String[] a,int i){
        return new Charters.Bounds(Integer.parseInt(a[i]),Integer.parseInt(a[i+1]),Integer.parseInt(a[i+2]),Integer.parseInt(a[i+3]));
    }
    private void hall(Player p,SettlementRegister.Manor m){
        require(p.getLocation().distanceSquared(new Location(realm.world,m.x()+.5,m.y()+.5,m.z()+.5))<=64,"请到庄宅办事厅八格内");
    }
    void archive(Player p,String id)throws SQLException{
        var m=register.manor(id);var buttons=new ArrayList<ActionButton>();var body=new ArrayList<DialogBody>();
        body.add(text("业主　"+realm.display(m.owner())+"　｜　在住 "+m.households()+" 户"));
        body.add(text("厅堂　"+m.x()+"，"+m.y()+"，"+m.z()+"　｜　完成赈济 "+m.service()+" 季"));
        body.add(text("厅旁木桶每季供给每户一份面包，并留三份赈济。增设床位和供粮可接纳迁户。"));
        String nation=s.value("SELECT nation FROM capital WHERE manor=?",id);
        if(nation!=null)body.add(text("首都　"+s.title(nation).name()));
        for(String mill:realm.diplomacy.column("SELECT id FROM mill WHERE manor=?",id))buttons.add(command("水磨 · "+mill,"realm mill "+mill));
        if(m.owner().equals(p.getUniqueId().toString()) && s.value("SELECT id FROM nation WHERE id=?",id)==null){
            buttons.add(command("申请建领","realm surveyclaim "+id));
        }
        for(String n:realm.diplomacy.column("SELECT id FROM claim WHERE manor=? ORDER BY id DESC LIMIT 8",id))
            buttons.add(command("边界公示 #"+n,"realm hearing "+n));
        buttons.add(command("公议与庄园","realm estates"));show(p,m.name(),body,List.of(),buttons,2);
    }
    void directory(Player p)throws SQLException{
        var buttons=new ArrayList<ActionButton>();
        for(String id:realm.diplomacy.column("SELECT id FROM manor ORDER BY name"))buttons.add(command(register.manor(id).name(),"realm manor "+id));
        for(String n:realm.diplomacy.column("SELECT id FROM claim WHERE state='公示中' ORDER BY id DESC"))buttons.add(command("待审边界 #"+n,"realm hearing "+n));
        buttons.add(command("测量并立庄","realm estateform"));buttons.add(command("返回纹章院","realm atlas"));
        show(p,"地籍与地方公议",List.of(text("在当地围建庄园，设门、讲台、赈济木桶和床位。稳定六户并赈济一季后，可将庄宅登记为新领邦首都。")),List.of(),buttons,2);
    }
    @EventHandler(ignoreCancelled=true) public void use(PlayerInteractEvent e)throws SQLException{
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null)return;
        var b=e.getClickedBlock();
        if(b.getWorld()!=realm.world || (b.getType()!=Material.LECTERN && !(b.getType()==Material.CRAFTING_TABLE && e.getPlayer().isSneaking())))return;
        String id=s.value("SELECT id FROM manor WHERE x=? AND y=? AND z=?",b.getX(),b.getY(),b.getZ());
        if(id!=null){e.setCancelled(true);archive(e.getPlayer(),id);}
    }
    void run(Player p,String[] a)throws SQLException{
        realm.inWorld(p);String actor=p.getUniqueId().toString();var l=p.getLocation();
        switch(a[0]){
            case "estates" -> directory(p);
            case "estateform" -> {
                var fields=new ArrayList<DiplomacyDesk.Field>();fields.add(new DiplomacyDesk.Field("id","庄园代号","",24));fields.add(new DiplomacyDesk.Field("name","庄园名","",30));
                fields.add(new DiplomacyDesk.Field("bounds","围界方块坐标 西 北 东 南","",60));
                DiplomacyDesk.form(p,"立庄测量","瞄准庄宅讲台，填写实际围界。每户一床，讲台旁设赈济桶。","realm settle",fields);
            }
            case "settle" -> {
                usage(a.length==7,"settle 代号 名称 西 北 东 南（方块）");
                var block=p.getTargetBlockExact(6);require(block!=null && block.getType()==Material.LECTERN,"请瞄准庄宅讲台");
                var m=new SettlementRegister.Manor(a[1],a[2],actor,block.getX(),block.getY(),block.getZ(),bounds(a,3),3,0,false);
                inspect(m);register.estate(m);realm.changed();archive(p,m.id());
            }
            case "manor" -> {usage(a.length==2,"manor 庄园");archive(p,a[1]);}
            case "surveyclaim" -> {
                usage(a.length==2,"surveyclaim 庄园");var m=register.manor(a[1]);hall(p,m);var b=m.estate();
                DiplomacyDesk.form(p,"拟定建领公示","首都设在本庄。选区须含庄宅，单案最多 36 区块。","realm found "+m.id(),List.of(
                        new DiplomacyDesk.Field("status","政治地位",s.politics.choiceOpen()?"独立":"入帝",4),
                        new DiplomacyDesk.Field("bounds","区块坐标 西 北 东 南",(b.west()>>4)+" "+(b.north()>>4)+" "+(b.east()>>4)+" "+(b.south()>>4),70)));
            }
            case "found","frontier" -> {
                usage(a.length==7,a[0]+" 庄园 入帝|独立（开拓填头衔ID） 西 北 东 南");
                var m=register.manor(a[1]);hall(p,m);inspect(m);boolean founding=a[0].equals("found");
                int n=register.propose(actor,m.id(),founding?m.id():a[2],founding?a[2]:"开拓",bounds(a,3),System.currentTimeMillis()/1000,noticeSeconds);
                realm.changed();p.sendMessage("边界公示 #"+n+" 已张贴");hearing(p,n);
            }
            case "hearing" -> {usage(a.length==2,"hearing 公示号");hearing(p,Integer.parseInt(a[1]));}
            case "object" -> {usage(a.length>=3,"object 公示号 理由");register.object(Integer.parseInt(a[1]),actor,l.getBlockX()>>4,l.getBlockZ()>>4,String.join(" ",Arrays.copyOfRange(a,2,a.length)));p.sendMessage("已记入本区块异议");}
            case "retract" -> {usage(a.length==2,"retract 公示号");s.update("DELETE FROM land_objection WHERE claim=? AND actor=?",Integer.parseInt(a[1]),actor);p.sendMessage("已撤回本人异议");}
            case "cancelclaim","enact" -> {
                usage(a.length==2,a[0]+" 公示号");var c=register.claim(Integer.parseInt(a[1]));var m=register.manor(c.manor());hall(p,m);
                require(m.owner().equals(actor),"须由申请人办理");
                if(a[0].equals("enact")){inspect(m);register.resolve(c.id(),actor,System.currentTimeMillis()/1000);}
                else require(s.update("UPDATE claim SET state='撤案' WHERE id=? AND state='公示中'",c.id())==1,"此案已结");
                realm.council.succession.sync();realm.changed();hearing(p,c.id());
            }
            case "permit" -> {usage(a.length==6,"permit 受许头衔 西 北 东 南");register.permit(actor,a[1],bounds(a,2));p.sendMessage("边境开拓封契已签发");}
            case "capital" -> {
                usage(a.length==3,"capital 国家 新庄园");var m=register.manor(a[2]);hall(p,m);inspect(m);
                var bin=realm.fixtures.bin(m.x()>>4,m.z()>>4,"vault",a[1]);
                require(bin!=null,"请先瞄准新公库箱子，使用 /realm seatvault 国家 庄园");
                register.moveCapital(a[1],a[2],actor);realm.changed();realm.dialogs.brief(p,s.title(a[1]));
            }
            case "seatvault" -> {
                usage(a.length==3,"seatvault 国家 庄园");var m=register.manor(a[2]);hall(p,m);
                s.politics.represents(a[1],actor);
                require(m.owner().equals(actor) && a[1].equals(s.owner(m.x()>>4,m.z()>>4)),"须在本国直辖的自有庄园办理");
                var b=p.getTargetBlockExact(6);require(b!=null && m.estate().contains(b.getX(),b.getZ()) && (b.getX()>>4)==(m.x()>>4) && (b.getZ()>>4)==(m.z()>>4),"请瞄准庄宅所在区块的库箱");
                require(b.getType()==Material.BARREL || b.getType()==Material.CHEST,"公库使用桶或箱子");
                realm.fixtures.bind(b,"vault",a[1]);p.sendMessage("已登记治所公库，粮食须实际搬运");
            }
            case "peer" -> {
                usage(a.length>=4,"peer 领邦 申请|批准|驳回 理由");register.peer(actor,a[1],a[2],String.join(" ",Arrays.copyOfRange(a,3,a.length)));
                if(a[2].equals("申请") && s.title("crown").seal().equals("npc-皇廷署理"))
                    register.peer("npc-皇廷署理",a[1],"批准","核准本庄公议与建领文书");
                realm.dialogs.brief(p,s.title(a[1]));
            }
            default -> throw new IllegalArgumentException("未知地籍操作");
        }
    }
    private void hearing(Player p,int id)throws SQLException{
        var c=register.claim(id);var b=c.area();var buttons=new ArrayList<ActionButton>();
        var body=new ArrayList<DialogBody>();
        body.add(text(register.manor(c.manor()).name()+" · "+c.type()+" · "+c.state()));
        body.add(text("区块边界　"+b.west()+"，"+b.north()+" 至 "+b.east()+"，"+b.south()+"　｜　"+b.area()+" 块"));
        body.add(text("公示至 "+java.time.Instant.ofEpochSecond(c.until())+"，且须跨过一个生产季。当前异议 "+s.number("SELECT count(*) FROM land_objection WHERE claim=?",id)+" 件。"));
        if(c.state().equals("公示中")){
            buttons.add(command("登记所在区块异议","realm object "+id+" 请求复核当地权属"));
            buttons.add(command("撤回本人异议","realm retract "+id));
            if(register.manor(c.manor()).owner().equals(p.getUniqueId().toString())){
                buttons.add(command("公示期满裁定","realm enact "+id));buttons.add(command("撤回申请","realm cancelclaim "+id));
            }
        }
        buttons.add(command("庄园档案","realm manor "+c.manor()));
        show(p,"边界公示 #"+id,body,List.of(),buttons,2);
    }
}
