package social.feudal.realm;

import java.sql.SQLException;
import java.util.List;
import org.bukkit.entity.Player;
import static social.feudal.realm.RealmStore.require;
import static social.feudal.ui.Menus.*;

/** A surrender of the whole seat requires local delivery of the seal. Bridge disputes never call this. */
final class TerritorialControl {
    private final RealmService r;
    TerritorialControl(RealmService r){this.r=r;}
    static void schema(RealmStore s)throws SQLException{
        s.update("""
            CREATE TABLE IF NOT EXISTS occupation(title TEXT PRIMARY KEY REFERENCES title(id),nation TEXT REFERENCES nation(id),
            former TEXT NOT NULL,holder TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 0)""");
        s.update("""
            CREATE TRIGGER IF NOT EXISTS replace_occupation AFTER UPDATE OF seal ON title
            WHEN NEW.seal<>OLD.seal BEGIN
              DELETE FROM occupation WHERE title=NEW.id AND
              ((active=1 AND holder<>NEW.seal) OR (active=0 AND former<>NEW.seal AND holder<>NEW.seal));
            END""");
        s.update("""
            CREATE VIEW IF NOT EXISTS dwelling_jurisdiction AS SELECT m.id,l.title,
            coalesce(o.nation,a.nation,l.title,'@') AS district FROM manor m
            LEFT JOIN land l ON l.x=(m.x>>4) AND l.z=(m.z>>4)
            LEFT JOIN affiliation a ON a.title=l.title
            LEFT JOIN occupation o ON o.title=l.title AND o.active=1""");
    }
    static void offer(RealmStore s,String title,String nation,String actor)throws SQLException{
        require(s.title(title).seal().equals(actor),"须由本领实际持印人提出交接");
        String holder=s.title(nation).seal();s.politics.represents(nation,holder);
        require(!actor.equals(holder) && !nation.equals(s.politics.nation(title)),"须交接给另一国家的持印人");
        require(s.number("SELECT active FROM occupation WHERE title=?",title)==0,"本领已有生效占领");
        s.update("INSERT OR REPLACE INTO occupation VALUES(?,?,?,?,0)",title,nation,actor,holder);
    }
    static void accept(RealmStore s,String title,String actor)throws SQLException{
        s.atomic(()->{
            String nation=s.value("SELECT nation FROM occupation WHERE title=? AND holder=? AND active=0",title,actor);
            require(nation!=null,"没有向你交付的领印");s.politics.represents(nation,actor);
            require(s.update("""
                UPDATE title SET seal=? WHERE id=? AND seal=(SELECT former FROM occupation WHERE title=?)
                """,actor,title,title)==1,"原领印已变更");
            s.update("UPDATE occupation SET active=1 WHERE title=?",title);
            s.politics.log(null,nation,"接管整领 "+title+"，住户照原地登记");return null;
        });
    }
    void run(Player p,String[] a)throws SQLException{
        r.inWorld(p);RealmService.usage(a.length>=2 && a.length<=4,"territory 领地 [交印 国家|接受|归还|撤函]");
        var s=r.store;String id=a[1],actor=p.getUniqueId().toString();var t=s.title(id);
        if(a.length>2){
            require(p.getLocation().getBlockX()>>4==t.x() && p.getLocation().getBlockZ()>>4==t.z(),"须亲临领地治所区块交接");
            switch(a[2]){
                case "交印" -> {RealmService.usage(a.length==4,"territory 领地 交印 接管国");offer(s,id,a[3],actor);}
                case "接受" -> accept(s,id,actor);
                case "撤函" -> require(s.update("DELETE FROM occupation WHERE title=? AND active=0 AND former=?",id,actor)==1,"没有可撤回的交接");
                case "归还" -> s.atomic(()->{
                    require(s.number("SELECT count(*) FROM occupation WHERE title=? AND holder=? AND active=1",id,actor)==1,"须由占领持印人归还");
                    s.update("UPDATE title SET seal=lawful WHERE id=?",id);s.update("DELETE FROM occupation WHERE title=?",id);
                    s.politics.log(null,null,"归还领印 "+id);return null;
                });
                default -> throw new IllegalArgumentException("未知领印事务");
            }
            r.changed();t=s.title(id);
        }
        var buttons=new java.util.ArrayList<io.papermc.paper.registry.data.dialog.ActionButton>();
        var body=new java.util.ArrayList<io.papermc.paper.registry.data.dialog.body.DialogBody>();
        int people=s.number("SELECT coalesce(sum(m.residents),0) FROM manor m JOIN dwelling_jurisdiction j USING(id) WHERE j.title=?",id);
        body.add(text("领地实住 "+people+" 人　｜　实际持印 "+r.display(t.seal())));
        String occupying=s.value("SELECT nation FROM occupation WHERE title=? AND active=1",id);
        body.add(text("法理所属　"+s.polity(id)+(occupying==null?"":"　｜　实际占领国　"+s.title(occupying).name())));
        for(String home:r.diplomacy.column("SELECT id FROM dwelling_jurisdiction WHERE title=? ORDER BY id",id)){
            var m=r.manors.register.manor(home);body.add(text(m.name()+"　"+m.residents()+" 人"));
        }
        if(t.seal().equals(actor)){
            if(occupying!=null)buttons.add(command("归还领印","realm territory "+id+" 归还"));
            else{
                buttons.add(command("拟整领交接","realm surrenderform "+id));
                if(s.number("SELECT count(*) FROM occupation WHERE title=?",id)>0)buttons.add(command("撤回交接","realm territory "+id+" 撤函"));
            }
        }
        if(s.number("SELECT count(*) FROM occupation WHERE title=? AND holder=? AND active=0",id,actor)>0)
            buttons.add(command("在治所接印","realm territory "+id+" 接受"));
        buttons.add(command("国家人口汇总","realm census"));show(p,t.name()+" · 领地人口簿",body,List.of(),buttons,2);
    }
}
