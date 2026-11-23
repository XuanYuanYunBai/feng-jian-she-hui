package social.feudal.realm;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

final class FirstHolding implements Listener {
    private final RealmService realm;
    private final RealmStore store;
    private final NamespacedKey siteKey;

    FirstHolding(RealmService realm) {
        this.realm=realm;
        store=realm.store;
        siteKey=new NamespacedKey(realm.plugin,"first_holding_site");
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        var p=event.getPlayer();
        if(!p.hasPlayedBefore() || site(p)!=null)
            p.sendMessage(Component.text("《封建社会》从百人聚落开始　[打开经营簿 /realm]",NamedTextColor.GOLD)
                    .clickEvent(ClickEvent.runCommand("/realm")));
    }

    private String manor(String actor)throws SQLException {
        return store.value("SELECT id FROM manor WHERE owner=? ORDER BY cottage DESC,rowid LIMIT 1",actor);
    }

    private int[] site(Player p) {
        return p.getPersistentDataContainer().get(siteKey,PersistentDataType.INTEGER_ARRAY);
    }

    static Charters.Bounds yard(int x,int z) { return new Charters.Bounds(x-4,z-4,x+4,z+4); }

    void border(Charters.Bounds b) {
        var border=realm.world.getWorldBorder();
        require(border.isInside(new Location(realm.world,b.west()+.5,0,b.north()+.5)) &&
                border.isInside(new Location(realm.world,b.east()+.5,0,b.south()+.5)),"这块院地超出了世界边界");
    }

