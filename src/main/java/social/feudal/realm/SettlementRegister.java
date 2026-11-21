package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

final class SettlementRegister {
    record Manor(String id,String name,String owner,int x,int y,int z,Charters.Bounds estate,int households,int service){}
    record Claim(int id,String manor,String title,String type,Charters.Bounds area,String state,int season,long until){}
    final RealmStore s;
    SettlementRegister(RealmStore s)throws SQLException{
        this.s=s;
        s.update("""
            CREATE TABLE IF NOT EXISTS manor(id TEXT PRIMARY KEY,name TEXT NOT NULL,owner TEXT NOT NULL,x INTEGER,y INTEGER,z INTEGER,
            west INTEGER,north INTEGER,east INTEGER,south INTEGER,households INTEGER NOT NULL DEFAULT 3,
            service INTEGER NOT NULL DEFAULT 0,settled INTEGER NOT NULL DEFAULT 0)""");
        s.update("""
            CREATE TABLE IF NOT EXISTS claim(id INTEGER PRIMARY KEY,manor TEXT REFERENCES manor(id),title TEXT NOT NULL,
            type TEXT NOT NULL,west INTEGER,north INTEGER,east INTEGER,south INTEGER,state TEXT NOT NULL,season INTEGER,until INTEGER)""");
        s.update("""
            CREATE TABLE IF NOT EXISTS land_objection(claim INTEGER REFERENCES claim(id),x INTEGER,z INTEGER,
            actor TEXT,reason TEXT,PRIMARY KEY(claim,x,z,actor))""");
        s.update("CREATE TABLE IF NOT EXISTS frontier_seal(title TEXT PRIMARY KEY REFERENCES title(id),issuer TEXT REFERENCES title(id),west INTEGER,north INTEGER,east INTEGER,south INTEGER)");
        s.update("CREATE TABLE IF NOT EXISTS peerage(title TEXT PRIMARY KEY REFERENCES nation(id),state TEXT NOT NULL,reason TEXT NOT NULL)");
        s.update("CREATE TABLE IF NOT EXISTS capital(nation TEXT PRIMARY KEY REFERENCES nation(id),manor TEXT REFERENCES manor(id))");
    }
    Manor manor(String id)throws SQLException{
        try(var q=s.sql("SELECT * FROM manor WHERE id=?",id);var r=q.executeQuery()){
            require(r.next(),"未知庄园");return new Manor(id,r.getString("name"),r.getString("owner"),r.getInt("x"),r.getInt("y"),r.getInt("z"),
                    new Charters.Bounds(r.getInt("west"),r.getInt("north"),r.getInt("east"),r.getInt("south")),r.getInt("households"),r.getInt("service"));
        }
    }
    void estate(String id,String name,String actor,int x,int y,int z,Charters.Bounds b)throws SQLException{
        Politics.id(id);Politics.wording(name,30);
        require(b.contains(x,z) && b.east()-b.west()>=7 && b.south()-b.north()>=7 && b.east()-b.west()<96 && b.south()-b.north()<96,"庄宅须在 8–96 格长宽的围界内");
        s.atomic(()->{
            require(s.value("SELECT id FROM manor WHERE id=? UNION SELECT id FROM title WHERE id=?",id,id)==null,"庄园代号已用");
            require(s.number("SELECT count(*) FROM manor WHERE west<=? AND east>=? AND north<=? AND south>=?",b.east(),b.west(),b.south(),b.north())==0,"与已有庄园重叠");
            for(int cx=b.west()>>4;cx<=b.east()>>4;cx++)for(int cz=b.north()>>4;cz<=b.south()>>4;cz++){
                String owner=s.owner(cx,cz);require(owner==null || s.title(owner).seal().equals(actor),"建庄涉及他领，请先取得土地");
            }
            if(s.number("SELECT count(*) FROM manor WHERE owner=?",actor)>0){
                String source=s.value("SELECT id FROM manor WHERE owner=? AND households>=4 ORDER BY households DESC,id LIMIT 1",actor);
                require(source!=null,"另立庄园需要从已有庄园派出三户，原庄至少留一户");
                s.update("UPDATE manor SET households=households-3 WHERE id=?",source);
            }
            s.update("INSERT INTO manor(id,name,owner,x,y,z,west,north,east,south) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id,name,actor,x,y,z,b.west(),b.north(),b.east(),b.south());return null;
        });
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
                require(m.households()>=6 && m.service()>0,"建领需要六户和连续一季公共服务");
                require(type.equals("入帝") || s.politics.choiceOpen(),"首位玩家皇帝即位前，新领自动加入帝国");
            }else{
                new Charters(s).acts(title,actor);opening(title,b);adjacent(title,b);
                require(m.households()>=Math.ceilDiv(b.area(),4),"每四个区块至少须一户派驻");
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
                require(m.households()>=Math.ceilDiv(c.area().area(),4) && m.service()>0,"开拓地需维持住户和一季供养");
            }else require(m.households()>=6 && m.service()>0,"建领资格已变化");
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
                s.titleRow(c.title(),m.name(),inside?"地主":"伯爵",null,"建领公议",actor,cx,cz);
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
