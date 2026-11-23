package social.feudal.realm;

import java.sql.SQLException;
import org.bukkit.*;
import org.bukkit.block.Barrel;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import static social.feudal.realm.RealmStore.require;

/** The same signed purse must leave the payer's private barrel and reach the receiving pantry. */
final class FamilyPayments {
    private final RealmService realm;
    private final FamilyRegister book;
    private final NamespacedKey purse;
    FamilyPayments(RealmService realm,FamilyRegister book){
        this.realm=realm;this.book=book;purse=new NamespacedKey(realm.plugin,"family_purse");
    }
    record Bill(String key,int payer,int receiver,int due){}
    Bill bill(int match,boolean dowry)throws SQLException{
        var s=book.s;
        var first=book.person(s.number("SELECT a FROM family_match WHERE id=?",match));
        var second=book.person(s.number("SELECT b FROM family_match WHERE id=?",match));
        String state=s.value("SELECT state FROM family_match WHERE id=?",match);
        require("已婚".equals(state)||"遗属".equals(state),"须有生效婚书");
        var payer=first;var receiver=second;int period=0;
        if(!dowry){
            require(!first.alive() ^ !second.alive(),"须有一名在世遗属");
            if(first.alive()){payer=second;receiver=first;}
            period=s.number("SELECT death FROM kin WHERE id=?",payer.id())+
                s.number("SELECT count(*) FROM family_payment WHERE marriage=? AND bill LIKE 'support/%'",match);
            require(period<=book.season(),"此前各季供养均已结清");
        }
        int due=s.number("SELECT "+(dowry?"dowry":"support")+" FROM family_match WHERE id=?",match);
        require(due>0,"婚书无此款项");String key=dowry?"dowry/"+match:"support/"+match+"/"+period;
        require(s.number("SELECT count(*) FROM family_payment WHERE bill=?",key)==0,"此款已经交割");
        require(receiver.alive() || (dowry && book.account(receiver.family())!=null),"受款家族已绝嗣，请查遗产文书");
        return new Bill(key,payer.family(),receiver.family(),due);
    }
    private ItemStack seal(Bill b){
        var item=new ItemStack(Material.BARREL);var meta=(BlockStateMeta)item.getItemMeta();
        var box=(Barrel)meta.getBlockState();box.getSnapshotInventory().setItem(0,TollCoin.SILVER.stack(b.due()));meta.setBlockState(box);
        meta.displayName(Component.text("婚书封银 · "+b.due()+" 枚"));meta.getPersistentDataContainer().set(purse,PersistentDataType.STRING,b.key());
        item.setItemMeta(meta);return item;
    }
    void pack(Player p,int f,int match,String home,boolean dowry)throws SQLException{
        var b=bill(match,dowry);require(f==b.payer(),"须由付款一家亲办");
        var site=realm.rooms.site(home,"私柜");
        require(site!=null && site.getState() instanceof Barrel && realm.manors.register.manor(home).owner().equals(book.account(f)) &&
                p.getLocation().distanceSquared(site.getLocation().add(.5,.5,.5))<=36,"请到本家私柜六格内备银");
        var bin=(Barrel)site.getState();var stamp=new NamespacedKey(realm.plugin,"packed/"+b.key());
        if(book.s.number("SELECT count(*) FROM family_purse WHERE bill=? AND packed=1",b.key())>0){
            p.sendMessage("本期封银已提出，请查原封，勿重复备款。");return;
        }
        String saved=book.s.value("SELECT manor FROM family_purse WHERE bill=?",b.key());
        if(saved!=null)require(book.s.number("SELECT count(*) FROM family_purse WHERE bill=? AND manor=? AND x=? AND y=? AND z=?",b.key(),home,site.getX(),site.getY(),site.getZ())==1,"备款中途改建，请复核原私柜");
        if(!bin.getPersistentDataContainer().has(stamp,PersistentDataType.BYTE)){
            var inv=bin.getSnapshotInventory();var coins=inv.getItem(0);
            require(coins!=null && coins.isSimilar(TollCoin.SILVER.stack(1)) && coins.getAmount()>=b.due() && inv.getItem(8)==null,"私柜第一格放足银币，第九格留空接封银");
            if(saved==null)book.s.update("INSERT INTO family_purse(bill,manor,x,y,z) VALUES(?,?,?,?,?)",b.key(),home,site.getX(),site.getY(),site.getZ());
            inv.setItem(0,coins.getAmount()==b.due()?null:coins.asQuantity(coins.getAmount()-b.due()));inv.setItem(8,seal(b));
            bin.getPersistentDataContainer().set(stamp,PersistentDataType.BYTE,(byte)1);
            require(bin.update(false,false),"私柜已改建");realm.world.save(true);
        }
        book.s.update("UPDATE family_purse SET packed=1 WHERE bill=?",b.key());
        p.sendMessage("请将私柜第九格的原封银运至受款人厅仓第一格。");
    }
    void pay(Player player,int f,int match,String home,boolean dowry)throws SQLException{
        var s=book.s;var b=bill(match,dowry);
        require(f==b.payer() || f==b.receiver(),"须由婚书当事家族交割");
        var manor=realm.manors.register.manor(home);
        require(manor.owner().equals(book.account(b.receiver())) && player.getLocation().distanceSquared(new Location(realm.world,manor.x()+.5,manor.y()+.5,manor.z()+.5))<=64,"须到受益家族的私庄厅仓交付");
        var bin=realm.manors.foodBarrel(manor);require(bin!=null,"未找到登记厅仓");
        var stamp=new NamespacedKey(realm.plugin,"paid/"+b.key());
        if(!bin.getPersistentDataContainer().has(stamp,PersistentDataType.BYTE)){
            var inv=bin.getSnapshotInventory();var bag=inv.getItem(0);var dest=inv.getItem(8);var silver=TollCoin.SILVER.stack(b.due());
            require(bag!=null && bag.isSimilar(seal(b)) && bag.getAmount()==1,"第一格须是付款方私柜提出的本期原封银");
            require(dest==null || (dest.isSimilar(silver) && dest.getAmount()+b.due()<=64),"请腾出第九格作为收款格");
            inv.setItem(0,null);inv.setItem(8,silver.asQuantity(b.due()+(dest==null?0:dest.getAmount())));
            bin.getPersistentDataContainer().set(stamp,PersistentDataType.BYTE,(byte)1);
            require(bin.update(false,false),"仓箱已改建");realm.world.save(true);
        }
        s.atomic(()->{s.update("INSERT INTO family_payment VALUES(?,?,?,?)",b.key(),match,home,b.due());
            if(dowry)s.update("UPDATE family_match SET paid=? WHERE id=?",b.due(),match);return null;});
    }
}
