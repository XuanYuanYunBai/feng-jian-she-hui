package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.entity.Player;
import social.feudal.transport.HaulNetwork.Point;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.realm.RealmService.usage;
import static social.feudal.ui.Menus.*;

final class RoadWorks {
    private final RealmService r;
    private final RealmStore s;
    private final RoadWorkBook book;
    RoadWorks(RealmService r)throws SQLException{this.r=r;s=r.store;book=new RoadWorkBook(s);}

    private boolean holds(String actor,Point pt)throws SQLException{
        String title=s.owner(pt.x()>>4,pt.z()>>4);
        return title!=null && actor.equals(s.title(title).seal());
    }
    private boolean uses(RealmService.PublicRoute route,Point p){
        return route!=null && !route.sea() && route.path().size()>2 &&
                route.path().subList(1,route.path().size()-1).contains(p);
    }
    void run(Player p,String[] a)throws SQLException{
        r.inWorld(p);String actor=p.getUniqueId().toString();
        if(a[0].equals("works")){
            usage(a.length<=2,"works [页码]");int page=a.length==2?Integer.parseInt(a[1]):1;
            int pages=Math.max(1,Math.ceilDiv(s.number("SELECT count(*) FROM road_work"),6));
            require(page>=1 && page<=pages,"没有这一页");var buttons=new ArrayList<ActionButton>();
            for(String num:r.diplomacy.column("SELECT id FROM road_work ORDER BY id DESC LIMIT 6 OFFSET ?",(page-1)*6)){
                var o=book.get(Integer.parseInt(num));buttons.add(command("#"+num+" "+o.route()+" · "+o.state(),"realm workdetail "+num));
            }
            buttons.add(command("勘查眼前缺口","realm worksurvey"));
            if(page>1)buttons.add(command("上一页","realm works "+(page-1)));
            if(page<pages)buttons.add(command("下一页","realm works "+(page+1)));
            buttons.add(command("经营簿","realm journal"));
            show(p,"公共养路簿 · "+page+"/"+pages,List.of(text("持印人到登记陆路的缺口，瞄准缺失路面下方或旁边的支撑面勘查。领内商户可自愿出资购料，运到现场修好后由当地持印人验收。")),List.of(),buttons,2);return;
        }
        if(a[0].equals("worksurvey")){
            var pt=r.smiths.gap(p);require(holds(actor,pt),"请由缺口所在区块的持印人勘查");
            String route=r.publicRoutes.entrySet().stream().filter(e->uses(e.getValue(),pt)).map(Map.Entry::getKey).sorted().findFirst()
                    .orElseThrow(()->new IllegalArgumentException("此处须为登记陆线路面，站桶损坏请重建站桶"));
            int id=book.survey(s.owner(pt.x()>>4,pt.z()>>4),route,pt);r.changed();detail(p,book.get(id));return;
        }
        require(a.length>=2,"请选择养路案号");int id=Integer.parseInt(a[1]);var o=book.get(id);
        switch(a[0]){
            case "workdetail" -> {}
            case "workform" -> {
                usage(a.length==3,"workform 案号 商站");
                DiplomacyDesk.form(p,"为 #"+id+" 购置桥路铁件","业主先在商柜第二格放钱。以固定价收购一件，铁匠交货后才入支出账。",
                        "realm workfund "+id+" "+a[2],List.of(new DiplomacyDesk.Field("sum","一件铁件的料价","1",2),
                        new DiplomacyDesk.Field("coin","金或银","银",2)));return;
            }
            case "workfund" -> {
                usage(a.length==5,"workfund 案号 商站 金额 金|银");
                require(o.result()==null && o.bid()==0,"工程已筹款或已结案");
                require(o.title().equals(s.owner(o.point().x()>>4,o.point().z()>>4)),"缺口辖权已变，请由当地持印人重新勘立");
                var bin=r.markets.at(p,a[2]);
                require(actor.equals(s.value("SELECT owner FROM stall WHERE id=?",a[2])) && o.title().equals(s.owner(bin.getX()>>4,bin.getZ()>>4)),"须到工程领内自己的商柜出资");
                require(uses(r.publicRoutes.get(o.route()),o.point()),"原陆线已经变更");
                require(!r.roads.loaded(o.point()) || r.roads.block(o.point()).isEmpty(),"缺口现场已变，请先复勘");
                int price=Integer.parseInt(a[3]);require(price>=1 && price<=64,"单件料价限 1–64 枚");var coin=TollCoin.parse(a[4]);
                s.atomic(()->{int bid=r.markets.post(bin,a[2],"桥路铁件",1,price,1,coin,false,false);
                    book.attach(id,bid,actor);return null;});r.changed();
            }
            case "workendform" -> {
                require(holds(actor,o.point()) && o.result()==null,"请由现任持印人办理未结工程");
                show(p,"终止养路工程 #"+id,List.of(text("未交货的订单须到原出资柜撤下。已付料款保留原账，原有实物仍在原处。")),List.of(),
                        List.of(command("确认终止","realm workend "+id),command("保留工程","realm workdetail "+id)),1);return;
            }
            case "workaccept","workend" -> {
                require(holds(actor,o.point()),"请由现任当地持印人办理");boolean accept=a[0].equals("workaccept");
                if(accept){
                    require(p.getLocation().distanceSquared(o.point().feet(r.world))<=36,"请到缺口六格内验路");
                    require(uses(r.publicRoutes.get(o.route()),o.point()) && r.roads.road(o.point()),"该段登记路面尚未恢复通行");
                }
                if(!accept && o.bid()>0 && o.supplier()==null && o.result()==null){
                    r.markets.at(p,o.stall()); // Reconcile any sale stamped on the physical till before cancelling.
                    if(book.get(id).supplier()==null){
                        s.update("UPDATE bid SET remaining=0 WHERE id=? AND remaining>0",o.bid());
                        r.changed();detail(p,book.get(id));return;
                    }
                }
                book.close(id,actor,accept);r.changed();
            }
            default -> throw new IllegalArgumentException("未知养路事务");
        }
        detail(p,book.get(id));
    }
    void materials(Player fitter,Point pt,org.bukkit.Material plank)throws SQLException{
        int id=s.number("SELECT id FROM road_work WHERE x=? AND y=? AND z=? AND result IS NULL",pt.x(),pt.y(),pt.z());
        if(id==0)return;
        fitter.saveData();r.world.save(true);
        s.update("INSERT INTO road_work_material VALUES(?,?,?,?,1) ON CONFLICT(work,fitter,plank,season) DO UPDATE SET uses=uses+1",id,fitter.getUniqueId().toString(),plank.name(),r.manors.register.season());
    }
    private void detail(Player p,RoadWorkBook.Order o)throws SQLException{
        var b=new ArrayList<ActionButton>();var body=new ArrayList<DialogBody>();var pt=o.point();String actor=p.getUniqueId().toString();
        body.add(text(s.title(o.title()).name()+" · "+o.route()+"　"+o.state()+"\n缺口方块　"+pt.x()+"，"+pt.y()+"，"+pt.z()));
        if(o.bid()>0){
            var coin=TollCoin.parse(o.currency()).label;
            body.add(text("出资人　"+r.display(o.sponsor())+"　｜　商柜 "+o.stall()+"\n预算 "+o.price()+" "+coin+"　｜　实付 "+o.paid()+" "+coin+
                    (o.supplier()==null?"":"　｜　交料人 "+r.display(o.supplier()))));
            b.add(command("核对柜台订单 #"+o.bid(),"realm quote "+o.bid()));
        }else if(o.result()==null){
            body.add(text("采购一件锻造的桥路铁件。领内任一商柜业主可自愿定价出资。"));
            for(String stall:r.diplomacy.column("SELECT t.id FROM stall t JOIN land l ON l.x=(t.x>>4) AND l.z=(t.z>>4) WHERE t.owner=? AND l.title=? ORDER BY t.id",actor,o.title()))
                b.add(command("由 "+stall+" 出资","realm workform "+o.id()+" "+stall));
        }
        if(o.supplier()!=null)body.add(text("交货后的铁件在出资柜第四格起，由业主取出搬运。持印人在缺口主手持铁件、副手持木板修路，再现场验通。"));
        int repairs=s.number("SELECT coalesce(sum(uses),0) FROM road_work_material WHERE work=?",o.id());
        if(repairs>0){
            body.add(text("实耗合计　桥路铁件 ×"+repairs+"，木板 ×"+repairs+"。最近用料记录"));
            try(var q=s.sql("SELECT fitter,plank,season,uses FROM road_work_material WHERE work=? ORDER BY season DESC,rowid DESC LIMIT 4",o.id());var row=q.executeQuery()){
                while(row.next()){
                    var plank=org.bukkit.Material.valueOf(row.getString("plank"));
                    body.add(DialogBody.plainMessage(net.kyori.adventure.text.Component.text("第 "+row.getInt("season")+" 季　"+r.display(row.getString("fitter"))+"　")
                            .append(net.kyori.adventure.text.Component.translatable(plank.translationKey()))
                            .append(net.kyori.adventure.text.Component.text(" ×"+row.getInt("uses")))));
                }
            }
        }else if(o.inspector()!=null)body.add(text("本案没有登记铁件与木板的领用回执。"));
        if(o.inspector()!=null)body.add(text("结案人　"+r.display(o.inspector())));
        if(o.result()==null && holds(actor,pt)){
            if(o.bid()==0 || o.supplier()!=null){b.add(command("修补眼前缺口","realm mend"));b.add(command("现场验通","realm workaccept "+o.id()));}
            b.add(command("终止本案","realm workendform "+o.id()));
        }
        b.add(command("公共养路簿","realm works"));show(p,"养路工程 #"+o.id(),body,List.of(),b,2);
    }
}
