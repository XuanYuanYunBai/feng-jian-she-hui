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
            "羊毛",Material.WHITE_WOOL,"皮革",Material.LEATHER,"干草",Material.HAY_BLOCK,"铜锭",Material.COPPER_INGOT,"铁锭",Material.IRON_INGOT);
    MarketOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;market=new NamespacedKey(realm.plugin,"market_stall");receipt=new NamespacedKey(realm.plugin,"trade_receipt");
        vendor=new NamespacedKey(realm.plugin,"offering_seller");buyer=new NamespacedKey(realm.plugin,"stall_owner");
        bids=new MarketBids(s);
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }
    private Block location(String id)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM stall WHERE id=?",id);var r=q.executeQuery()){
            require(r.next() && r.getObject(1)!=null,"商站已撤或未登记");require(realm.world.isChunkLoaded(r.getInt(1)>>4,r.getInt(3)>>4),"请到市场交易");
            return realm.world.getBlockAt(r.getInt(1),r.getInt(2),r.getInt(3));
        }
    }
    private Barrel at(Player p,String id)throws SQLException{
        Block b=location(id);
        require(p.getWorld()==b.getWorld() && p.getLocation().distanceSquared(b.getLocation())<=36,"须在商站六格内交割");
        require(b.getState() instanceof Barrel barrel && id.equals(barrel.getPersistentDataContainer().get(market,PersistentDataType.STRING)),"商站柜桶已拆除或迁移");
        var bin=(Barrel)b.getState();recover(bin);return bin;
    }
    private void recover(Barrel bin)throws SQLException{
        var raw=bin.getPersistentDataContainer().get(receipt,PersistentDataType.STRING);if(raw==null)return;
        String[] parts=raw.split(" ");int id=Integer.parseInt(parts[1]);
        boolean changed=s.atomic(()->{
            if(s.update("INSERT OR IGNORE INTO trade VALUES(?,?,?)",parts[0],id,parts[2])==0)return false;
            require(s.update("UPDATE bid SET remaining=remaining-1 WHERE id=? AND remaining>0",id)==1,"买单余额需要核对");
            return true;
        });
        if(changed)realm.changed();
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
                require(goods.equals("全部") || GOODS.containsKey(goods),"请选择已登记的收购货种");
                board(p,goods,a.length==3?Integer.parseInt(a[2]):1);
            }
            case "quote" -> {
                usage(a.length==2,"quote 收购单号");int id=Integer.parseInt(a[1]);
                var offer=bids.offer(id);
                var buttons=new ArrayList<ActionButton>();
                if(offer.remaining()>0)buttons.add(command("在柜台交付一批","realm sell "+id));
                buttons.add(command("商站其他报价","realm market "+offer.stall()));
                buttons.add(command("比较同种货物","realm prices "+offer.goods()));
                show(p,"收购单 #"+id,List.of(text(offer.name()+"　("+offer.x()+", "+offer.y()+", "+offer.z()+")"),
                        text(offer.quote()+"\n"+funding(offer)),text("请本人将货物放入商柜第一格，交割后从第三格领走钱币。")),List.of(),buttons,2);
            }
            case "pricefilter" -> {
                var choices=new ArrayList<ActionButton>();
                choices.add(command("全部货种","realm prices"));
                for(var goods:new TreeSet<>(GOODS.keySet()))choices.add(command(goods,"realm prices "+goods));
                show(p,"选择收购货种",List.of(),List.of(),choices,2);
            }
            case "bidform" -> {
                usage(a.length==2,"bidform 商站");DiplomacyDesk.form(p,"张贴收购单","首格供卖家放货，第二格放买方金粒或桥亭兑换的银币，第三格供卖家领款。每单使用一种货币。","realm bid "+a[1],
                        List.of(new DiplomacyDesk.Field("goods","小麦、原木、羊毛、皮革、干草、铜锭或铁锭","小麦",8),
                                new DiplomacyDesk.Field("lot","每批件数","8",2),new DiplomacyDesk.Field("price","每批金额","2",2),new DiplomacyDesk.Field("lots","收购批数","4",4),new DiplomacyDesk.Field("coin","支付币种（金或银）","金",2)));
            }
            case "bid" -> {
                usage(a.length==6 || a.length==7,"bid 商站 货种 每批件数 每批金额 批数 [金或银]");var bin=at(p,a[1]);
                require(actor.equals(s.value("SELECT owner FROM stall WHERE id=?",a[1])),"请由商站主人出价");
                int lot=Integer.parseInt(a[3]),price=Integer.parseInt(a[4]),lots=Integer.parseInt(a[5]);
                require(GOODS.containsKey(a[2]) && lot>0 && lot<=64 && price>0 && price<=64 && lots>0 && lots<=4096,"货种或报价超出范围");
                var currency=a.length==7?TollCoin.parse(a[6]):TollCoin.GOLD;
                var coins=bin.getSnapshotInventory().getItem(1);
                require(coins!=null && coins.isSimilar(currency.stack(1)) && coins.getAmount()>=price,"商站第二格须有至少一批实付"+currency.label);
                s.update("INSERT INTO bid(stall,goods,lot,price,remaining,currency) VALUES(?,?,?,?,?,?)",a[1],a[2],lot,price,lots,currency.name());realm.changed();page(p,a[1],1);
            }
            case "sell" -> {
                usage(a.length==2,"sell 收购单号");int id=Integer.parseInt(a[1]);
                String site=s.value("SELECT stall FROM bid WHERE id=?",id);require(site!=null,"未知收购单");var bin=at(p,site);
                require(!actor.equals(s.value("SELECT owner FROM stall WHERE id=?",site)),"本家不能向自己的订单报交");
                String goods=s.value("SELECT goods FROM bid WHERE id=?",id);int lot=s.number("SELECT lot FROM bid WHERE id=?",id),price=s.number("SELECT price FROM bid WHERE id=?",id);
                require(s.number("SELECT remaining FROM bid WHERE id=?",id)>0,"本单已结");
                var inv=bin.getSnapshotInventory();var offered=inv.getItem(0);var cash=inv.getItem(1);
                require(actor.equals(bin.getPersistentDataContainer().get(vendor,PersistentDataType.STRING)),"请先本人放货");
                require(offered!=null && offered.isSimilar(new ItemStack(GOODS.get(goods))) && offered.getAmount()>=lot,"首格待交货物不足");
                var currency=TollCoin.parse(s.value("SELECT currency FROM bid WHERE id=?",id));
                require(cash!=null && cash.isSimilar(currency.stack(1)) && cash.getAmount()>=price,"柜中实付"+currency.label+"不足，本批暂缓交割");
                require(inv.getItem(2)==null,"请先取走上笔找付");
                int slot=-1;
                for(int n=3;n<inv.getSize();n++){var stock=inv.getItem(n);if(stock==null || stock.isSimilar(offered) && stock.getAmount()+lot<=64){slot=n;break;}}
                require(slot>=0,"收货柜已满");var stock=inv.getItem(slot);
                inv.setItem(slot,new ItemStack(GOODS.get(goods),lot+(stock==null?0:stock.getAmount())));
                inv.setItem(0,offered.getAmount()==lot?null:new ItemStack(GOODS.get(goods),offered.getAmount()-lot));
                inv.setItem(1,cash.getAmount()==price?null:cash.asQuantity(cash.getAmount()-price));
                inv.setItem(2,cash.asQuantity(price));
                bin.getPersistentDataContainer().set(receipt,PersistentDataType.STRING,UUID.randomUUID()+" "+id+" "+actor);
                require(bin.update(),"柜桶已改变");realm.world.save(true);recover((Barrel)bin.getBlock().getState());
                p.sendMessage("本批交割完成，请从第三格取走 "+price+" "+currency.label);page(p,site,1);
            }
        }
    }
    private String funding(MarketBids.Offer offer){
        if(offer.remaining()==0)return "本单已结";
        var w=realm.world;
        if(!w.isChunkLoaded(offer.x()>>4,offer.z()>>4))return "待到访验资";
        if(!(w.getBlockAt(offer.x(),offer.y(),offer.z()).getState() instanceof Barrel bin) ||
                !offer.stall().equals(bin.getPersistentDataContainer().get(market,PersistentDataType.STRING)))return "柜桶待修";
        var cash=bin.getSnapshotInventory().getItem(1);
        return cash!=null && cash.isSimilar(offer.currency().stack(1)) && cash.getAmount()>=offer.price()
                ? "现款可付一批" : "等待买家补款";
    }
    private void board(Player p,String goods,int page)throws SQLException{
        var listed=bids.listed(null,goods.equals("全部")?null:goods,page);
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        body.add(text("同种货物按每件折银从高到低排列。现款按查看时核对，多张买单可共用一柜。"));
        for(var offer:listed.offers()){
            body.add(text(offer.name()+"　("+offer.x()+", "+offer.y()+", "+offer.z()+")\n"+offer.quote()+"　"+funding(offer)));
            buttons.add(command("查看 #"+offer.id()+" · "+offer.name(),"realm quote "+offer.id()));
        }
        if(listed.offers().isEmpty())body.add(text("当前没有此类收购单。"));
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
            body.add(text("商柜坐标　"+r.getInt(2)+", "+r.getInt(3)+", "+r.getInt(4)+"\n第一格放货，第二格放买方钱币，第三格由卖家领款。"));
            var listed=bids.listed(site,null,page);
            for(var offer:listed.offers()){
                body.add(text("#"+offer.id()+"　"+offer.quote()));
                buttons.add(command("交付 #"+offer.id(),"realm sell "+offer.id()));
            }
            if(listed.offers().isEmpty())body.add(text("目前没有待交割的收购单。"));
            if(p.getUniqueId().toString().equals(r.getString(5)))buttons.add(command("出价收购","realm bidform "+site));
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
    @EventHandler(ignoreCancelled=true)public void click(InventoryClickEvent e){
        var bin=till(e.getView().getTopInventory());if(bin==null)return;
        String actor=e.getWhoClicked().getUniqueId().toString();
        // Only single-slot operations can move goods across the three custody compartments.
        if(e.isShiftClick() || e.getClick()!=ClickType.LEFT && e.getClick()!=ClickType.RIGHT){e.setCancelled(true);return;}
        int slot=e.getRawSlot();if(slot<0 || slot>=bin.getInventory().getSize())return;
        if(slot>=1 && slot!=2){
            if(!actor.equals(bin.getPersistentDataContainer().get(buyer,PersistentDataType.STRING)))e.setCancelled(true);
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
        if(!b.getInventory().isEmpty() || !e.getPlayer().getUniqueId().toString().equals(b.getPersistentDataContainer().get(buyer,PersistentDataType.STRING))){
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
