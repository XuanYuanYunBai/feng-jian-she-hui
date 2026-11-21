package social.feudal.realm;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.inventory.Book;
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
    private MapView view;
    private PoliticalMap renderer;

    public RealmService(JavaPlugin plugin,World world,Path path) throws SQLException {
        this.plugin=plugin;this.world=world;store=new RealmStore(path);
        store.checkWorld(world.getUID().toString());
        if(store.ready()) restoreMap();
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
            String action=a.length==0?"help":a[0].toLowerCase(Locale.ROOT);
            if(action.equals("help")){
                s.sendMessage("/realm atlas 政治图册 · map 手持图 · here 地方志 · depots 税仓坐标");
                s.sendMessage("管理员 /realm init 在线君主名  以主世界出生区块建立帝国");
                return true;
            }
            if(action.equals("init")){
                RealmStore.require(s.hasPermission("feudal.realm.admin"),"需要建国权限");
                usage(a.length==2,"init 在线君主名");
                var l=world.getSpawnLocation();
                store.initialize(world.getUID().toString(),l.getBlockX()>>4,l.getBlockZ()>>4,actor(a[1]));
                restoreMap();s.sendMessage("河湾帝国已建国。使用 /realm atlas");return true;
            }
            RealmStore.require(store.ready(),"帝国尚未建立，管理员使用 /realm init 在线君主名");
            switch(action){
                case "atlas" -> atlas(s);
                case "map" -> {
                    Player p=player(s);inWorld(p);
                    var item=new ItemStack(Material.FILLED_MAP);
                    var meta=(MapMeta)item.getItemMeta();meta.setMapView(view);
                    meta.displayName(Component.text("帝国疆域图"));item.setItemMeta(meta);
                    RealmStore.require(p.getInventory().firstEmpty()!=-1,"物品栏已满");
                    p.getInventory().addItem(item);s.sendMessage("已领取疆域图，手持查看；/realm atlas 查阅图例");
                }
                case "here" -> {
                    var p=player(s);inWorld(p);var l=p.getLocation();int x=l.getBlockX()>>4,z=l.getBlockZ()>>4;
                    String owner=store.owner(x,z);
                    if(owner==null)s.sendMessage("区块 "+x+", "+z+" · 未登记土地");
                    else detail(s,store.title(owner),x,z);
                }
                case "title" -> {usage(a.length==2,"title 头衔ID");var t=store.title(a[1]);detail(s,t,t.x(),t.z());}
                case "depots" -> {
                    for(var d:store.depots())s.sendMessage(d.id()+" · "+d.name()+"  X "+(d.x()*16+8)+"  Z "+(d.z()*16+8));
                }
                default -> throw new IllegalArgumentException("未知操作，使用 /realm help");
            }
        }catch(IllegalArgumentException ex){s.sendMessage(Component.text(ex.getMessage(),NamedTextColor.RED));}
        catch(SQLException ex){
            plugin.getLogger().log(Level.SEVERE,"政治账本操作失败",ex);
            s.sendMessage("账本读取或写入失败，请查看服务器日志");
        }
        return true;
    }
    void changed(){if(renderer!=null)renderer.invalidate();}
    private void atlas(CommandSender s)throws SQLException {
        List<Component> pages=new ArrayList<>();
        int i=0;
        for(var t:store.titles()){
            String text=(++i)+" · "+t.name()+"\n"+t.rank()+" / "+t.charter()+"\n\n驻地区块\n"+t.x()+", "+t.z()
                    +"\n\n直接上级\n"+(t.parent()==null?"帝国最高席位":store.title(t.parent()).name())
                    +"\n\n印主  "+display(t.seal())+"\n代号  "+t.id();
            pages.add(Component.text(text));if(!(s instanceof Player))s.sendMessage(text.replace('\n',' '));
        }
        if(s instanceof Player p)p.openBook(Book.book(Component.text("帝国政治图册"),Component.text("纹章院"),pages));
    }
    private void detail(CommandSender s,RealmStore.Title t,int x,int z)throws SQLException {
        s.sendMessage(Component.text("◆ "+t.name()+"  ·  区块 "+x+", "+z,NamedTextColor.GOLD));
        s.sendMessage("国家 "+store.polity(t.id())+"  |  "+t.rank()+" · "+t.charter());
        s.sendMessage("直接上级 "+(t.parent()==null?"最高席位":store.title(t.parent()).name())+"  |  代号 "+t.id());
        s.sendMessage("法定任命 "+display(t.lawful())+"  |  实际持印 "+display(t.seal()));
        s.sendMessage("税权 "+t.name()+"公库");
    }
    @Override public void close()throws SQLException{
        if(view!=null && renderer!=null)view.removeRenderer(renderer);
        store.close();
    }
}
