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
    private final NamespacedKey market,receipt,vendor,buyer;
    private static final Map<String,Material> GOODS=Map.of("小麦",Material.WHEAT,"原木",Material.OAK_LOG,
            "羊毛",Material.WHITE_WOOL,"皮革",Material.LEATHER,"干草",Material.HAY_BLOCK,"铜锭",Material.COPPER_INGOT,"铁锭",Material.IRON_INGOT);
    MarketOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;market=new NamespacedKey(realm.plugin,"market_stall");receipt=new NamespacedKey(realm.plugin,"trade_receipt");
        vendor=new NamespacedKey(realm.plugin,"offering_seller");buyer=new NamespacedKey(realm.plugin,"stall_owner");
        s.update("CREATE TABLE IF NOT EXISTS stall(id TEXT PRIMARY KEY,owner TEXT,name TEXT,x INTEGER,y INTEGER,z INTEGER,UNIQUE(x,y,z))");
        s.update("CREATE TABLE IF NOT EXISTS bid(id INTEGER PRIMARY KEY,stall TEXT REFERENCES stall(id),goods TEXT,lot INTEGER,price INTEGER,remaining INTEGER)");
        s.update("CREATE TABLE IF NOT EXISTS trade(id TEXT PRIMARY KEY,bid INTEGER REFERENCES bid(id),seller TEXT NOT NULL)");
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }
    private Block location(String id)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM stall WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"未知商站");require(realm.world.isChunkLoaded(r.getInt(1)>>4,r.getInt(3)>>4),"请到市场交易");
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
        s.atomic(()->{
            if(s.update("INSERT OR IGNORE INTO trade VALUES(?,?,?)",parts[0],id,parts[2])==1)
                require(s.update("UPDATE bid SET remaining=remaining-1 WHERE id=? AND remaining>0",id)==1,"买单余额需要核对");
            return null;
        });
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
                bin.getPersistentDataContainer().set(buyer,PersistentDataType.STRING,actor);bin.getPersistentDataContainer().set(market,PersistentDataType.STRING,a[1]);bin.update();page(p,a[1]);
            }
            case "market" -> {usage(a.length==2,"market 商站");page(p,a[1]);}
            case "bidform" -> {
                usage(a.length==2,"bidform 商站");DiplomacyDesk.form(p,"张贴收购单","首格供卖家放货，第二格放入买方实付金粒，第三格找付。库存不足即停单。","realm bid "+a[1],
                        List.of(new DiplomacyDesk.Field("goods","小麦、原木、羊毛、皮革、干草、铜锭或铁锭","小麦",8),
                                new DiplomacyDesk.Field("lot","每批件数","8",2),new DiplomacyDesk.Field("price","每批金粒","2",2),new DiplomacyDesk.Field("lots","收购批数","4",4)));
            }
            case "bid" -> {
                usage(a.length==6,"bid 商站 货种 每批件数 每批金粒 批数");var bin=at(p,a[1]);
                require(actor.equals(s.value("SELECT owner FROM stall WHERE id=?",a[1])),"请由商站主人出价");
                int lot=Integer.parseInt(a[3]),price=Integer.parseInt(a[4]),lots=Integer.parseInt(a[5]);
                require(GOODS.containsKey(a[2]) && lot>0 && lot<=64 && price>0 && price<=64 && lots>0 && lots<=4096,"货种或报价超出范围");
                var coins=bin.getSnapshotInventory().getItem(1);
                require(coins!=null && coins.isSimilar(new ItemStack(Material.GOLD_NUGGET)) && coins.getAmount()>=price,"商站第二格须有至少一批实付金粒");
                s.update("INSERT INTO bid(stall,goods,lot,price,remaining) VALUES(?,?,?,?,?)",a[1],a[2],lot,price,lots);page(p,a[1]);
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
                require(cash!=null && cash.isSimilar(new ItemStack(Material.GOLD_NUGGET)) && cash.getAmount()>=price,"买方现有金粒不足");
                require(inv.getItem(2)==null,"请先取走上笔找付");
                int slot=-1;
                for(int n=3;n<inv.getSize();n++){var stock=inv.getItem(n);if(stock==null || stock.isSimilar(offered) && stock.getAmount()+lot<=64){slot=n;break;}}
                require(slot>=0,"收货柜已满");var stock=inv.getItem(slot);
                inv.setItem(slot,new ItemStack(GOODS.get(goods),lot+(stock==null?0:stock.getAmount())));
                inv.setItem(0,offered.getAmount()==lot?null:new ItemStack(GOODS.get(goods),offered.getAmount()-lot));
                inv.setItem(1,cash.getAmount()==price?null:new ItemStack(Material.GOLD_NUGGET,cash.getAmount()-price));
                inv.setItem(2,new ItemStack(Material.GOLD_NUGGET,price));
                bin.getPersistentDataContainer().set(receipt,PersistentDataType.STRING,UUID.randomUUID()+" "+id+" "+actor);
                require(bin.update(),"柜桶已改变");realm.world.save(true);recover((Barrel)bin.getBlock().getState());
                p.sendMessage("本批交割完成，请从第三格取走 "+price+" 金粒");page(p,site);
            }
        }
    }
    private void page(Player p,String site)throws SQLException{
        String name=s.value("SELECT name FROM stall WHERE id=?",site);require(name!=null,"未知商站");
        var buttons=new ArrayList<ActionButton>();var body=new ArrayList<DialogBody>();
        body.add(text("第一格待售货物，第二格买家实付金粒，第三格卖家找付。先放货，再点击对应订单交割。"));
        for(String id:realm.diplomacy.column("SELECT id FROM bid WHERE stall=? AND remaining>0 ORDER BY id DESC LIMIT 20",site)){
            body.add(text(s.value("SELECT goods || ' ×' || lot || '　每批 ' || price || ' 金粒　剩 ' || remaining || ' 批' FROM bid WHERE id=?",id)));
            buttons.add(command("交付 #"+id,"realm sell "+id));
        }
        if(p.getUniqueId().toString().equals(s.value("SELECT owner FROM stall WHERE id=?",site)))buttons.add(command("出价收购","realm bidform "+site));
        show(p,name,body,List.of(),buttons,2);
    }
    @EventHandler(ignoreCancelled=true)public void inspect(PlayerInteractEvent e)throws SQLException{
        if(e.getHand()!=EquipmentSlot.HAND || e.getAction()!=Action.RIGHT_CLICK_BLOCK || e.getClickedBlock()==null)return;
        Block b=e.getClickedBlock();if(b.getType()!=Material.OAK_SIGN || b.getWorld()!=realm.world)return;
        var below=b.getRelative(BlockFace.DOWN);if(!(below.getState() instanceof Barrel bin))return;
        var id=bin.getPersistentDataContainer().get(market,PersistentDataType.STRING);if(id!=null){e.setCancelled(true);page(e.getPlayer(),id);}
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
        s.update("UPDATE stall SET x=NULL,y=NULL,z=NULL WHERE id=?",id);
    }
    @EventHandler(ignoreCancelled=true)public void hopper(InventoryMoveItemEvent e){
        for(var inv:List.of(e.getSource(),e.getDestination()))
            if(inv.getHolder(false) instanceof Barrel bin && bin.getPersistentDataContainer().has(market)){e.setCancelled(true);return;}
    }
}
