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
    private final CarrierTolls carriers;
    final BorderCourt court;
    private final Map<String,social.feudal.transport.HaulNetwork.Gate> gates=new HashMap<>();
    private final Map<String,Set<String>> waiting=new HashMap<>();
    final RealmStore s;
    CrossingOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;court=new BorderCourt(s);carriers=new CarrierTolls(realm);
        try(var q=s.sql("SELECT id,x,y,z,nx,nz FROM crossing");var rs=q.executeQuery()){
            while(rs.next())remember(new social.feudal.transport.HaulNetwork.Gate(rs.getString(1),
                    new social.feudal.transport.HaulNetwork.Point(rs.getInt(2),rs.getInt(3),rs.getInt(4)),rs.getInt(5),rs.getInt(6)));
        }
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::tick,20,20);
    }
    private void remember(social.feudal.transport.HaulNetwork.Gate gate){
        gates.put(gate.id(),gate);realm.roads.remember(gate);
    }
    private boolean open(Block b){
        var p=social.feudal.transport.HaulNetwork.Point.of(b);
        for(var g:gates.values())if(g.post().equals(p))return realm.roads.open(g);
        return false;
    }
    private Barrel till(String id,Block post){
        var g=gates.get(id);return (Barrel)post.getRelative(-g.nx(),0,-g.nz()).getState();
    }
    void clearance(social.feudal.transport.HaulNetwork.Point p,String bill,social.feudal.transport.TransportCrew.Team team)throws SQLException{
        var gate=realm.roads.gate(p);if(gate==null)return;
        String id=gate.id();
        boolean paid=s.number("SELECT count(*) FROM passage WHERE bridge=? AND bill=?",id,bill)>0;
        if(!paid){
            boolean reserved=s.value("SELECT id FROM carrier_toll WHERE bridge=? AND bill=?",id,bill)!=null;
            if(!reserved && s.number("SELECT fee FROM crossing WHERE id=?",id)==0)paid=true;
            else paid=carriers.settle(id,bill,team,till(id,post(id)));
        }
        if(paid){
            var entries=waiting.get(id);if(entries!=null)entries.remove(bill);return;
        }
        waiting.computeIfAbsent(id,unused->new LinkedHashSet<>()).add(bill);
        throw new IllegalArgumentException("运输队在 "+id+" 等候付费，请在亭内选择运单 "+bill);
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
                if(court.observe(a[0],now,active,attack,defend))realm.changed();
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
        body.add(text("收费权　"+s.title(owner).name()+"　｜　每次 "+s.number("SELECT fee FROM crossing WHERE id=?",id)+" "+coin(id).label));
        body.add(text("在柜桶首格放入本次路费，现场核账。过往收据按当时权利人留档。"));
        var buttons=new ArrayList<io.papermc.paper.registry.data.dialog.ActionButton>();
        buttons.add(command("确认本次路费","realm toll "+id));
        buttons.add(command("一金兑十二银","realm exchange "+id+" 银"));
        buttons.add(command("十二银兑一金","realm exchange "+id+" 金"));
        for(String bill:waiting.getOrDefault(id,Set.of()))
            buttons.add(command("代付运单 "+bill,"realm paybill "+id+" "+bill));
        String actor=p.getUniqueId().toString();
        var title=s.title(owner);
        if(actor.equals(title.lawful()) && actor.equals(title.seal()))
            {
                buttons.add(command("颁发定次免税特许","realm tollform "+id));
                buttons.add(command("调整币种及费率","realm rateform "+id));
            }
        try(var query=s.sql("""
                SELECT g.id,t.name,t.lawful,t.seal,g.remaining FROM bridge_privilege g
                JOIN title t ON t.id=g.holder WHERE g.bridge=? AND g.remaining>0 ORDER BY g.id""",id);
                var rows=query.executeQuery()){
            while(rows.next()){
                body.add(text(rows.getString(2)+"　免税余 "+rows.getInt(5)+" 次"));
                if(actor.equals(rows.getString(3)) && actor.equals(rows.getString(4)))
                    buttons.add(command("代表"+rows.getString(2)+"免税通行","realm passage "+id+" "+rows.getInt(1)));
            }
        }
        for(String n:realm.diplomacy.column("SELECT id FROM feud WHERE bridge=? ORDER BY id DESC LIMIT 6",id))
            buttons.add(command("争议 #"+n,"realm conflict "+n));
        show(p,s.value("SELECT name FROM crossing WHERE id=?",id),body,List.of(),buttons,2);
    }
    private Block counter(Player p,String id)throws SQLException{
        var block=post(id);
        require(p.getLocation().distanceSquared(block.getLocation())<=36 && open(block),"请到可通行的桥亭六格内");
        return block;
    }
    private TollCoin coin(String id)throws SQLException{return TollCoin.parse(s.value("SELECT currency FROM crossing WHERE id=?",id));}
    private void pay(Player p,String id,String bill)throws SQLException{
        var block=counter(p,id);
        String owner=s.value("SELECT owner FROM crossing WHERE id=?",id),actor=p.getUniqueId().toString();
        int fee=s.number("SELECT fee FROM crossing WHERE id=?",id);long now=Instant.now().getEpochSecond();
        var barrel=till(id,block);
        var receipt=new NamespacedKey(realm.plugin,"toll_receipt");recover(id,barrel,receipt);
        if(bill==null)court.privileges.checkPassage(id,actor,now);
        else require(s.value("SELECT id FROM carrier_toll WHERE bridge=? AND bill=?",id,bill)==null && waiting.getOrDefault(id,Set.of()).contains(bill) && s.value("SELECT id FROM passage WHERE bridge=? AND bill=?",id,bill)==null,"运单已缴或未在此等候");
        var currency=coin(id);
        if(fee==0){
            s.update("INSERT INTO passage(bridge,owner,payer,paid,at,currency,bill) VALUES(?,?,?,0,?,?,?)",id,owner,actor,now,currency.name(),bill);
            p.sendMessage("本次免费通行");return;
        }
        var inv=barrel.getSnapshotInventory();var item=inv.getItem(0);var coin=currency.stack(1);
        require(item!=null && item.isSimilar(coin) && item.getAmount()>=fee,"请把 "+fee+" 枚"+currency.label+"放入柜桶首格");
        int destination=-1;
        for(int k=1;k<inv.getSize();k++){
            var pile=inv.getItem(k);if(pile==null || pile.isSimilar(coin) && pile.getAmount()+fee<=64){destination=k;break;}
        }
        require(destination>=0,"收费柜已满");var pile=inv.getItem(destination);
        inv.setItem(destination,currency.stack(fee+(pile==null?0:pile.getAmount())));
        inv.setItem(0,item.getAmount()==fee?null:currency.stack(item.getAmount()-fee));
        // The physical movement and its retry record are saved together in the same barrel.
        barrel.getPersistentDataContainer().set(receipt,PersistentDataType.STRING,owner+" "+actor+" "+fee+" "+now+" "+currency.name()+(bill==null?"":" "+bill));
        require(barrel.update(),"柜桶已改建");realm.world.save(true);
        recover(id,till(id,block),receipt);
        p.sendMessage(s.title(owner).name()+" 的桥亭实收 "+fee+" "+currency.label);
    }
    private void recover(String id,Barrel bin,NamespacedKey key)throws SQLException{
        String saved=bin.getPersistentDataContainer().get(key,PersistentDataType.STRING);
        if(saved==null)return;String[] row=saved.split(" ");
        String bill=row.length>5?row[5]:null;
        s.update("""
            INSERT OR IGNORE INTO passage(bridge,owner,payer,paid,at,currency,bill) SELECT ?,?,?,?,?,?,? WHERE NOT EXISTS
            (SELECT 1 FROM passage WHERE bridge=? AND payer=? AND at=? AND bill IS ?)""",id,row[0],row[1],Integer.parseInt(row[2]),
            Long.parseLong(row[3]),row.length>4?row[4]:"GOLD",bill,id,row[1],Long.parseLong(row[3]),bill);
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
                usage(a.length==5 || a.length==6,"bridge 亭号 名称 国家 [金|银] 金额");
                var b=p.getTargetBlockExact(6);require(b!=null,"请瞄准路边讲台");
                var g=realm.roads.surveyGate(a[1],b);var currency=a.length==6?TollCoin.parse(a[4]):TollCoin.GOLD;
                s.atomic(()->{
                    court.bridge(actor,a[1],a[2],a[3],b.getX(),b.getY(),b.getZ(),Integer.parseInt(a[a.length-1]));
                    s.update("UPDATE crossing SET nx=?,nz=?,currency=? WHERE id=?",g.nx(),g.nz(),currency.name(),a[1]);return null;
                });remember(g);realm.changed();archive(p,a[1]);
            }
            case "purse" -> {usage(a.length==1,"purse");carriers.fund(p);}
            case "exchange" -> {usage(a.length==3,"exchange 亭号 金|银");counter(p,a[1]);TollCoin.exchange(p,TollCoin.parse(a[2]));}
            case "rateform" -> {
                usage(a.length==2,"rateform 亭号");counter(p,a[1]);s.politics.represents(s.value("SELECT owner FROM crossing WHERE id=?",a[1]),actor);
                DiplomacyDesk.form(p,"调整通行费","新费率从下一笔缴费生效。零为免费通行。","realm rate "+a[1],List.of(
                    new DiplomacyDesk.Field("coin","币种（金或银）",coin(a[1]).label,4),new DiplomacyDesk.Field("fee","每次金额",""+s.number("SELECT fee FROM crossing WHERE id=?",a[1]),2)));
            }
            case "rate" -> {usage(a.length==4,"rate 亭号 金|银 金额");counter(p,a[1]);court.price(actor,a[1],TollCoin.parse(a[2]),Integer.parseInt(a[3]));realm.changed();archive(p,a[1]);}
            case "paybill" -> {usage(a.length==3,"paybill 亭号 运单");pay(p,a[1],a[2]);archive(p,a[1]);}
            case "tollform" -> {
                usage(a.length==2 || a.length==3,"tollform 桥亭 [受许国]");counter(p,a[1]);
                String owner=s.value("SELECT owner FROM crossing WHERE id=?",a[1]);
                s.politics.represents(owner,actor);
                if(a.length==2){
                    var buttons=new ArrayList<io.papermc.paper.registry.data.dialog.ActionButton>();
                    for(String nation:realm.diplomacy.column("SELECT id FROM nation WHERE id<>? ORDER BY id",owner))
                        buttons.add(command(s.title(nation).name(),"realm tollform "+a[1]+" "+nation));
                    show(p,"选择享有通行权的国家",List.of(text("特许绑定本桥，收费权易手后继续有效。")),List.of(),buttons,2);
                }else DiplomacyDesk.form(p,"给"+s.title(a[2]).name()+"颁发免税特许","该国持印人每次在桥亭核销一次。可授 1–64 次，用尽后可续授。",
                        "realm tollgrant "+a[1]+" "+a[2],List.of(new DiplomacyDesk.Field("uses","可免次数","4",2)));
            }
            case "tollgrant" -> {
                usage(a.length==4,"tollgrant 桥亭 受许国 次数");counter(p,a[1]);
                court.privileges.grant(actor,a[1],a[2],Integer.parseInt(a[3]));realm.changed();archive(p,a[1]);
            }
            case "passage" -> {
                usage(a.length==3,"passage 桥亭 特许号");var block=counter(p,a[1]);
                var bin=till(a[1],block);
                recover(a[1],bin,new NamespacedKey(realm.plugin,"toll_receipt"));
                int left=court.privileges.redeem(actor,a[1],Integer.parseInt(a[2]),Instant.now().getEpochSecond());
                realm.changed();p.sendMessage("已登记免税通行，特许余 "+left+" 次");archive(p,a[1]);
            }
            case "toll" -> {usage(a.length==2,"toll 桥亭");pay(p,a[1],null);}
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
