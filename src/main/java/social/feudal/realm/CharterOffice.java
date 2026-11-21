package social.feudal.realm;

import java.sql.SQLException;
import org.bukkit.command.CommandSender;
import static social.feudal.realm.RealmService.*;

final class CharterOffice {
    final Charters law;
    private final RealmService realm;
    CharterOffice(RealmService realm){this.realm=realm;law=new Charters(realm.store);}
    String grantee(String name){
        if(name.startsWith("npc-") && name.matches("npc-[\\p{L}0-9_-]{1,20}"))return name;
        return realm.actor(name);
    }
    private Charters.Bounds bounds(String[] a,int i){
        return new Charters.Bounds(Integer.parseInt(a[i]),Integer.parseInt(a[i+1]),Integer.parseInt(a[i+2]),Integer.parseInt(a[i+3]));
    }
    void execute(CommandSender s,String[] a)throws SQLException{
        var p=player(s);realm.inWorld(p);String actor=p.getUniqueId().toString();
        switch(a[0]){
            case "patent" -> {
                usage(a.length>=11,"patent 帝国ID 伯爵|城市|修院 新ID 名称 人名 西 北 东 南 特许条件");
                law.patent(actor,a[1],a[2],a[3],a[4],grantee(a[5]),bounds(a,6),
                        String.join(" ",java.util.Arrays.copyOfRange(a,10,a.length)));
                s.sendMessage("帝国直属特许已签发 · "+a[4]);
            }
            case "register" -> {
                usage(a.length==6,"register 君主头衔 西 北 东 南");var b=bounds(a,2);
                law.acts(a[1],actor);
                var sites=realm.diplomacy.column("SELECT id FROM manor WHERE owner=? AND (x >> 4) BETWEEN ? AND ? AND (z >> 4) BETWEEN ? AND ?",actor,b.west(),b.east(),b.north(),b.south());
                RealmStore.require(sites.size()==1,"请在选区先立庄，或在拟设庄宅办理 /realm frontier 庄园 头衔 西 北 东 南");
                realm.manors.run(p,new String[]{"frontier",sites.getFirst(),a[1],a[2],a[3],a[4],a[5]});
            }
            case "preview","grant" -> {
                usage(a.length==10,a[0]+" 上级 公爵|伯爵|流官 新ID 领名 人名 西 北 东 南");
                boolean preview=a[0].equals("preview");
                var b=bounds(a,6);String person=grantee(a[5]);
                s.sendMessage((preview?"预览 · ":"授封完成 · ")+law.grant(actor,a[1],a[2],a[3],a[4],person,b,preview));
                if(preview)realm.preview(p,b,a[2].equals("流官")?"合计 "+(law.areaHeld(person)+b.area())+"/256":"授予 "+b.area());
            }
            case "extend","estimate" -> {
                usage(a.length==6,a[0]+" 流官领ID 西 北 东 南");
                boolean preview=a[0].equals("estimate");
                var b=bounds(a,2);
                s.sendMessage((preview?"预览 · ":"追加完成 · ")+law.add(actor,a[1],b,preview));
                if(preview)realm.preview(p,b,"合计 "+(law.areaHeld(realm.store.title(a[1]).seal())+b.area())+"/256");
            }
            case "estate" -> {
                usage(a.length==7,"estate 上级 庄园|修院|磨坊|地租 新ID 名称 人名 税仓ID");
                law.estate(actor,a[1],a[2],a[3],a[4],grantee(a[5]),realm.taxes.ledger.depot(a[6]));
                s.sendMessage("收益封契生效 · "+a[4]+"。领界仍归 "+realm.store.title(a[1]).name());
            }
            default -> throw new IllegalArgumentException("未知授封操作");
        }
        if(!a[0].equals("preview") && !a[0].equals("estimate")){
            realm.clearPreview(p);realm.council.succession.sync();realm.changed();
        }
    }
}
