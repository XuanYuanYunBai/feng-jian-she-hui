package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmService.usage;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

final class DiplomacyDesk {
    private final RealmService realm;
    private final RealmStore s;
    private final Politics law;
    DiplomacyDesk(RealmService realm){this.realm=realm;s=realm.store;law=s.politics;}
    List<String> column(String sql,Object...args)throws SQLException{
        List<String> result=new ArrayList<>();
        try(var q=s.sql(sql,args);var rows=q.executeQuery()){while(rows.next())result.add(rows.getString(1));}
        return result;
    }
    private List<String> countries(Player p)throws SQLException{
        return column("SELECT nation.id FROM nation JOIN title ON title.id=nation.id WHERE seal=? AND lawful=seal ORDER BY name",p.getUniqueId().toString());
    }
    void directory(Player p)throws SQLException{
        var buttons=new ArrayList<ActionButton>();
        for(String id:column("SELECT id FROM association ORDER BY rowid"))
            buttons.add(command(s.value("SELECT name FROM association WHERE id=?",id),"realm org "+id));
        for(String id:column("SELECT id FROM treaty WHERE kind IN ('条约','割地条约') OR state='待批准' ORDER BY rowid DESC"))
            buttons.add(command("文书 · "+s.value("SELECT name FROM treaty WHERE id=?",id),"realm document "+id));
        for(String id:countries(p)){
            buttons.add(command(s.title(id).name()+" · 起草","realm compose "+id));
            buttons.add(command(s.title(id).name()+" · 议让土地","realm cedeform "+id));
        }
        buttons.add(command("查看国家疆界","realm map country"));buttons.add(command("查看帝国疆域","realm map empire"));
        buttons.add(command("返回纹章院","realm atlas"));
        show(p,"外交院 · 条约与国际组织",List.of(text("选取组织查阅章程、成员和议案。代表本国起草条约，经各方批准可以设立常设组织。")),List.of(),buttons,2);
    }
    void profile(Player p,String org)throws SQLException{
        String name=s.value("SELECT name FROM association WHERE id=?",org);require(name!=null,"未知组织");
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        body.add(text(s.value("SELECT kind || '　｜　' || state FROM association WHERE id=?",org)+"　·　章程"));
        body.add(text(s.value("SELECT charter FROM association WHERE id=?",org)));
        if(org.equals(Politics.EMPIRE))body.add(text("帝位　"+realm.display(s.title("crown").lawful())+"　｜　"+(law.choiceOpen()?"新建国可选择入帝或独立":"新建国自动入帝")));
        var names=new ArrayList<String>();
        for(String id:column("SELECT nation FROM member WHERE org=? ORDER BY nation",org)){
            var t=s.title(id);
            String role=s.number("SELECT elector FROM member WHERE org=? AND nation=?",org,id)==1?" · 选侯":s.number("SELECT seat FROM member WHERE org=? AND nation=?",org,id)==1?" · 议席":" · 成员";
            names.add(t.name()+role);buttons.add(command(t.name(),"realm brief "+id));
        }
        body.add(text("成员 "+names.size()+" 国　｜　"+String.join("、",names)));
        for(String doc:column("SELECT treaty FROM organisation_signature WHERE org=? AND signer IS NULL",org))
            buttons.add(command("待授组织签字 · "+doc,"realm document "+doc));
        for(String id:column("SELECT id FROM motion WHERE org=? ORDER BY id DESC LIMIT 12",org))
            buttons.add(command("议案 "+id+" · "+s.value("SELECT kind || ' · ' || state FROM motion WHERE id=?",id),"realm debate "+id));
        for(String country:countries(p)){
            if(law.member(org,country)){
                if(org.equals(Politics.EMPIRE) && !Succession.isPlayer(s.title("crown").lawful()))
                    buttons.add(command(s.title(country).name()+" · 参选帝位","realm stand "+country));
                if(org.equals(Politics.EMPIRE) && s.number("SELECT seat FROM member WHERE org=? AND nation=?",org,country)==0)
                    buttons.add(command(s.title(country).name()+" · 请授议席","realm propose empire "+country+" 授席 "+country+" 请求议会授予独立席位"));
                if(org.equals(Politics.EMPIRE))buttons.add(command(s.title(country).name()+" · 议退帝国","realm propose empire "+country+" 退帝 "+country+" 依议会裁定解除成员资格"));
                if(!org.equals(Politics.EMPIRE))buttons.add(command(s.title(country).name()+" · 退出","realm withdraw "+org+" "+country));
                buttons.add(command(s.title(country).name()+" · 提案","realm agenda "+org+" "+country));
            }else buttons.add(command(s.title(country).name()+" · 申请加入","realm propose "+org+" "+country+" 接纳 "+country+" 申请依章加入"));
        }
        for(var line:column("SELECT '#' || id || '　' || nation || '　' || received || '/' || quantity || ' ' || CASE goods WHEN 'WHEAT' THEN '小麦' WHEN 'OAK_LOG' THEN '橡木' ELSE '羊毛' END || '　到期季 ' || due FROM contribution WHERE org=? ORDER BY id DESC LIMIT 8",org))
            body.add(text(line));
        for(var country:countries(p))if(law.member(org,country))buttons.add(command(s.title(country).name()+" · 认缴","realm pledgeform "+org+" "+country));
        buttons.add(command("外交院","realm diplomacy"));
        show(p,name,body,List.of(),buttons,2);
    }
    void document(Player p,String id)throws SQLException{
        var name=s.value("SELECT name FROM treaty WHERE id=?",id);require(name!=null,"未知条约");
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();
        body.add(text(s.value("SELECT kind || '　｜　' || state || char(10) || terms FROM treaty WHERE id=?",id)));
        var my=countries(p);
        var deed=law.cessions.get(id);
        boolean pending="待批准".equals(s.value("SELECT state FROM treaty WHERE id=?",id));
        if(deed!=null){
            var b=deed.bounds();
            body.add(text("让方　"+s.title(deed.donor()).name()+"　→　受方　"+s.title(deed.recipient()).name()));
            body.add(text("地籍附件　西北 ("+b.west()+", "+b.north()+") 至东南 ("+b.east()+", "+b.south()+")　共 "+b.area()+" 个区块"));
            if(pending){
                body.add(text("双方持印人须在十二格内会签。受方落印时立即按附件交地。"));
                if(my.contains(deed.donor()))buttons.add(command("撤回割地文书","realm cancelcede "+id+" "+deed.donor()));
                if(my.contains(deed.recipient()))buttons.add(command("谢绝受地","realm cancelcede "+id+" "+deed.recipient()));
            }
        }
        for(var country:column("SELECT nation FROM signatory WHERE treaty=?",id)){
            String signer=s.value("SELECT signer FROM signatory WHERE treaty=? AND nation=?",id,country);
            String signature = signer == null ? "未签署" : realm.display(signer)+" 已盖印";
            if(signer==null && pending)signature="待批准";
            body.add(text(s.title(country).name()+"　"+signature));
            if(pending && signer==null && my.contains(country))buttons.add(command("代表"+s.title(country).name()+"签署","realm sign "+id+" "+country));
        }
        for(String org:column("SELECT org FROM organisation_signature WHERE treaty=?",id)){
            String sign=s.value("SELECT signer FROM organisation_signature WHERE treaty=? AND org=?",id,org);
            String delegate=s.value("SELECT delegate FROM organisation_signature WHERE treaty=? AND org=?",id,org);
            body.add(text(s.value("SELECT name FROM association WHERE id=?",org)+"　"+(sign!=null?realm.display(sign)+" 已代表组织签署":delegate==null?"尚待组织授权":"议会已授权")));
            if(sign!=null)continue;
            if(delegate==null){
                for(String country:my)if(law.member(org,country))
                    buttons.add(command(s.title(country).name()+" · 提请授权","realm propose "+org+" "+country+" 外交授权 "+id+" "+country));
            }else if(org.equals(Politics.EMPIRE)?s.title("crown").seal().equals(p.getUniqueId().toString()):my.contains(delegate))
                buttons.add(command("以组织名义签署","realm orgsign "+id+" "+org));
        }
        buttons.add(command("外交院","realm diplomacy"));show(p,name,body,List.of(),buttons,2);
    }
    void debate(Player p,int id)throws SQLException{
        String org=s.value("SELECT org FROM motion WHERE id=?",id);require(org!=null,"议案不存在");
        var body=new ArrayList<DialogBody>();var buttons=new ArrayList<ActionButton>();var my=countries(p);
        body.add(text(s.value("SELECT kind || ' · ' || state || char(10) || terms FROM motion WHERE id=?",id)));
        for(var country:column("SELECT nation FROM ballot WHERE motion=?",id)){
            var vote=s.value("SELECT vote FROM ballot WHERE motion=? AND nation=?",id,country);
            body.add(text(s.title(country).name()+"　"+(vote==null?"尚未投票":"1".equals(vote)?"赞成":"反对")));
            if(vote==null && my.contains(country) && "表决中".equals(s.value("SELECT state FROM motion WHERE id=?",id))){
                buttons.add(command("本席赞成","realm vote "+id+" "+country+" 赞成"));
                buttons.add(command("本席反对","realm vote "+id+" "+country+" 反对"));
            }
        }
        buttons.add(command("返回组织档案","realm org "+org));show(p,"第 "+id+" 号议案",body,List.of(),buttons,2);
    }
    private void present(Player p,String id)throws SQLException{
        if(law.unsigned(id)!=1)return;
        for(var signer:column("SELECT signer FROM signatory WHERE treaty=? AND signer IS NOT NULL UNION SELECT signer FROM organisation_signature WHERE treaty=? AND signer IS NOT NULL",id,id)){
            var other=Bukkit.getPlayer(UUID.fromString(signer));
            require(other!=null && other.getWorld().equals(p.getWorld()) && other.getLocation().distanceSquared(p.getLocation())<=144,"最后签署时请各方签署人到十二格内交换正本");
        }
    }
    record Field(String key,String label,String initial,int max){}
    static void form(Player p,String title,String hint,String prefix,List<Field> fields){
        var inputs=new ArrayList<DialogInput>();
        for(var f:fields)inputs.add(DialogInput.text(f.key(),230,Component.text(f.label()),true,f.initial(),f.max(),null));
        var action=DialogAction.customClick((reply,viewer)->{
            if(!(viewer instanceof Player signer)||!p.equals(signer))return;
            var args=new ArrayList<String>();
            for(var f:fields){
                String value=reply.getText(f.key());
                if(value==null || value.isBlank() || value.contains("\n")){p.sendMessage("请填写"+f.label());return;}
                args.add(value.trim());
            }
            Bukkit.getScheduler().runTask(org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(DiplomacyDesk.class),()->{if(signer.isOnline())signer.performCommand(prefix+" "+String.join(" ",args));});
        },ClickCallback.Options.builder().uses(1).build());
        var submit=ActionButton.create(Component.text("核对并提交"),null,128,action);
        show(p,title,List.of(text(hint)),inputs,List.of(submit),1);
    }
    void run(Player p,String[] a)throws SQLException{
        realm.inWorld(p);
        String actor=p.getUniqueId().toString();
        switch(a[0]){
            case "diplomacy" -> directory(p);
            case "org" -> {usage(a.length==2,"org 组织");profile(p,a[1]);}
            case "document" -> {usage(a.length==2,"document 条约");document(p,a[1]);}
            case "debate" -> {usage(a.length==2,"debate 议案");debate(p,Integer.parseInt(a[1]));}
            case "cedeform" -> {
                usage(a.length==2 || a.length==3,"cedeform 让方 [受方]");law.represents(a[1],actor);
                if(a.length==2){
                    var buttons=new ArrayList<ActionButton>();
                    for(var other:column("SELECT id FROM nation WHERE id<>? ORDER BY id",a[1]))
                        buttons.add(command(s.title(other).name(),"realm cedeform "+a[1]+" "+other));
                    buttons.add(command("返回外交院","realm diplomacy"));
                    show(p,"为"+s.title(a[1]).name()+"选择受让国",List.of(text("选定受方后填写直辖区块的西北角与东南角。")),List.of(),buttons,2);
                }else{
                    var chunk=p.getLocation().getChunk();
                    form(p,"起草割地条约","让方 "+s.title(a[1]).name()+"，受方 "+s.title(a[2]).name()+"。坐标以区块计，含两端。提交即由让方盖印，待会签前可撤回。",
                            "realm cede "+a[1]+" "+a[2],List.of(new Field("id","文书代号","",24),
                                    new Field("west","西界",""+chunk.getX(),9),new Field("north","北界",""+chunk.getZ(),9),
                                    new Field("east","东界",""+chunk.getX(),9),new Field("south","南界",""+chunk.getZ(),9),
                                    new Field("name","条约名称","",30)));
                }
            }
            case "cede" -> {
                usage(a.length>=9,"cede 让方 受方 文号 西 北 东 南 条约名称");
                var b=new Charters.Bounds(Integer.parseInt(a[4]),Integer.parseInt(a[5]),Integer.parseInt(a[6]),Integer.parseInt(a[7]));
                law.cessions.draft(a[3],a[1],a[2],actor,b,String.join(" ",Arrays.copyOfRange(a,8,a.length)));
                document(p,a[3]);
            }
            case "cancelcede" -> {
                usage(a.length==3,"cancelcede 文号 本国");law.cessions.cancel(a[1],a[2],actor);document(p,a[1]);
            }
            case "compose" -> {usage(a.length==2,"compose 本国");law.represents(a[1],actor);
                form(p,"起草多边文书","本国先签，最后一国批准时创始签署人须在场。国家代号可在各领档案查询。",
                        "realm treaty "+a[1],List.of(new Field("id","文书代号","",24),new Field("name","名称","",30),new Field("kind","条约、河运同盟或防御联盟","条约",8),new Field("members","缔约方（本国、他国或 @组织）",a[1]+",",180),new Field("terms","条款","",200)));
            }
            case "agenda" -> {usage(a.length==3,"agenda 组织 本国");law.represents(a[2],actor);
                form(p,"提出议案","选帝填写候选国。议决或修章填写本国代号。成员按章投票。","realm propose "+a[1]+" "+a[2],
                        List.of(new Field("kind","议决、修章或选帝","议决",8),new Field("subject","所涉国家",a[2],24),new Field("terms","决议全文","",200)));
            }
            case "treaty" -> {usage(a.length>=7,"treaty 本国 文号 名称 条约|河运同盟|防御联盟 成员代号以逗号分隔 条款");
                law.draft(a[2],a[3],a[4],String.join(" ",Arrays.copyOfRange(a,6,a.length)),Arrays.asList(a[5].replace('，',',').split(",",-1)),a[1],actor);
                document(p,a[2]);}
            case "sign" -> {usage(a.length==3,"sign 条约 本国");
                present(p,a[1]);
                law.sign(a[1],a[2],actor);realm.changed();document(p,a[1]);}
            case "orgsign" -> {usage(a.length==3,"orgsign 条约 组织");present(p,a[1]);law.signOrganisation(a[1],a[2],actor);document(p,a[1]);}
            case "propose" -> {usage(a.length>=6,"propose 组织 本国 种类 所涉国家 条款");
                debate(p,law.propose(a[1],a[2],actor,a[3],a[4],String.join(" ",Arrays.copyOfRange(a,5,a.length))));}
            case "vote" -> {usage(a.length==4 && Set.of("赞成","反对").contains(a[3]),"vote 议案 本国 赞成|反对");
                int id=Integer.parseInt(a[1]);law.vote(id,a[2],actor,a[3].equals("赞成"));realm.changed();debate(p,id);}
            case "pledgeform" -> {usage(a.length==3,"pledgeform 组织 本国");
                form(p,"承诺实物出资","选择登记货线。实物签收后，凭该批运单登记履约。","realm pledge "+a[1]+" "+a[2],
                        List.of(new Field("route","货线代号","",40),new Field("goods","小麦、橡木或白羊毛","小麦",6),
                                new Field("quantity","数量","8",4),new Field("due","到期季",""+(realm.manors.register.season()+1),8)));
            }
            case "pledge" -> {usage(a.length==7,"pledge 组织 本国 货线 小麦|橡木|白羊毛 数量 到期季");
                String goods=switch(a[4]){case "小麦"->"WHEAT";case "橡木"->"OAK_LOG";case "白羊毛"->"WHITE_WOOL";default->throw new IllegalArgumentException("未知货种");};
                int n=realm.contributions.pledge(a[1],a[2],actor,a[3],goods,Integer.parseInt(a[5]),Integer.parseInt(a[6]));
                p.sendMessage("认缴 #"+n+" 已登记。签收后 /realm accountfor "+n+" 运单号");profile(p,a[1]);
            }
            case "accountfor" -> {usage(a.length==3,"accountfor 认缴号 已签收运单号");realm.contributions.credit(Integer.parseInt(a[1]),actor,a[2]);p.sendMessage("已核销实物回执");}
            case "stand" -> {usage(a.length==2,"stand 本国");int n=law.requestElection(a[1],actor);realm.changed();debate(p,n);}
            case "withdraw" -> {usage(a.length==3,"withdraw 组织 本国");law.leave(a[1],a[2],actor);profile(p,a[1]);}
            default -> throw new IllegalArgumentException("未知外交事务");
        }
    }
}
