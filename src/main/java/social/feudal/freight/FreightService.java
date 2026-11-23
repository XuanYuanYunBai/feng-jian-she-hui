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
    private final java.util.Map<String,String> delays=new java.util.HashMap<>();
    private final java.util.Map<Integer,String> waiting=new java.util.HashMap<>();
    private long lastDispatch;
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
                    for(var crew:store.returns()){
                        try{ returnHome(crew); delays.remove(crew.bill()); }
                        catch(Exception e){ if(!closing)noteFailure(crew.bill(),e); }
                    }
                    for(var trip:store.activeTrips()){
                        try{ tick(trip); delays.remove(trip.id()); }
                        catch(Exception e){ if(!closing)noteFailure(trip.id(),e); }
                    }
                    dispatchRelay();
                } catch (Exception e) {
                    if (!closing) plugin.getLogger().log(Level.WARNING,"货运调度失败",e);
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

    private void noteFailure(String id,Exception e) {
        String reason = e.getMessage() == null ? e.toString() : e.getMessage();
        if (reason.equals(delays.put(id,reason))) return;
        if (e instanceof IllegalArgumentException) plugin.getLogger().info("货单 "+id+" 等候：" + reason);
        else plugin.getLogger().log(Level.SEVERE, "货运操作中止，将从检查点复核", e);
    }

    private void returnHome(FreightStore.Return trip)throws Exception{
        var r=trip.route();int next=Math.max(0,trip.step()-1);
        if(main(()->{
            road.hold(r,trip.step(),next);road.inspect(r,trip.step());road.inspect(r,next);
            checkGrade(r,trip.step(),next);
            var team=road.team(r);
            if(!r.sea())road.realm.clearance(r.path().get(next),"返-"+trip.bill(),team);
            if(!road.walk(r,team,next))return false;
            if(next==0){road.rested(r,team);road.release(r);}
            return true;
        }))store.walkedBack(trip);
    }

    private void checkGrade(Route route,int from,int to){
        if(!route.sea() && !road.net.traversable(route.path().get(from),route.path().get(to)))
            throw new IllegalArgumentException("路面高差过大，请调整台阶或楼梯朝向");
    }

    private void tick(Trip t) throws Exception {
        Route r = t.route();
        int next = t.phase() == Phase.TRAVEL ? t.step() + 1 : t.step();
        boolean ready = main(() -> {
            if(t.phase()!=Phase.LOADING && !road.readyToCarry(t))return false;
            road.hold(r, t.step(), next);
            if((t.phase()==Phase.ARRIVED || t.phase()==Phase.UNLOADING) && !road.atDock(t))return false;
            switch (t.phase()) {
                case LOADING -> { return road.transfer(t, true); }
                case TRAVEL -> {
                    road.inspect(r, t.step());
                    road.inspect(r, next);
                    checkGrade(r,t.step(),next);
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
            case ARRIVED -> {
                if (ready && t.id().startsWith("relief.")) store.accept(t,"货站交接");
            }
            case LOADING -> {
                if (ready) store.loaded(t);
                else {
                    store.cancelUnloaded(t);
                    main(() -> { road.release(r); notifyParty(t.sender(), "原货栈已变动，货单 " + t.id() + " 已撤销"); return null; });
                }
            }
            case TRAVEL -> {
                if(!ready)return;
                store.move(t);
                if (next == r.length() && !t.id().startsWith("relief.")) main(() -> {
                    notifyParty(t.recipient(), "货单 " + t.id() + " 已到 " + r.dx() + "," + r.dy()
                            + "," + r.dz() + "，请到站空手潜行右键签收");
                    return null;
                });
            }
            case UNLOADING -> {
                if(!ready)return;
                store.delivered(t);
                main(() -> { road.release(r); return null; });
                plugin.getLogger().info("货单 " + t.id() + " 已交付");
            }
            default -> { }
        }
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
                    case "relay" -> relay(sender,party,args);
                    case "track" -> track(sender,args);
                    case "withdraw","resume" -> changeDispatch(party,args,action.equals("resume"));
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
            if (!closing && !reply.isEmpty()) plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(reply));
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
        String wait=store.unavailable(r);
        if(wait!=null)throw new IllegalArgumentException(wait);
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
        if(goods.relief()!=null){
            var w=store.waybill(goods.relief());
            if(w!=null && !w.closed())throw new IllegalArgumentException("本批封粮已委托联运，请查阅赈单行程");
        }
        store.book(id, r, party, recipient, goods.bytes(), goods.material(), goods.amount());
        return "已开货单 " + id + "，待装 " + goods.amount() + " " + FreightRoad.goodsName(goods.material());
    }

    private static int reliefId(String input) {
        try {
            int id=Integer.parseInt(input);
            if(id>0)return id;
        } catch(NumberFormatException ignored) { }
        throw new IllegalArgumentException("请填写正整数赈单号");
    }

    private String relay(CommandSender sender,String party,String[] args) throws Exception {
        if(!(sender instanceof Player p) || args.length<3 || args.length>10)
            throw new IllegalArgumentException("用法 /freight relay 赈单号 货线一 [货线二 …]");
        int id=reliefId(args[1]);
        var routes=new ArrayList<Route>();
        for(int i=2;i<args.length;i++){
            var route=store.route(key(args[i]));
            if(route==null)throw new IllegalArgumentException("没有这条公共货线："+args[i]);
            routes.add(route);
        }
        checkChain(routes);
        var existing=store.waybill(id);
        if(existing!=null){
            if(existing.sender().equals(party) && routes.equals(existing.legs().stream().map(RelayLeg::route).toList()))
                return "赈单已有联运记录，请用 /freight track "+id+" 查阅";
            throw new IllegalArgumentException("这张赈单已有联运记录");
        }
        var first=routes.getFirst();
        var goods=main(()->{
            nearby(sender,first.sx(),first.y(),first.sz());
            var manifest=road.manifest(first);
            if(!"BREAD".equals(manifest.material()) || !Integer.valueOf(id).equals(manifest.relief()))
                throw new IllegalArgumentException("请将本赈单的整批封粮放在起点站桶第一格");
            road.realm.checkReliefRoute(id,p,manifest.amount(),first.start(),routes.getLast().end());
            return manifest;
        });
        store.relay(id,party,goods.bytes(),goods.amount(),routes);
        return "已委托 "+routes.size()+" 段联运，赈单 #"+id+"。用 /freight track "+id+" 查验。";
    }

    // Only release the next leg once its predecessor durably unloaded into the same sealed barrel.
    private void dispatchRelay() throws Exception {
        if(System.nanoTime()-lastDispatch<1_000_000_000L)return;
        lastDispatch=System.nanoTime();
        for(int id:store.waitingRelays()){
            var order=store.waybill(id);
            if (!"在途".equals(main(()->road.realm.reliefStatus(id)))) {
                store.setDispatchClosed(id,true); waiting.remove(id); continue;
            }
            var leg=order.legs().stream().filter(l->!"DELIVERED".equals(l.phase())).findFirst().orElseThrow();
            if(leg.phase()!=null && !"CANCELLED".equals(leg.phase()))continue;
            String wait=store.unavailable(leg.route());
            if(wait!=null){waiting.put(id,wait);continue;}
            try{
                boolean staged=main(()->road.staged(leg.route(),order.cargo()));
                if(!staged){waiting.put(id,"等待原批封粮放回该站第一格");continue;}
                store.dispatch(order,leg);waiting.remove(id);
            }catch(IllegalArgumentException ex){waiting.put(id,ex.getMessage());}
        }
    }

    private String changeDispatch(String party,String[] args,boolean resume) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("请填写赈单号");
        var w=store.waybill(reliefId(args[1]));
        if(w==null || !w.sender().equals(party))throw new IllegalArgumentException("须由原委托人办理");
        if(w.legs().stream().noneMatch(l->l.phase()==null || "CANCELLED".equals(l.phase())))
            throw new IllegalArgumentException("所有路段已发车");
        if(resume && !"在途".equals(main(()->road.realm.reliefStatus(w.relief()))))
            throw new IllegalArgumentException("这份赈单已经结清");
        store.setDispatchClosed(w.relief(),!resume);waiting.remove(w.relief());
        return resume?"已恢复派车，下一段须在原站取得原批封粮。":"已撤回后续派车。已启程的工班会把实货交到本段终点，可在那里亲取。";
    }

    private String track(CommandSender sender,String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("用法 /freight track 赈单号");
        int id=reliefId(args[1]);var order=store.waybill(id);
        if(order==null)return "暂无联运委托；可由供粮庄在赈单选择「安排联运」。";
        var lines=new ArrayList<String>();
        String state=main(()->road.realm.reliefStatus(id));
        lines.add("封粮 "+order.amount()+" 个　｜　赈单"+state);
        boolean firstPending=true;
        for(var leg:order.legs()){
            var r=leg.route();String label="第 "+leg.number()+" 段　"+(r.sea()?"水运":"陆运")+" · "+r.name();
            lines.add(label);
            if("DELIVERED".equals(leg.phase()))lines.add("　已交仓　"+r.dx()+"，"+r.dy()+"，"+r.dz());
            else if(firstPending){
                firstPending=false;
                if(leg.phase()==null || "CANCELLED".equals(leg.phase()))
                    lines.add("　待装仓　"+r.sx()+"，"+r.y()+"，"+r.sz()+"　"+(order.closed()?"已撤回派车":waiting.getOrDefault(id,"等候工班")));
                else lines.add("　"+describe(store.trip(leg.bill())));
            }else lines.add(order.closed()?"　后续派车已撤回":"　等待前段交仓");
        }
        if(firstPending && state.equals("在途"))lines.add("末段已交仓，须由当地持印人到厅仓验货开封。");
        if(sender instanceof Player p){
            main(()->{
                var body=lines.stream().map(s->text(s)).toList();
                var buttons=new ArrayList<ActionButton>();
                buttons.add(command("刷新行程","freight track "+id));
                if(p.getUniqueId().toString().equals(order.sender()) && state.equals("在途")
                        && order.legs().stream().anyMatch(l->l.phase()==null || "CANCELLED".equals(l.phase())))
                    buttons.add(order.closed()?command("恢复后续派车","freight resume "+id):command("撤回后续派车","freight withdraw "+id));
                show(p,"赈粮联运 #"+id,body,java.util.List.of(),buttons,1);return null;
            });
            return "";
        }
        return String.join("\n",lines);
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
        if(a.length==1){
            var all=store.activeTrips();
            return all.isEmpty()?"脚夫待命":String.join("\n",all.stream().map(this::describe).toList());
        }
        Trip t=store.trip(key(a[1]));
        return t==null?"货单不存在":describe(t);
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
        String delay = delays.containsKey(t.id()) ? " | 等候：" + delays.get(t.id()) : "";
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
                var active=store.activeTrips();
                var descriptions=new java.util.HashMap<String,String>();
                for(var t:active)descriptions.put(t.id(),describe(t));
                var routes = store.routes();
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
                        boolean atDestination=x==r.dx() && y==r.dy() && z==r.dz();
                        boolean atSource=x==r.sx() && y==r.y() && z==r.sz();
                        if (!atDestination && !atSource)continue;
                        for(var trip:active){
                            if(!trip.route().name().equals(r.name()))continue;
                            body.add(text(descriptions.get(trip.id())));
                            if (atDestination && trip.phase()==Phase.ARRIVED && !trip.id().startsWith("relief.") && party.equals(trip.recipient()))
                                buttons.add(command("签收 "+r.name()+" · "+trip.amount()+" "+FreightRoad.goodsName(trip.material()),"freight accept "+trip.id()));
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
