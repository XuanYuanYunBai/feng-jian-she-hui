package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.entity.Player;
import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

/** A camp is a real sleeping and storage place. Paid procurement uses the funded market order. */
final class FamilyLivelihood {
    private final RealmService realm;
    private final RealmStore s;
    private final FamilyRegister book;
    FamilyLivelihood(RealmService realm,FamilyRegister book)throws SQLException{
        this.realm=realm;this.book=book;s=realm.store;
        s.update("CREATE TABLE IF NOT EXISTS family_camp(family INTEGER PRIMARY KEY REFERENCES family(id),x INTEGER,y INTEGER,z INTEGER,UNIQUE(x,y,z))");
        s.update("CREATE TABLE IF NOT EXISTS family_lease(id INTEGER PRIMARY KEY,manor TEXT REFERENCES manor(id),lessor INTEGER REFERENCES family(id),lessee INTEGER REFERENCES family(id),until INTEGER,state TEXT)");
        s.update("CREATE TABLE IF NOT EXISTS family_guest(manor TEXT REFERENCES manor(id),guest INTEGER REFERENCES family(id),host TEXT,settlers INTEGER DEFAULT 0,PRIMARY KEY(manor,guest))");
        if(s.number("SELECT count(*) FROM pragma_table_info('family_guest') WHERE name='settlers'")==0)s.update("ALTER TABLE family_guest ADD COLUMN settlers INTEGER DEFAULT 0");
    }
    void camp(Player p,int family)throws SQLException{
        var aim=p.getTargetBlockExact(5);require(aim!=null && aim.getType()==Material.BARREL,"请瞄准营地实物桶");
        boolean bed=false,fire=false,roof=false;
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=-1;dy<=2;dy++){
            var b=aim.getRelative(dx,dy,dz);
            if(Tag.BEDS.isTagged(b.getType()))bed=true;
            if((b.getType()==Material.CAMPFIRE || b.getType()==Material.SOUL_CAMPFIRE) && b.getBlockData() instanceof Lightable l && l.isLit())fire=true;
            if(dy==2 && (Tag.WOOL.isTagged(b.getType()) || Tag.PLANKS.isTagged(b.getType())))roof=true;
        }
        require(bed && fire && roof,"营桶三格内须有床、燃烧营火，上方有羊毛或木板棚顶");
        String title=s.owner(aim.getX()>>4,aim.getZ()>>4),account=book.account(family);
        String host=s.value("SELECT id FROM manor WHERE ? BETWEEN west AND east AND ? BETWEEN north AND south",aim.getX(),aim.getZ());
        boolean guest=host!=null && canStay(family,host);
        require(guest || (host==null && (title==null || s.title(title).seal().equals(account))),"请选无主地、自辖空地或已获寄居许可的庄园");
        require(s.number("SELECT count(*) FROM family_camp WHERE x=? AND y=? AND z=? AND family<>?",aim.getX(),aim.getY(),aim.getZ(),family)==0,"此营桶已有家族");
        s.update("INSERT INTO family_camp VALUES(?,?,?,?) ON CONFLICT(family) DO UPDATE SET x=excluded.x,y=excluded.y,z=excluded.z",family,aim.getX(),aim.getY(),aim.getZ());
    }
    boolean canStay(int family,String manor)throws SQLException{
        return s.number("""
            SELECT count(*) FROM manor m WHERE id=? AND (owner=? OR EXISTS(SELECT 1 FROM family_guest g
            WHERE g.manor=m.id AND g.guest=? AND g.host=m.owner) OR EXISTS(SELECT 1 FROM family_lease l WHERE l.manor=m.id
            AND l.lessee=? AND l.state='已签' AND l.until>?))""",manor,book.account(family),family,family,book.season())>0;
    }
    void invite(int host,int guest,String manor)throws SQLException{
        require(host!=guest && book.account(guest)!=null,"须指定另一在世家族");
        require(book.account(host).equals(s.value("SELECT owner FROM manor WHERE id=?",manor)),"须由私庄业主办理");
        s.update("INSERT OR REPLACE INTO family_guest(manor,guest,host) VALUES(?,?,?)",manor,guest,book.account(host));
    }
    void lease(int issuer,int guest,String manor,int seasons)throws SQLException{
        require(book.account(issuer).equals(s.value("SELECT owner FROM manor WHERE id=?",manor)) && guest!=issuer && book.account(guest)!=null,"须由业主向另一在世家族出约");
        require(seasons>=1 && seasons<=64,"契期一至六十四季");
        require(s.number("SELECT count(*) FROM family_lease WHERE manor=? AND lessee=? AND until>? AND state IN ('待签','已签')",manor,guest,book.season())==0,"已有本处契约");
        s.update("INSERT INTO family_lease(manor,lessor,lessee,until,state) VALUES(?,?,?,?,'待签')",manor,issuer,guest,book.season()+seasons);
    }
    void signLease(Player p,int f,int id)throws SQLException{
        String home=s.value("SELECT manor FROM family_lease WHERE id=? AND lessee=? AND state='待签' AND until>?",id,f,book.season());
        require(home!=null,"查无本家待签租约");var m=realm.manors.register.manor(home);
        int host=s.number("SELECT lessor FROM family_lease WHERE id=?",id);
        require(m.owner().equals(book.account(host)),"业主已变，请另拟新约");
        var other=Bukkit.getPlayer(UUID.fromString(book.account(host)));
        require(other!=null && !other.isDead() && other.getWorld()==p.getWorld() && other.getLocation().distanceSquared(p.getLocation())<=144 &&
            p.getLocation().distanceSquared(new Location(realm.world,m.x()+.5,m.y()+.5,m.z()+.5))<=64,"两家须同到出租庄厅");
        s.update("UPDATE family_lease SET state='已签' WHERE id=?",id);
    }
    void settlers(int f,int guest,String home)throws SQLException{
        require(s.update("UPDATE family_guest SET settlers=100 WHERE guest=? AND manor=? AND host=? AND host=(SELECT owner FROM manor WHERE id=?)",guest,home,book.account(f),home)==1,"须由本庄业主为已受邀家族拨户");
    }
    void arrive(int family,int rep,String manor)throws SQLException{
        require(canStay(family,manor),"须先取得主人签发的寄居文书");var old=book.person(rep).home();
        require(!manor.equals(old),"已经在本庄居住");
        s.atomic(()->{
            if(old==null)require(realm.families.life.freeResidents(manor)>0,"庄内没有可具名的居民名额");
            else{
                require(s.update("UPDATE manor SET residents=residents+1 WHERE id=? AND housing>residents",manor)==1,"目的庄住房不足");
                require(s.update("UPDATE manor SET residents=residents-1 WHERE id=? AND residents>0",old)==1,"原庄人数待复核");
            }
            s.update("UPDATE kin SET home=? WHERE id=?",manor,rep);return null;
        });
    }
    void page(int family,List<DialogBody> body,List<ActionButton> buttons)throws SQLException{
        try(var q=s.sql("SELECT x,y,z FROM family_camp WHERE family=?",family);var r=q.executeQuery()){
            if(r.next())body.add(text("本家营桶　"+r.getInt(1)+"，"+r.getInt(2)+"，"+r.getInt(3)+"　搬营须自行运货"));
        }
        buttons.add(command("登记脚下营地","realm family 扎营"));
        buttons.add(command("拨户重立庄","realm family 填表 拨户"));buttons.add(command("允许他家寄居","realm family 填表 请客"));buttons.add(command("到庄寄居","realm family 填表 入住"));
        for(int id:book.ids("SELECT id FROM family_lease WHERE (lessor=? OR lessee=?) AND until>? ORDER BY id",family,family,book.season())){
            String state=s.value("SELECT state FROM family_lease WHERE id=?",id);
            body.add(text("居住租约 #"+id+"　"+s.value("SELECT manor||' · 至第 '||until||' 季' FROM family_lease WHERE id=?",id)+"　"+state));
            if(state.equals("待签") && s.number("SELECT lessee FROM family_lease WHERE id=?",id)==family)buttons.add(command("同场签租约 #"+id,"realm family 签租 "+id));
        }
        buttons.add(command("拟居住租约","realm family 填表 出租"));
        buttons.add(command("查看实物委托","realm family 差事"));
    }
    void orders(Player p,int family)throws SQLException{
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        body.add(text("可把实货送到收购柜领取实币；业主须先备款，开单、履约和成交均沿用商站账。"));
        try(var q=s.sql("SELECT id,name FROM stall WHERE owner=? AND x IS NOT NULL",book.account(family));var r=q.executeQuery()){
            while(r.next())buttons.add(command("出资收购 · "+r.getString(2),"realm bidform "+r.getString(1)));
        }
        try(var q=s.sql("SELECT b.id,s.name,b.goods,b.lot FROM bid b JOIN stall s ON s.id=b.stall WHERE b.ask=0 AND b.remaining>0 AND s.x IS NOT NULL AND s.owner<>? ORDER BY b.id DESC LIMIT 12",book.account(family));var r=q.executeQuery()){
            while(r.next())buttons.add(command(r.getString(2)+" · "+r.getString(3)+" ×"+r.getInt(4),"realm quote "+r.getInt(1)));
        }
        buttons.add(command("全服商站与行情","realm prices"));buttons.add(command("返回家档","realm family"));show(p,"家业 · 实物委托",body,List.of(),buttons,2);
    }
}
