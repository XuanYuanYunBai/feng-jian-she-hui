package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.sql.SQLException;
import java.util.*;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.realm.RealmService.usage;
import static social.feudal.ui.Menus.*;

/** A finite standing bid. Both settlement legs move inside one real barrel snapshot. */
final class MarketOffice implements Listener {
    private final RealmService realm;
    private final RealmStore s;
    private final MarketBids bids;
    private final NamespacedKey market,receipt,vendor,buyer;
    private static final Map<String,Material> GOODS=Map.of("小麦",Material.WHEAT,"原木",Material.OAK_LOG,
            "羊毛",Material.WHITE_WOOL,"皮革",Material.LEATHER,"干草",Material.HAY_BLOCK,"铜锭",Material.COPPER_INGOT,"铁锭",Material.IRON_INGOT,"桥路铁件",Material.IRON_INGOT);
    MarketOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;market=new NamespacedKey(realm.plugin,"market_stall");receipt=new NamespacedKey(realm.plugin,"trade_receipt");
        vendor=new NamespacedKey(realm.plugin,"offering_seller");buyer=new NamespacedKey(realm.plugin,"stall_owner");
        bids=new MarketBids(s);
        Bukkit.getPluginManager().registerEvents(new NonPlayerTrade(),realm.plugin);
        Bukkit.getPluginManager().registerEvents(new TollCoin.Crafting(),realm.plugin);
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }
    private Block location(String id)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM stall WHERE id=?",id);var r=q.executeQuery()){
            require(r.next() && r.getObject(1)!=null,"商站已撤或未登记");require(realm.world.isChunkLoaded(r.getInt(1)>>4,r.getInt(3)>>4),"请到市场交易");
            return realm.world.getBlockAt(r.getInt(1),r.getInt(2),r.getInt(3));
        }
    }
    Barrel at(Player p,String id)throws SQLException{
        Block b=location(id);
        require(p.getWorld()==b.getWorld() && p.getLocation().distanceSquared(b.getLocation())<=36,"须在商站六格内交割");
        require(b.getState() instanceof Barrel barrel && id.equals(barrel.getPersistentDataContainer().get(market,PersistentDataType.STRING)),"商站柜桶已拆除或迁移");
        var bin=(Barrel)b.getState();recover(bin);return bin;
    }
    private void recover(Barrel bin)throws SQLException{
        var raw=bin.getPersistentDataContainer().get(receipt,PersistentDataType.STRING);if(raw==null)return;
        String[] parts=raw.split(" ");int id=Integer.parseInt(parts[1]);
        int paid=parts.length==4?Integer.parseInt(parts[2]):s.number("SELECT price FROM bid WHERE id=?",id);
        String customer=parts[parts.length-1];
        boolean changed=s.atomic(()->{
            if(s.update("INSERT INTO trade(id,bid,seller,paid) VALUES(?,?,?,?) ON CONFLICT(id) DO NOTHING",parts[0],id,customer,paid)==0)return false;
            require(s.update("UPDATE bid SET remaining=remaining-1 WHERE id=? AND remaining>0",id)==1,"买单余额需要核对");
            return true;
        });
        if(changed)realm.changed();
    }
    private ItemStack goods(MarketBids.Offer o){return o.goods().equals("桥路铁件")?realm.smiths.brace().asQuantity(o.lot()):new ItemStack(GOODS.get(o.goods()),o.lot());}
    private ItemStack ownerLot(MarketBids.Offer o){return o.ask()?goods(o):o.currency().stack(o.price());}
    private static boolean enough(ItemStack actual,ItemStack required){
        return actual!=null && actual.isSimilar(required) && actual.getAmount()>=required.getAmount();
    }
    private void settle(Player p,String[] a)throws SQLException{
        usage(a.length==2 || a.length==3,a[0]+" 订单号 [确认金额]");
        int id=Integer.parseInt(a[1]);
        var initial=bids.offer(id);
        var bin=at(p,initial.stall());
        // Recovery may have recorded a previously committed exchange and changed the reference.
        var o=bids.offer(id);String actor=p.getUniqueId().toString();
        require(o.action().equals(a[0]),"请选择与订单方向相符的交易");
        require(!actor.equals(s.value("SELECT owner FROM stall WHERE id=?",o.stall())),"本家不能与自己的订单成交");
        require(o.remaining()>0,"本单已结");
        if((o.floating() && a.length==2) || (a.length==3 && Integer.parseInt(a[2])!=o.price())){
            p.sendMessage("请核对当前整批金额，再确认交割");run(p,new String[]{"quote",a[1]});return;
        }
        var inv=bin.getSnapshotInventory();
        var visitor=o.ask()?o.currency().stack(o.price()):goods(o);var owner=ownerLot(o);
        require(actor.equals(bin.getPersistentDataContainer().get(vendor,PersistentDataType.STRING)),"请本人把货物或付款放入第一格");
        require(enough(inv.getItem(0),visitor),"第一格的货物或钱币不足，或品种不符");
        require(enough(inv.getItem(1),owner),o.ask()?"柜中现货不足":"柜中实付"+o.currency().label+"不足，本批暂缓交割");
        require(inv.getItem(2)==null,"请先取走上笔交割物");
        int destination=-1;
        for(int n=3;n<inv.getSize();n++){
            var stack=inv.getItem(n);
            if(stack==null || stack.isSimilar(visitor) && stack.getAmount()+visitor.getAmount()<=stack.getMaxStackSize()){
                destination=n;break;
            }
        }
        require(destination>=0,"业主收存格已满");
        var stored=inv.getItem(destination);
        inv.setItem(destination,visitor.asQuantity(visitor.getAmount()+(stored==null?0:stored.getAmount())));
        deduct(inv,0,visitor.getAmount());deduct(inv,1,owner.getAmount());inv.setItem(2,owner);
        bin.getPersistentDataContainer().set(receipt,PersistentDataType.STRING,UUID.randomUUID()+" "+id+" "+o.price()+" "+actor);
        require(bin.update(),"柜桶已改变");realm.world.save(true);recover((Barrel)bin.getBlock().getState());
        p.sendMessage("本批交割完成，请从第三格取走 "+(o.ask()?o.lot()+" 件"+o.goods():o.price()+" "+o.currency().label));
        page(p,o.stall(),1);
    }
    private static void deduct(Inventory inv,int slot,int count){
        var stack=Objects.requireNonNull(inv.getItem(slot));
        inv.setItem(slot,stack.getAmount()==count?null:stack.asQuantity(stack.getAmount()-count));
    }
    void run(Player p,String[] a)throws SQLException{
        realm.inWorld(p);String actor=p.getUniqueId().toString();
        switch(a[0]){
            case "stall" -> {
                usage(a.length==3,"stall 代号 名称");Politics.id(a[1]);Politics.wording(a[2],30);
                var b=p.getTargetBlockExact(5);require(b!=null && b.getState() instanceof Barrel && b.getRelative(BlockFace.UP).getType()==Material.OAK_SIGN,"请在木桶上立橡木告示牌，瞄准桶身");
                require(s.value("SELECT id FROM stall WHERE id=? OR (x=? AND y=? AND z=?)",a[1],b.getX(),b.getY(),b.getZ())==null,"代号或商站位置已登记");
                require(s.number("SELECT count(*) FROM manor WHERE west<=? AND east>=? AND north<=? AND south>=? AND owner<>?",b.getX(),b.getX(),b.getZ(),b.getZ(),actor)==0,"此处已有他人庄产");
                require(((Barrel)b.getState()).getInventory().isEmpty(),"请使用空柜登记商站");
                s.update("INSERT INTO stall VALUES(?,?,?,?,?,?)",a[1],actor,a[2],b.getX(),b.getY(),b.getZ());var bin=(Barrel)b.getState();
                bin.getPersistentDataContainer().set(buyer,PersistentDataType.STRING,actor);bin.getPersistentDataContainer().set(market,PersistentDataType.STRING,a[1]);bin.update();realm.changed();page(p,a[1],1);
            }
            case "market" -> {
                usage(a.length==2 || a.length==3,"market 商站 [页码]");
                page(p,a[1],a.length==3?Integer.parseInt(a[2]):1);
            }
            case "prices" -> {
                usage(a.length<=3,"prices [货种或全部] [页码]");
                String goods=a.length>=2?a[1]:"全部";
                require(goods.equals("全部") || GOODS.containsKey(goods),"请选择已登记的货种");
                board(p,goods,a.length==3?Integer.parseInt(a[2]):1);
            }
            case "quote" -> {
                usage(a.length==2,"quote 收购单号");int id=Integer.parseInt(a[1]);
                var offer=bids.offer(id);
                var buttons=new ArrayList<ActionButton>();
                if(offer.remaining()>0){
                    if(actor.equals(s.value("SELECT owner FROM stall WHERE id=?",offer.stall())))
                        buttons.add(command("到柜撤下未成交批次","realm unlist "+id));
                    else buttons.add(command(offer.ask()?"在柜台买一批":"在柜台卖一批",offer.deal()));
                }
                buttons.add(command("商站其他报价","realm market "+offer.stall()));
                buttons.add(command("比较同种货物","realm prices "+offer.goods()));
                show(p,offer.kind()+"单 #"+id,List.of(text(offer.name()+"　("+offer.x()+", "+offer.y()+", "+offer.z()+")"),
                        text(offer.quote()+"\n"+offer.terms()+"　"+funding(offer)),text("请本人将"+(offer.ask()?"钱币":"货物")+"放入商柜第一格，交割后从第三格领取。")),List.of(),buttons,2);
            }
            case "pricefilter" -> {
                var choices=new ArrayList<ActionButton>();
                choices.add(command("全部货种","realm prices"));
                for(var goods:new TreeSet<>(GOODS.keySet()))choices.add(command(goods,"realm prices "+goods));
                show(p,"选择货种",List.of(),List.of(),choices,2);
            }
            case "bidform","askform" -> {
                usage(a.length==2,a[0]+" 商站");boolean ask=a[0].equals("askform");
                DiplomacyDesk.form(p,ask?"张贴出售单":"张贴收购单","第二格先备一批"+(ask?"现货":"钱币")+"。随市按全服实际成交折银定价，无成交时采用业主填写的金额。",
                        "realm "+(ask?"ask":"bid")+" "+a[1], List.of(
                        new DiplomacyDesk.Field("goods","小麦、原木、羊毛、皮革、干草、铜锭、铁锭或桥路铁件","小麦",8),
                        new DiplomacyDesk.Field("lot","每批件数","8",2),new DiplomacyDesk.Field("price",ask?"每批金额（随市最低）":"每批金额（随市最高）","2",2),
                        new DiplomacyDesk.Field("lots","批数","4",4),new DiplomacyDesk.Field("coin","支付币种（金或银）","银",2),
                        new DiplomacyDesk.Field("mode","固定或随市","固定",2)));
            }
            case "bid","ask" -> {
                usage(a.length>=6 && a.length<=8,a[0]+" 商站 货种 每批件数 金额 批数 [币种] [固定或随市]");
                var bin=at(p,a[1]);boolean ask=a[0].equals("ask");
                require(actor.equals(s.value("SELECT owner FROM stall WHERE id=?",a[1])),"请由商站主人出价");
                int lot=Integer.parseInt(a[3]),limit=Integer.parseInt(a[4]),lots=Integer.parseInt(a[5]);
                require(GOODS.containsKey(a[2]) && lot>0 && lot<=64 && limit>0 && limit<=64 && lots>0 && lots<=4096,"货种或报价超出范围");
                var currency=a.length>=7?TollCoin.parse(a[6]):TollCoin.GOLD;
                require(a.length<8 || Set.of("固定","随市").contains(a[7]),"定价方式请选择固定或随市");
                boolean floating=a.length==8 && a[7].equals("随市");
                s.atomic(()->post(bin,a[1],a[2],lot,limit,lots,currency,ask,floating));
                realm.changed();page(p,a[1],1);
            }
            case "unlist" -> {
                usage(a.length==2,"unlist 订单号");int id=Integer.parseInt(a[1]);
                String site=bids.offer(id).stall();at(p,site);bids.withdraw(id,actor);
                p.sendMessage("未成交批次已撤，现款、存货和他人的待领交割物仍在原柜。");realm.changed();page(p,site,1);
            }
            case "sell","buy" -> settle(p,a);
        }
    }
    // Called inside the transaction that also records the purpose of the purchase.
    int post(Barrel bin,String site,String goods,int lot,int price,int lots,TollCoin coin,boolean ask,boolean floating)throws SQLException{
        s.update("INSERT INTO bid(stall,goods,lot,price,remaining,currency,ask,floating) VALUES(?,?,?,?,?,?,?,?)",
                site,goods,lot,price,lots,coin.name(),ask?1:0,floating?1:0);
        int id=s.number("SELECT last_insert_rowid()");
        require(enough(bin.getSnapshotInventory().getItem(1),ownerLot(bids.offer(id))),
                ask?"第二格须有至少一批现货":"商站第二格须有至少一批实付"+coin.label);
        return id;
    }
    private String funding(MarketBids.Offer offer){
        if(offer.remaining()==0)return "本单已结";
        var w=realm.world;
        if(!w.isChunkLoaded(offer.x()>>4,offer.z()>>4))return "待到访验资";
        if(!(w.getBlockAt(offer.x(),offer.y(),offer.z()).getState() instanceof Barrel bin) ||
                !offer.stall().equals(bin.getPersistentDataContainer().get(market,PersistentDataType.STRING)))return "柜桶待修";
        boolean stocked=enough(bin.getSnapshotInventory().getItem(1),ownerLot(offer));
        if(offer.ask())return stocked?"现货可卖一批":"等待业主补货";
        return stocked?"现款可付一批":"等待买家补款";
    }
    private void board(Player p,String goods,int page)throws SQLException{
        var listed=bids.listed(null,goods.equals("全部")?null:goods,page);
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        body.add(text("同种货物先列高价收购，再列低价出售。现款与现货由各家自备。"));
        for(var offer:listed.offers()){
            body.add(text(offer.name()+"　("+offer.x()+", "+offer.y()+", "+offer.z()+")\n"+offer.quote()+"　"+funding(offer)));
            buttons.add(command("查看 #"+offer.id()+" · "+offer.name(),"realm quote "+offer.id()));
        }
        if(listed.offers().isEmpty())body.add(text("当前没有此类订单。"));
        buttons.add(command("商站与交易","realm commerce"));
        navigation(buttons,"realm prices "+goods,page,listed.more());
        buttons.add(command("选择货种","realm pricefilter"));
        show(p,"各地行情 · "+goods+" · 第"+page+"页",body,List.of(),buttons,2);
    }
    private static void navigation(List<ActionButton> buttons,String prefix,int page,boolean more){
        if(page>1)buttons.add(command("上一页",prefix+" "+(page-1)));
        if(more)buttons.add(command("下一页",prefix+" "+(page+1)));
    }
    private void page(Player p,String site,int page)throws SQLException{
        try(var q=s.sql("SELECT name,x,y,z,owner FROM stall WHERE id=? AND x IS NOT NULL",site);var r=q.executeQuery()){
            require(r.next(),"商站已撤或未登记");
            var buttons=new ArrayList<ActionButton>();var body=new ArrayList<DialogBody>();
            body.add(text("商柜坐标　"+r.getInt(2)+", "+r.getInt(3)+", "+r.getInt(4)+"\n第一格由来客放货或付款，第二格由业主备款或备货，第三格由来客领取。"));
            var listed=bids.listed(site,null,page);
            for(var offer:listed.offers()){
                body.add(text("#"+offer.id()+"　"+offer.quote()));
                buttons.add(command("核对订单 #"+offer.id(),"realm quote "+offer.id()));
            }
            if(listed.offers().isEmpty())body.add(text("目前没有待交割的订单。"));
            if(p.getUniqueId().toString().equals(r.getString(5))){
                buttons.add(command("出价收购","realm bidform "+site));buttons.add(command("挂牌出售","realm askform "+site));
            }
            navigation(buttons,"realm market "+site,page,listed.more());
            buttons.add(command("各地行情","realm prices"));
            show(p,r.getString(1),body,List.of(),buttons,2);
        }
    }
    @EventHandler(ignoreCancelled=true)public void inspect(PlayerInteractEvent e)throws SQLException{
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null)return;
        Block b=e.getClickedBlock();if(b.getType()!=Material.OAK_SIGN || b.getWorld()!=realm.world)return;
        var below=b.getRelative(BlockFace.DOWN);if(!(below.getState() instanceof Barrel bin))return;
        var id=bin.getPersistentDataContainer().get(market,PersistentDataType.STRING);if(id!=null){e.setCancelled(true);page(e.getPlayer(),id,1);}
    }
    private Barrel till(Inventory inventory){
        if(inventory.getHolder(false) instanceof Barrel b && b.getPersistentDataContainer().has(market))return b;
        return null;
    }
    private String owner(Barrel bin)throws SQLException{
        return s.value("SELECT owner FROM stall WHERE id=? AND x=? AND y=? AND z=?",bin.getPersistentDataContainer().get(market,PersistentDataType.STRING),bin.getX(),bin.getY(),bin.getZ());
    }
    @EventHandler(ignoreCancelled=true)public void click(InventoryClickEvent e)throws SQLException{
        var bin=till(e.getView().getTopInventory());if(bin==null)return;
        String actor=e.getWhoClicked().getUniqueId().toString();
        // Only single-slot operations can move goods across the three custody compartments.
        if(e.isShiftClick() || e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT){e.setCancelled(true);return;}
        int slot=e.getRawSlot();if(slot<0 || slot>=bin.getInventory().getSize())return;
        if(slot>=1 && slot!=2){
            e.setCancelled(true);
            if(actor.equals(owner(bin)))e.setCancelled(false);
            return;
        }
        if(slot==2){
            String previous=bin.getPersistentDataContainer().get(receipt,PersistentDataType.STRING);
            if(previous==null || !previous.endsWith(" "+actor) || !e.getCursor().isEmpty())e.setCancelled(true);
            return;
        }
        if(e.getCurrentItem()!=null && !e.getCurrentItem().isEmpty()){
            if(!actor.equals(bin.getPersistentDataContainer().get(vendor,PersistentDataType.STRING)))e.setCancelled(true);
        }else if(!e.getCursor().isEmpty()){
            bin.getPersistentDataContainer().set(vendor,PersistentDataType.STRING,actor);bin.update();
        }
    }
    @EventHandler(ignoreCancelled=true)public void drag(InventoryDragEvent e){
        var bin=till(e.getView().getTopInventory());
        if(bin!=null && e.getRawSlots().stream().anyMatch(n->n<bin.getInventory().getSize()))e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true)public void dismantle(BlockBreakEvent e)throws SQLException{
        if(!(e.getBlock().getState() instanceof Barrel b) || !b.getPersistentDataContainer().has(market))return;
        if(!b.getInventory().isEmpty() || !e.getPlayer().getUniqueId().toString().equals(owner(b))){
            e.setCancelled(true);e.getPlayer().sendMessage("商柜须由业主在清空货款后拆除");return;
        }
        recover(b);
        String id=b.getPersistentDataContainer().get(market,PersistentDataType.STRING);
        s.update("UPDATE bid SET remaining=0 WHERE stall=?",id);
        // Archived transactions retain their stall id while the physical site becomes free.
        s.update("UPDATE stall SET x=NULL,y=NULL,z=NULL WHERE id=?",id);realm.changed();
    }
    @EventHandler(ignoreCancelled=true)public void hopper(InventoryMoveItemEvent e){
        for(var inv:List.of(e.getSource(),e.getDestination()))
            if(inv.getHolder(false) instanceof Barrel bin && bin.getPersistentDataContainer().has(market)){e.setCancelled(true);return;}
    }
}
