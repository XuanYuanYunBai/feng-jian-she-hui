package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.entity.Player;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

/** A player's current business, read from the same registers that execute it. */
final class StewardDesk {
    private final RealmService realm;
    private final RealmStore s;
    StewardDesk(RealmService realm){this.realm=realm;s=realm.store;}

    void open(Player p,String[] args)throws SQLException{
        realm.inWorld(p);
        String actor=p.getUniqueId().toString();
        var book=realm.families.book;
        int family=book.family(actor),rep=book.rep(family);
        if(family==0 || rep==0){realm.families.run(p,new String[]{"family"});return;}
        var homes=realm.diplomacy.column("SELECT id FROM manor WHERE owner=? ORDER BY cottage DESC,id",actor);
        var titles=realm.diplomacy.column("SELECT id FROM title WHERE seal=? ORDER BY id",actor);
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        var person=book.person(rep);
        body.add(text(s.value("SELECT name FROM family WHERE id=?",family)+"　"+person.name()+"　｜　第 "+book.season()+" 季"));
        if(homes.isEmpty()){
            body.add(text("尚无私庄。可在安家手册选址立庄，也可寄居、经商或接受已有货款的委托。"));
            buttons.add(command("选址与安家","realm start"));
            buttons.add(command("寄居与营地","realm family 家产"));
        }else{
            String home=args.length==2?args[1]:homes.getFirst();
            require(homes.contains(home),"经营簿只显示自己管理的私庄");
            manor(home,rep,body,buttons);
            for(String id:homes)if(!id.equals(home))buttons.add(command("换庄 · "+realm.manors.register.manor(id).name(),"realm journal "+id));
        }
        var heirs=book.successors(rep);
        body.add(text(heirs.isEmpty()?"家档尚无可接续的男性。成年代表可在居住庄议婚，婚后由父家开启养育。":"家档可接续 "+heirs.size()+" 人　首位 "+book.person(heirs.getFirst()).name()));
        buttons.add(command("婚书与养育","realm family 婚约"));buttons.add(command("生前核对继承","realm family 继承"));
        buttons.add(command("本家效忠誓书","realm fealty"));
        for(String t:titles)buttons.add(command("治领 · "+s.title(t).name(),"realm stewardship "+t));
        buttons.add(command("脚下路况与运力","realm travel"));buttons.add(command("公共养路簿","realm works"));buttons.add(command("商站与交易","realm commerce"));buttons.add(command("家族档案","realm family"));
        buttons.add(command("建筑图谱","realm buildings"));buttons.add(command("政治图册","realm atlas"));
        show(p,"封建社会 · 经营簿",body,List.of(),buttons,2);
    }

    private void manor(String id,int rep,List<DialogBody> body,List<ActionButton> buttons)throws SQLException{
        var m=realm.manors.register.manor(id);
        int housing=s.number("SELECT housing FROM manor WHERE id=?",id);
        body.add(text(m.name()+"　"+m.residents()+" 人 / "+housing+" 住位　｜　完成供养 "+m.service()+" 季\n庄厅 "+m.x()+"，"+m.y()+"，"+m.z()));
        var pantry=realm.world.isChunkLoaded(m.x()>>4,m.z()>>4)?realm.manors.foodBarrel(m):null;
        if(pantry!=null){
            int stock=0;
            for(var item:WarehouseFixtures.inventory(pantry).getContents())if(ReliefOffice.plain(item))stock+=item.getAmount();
            int credit=s.number("SELECT ration_credit FROM manor WHERE id=?",id);
            int required=Math.ceilDiv(Math.max(0,m.residents()-credit),100);
            body.add(text("厅仓面包 "+stock+"　｜　按当前人口，下季须取 "+required+" 个，持续公议供养另留 3 个。"));
        }else body.add(text("厅仓待现场查验，走近庄厅可看实存口粮。"));
        buttons.add(command("庄档与住房","realm manor "+id));
        String mill=s.value("SELECT id FROM mill WHERE manor=? ORDER BY id LIMIT 1",id);
        if(mill==null)buttons.add(command("登记本庄水磨","realm millform "+id));
        else buttons.add(command("水磨与口粮工班","realm mill "+mill));
        var book=realm.families.book;
        if(book.person(rep).home()==null)buttons.add(command("在此庄落籍","realm family 落籍 "+rep+" "+id));
        if(id.equals(book.person(rep).home()) && book.person(rep).age(book.season())>=18 &&
           s.number("SELECT count(*) FROM family_match WHERE (a=? OR b=?) AND state IN ('议婚','已婚')",rep,rep)==0){
            buttons.add(command("在本庄议婚","realm localmatch "+id));
        }
        String local=s.owner(m.x()>>4,m.z()>>4);
        if(local!=null){body.add(text("庄址所在领地　"+s.title(local).name()));return;}
        int pending=s.number("SELECT id FROM claim WHERE manor=? AND state='公示中' ORDER BY id LIMIT 1",id);
        if(pending!=0){buttons.add(command("公示进度与裁定 #"+pending,"realm hearing "+pending));return;}
        boolean hall=s.number("SELECT count(*) FROM manor_room WHERE manor=? AND kind='议事厅'",id)>0;
        body.add(text("建领公议　人口 "+m.residents()+"/200　｜　议事厅"+(hall?"已登记":"待登记")+"　｜　供养 "+m.service()+"/1 季"));
        buttons.add(command(hall?"查验并申请建领":"议事厅建设要求",hall?"realm surveyclaim "+id:"realm manor "+id));
    }

