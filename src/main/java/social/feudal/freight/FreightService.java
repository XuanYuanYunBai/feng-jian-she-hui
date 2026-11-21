package social.feudal.freight;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
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

    public FreightService(JavaPlugin plugin, World world, Path db) throws SQLException {
        this.plugin = plugin;
        road = new FreightRoad(plugin, world);
        store = new FreightStore(db);
        pauseWhenEmpty = plugin.getConfig().getBoolean("freight.pause-when-empty", true);
        plugin.getCommand("freight").setExecutor(this);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (pauseWhenEmpty && plugin.getServer().getOnlinePlayers().isEmpty()) return;
            if (!ticking.compareAndSet(false, true)) return;
            ledger.execute(() -> {
                try {
                    Trip t = store.active();
                    if (t != null) tick(t);
                } catch (Exception e) {
                    if (!closing) noteFailure(e);
                } finally { ticking.set(false); }
            });
        }, 1, 10);
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
        if (e instanceof IllegalArgumentException) plugin.getLogger().info("货车等候：" + reason);
        else plugin.getLogger().log(Level.SEVERE, "货运操作中止，将从检查点复核", e);
    }

    private void tick(Trip t) throws Exception {
        delayedTrip = t.id();
        Route r = t.route();
        int next = t.phase() == Phase.TRAVEL ? t.step() + 1 : t.step();
        boolean ready = main(() -> {
            road.hold(r, t.step(), next);
            switch (t.phase()) {
                case LOADING -> { return road.transfer(t, true); }
                case TRAVEL -> {
                    road.show(t, t.step());
                    road.inspect(r, t.step());
                    road.inspect(r, next);
                }
                case ARRIVED -> road.show(t, t.step());
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
                store.move(t);
                main(() -> {
                    road.show(t, next);
                    if (next == r.length()) notifyParty(t.recipient(), "货单 " + t.id() + " 已到 " + r.dx() + "," + r.y()
                            + "," + r.dz() + "，请到站持书右键签收");
                    return null;
                });
            }
            case UNLOADING -> {
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
            sender.sendMessage("先用 /freight routes 找到公共站桶。把一叠货放入起点桶第一格，持普通书右键站桶即可开单。到站后由收货人持书右键签收。");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("route") && !sender.hasPermission("feudal.freight.admin")) {
            sender.sendMessage("登记公共货线需要 feudal.freight.admin 权限");
            return true;
        }
        String party = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        ledger.execute(() -> {
            String message;
            try {
                message = switch (action) {
                    case "route" -> register(args);
                    case "routes" -> listRoutes();
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
        if (a.length != 7) throw new IllegalArgumentException("用法：/freight route 名称 起点X 桶Y 起点Z 终点X 终点Z");
        String name = key(a[1]);
        if (store.route(name) != null) throw new IllegalArgumentException("货线名称已登记");
        int sx = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), sz = Integer.parseInt(a[4]);
        int dx = Integer.parseInt(a[5]), dz = Integer.parseInt(a[6]);
        Route r = main(() -> road.register(name, sx, y, sz, dx, dz));
        store.register(r);
        return "已登记公共货线 " + name + "，全长 " + r.length() + " 格";
    }

    private String listRoutes() throws SQLException {
        var list = store.routes();
        if (list.isEmpty()) return "尚未登记公共货线";
        var result = new StringBuilder("公共货线");
        for (Route r : list) result.append("\n%s (%d,%d,%d) → (%d,%d,%d)".formatted(r.name(), r.sx(), r.y(), r.sz(), r.dx(), r.y(), r.dz()));
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
        if (store.active() != null) throw new IllegalArgumentException("货车正在履约，请在交付后发车");
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
        if (t.phase() != Phase.ARRIVED) throw new IllegalArgumentException("货车尚未到站");
        main(() -> { nearby(sender, t.route().dx(), t.route().y(), t.route().dz()); return null; });
        store.accept(t, party);
        return "已签收货单 " + t.id() + "，等待卸入终点桶第一格";
    }

    private String status(String[] a) throws SQLException {
        if (a.length > 2) throw new IllegalArgumentException("用法：/freight status [运单号]");
        Trip t = a.length == 1 ? store.active() : store.trip(key(a[1]));
        return t == null ? (a.length == 1 ? "货车空闲" : "货单不存在") : describe(t);
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
                t.amount(), FreightRoad.goodsName(t.material()), t.route().x(t.step()), t.route().y()+1, t.route().z(t.step()), t.step(),
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
                || event.getItem() == null || event.getItem().getType() != Material.BOOK
                || !event.getPlayer().hasPermission("feudal.freight.use")) return;
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
                var message = Component.text("公共货站 (" + x + "," + y + "," + z + ")", NamedTextColor.GOLD);
                for (Route r : store.routes()) {
                    if (!r.world().equals(world) || y != r.y()) continue;
                    if (x == r.sx() && z == r.sz()) {
                        String bill = UUID.randomUUID().toString();
                        message = message.append(Component.text("\n" + r.name() + " → " + r.dx() + "," + y + "," + r.dz()
                                + "  [填写货单]", NamedTextColor.GREEN).clickEvent(
                                ClickEvent.suggestCommand("/freight send " + r.name() + " " + bill + " ")));
                    }
                    if (x == r.dx() && z == r.dz() && trip != null && trip.route().equals(r)) {
                        message = message.append(Component.text("\n" + describe(trip), NamedTextColor.WHITE));
                        if (trip.phase() == Phase.ARRIVED && party.equals(trip.recipient()))
                            message = message.append(Component.text("  [签收]", NamedTextColor.GREEN)
                                    .clickEvent(ClickEvent.runCommand("/freight accept " + trip.id())));
                    }
                }
                var response = message;
                main(() -> { if (player.isOnline()) player.sendMessage(response); return null; });
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
