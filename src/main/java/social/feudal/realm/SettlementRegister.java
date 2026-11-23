package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

final class SettlementRegister {
    record Manor(String id,String name,String owner,int x,int y,int z,Charters.Bounds estate,int residents,int service,boolean cottage){}
    record Claim(int id,String manor,String title,String type,Charters.Bounds area,String state,int season,long until){}
    final RealmStore s;
    SettlementRegister(RealmStore s)throws SQLException{
        this.s=s;
        s.update("""
            CREATE TABLE IF NOT EXISTS manor(id TEXT PRIMARY KEY,name TEXT NOT NULL,owner TEXT NOT NULL,x INTEGER,y INTEGER,z INTEGER,
            west INTEGER,north INTEGER,east INTEGER,south INTEGER,households INTEGER NOT NULL DEFAULT 3,
            service INTEGER NOT NULL DEFAULT 0,settled INTEGER NOT NULL DEFAULT 0)""");
        if(s.number("SELECT count(*) FROM pragma_table_info('manor') WHERE name='cottage'")==0)
            s.update("ALTER TABLE manor ADD COLUMN cottage INTEGER NOT NULL DEFAULT 0");
        if(s.number("SELECT count(*) FROM pragma_table_info('manor') WHERE name='residents'")==0){
            s.atomic(()->{
                try(var q=s.sql("ALTER TABLE manor ADD COLUMN residents INTEGER NOT NULL DEFAULT 0 CHECK(residents>=0)")){q.execute();}
                s.update("ALTER TABLE manor ADD COLUMN housing INTEGER NOT NULL DEFAULT 0");
                s.update("ALTER TABLE manor ADD COLUMN grace_until INTEGER NOT NULL DEFAULT 0");
                s.update("ALTER TABLE manor ADD COLUMN last_season INTEGER NOT NULL DEFAULT 0");
                s.update("ALTER TABLE manor ADD COLUMN ration_credit INTEGER NOT NULL DEFAULT 0");
                s.update("ALTER TABLE manor ADD COLUMN growth_remainder INTEGER NOT NULL DEFAULT 0");
                s.update("ALTER TABLE manor ADD COLUMN shortages INTEGER NOT NULL DEFAULT 0");
                s.update("UPDATE manor SET residents=households*100,housing=households*100,grace_until=?,last_season=?",season()+1,season());
                return null;
            });
        }
        s.update("CREATE TABLE IF NOT EXISTS pantry_snapshot(manor TEXT REFERENCES manor(id),season INTEGER,stock INTEGER,PRIMARY KEY(manor,season))");
        s.update("""
            CREATE TABLE IF NOT EXISTS population_season(manor TEXT REFERENCES manor(id),season INTEGER,
            opening INTEGER,capacity INTEGER,bread INTEGER,shortfall INTEGER,births INTEGER,deaths INTEGER,
            closing INTEGER,PRIMARY KEY(manor,season))""");
        s.update("""
            CREATE TABLE IF NOT EXISTS claim(id INTEGER PRIMARY KEY,manor TEXT REFERENCES manor(id),title TEXT NOT NULL,
            type TEXT NOT NULL,west INTEGER,north INTEGER,east INTEGER,south INTEGER,state TEXT NOT NULL,season INTEGER,until INTEGER)""");
        s.update("""
            CREATE TABLE IF NOT EXISTS land_objection(claim INTEGER REFERENCES claim(id),x INTEGER,z INTEGER,
            actor TEXT,reason TEXT,PRIMARY KEY(claim,x,z,actor))""");
        s.update("CREATE TABLE IF NOT EXISTS partition_claim(claim INTEGER PRIMARY KEY REFERENCES claim(id),person INTEGER NOT NULL)");
        TerritorialControl.schema(s);
        s.update("CREATE TABLE IF NOT EXISTS frontier_seal(title TEXT PRIMARY KEY REFERENCES title(id),issuer TEXT REFERENCES title(id),west INTEGER,north INTEGER,east INTEGER,south INTEGER)");
        s.update("CREATE TABLE IF NOT EXISTS peerage(title TEXT PRIMARY KEY REFERENCES nation(id),state TEXT NOT NULL,reason TEXT NOT NULL)");
        s.update("CREATE TABLE IF NOT EXISTS capital(nation TEXT PRIMARY KEY REFERENCES nation(id),manor TEXT REFERENCES manor(id))");
    }
    Manor manor(String id)throws SQLException{
        try(var q=s.sql("SELECT * FROM manor WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"未知庄园");return new Manor(id,r.getString("name"),r.getString("owner"),r.getInt("x"),r.getInt("y"),r.getInt("z"),
                    new Charters.Bounds(r.getInt("west"),r.getInt("north"),r.getInt("east"),r.getInt("south")),r.getInt("residents"),r.getInt("service"),r.getBoolean("cottage"));
        }
    }
    void estate(String id,String name,String actor,int x,int y,int z,Charters.Bounds b)throws SQLException{
        estate(new Manor(id,name,actor,x,y,z,b,300,0,false));
    }
    boolean manages(Manor manor,String actor)throws SQLException{
        if(manor.owner().equals(actor))return true;
        if(!manor.owner().startsWith("公产·"))return false;
        String title=s.owner(manor.x()>>4,manor.z()>>4);
        return title!=null && s.title(title).seal().equals(actor);
    }
    void available(String actor,Charters.Bounds b)throws SQLException{
        available(actor,b,"");
    }
    void available(String actor,Charters.Bounds b,String existing)throws SQLException{
        require(s.number("SELECT count(*) FROM manor WHERE id<>? AND west<=? AND east>=? AND north<=? AND south>=?",existing,b.east(),b.west(),b.south(),b.north())==0,"与已有庄园重叠");
        for(int cx=b.west()>>4;cx<=b.east()>>4;cx++)for(int cz=b.north()>>4;cz<=b.south()>>4;cz++){
            String owner=s.owner(cx,cz);require(owner==null || s.title(owner).seal().equals(actor),"建庄涉及他领，请先取得土地");
        }
    }
    // Search cadastral records only. Looking up a site must never generate or load terrain.
    enum Ground { DRY, UNSEEN, UNSUITABLE }
    int[] nearestYard(String actor,int x,int z,double west,double north,double east,double south,
                      java.util.function.BiFunction<Integer,Integer,Ground> ground)throws SQLException{
        try(var q=s.sql("""
            WITH RECURSIVE offsets(n) AS (VALUES(-16) UNION ALL SELECT n+1 FROM offsets WHERE n<16),
            sites AS (SELECT ((?+a.n)*16+8) AS x,((?+b.n)*16+8) AS z FROM offsets a CROSS JOIN offsets b)
            SELECT x,z FROM sites p WHERE x-4>=? AND z-4>=? AND x+5<=? AND z+5<=?
            AND NOT EXISTS(SELECT 1 FROM land l JOIN title t ON t.id=l.title
                           WHERE l.x=(p.x>>4) AND l.z=(p.z>>4) AND t.seal<>?)
            AND NOT EXISTS(SELECT 1 FROM manor m WHERE m.west<=p.x+4 AND m.east>=p.x-4 AND m.north<=p.z+4 AND m.south>=p.z-4)
            ORDER BY (x-?)*(x-?)+(z-?)*(z-?),x,z""",x>>4,z>>4,west,north,east,south,actor,x,x,z,z);
            var row=q.executeQuery()){
            int[] unexplored=null;
            while(row.next()){
                int px=row.getInt(1),pz=row.getInt(2);
                var surface=ground.apply(px,pz);
                if(surface==Ground.DRY)return new int[]{px,pz};
                if(surface==Ground.UNSEEN && unexplored==null)unexplored=new int[]{px,pz};
            }
            return unexplored;
        }
    }
    void estate(Manor m)throws SQLException{estate(m,0);}
    void estate(Manor m,int capacity)throws SQLException{
        String id=m.id(), actor=m.owner();
        var b=m.estate();
        Politics.id(id);Politics.wording(m.name(),30);
        require(b.contains(m.x(),m.z()) && b.east()-b.west()>=7 && b.south()-b.north()>=7 && b.east()-b.west()<96 && b.south()-b.north()<96,"庄宅须在 8–96 格长宽的围界内");
        s.atomic(()->{
            require(s.value("SELECT id FROM manor WHERE id=? UNION SELECT id FROM title WHERE id=?",id,id)==null,"庄园代号已用");
            available(actor,b);
            int residents=100;
            if(s.number("SELECT count(*) FROM manor WHERE owner=?",actor)>0){
                require(!m.cottage(),"安家只适用于首庄");
                String source=s.value("SELECT id FROM manor WHERE owner=? AND residents>=400 AND last_season=? ORDER BY residents DESC,id LIMIT 1",actor,season());
                if(source!=null && s.value("SELECT name FROM sqlite_schema WHERE name='kin'")!=null)
                    require(s.number("SELECT residents-(SELECT count(*) FROM kin WHERE home=? AND death IS NULL) FROM manor WHERE id=?",source,source)>=300,"分庄迁出人口不能重复计算具名家人");
                require(source!=null,"分庄需要从已结季账的聚落实迁 300 人，原庄至少留 100 人");
                s.update("UPDATE manor SET residents=residents-300 WHERE id=?",source);
                residents=300;
            }
            else if(s.value("SELECT name FROM sqlite_schema WHERE name='family'")!=null &&
                    s.number("SELECT count(*) FROM family WHERE account=? AND ended IS NOT NULL",actor)>0){
                String source=s.value("""
                    SELECT m.id FROM manor m JOIN family_guest g ON g.manor=m.id AND g.host=m.owner
                    JOIN family f ON f.id=g.guest WHERE f.account=? AND f.ended IS NULL AND m.residents>=200
                    AND m.residents-(SELECT count(*) FROM kin k WHERE k.home=m.id AND k.death IS NULL)>=100
                    AND g.settlers>=100 ORDER BY m.id LIMIT 1""",actor);
                require(source!=null,"重立家门须由有余户的寄居庄拨出一百居民，请主人办理迁户许可");
                s.update("UPDATE manor SET residents=residents-100 WHERE id=?",source);
                s.update("UPDATE family_guest SET settlers=0 WHERE manor=? AND guest=(SELECT id FROM family WHERE account=? AND ended IS NULL)",source,actor);
            }
            s.update("INSERT INTO manor(id,name,owner,x,y,z,west,north,east,south,residents,cottage,last_season,housing) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,m.name(),actor,m.x(),m.y(),m.z(),b.west(),b.north(),b.east(),b.south(),residents,m.cottage()?1:0,season(),capacity);return null;
        });
    }
    // Residence follows the hall's public jurisdiction. Revenue grants and imperial membership have no vote here.
    record Census(String id,String name,int population,int direct,int capacity,int villages){}
    List<Census> census()throws SQLException{
        var rows=new ArrayList<Census>();
        try(var q=s.sql("""
                WITH districts(id,name) AS (SELECT n.id,t.name FROM nation n JOIN title t USING(id)
                    UNION ALL SELECT 'crown',name FROM title WHERE id='crown' UNION ALL SELECT '@','未建领聚落'),
                homes AS (SELECT m.*,j.title,j.district FROM manor m JOIN dwelling_jurisdiction j USING(id))
                SELECT d.id,d.name,coalesce(sum(h.residents),0),
                    coalesce(sum(CASE WHEN h.title=d.id THEN h.residents ELSE 0 END),0),
                    coalesce(sum(h.housing),0),count(h.id)
                FROM districts d LEFT JOIN homes h ON h.district=d.id GROUP BY d.id,d.name ORDER BY d.name""");
            var r=q.executeQuery()){
            while(r.next())rows.add(new Census(r.getString(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getInt(6)));
        }
        return rows;
    }
    int season()throws SQLException{return s.number("SELECT v FROM setting WHERE k='season'");}
    Claim claim(int id)throws SQLException{
        try(var q=s.sql("SELECT * FROM claim WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"没有这份公示");return new Claim(id,r.getString("manor"),r.getString("title"),r.getString("type"),
                    new Charters.Bounds(r.getInt("west"),r.getInt("north"),r.getInt("east"),r.getInt("south")),r.getString("state"),r.getInt("season"),r.getLong("until"));
        }
    }
    void opening(String subject,Charters.Bounds b)throws SQLException{
        var country=s.title(subject);
        require(!country.charter().equals("流官"),"流官只能请君主增拨");
        if(country.parent()==null || subject.equals("crown"))return;
        require(s.number("SELECT count(*) FROM frontier_seal WHERE title=? AND issuer=? AND west<=? AND north<=? AND east>=? AND south>=?",
                subject,country.parent(),b.west(),b.north(),b.east(),b.south())==1,"此方向须获封君开拓特许");
    }
    void permit(String actor,String title,Charters.Bounds b)throws SQLException{
        b.area();var t=s.title(title);require(t.parent()!=null && !Set.of("流官","收益封契").contains(t.charter()),"该头衔不能取得开拓特许");
        new Charters(s).acts(t.parent(),actor);
        s.update("INSERT OR REPLACE INTO frontier_seal VALUES(?,?,?,?,?,?)",title,t.parent(),b.west(),b.north(),b.east(),b.south());
    }
    private void adjacent(String title,Charters.Bounds b)throws SQLException{
        require(s.number("""
            SELECT count(*) FROM land WHERE title=? AND ((x IN (?,?) AND z BETWEEN ? AND ?) OR
            (z IN (?,?) AND x BETWEEN ? AND ?))""",title,b.west()-1,b.east()+1,b.north(),b.south(),
                b.north()-1,b.south()+1,b.west(),b.east())>0,"拓地须与本领共边");
    }
    int propose(String actor,String id,String title,String type,Charters.Bounds b,long now,long notice)throws SQLException{
        return s.atomic(()->{
            var m=manor(id);require(m.owner().equals(actor),"须由庄园主办理");
            boolean founding=!type.equals("开拓");
            require(Set.of("入帝","独立","开拓").contains(type),"请选择入帝、独立或开拓");
            require(b.area()<=(founding?36:16),"选区超过本次公示面积上限");
            require(b.contains(m.x()>>4,m.z()>>4),"须在选区内建立庄园驻地");
            if(founding){
                require(title.equals(id) && s.value("SELECT id FROM title WHERE id=?",title)==null,"该庄园已建领或代号已占用");
                require(m.residents()>=200 && m.service()>0,"建领需要 200 人和连续一季公共服务");
                require(type.equals("入帝") || s.politics.choiceOpen(),"首位玩家皇帝即位前，新领自动加入帝国");
            }else{
                new Charters(s).acts(title,actor);opening(title,b);adjacent(title,b);
                require(m.residents()>=100*Math.ceilDiv(b.area(),4),"每四个区块至少须 100 人派驻");
            }
            require(s.number("SELECT count(*) FROM claim WHERE manor=? AND state='公示中'",id)==0,"本庄已有在审公示");
            require(s.number("SELECT count(*) FROM land WHERE x BETWEEN ? AND ? AND z BETWEEN ? AND ?",b.west(),b.east(),b.north(),b.south())==0,"选区已有公共管辖");
            s.update("INSERT INTO claim(manor,title,type,west,north,east,south,state,season,until) VALUES(?,?,?,?,?,?,?,'公示中',?,?)",
                    id,title,type,b.west(),b.north(),b.east(),b.south(),season(),Math.addExact(now,notice));
            int n=s.number("SELECT last_insert_rowid()");
            return n;
        });
    }
    void object(int n,String actor,int x,int z,String reason)throws SQLException{
        var c=claim(n);Politics.wording(reason,160);
        require(c.state().equals("公示中") && c.area().contains(x,z),"须在公示区内提出异议");
        s.update("INSERT OR REPLACE INTO land_objection VALUES(?,?,?,?,?)",n,x,z,actor,reason);
    }
    void resolve(int n,String actor,long now)throws SQLException{
        s.atomic(()->{
            var c=claim(n);var m=manor(c.manor());require(actor.equals(m.owner()) && c.state().equals("公示中"),"此案已结或并非申请人");
            require(now>=c.until() && season()>c.season(),"至少经过一季及公告答辩期限");
            boolean frontier=c.type().equals("开拓");
            if(frontier){
                new Charters(s).acts(c.title(),actor);opening(c.title(),c.area());adjacent(c.title(),c.area());
                require(m.residents()>=100*Math.ceilDiv(c.area().area(),4) && m.service()>0,"开拓地需维持住户和一季供养");
            }else require(m.residents()>=200 && m.service()>0,"建领资格已变化");
            var cells=new ArrayList<int[]>();var b=c.area();
            for(int x=b.west();x<=b.east();x++)for(int z=b.north();z<=b.south();z++){
                if(s.owner(x,z)!=null || s.number("SELECT count(*) FROM land_objection WHERE claim=? AND x=? AND z=?",n,x,z)>0)continue;
                if(s.number("""
                    SELECT count(*) FROM claim WHERE id<>? AND state='公示中' AND west<=? AND east>=? AND north<=? AND south>=?""",n,x,x,z,z)>0)continue;
                cells.add(new int[]{x,z});
            }
            int cx=m.x()>>4,cz=m.z()>>4;
            require(cells.stream().anyMatch(a->a[0]==cx && a[1]==cz),"驻地有异议，请先解决或撤案");
            // Admit only the connected component containing the actual seat.
            var component=new HashSet<Long>();component.add(key(cx,cz));
            for(int i=0;i<cells.size();i++)for(var a:cells){
                int x=a[0],z=a[1];
                if(component.contains(key(x-1,z)) || component.contains(key(x+1,z)) || component.contains(key(x,z-1)) || component.contains(key(x,z+1)))component.add(key(x,z));
            }
            if(frontier){
                boolean touches=false;
                for(var a:cells)if(component.contains(key(a[0],a[1])))
                    for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                        if(c.title().equals(s.owner(a[0]+d[0],a[1]+d[1])))touches=true;
                require(touches,"无异议部分须与旧疆界相接");
            }else{
                boolean inside=c.type().equals("入帝");
                String holder=actor;
                int nominated=s.number("SELECT person FROM partition_claim WHERE claim=?",n);
                if(nominated!=0){
                    holder=s.value("""
                        SELECT CASE WHEN f.rep=k.id THEN f.account ELSE '族人·'||k.id END FROM kin k JOIN family f ON f.id=k.family
                        WHERE k.id=? AND k.death IS NULL AND f.ended IS NULL""",nominated);
                    require(holder!=null,"预定承领人已故，请撤回后重新公议");
                }
                s.titleRow(c.title(),m.name(),inside?"地主":"伯爵",null,"建领公议",holder,cx,cz);
                s.politics.newNation(c.title(),inside,false);
                s.update("INSERT INTO capital VALUES(?,?)",c.title(),m.id());
            }
            for(var a:cells)if(component.contains(key(a[0],a[1])))s.update("INSERT INTO land VALUES(?,?,?)",a[0],a[1],c.title());
            s.update("UPDATE claim SET state=? WHERE id=?",component.size()==b.area()?"裁定":"无争议部分裁定",n);
            s.politics.log(c.type().equals("独立")?null:Politics.EMPIRE,c.title(),"公示 #"+n+" 裁定 "+component.size()+" 区块");return null;
        });
    }
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    void moveCapital(String nation,String manor,String actor)throws SQLException{
        s.atomic(()->{
            s.politics.represents(nation,actor);var m=manor(manor);
            require(m.owner().equals(actor),"新治所须获庄园主同意");
            int x=m.x()>>4,z=m.z()>>4;
            require(nation.equals(s.owner(x,z)),"新首都须在本国直辖领");
            require(!manor.equals(s.value("SELECT manor FROM capital WHERE nation=?",nation)),"已经是首都");
            s.update("UPDATE title SET x=?,z=? WHERE id=?",x,z,nation);
            s.update("INSERT OR REPLACE INTO capital VALUES(?,?)",nation,manor);
            s.politics.log(null,nation,"迁都至 "+m.name()+"。历史税单沿用期初税距，旧仓货物待实运。");
            return null;
        });
    }
    void peer(String actor,String country,String decision,String reason)throws SQLException{
        s.atomic(()->{
            require(s.politics.member(Politics.EMPIRE,country) && s.title(country).rank().equals("地主"),"仅帝国内无爵领邦可请爵");
            Politics.wording(reason,160);
            if(decision.equals("申请")){
                s.politics.represents(country,actor);
                require(!"待审".equals(s.value("SELECT state FROM peerage WHERE title=?",country)),"已提交");
                s.update("INSERT OR REPLACE INTO peerage VALUES(?,'待审',?)",country,reason);
            }else{
                var emperor=new Charters(s).acts("crown",actor);
                require(!emperor.seal().equals(s.title(country).seal()),"授爵须由另一位签署人核准");
                require(Set.of("批准","驳回").contains(decision) && s.update("UPDATE peerage SET state=?,reason=? WHERE title=? AND state='待审'",decision,reason,country)==1,"没有待审请爵书");
                if(decision.equals("批准"))s.update("UPDATE title SET rank='伯爵' WHERE id=?",country);
            }
            s.politics.log(Politics.EMPIRE,country,"请爵"+decision+" · "+reason);return null;
        });
    }
}
