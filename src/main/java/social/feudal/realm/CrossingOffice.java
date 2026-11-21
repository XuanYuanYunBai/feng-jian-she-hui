package social.feudal.realm;

import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.logging.Level;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import static social.feudal.realm.RealmService.usage;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

/** Public hearings and staffed, passable crossing objectives share the same territorial register. */
final class CrossingOffice implements Listener {
    private final RealmService realm;
    final BorderCourt court;
    final RealmStore s;
    CrossingOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;court=new BorderCourt(s);
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::tick,20,20);
    }
    private static boolean open(Block post){
        if(post.getType()!=Material.LECTERN || post.getRelative(BlockFace.WEST).getType()!=Material.BARREL)return false;
        // Inspect the local deck and both approaches. The signing desk sits immediately west of the centre lane.
        for(int dx=1;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            var feet=post.getRelative(dx,0,dz);
            if(!feet.isPassable() || !feet.getRelative(BlockFace.UP).isPassable() || !feet.getRelative(0,2,0).isPassable()
                    || !feet.getRelative(BlockFace.DOWN).getType().isSolid())return false;
        }
        return true;
    }
    void tick(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try(var q=s.sql("""
                SELECT f.id,c.x,c.y,c.z FROM feud f JOIN crossing c ON c.id=f.bridge
                WHERE f.state NOT IN ('结案','和解')""");var r=q.executeQuery()){
            var observations=new ArrayList<int[]>();
            while(r.next())observations.add(new int[]{r.getInt(1),r.getInt(2),r.getInt(3),r.getInt(4)});
            for(var a:observations){
                var world=realm.world;boolean loaded=world.isChunkLoaded(a[1]>>4,a[3]>>4);
                boolean active=loaded && open(world.getBlockAt(a[1],a[2],a[3]));boolean attack=false,defend=false;
                long now=Instant.now().getEpochSecond();
                long start=Long.parseLong(s.value("SELECT start+slot*86400 FROM feud WHERE id=?",a[0]));
                if(active && now>=start && now<start+BorderCourt.WINDOW){
                    var centre=new Location(world,a[1]+.5,a[2]+.5,a[3]+.5);
                    for(var p:world.getNearbyPlayers(centre,10)){
                        if(p.isDead() || p.getGameMode()==GameMode.SPECTATOR)continue;
                        String side=s.value("SELECT side FROM levy JOIN title ON title.id=levy.nation WHERE feud=? AND actor=? AND seal=actor AND lawful=actor",a[0],p.getUniqueId().toString());
                        if(side==null)continue;
                        String actor=p.getUniqueId().toString();
                        if(s.number("SELECT supplied FROM levy WHERE feud=? AND actor=?",a[0],actor)<=now){
                            var ration=new ItemStack(Material.BREAD);
                            if(!p.getInventory().containsAtLeast(ration,1))continue;
                            p.getInventory().removeItem(ration);
                            s.update("UPDATE levy SET supplied=? WHERE feud=? AND actor=?",now+60,a[0],actor);
                        }
                        attack|="原告".equals(side);defend|="守方".equals(side);
                    }
                }
                court.observe(a[0],now,active,attack,defend);
            }
        }catch(SQLException e){realm.plugin.getLogger().log(Level.WARNING,"桥亭战事结算失败",e);}
    }
    private Block post(String id)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM crossing WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"桥亭未登记");
            require(realm.world.isChunkLoaded(r.getInt(1)>>4,r.getInt(3)>>4),"请到桥亭现场办理");
            return realm.world.getBlockAt(r.getInt(1),r.getInt(2),r.getInt(3));
        }
    }
    @EventHandler(ignoreCancelled=true) public void use(PlayerInteractEvent e)throws SQLException{
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        var b=e.getClickedBlock();if(b==null || b.getWorld()!=realm.world || b.getType()!=Material.LECTERN)return;
        var id=s.value("SELECT id FROM crossing WHERE x=? AND y=? AND z=?",b.getX(),b.getY(),b.getZ());
        if(id!=null){e.setCancelled(true);archive(e.getPlayer(),id);}
    }
    private void archive(Player p,String id)throws SQLException{
        var owner=s.value("SELECT owner FROM crossing WHERE id=?",id);require(owner!=null,"桥亭未登记");
        var body=new ArrayList<io.papermc.paper.registry.data.dialog.body.DialogBody>();
        body.add(text("收费权　"+s.title(owner).name()+"　｜　每次 "+s.number("SELECT fee FROM crossing WHERE id=?",id)+" 金粒"));
        body.add(text("在亭西的木桶首格放入本次路费，现场核账。过往收据按当时权利人留档。"));
        var buttons=new ArrayList<io.papermc.paper.registry.data.dialog.ActionButton>();
        buttons.add(command("确认本次路费","realm toll "+id));
        for(String n:realm.diplomacy.column("SELECT id FROM feud WHERE bridge=? ORDER BY id DESC LIMIT 6",id))
            buttons.add(command("争议 #"+n,"realm conflict "+n));
        show(p,s.value("SELECT name FROM crossing WHERE id=?",id),body,List.of(),buttons,2);
    }
    private void pay(Player p,String id)throws SQLException{
        var block=post(id);require(p.getLocation().distanceSquared(block.getLocation())<=36 && open(block),"请到可通行的桥亭六格内");
        String owner=s.value("SELECT owner FROM crossing WHERE id=?",id),actor=p.getUniqueId().toString();
        int fee=s.number("SELECT fee FROM crossing WHERE id=?",id);long now=Instant.now().getEpochSecond();
        var barrel=(Barrel)block.getRelative(BlockFace.WEST).getState();
        var receipt=new NamespacedKey(realm.plugin,"toll_receipt");recover(id,barrel,receipt);
        require(s.value("SELECT id FROM passage WHERE bridge=? AND payer=? AND at>?",id,actor,now-10)==null,"已签发此次通行回执");
        var inv=barrel.getSnapshotInventory();var item=inv.getItem(0);var coin=new ItemStack(Material.GOLD_NUGGET);
        require(item!=null && item.isSimilar(coin) && item.getAmount()>=fee,"请将实物金粒放入西侧柜桶首格");
        int destination=-1;
        for(int k=1;k<inv.getSize();k++){
            var pile=inv.getItem(k);if(pile==null || pile.isSimilar(coin) && pile.getAmount()+fee<=64){destination=k;break;}
        }
        require(destination>=0,"收费柜已满");var pile=inv.getItem(destination);
        inv.setItem(destination,new ItemStack(Material.GOLD_NUGGET,fee+(pile==null?0:pile.getAmount())));
        inv.setItem(0,item.getAmount()==fee?null:new ItemStack(Material.GOLD_NUGGET,item.getAmount()-fee));
        // The physical movement and its retry record are saved together in the same barrel.
        barrel.getPersistentDataContainer().set(receipt,PersistentDataType.STRING,owner+" "+actor+" "+fee+" "+now);
        require(barrel.update(),"柜桶已改建");realm.world.save(true);
        recover(id,(Barrel)block.getRelative(BlockFace.WEST).getState(),receipt);
        p.sendMessage(s.title(owner).name()+" 的桥亭实收 "+fee+" 金粒");
    }
    private void recover(String id,Barrel bin,NamespacedKey key)throws SQLException{
        String saved=bin.getPersistentDataContainer().get(key,PersistentDataType.STRING);
        if(saved==null)return;String[] row=saved.split(" ");
        s.update("""
            INSERT INTO passage(bridge,owner,payer,paid,at) SELECT ?,?,?,?,? WHERE NOT EXISTS
            (SELECT 1 FROM passage WHERE bridge=? AND payer=? AND at=?)""",id,row[0],row[1],Integer.parseInt(row[2]),Long.parseLong(row[3]),id,row[1],Long.parseLong(row[3]));
    }
    private void conflict(Player p,int n)throws SQLException{
        var body=new ArrayList<io.papermc.paper.registry.data.dialog.body.DialogBody>();
        try(var q=s.sql("SELECT * FROM feud WHERE id=?",n);var r=q.executeQuery()){
            require(r.next(),"未知战争照会");long start=r.getLong("start");
            body.add(text(s.title(r.getString("attacker")).name()+" 与 "+s.title(r.getString("defender")).name()+"　｜　"+r.getString("state")));
            body.add(text("争议桥亭　"+r.getString("bridge")+"　｜　理由　"+r.getString("reason")));
            for(int d=0;d<3;d++)body.add(text((d==2?"备用":"争夺")+"　"+Instant.ofEpochSecond(start+d*BorderCourt.DAY)+"　持续 90 分钟"));
            body.add(text("现场控制　"+r.getInt("progress")+" / 1200 秒。请携口粮到桥头。"));
        }
        var buttons=new ArrayList<io.papermc.paper.registry.data.dialog.ActionButton>();
        for(String id:realm.diplomacy.column("SELECT n.id FROM nation n JOIN title t ON n.id=t.id WHERE lawful=seal AND seal=?",p.getUniqueId().toString())){
            buttons.add(command(s.title(id).name()+" · 原告","realm muster "+n+" "+id+" 原告"));
            buttons.add(command(s.title(id).name()+" · 守方","realm muster "+n+" "+id+" 守方"));
        }
        show(p,"桥税争议 #"+n,body,List.of(),buttons,2);
    }
    void run(Player p,String[] a)throws SQLException{
        realm.inWorld(p);String actor=p.getUniqueId().toString();
        switch(a[0]){
            case "crossing" -> {usage(a.length==2,"crossing 桥亭");archive(p,a[1]);}
            case "bridge" -> {
                usage(a.length==5,"bridge 桥亭ID 桥名 国家 每次金粒");var b=p.getTargetBlockExact(6);
                require(b!=null && open(b),"请瞄准讲台，西侧设收费桶，东侧留三格宽七格长的通道");
                court.bridge(actor,a[1],a[2],a[3],b.getX(),b.getY(),b.getZ(),Integer.parseInt(a[4]));archive(p,a[1]);
            }
            case "toll" -> {usage(a.length==2,"toll 桥亭");pay(p,a[1]);}
            case "notice" -> {
                usage(a.length>=5,"notice 原告国 桥亭 UTC开场时间（如2026-12-01T20:00:00Z） 理由");
                long start;try{start=Instant.parse(a[3]).getEpochSecond();}catch(DateTimeException e){throw new IllegalArgumentException("请填写有效 UTC 时间");}
                int n=court.declare(actor,a[1],a[2],String.join(" ",Arrays.copyOfRange(a,4,a.length)),start,Instant.now().getEpochSecond());
                Bukkit.broadcast(net.kyori.adventure.text.Component.text("已发布桥税照会 #"+n+"，使用 /realm conflict "+n+" 查阅"));
                conflict(p,n);
            }
            case "conflict" -> {usage(a.length==2,"conflict 照会号");conflict(p,Integer.parseInt(a[1]));}
            case "muster" -> {usage(a.length==4,"muster 照会号 国家 原告|守方");court.enlist(Integer.parseInt(a[1]),actor,a[2],a[3]);p.sendMessage("出席争夺名册已登记");}
            case "peace" -> {usage(a.length==3,"peace 照会号 本国");court.settle(Integer.parseInt(a[1]),a[2],actor);conflict(p,Integer.parseInt(a[1]));}
        }
    }
}
