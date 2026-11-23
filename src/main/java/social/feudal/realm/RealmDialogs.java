package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import java.sql.SQLException;
import java.util.*;
import org.bukkit.entity.Player;
import static social.feudal.ui.Menus.*;

final class RealmDialogs {
    private static final int PAGE_SIZE = 8;
    private final RealmService realm;
    RealmDialogs(RealmService realm) { this.realm = realm; }

    private static int pageCount(int size) { return Math.max(1, Math.ceilDiv(size, PAGE_SIZE)); }

    private static void navigation(List<ActionButton> buttons, String prefix, int page, int count) {
        if (page > 1) buttons.add(command("上一页", prefix + (page - 1)));
        if (page < count) buttons.add(command("下一页", prefix + (page + 1)));
    }

    void directory(Player p, int page) throws SQLException {
        var titles = realm.store.titles();
        int count = pageCount(titles.size());
        RealmStore.require(page >= 1 && page <= count, "没有这一页领地");
        var buttons = new ArrayList<ActionButton>();
        for (var t : titles.subList((page-1)*PAGE_SIZE, Math.min(titles.size(), page*PAGE_SIZE)))
            buttons.add(command(t.name(), "realm brief " + t.id()));
        navigation(buttons, "realm atlas ", page, count);
        buttons.add(command("各国人口簿","realm census"));
        buttons.add(command("家族档案","realm family"));buttons.add(command("建筑图谱","realm buildings"));buttons.add(command("安家手册","realm start"));
        buttons.add(command("各地行情","realm prices"));
        buttons.add(command("行旅与货运","realm travel"));
        buttons.add(command("国家疆域", "realm map country"));
        buttons.add(command("帝国成员图","realm map empire"));
        buttons.add(command("外交与国际组织","realm diplomacy"));
        buttons.add(command("庄园与建领公议","realm estates"));
        buttons.add(command("查阅税仓", "realm accounts"));
        show(p, "封建社会", List.of(text("纹章院　·　领地与封契"),
                text("登记领地与地产　" + titles.size() + " 处　｜　第 " + page + " / " + count + " 页")),
                List.of(), buttons, 2);
    }

    void brief(Player p, RealmStore.Title t) throws SQLException {
        var s = realm.store;
        String beneficiary = s.value("SELECT tax_right.title FROM depot JOIN tax_right ON depot.id=tax_right.depot WHERE x=? AND z=?", t.x(), t.z());
        String parent = t.parent()==null ? "无" : t.parent().equals("crown")?"帝位（依封契）":s.title(t.parent()).name();
        var body = new ArrayList<>(List.of(text(t.rank() + "　·　" + t.charter()), text((s.politics.nation(t.id())==null?"领产登记　":"所属国家　") + s.polity(t.id()) + "\n直属上级　" + parent),
                text("帝国关系　"+(s.politics.imperial(t.id())?"帝国境内":"帝国外")+"　｜　档案代号　"+t.id()),
                text("法定任命　" + realm.display(t.lawful()) + "\n实际持印　" + realm.display(t.seal())),
                text("驻地区块　" + t.x() + "，" + t.z() + "　｜　直辖 " + s.number("SELECT count(*) FROM land WHERE title=?",t.id()) + " 区块"),
                text("驻地税权　" + (beneficiary==null?t.name():s.title(beneficiary).name()) + "\n公库存粮　" + (p.getUniqueId().toString().equals(t.seal())?realm.taxes.balance(t):"仅持印人可查"))));
        var buttons=new ArrayList<ActionButton>();
        buttons.add(command("领地人口与领印","realm territory "+t.id()));
        for(var region:realm.manors.register.census())if(region.id().equals(t.id())){
            buttons.add(command("辖内聚落","realm people "+t.id()));
            body.add(text("居民 "+region.population()+" 人　｜　直辖 "+region.direct()+" 人　｜　封臣辖下 "+(region.population()-region.direct())+" 人"));
        }
        buttons.add(command("各国人口簿","realm census"));
        if(t.rank().equals("地主") && s.politics.member(Politics.EMPIRE,t.id())){
            String state=s.value("SELECT state || ' · ' || reason FROM peerage WHERE title=?",t.id());
            body.add(text("爵位请书　"+(state==null?"尚未递交":state)));
            var actor=p.getUniqueId().toString();
            if(actor.equals(t.seal()))buttons.add(command("向皇廷请爵","realm peer "+t.id()+" 申请 请核准公议与疆界"));
            if(actor.equals(s.title("crown").seal()) && state!=null && state.startsWith("待审")){
                buttons.add(command("核准伯爵头衔","realm peer "+t.id()+" 批准 皇廷查验公议"));
                buttons.add(command("驳回请爵","realm peer "+t.id()+" 驳回 须补充担保"));
            }
        }
        buttons.add(command("返回纹章院","realm atlas"));buttons.add(command("外交与国际组织","realm diplomacy"));buttons.add(command("查阅税仓","realm accounts"));
        show(p,t.name(),body,List.of(),buttons,2);
    }

