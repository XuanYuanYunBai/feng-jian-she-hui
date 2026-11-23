package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import static social.feudal.realm.RealmStore.require;

/** Coins remain in one physical envelope until its owners visit a cabinet and divide them. */
final class FamilyCash implements Listener {
    final RealmService realm;
    final FamilyRegister book;
    final NamespacedKey envelope;
    FamilyCash(RealmService realm,FamilyRegister book){
        this.realm=realm;this.book=book;envelope=new NamespacedKey(realm.plugin,"estate_cash");
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
    }
    @EventHandler(ignoreCancelled=true) public void place(BlockPlaceEvent event){
        var item=event.getItemInHand();
        if(item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(envelope,PersistentDataType.STRING)){
            event.setCancelled(true);event.getPlayer().sendMessage("登记分产的封银请在家族私柜办理分银。");
        }
    }
    ItemStack bag(String key,int amount){
        var item=new ItemStack(Material.BARREL);var m=(BlockStateMeta)item.getItemMeta();var barrel=(Barrel)m.getBlockState();
        barrel.getSnapshotInventory().setItem(0,TollCoin.SILVER.stack(amount));m.setBlockState(barrel);
        m.getPersistentDataContainer().set(envelope,PersistentDataType.STRING,key);m.displayName(Component.text("家库封银 · "+amount+" 枚"));item.setItemMeta(m);return item;
    }
    Barrel cabinet(Player player,String home)throws SQLException{
        var block=realm.rooms.site(home,"私柜");
        require(block!=null && block.getState() instanceof Barrel && player.getLocation().distanceSquared(block.getLocation().add(.5,.5,.5))<=36,"请到登记的家族私柜六格内");
        return (Barrel)block.getState();
    }
    void store(Player player,int person,String home,String key,int amount)throws SQLException{
        Politics.id(key);require(amount>=1 && amount<=64,"一封存银为 1–64 枚");
        var s=book.s;var m=realm.manors.register.manor(home);require(m.owner().equals(player.getUniqueId().toString()),"须在自己的私柜备银");
        var bin=cabinet(player,home);var prep=new NamespacedKey(realm.plugin,"estate/pack/"+key);
        if(s.number("SELECT count(*) FROM family_cash WHERE id=?",key)>0)
            require(s.number("SELECT count(*) FROM family_cash WHERE id=? AND owner=? AND amount=? AND manor=? AND packed=0 AND x=? AND y=? AND z=?",key,person,amount,home,bin.getX(),bin.getY(),bin.getZ())==1,"封存号已用");
        if(!bin.getPersistentDataContainer().has(prep)){
            var inv=bin.getSnapshotInventory();var coins=inv.getItem(0);
            require(coins!=null && coins.isSimilar(TollCoin.SILVER.stack(1)) && coins.getAmount()>=amount && inv.getItem(8)==null,"私柜第一格放足银币，第九格留空");
            s.update("INSERT OR IGNORE INTO family_cash(id,owner,amount,manor,x,y,z) VALUES(?,?,?,?,?,?,?)",key,person,amount,home,bin.getX(),bin.getY(),bin.getZ());
            inv.setItem(0,coins.getAmount()==amount?null:coins.asQuantity(coins.getAmount()-amount));inv.setItem(8,bag(key,amount));
            bin.getPersistentDataContainer().set(prep,PersistentDataType.BYTE,(byte)1);
            require(bin.update(false,false),"柜址已变动");realm.world.save(true);
        }
        s.atomic(()->{s.update("UPDATE family_cash SET packed=1 WHERE id=?",key);
            s.update("INSERT INTO family_asset VALUES('存银',?,?)",key,person);return null;});
    }
    void divide(Player player,String home,String key)throws SQLException{
        var s=book.s;int f=book.family(player.getUniqueId().toString());var bin=cabinet(player,home);
        require(realm.manors.register.manor(home).owner().equals(book.account(f)),"须在本家登记的私柜分银");
        int amount=s.number("SELECT amount FROM family_cash WHERE id=? AND packed=1 AND closed=0",key);
        require(amount>0,"无待分封银");require(!encumbered(key),"此封银在有效借据中，请依契交割或撤回未签草案");int owner=s.number("SELECT person FROM family_asset WHERE kind='存银' AND asset=?",key);
        require(owner!=0 && book.person(owner).family()==f,"须由封银的管产一家分配");
        var owners=new LinkedHashMap<Integer,Integer>();
        try(var q=s.sql("SELECT person,units FROM family_interest WHERE kind='存银' AND asset=? ORDER BY units DESC,person",key);var r=q.executeQuery()){
            while(r.next())owners.put(r.getInt(1),r.getInt(2));
        }
        if(owners.isEmpty())owners.put(owner,10000);
        var receipt=new NamespacedKey(realm.plugin,"estate/divide/"+key);
        if(!bin.getPersistentDataContainer().has(receipt)){
            var inv=bin.getSnapshotInventory();var original=inv.getItem(0);
            require(original!=null && original.getAmount()==1 && original.isSimilar(bag(key,amount)),"须把此批原封存银放在私柜第一格");
            int granted=0;var due=new LinkedHashMap<Integer,Integer>();
            for(var entry:owners.entrySet()){int cut=amount*entry.getValue()/10000;due.put(entry.getKey(),cut);granted+=cut;}
            var order=new ArrayList<>(owners.keySet());
            order.sort(Comparator.comparingInt((Integer id)->amount*owners.get(id)%10000).reversed());
            for(int i=0;i<amount-granted;i++)due.merge(order.get(i),1,Integer::sum);
            int slot=1;inv.setItem(0,null);
            for(var entry:due.entrySet())if(entry.getValue()>0){
                while(slot<inv.getSize() && inv.getItem(slot)!=null)slot++;
                require(slot<inv.getSize(),"私柜须留足空格承接各房分银");
                var item=bag(key,entry.getValue());var meta=item.getItemMeta();
                meta.getPersistentDataContainer().remove(envelope);
                meta.displayName(Component.text(book.person(entry.getKey()).name()+" · 应领 "+entry.getValue()+" 银"));item.setItemMeta(meta);
                inv.setItem(slot++,item);
            }
            bin.getPersistentDataContainer().set(receipt,PersistentDataType.BYTE,(byte)1);require(bin.update(false,false),"柜址已变动");realm.world.save(true);
        }
        s.atomic(()->{s.update("UPDATE family_cash SET closed=1 WHERE id=?",key);
            s.update("DELETE FROM family_interest WHERE kind='存银' AND asset=?",key);s.update("DELETE FROM family_asset WHERE kind='存银' AND asset=?",key);return null;});
    }
    boolean encumbered(String key)throws SQLException{
        return book.s.number("SELECT count(*) FROM family_loan WHERE (principal=? AND state='待签') OR (pledge=? AND state='在借')",key,key)>0;
    }
    private void entireFund(int f,String key)throws SQLException{
        int holder=book.s.number("SELECT person FROM family_asset WHERE kind='存银' AND asset=?",key);
        require(holder!=0 && book.person(holder).family()==f && book.holdings.lots(holder).stream().anyMatch(l->l.kind().equals("存银") && l.key().equals(key) && l.deceasedShare()==10000),"须有本家独有且已实物封存的银包");
    }
    void offer(int f,String principal,int borrower,int seasons)throws SQLException{
        var s=book.s;entireFund(f,principal);
        require(!encumbered(principal) && borrower!=f && book.account(borrower)!=null && seasons>=1 && seasons<=64,"借用期为一至六十四季，须指定另一在世家族及未质押的封银");
        s.update("INSERT INTO family_loan(lender,borrower,principal,term,state) VALUES(?,?,?,?,'待签')",f,borrower,principal,seasons);
    }
    void loan(Player p,int f,int id,String home,String key,boolean redeem)throws SQLException{
        var s=book.s;
        int lender=s.number("SELECT lender FROM family_loan WHERE id=?",id),borrower=s.number("SELECT borrower FROM family_loan WHERE id=?",id);
        String principal=s.value("SELECT principal FROM family_loan WHERE id=?",id),state=s.value("SELECT state FROM family_loan WHERE id=?",id);
        require(lender!=0 && (redeem?"在借":"待签").equals(state),"借据状态不符");
        int amount=s.number("SELECT amount FROM family_cash WHERE id=?",principal);
        var bin=cabinet(p,home);
        if(redeem){
            String pledged=s.value("SELECT pledge FROM family_loan WHERE id=?",id);
            require(key.equals(pledged) && (f==lender || f==borrower) && realm.manors.register.manor(home).owner().equals(book.account(lender)),"请将原偿款封银送至债权方私柜，由到场一家交割");
        }else{
            require(f==borrower && realm.manors.register.manor(home).owner().equals(book.account(f)),"请由借方到自家私柜签领");
            String lenderAccount=book.account(lender);
            require(lenderAccount!=null,"贷方家族已绝嗣，这份草案无法签领");
            var other=Bukkit.getPlayer(UUID.fromString(lenderAccount));
            require(other!=null && !other.isDead() && other.getWorld()==p.getWorld() && other.getLocation().distanceSquared(p.getLocation())<=144,"两家代表须到借方私柜交换借据");
            entireFund(f,key);require(!encumbered(key) && s.number("SELECT amount FROM family_cash WHERE id=?",key)==amount,"须指定本家同额封银作为到期偿款保障");
        }
        String consumed=redeem?key:principal;var stamp=new NamespacedKey(realm.plugin,(redeem?"repay/":"loan/")+id);
        if(!bin.getPersistentDataContainer().has(stamp)){
            var inv=bin.getSnapshotInventory();var src=inv.getItem(0);var dest=inv.getItem(8);var coin=TollCoin.SILVER.stack(amount);
            require(src!=null && src.getAmount()==1 && src.isSimilar(bag(consumed,amount)),"将实际交付的原封银放入第一格");
            require(dest==null || (dest.isSimilar(coin) && dest.getAmount()+amount<=64),"第九格须能接收全部实银");
            inv.setItem(0,null);inv.setItem(8,coin.asQuantity(amount+(dest==null?0:dest.getAmount())));
            bin.getPersistentDataContainer().set(stamp,PersistentDataType.BYTE,(byte)1);
            require(bin.update(false,false),"私柜已改建");realm.world.save(true);
        }
        s.atomic(()->{
            s.update("UPDATE family_cash SET closed=1 WHERE id=?",consumed);
            s.update("DELETE FROM family_interest WHERE kind='存银' AND asset=?",consumed);
            s.update("DELETE FROM family_asset WHERE kind='存银' AND asset=?",consumed);
            if(redeem)s.update("UPDATE family_loan SET state='已还' WHERE id=?",id);
            else s.update("UPDATE family_loan SET state='在借',pledge=?,due=?+term WHERE id=?",key,book.season(),id);
            return null;
        });
    }

}