    private SettlementRegister.Ground ground(int x,int z){
        if(!realm.world.isChunkLoaded(x>>4,z>>4))return SettlementRegister.Ground.UNSEEN;
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        for(int[] offset:new int[][]{{0,0},{-4,-4},{-4,4},{4,-4},{4,4}}){
            var top=realm.world.getHighestBlockAt(x+offset[0],z+offset[1],org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
            var material=top.getType();
            if(!material.isSolid() || top.isLiquid() || org.bukkit.Tag.LOGS.isTagged(material)
                    || material==org.bukkit.Material.CACTUS || material==org.bukkit.Material.MAGMA_BLOCK)
                return SettlementRegister.Ground.UNSUITABLE;
            low=Math.min(low,top.getY());high=Math.max(high,top.getY());
        }
        return high-low<=2?SettlementRegister.Ground.DRY:SettlementRegister.Ground.UNSUITABLE;
    }

    void run(Player p,String[] args)throws SQLException {
        realm.inWorld(p);
        if(!store.ready()){
            show(p,"等待开放庄籍",List.of(text("本世界尚未登记。请服主执行 /realm init 开放庄籍，然后再打开手册。")),List.of(),List.of(),1);
            return;
        }
        String actor=p.getUniqueId().toString(),id=manor(actor);
        String action=args.length==0?"start":args[0];
        if(id!=null){
            if(action.equals("firstmill")){
                require(store.value("SELECT id FROM mill WHERE manor=? LIMIT 1",id)==null,"本庄已有水磨，请打开安家手册");
                String wheel="w_"+p.getUniqueId().toString().replace("-","").substring(0,20);
                realm.mills.run(p,new String[]{"watermill",id,wheel});
            }else production(p,realm.manors.register.manor(id));
            return;
        }
        switch(action){
            case "seekland" -> {
                var l=p.getLocation();var wb=realm.world.getWorldBorder();var c=wb.getCenter();
                double radius=wb.getSize()/2;
                int[] point=realm.manors.register.nearestYard(actor,l.getBlockX(),l.getBlockZ(),
                        c.getX()-radius,c.getZ()-radius,c.getX()+radius,c.getZ()+radius,this::ground);
                require(point!=null,"附近十六个区块内未找到合适的地籍空位，请沿边界移动后再查");
                p.getPersistentDataContainer().set(siteKey,PersistentDataType.INTEGER_ARRAY,point);
            }
            case "stakehome","stakebench" -> {
                var block=p.getLocation().getBlock();
                if(action.equals("stakebench")){
                    block=p.getTargetBlockExact(5);
                    require(block!=null && (block.getType()==org.bukkit.Material.CRAFTING_TABLE || block.getType()==org.bukkit.Material.LECTERN),
                            "请在五格内瞄准庄厅的工作台或讲台");
                }else require(block.isPassable() && !block.isLiquid(),"请站在院内干燥的地面定厅位");
                require(block.getRelative(0,-1,0).getType().isSolid(),"厅位下方须有坚实地面");
                int x=block.getX(),z=block.getZ(),y=block.getY();
                var bounds=yard(x,z);border(bounds);realm.manors.register.available(actor,bounds);
                p.getPersistentDataContainer().set(siteKey,PersistentDataType.INTEGER_ARRAY,new int[]{x,y,z});
            }
            case "firstdeed" -> {
                var pos=site(p);require(pos!=null && pos.length==3,"先在院心定厅位");
                require(p.getLocation().distanceSquared(new Location(realm.world,pos[0]+.5,pos[1]+.5,pos[2]+.5))<=64,"请回厅位八格内查验");
                String home="h_"+p.getUniqueId().toString().replace("-","").substring(0,20);
                if(store.value("SELECT id FROM manor WHERE id=?",home)!=null)home="hf_"+realm.families.book.family(actor);
                String name=args.length>1?String.join(" ",java.util.Arrays.copyOfRange(args,1,args.length)):p.getName()+"庄";
                var m=new SettlementRegister.Manor(home,name,actor,pos[0],pos[1],pos[2],yard(pos[0],pos[2]),100,0,true);
                border(m.estate());int capacity=realm.manors.inspect(m);realm.manors.register.estate(m,capacity);realm.changed();
                p.getPersistentDataContainer().remove(siteKey);production(p,m);return;
            }
            case "firstname" -> {
                DiplomacyDesk.form(p,"给首庄命名","在厅位八格内提交。登记后入住 100 人，围界与粮桶仍由你维护。","realm firstdeed",
                        List.of(new DiplomacyDesk.Field("name","庄名",p.getName()+"庄",30)));return;
            }
            case "clearhome" -> p.getPersistentDataContainer().remove(siteKey);
            case "start" -> {}
            default -> throw new IllegalArgumentException("请先立庄，然后登记水磨");
        }
        guide(p);
    }

    private void guide(Player p)throws SQLException{
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();var pos=site(p);
        String title;
        if(pos==null || pos.length==2){
            title="安家手册 · 寻一处庄址";
            body.add(text("从首批 100 名居民开始。先找一块可以建九格见方院子、附近有水的地，再备床和口粮。"));
            if(pos!=null){
                var l=p.getLocation();int dx=pos[0]-l.getBlockX(),dz=pos[1]-l.getBlockZ();
                body.add(text((ground(pos[0],pos[1])==SettlementRegister.Ground.DRY?"已查地表，候选院心":"地表待勘察，候选院心")+"　X "+pos[0]+"，Z "+pos[1]+"\n从脚下向"+(dx>=0?"东":"西")+Math.abs(dx)+" 格、向"+(dz>=0?"南":"北")+Math.abs(dz)+" 格。按 F3 可对照坐标。"));
            }
            body.add(text("优先推荐已加载的平缓干地。候选地不保留，抵达后可沿岸另选平地，站到院心定厅位。"));
            buttons.add(command("查附近可建地","realm seekland"));buttons.add(command("在脚下定厅位","realm stakehome"));
        }else{
            title="安家手册 · 围院备粮";
            var b=yard(pos[0],pos[2]);
            body.add(text("厅位　"+pos[0]+"，"+pos[1]+"，"+pos[2]+"\n围界　X "+b.west()+" 至 "+b.east()+"，Z "+b.north()+" 至 "+b.south()+"。"));
            body.add(text("平地示例：外圈 31 段栅栏和 1 扇栅栏门，四角封好。院内建有屋顶、墙、门和室内通道的民房，内放 1 张床，厅位放工作台，紧贴工作台一侧放木桶，存入 4 个面包。"));
            body.add(text("第一批口粮：把磨石立在圆石或石头上，主手拿麦，潜行右键逐份磨粉。主手叠三份粉，在邻水木台阶上分三次右键揉面；把面团放上点燃的营火烤八秒。12 份麦可备齐四个面包。"));
            var draft=new SettlementRegister.Manor("","","",pos[0],pos[1],pos[2],b,100,0,true);
            if(p.getLocation().distanceSquared(new Location(realm.world,pos[0]+.5,pos[1]+.5,pos[2]+.5))<=64){
                try{realm.manors.inspect(draft);body.add(text("现场查验通过，可以登记首庄。"));buttons.add(command("命名并登记","realm firstname"));}
                catch(IllegalArgumentException ex){body.add(text("现场待办　"+ex.getMessage()));}
            }else body.add(text("回厅位八格内可以现场查验。"));
            buttons.add(command("重新查验","realm start"));buttons.add(command("改在脚下定址","realm stakehome"));
        }
        buttons.add(command("用眼前已有庄厅定址","realm stakebench"));
        if(pos!=null)buttons.add(command("清除选址","realm clearhome"));
        buttons.add(command("我的经营簿","realm journal"));buttons.add(command("家族档案","realm family"));buttons.add(command("建筑图谱","realm buildings"));buttons.add(command("政治图册","realm atlas"));
        show(p,title,body,List.of(),buttons,2);
    }

    private void production(Player p,SettlementRegister.Manor m)throws SQLException {
        String mill=store.value("SELECT id FROM mill WHERE manor=? ORDER BY rowid LIMIT 1",m.id());
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        body.add(text(m.name()+"　｜　在住 "+m.residents()+" 人\n厅位　"+m.x()+"，"+m.y()+"，"+m.z()));
        String title="安家手册 · 第一袋面粉";
        if(mill==null){
            body.add(text("在庄内平地建水磨：磨石东西各贴一个木桶，西桶存种、东桶存粮。北侧留可站立的入口；磨石南侧两格内留水、装活板门作轮叶。"));
            body.add(text("把自种或购得的小麦放进东桶（三份可制一份面包），走近并瞄准磨石，登记后即可派一班磨粉，四季皆可。"));
            buttons.add(command("登记眼前水磨","realm firstmill"));
        }else{
            boolean done=store.number("SELECT count(*) FROM farmhand WHERE mill=? AND delivered_flour=1",mill)>0;
            if(done){
                title="安家手册 · 首批产出已入仓";
                body.add(text("磨工已把小麦粉送进磨石东侧粮桶。手持三份粉，在邻水木台阶上分三次右键按揉。持面团右键点燃的营火，八秒后拾取面包，放回厅旁赈济桶。增建民房、足额供粮可自然增长；200 人并连续赈济一季后可申请建领。"));
            }else{
                body.add(text("在东桶放一份小麦，到磨坊十格内派一班磨粉。磨工会把制成的小麦粉放回东桶；缺麦或仓满时查看田务。"));
                if(store.number("SELECT count(*) FROM farmhand WHERE mill=? AND role='磨粉' AND season=?",mill,realm.manors.register.season())==0)
                    buttons.add(command("派一班磨粉","realm farmjob "+mill+" 磨粉"));
                buttons.add(command("复核入仓","realm start"));
            }
            buttons.add(command("水磨田务","realm mill "+mill));
        }
        buttons.add(command("庄园档案","realm manor "+m.id()));buttons.add(command("我的经营簿","realm journal"));buttons.add(command("家族档案","realm family"));buttons.add(command("建筑图谱","realm buildings"));buttons.add(command("政治图册","realm atlas"));
        show(p,title,body,List.of(),buttons,2);
    }
}