    void crew(Player p,RealmStore.Depot d)throws SQLException{
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        if(realm.world.isChunkLoaded(d.x(),d.z()))body.add(text(realm.crews.status(realm.world.getChunkAt(d.x(),d.z()),TaxCouriers.crewId(d))));
        body.add(text("脚夫送粮后步行回仓。请在仓边交付装备。"));
        body.add(text("步鞋　皮革靴 ×1，加快步速\n背架　皮革 ×4、木棍 ×4，每趟增至 16 份\n驮具　箱子、拴绳、鞍各 ×1，每趟增至 64 份"));
        body.add(text("先配背架，再瞄准亲自带来的成年驯骡登记。"));
        for(var u:social.feudal.transport.TransportCrew.Upgrade.values())
            buttons.add(command("配备"+u.name,"realm equip "+d.id()+" "+u.name().toLowerCase(Locale.ROOT)));
        buttons.add(command("返回账簿","realm accounts "+d.id()));
        show(p,"仓务人手 · "+d.name(),body,List.of(),buttons,2);
    }
    void fixtures(Player p,RealmStore.Depot d)throws SQLException{
        var buttons=new ArrayList<ActionButton>();
        buttons.add(command("登记瞄准的讲台","realm desk "+d.id()));
        buttons.add(command("登记产地箱或桶","realm stock "+d.id()));
        for(var t:realm.store.titles())if(t.x()==d.x() && t.z()==d.z() && (t.seal().equals(p.getUniqueId().toString()) || p.hasPermission("feudal.realm.admin")))
            buttons.add(command("登记公库 · "+t.name(),"realm vault "+t.id()));
        buttons.add(command("返回账簿","realm accounts "+d.id()));
        show(p,"税仓改建 · "+d.name(),List.of(text("在本区块放好新的讲台、箱子或木桶。瞄准方块后选择用途。"),
                text("登记后旧址立即停用，原箱中的物资需自行搬运。请为脚夫和驮队留出装卸场和通路。")),List.of(),buttons,2);
    }
    void warehouses(Player p, int period, int page) throws SQLException {
        var depots = realm.store.depots();
        int count = pageCount(depots.size());
        RealmStore.require(page >= 1 && page <= count, "没有这一页税仓");
        var buttons = new ArrayList<ActionButton>();
        for (var d : depots.subList((page-1)*PAGE_SIZE, Math.min(depots.size(), page*PAGE_SIZE)))
            buttons.add(command(d.name(), "realm accounts " + d.id() + " " + period));
        navigation(buttons, "realm warehouses " + period + " ", page, count);
        buttons.add(command("返回纹章院", "realm atlas"));
        show(p, "税仓总册", List.of(text("第 " + period + " 税期　｜　登记税仓 " + depots.size() + " 处")),
                List.of(), buttons, 2);
    }

    void reliefDepots(Player p,int relief)throws SQLException {
        var f=realm.taxes.ledger;
        String land=f.remissions.district(relief,true);
        RealmStore.require(land!=null && realm.store.title(land).seal().equals(p.getUniqueId().toString()),"请由未结赈单所在领地持印人拟令");
        var buttons=new ArrayList<ActionButton>();
        for(var depot:realm.store.depots()){
            if(land.equals(realm.store.owner(depot.x(),depot.z())))
                buttons.add(command(depot.name(),"realm remitform "+relief+" "+depot.id()+" "+(f.period()+1)));
        }
        show(p,"赈单 #"+relief+" · 地方减征",List.of(text(buttons.isEmpty()?"本领尚无登记税仓。":"选择本领税仓，减征只对下一税期有效。")),List.of(),buttons,2);
    }

