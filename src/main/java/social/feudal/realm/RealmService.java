package social.feudal.realm;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

public final class RealmService implements CommandExecutor,AutoCloseable {
    final JavaPlugin plugin;
    final World world;
    final RealmStore store;
    final RealmDialogs dialogs;
    final DiplomacyDesk diplomacy;
    final Contributions contributions;
    final ManorOffice manors;
    final MillOffice mills;
    final CrossingOffice crossings;
    final MarketOffice markets;
    final Council council;
    final CharterOffice charters;
    final DepotMarkers markers;
    final WarehouseFixtures fixtures;
    final TaxOffice taxes;
    final social.feudal.transport.TransportCrew crews;
    private MapView view;
    private PoliticalMap renderer;
    int mapRevision;

    public RealmService(JavaPlugin plugin,World world,Path path,social.feudal.transport.TransportCrew crews) throws SQLException {
        this.crews=crews;this.plugin=plugin;this.world=world;store=new RealmStore(path);
        store.checkWorld(world.getUID().toString());
        contributions=new Contributions(store,path.resolveSibling("freight.sqlite"));
        dialogs=new RealmDialogs(this);diplomacy=new DiplomacyDesk(this);
        charters=new CharterOffice(this);
        fixtures=new WarehouseFixtures(this);
        taxes=new TaxOffice(this);
        council=new Council(this);manors=new ManorOffice(this);mills=new MillOffice(this);crossings=new CrossingOffice(this);markets=new MarketOffice(this);
        if(store.ready()) restoreMap();
        if(Bukkit.getPluginManager().getPlugin("BlueMap")!=null)
            Bukkit.getScheduler().runTaskTimer(plugin,new BlueAtlas(this),40,100);
        markers=new DepotMarkers(this);new SeatEnvoys(this);
        Objects.requireNonNull(plugin.getCommand("realm")).setExecutor(this);
    }
    private void restoreMap() throws SQLException {
        String saved=store.value("SELECT v FROM setting WHERE k='map'");
        if(saved!=null)view=Bukkit.getMap(Integer.parseInt(saved));
        if(view==null){
            view=Bukkit.createMap(world);
            store.update("INSERT OR REPLACE INTO setting VALUES('map',?)",view.getId());
        }
        view.getRenderers().forEach(view::removeRenderer);
        renderer=new PoliticalMap(store);view.addRenderer(renderer);
    }
    static void usage(boolean condition,String syntax){RealmStore.require(condition,"用法 /realm "+syntax);}
    static Player player(CommandSender s){
        RealmStore.require(s instanceof Player,"请在游戏中操作");return (Player)s;
    }
    String actor(String name){
        Player p=Bukkit.getPlayerExact(name);
        RealmStore.require(p!=null,"指定玩家须在线");return p.getUniqueId().toString();
    }
    String display(String actor){
        if(actor==null)return "待任";
        try {
            var p=Bukkit.getOfflinePlayer(UUID.fromString(actor));
            return p.getName()==null?actor:p.getName();
        } catch(IllegalArgumentException ex){return actor;}
    }
    void inWorld(Player p){RealmStore.require(p.getWorld().equals(world),"请前往登记的主世界");}
    @Override public boolean onCommand(CommandSender s,Command command,String label,String[] a){
        try {
            String action=a.length==0 && s instanceof Player && store.ready()?"atlas":a.length==0?"help":a[0].toLowerCase(Locale.ROOT);
            if(a.length>0)a[0]=action;
            if(action.equals("help")){
                s.sendMessage("/realm atlas 政治图册 · map 手持图 · here 地方志 · depots 税仓坐标");
                s.sendMessage("/realm ledger 税账 · collect 仓名 · deposit 交箱 · treasury 头衔 · gift 头衔 粮数");
                s.sendMessage("/realm desk 税仓ID、stock 税仓ID、vault 头衔ID · 瞄准改建后的讲台或仓箱登记");
                s.sendMessage("/realm preview 或 grant 授封 · estimate 或 extend 追加辖地 · estate 授益");
                s.sendMessage("/realm acts 查文书 · heir 提名 · vacate 交位 · appoint 任命 · yield 交印 · resist 抗命");
                s.sendMessage("/realm support 联署 · petition 请袭 · confirm 准袭 · offer 拟约 · ratify 签署");
                s.sendMessage("/realm estates 庄园公议 · diplomacy 外交院 · map country 国家图 · map empire 帝国图");
                s.sendMessage("/realm watermill 庄园ID 磨坊ID · field 登记田地 · farmjob 派工 · bridge 登记桥亭");
                s.sendMessage("管理员 /realm init [在线皇帝名]  设立初始成员，留空由皇廷署理");
                return true;
            }
            if(action.equals("init")){
                RealmStore.require(s.hasPermission("feudal.realm.admin"),"需要初始化世界的权限");
                usage(a.length<=2,"init [在线皇帝名]");
                var l=world.getSpawnLocation();
                store.initialize(world.getUID().toString(),l.getBlockX()>>4,l.getBlockZ()>>4,a.length==2?actor(a[1]):"npc-皇廷署理");
                council.succession.sync();taxes.openPeriod(1);restoreMap();changed();markers.refresh();s.sendMessage("河湾帝国及初始成员已登记。使用 /realm atlas");return true;
            }
            RealmStore.require(store.ready(),"帝国尚未建立，管理员使用 /realm init 在线君主名");
            switch(action){
                case "stall","market","bidform","bid","sell" -> markets.run(player(s),a);
                case "bridge","crossing","toll","notice","conflict","muster","peace" -> crossings.run(player(s),a);
                case "mill","watermill","field","farmjob","millup" -> mills.run(player(s),a);
                case "estates","estateform","settle","manor","surveyclaim","found","frontier","hearing","object","retract","cancelclaim","enact","permit","peer","seatvault","capital" -> manors.run(player(s),a);
                case "diplomacy","org","document","debate","compose","cedeform","cede","cancelcede","agenda","treaty","sign","orgsign","propose","vote","withdraw","stand","pledge","accountfor","pledgeform" -> diplomacy.run(player(s),a);
                case "acts","relinquish","heir","appoint","support","petition","confirm","vacate","yield","resist","offer","ratify" -> council.execute(s,a);
                case "patent","register","preview","grant","extend","estimate","estate" -> charters.execute(s,a);
                case "equip" -> {usage(a.length==3,"equip 税仓ID speed|pack|mule");taxes.couriers.equip(player(s),taxes.ledger.depot(a[1]),a[2]);}
                case "crew" -> {usage(a.length==2,"crew 税仓ID");dialogs.crew(player(s),taxes.ledger.depot(a[1]));}
                case "desk","stock","vault" -> {usage(a.length==2,action+" 登记代号");fixtures.register(player(s),action,a[1]);}
                case "visit","ledger","collect","reissue","deposit","treasury","gift" -> taxes.execute(s,a);
                case "atlas" -> {
                    if(s instanceof Player p)dialogs.directory(p,a.length==2?Integer.parseInt(a[1]):1);
                    else for(var t:store.titles())detail(s,t,t.x(),t.z());
                }
                case "brief" -> {usage(a.length==2,"brief 头衔ID");dialogs.brief(player(s),store.title(a[1]));}
                case "accounts" -> {
                    usage(a.length<=3,"accounts [税仓ID] [税期]");
                    if(a.length>=2)taxes.ledger.depot(a[1]);
                    if(a.length==1)dialogs.warehouses(player(s),taxes.ledger.period(),1);
                    else dialogs.accounts(player(s),a.length==3?Integer.parseInt(a[2]):taxes.ledger.period(),a[1]);
                }
                case "warehouses" -> {
                    usage(a.length==3,"warehouses 税期 页码");
                    dialogs.warehouses(player(s),Integer.parseInt(a[1]),Integer.parseInt(a[2]));
                }
                case "map","local" -> {
                    Player p=player(s);inWorld(p);
                    if(action.equals("local"))renderer.local(p.getUniqueId(),p.getLocation().getBlockX()>>4,p.getLocation().getBlockZ()>>4);
                    else {usage(a.length==1 || a.length==2 && Set.of("empire","country").contains(a[1]),"map [country|empire]");
                        clearPreview(p);renderer.empire(p.getUniqueId(),a.length==2 && a[1].equals("empire"));}
                    equipMap(p);s.sendMessage("已展开疆域图，右键查阅政治图册");
                }
                case "here" -> {
                    var p=player(s);inWorld(p);var l=p.getLocation();int x=l.getBlockX()>>4,z=l.getBlockZ()>>4;
                    String owner=store.owner(x,z);
                    if(owner==null)s.sendMessage("区块 "+x+", "+z+" · 未登记土地");
                    else detail(s,store.title(owner),x,z);
                }
                case "title" -> {usage(a.length==2,"title 头衔ID");var t=store.title(a[1]);detail(s,t,t.x(),t.z());}
                case "depot" -> {
                    usage(a.length==3,"depot 税仓ID 仓名");var p=player(s);inWorld(p);
                    var l=p.getLocation();store.foundDepot(p.getUniqueId().toString(),a[1],a[2],l.getBlockX()>>4,l.getBlockZ()>>4);
                    markers.refresh();s.sendMessage("税仓已登记，下个税期开始入账");
                    markers.complete(taxes.ledger.depot(a[1]));
                }
                case "fixtures" -> {usage(a.length==2,"fixtures 税仓ID");dialogs.fixtures(player(s),taxes.ledger.depot(a[1]));}
                case "build" -> {
                    var p=player(s);inWorld(p);var chunk=p.getLocation().getChunk();
                    var d=store.depots().stream().filter(row->row.x()==chunk.getX() && row.z()==chunk.getZ()).findFirst()
                            .orElseThrow(()->new IllegalArgumentException("此区块没有登记税仓"));
                    String holder=store.title(store.owner(chunk.getX(),chunk.getZ())).seal();
                    RealmStore.require(p.hasPermission("feudal.realm.admin") || p.getUniqueId().toString().equals(holder),"请由该领持印人补建");
                    markers.complete(d);s.sendMessage("税仓已建成");
                }
                case "depots" -> {
                    for(var d:store.depots()){
                        if(world.isChunkLoaded(d.x(),d.z()) && markers.counter(d.x(),d.z())!=null){
                            var desk=markers.counter(d.x(),d.z());
                            s.sendMessage(d.name()+" · 账台 "+desk.getBlockX()+"，"+desk.getBlockY()+"，"+desk.getBlockZ()+" · "+d.id());
                        }else s.sendMessage(d.name()+" · 所在区块 "+d.x()+"，"+d.z()+" · "+d.id());
                    }
                }
                default -> throw new IllegalArgumentException("未知操作，使用 /realm help");
            }
        }catch(NumberFormatException ex){s.sendMessage("区块坐标、税期与数量须为有效整数");}
        catch(IllegalArgumentException ex){s.sendMessage(Component.text(ex.getMessage(),NamedTextColor.RED));}
        catch(SQLException ex){
            plugin.getLogger().log(Level.SEVERE,"政治账本操作失败",ex);
            s.sendMessage("账本读取或写入失败，请查看服务器日志");
        }
        return true;
    }
    private void equipMap(Player p){
        var inv=p.getInventory();
        for(int slot=0;slot<36;slot++)if(isAtlas(inv.getItem(slot))){
            if(slot<9)inv.setHeldItemSlot(slot);
            else{
                ItemStack old=inv.getItemInMainHand();inv.setItemInMainHand(inv.getItem(slot));inv.setItem(slot,old);
            }
            return;
        }
        int free=inv.firstEmpty();RealmStore.require(free>=0,"请腾出一个物品栏槽位");
        var item=new ItemStack(Material.FILLED_MAP);var meta=(MapMeta)item.getItemMeta();
        meta.setMapView(view);meta.displayName(Component.text("帝国疆域图"));item.setItemMeta(meta);
        inv.setItem(free,inv.getItemInMainHand());inv.setItemInMainHand(item);
    }
    void preview(Player p,Charters.Bounds b,String caption){
        renderer.select(p.getUniqueId(),b,caption);
        equipMap(p);p.sendMessage("疆域图已标出拟授选区与面积");
    }
    void clearPreview(Player p){if(renderer!=null)renderer.forget(p.getUniqueId());}
    boolean isAtlas(ItemStack item){
        return view!=null && item!=null && item.getItemMeta() instanceof MapMeta m && m.hasMapView() && m.getMapView()!=null && m.getMapView().getId()==view.getId();
    }
    void changed(){mapRevision++;if(renderer!=null)renderer.invalidate();}
    private void detail(CommandSender s,RealmStore.Title t,int x,int z)throws SQLException {
        s.sendMessage(Component.text("◆ "+t.name()+"  ·  区块 "+x+", "+z,NamedTextColor.GOLD));
        s.sendMessage("国家 "+store.polity(t.id())+"  |  "+t.rank()+" · "+t.charter());
        s.sendMessage("直接上级 "+(t.parent()==null?"最高席位":store.title(t.parent()).name())+"  |  代号 "+t.id());
        s.sendMessage("法定任命 "+display(t.lawful())+"  |  实际持印 "+display(t.seal()));
        String beneficiary=store.value("SELECT tax_right.title FROM tax_right JOIN depot ON depot.id=tax_right.depot WHERE depot.x=? AND depot.z=?",x,z);
        s.sendMessage("税权 "+(beneficiary==null?t.name():store.title(beneficiary).name())+"公库");
    }
    @Override public void close()throws SQLException{
        if(view!=null && renderer!=null)view.removeRenderer(renderer);
        taxes.saveTime();store.close();
    }
}
