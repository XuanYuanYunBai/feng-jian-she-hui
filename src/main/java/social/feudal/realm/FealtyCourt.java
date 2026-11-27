package social.feudal.realm;

import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static social.feudal.ui.Menus.*;
import static social.feudal.realm.RealmStore.require;

final class FealtyCourt {
    private final RealmService realm;
    final Fealty ledger;
    FealtyCourt(RealmService realm)throws SQLException {
        this.realm=realm;
        ledger=new Fealty(realm.store);
    }
    void open(Player p,String[] args)throws SQLException {
        realm.inWorld(p);
        String actor=p.getUniqueId().toString();
        var buttons=new ArrayList<ActionButton>();
        var body=new ArrayList<DialogBody>();
        if(args.length==1){
            for(String id:realm.diplomacy.column("""
                SELECT v.id FROM title v JOIN title l ON v.parent=l.id
                WHERE v.seal=? OR l.seal=? ORDER BY v.id""",actor,actor)){
                var b=ledger.bond(id);
                buttons.add(command(b.fief().name()+" · "+status(b),"realm fealty "+id));
            }
            body.add(text("每份封契各立一誓。双方到直属封君的治所，封臣先呈誓，封君再受誓。任一方换代须重行其礼。"));
            if(buttons.isEmpty())body.add(text("你目前没有持印的直属效忠关系。"));
        }else{
            var b=ledger.bond(args[1]);
            if(args[0].equals("swear")){
                require(args.length==5,"请重新打开效忠誓书");
                require((p.getLocation().getBlockX()>>4)==b.lord().x() && (p.getLocation().getBlockZ()>>4)==b.lord().z(),"请到直属封君的治所区块行礼");
                for(String holder:List.of(b.lord().seal(),b.fief().seal())){
                    if(!Succession.isPlayer(holder))continue;
                    Player other=Bukkit.getPlayer(UUID.fromString(holder));
                    require(other!=null && !other.isDead() && other.getWorld()==realm.world
                        && other.getLocation().distanceSquared(p.getLocation())<=144
                        && (other.getLocation().getBlockX()>>4)==b.lord().x() && (other.getLocation().getBlockZ()>>4)==b.lord().z(),"双方须同在治所，相距十二格以内");
                }
                ledger.sign(args[1],actor,args[2],args[3],Integer.parseInt(args[4]));
                for(String holder:List.of(b.lord().seal(),b.fief().seal()))if(!holder.equals(actor) && Succession.isPlayer(holder)){
                    Player other=Bukkit.getPlayer(UUID.fromString(holder));
                    if(other!=null)other.sendMessage(net.kyori.adventure.text.Component.text("效忠礼 · "+b.fief().name()+"　点击打开誓书")
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/realm fealty "+args[1])));
                }
            }
            var current=ledger.current(b);
            body.add(text(b.fief().name()+"　向　"+b.lord().name()+"　宣誓"));
            body.add(text("当代封臣　"+realm.display(b.vassal())+"　｜　封君　"+realm.display(b.ruler())));
            body.add(text("治所区块　"+b.lord().x()+"，"+b.lord().z()+"　｜　"+status(b)));
            body.add(text("双方本人到场。具名非玩家持印人由治所代为应礼。续誓后可办理转封与请袭。"));
            String action="realm swear "+args[1]+" "+b.vassal()+" "+b.ruler()+" "+current.id();
            if(b.ready() && current.id()==0 && (actor.equals(b.fief().seal()) || (!Succession.isPlayer(b.fief().seal()) && actor.equals(b.lord().seal()))))
                buttons.add(command("当场行效忠礼",action));
            if(b.ready() && current.state().equals("待受誓") && actor.equals(b.lord().seal()))
                buttons.add(command("当面接受誓言",action));
            try(var q=realm.store.sql("SELECT id,vassal,ruler,state,offered,accepted FROM fealty WHERE fief=? ORDER BY id DESC LIMIT 8",args[1]);var r=q.executeQuery()){
                while(r.next())body.add(text("#"+r.getInt(1)+"　"+realm.display(r.getString(2))+" → "+realm.display(r.getString(3))+"　"+r.getString(4)+"　呈誓季 "+r.getInt(5)+(r.getObject(6)==null?"":"　受誓季 "+r.getInt(6))));
            }
            buttons.add(command("返回本家誓书","realm fealty"));
        }
        buttons.add(command("经营簿","realm journal"));
        show(p,"效忠誓书",body,List.of(),buttons,2);
    }
    private String status(Fealty.Bond b)throws SQLException{
        if(b.ready())return ledger.current(b).state();
        return b.vassal()!=null && b.vassal().equals(b.ruler())?"一人兼领":"待两方印主就位";
    }
}
