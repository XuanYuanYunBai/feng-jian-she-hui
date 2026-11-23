package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import static social.feudal.realm.RealmService.*;
import static social.feudal.realm.RealmStore.require;

final class TaxOffice {
    final RealmService realm;
    final FiscalLedger ledger;
    final TaxCouriers couriers;
    private final NamespacedKey tag;
    private int activeSeconds;
    TaxOffice(RealmService realm)throws SQLException{
        this.realm=realm;ledger=new FiscalLedger(realm.store,realm.plugin.getConfig().getInt("realm.chunks-per-tax-step",1));
        tag=new NamespacedKey(realm.plugin,"tax_crate");
        couriers=new TaxCouriers(this);
        activeSeconds=realm.store.number("SELECT v FROM setting WHERE k='seconds'");
        if(realm.store.ready())openPeriod(ledger.period());
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::tick,20,20);
    }
    void openPeriod(int period)throws SQLException{
        var tendered=new HashMap<String,Integer>();
        for(var d:realm.store.depots()){
            var bin=realm.fixtures.bin(d.x(),d.z(),"stock",d.id());
            if(bin==null)continue;
            var reserved=realm.store.number("""
                SELECT coalesce(sum(due-automatic-packed),0) FROM assessment JOIN tendered_tax USING(depot,period) WHERE depot=?
                """,d.id())+realm.store.number("""
                SELECT coalesce(sum(amount),0) FROM crate JOIN parcel USING(id) JOIN tendered_tax USING(depot,period)
                WHERE depot=? AND staged=0""",d.id());
            tendered.put(d.id(),Math.max(0,count(WarehouseFixtures.inventory(bin))-reserved));
        }
        ledger.begin(period,tendered);
    }
    private void tick(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try{
            if(!realm.store.ready())return;
            if(++activeSeconds>=1200){openPeriod(ledger.period()+1);activeSeconds=0;}
            if(activeSeconds%10==0){
                saveTime();
                for(var t:realm.store.titles())migrate(t);
                for(var d:realm.store.depots()){
                    Container stock=realm.fixtures.bin(d.x(),d.z(),"stock",d.id());
                    if(stock!=null){
                        if(realm.store.value("SELECT title FROM assessment WHERE depot=? AND period=?",d.id(),ledger.period())==null && count(WarehouseFixtures.inventory(stock))>0)
                            openPeriod(ledger.period());
                        stage(d,stock);
                    }
                }
            }
            for(var d:realm.store.depots()){
                var stock=realm.fixtures.bin(d.x(),d.z(),"stock",d.id());
                if(stock!=null)couriers.tick(d,stock);
            }
        }catch(SQLException ex){realm.plugin.getLogger().log(Level.SEVERE,"税务交割失败",ex);}
    }
    void saveTime()throws SQLException{realm.store.update("INSERT OR REPLACE INTO setting VALUES('seconds',?)",activeSeconds);}
    private NamespacedKey receipt(String name){return new NamespacedKey(realm.plugin,name);}
    Container vault(RealmStore.Title t){return realm.fixtures.bin(t.x(),t.z(),"vault",t.id());}
    private static int count(Inventory inv){
        return inv.all(Material.WHEAT).values().stream().filter(i->i.isSimilar(new ItemStack(Material.WHEAT))).mapToInt(ItemStack::getAmount).sum();
    }
    String balance(RealmStore.Title t){
        var bin=vault(t);
        return bin==null?"待到场盘点":count(WarehouseFixtures.inventory(bin))+" 份小麦";
    }
    private static int room(Inventory inv){
        var wheat=new ItemStack(Material.WHEAT);int room=0;
        for(var slot:inv.getStorageContents())room+=slot==null || slot.isEmpty()?64:slot.isSimilar(wheat)?64-slot.getAmount():0;
        return room;
    }
    private static void add(Inventory inv,int amount){
        while(amount>0){int n=Math.min(amount,64);inv.addItem(new ItemStack(Material.WHEAT,n));amount-=n;}
    }
    // The chest and its chunk receipt persist together, including when a player rebinds the fixture.
    private void migrate(RealmStore.Title t)throws SQLException{
        int old=ledger.legacy(t.id());if(old<=0)return;
        var bin=vault(t);if(bin==null)return;
        var inv=WarehouseFixtures.inventory(bin);var data=bin.getChunk().getPersistentDataContainer();
        var key=receipt("legacy/"+t.id());
        int left=data.getOrDefault(key,PersistentDataType.INTEGER,old);
        int amount=Math.min(left,room(inv));
        if(amount>0){add(inv,amount);left-=amount;data.set(key,PersistentDataType.INTEGER,left);realm.world.save(true);}
        if(left!=old)realm.store.update("UPDATE treasury SET grain=? WHERE title=?",left,t.id());
    }
    void stage(RealmStore.Depot d,Container bin)throws SQLException{
        var periods=new ArrayList<Integer>();
        try(var q=realm.store.sql("SELECT period FROM assessment WHERE depot=? AND due>automatic+packed ORDER BY period",d.id());var r=q.executeQuery()){
            while(r.next())periods.add(r.getInt(1));
        }
        for(int period:periods)ledger.prepare(d,period);
        var inv=WarehouseFixtures.inventory(bin);
        var data=bin.getChunk().getPersistentDataContainer();
        var stocked=new ArrayList<String>();
        for(String id:ledger.pending(d.id())){
            var key=receipt("staged/"+id);
            if(!data.has(key)){
                int slot=inv.firstEmpty();if(slot<0)break;
                var crate=ledger.crate(id);
                var parcel=box(crate,d);
                if(realm.store.number("SELECT count(*) FROM tendered_tax JOIN crate USING(depot,period) WHERE crate.id=?",id)>0){
                    if(count(inv)<crate.amount())break;
                    inv.removeItem(new ItemStack(Material.WHEAT,crate.amount()));
                }
                inv.setItem(slot,parcel);
                data.set(key,PersistentDataType.BYTE,(byte)1);
            }
            stocked.add(id);
        }
        if(stocked.isEmpty())return;
        realm.world.save(true);
        realm.store.atomic(()->{
            for(String id:stocked)realm.store.update("UPDATE parcel SET staged=1 WHERE id=?",id);
            return null;
        });
        for(String id:stocked)data.remove(receipt("staged/"+id));
    }
    private ItemStack box(FiscalLedger.Crate c,RealmStore.Depot d)throws SQLException{
        var box=new ItemStack(Material.BARREL);var meta=(BlockStateMeta)box.getItemMeta();
        var contents=(Barrel)meta.getBlockState();
        contents.getSnapshotInventory().setItem(0,new ItemStack(Material.WHEAT,c.amount()));meta.setBlockState(contents);
        meta.displayName(Component.text("封缄税箱 · "+c.amount()+" 份小麦"));
        meta.lore(List.of(Component.text(d.name()+" → "+realm.store.title(c.title()).name()),Component.text("运到公库后手持此箱交割")));
        meta.getPersistentDataContainer().set(tag,PersistentDataType.STRING,c.id());box.setItemMeta(meta);return box;
    }
    String id(ItemStack item){
        return item==null || !item.hasItemMeta()?null:item.getItemMeta().getPersistentDataContainer().get(tag,PersistentDataType.STRING);
    }
    boolean holdsCrate(Player p){return id(p.getInventory().getItemInMainHand())!=null;}
    private static void nearby(Player p,Location point){
        require(point!=null && p.getWorld().equals(point.getWorld()) && p.getLocation().distanceSquared(point.clone().add(.5,.5,.5))<=36,"请到登记设施六格内办理；拆除后须重新登记");
    }
    void deliver(ItemStack item,Container bin)throws SQLException{
        String id=id(item);require(id!=null,"请手持封缄税箱");var c=ledger.crate(id);
        require(!c.received(),"此箱已入库");
        require(item.getItemMeta() instanceof BlockStateMeta m && m.getBlockState() instanceof Barrel,"封缄无效");
        var grain=((Barrel)((BlockStateMeta)item.getItemMeta()).getBlockState()).getSnapshotInventory().getItem(0);
        require(grain!=null && grain.isSimilar(new ItemStack(Material.WHEAT)) && grain.getAmount()==c.amount(),"箱内小麦与清单不符");
        var inv=WarehouseFixtures.inventory(bin);
        var data=bin.getChunk().getPersistentDataContainer();var key=receipt("delivered/"+id);
        if(!id.equals(data.get(key,PersistentDataType.STRING))){
            require(room(inv)>=c.amount(),"公库仓箱已满，请腾出粮位");
            add(inv,c.amount());data.set(key,PersistentDataType.STRING,id);realm.world.save(true);
        }
        ledger.receive(id);data.remove(key);
    }
    private void collect(Player p,String[] a)throws SQLException{
        usage(a.length==2 || a.length==3,"collect 税仓ID [税期]");
        var d=ledger.depot(a[1]);nearby(p,realm.markers.counter(d.x(),d.z()));
        int period=a.length==3?Integer.parseInt(a[2]):ledger.period();
        String title=ledger.account(d.id(),period).title();
        require(realm.store.title(title).seal().equals(p.getUniqueId().toString()),"请由本税期期初权利人的持印人提运");
        var bin=realm.fixtures.bin(d.x(),d.z(),"stock",d.id());require(bin!=null,"请登记产地仓箱");
        stage(d,bin);var inv=WarehouseFixtures.inventory(bin);
        int amount=0;
        for(int slot=0;slot<inv.getSize();slot++){
            var item=inv.getItem(slot);String id=id(item);
            if(id==null || realm.store.number("SELECT count(*) FROM crate WHERE id=? AND depot=? AND period=? AND received=0",id,d.id(),period)==0)continue;
            if(p.getInventory().firstEmpty()<0)break;
            realm.store.update("UPDATE crate SET carrier=? WHERE id=?",p.getUniqueId().toString(),id);
            inv.setItem(slot,null);p.getInventory().addItem(item);amount+=ledger.crate(id).amount();
        }
        require(amount>0,"仓箱中没有可提的当期税箱，或背包已满");p.sendMessage("已从产地仓箱提取 "+amount+" 份小麦，请随身运往公库");
    }
    boolean canRead(CommandSender sender,String title)throws SQLException{
        return sender.hasPermission("feudal.realm.admin") || sender instanceof Player p && realm.store.title(title).seal().equals(p.getUniqueId().toString());
    }
    void execute(CommandSender s,String[] a)throws SQLException{
        switch(a[0]){
            case "ledger" -> {
                int period=a.length==2?Integer.parseInt(a[1]):ledger.period();
                for(var d:realm.store.depots()){
                    if(realm.store.value("SELECT title FROM assessment WHERE depot=? AND period=?",d.id(),period)==null)continue;
                    var row=ledger.account(d.id(),period);if(!canRead(s,row.title()))continue;
                    var w=ledger.remissions.latest(d.id(),period);
                    String retained=w!=null && w.state().equals("已减征")?"  减征留乡 "+w.spared():"";
                    s.sendMessage(d.name()+retained+" · 应收 "+row.due()+"  自动份额 "+ledger.quota(d.id(),period)+"  尚待装箱 "+row.remaining());
                }
            }
            case "treasury" -> {usage(a.length==2,"treasury 头衔ID");var t=realm.store.title(a[1]);require(canRead(s,t.id()),"公库仅向本领持印人开放");s.sendMessage(t.name()+"公库 · "+balance(t));}
            case "collect" -> collect(player(s),a);
            case "reissue" -> s.sendMessage("请在原仓箱、承运人背包或驮骡上查找原税箱。潜行右键脚夫可接运。");
            case "deposit" -> {
                var p=player(s);var item=p.getInventory().getItemInMainHand();String id=id(item);require(id!=null,"请手持封缄税箱");
                var c=ledger.crate(id);var t=realm.store.title(c.title());var bin=vault(t);require(bin!=null,"领地驻地须登记公库箱");
                nearby(p,bin.getLocation());deliver(item,bin);item.subtract();p.sendMessage(t.name()+"公库实收入箱 "+c.amount()+" 份小麦");
            }
            case "visit","gift" -> {
                boolean visit=a[0].equals("visit");usage(a.length==(visit?2:3),visit?"visit 直属领ID":"gift 本领头衔ID 粮数");
                var p=player(s);var t=realm.store.title(a[1]);var bin=vault(t);require(bin!=null,"请前往当地登记公库");
                nearby(p,bin.getLocation());var inv=WarehouseFixtures.inventory(bin);int stock=count(inv);
                String actor=p.getUniqueId().toString();require(t.parent()!=null,"此头衔没有直属上级");
                int n;
                if(visit){require(room(p.getInventory())>=4,"请留出四份小麦的背包空间");n=ledger.visit(t.id(),actor,realm.council.succession.support(t.id()),stock);}
                else{require(t.seal().equals(actor),"须由当地持印人决定赠纳");n=Integer.parseInt(a[2]);require(n>0 && n<=stock && n<=room(p.getInventory()),"请核对数量、公库存粮与背包空间");}
                if(n==0){s.sendMessage("当地持印人谢绝赠纳");return;}
                for(int left=n;left>0;left-=64)inv.removeItem(new ItemStack(Material.WHEAT,Math.min(left,64)));
                add(p.getInventory(),n);s.sendMessage("已从本地公库提取赠粮 "+n+" 份。请运至"+realm.store.title(t.parent()).name()+"公库，放入仓箱。");
            }
            default -> throw new IllegalArgumentException("未知财政操作");
        }
    }
}
