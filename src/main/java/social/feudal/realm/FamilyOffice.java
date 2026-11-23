package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

final class FamilyOffice implements Listener {
    final RealmService realm;
    final FamilyRegister book;
    final FamilyLife life;
    final FamilyLivelihood livelihood;
    final FamilyCash cash;
    final FamilyJourney journey;
    private final RealmStore s;
    private final NamespacedKey current;
    FamilyOffice(RealmService realm)throws SQLException{
        this.realm=realm;s=realm.store;book=new FamilyRegister(s);life=new FamilyLife(book);livelihood=new FamilyLivelihood(realm,book);cash=new FamilyCash(realm,book);journey=new FamilyJourney(this);
        current=new NamespacedKey(realm.plugin,"representative");
        Bukkit.getPluginManager().registerEvents(this,realm.plugin);
        Bukkit.getScheduler().runTaskTimer(realm.plugin,this::famineDeaths,200,200);
    }
    private String account(Player p){return p.getUniqueId().toString();}
    private void attach(Player p,int id){
        if(id==0)p.getPersistentDataContainer().remove(current);
        else p.getPersistentDataContainer().set(current,PersistentDataType.INTEGER,id);
        p.saveData();
    }
    private int enroll(Player p)throws SQLException{
        String actor=account(p);int f=book.family(actor);
        if(f==0 && s.number("SELECT count(*) FROM family WHERE account=?",actor)==0)
            f=book.found(actor,p.getName()+"家",p.getName(),"麦穗");
        return f;
    }
    @EventHandler public void join(PlayerJoinEvent event){
        Player p=event.getPlayer();if(!realm.world.equals(p.getWorld()))return;
        try{
            if(!s.ready())return;
            int f=enroll(p);int rep=f==0?0:book.rep(f);
            if(!p.isDead())attach(p,rep);
            if(f!=0 && rep==0)later(p);
        }catch(SQLException ex){failure(ex);}
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void death(PlayerDeathEvent event){
        var p=event.getEntity();
        if(p.getGameMode()!=GameMode.SURVIVAL || !p.getWorld().equals(realm.world))return;
        try{
            if(!s.ready())return;int f=enroll(p);if(f==0)return;
            Integer lifeId=p.getPersistentDataContainer().get(current,PersistentDataType.INTEGER);
            int id=lifeId==null?book.rep(f):lifeId;if(id==0)return;
            book.die(account(p),id);
            if(event.getKeepInventory()){
                event.getDrops().clear();event.getItemsToKeep().clear();
                for(var item:p.getInventory().getContents())if(item!=null && !item.isEmpty() &&
                        !item.containsEnchantment(org.bukkit.enchantments.Enchantment.VANISHING_CURSE))event.getDrops().add(item.clone());
                event.setKeepInventory(false);
            }
            realm.changed();
            p.sendMessage("本代人物已身故。复生后请在家档查看结算，选择接续人。");
        }catch(SQLException ex){failure(ex);}
    }
    @EventHandler public void respawn(PlayerRespawnEvent e){if(e.getRespawnLocation().getWorld()==realm.world)later(e.getPlayer());}
    private void later(Player p){
        Bukkit.getScheduler().runTaskLater(realm.plugin,()->{
            if(!p.isOnline() || p.isDead() || !p.getWorld().equals(realm.world))return;
            try{int f=book.family(account(p));if(f==0 || book.rep(f)==0)run(p,new String[]{"family"});}
            catch(SQLException ex){failure(ex);}
        },15);
    }
    private void famineDeaths(){
        try(var q=s.sql("SELECT f.account FROM family_famine d JOIN family f ON f.rep=d.person");var rows=q.executeQuery()){
            var players=new ArrayList<Player>();
            while(rows.next()){
                var p=Bukkit.getPlayer(UUID.fromString(rows.getString(1)));
                if(p!=null && !p.isDead() && p.getGameMode()==GameMode.SURVIVAL && p.getWorld()==realm.world)players.add(p);
            }
            for(var p:players){p.sendMessage("庄园季账记录当代代表死于饥荒。");p.setHealth(0);}
        }catch(SQLException ex){failure(ex);}
    }
    private void failure(SQLException ex){realm.plugin.getLogger().log(Level.SEVERE,"家档读写失败",ex);}
    private int number(String[] a,int index){require(index<a.length,"缺少文书参数");return Integer.parseInt(a[index]);}
    private void size(String[] a,int len){require(a.length==len,"参数不符，请从 /realm family 打开家档表单");}
    private void near(Player p,String home)throws SQLException{
        var m=realm.manors.register.manor(home);
        require(p.getLocation().distanceSquared(new Location(realm.world,m.x()+.5,m.y()+.5,m.z()+.5))<=64,"请亲至该庄厅位八格内办理");
    }
    void checkPublicAct(Player player,String action,String[] args)throws SQLException{
        int house=enroll(player);
        if(house==0){
            require(Set.of("help","start","atlas","here","depots","travel","prices","buildings","init").contains(action),"旧家无合资格男嗣，请在家档另立新家");return;
        }
        int rep=book.rep(house);
        if(rep==0){require(Set.of("help","start","atlas","here","depots","travel","prices","buildings").contains(action),"请先在 /realm family 接续本家");return;}
        if(book.person(rep).age(book.season())>=18)return;
        boolean restricted=Set.of("grant","extend","estate","patent","cede","cancelcede","charter","sign","orgsign","ratify","vacate","appoint","yield","resist","relinquish","confirm","treaty","offer","enact","permit","peer","capital","rate","tollgrant","minegrant","remit","cosign","territory","firstdeed","tollform","register","found","frontier","withdraw","propose","pledge","stand","vote","notice","muster","peace","seatvault").contains(action);
        require(!restricted || (action.equals("territory") && args.length==2),"幼主摄政期间保留原约，重大权利变更须候成年");
    }
    private void adults(int who)throws SQLException{require(book.person(who).age(book.season())>=18,"幼主摄政期间只能办理家务、学习和既定供养");}
    private void atHome(Player p,int who)throws SQLException{
        String home=book.person(who).home();require(home!=null,"请先在私庄安家");near(p,home);
    }
    void run(Player p,String[] a)throws SQLException{
        realm.inWorld(p);String actor=account(p),op=a.length>1?a[1]:"本家";
        int f=enroll(p);var buttons=new ArrayList<ActionButton>();var body=new ArrayList<DialogBody>();
        if(f==0){
            if(op.equals("另立")){
                size(a,5);f=book.found(actor,a[2],a[3],a[4]);attach(p,book.rep(f));op="本家";
            }else{
                body.add(text("旧家已无合资格男嗣。新家取得新的家籍，原来的产业、债务及货物保留原账。"));
                if(op.equals("新家表")){identityForm(p,"另立");return;}
                buttons.add(command("另立新家","realm family 新家表"));show(p,"家档 · 男嗣已尽",body,List.of(),buttons,1);return;
            }
        }
        int rep=book.rep(f);
        if(rep==0){
            if(op.equals("接续")){
                size(a,3);book.assume(f,number(a,2));rep=book.rep(f);attach(p,rep);realm.changed();op="本家";
            }else{
                body.add(text(Objects.toString(lastReceipt(f),"本家代表已退居，请依次序选择接续人。")));int preferred=s.number("SELECT preferred FROM family WHERE id=?",f);
                for(int id:book.choices(f))buttons.add(command((id==preferred?"首选 · ":"接续 · ")+book.person(id).name(),"realm family 接续 "+id));
                show(p,"家档 · 身后交割",body,List.of(),buttons,1);return;
            }
        }
        if(!Objects.equals(p.getPersistentDataContainer().get(current,PersistentDataType.INTEGER),rep))attach(p,rep);
        book.syncAssets(actor,rep);
        switch(op){
            case "题名表" -> {identityForm(p,"题名");return;}
            case "题名" -> {size(a,5);String name=FamilyRegister.label(a[2]),person=FamilyRegister.label(a[3]),emblem=FamilyRegister.label(a[4]);
                final int who=rep,house=f;s.atomic(()->{s.update("UPDATE family SET name=?,emblem=? WHERE id=?",name,emblem,house);s.update("UPDATE kin SET name=? WHERE id=?",person,who);return null;});realm.changed();}
            case "首选" -> {size(a,3);book.prefer(f,number(a,2));op="继承";}
            case "领法" -> {require(a.length==4 || a.length==5,"请填写头衔、资格及次序");adults(rep);var title=s.title(a[2]);require((p.getLocation().getBlockX()>>4)==title.x() && (p.getLocation().getBlockZ()>>4)==title.z(),"须在本领治所办理");
                book.lineage.setLaw(a[2],actor,a[3],a.length==5?a[4]:"沿用家法");op="继承";}
            case "家法" -> {size(a,3);adults(rep);atHome(p,rep);require(FamilyRegister.LAWS.contains(a[2]),"未知家法");s.update("UPDATE family SET law=? WHERE id=?",a[2],f);op="继承";}
            case "交棒" -> {size(a,3);atHome(p,rep);book.retire(f,number(a,2));rep=book.rep(f);attach(p,rep);realm.changed();}
            case "落籍" -> {size(a,4);near(p,a[3]);life.settle(f,number(a,2),a[3]);op="亲谱";}
            case "征婚","收帖" -> {size(a,3);int who=number(a,2);book.own(f,who);
                if(op.equals("征婚")){var k=book.person(who);require(k.age(book.season())>=18 && k.home()!=null,"须成年且有居所");
                    s.update("INSERT OR IGNORE INTO family_notice VALUES(?)",who);}
                else s.update("DELETE FROM family_notice WHERE person=?",who);op="婚约";}
            case "议婚" -> {require(a.length==7 || a.length==8,"请从婚约页面拟约");adults(rep);near(p,a[4]);
                if(a.length==8){
                    String paternal=book.person(number(a,2)).sex().equals("男")?"我家":"对家";
                    require(paternal.equals(a[5]),"新婚书子女归父家，请在婚约页面重新拟约");
                }
                life.marriage(f,number(a,2),number(a,3),a[4],number(a,a.length-2),number(a,a.length-1));op="婚约";}
            case "签婚" -> {size(a,3);adults(rep);int match=number(a,2);
                int origin=s.number("SELECT a FROM family_match WHERE id=?",match);var first=book.person(origin);
                Player partner=Bukkit.getPlayer(UUID.fromString(book.account(first.family())));
                require(partner!=null && !partner.isDead() && partner.getWorld()==p.getWorld() && partner.getLocation().distanceSquared(p.getLocation())<=144,"两家代表须在同一庄宅交换婚书");
                near(p,s.value("SELECT home FROM family_match WHERE id=?",match));life.sign(f,match);op="婚约";}
            case "撤婚" -> {size(a,3);require(s.update("""
                UPDATE family_match SET state='已撤回' WHERE id=? AND state='议婚'
                AND (a IN (SELECT id FROM kin WHERE family=?) OR b IN (SELECT id FROM kin WHERE family=?))""",number(a,2),f,f)==1,"只可撤回本家未签的草案");op="婚约";}
            case "养育","暂停养育" -> {size(a,3);life.raising(f,number(a,2),op.equals("养育"));op="婚约";}
            case "监护" -> {size(a,4);life.guard(f,number(a,2),number(a,3));op="婚约";}
            case "托业" -> {size(a,5);adults(rep);near(p,a[3]);life.steward(f,number(a,2),a[3],a[4]);op="家产";}
            case "查账" -> {size(a,3);near(p,a[2]);require(life.stewards(actor,a[2],"账房") || actor.equals(realm.manors.register.manor(a[2]).owner()),"须由本庄业主或受托账房查阅");
                try(var q=s.sql("SELECT season,opening,bread,shortfall,closing FROM population_season WHERE manor=? ORDER BY season DESC LIMIT 6",a[2]);var r=q.executeQuery()){
                    while(r.next())body.add(text("第 "+r.getInt(1)+" 季　期初 "+r.getInt(2)+" 人　食粮 "+r.getInt(3)+"　缺口 "+r.getInt(4)+" 人份　期末 "+r.getInt(5)+" 人"));}
                op="家产";}
            case "分房" -> {size(a,5);adults(rep);near(p,a[3]);life.branch(f,number(a,2),a[3],a[4]);op="家产";}
            case "委托" -> {size(a,5);adults(rep);var t=s.title(a[2]);require(p.getLocation().getBlockX()>>4==t.x() && p.getLocation().getBlockZ()>>4==t.z(),"请到持印亲属的治所登记");book.mandate(f,a[2],a[3],number(a,4));op="家产";}
            case "分产" -> {size(a,5);adults(rep);atHome(p,rep);book.own(f,number(a,4));book.holdings.deed(f,rep,number(a,4),a[2],a[3]);op="家产";}
            case "借出" -> {size(a,5);adults(rep);atHome(p,rep);cash.offer(f,a[2],number(a,3),number(a,4));op="家产";}
            case "签借","清偿" -> {size(a,5);adults(rep);cash.loan(p,f,number(a,2),a[3],a[4],op.equals("清偿"));op="家产";}
            case "撤借" -> {size(a,3);require(s.update("UPDATE family_loan SET state='已撤' WHERE id=? AND lender=? AND state='待签'",number(a,2),f)==1,"只可撤回本家未交割的借据");op="家产";}
            case "封存" -> {size(a,5);adults(rep);cash.store(p,rep,a[2],a[3],number(a,4));op="家产";}
            case "分银" -> {size(a,4);cash.divide(p,a[2],a[3]);op="家产";}
            case "估值" -> {size(a,5);adults(rep);atHome(p,rep);book.holdings.value(rep,a[2],a[3],number(a,4));op="家产";}
            case "扎营" -> {livelihood.camp(p,f);op="家产";}
            case "差事" -> {livelihood.orders(p,f);return;}
            case "出租" -> {size(a,5);adults(rep);near(p,a[3]);livelihood.lease(f,number(a,2),a[3],number(a,4));op="家产";}
            case "签租" -> {size(a,3);adults(rep);livelihood.signLease(p,f,number(a,2));op="家产";}
            case "拨户" -> {size(a,4);adults(rep);near(p,a[3]);livelihood.settlers(f,number(a,2),a[3]);op="家产";}
            case "请客" -> {size(a,4);adults(rep);near(p,a[3]);livelihood.invite(f,number(a,2),a[3]);op="家产";}
            case "入住" -> {size(a,3);near(p,a[2]);livelihood.arrive(f,rep,a[2]);op="家产";}
            case "学徒" -> {size(a,5);near(p,a[3]);life.apprentice(f,number(a,2),a[3],a[4]);op="亲谱";}
            case "出师" -> {size(a,3);life.graduate(f,number(a,2));op="亲谱";}
            case "陪同" -> {size(a,4);journey.depart(p,f,number(a,2),a[3]);op="亲谱";}
            case "到庄","返乡" -> {journey.arrive(p,f,op.equals("返乡"));op="亲谱";}
            case "援送" -> {size(a,3);journey.assist(p,f,number(a,2));op="亲谱";}
            case "接回" -> {journey.resume(p,f);op="亲谱";}
            case "迁居" -> {size(a,3);near(p,a[2]);moveSpouse(f,rep,a[2]);op="婚约";}
            case "封嫁资","封供养" -> {size(a,4);new FamilyPayments(realm,book).pack(p,f,number(a,2),a[3],op.equals("封嫁资"));op="婚约";}
            case "付嫁资","付供养" -> {size(a,4);payment(p,f,number(a,2),a[3],op.equals("付嫁资"));op="婚约";}
            case "填表" -> {size(a,3);form(p,a[2],rep);return;}
            default -> require(Set.of("本家","亲谱","婚约","家产","继承").contains(op),"请从家档选择事务");
        }
        var self=book.person(rep);
        if(op.equals("家产") || op.equals("继承"))try(var q=s.sql("""
            SELECT l.*,c.amount FROM family_loan l JOIN family_cash c ON c.id=l.principal WHERE lender=? OR borrower=? ORDER BY l.id""",f,f);var r=q.executeQuery()){
            while(r.next()){
                body.add(text("借据 #"+r.getInt("id")+"　"+(r.getInt("lender")==f?"应收":"应付")+" "+r.getInt("amount")+" 银　"+r.getString("state")+(r.getString("pledge")==null?"":"　优先偿款 "+r.getString("pledge")+"　到期 "+r.getInt("due")+" 季")));
                if(r.getInt("lender")==f && r.getString("state").equals("待签"))buttons.add(command("撤借 #"+r.getInt("id"),"realm family 撤借 "+r.getInt("id")));
            }
        }
        if(op.equals("亲谱")){
            for(int id:book.ids("SELECT id FROM kin WHERE family=? ORDER BY id",f)){
                var k=book.person(id);String rel=k.parent()!=0?"双亲 #"+k.parent()+"、#"+k.other():k.sibling()!=0?"同胞 #"+k.sibling():"";
                int end=k.alive()?book.season():s.number("SELECT death FROM kin WHERE id=?",id);
                body.add(text("#"+id+" "+k.name()+"　"+k.sex()+"　"+k.age(end)+" 岁　"+(k.alive()?"在世":"已故")+"　"+rel+"　"+(k.home()==null?"无地":k.home())+(k.branch()==0?"":" · "+s.value("SELECT name FROM family_branch WHERE id=?",k.branch()))));
            }
            for(int grandson:book.lineage.grandsons(rep,false)){
                var child=book.person(grandson);body.add(text("外孙 #"+grandson+" "+child.name()+"　父家 #"+child.family()+(book.lineage.ai(child.family())?" · 乡户":" · 玩家家族")));
            }
            for(int stranded:journey.stranded(p))buttons.add(command("援送附近的"+book.person(stranded).name(),"realm family 援送 "+stranded));
            String travel=s.value("SELECT source||' → '||dest||'　位置 '||x||'，'||y||'，'||z FROM family_journey WHERE family=?",f);
            if(travel!=null){
                body.add(text("亲属同行　"+travel));
                for(String cmd:List.of("接回","到庄","返乡"))buttons.add(command(cmd,"realm family "+cmd));
            }
            for(String action:List.of("落籍","陪同","学徒","出师"))buttons.add(command(action,"realm family 填表 "+action));
        }else if(op.equals("婚约")){
            body.add(text("新婚书按父家登记子女。乡户为独立家族，外孙保留父家家籍。"));showMatches(f,body,buttons);
            for(String action:List.of("征婚","收帖","议婚","监护","迁居","封嫁资","封供养","付嫁资","付供养"))buttons.add(command(action,"realm family 填表 "+action));
        }else if(op.equals("家产")){
            try(var q=s.sql("SELECT kind,asset,k.name FROM family_asset a JOIN kin k ON a.person=k.id WHERE k.family=? ORDER BY kind,asset",f);var row=q.executeQuery()){
                while(row.next())body.add(text(row.getString(1)+"　"+row.getString(2)+"　权利人 "+row.getString(3)));}
            for(int member:book.living(f))for(var lot:book.holdings.lots(member))if(lot.deceasedShare()<10000)
                body.add(text(lot.name()+" · "+book.person(member).name()+" 实益份额 "+(lot.deceasedShare()/100.0)+"%"));
            for(int member:book.living(f)){
                for(var r:book.preview(member))if(r.kind().equals("职权"))body.add(text(r.name()+"（"+r.key()+"）　"+book.person(member).name()+"　"+r.basis()));
            }
            try(var q=s.sql("SELECT t.name,k.name,m.task,m.until FROM family_mandate m JOIN title t ON t.id=m.title JOIN kin k ON k.id=m.issuer WHERE k.family=? AND m.until>? AND t.seal=('族人·'||m.issuer)",f,book.season());var row=q.executeQuery()){
                while(row.next())body.add(text(row.getString(1)+" · "+row.getString(2)+" 授权"+row.getString(3)+"至第 "+row.getInt(4)+" 季"));
            }
            livelihood.page(f,body,buttons);
            for(String action:List.of("分房","分产","估值","封存","分银","借出","签借","清偿","委托","托业","查账"))buttons.add(command(action,"realm family 填表 "+action));
        }else if(op.equals("继承")){
            body.add(text("角色接续：本家男性后裔，再本家兄弟。仅无人承接时可迎回乡户外孙。"));
            var candidates=book.successors(rep);var names=new ArrayList<String>();for(int id:candidates){var kin=book.person(id);names.add(kin.name()+" #"+id+(kin.family()==f?"":"（乡户外孙）"));}
            body.add(text("接续人选　"+(names.isEmpty()?"无人":String.join("、",names))));
            body.add(text("现行家法　"+s.value("SELECT law FROM family WHERE id=?",f)));
            body.add(text(book.report(book.preview(rep))));
            try(var q=s.sql("SELECT id,dowry-paid,support FROM family_match WHERE (a=? OR b=?) AND state IN ('已婚','遗属') AND (dowry>paid OR support>0)",rep,rep);var row=q.executeQuery()){
                while(row.next())body.add(text("婚书 #"+row.getInt(1)+" 仍有效　未交嫁资 "+row.getInt(2)+" 银　遗属每季 "+row.getInt(3)+" 银"));
            }
            body.add(text("公库与在途货按原账；随身实物留在死亡现场。"));
            int pref=s.number("SELECT preferred FROM family WHERE id=?",f);
            for(int id:book.successors(rep))buttons.add(command((id==pref?"已首选 · ":"首选 · ")+book.person(id).name(),"realm family 首选 "+id));
            buttons.add(command("在家交棒","realm family 填表 交棒"));buttons.add(command("变更家法","realm family 填表 家法"));buttons.add(command("领地继承法","realm family 填表 领法"));
        }else{
            body.add(text("当代代表　"+self.name()+"　#"+rep+"　"+self.age(book.season())+" 岁"));
            body.add(text("家籍 #"+f+"　家徽　"+s.value("SELECT emblem FROM family WHERE id=?",f)+"　家中在世 "+book.living(f).size()+" 人"));
            if(self.age(book.season())<18){int guardian=life.regent(rep);body.add(text("幼主摄政　"+(guardian==0?"暂维持日常收支":book.person(guardian).name())));}
            if(s.number("SELECT count(*) FROM life_receipt l JOIN kin k ON k.id=l.person WHERE k.family=?",f)>0)body.add(text(lastReceipt(f)));
            buttons.add(command("题家名与家徽","realm family 题名表"));
        }
        for(String page:List.of("本家","亲谱","婚约","家产","继承"))if(!op.equals(page))buttons.add(command(page.equals("继承")?"继承预览":page,"realm family "+page));
        show(p,s.value("SELECT name FROM family WHERE id=?",f)+" · "+(Set.of("亲谱","婚约","家产","继承").contains(op)?op:"本家"),body,List.of(),buttons,2);
    }
    private String lastReceipt(int f)throws SQLException{
        return s.value("SELECT k.name||' 身故 · 交割回执'||char(10)||l.summary FROM life_receipt l JOIN kin k ON k.id=l.person WHERE k.family=? ORDER BY l.season DESC,l.rowid DESC LIMIT 1",f);
    }
    private void identityForm(Player p,String op){
        DiplomacyDesk.form(p,"登记家名与代表","每个账号经营一家；家徽填写图案名称。","realm family "+op,
                List.of(field("家名",p.getName()+"家"),field("代表姓名",p.getName()),field("家徽","麦穗")));
    }
    private DiplomacyDesk.Field field(String name,String initial){return new DiplomacyDesk.Field("f"+Integer.toUnsignedString(name.hashCode()),name,initial,24);}
    private void form(Player p,String op,int rep){
        String[] labels=switch(op){
            case "陪同" -> new String[]{"族人号","目的庄园代号"};
            case "落籍" -> new String[]{"族人号","庄园代号"};
            case "出租" -> new String[]{"承租家族号","庄园代号","有效季数"};
            case "请客","拨户" -> new String[]{"受邀家族号","庄园代号"};
            case "入住" -> new String[]{"庄园代号"};
            case "学徒" -> new String[]{"族人号","庄园代号","铁工或账房"};
            case "出师","交棒","征婚","收帖" -> new String[]{"族人号"};
            case "领法" -> new String[]{"头衔代号","父系男嗣或女系递传","沿用家法或长嗣幼嗣年长者"};
            case "家法" -> new String[]{"家法名称"};
            case "委托" -> new String[]{"头衔代号","亲收税箱或交印","有效季数"};
            case "借出" -> new String[]{"本金封银代号","借方家族号","借期季数"};
            case "签借","清偿" -> new String[]{"借据号","交付庄园代号","偿款封银代号"};
            case "封存" -> new String[]{"庄园代号","封银代号","银币数"};
            case "分银" -> new String[]{"庄园代号","封银代号"};
            case "托业" -> new String[]{"承办人号","庄园代号","铁工或账房"};
            case "查账" -> new String[]{"庄园代号"};
            case "估值" -> new String[]{"庄产或商站","产业代号","分产估值"};
            case "分产" -> new String[]{"庄产或商站","产业代号","新权利人号"};
            case "分房" -> new String[]{"族人号","庄园代号","房名"};
            case "议婚" -> new String[]{"族人号","对方人物号（本地填0）","庄园代号","嫁资银数","每季遗属供养银数"};
            case "监护" -> new String[]{"幼主号","成年监护人号"};
            case "迁居" -> new String[]{"配偶庄园代号"};
            case "封嫁资","封供养" -> new String[]{"婚书号","付款庄园代号"};
            case "付嫁资","付供养" -> new String[]{"婚书号","收款庄园代号"};
            default -> throw new IllegalArgumentException("未知表单");
        };
        var fields=new ArrayList<DiplomacyDesk.Field>();
        for(String name:labels)fields.add(field(name,switch(name){case "族人号"->String.valueOf(rep);case "对方人物号（本地填0）","嫁资银数","每季遗属供养银数"->"0";case "父系男嗣或女系递传"->"父系男嗣";case "沿用家法或长嗣幼嗣年长者"->"沿用家法";case "家法名称"->"普通分割";default->"";}));
        DiplomacyDesk.form(p,op,op.equals("家法")?String.join("、",FamilyRegister.LAWS):"族人号见亲谱；庄园代号见庄档。","realm family "+op,fields);
    }
    private void showMatches(int f,List<DialogBody> body,List<ActionButton> buttons)throws SQLException{
        try(var q=s.sql("""
            SELECT k.id,k.name,f.name,k.home,k.sex FROM family_notice n JOIN kin k ON k.id=n.person JOIN family f ON f.id=k.family
            WHERE k.death IS NULL AND NOT EXISTS(SELECT 1 FROM family_match WHERE (a=k.id OR b=k.id) AND state IN ('议婚','已婚')) ORDER BY k.id""");var r=q.executeQuery()){
            while(r.next())body.add(text("公开征婚　#"+r.getInt(1)+" "+r.getString(2)+" · "+r.getString(5)+"　"+r.getString(3)+" · "+r.getString(4)));
        }
        try(var q=s.sql("""
            SELECT m.*,x.family af,y.family bf,x.name an,y.name bn FROM family_match m JOIN kin x ON x.id=m.a
            JOIN kin y ON y.id=m.b JOIN marriage_parties pact ON pact.marriage=m.id
            WHERE ? IN (x.family,y.family,pact.first_house,pact.second_house) ORDER BY m.id DESC""",f);var r=q.executeQuery()){
            while(r.next()){
                int id=r.getInt("id");String state=r.getString("state");
                body.add(text("#"+id+" "+r.getString("an")+" × "+r.getString("bn")+"　"+state+"　子女入家 #"+r.getInt("children")+"　住地 "+r.getString("home")+"　嫁资 "+r.getInt("paid")+"/"+r.getInt("dowry")+" 银　遗属每季 "+r.getInt("support")+" 银"));
                if(state.equals("议婚")){
                    if(r.getInt("bf")==f)buttons.add(command("同场签婚 #"+id,"realm family 签婚 "+id));
                    buttons.add(command("撤回草案 #"+id,"realm family 撤婚 "+id));
                }
                if(state.equals("已婚") && (r.getInt("children")==f || (book.lineage.ai(r.getInt("children")) && r.getInt("af")==f))){String action=r.getBoolean("raising")?"暂停养育":"养育";buttons.add(command(action+" #"+id,"realm family "+action+" "+id));}
            }
        }
    }
    private void moveSpouse(int f,int id,String home)throws SQLException{
        s.atomic(()->{
            var k=book.person(id);require(k.home()!=null && !home.equals(k.home()),"请到配偶所在地");
            require(s.number("""
                SELECT count(*) FROM family_match m JOIN kin other ON other.id=CASE WHEN a=? THEN b ELSE a END
                WHERE (a=? OR b=?) AND state='已婚' AND other.home=? AND m.home=?""",id,id,id,home,home)>0,"须迁入婚约所列的共同居所");
            require(s.update("UPDATE manor SET residents=residents+1 WHERE id=? AND housing>residents",home)==1,"目的庄没有空位");
            require(s.update("UPDATE manor SET residents=residents-1 WHERE id=? AND residents>0",k.home())==1,"原庄人数不足");
            s.update("UPDATE kin SET home=? WHERE id=?",home,id);return null;
        });
    }
    private void payment(Player p,int f,int match,String home,boolean dowry)throws SQLException{
        new FamilyPayments(realm,book).pay(p,f,match,home,dowry);
    }
}
