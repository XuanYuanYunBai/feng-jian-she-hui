package social.feudal.freight;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Locale;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.event.ClickCallback;
import static social.feudal.ui.Menus.*;
import java.util.UUID;
import social.feudal.transport.HaulNetwork.Point;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import static social.feudal.freight.FreightStore.*;

public final class FreightService implements CommandExecutor, Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final FreightStore store;
    private final FreightRoad road;
    private final ExecutorService ledger = Executors.newSingleThreadExecutor();
    private final AtomicBoolean ticking = new AtomicBoolean();
    private volatile boolean closing;
    // Accessed only by the ledger worker.
    private String stopped = "";
    private String delayedTrip = "";
    private final boolean pauseWhenEmpty;

    public FreightService(JavaPlugin plugin, World world, Path db,social.feudal.transport.TransportCrew crews,social.feudal.realm.RealmService realm) throws SQLException {
        this.plugin = plugin;
        road = new FreightRoad(plugin, world,crews,realm);
        store = new FreightStore(db);
        for(var r:store.routes())realm.recordRoute(r.name(),r.sea(),r.path());
        pauseWhenEmpty = plugin.getConfig().getBoolean("freight.pause-when-empty", true);
        plugin.getCommand("freight").setExecutor(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (pauseWhenEmpty && plugin.getServer().getOnlinePlayers().isEmpty()) return;
            if (!ticking.compareAndSet(false, true)) return;
            ledger.execute(() -> {
                try {
                    var returning=store.returning();
                    if(returning!=null){returnHome(returning);return;}
                    Trip t = store.active();
                    if (t != null) tick(t);
                } catch (Exception e) {
                    if (!closing) noteFailure(e);
                } finally { ticking.set(false); }
            });
        }, 1, 4);
    }

    private <T> T main(Callable<T> work) throws Exception {
        if (closing) throw new CancellationException();
        try {
            return plugin.getServer().getScheduler().callSyncMethod(plugin, () -> {
                if (closing) throw new CancellationException();
                return work.call();
            }).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    private void noteFailure(Exception e) {
        String reason = e.getMessage() == null ? e.toString() : e.getMessage();
        if (reason.equals(stopped)) return;
        stopped = reason;
        if (e instanceof IllegalArgumentException) plugin.getLogger().info("运输队等候：" + reason);
        else plugin.getLogger().log(Level.SEVERE, "货运操作中止，将从检查点复核", e);
    }

    private void returnHome(FreightStore.Return trip)throws Exception{
        var r=trip.route();int next=Math.max(0,trip.step()-1);
        if(main(()->{
            road.hold(r,trip.step(),next);road.inspect(r,trip.step());road.inspect(r,next);
            var team=road.team(r);
            if(!r.sea())road.realm.clearance(r.path().get(next),"返-"+trip.bill(),team);
            if(!road.walk(r,team,next))return false;
            if(next==0){road.rested(team);road.release();}
            return true;
        }))store.walkedBack(trip);
    }

    private void tick(Trip t) throws Exception {
        delayedTrip = t.id();
        Route r = t.route();
        int next = t.phase() == Phase.TRAVEL ? t.step() + 1 : t.step();
        boolean ready = main(() -> {
            if(t.phase()!=Phase.LOADING && !road.readyToCarry(t))return false;
            road.hold(r, t.step(), next);
            switch (t.phase()) {
                case LOADING -> { return road.transfer(t, true); }
                case TRAVEL -> {
                    road.inspect(r, t.step());
                    road.inspect(r, next);
                    var team=road.team(r);
                    if(!r.sea())road.realm.clearance(r.path().get(next),"货-"+t.id(),team);
                    return road.walk(r,team,next);
                }
                case ARRIVED -> road.team(r).halt();
                case UNLOADING -> road.transfer(t, false);
                case DELIVERED, CANCELLED -> throw new IllegalStateException("已结束的货单进入运输队列");
            }
            return true;
        });
        switch (t.phase()) {
            case LOADING -> {
                if (ready) store.loaded(t);
                else {
                    store.cancelUnloaded(t);
                    main(() -> { road.release(); notifyParty(t.sender(), "原货栈已变动，货单 " + t.id() + " 已撤销"); return null; });
                }
            }
            case TRAVEL -> {
                if(!ready)return;
                store.move(t);
                main(() -> {
                    if (next == r.length()) notifyParty(t.recipient(), "货单 " + t.id() + " 已到 " + r.dx() + "," + r.dy()
                            + "," + r.dz() + "，请到站空手潜行右键签收");
                    return null;
                });
            }
            case UNLOADING -> {
                if(!ready)return;
                store.delivered(t);
                main(() -> { road.release(); return null; });
                plugin.getLogger().info("货单 " + t.id() + " 已交付");
            }
            default -> { }
        }
        stopped = "";
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender || sender instanceof Player)) {
            sender.sendMessage("请由玩家或服务端控制台办理货运");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("先用 /freight routes 找到公共站桶。把一叠货放入起点桶第一格，空手潜行右键站桶即可填单或签收。");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if ((action.equals("route") || action.equals("sail")) && !sender.hasPermission("feudal.freight.admin")) {
            sender.sendMessage("登记公共货线需要 feudal.freight.admin 权限");
            return true;
        }
        String party = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        ledger.execute(() -> {
            String message;
            try {
                message = switch (action) {
                    case "route","sail" -> register(args);
                    case "routes" -> listRoutes();
                    case "equip" -> {
                        if(!(sender instanceof Player p) || args.length!=3)throw new IllegalArgumentException("用法 /freight equip 货线 speed|pack|mule");
                        Route r=store.route(key(args[1]));if(r==null)throw new IllegalArgumentException("货线不存在");
                        main(()->{road.equip(p,r,args[2]);return null;});yield "货站装备已登记";
                    }
                    case "send" -> book(sender, party, args);
                    case "accept" -> accept(sender, party, args);
                    case "status" -> status(args);
                    default -> "用法：" + cmd.getUsage();
                };
            } catch (Exception e) {
                if (closing) return;
                if (!(e instanceof IllegalArgumentException)) plugin.getLogger().log(Level.WARNING, "货运命令失败", e);
                message = "货运：" + e.getMessage();
            }
            String reply = message;
            if (!closing) plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(reply));
        });
        return true;
    }

    private static String key(String input) {
        if (!input.matches("[a-zA-Z0-9_-]{1,40}")) throw new IllegalArgumentException("名称须为 1–40 位字母、数字、横线或下划线");
        return input.toLowerCase(Locale.ROOT);
    }

    private String register(String[] a) throws Exception {
        if(a.length!=7 && a.length!=8)throw new IllegalArgumentException("用法 /freight route|sail 名称 起X 起Y 起Z 终X 终Y 终Z");
        String name=key(a[1]);
        if(store.route(name)!=null)throw new IllegalArgumentException("货线名称已登记");
        Point start=new Point(Integer.parseInt(a[2]),Integer.parseInt(a[3]),Integer.parseInt(a[4]));
        Point end=new Point(Integer.parseInt(a[5]),a.length==7?start.y():Integer.parseInt(a[6]),Integer.parseInt(a[a.length-1]));
        Route r=main(()->road.register(name,start,end,a[0].equalsIgnoreCase("sail")));
        store.register(r);
        main(()->{road.realm.recordRoute(r.name(),r.sea(),r.path());return null;});
        return "已登记公共货线 " + name + "，全长 " + r.length() + " 格";
    }

    private String listRoutes() throws SQLException {
        var list = store.routes();
        if (list.isEmpty()) return "尚未登记公共货线";
        var result = new StringBuilder("公共货线");
        for (Route r : list) result.append("\n%s (%d,%d,%d) → (%d,%d,%d)".formatted(r.name(), r.sx(), r.y(), r.sz(), r.dx(), r.dy(), r.dz()));
        return result.toString();
    }

    private void nearby(CommandSender sender, int x, int y, int z) {
        if (sender instanceof Player p && (!p.isOnline() || !p.getWorld().equals(road.world)
                || p.getLocation().toVector().distanceSquared(new org.bukkit.util.Vector(x+.5, y+.5, z+.5)) > 36))
            throw new IllegalArgumentException("请到登记站桶 6 格以内办理");
    }

    private String book(CommandSender sender, String party, String[] a) throws Exception {
        if (a.length != 3 && a.length != 4) throw new IllegalArgumentException("用法：/freight send 货线 运单号 [在线收货人]");
        String id = key(a[2]);
        Route r = store.route(key(a[1]));
        if (r == null) throw new IllegalArgumentException("货线不存在");
        Trip existing = store.trip(id);
        if (existing != null) {
            if (!existing.route().equals(r) || !existing.sender().equals(party)) throw new IllegalArgumentException("该运单号已占用");
            return describe(existing);
        }
        if(store.returning()!=null)throw new IllegalArgumentException("脚夫正在徒步回站");
        if (store.active() != null) throw new IllegalArgumentException("已有货单在运，请等待脚夫回站");
        String recipient = main(() -> {
            nearby(sender, r.sx(), r.y(), r.sz());
            if (a.length == 3) return party;
            var p = plugin.getServer().getPlayerExact(a[3]);
            if (p == null) throw new IllegalArgumentException("指定的收货人须在线");
            return p.getUniqueId().toString();
        });
        var goods = main(() -> {
            nearby(sender, r.sx(), r.y(), r.sz());
            return road.manifest(r);
        });
        store.book(id, r, party, recipient, goods.bytes(), goods.material(), goods.amount());
        stopped = "";
        return "已开货单 " + id + "，待装 " + goods.amount() + " " + FreightRoad.goodsName(goods.material());
    }

    private String accept(CommandSender sender, String party, String[] a) throws Exception {
        if (a.length != 2) throw new IllegalArgumentException("用法：/freight accept 运单号");
        Trip t = store.trip(key(a[1]));
        if (t == null) throw new IllegalArgumentException("货单不存在");
        if (!party.equals("console") && !party.equals(t.recipient())) throw new IllegalArgumentException("仅指定收货人可签收");
        if (t.phase() == Phase.DELIVERED || t.phase() == Phase.UNLOADING) return describe(t);
        if (t.phase() != Phase.ARRIVED) throw new IllegalArgumentException("承运脚夫尚未到站");
        main(() -> { nearby(sender, t.route().dx(), t.route().dy(), t.route().dz()); return null; });
        store.accept(t, party);
        return "已签收货单 " + t.id() + "，等待卸入终点桶第一格";
    }

    private String status(String[] a) throws SQLException {
        if (a.length > 2) throw new IllegalArgumentException("用法：/freight status [运单号]");
        Trip t = a.length == 1 ? store.active() : store.trip(key(a[1]));
        return t == null ? (a.length == 1 ? "脚夫待命" : "货单不存在") : describe(t);
    }

    private String describe(Trip t) {
        String state = switch (t.phase()) {
            case LOADING -> "等待装货";
            case TRAVEL -> "在途";
            case ARRIVED -> "到站待签收";
            case UNLOADING -> "待卸货";
            case DELIVERED -> "已交付";
            case CANCELLED -> "原货栈变动，装货前已撤销";
        };
        String delay = t.id().equals(delayedTrip) && !stopped.isEmpty() ? " | 等候：" + stopped : "";
        String receipt = t.deliveredAt() == null ? "" : " | 签收人 " + t.signer() + " | " + t.deliveredAt() + " UTC";
        return "%s | %s | %s | %d %s | (%d,%d,%d) | %d/%d%s%s".formatted(t.id(), t.route().name(), state,
                t.amount(), FreightRoad.goodsName(t.material()), t.route().x(t.step()), t.route().path().get(t.step()).y()+1, t.route().z(t.step()), t.step(),
                t.route().length(), delay, receipt);
    }

    private void notifyParty(String party, String message) {
        if (party.equals("console")) return;
        Player player = plugin.getServer().getPlayer(UUID.fromString(party));
        if (player != null) player.sendMessage(message);
    }

    @EventHandler(ignoreCancelled = true)
    public void inspectStation(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || !event.getPlayer().hasPermission("feudal.freight.use")) return;
        var held = event.getPlayer().getInventory().getItemInMainHand().getType();
        if (held != Material.BOOK && !(held.isAir() && event.getPlayer().isSneaking())) return;
        var block = event.getClickedBlock();
        if (block == null || !road.registered(block)) return;
        event.setCancelled(true);
        var player = event.getPlayer();
        String world = block.getWorld().getUID().toString();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        String party = player.getUniqueId().toString();
        ledger.execute(() -> {
            try {
                Trip trip = store.active();
                var routes = store.routes();
                String description = trip==null ? "" : describe(trip);
                main(() -> {
                    if (!player.isOnline()) return null;
                    var buttons = new ArrayList<ActionButton>();
                    var body = new ArrayList<DialogBody>();
                    var inputs = new ArrayList<DialogInput>();
                    body.add(text("站址　" + x + "，" + y + "，" + z));
                    for (Route r : routes) {
                        if (!r.world().equals(world)) continue;
                        if (x==r.sx() && y==r.y() && z==r.sz()) {
                            body.add(text(road.capacity(r)));
                            for(var u:social.feudal.transport.TransportCrew.Upgrade.values())
                                buttons.add(command(u.name+" · "+u.cost,"freight equip "+r.name()+" "+u.name().toLowerCase(Locale.ROOT)));
                            String bill = UUID.randomUUID().toString();
                            if(inputs.isEmpty()) inputs.add(DialogInput.text("recipient",200,Component.text("收货人（留空为本人）"),true,"",16,null));
                            var action = DialogAction.customClick((reply, viewer) -> {
                                if (!(viewer instanceof Player signer) || !signer.getUniqueId().equals(player.getUniqueId())) return;
                                String name = reply.getText("recipient");
                                String recipient = name==null ? "" : name.strip();
                                if (!recipient.isEmpty() && !recipient.matches("[A-Za-z0-9_]{1,16}")) {
                                    signer.sendMessage("请输入有效的在线玩家名");return;
                                }
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    if(signer.isOnline()) signer.performCommand("freight send " + r.name() + " " + bill
                                            + (recipient.isEmpty()?"":" "+recipient));
                                });
                            }, ClickCallback.Options.builder().uses(1).build());
                            buttons.add(ActionButton.create(Component.text("发往 "+r.dx()+"，"+r.dz()),
                                    Component.text("货线 "+r.name()),128,action));
                        }
                        if (x==r.dx() && y==r.dy() && z==r.dz() && trip!=null && trip.route().equals(r)) {
                            body.add(text(description));
                            if (trip.phase()==Phase.ARRIVED && party.equals(trip.recipient()))
                                buttons.add(command("签收货物","freight accept "+trip.id()));
                        }
                    }
                    if(buttons.isEmpty())body.add(text("本站当前没有可办理的货单"));
                    show(player,"公共货站",body,inputs,buttons,1);
                    return null;
                });
            } catch (Exception e) {
                if (!closing) plugin.getLogger().log(Level.WARNING, "读取货站失败", e);
            }
        });
    }

    @Override public void close() {
        closing = true;
        ledger.shutdownNow();
        road.release();
        try {
            if (ledger.awaitTermination(5, TimeUnit.SECONDS)) store.close();
            else plugin.getLogger().severe("货运数据库线程未在停服期限内退出");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (SQLException e) { plugin.getLogger().log(Level.SEVERE, "关闭货运账本失败", e); }
    }
}
