package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.*;
import org.bukkit.entity.Player;

/** Native books keep the political register readable with a stock client. */
final class RealmBooks {
    private final RealmService realm;
    RealmBooks(RealmService realm){this.realm=realm;}
    private static Component heading(String s){return Component.text(s+"\n",NamedTextColor.DARK_RED,TextDecoration.BOLD);}
    private static Component link(String text,String cmd){
        return Component.text(text,NamedTextColor.DARK_GREEN,TextDecoration.UNDERLINED).clickEvent(ClickEvent.runCommand(cmd));
    }
    private static void open(Player p,String title,List<Component> pages){
        p.openBook(Book.book(Component.text(title),Component.text("帝国纹章院"),pages));
    }
    void directory(Player p)throws SQLException{
        var titles=realm.store.titles();var pages=new ArrayList<Component>();
        for(int start=0;start<titles.size();start+=4){
            var page=heading("帝国纹章院").append(Component.text("领地与封契\n\n"));
            for(int i=start;i<Math.min(start+4,titles.size());i++){
                var t=titles.get(i);
                page=page.append(link((i+1)+" "+t.name(),"/realm brief "+t.id())).append(Component.newline());
            }
            page=page.append(Component.newline()).append(link("疆域图","/realm map")).append(Component.text("  ")).append(link("税仓账簿","/realm accounts"));
            pages.add(page);
        }
        open(p,"帝国政治图册",pages);
    }
    void brief(Player p,RealmStore.Title t)throws SQLException{
        var s=realm.store;
        var status=heading(t.name()).append(Component.text(t.rank()+" · "+t.charter()+"\n\n"))
                .append(Component.text("直属上级\n"+(t.parent()==null?"最高席位":s.title(t.parent()).name())+"\n"))
                .append(Component.text("法定任命\n"+realm.display(t.lawful())+"\n实际持印\n"+realm.display(t.seal())+"\n"))
                .append(link("返回图册","/realm atlas"));
        String beneficiary=s.value("SELECT tax_right.title FROM depot JOIN tax_right ON depot.id=tax_right.depot WHERE x=? AND z=?",t.x(),t.z());
        var finance=heading("领界与财权").append(Component.text("驻地区块 "+t.x()+", "+t.z()+"\n直辖 "+s.number("SELECT count(*) FROM land WHERE title=?",t.id())+" 区块\n"))
                .append(Component.text("国家 "+s.polity(t.id())+"\n驻地税权 "+(beneficiary==null?t.name():s.title(beneficiary).name())+"\n"))
                .append(Component.text("公库存粮 "+realm.taxes.ledger.balance(t.id())+"\n"))
                .append(link("税仓账簿","/realm accounts"));
        open(p,t.name(),List.of(status,finance));
    }
    void accounts(Player p,int period,String focus)throws SQLException{
        var pages=new ArrayList<Component>();var f=realm.taxes.ledger;
        if(!focus.isEmpty() && realm.store.value("SELECT title FROM assessment WHERE depot=? AND period=?",focus,period)==null){
            var d=f.depot(focus);
            open(p,d.name(),List.of(heading(d.name()).append(Component.text("第 "+period+" 期没有应收税款。"))));
            return;
        }
        var depots=new ArrayList<>(realm.store.depots());depots.sort(Comparator.comparing(d->!d.id().equals(focus)));
        for(var d:depots){
            if(realm.store.value("SELECT title FROM assessment WHERE depot=? AND period=?",d.id(),period)==null)continue;
            var a=f.account(d.id(),period);var t=realm.store.title(a.title());
            var page=heading(d.name()).append(Component.text("第 "+period+" 税期  ·  小麦\n\n"))
                    .append(Component.text("应收    "+a.due()+"\n自动入库 "+a.automatic()+"\n亲征装箱 "+a.packed()+"\n尚待征收 "+a.remaining()+"\n"))
                    .append(Component.text("期初税权 "+t.name()+"\n"))
                    .append(link("在此亲征","/realm collect "+d.id()+" "+period));
            pages.add(page);
        }
        RealmStore.require(!pages.isEmpty(),"该税期暂无记录");open(p,"税仓账簿",pages);
    }
}
