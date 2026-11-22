package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import io.papermc.paper.registry.data.dialog.ActionButton;
import net.kyori.adventure.text.Component;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

/** One physical bread consignment per request. Freight transfers keep its original item metadata. */
final class ReliefOffice {
    private final RealmService r;
    private final RealmStore s;
    private final NamespacedKey seal,issued,received;
    ReliefOffice(RealmService r)throws SQLException{
        this.r=r;s=r.store;
        seal=new NamespacedKey(r.plugin,"relief");issued=new NamespacedKey(r.plugin,"relief_issued");received=new NamespacedKey(r.plugin,"relief_received");
        s.update("""
            CREATE TABLE IF NOT EXISTS relief(id INTEGER PRIMARY KEY,dest TEXT REFERENCES manor(id),title TEXT REFERENCES title(id),
            source TEXT REFERENCES manor(id),quantity INTEGER CHECK(quantity BETWEEN 1 AND 64),stage INTEGER DEFAULT 0)""");
        s.update("CREATE UNIQUE INDEX IF NOT EXISTS relief_open ON relief(dest) WHERE stage<3");
    }
    private Container pantry(Player p,SettlementRegister.Manor m)throws SQLException{
        require(p.getLocation().distanceSquared(r.world.getBlockAt(m.x(),m.y(),m.z()).getLocation())<=64,"须到庄宅厅旁清点实粮");
        var c=r.manors.foodBarrel(m);require(c!=null,"请先修复庄宅粮桶");return c;
    }
    private String authority(Player p,SettlementRegister.Manor m)throws SQLException{
        String title=s.owner(m.x()>>4,m.z()>>4);
        require(title!=null && s.title(title).seal().equals(p.getUniqueId().toString()),"公共赈济须由所在领地实际持印人办理");
        return title;
    }
    static boolean plain(ItemStack item){return item!=null && item.isSimilar(new ItemStack(Material.BREAD));}
    private int count(Inventory inv){
        int total=0;for(var stack:inv.getContents())if(plain(stack))total+=stack.getAmount();return total;
    }
    void run(Player p,String[] a)throws SQLException{
        r.inWorld(p);RealmService.usage(a.length==2 || a.length==4,"relief 庄园 [申请 面包数|备货 单号|签收 单号|撤回 单号]");
        var m=r.manors.register.manor(a[1]);String actor=p.getUniqueId().toString();
        if(a.length==4){
            int value=Integer.parseInt(a[3]);var bin=pantry(p,m);
            switch(a[2]){
                case "申请" -> {
                    var title=authority(p,m);
                    int credit=s.number("SELECT ration_credit FROM manor WHERE id=?",m.id());
                    int lack=Math.max(0,Math.ceilDiv(m.residents()-credit,100)-count(bin.getInventory()));
                    require(value>0 && value<=Math.min(64,lack),"本庄下季实缺 "+lack+" 个面包，请依实缺开单");
                    require(s.number("SELECT count(*) FROM relief WHERE dest=? AND stage<3",m.id())==0,"本庄已有待办赈单");
                    s.update("INSERT INTO relief(dest,title,quantity) VALUES(?,?,?)",m.id(),title,value);
                }
                case "备货" -> pack(p,m,bin,value,actor);
                case "签收" -> {authority(p,m);deliver(m,bin,value);}
                case "撤回" -> {authority(p,m);require(s.update("UPDATE relief SET stage=4 WHERE id=? AND dest=? AND stage=0",value,m.id())==1,"仅可撤回未备货的本庄赈单");}
                default -> throw new IllegalArgumentException("未知赈济事务");
            }
        }
        var body=new ArrayList<io.papermc.paper.registry.data.dialog.body.DialogBody>();
        var buttons=new ArrayList<ActionButton>();
        body.add(text("熟食由供粮庄现场封装，可随身运输或逐段沿公共货线运送。收粮庄须把原批面包放入厅桶，再由持印人签收。"));
        if(m.residents()>0)buttons.add(command("按实缺开单","realm reliefform "+m.id()));
        for(var key:r.diplomacy.column("SELECT id FROM relief WHERE stage<3 OR dest=? ORDER BY id DESC LIMIT 8",m.id())){
            int id=Integer.parseInt(key);int stage=s.number("SELECT stage FROM relief WHERE id=?",id);
            String dest=s.value("SELECT dest FROM relief WHERE id=?",id);
            body.add(text("#"+id+"　"+r.manors.register.manor(dest).name()+"　"+s.number("SELECT quantity FROM relief WHERE id=?",id)+" 个面包　"+List.of("待备货","待封装","在途","已签收","已撤回").get(stage)));
            if(stage<=1 && !dest.equals(m.id()))buttons.add(command("本庄备货 #"+id,"realm relief "+m.id()+" 备货 "+id));
            if(dest.equals(m.id()) && stage==2)buttons.add(command("验货签收 #"+id,"realm relief "+m.id()+" 签收 "+id));
        }
        show(p,"跨庄赈济 · "+m.name(),body,List.of(),buttons,2);
    }
    private void pack(Player p,SettlementRegister.Manor m,Container bin,int id,String actor)throws SQLException{
        require(r.manors.register.manages(m,actor),"须由供粮庄业主或公产持印人备货");
        require(s.number("SELECT count(*) FROM relief WHERE id=? AND dest<>? AND (stage=0 OR (stage=1 AND source=?))",id,m.id(),m.id())==1,"单号或供粮庄不符");
        require(s.number("SELECT count(*) FROM relief WHERE source=? AND stage=1 AND id<>?",m.id(),id)==0,"请先复核本仓上一笔封装");
        int n=s.number("SELECT quantity FROM relief WHERE id=?",id);
        var inv=bin.getSnapshotInventory();
        if(bin.getPersistentDataContainer().getOrDefault(issued,PersistentDataType.INTEGER,-1)!=id){
            int own=Math.ceilDiv(Math.max(0,m.residents()-s.number("SELECT ration_credit FROM manor WHERE id=?",m.id())),100);
            require(count(inv)>=n+own,"请先备足本庄下季口粮与本批赈粮");
            inv.removeItem(new ItemStack(Material.BREAD,n));
            var batch=new ItemStack(Material.BREAD,n);
            batch.editMeta(meta->{meta.getPersistentDataContainer().set(seal,PersistentDataType.INTEGER,id);meta.customName(Component.text("封装赈粮 #"+id));});
            require(inv.addItem(batch).isEmpty(),"请留一个空格存放封装赈粮");
            s.update("UPDATE relief SET stage=1,source=? WHERE id=?",m.id(),id);
            bin.getPersistentDataContainer().set(issued,PersistentDataType.INTEGER,id);require(bin.update(false,false),"源仓变动");
        }
        r.world.save(true);s.update("UPDATE relief SET stage=2 WHERE id=?",id);p.sendMessage("请从本庄粮桶取出封装赈粮，运至收粮庄。");
    }
    private void deliver(SettlementRegister.Manor m,Container bin,int id)throws SQLException{
        require(s.number("SELECT count(*) FROM relief WHERE id=? AND dest=? AND stage=2",id,m.id())==1,"这份赈单尚未在途或已签收");
        int n=s.number("SELECT quantity FROM relief WHERE id=?",id);
        if(bin.getPersistentDataContainer().getOrDefault(received,PersistentDataType.INTEGER,-1)!=id){
            int found=0;var inv=bin.getSnapshotInventory();
            for(int i=0;i<inv.getSize();i++){
                var item=inv.getItem(i);
                if(item==null || item.getType()!=Material.BREAD || item.getItemMeta().getPersistentDataContainer().getOrDefault(seal,PersistentDataType.INTEGER,-1)!=id)continue;
                found+=item.getAmount();inv.setItem(i,new ItemStack(Material.BREAD,item.getAmount()));
            }
            require(found==n,"目的仓原批赈粮实到 "+found+"／"+n+" 个，补齐后签收");
            bin.getPersistentDataContainer().set(received,PersistentDataType.INTEGER,id);require(bin.update(false,false),"目的仓变动");
        }
        r.world.save(true);s.update("UPDATE relief SET stage=3 WHERE id=?",id);
    }
}