    void remission(Player p,TaxRemissions.Writ w)throws SQLException {
        var fiscal=realm.taxes.ledger;
        var body=new ArrayList<DialogBody>();
        var buttons=new ArrayList<ActionButton>();
        var local=realm.store.title(w.land());var right=realm.store.title(w.right());
        body.add(text(fiscal.depot(w.depot()).name()+"　｜　第 "+w.period()+" 税期　｜　"+w.state()));
        body.add(text("关联赈单 #"+w.relief()+"　减征 "+w.percent()+"%"));
        body.add(text("地方领印　"+local.name()+"　拟令人　"+realm.display(w.issuer())));
        body.add(text("税收收益权　"+right.name()+"　会签人　"+(w.signer()==null?"尚无":realm.display(w.signer()))));
        if(w.state().equals("已减征"))body.add(text("本期留在产地　"+w.spared()+" 份小麦。运去磨坊后仍须磨粉制饼，居民才能食用。"));
        String who=p.getUniqueId().toString();
        if(w.period()==fiscal.period()+1 && w.state().equals("待会签") && who.equals(right.seal()))
            buttons.add(command("以税权持印人身份会签","realm cosign "+w.id()));
        if(w.period()==fiscal.period()+1 && (w.state().equals("待会签") || w.state().equals("已核准"))
                && (who.equals(local.seal()) || who.equals(right.seal())))
            buttons.add(command("撤回此令","realm revokewrit "+w.id()));
        buttons.add(command("查看该期税仓账","realm accounts "+w.depot()+" "+w.period()));
        show(p,"减征令 #"+w.id(),body,List.of(),buttons,2);
    }

    void accounts(Player p, int period, String id) throws SQLException {
        var f = realm.taxes.ledger;
        var d = f.depot(id);
        var body = new ArrayList<DialogBody>();
        var buttons = new ArrayList<ActionButton>();
        body.add(text("第 " + period + " 税期　·　实物税"));
        if (realm.store.value("SELECT title FROM assessment WHERE depot=? AND period=?",id,period)==null) {
            body.add(text("本期尚无应收记录"));
        } else if(!realm.taxes.canRead(p,f.account(id,period).title())){
            body.add(text("实物税账向本期权利人开放"));
        } else {
            var a = f.account(id,period);
            int received=realm.store.number("SELECT sum(amount) FROM crate WHERE depot=? AND period=? AND received=1",id,period);
            var reduction=f.remissions.latest(id,period);
            if(reduction!=null && reduction.state().equals("已减征"))
                body.add(text("盘得应税 "+(a.due()+reduction.spared())+"　｜　减征留乡 "+reduction.spared()+"　｜　净应收 "+a.due()));
            body.add(text("应收小麦　"+a.due()+" 份　｜　税箱已交割　"+received+" 份"));
            body.add(text("本期可委托驮运　"+f.quota(id,period)+" 份　｜　尚待装箱　"+a.remaining()+" 份"));
            body.add(text("期初税权　"+realm.store.title(a.title()).name()+"\n持印人可在发运前提走本地全部税箱。"));
            buttons.add(command("从本仓提运","realm collect "+id+" "+period));
        }
        for(var which:period==f.period()?List.of(period,period+1):List.of(period)){
            var w=f.remissions.latest(id,which);
            if(w!=null)buttons.add(command("第 "+which+" 期减征令 · "+w.state(),"realm writ "+w.id()));
        }
        var stock=realm.fixtures.lookup(d.x(),d.z(),"stock",id);
        if(stock!=null)body.add(text("产地仓箱　"+stock.getX()+"，"+stock.getY()+"，"+stock.getZ()));
        var owner=realm.store.title(realm.store.owner(d.x(),d.z()));
        if(owner.seal().equals(p.getUniqueId().toString()) || p.hasPermission("feudal.realm.admin"))
            buttons.add(command("改建登记","realm fixtures "+id));
        if (realm.taxes.holdsCrate(p)) buttons.add(command("交付手中税箱", "realm deposit"));
        buttons.add(command("脚夫与装备","realm crew "+id));
        buttons.add(command("返回税仓总册", "realm warehouses " + period + " 1"));
        show(p, d.name(), body, List.of(), buttons, 2);
    }
}
