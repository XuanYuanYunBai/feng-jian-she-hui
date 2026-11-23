package social.feudal.realm;

import java.sql.SQLException;
import java.util.Arrays;
import org.bukkit.command.CommandSender;
import static social.feudal.realm.RealmService.*;

final class Council {
    private final RealmService realm;
    final Succession succession;
    Council(RealmService realm)throws SQLException{
        this.realm=realm;succession=new Succession(realm.store,realm.charters.law,realm.taxes.ledger);
    }
    private static void requireKinNumber(String who){RealmStore.require(who.matches("[0-9]{1,9}"),"本家继承提名请填写亲谱人物号；另行任命可选外人");}
    void execute(CommandSender s,String[] a)throws SQLException{
        usage(a.length>=2,a[0]+" 头衔ID");String id=a[1];
        if(a[0].equals("acts")){
            realm.store.title(id);
            s.sendMessage("◆ 议事记录 · "+id+"  地方支持与资历 "+succession.support(id));
            try(var q=realm.store.sql("SELECT period,event FROM instrument WHERE title=? ORDER BY id DESC LIMIT 8",id);var r=q.executeQuery()){
                while(r.next())s.sendMessage("第 "+r.getInt(1)+" 期 · "+r.getString(2));
            }
            return;
        }
        var p=player(s);realm.inWorld(p);String actor=p.getUniqueId().toString();
        switch(a[0]){
            case "heir","appoint" -> {
                usage(a.length==3,a[0]+" 头衔ID 继任人");String next;
                int family=realm.families.book.family(actor);
                if(a[0].equals("heir") && family!=0){
                    requireKinNumber(a[2]);int person=Integer.parseInt(a[2]);
                    RealmStore.require(realm.families.book.lineage.titleHeirs(realm.families.book.rep(family),id).contains(person),"此人不在本领法定男性继承顺位内");next=FamilyRegister.personal(person);
                    realm.store.atomic(()->{succession.nominate(id,actor,next);realm.store.update("INSERT OR REPLACE INTO family_nomination VALUES(?,?)",id,person);return null;});
                    break;
                }
                next=realm.charters.grantee(a[2]);
                if(a[0].equals("heir"))succession.nominate(id,actor,next);else succession.appoint(id,actor,next);
            }
            case "support" -> succession.sponsor(id,actor,p.getLocation().getBlockX()>>4,p.getLocation().getBlockZ()>>4);
            case "petition" -> succession.petition(id,actor);
            case "confirm" -> succession.confirm(id,actor);
            case "relinquish" -> succession.relinquish(id,actor);
            case "vacate" -> succession.vacate(id,actor,realm.families.book.family(actor)==0);
            case "yield","resist" -> succession.seal(id,a[0].equals("yield") && realm.families.book.delegated(actor,id,"交印")?realm.store.title(id).seal():actor,a[0].equals("yield"));
            case "offer" -> {
                usage(a.length>=4,"offer 头衔ID 王号|独立 协议条件");
                succession.offer(id,actor,a[2],String.join(" ",Arrays.copyOfRange(a,3,a.length)));
            }
            case "ratify" -> succession.ratify(id,actor);
            default -> throw new IllegalArgumentException("未知议事操作");
        }
        var t=realm.store.title(id);realm.changed();
        s.sendMessage("已登记 · "+t.name()+" · "+t.rank()+"  法定 "+realm.display(t.lawful())+"  |  持印 "+realm.display(t.seal()));
    }
}
