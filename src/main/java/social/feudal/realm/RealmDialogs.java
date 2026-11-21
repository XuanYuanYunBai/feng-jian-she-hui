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
        buttons.add(command("展开疆域图", "realm map"));
        buttons.add(command("查阅税仓", "realm accounts"));
        show(p, "封建社会", List.of(text("纹章院　·　领地与封契"),
                text("登记领地与地产　" + titles.size() + " 处　｜　第 " + page + " / " + count + " 页")),
                List.of(), buttons, 2);
    }

    void brief(Player p, RealmStore.Title t) throws SQLException {
        var s = realm.store;
        String beneficiary = s.value("SELECT tax_right.title FROM depot JOIN tax_right ON depot.id=tax_right.depot WHERE x=? AND z=?", t.x(), t.z());
        String parent = t.parent()==null ? "最高席位" : s.title(t.parent()).name();
        var body = List.of(text(t.rank() + "　·　" + t.charter()), text("所属国家　" + s.polity(t.id()) + "\n直属上级　" + parent),
                text("法定任命　" + realm.display(t.lawful()) + "\n实际持印　" + realm.display(t.seal())),
                text("驻地区块　" + t.x() + "，" + t.z() + "　｜　直辖 " + s.number("SELECT count(*) FROM land WHERE title=?",t.id()) + " 区块"),
                text("驻地税权　" + (beneficiary==null?t.name():s.title(beneficiary).name()) + "\n公库存粮　" + realm.taxes.ledger.balance(t.id()) + " 份"));
        show(p, t.name(), body, List.of(), List.of(command("返回纹章院", "realm atlas"), command("查阅税仓", "realm accounts")), 2);
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

    void accounts(Player p, int period, String id) throws SQLException {
        var f = realm.taxes.ledger;
        var d = f.depot(id);
        var body = new ArrayList<DialogBody>();
        var buttons = new ArrayList<ActionButton>();
        body.add(text("第 " + period + " 税期　·　实物税"));
        if (realm.store.value("SELECT title FROM assessment WHERE depot=? AND period=?",id,period)==null) {
            body.add(text("本期尚无应收记录"));
        } else {
            var a = f.account(id,period);
            body.add(text("应收小麦　" + a.due() + " 份　｜　尚待征收　" + a.remaining() + " 份"));
            body.add(text("自动入库　" + a.automatic() + " 份\n亲征装箱　" + a.packed() + " 份"));
            body.add(text("期初税权　" + realm.store.title(a.title()).name()));
            if (a.remaining()>0) buttons.add(command("在此亲征", "realm collect " + id + " " + period));
        }
        if (realm.taxes.holdsCrate(p)) buttons.add(command("交付手中税箱", "realm deposit"));
        buttons.add(command("返回税仓总册", "realm warehouses " + period + " 1"));
        show(p, d.name(), body, List.of(), buttons, 2);
    }
}
