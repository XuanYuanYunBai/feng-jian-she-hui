package social.feudal.realm;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.block.Barrel;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import static social.feudal.realm.RealmService.*;
import static social.feudal.realm.RealmStore.require;

final class TaxOffice {
    private final RealmService realm;
    final FiscalLedger ledger;
    private final NamespacedKey tag;
    private int activeSeconds;
    TaxOffice(RealmService realm)throws SQLException{
        this.realm=realm;ledger=new FiscalLedger(realm.store,realm.plugin.getConfig().getInt("realm.chunks-per-tax-step",1));tag=new NamespacedKey(realm.plugin,"tax_crate");
        activeSeconds=realm.store.number("SELECT v FROM setting WHERE k='seconds'");
        if(realm.store.ready())ledger.begin(ledger.period());
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::tick,20,20);
    }
    private void tick(){
        if(Bukkit.getOnlinePlayers().isEmpty())return;
        try{
            if(!realm.store.ready())return;
            activeSeconds++;
            if(activeSeconds>=1200){
                ledger.begin(ledger.period()+1);
                activeSeconds=0;
            }
            if(activeSeconds%10==0)saveTime();
        } catch(SQLException ex){realm.plugin.getLogger().log(Level.SEVERE,"税期结算失败",ex);}
    }
    void saveTime()throws SQLException{realm.store.update("INSERT OR REPLACE INTO setting VALUES('seconds',?)",activeSeconds);}
    void nearby(Player p,int x,int z){
        realm.inWorld(p);var l=p.getLocation();
        int bx=x*16+8,bz=z*16+8;
        require(Math.abs(l.getX()-bx)<=15 && Math.abs(l.getZ()-bz)<=15,"请亲赴税仓所在区块 "+x+"，"+z);
        var desk=realm.markers.counter(x,z);
        require(Math.abs(l.getX()-desk.getX()-.5)<=5 && Math.abs(l.getZ()-desk.getZ()-.5)<=5
                && Math.abs(l.getY()-desk.getY())<=6,"请到库址地表账台交割");
    }
    boolean holdsCrate(Player p){
        var item=p.getInventory().getItemInMainHand();
        return item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(tag,PersistentDataType.STRING);
    }
    void execute(CommandSender s,String[] a)throws SQLException{
        switch(a[0]){
            case "ledger" -> {
                usage(a.length<=2,"ledger [税期]");int period=a.length==2?Integer.parseInt(a[1]):ledger.period();
                s.sendMessage("◆ 第 "+period+" 税期 · 税仓账簿   应收 / 自动 / 装箱 / 待征");
                for(var d:realm.store.depots()){
                    if(realm.store.value("SELECT title FROM assessment WHERE depot=? AND period=?",d.id(),period)==null)continue;
                    var row=ledger.account(d.id(),period);
                    s.sendMessage(d.name()+" "+d.id()+"   "+row.due()+" / "+row.automatic()+" / "+row.packed()+" / "+row.remaining()+"   期初 "+realm.store.title(row.title()).name());
                }
            }
            case "treasury" -> {
                usage(a.length==2,"treasury 头衔ID");var t=realm.store.title(a[1]);s.sendMessage(t.name()+"公库 · 存粮 "+ledger.balance(t.id()));
            }
            case "collect","reissue" -> {
                usage(a.length==2 || a.length==3,a[0]+" 税仓ID [税期]");var p=player(s);var d=ledger.depot(a[1]);nearby(p,d.x(),d.z());
                require(p.getInventory().firstEmpty()>=0,"请腾出一个物品栏槽位");
                int period=a.length==3?Integer.parseInt(a[2]):ledger.period();String actor=p.getUniqueId().toString();
                var c=a[0].equals("collect")?ledger.collect(d.id(),period,actor):ledger.outstanding(d.id(),period,actor);
                var box=new ItemStack(Material.BARREL);var meta=(BlockStateMeta)box.getItemMeta();
                Barrel contents=(Barrel)meta.getBlockState();
                contents.getSnapshotInventory().setItem(0,new ItemStack(Material.WHEAT,c.amount()));
                meta.setBlockState(contents);
                meta.displayName(Component.text("封缄税箱 · "+c.amount()+" 份小麦"));
                meta.lore(List.of(Component.text("来源 "+d.name()+" / 第 "+period+" 税期"),Component.text("运回所属公库，手持税箱右键账台交付")));
                meta.getPersistentDataContainer().set(tag,PersistentDataType.STRING,c.id());box.setItemMeta(meta);
                p.getInventory().addItem(box);s.sendMessage("领到实物税箱 · "+c.amount()+" 份小麦，请运回所属公库");
            }
            case "deposit" -> {
                var p=player(s);var item=p.getInventory().getItemInMainHand();
                require(item.hasItemMeta(),"请主手持封缄税箱");
                var id=item.getItemMeta().getPersistentDataContainer().get(tag,PersistentDataType.STRING);
                require(id!=null,"请主手持封缄税箱");var c=ledger.crate(id);var t=realm.store.title(c.title());nearby(p,t.x(),t.z());
                if(item.getItemMeta() instanceof BlockStateMeta meta && meta.hasBlockState()){
                    var barrel=(Barrel)meta.getBlockState();
                    var grain=barrel.getSnapshotInventory().getItem(0);
                    require(grain!=null && grain.getType()==Material.WHEAT && grain.getAmount()==c.amount(),"税箱实物与账本不符");
                }
                int n=ledger.receive(id);item.subtract();s.sendMessage(t.name()+"公库已实收 "+n+" 份小麦");
            }
            case "visit" -> {
                usage(a.length==2,"visit 直属领ID");var p=player(s);var t=realm.store.title(a[1]);nearby(p,t.x(),t.z());
                int amount=ledger.visit(t.id(),p.getUniqueId().toString(),realm.council.succession.support(t.id()));
                s.sendMessage(amount==0?"当地持印人谢绝赠纳":t.name()+"自愿赠粮 "+amount+" 份，双方公库已记账");
            }
            case "gift" -> {
                usage(a.length==3,"gift 本领头衔ID 粮数");ledger.gift(a[1],player(s).getUniqueId().toString(),Integer.parseInt(a[2]));
                s.sendMessage("赠纳已记入双方公库");
            }
            default -> throw new IllegalArgumentException("未知财政操作");
        }
    }
}
