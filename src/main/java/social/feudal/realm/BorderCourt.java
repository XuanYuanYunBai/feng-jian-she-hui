package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

/** A crossing's prospective toll is the disputed right. Past traffic and private land never change hands. */
final class BorderCourt {
    static final long DAY=86400, WINDOW=5400;
    final RealmStore s;
    final BridgePrivileges privileges;
    BorderCourt(RealmStore s)throws SQLException{
        this.s=s;
        s.update("""
            CREATE TABLE IF NOT EXISTS crossing(id TEXT PRIMARY KEY,name TEXT NOT NULL,owner TEXT REFERENCES nation(id),
            x INTEGER,y INTEGER,z INTEGER,fee INTEGER NOT NULL,UNIQUE(x,y,z))""");
        s.update("""
            CREATE TABLE IF NOT EXISTS feud(id INTEGER PRIMARY KEY,bridge TEXT REFERENCES crossing(id),
            attacker TEXT REFERENCES nation(id),defender TEXT REFERENCES nation(id),reason TEXT NOT NULL,
            start INTEGER NOT NULL,last INTEGER NOT NULL,progress INTEGER NOT NULL DEFAULT 0,
            reversed INTEGER NOT NULL DEFAULT 0,slot INTEGER NOT NULL DEFAULT 0,opened INTEGER NOT NULL DEFAULT 0,state TEXT NOT NULL DEFAULT '预告')""");
        s.update("""
            CREATE TABLE IF NOT EXISTS levy(feud INTEGER REFERENCES feud(id),actor TEXT,nation TEXT REFERENCES nation(id),
            side TEXT NOT NULL,supplied INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(feud,actor))""");
        if(s.number("SELECT count(*) FROM pragma_table_info('levy') WHERE name='supplied'")==0)s.update("ALTER TABLE levy ADD COLUMN supplied INTEGER NOT NULL DEFAULT 0");
        if(s.number("SELECT count(*) FROM pragma_table_info('feud') WHERE name='slot'")==0)s.update("ALTER TABLE feud ADD COLUMN slot INTEGER NOT NULL DEFAULT 0");
        if(s.number("SELECT count(*) FROM pragma_table_info('feud') WHERE name='opened'")==0)s.update("ALTER TABLE feud ADD COLUMN opened INTEGER NOT NULL DEFAULT 0");
        s.update("CREATE TABLE IF NOT EXISTS passage(id INTEGER PRIMARY KEY,bridge TEXT,owner TEXT,payer TEXT,paid INTEGER,at INTEGER)");
        if(s.number("SELECT count(*) FROM pragma_table_info('crossing') WHERE name='currency'")==0)
            s.update("ALTER TABLE crossing ADD COLUMN currency TEXT NOT NULL DEFAULT 'GOLD'");
        if(s.number("SELECT count(*) FROM pragma_table_info('crossing') WHERE name='nx'")==0)
            s.update("ALTER TABLE crossing ADD COLUMN nx INTEGER NOT NULL DEFAULT 1");
        if(s.number("SELECT count(*) FROM pragma_table_info('crossing') WHERE name='nz'")==0)
            s.update("ALTER TABLE crossing ADD COLUMN nz INTEGER NOT NULL DEFAULT 0");
        if(s.number("SELECT count(*) FROM pragma_table_info('passage') WHERE name='currency'")==0)
            s.update("ALTER TABLE passage ADD COLUMN currency TEXT NOT NULL DEFAULT 'GOLD'");
        if(s.number("SELECT count(*) FROM pragma_table_info('passage') WHERE name='bill'")==0)
            s.update("ALTER TABLE passage ADD COLUMN bill TEXT");
        s.update("CREATE UNIQUE INDEX IF NOT EXISTS toll_bill ON passage(bridge,bill) WHERE bill IS NOT NULL");
        privileges = new BridgePrivileges(s);
    }
    void bridge(String actor,String id,String name,String nation,int x,int y,int z,int fee)throws SQLException{
        Politics.id(id);Politics.wording(name,30);s.politics.represents(nation,actor);
        String title=s.owner(x>>4,z>>4);
        require(title!=null && nation.equals(s.politics.nation(title)),"桥亭须在本国辖区内");
        require(fee>=0 && fee<=64,"每次费额须为 0–64 枚");
        require(s.value("SELECT id FROM crossing WHERE id=? OR (x=? AND y=? AND z=?)",id,x,y,z)==null,"桥亭或代号已登记");
        s.update("INSERT INTO crossing(id,name,owner,x,y,z,fee) VALUES(?,?,?,?,?,?,?)",id,name,nation,x,y,z,fee);
    }
    void price(String actor,String bridge,TollCoin coin,int fee)throws SQLException{
        String owner=s.value("SELECT owner FROM crossing WHERE id=?",bridge);require(owner!=null,"未知收费亭");
        s.politics.represents(owner,actor);require(fee>=0 && fee<=64,"每次费额须为 0–64 枚");
        s.update("UPDATE crossing SET currency=?,fee=? WHERE id=?",coin.name(),fee,bridge);
    }
    int declare(String actor,String attacker,String bridge,String reason,long start,long now)throws SQLException{
        return s.atomic(()->{
            s.politics.represents(attacker,actor);Politics.wording(reason,160);
            String defender=s.value("SELECT owner FROM crossing WHERE id=?",bridge);
            require(defender!=null && !defender.equals(attacker),"请选择另一国的桥税权");
            require(start>=now+DAY && start<=now+30*DAY,"请提前 24 小时至 30 天预告");
            require(s.number("SELECT count(*) FROM feud WHERE bridge=? AND state NOT IN ('结案','和解')",bridge)==0,"该桥已有争议");
            if(s.politics.member(Politics.EMPIRE,attacker) && s.politics.member(Politics.EMPIRE,defender)){
                require(s.number("SELECT count(*) FROM motion WHERE org='empire' AND kind='调停' AND subject=? AND terms=? AND state='通过'",bridge,attacker)>0,
                        "帝内须先取得该桥争端的帝国调停议决");
            }
            s.update("INSERT INTO feud(bridge,attacker,defender,reason,start,last) VALUES(?,?,?,?,?,?)",bridge,attacker,defender,reason,start,now);
            int n=s.number("SELECT last_insert_rowid()");
            s.politics.log(null,attacker,"预告桥税争议 #"+n+" · "+reason);
            return n;
        });
    }
    void enlist(int feud,String actor,String country,String side)throws SQLException{
        s.politics.represents(country,actor);require(Set.of("原告","守方").contains(side),"请选择原告或守方");
        String main=s.value("SELECT "+(side.equals("原告")?"attacker":"defender")+" FROM feud WHERE id=? AND state NOT IN ('结案','和解')",feud);
        require(main!=null,"战事已结或不存在");
        if(!country.equals(main))require(s.number("""
                SELECT count(*) FROM association a JOIN member m ON m.org=a.id JOIN member ally ON ally.org=a.id
                WHERE a.kind='防御联盟' AND a.state='有效' AND m.nation=? AND ally.nation=?""",country,main)>0 && side.equals("守方"),
                "该国没有本次防守互助授权");
        require(s.update("INSERT OR IGNORE INTO levy(feud,actor,nation,side) VALUES(?,?,?,?)",feud,actor,country,side)==1,"本角色已代表一方参战");
    }
    void settle(int n,String country,String actor)throws SQLException{
        s.politics.represents(country,actor);
        require(s.update("""
            UPDATE feud SET state='和解' WHERE id=? AND state NOT IN ('和解','结案') AND
            ((reversed=0 AND attacker=?) OR (reversed=1 AND defender=?))""",n,country,country)==1,"仅当前有未决诉求的一方可撤回");
    }
    /** Count at most one observed second per tick. An unloaded or offline period cannot grant any control. */
    boolean observe(int id,long now,boolean available,boolean attack,boolean defend)throws SQLException{
        return s.atomic(()->{
            try(var q=s.sql("SELECT * FROM feud WHERE id=? AND state NOT IN ('结案','和解')",id);var r=q.executeQuery()){
                if(!r.next())return false;
                long start=r.getLong("start"),last=r.getLong("last");int progress=r.getInt("progress");boolean reverse=r.getBoolean("reversed");
                if(now<=last)return false;
                int slot=r.getInt("slot");long windowStart=start+slot*DAY;
                if(now<windowStart)return false;
                if(now>=windowStart+WINDOW){
                    if(!r.getBoolean("opened")){
                        long days=1+Math.floorDiv(now-windowStart-WINDOW,DAY);
                        s.update("UPDATE feud SET start=start+?,last=?,state='顺延待开场' WHERE id=?",days*DAY,now,id);
                    }else if(slot==2)s.update("UPDATE feud SET state='结案',last=? WHERE id=?",now,id);
                    else s.update("UPDATE feud SET slot=slot+1,opened=0,state='待开场',last=? WHERE id=?",now,id);
                    return false;
                }
                if(!available)return false;
                if(!r.getBoolean("opened"))s.update("UPDATE feud SET opened=1 WHERE id=?",id);
                boolean claimant=reverse?defend:attack,holder=reverse?attack:defend;
                if(claimant && !holder)progress++;
                else if(holder && !claimant)progress=Math.max(0,progress-1);
                if(progress>=1200){
                    String winner=r.getString(reverse?"defender":"attacker"),bridge=r.getString("bridge");
                    s.update("UPDATE crossing SET owner=? WHERE id=?",winner,bridge);
                    s.update("UPDATE feud SET last=?,progress=0,reversed=1,state=? WHERE id=?",now,reverse?"结案":"反向诉求",id);
                    s.politics.log(null,winner,"桥税争议 #"+id+" 已兑现，后续车次依新权源收费");
                    return true;
                }else s.update("UPDATE feud SET last=?,progress=?,state=? WHERE id=?",now,progress,reverse?"反向诉求":"争夺中",id);
            }return false;
        });
    }
}
