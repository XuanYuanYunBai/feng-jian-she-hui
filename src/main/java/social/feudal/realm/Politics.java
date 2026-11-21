package social.feudal.realm;

import java.sql.SQLException;
import java.util.*;
import static social.feudal.realm.RealmStore.require;

/** Nations own memberships. A title's seal authorizes a signature but never owns the membership. */
final class Politics {
    static final String EMPIRE="empire";
    final RealmStore s;
    Politics(RealmStore s) throws SQLException {
        this.s=s;
        s.atomic(()->{
            s.update("CREATE TABLE IF NOT EXISTS nation(id TEXT PRIMARY KEY REFERENCES title(id))");
            s.update("CREATE TABLE IF NOT EXISTS affiliation(title TEXT PRIMARY KEY REFERENCES title(id), nation TEXT REFERENCES nation(id))");
            s.update("""
                CREATE TABLE IF NOT EXISTS association(id TEXT PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,
                charter TEXT NOT NULL,state TEXT NOT NULL DEFAULT '有效',deadline INTEGER)""");
            s.update("""
                CREATE TABLE IF NOT EXISTS member(org TEXT REFERENCES association(id),nation TEXT REFERENCES nation(id),
                seat INTEGER NOT NULL,elector INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(org,nation))""");
            s.update("""
                CREATE TABLE IF NOT EXISTS treaty(id TEXT PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,
                terms TEXT NOT NULL,state TEXT NOT NULL DEFAULT '待批准')""");
            s.update("""
                CREATE TABLE IF NOT EXISTS signatory(treaty TEXT REFERENCES treaty(id),nation TEXT REFERENCES nation(id),
                signer TEXT,PRIMARY KEY(treaty,nation))""");
            s.update("""
                CREATE TABLE IF NOT EXISTS organisation_signature(treaty TEXT REFERENCES treaty(id),org TEXT REFERENCES association(id),
                delegate TEXT REFERENCES nation(id),signer TEXT,PRIMARY KEY(treaty,org))""");
            s.update("""
                CREATE TABLE IF NOT EXISTS motion(id INTEGER PRIMARY KEY,org TEXT REFERENCES association(id),kind TEXT NOT NULL,
                subject TEXT NOT NULL,terms TEXT NOT NULL,state TEXT NOT NULL DEFAULT '表决中')""");
            s.update("""
                CREATE TABLE IF NOT EXISTS ballot(motion INTEGER REFERENCES motion(id),nation TEXT REFERENCES nation(id),
                vote INTEGER,signer TEXT,PRIMARY KEY(motion,nation))""");
            s.update("""
                CREATE TABLE IF NOT EXISTS diplomatic_record(id INTEGER PRIMARY KEY,org TEXT,nation TEXT,
                event TEXT NOT NULL,period INTEGER NOT NULL)""");
            if(s.ready() && s.value("SELECT id FROM association WHERE id=?",EMPIRE)==null)bootstrap(true);
            return null;
        });
    }
    int period()throws SQLException{return Math.max(1,s.number("SELECT v FROM setting WHERE k='period'"));}
    void log(String org,String nation,String event)throws SQLException{
        s.update("INSERT INTO diplomatic_record(org,nation,event,period) VALUES(?,?,?,?)",org,nation,event,period());
    }
    void bootstrap(boolean legacy)throws SQLException{
        s.update("INSERT INTO association(id,name,kind,charter) VALUES(?,?,?,?)",EMPIRE,"河湾帝国","帝国",
                "成员对外自主，帝内争端先交帝国议会调停。选侯选举皇帝，共同事务依议决执行。");
        var crown=s.title("crown");
        s.update("UPDATE title SET name=? WHERE id='crown'",legacy?"皇室旧领（待清册）":"帝位直属领产");
        s.update("INSERT INTO affiliation VALUES('crown',NULL)");
        for(var t:s.titles())if(!t.id().equals("crown"))attach(t);
        if(Succession.isPlayer(crown.lawful()) || legacy && s.value("SELECT name FROM sqlite_master WHERE name='instrument'")!=null
                && s.number("SELECT count(*) FROM instrument WHERE title='crown' AND event='君主交位'")>0)firstReign();
        log(EMPIRE,null,legacy?"移录旧册，旧仓及在途货仍由原登记人保管":"开设帝国议席与领产档案");
    }
    void firstReign()throws SQLException{
        s.update("INSERT OR IGNORE INTO setting VALUES('first_player_emperor','1')");
    }
    boolean choiceOpen()throws SQLException{return s.number("SELECT v FROM setting WHERE k='first_player_emperor'")==1;}
    String nation(String title)throws SQLException{return s.value("SELECT nation FROM affiliation WHERE title=?",title);}
    boolean member(String org,String nation)throws SQLException{
        return s.number("SELECT count(*) FROM member WHERE org=? AND nation=?",org,nation)>0;
    }
    boolean imperial(String title)throws SQLException{
        require(s.value("SELECT title FROM affiliation WHERE title=?",title)!=null,"未登记的领地");
        String country=nation(title);return country==null || member(EMPIRE,country);
    }
    void newNation(String id,boolean imperial,boolean elector)throws SQLException{
        s.update("INSERT INTO nation VALUES(?)",id);
        s.update("INSERT INTO affiliation VALUES(?,?)",id,id);
        if(imperial)s.update("INSERT INTO member VALUES(?,?,?,?)",EMPIRE,id,elector?1:0,elector?1:0);
    }
    void attach(RealmStore.Title t)throws SQLException{
        boolean polity=t.parent()==null || "crown".equals(t.parent()) &&
                !Set.of("流官","收益封契").contains(t.charter());
        if(polity)newNation(t.id(),t.parent()!=null,Set.of("march","abbey").contains(t.id()));
        else s.update("INSERT INTO affiliation VALUES(?,?)",t.id(),nation(t.parent()));
    }
    void represents(String country,String actor)throws SQLException{
        require(s.value("SELECT id FROM nation WHERE id=?",country)!=null,"请以具名国家身份办理");
        new Charters(s).acts(country,actor);
    }
    static void id(String id){require(id.matches("[a-z][a-z0-9_-]{0,23}"),"代号须为 1–24 位小写字母、数字、短横或下划线");}
    static void wording(String text,int max){require(!text.isBlank() && text.length()<=max,"名称或条款为空或过长");}
    void draft(String id,String name,String kind,String terms,List<String> countries,String proposer,String actor)throws SQLException{
        s.atomic(()->{
            id(id);wording(name,30);wording(terms,200);
            require(Set.of("条约","河运同盟","防御联盟").contains(kind),"请选择条约、河运同盟或防御联盟");
            require(s.value("SELECT id FROM association WHERE id=? UNION SELECT id FROM treaty WHERE id=?",id,id)==null,"外交代号已存在");
            require(countries.size()>=2 && countries.size()<=24 && new HashSet<>(countries).size()==countries.size()
                    && countries.contains(proposer),"须列出 2–24 个不同国家及本国");
            represents(proposer,actor);
            s.update("INSERT INTO treaty(id,name,kind,terms) VALUES(?,?,?,?)",id,name,kind,terms);
            for(String country:countries){
                if(country.startsWith("@")){
                    require(kind.equals("条约"),"首发常设组织由国家创设，国际组织可作为条约缔约方");
                    String org=country.substring(1);active(org);
                    s.update("INSERT INTO organisation_signature(treaty,org) VALUES(?,?)",id,org);continue;
                }
                require(s.value("SELECT id FROM nation WHERE id=?",country)!=null,"没有这个国家 "+country);
                s.update("INSERT INTO signatory VALUES(?,?,?)",id,country,country.equals(proposer)?actor:null);
            }
            log(null,proposer,"提出 "+name+" · "+terms);return null;
        });
    }
    void sign(String id,String country,String actor)throws SQLException{
        s.atomic(()->{
            represents(country,actor);
            require(s.update("""
                UPDATE signatory SET signer=? WHERE treaty=? AND nation=? AND signer IS NULL
                AND EXISTS(SELECT 1 FROM treaty WHERE id=? AND state='待批准')""",actor,id,country,id)==1,"该国没有待签文书");
            require(s.number("SELECT count(*) FROM signatory WHERE treaty=? AND signer=? AND nation<>?",id,actor,country)==0 && s.number("SELECT count(*) FROM organisation_signature WHERE treaty=? AND signer=?",id,actor)==0,"不能代表双方签署同一文书");
            finishTreaty(id,country);
            return null;
        });
    }
    private void finishTreaty(String id,String party)throws SQLException{
        if(unsigned(id)>0){log(null,party,"批准 "+id);return;}
        s.update("UPDATE treaty SET state='生效' WHERE id=?",id);
        if(!"条约".equals(s.value("SELECT kind FROM treaty WHERE id=?",id))){
            s.update("INSERT INTO association(id,name,kind,charter) SELECT id,name,kind,terms FROM treaty WHERE id=?",id);
            s.update("INSERT INTO member SELECT treaty,nation,1,0 FROM signatory WHERE treaty=?",id);
        }
        log(id,party,"缔约各方完成批准");
    }
    int unsigned(String id)throws SQLException{
        return s.number("SELECT count(*) FROM signatory WHERE treaty=? AND signer IS NULL",id)
                +s.number("SELECT count(*) FROM organisation_signature WHERE treaty=? AND signer IS NULL",id);
    }
    void signOrganisation(String id,String org,String actor)throws SQLException{
        s.atomic(()->{
            active(org);
            String delegate=s.value("SELECT delegate FROM organisation_signature WHERE treaty=? AND org=? AND signer IS NULL",id,org);
            require(delegate!=null && member(org,delegate),"此约尚无有效组织授权");
            if(org.equals(EMPIRE))new Charters(s).acts("crown",actor);
            else represents(delegate,actor);
            require(s.number("SELECT count(*) FROM signatory WHERE treaty=? AND signer=?",id,actor)==0 &&
                    s.number("SELECT count(*) FROM organisation_signature WHERE treaty=? AND signer=?",id,actor)==0,"不能代表多方签署同一文书");
            require(s.update("UPDATE organisation_signature SET signer=? WHERE treaty=? AND org=? AND signer IS NULL",actor,id,org)==1,"组织已经签署");
            log(org,null,"依议会授权签署文书 "+id);finishTreaty(id,null);return null;
        });
    }
    void active(String org)throws SQLException{
        String state=s.value("SELECT state FROM association WHERE id=?",org);
        require("有效".equals(state)||"清算中".equals(state),"组织已归档或不存在");
    }
    int propose(String org,String country,String actor,String kind,String subject,String terms)throws SQLException{
        return s.atomic(()->{
            active(org);represents(country,actor);wording(terms,200);
            require(Set.of("接纳","议决","修章","选帝","退帝","调停","授席","选侯特许","外交授权").contains(kind),"未知议案种类");
            if(kind.equals("接纳")){
                require(country.equals(subject) && !member(org,country),"申请国须自行申请加入");
            }else require(member(org,country),"此国没有提案资格");
            if(kind.equals("外交授权")){
                require(member(org,terms),"受托国须是组织成员");
                require(s.number("SELECT count(*) FROM organisation_signature WHERE treaty=? AND org=? AND signer IS NULL",subject,org)==1,"该组织未列为待签方");
            }
            if(Set.of("授席","选侯特许").contains(kind)){
                require(org.equals(EMPIRE) && member(org,subject),"须提名一个帝国成员国");
                String privilege=kind.equals("授席")?"seat":"elector";
                require(s.number("SELECT "+privilege+" FROM member WHERE org=? AND nation=?",org,subject)==0,"已享有这项资格");
            }
            if(kind.equals("调停"))require(org.equals(EMPIRE) && country.equals(terms) && s.value("SELECT id FROM crossing WHERE id=?",subject)!=null,"请以本国为当事人向帝国提交具名桥亭争端");
            if(kind.equals("退帝"))require(org.equals(EMPIRE) && country.equals(subject),"仅本国可提请退出帝国");
            if(kind.equals("选帝")){
                require(!Succession.isPlayer(s.title("crown").lawful()),"帝位仍有在任皇帝");
                require(org.equals(EMPIRE) && member(org,subject) && Succession.isPlayer(s.title(subject).lawful()),"请推举帝国成员的在任玩家君主");
                require(s.number("SELECT elector FROM member WHERE org=? AND nation=?",org,country)==1,"仅选侯可提名");
                require(s.number("SELECT count(*) FROM motion WHERE kind='选帝' AND state='表决中'")==0,"已有选帝议案");
            }
            s.update("INSERT INTO motion(org,kind,subject,terms) VALUES(?,?,?,?)",org,kind,subject,terms);
            int n=s.number("SELECT last_insert_rowid()");
            s.update("""
                INSERT INTO ballot(motion,nation) SELECT ?,nation FROM member
                WHERE org=? AND seat=1 AND (?<>'选帝' OR elector=1)""",n,org,kind);
            require(s.number("SELECT count(*) FROM ballot WHERE motion=?",n)>0,"没有有效表决席位");
            log(org,country,"立案 #"+n+" "+kind+" · "+terms);return n;
        });
    }
    int requestElection(String country,String actor)throws SQLException{
        represents(country,actor);
        var current=s.title("crown");
        require(!Succession.isPlayer(current.lawful()),"在任皇帝须先依法交位");
        require(member(EMPIRE,country),"请以帝国成员国身份呈递竞选书");
        // Acting electors may sponsor a member under the founding charter. Human electors retain their vote.
        String sponsor=s.value("""
            SELECT m.nation FROM member m JOIN title t ON t.id=m.nation
            WHERE m.org=? AND m.elector=1 AND lawful=seal AND seal<>? AND
            (length(seal)<>36 OR seal NOT LIKE '%-%-%-%-%') ORDER BY m.nation LIMIT 1""",EMPIRE,Succession.VACANT);
        if(sponsor==null)return propose(EMPIRE,country,actor,"选帝",country,"选举 "+s.title(country).name()+" 的在任君主");
        int n=propose(EMPIRE,sponsor,s.title(sponsor).seal(),"选帝",country,"依署理章程推举 "+s.title(country).name()+" 的在任君主");
        var electors=new ArrayList<String>();
        try(var q=s.sql("SELECT nation FROM ballot WHERE motion=? ORDER BY nation",n);var r=q.executeQuery()){
            while(r.next())electors.add(r.getString(1));
        }
        for(String seat:electors){
            var t=s.title(seat);
            if(!"表决中".equals(s.value("SELECT state FROM motion WHERE id=?",n)))break;
            if(t.lawful().equals(t.seal()) && !Succession.isPlayer(t.seal()) && !t.seal().equals(Succession.VACANT))vote(n,seat,t.seal(),true);
        }
        return n;
    }
    void vote(int motion,String country,String actor,boolean assent)throws SQLException{
        s.atomic(()->{
            represents(country,actor);
            String org=s.value("SELECT org FROM motion WHERE id=? AND state='表决中'",motion);
            require(org!=null && member(org,country),"议案已结或本国不在组织内");
            require(s.update("UPDATE ballot SET vote=?,signer=? WHERE motion=? AND nation=? AND vote IS NULL",
                    assent?1:0,actor,motion,country)==1,"本席已投或无表决权");
            String kind=s.value("SELECT kind FROM motion WHERE id=?",motion);
            String sub=s.value("SELECT subject FROM motion WHERE id=?",motion);
            int total=s.number("SELECT count(*) FROM ballot WHERE motion=?",motion);
            int yes=s.number("SELECT count(*) FROM ballot WHERE motion=? AND vote=1",motion);
            int no=s.number("SELECT count(*) FROM ballot WHERE motion=? AND vote=0",motion);
            int needed=Set.of("修章","选侯特许").contains(kind)?Math.ceilDiv(total*2,3):total/2+1;
            if(yes>=needed){
                if(kind.equals("接纳")){
                    s.update("INSERT OR IGNORE INTO member VALUES(?,?,?,0)",org,sub,org.equals(EMPIRE)?0:1);
                    s.update("UPDATE association SET state='有效',deadline=NULL WHERE id=? AND (SELECT count(*) FROM member WHERE org=?)>=2",org,org);
                }
                if(kind.equals("外交授权"))s.update("""
                    UPDATE organisation_signature SET delegate=(SELECT terms FROM motion WHERE id=?)
                    WHERE treaty=? AND org=? AND signer IS NULL""",motion,sub,org);
                if(kind.equals("授席"))s.update("UPDATE member SET seat=1 WHERE org=? AND nation=?",org,sub);
                if(kind.equals("选侯特许"))s.update("UPDATE member SET seat=1,elector=1 WHERE org=? AND nation=?",org,sub);
                if(kind.equals("退帝")){
                    s.update("DELETE FROM member WHERE org=? AND nation=?",EMPIRE,sub);
                    s.update("UPDATE motion SET state='席位变动，须重提' WHERE org=? AND id<>? AND state='表决中'",EMPIRE,motion);
                    log(EMPIRE,sub,"依议决退出帝国，其他封契与债务独立履行");
                }
                if(kind.equals("修章"))s.update("UPDATE association SET charter=(SELECT terms FROM motion WHERE id=?) WHERE id=?",motion,org);
                if(kind.equals("选帝")){
                    var elected=s.title(sub);
                    require(member(EMPIRE,sub) && elected.lawful().equals(elected.seal()) && Succession.isPlayer(elected.lawful()),"候选资格已失效");
                    s.update("UPDATE title SET lawful=?,seal=? WHERE id='crown'",elected.lawful(),elected.seal());
                    s.update("UPDATE office SET heir=NULL,hereditary=0,petition=0,start=? WHERE id='crown'",period());
                    firstReign();
                }
                closeMotion(motion,org,"通过");
            }else if(total-no<needed)closeMotion(motion,org,"否决");
            return null;
        });
    }
    private void closeMotion(int n,String org,String result)throws SQLException{
        s.update("UPDATE motion SET state=? WHERE id=?",result,n);
        log(org,null,"议案 #"+n+" "+result);
    }
    void leave(String org,String country,String actor)throws SQLException{
        s.atomic(()->{
            require(!org.equals(EMPIRE),"退出帝国须取得独立和约或帝国裁定");
            represents(country,actor);removeMember(org,country);
            if(s.number("SELECT count(*) FROM member WHERE org=?",org)<2)
                s.update("UPDATE association SET state='清算中',deadline=COALESCE(deadline,?) WHERE id=?",s.number("SELECT v FROM setting WHERE k='season'")+1,org);
            return null;
        });
    }
    void removeMember(String org,String country)throws SQLException{
        require(s.update("DELETE FROM member WHERE org=? AND nation=?",org,country)==1,"该国不是成员");
        s.update("UPDATE motion SET state='席位变动，须重提' WHERE org=? AND state='表决中'",org);
        log(org,country,"退出组织");
    }
    void expiry(int period)throws SQLException{
        s.update("""
            UPDATE motion SET state='组织归档' WHERE state='表决中' AND org IN
            (SELECT id FROM association WHERE state='清算中' AND deadline<=?)""",period);
        s.update("UPDATE association SET state='归档' WHERE state='清算中' AND deadline<=?",period);
    }
}