    private static DiplomacyDesk.Field field(String key,String label,String initial){return new DiplomacyDesk.Field(key,label,initial,50);}
    void commerce(Player p)throws SQLException{
        String actor=p.getUniqueId().toString();
        var buttons=new ArrayList<ActionButton>();
        for(String id:realm.diplomacy.column("SELECT id FROM stall WHERE owner=? AND x IS NOT NULL ORDER BY id",actor))
            buttons.add(command(s.value("SELECT name FROM stall WHERE id=?",id),"realm market "+id));
        buttons.add(command("查全服现有报价","realm prices"));buttons.add(command("登记眼前商柜","realm stallform"));buttons.add(command("经营簿","realm journal"));
        show(p,"商站与交易",List.of(text("在可经营的地块放一只空木桶，桶上立橡木告示牌，瞄准桶身登记。业主把实货或实款放在第二格后报价。来客到柜边把货款放进第一格，成交后从第三格取走。")),List.of(),buttons,2);
    }
    void forms(Player p,String[] a)throws SQLException{
        String actor=p.getUniqueId().toString();
        switch(a[0]){
            case "stallform" -> DiplomacyDesk.form(p,"登记商站","请瞄准挂有橡木告示牌的空柜。代号以小写英文字母开头。","realm stall",List.of(field("id","商站代号",""),field("name","商站名称","")));
            case "millform" -> {require(a.length==2 && realm.manors.register.manor(a[1]).owner().equals(actor),"请选择本庄");DiplomacyDesk.form(p,"登记水磨","在庄内瞄准磨石。东西各设木桶，南侧两格内设水和轮叶，北侧留通道。","realm watermill "+a[1],List.of(field("id","水磨代号","")));}
            case "localmatch" -> {
                require(a.length==2,"请选择居住庄");int f=realm.families.book.family(actor),rep=realm.families.book.rep(f);
                require(a[1].equals(realm.families.book.person(rep).home()),"请到代表的居住庄议婚");
                show(p,"与本庄居民议婚",List.of(text("使用本庄一个尚未具名的居民名额。子女归父家，本约嫁资和遗属供养均为零。签后可在婚约页选择养育。")),List.of(),
                    List.of(command("同意并签婚","realm family 议婚 "+rep+" 0 "+a[1]+" 0 0"),command("返回经营簿","realm journal "+a[1])),1);
            }
            case "depotform" -> DiplomacyDesk.form(p,"设立本地税仓","站在自己持印的区块办理。下个税期开始计征，产地须有实粮。","realm depot",List.of(field("id","税仓代号",""),field("name","税仓名称","")));
            default -> throw new IllegalArgumentException("未知办事表单");
        }
    }

    void territory(Player p,String[] a)throws SQLException{
        require(a.length>=2,"请选择头衔");
        var t=s.title(a[1]);String actor=p.getUniqueId().toString();
        require(actor.equals(t.seal()),"须由实际持印人办理");
        var buttons=new ArrayList<ActionButton>();var body=new ArrayList<DialogBody>();
        body.add(text(t.name()+"　"+t.rank()+"　｜　"+t.charter()+"\n治所区块 "+t.x()+"，"+t.z()));
        buttons.add(command("领地与居民","realm territory "+t.id()));buttons.add(command("领印与请爵","realm brief "+t.id()));
        buttons.add(command("税仓账册","realm accounts"));buttons.add(command("公共养路簿","realm works"));
        boolean grantor=actor.equals(t.lawful()) && !t.charter().equals("流官");
        if(grantor && (Charters.sovereign(t)||t.rank().equals("公爵"))){
            for(String kind:Charters.sovereign(t)?List.of("公爵","流官"):List.of("伯爵"))
                buttons.add(command("拟授"+kind,"realm grantform "+t.id()+" "+kind));
        }
        if(grantor && List.of("伯爵","公爵","国王","皇帝").contains(t.rank()) && !t.charter().equals("收益封契")){
            buttons.add(command("登记眼前收费亭","realm bridgeform"));
            buttons.add(command("登记脚下区块税仓","realm depotform"));
            for(var d:s.depots())if(t.id().equals(s.owner(d.x(),d.z())) && s.value("SELECT title FROM tax_right WHERE depot=?",d.id())==null)
                buttons.add(command("拟授收益 · "+d.name(),"realm incomeform "+t.id()+" "+d.id()));
        }
        for(var child:s.titles())if(t.id().equals(child.parent()))body.add(text(child.name()+"　"+child.charter()+"　持印 "+realm.display(child.seal())));
        buttons.add(command("外交与国际组织","realm diplomacy"));buttons.add(command("经营簿","realm journal"));
        show(p,"领地政务 · "+t.name(),body,List.of(),buttons,2);
    }
    void charterForm(Player p,String[] a)throws SQLException{
        require(a.length==3,"请选择授予对象或税仓");realm.charters.law.acts(a[1],p.getUniqueId().toString());
        if(a[0].equals("grantform")){
            DiplomacyDesk.form(p,"拟定"+a[2]+"封契","先查验地界并预览。保留自己的驻地，选区仅可包含直属区块。","realm preview "+a[1]+" "+a[2],
                List.of(field("id","新领代号",""),field("name","领名",""),field("holder","受领者：玩家名或族人号（如族人3）",""),field("area","西 北 东 南（区块坐标）","")));
        }else{
            DiplomacyDesk.form(p,"拟授税仓收益","受益人取得这座税仓的收入，地界和居民仍属原领。","realm incomepreview "+a[1]+" "+a[2],
                List.of(field("kind","庄园、修院、磨坊或地租","地租"),field("id","封契代号",""),field("name","封契名称",""),field("holder","受益者：玩家名或族人号","")));
        }
    }
}
