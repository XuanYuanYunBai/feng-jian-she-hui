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
    final RealmBooks books;
    final Council council;
    final CharterOffice charters;
    private final DepotMarkers markers;
    final TaxOffice taxes;
    private MapView view;
    private PoliticalMap renderer;

    public RealmService(JavaPlugin plugin,World world,Path path) throws SQLException {
        this.plugin=plugin;this.world=world;store=new RealmStore(path);
        store.checkWorld(world.getUID().toString());
        books=new RealmBooks(this);
        charters=new CharterOffice(this);
        taxes=new TaxOffice(this);
        council=new Council(this);
        if(store.ready()) restoreMap();
        markers=new DepotMarkers(this);
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
                s.sendMessage("/realm preview 或 grant 授封 · estimate 或 extend 追加辖地 · estate 授益");
                s.sendMessage("/realm acts 查文书 · heir 提名 · vacate 交位 · appoint 任命 · yield 交印 · resist 抗命");
                s.sendMessage("/realm support 联署 · petition 请袭 · confirm 准袭 · offer 拟约 · ratify 签署");
                s.sendMessage("管理员 /realm init 在线君主名  以主世界出生区块建立帝国");
                return true;
            }
            if(action.equals("init")){
                RealmStore.require(s.hasPermission("feudal.realm.admin"),"需要建国权限");
                usage(a.length==2,"init 在线君主名");
                var l=world.getSpawnLocation();
                store.initialize(world.getUID().toString(),l.getBlockX()>>4,l.getBlockZ()>>4,actor(a[1]));
                council.succession.sync();taxes.ledger.begin(1);restoreMap();markers.refresh();s.sendMessage("河湾帝国已建国。使用 /realm atlas");return true;
            }
            RealmStore.require(store.ready(),"帝国尚未建立，管理员使用 /realm init 在线君主名");
            switch(action){
                case "acts","relinquish","heir","appoint","support","petition","confirm","vacate","yield","resist","offer","ratify" -> council.execute(s,a);
                case "patent","register","preview","grant","extend","estimate","estate" -> charters.execute(s,a);
                case "visit","ledger","collect","reissue","deposit","treasury","gift" -> taxes.execute(s,a);
                case "atlas" -> {
                    if(s instanceof Player p)books.directory(p);
                    else for(var t:store.titles())detail(s,t,t.x(),t.z());
                }
                case "brief" -> {usage(a.length==2,"brief 头衔ID");books.brief(player(s),store.title(a[1]));}
                case "accounts" -> {
                    usage(a.length<=3,"accounts [税仓ID] [税期]");
                    if(a.length>=2)taxes.ledger.depot(a[1]);
                    books.accounts(player(s),a.length==3?Integer.parseInt(a[2]):taxes.ledger.period(),a.length>=2?a[1]:"");
                }
                case "map","local" -> {
                    Player p=player(s);inWorld(p);
                    if(action.equals("local"))renderer.local(p.getUniqueId(),p.getLocation().getBlockX()>>4,p.getLocation().getBlockZ()>>4);
                    else clearPreview(p);
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
                }
                case "depots" -> {
                    for(var d:store.depots())s.sendMessage(d.id()+" · "+d.name()+"  X "+(d.x()*16+8)+"  Z "+(d.z()*16+8));
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
    void changed(){if(renderer!=null)renderer.invalidate();}
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
