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
            case "preview","grant" -> {
                usage(a.length==10,a[0]+" 上级 公爵|伯爵|流官 新ID 领名 人名 西 北 东 南");
                boolean preview=a[0].equals("preview");
                s.sendMessage((preview?"预览 · ":"授封完成 · ")+law.grant(actor,a[1],a[2],a[3],a[4],grantee(a[5]),bounds(a,6),preview));
            }
            case "extend","estimate" -> {
                usage(a.length==6,a[0]+" 流官领ID 西 北 东 南");
                boolean preview=a[0].equals("estimate");
                s.sendMessage((preview?"预览 · ":"追加完成 · ")+law.add(actor,a[1],bounds(a,2),preview));
            }
            case "estate" -> {
                usage(a.length==7,"estate 上级 庄园|修院|磨坊|地租 新ID 名称 人名 税仓ID");
                law.estate(actor,a[1],a[2],a[3],a[4],grantee(a[5]),realm.taxes.ledger.depot(a[6]));
                s.sendMessage("收益封契生效 · "+a[4]+"。领界仍归 "+realm.store.title(a[1]).name());
            }
            default -> throw new IllegalArgumentException("未知授封操作");
        }
        realm.council.succession.sync();realm.changed();
    }
}
