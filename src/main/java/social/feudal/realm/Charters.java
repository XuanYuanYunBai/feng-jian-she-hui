package social.feudal.realm;

import java.sql.SQLException;
import java.util.Set;
import static social.feudal.realm.RealmStore.require;

/** A political title and a charter over one stream of income are separate grants. */
final class Charters {
    static final int GOVERNOR_LIMIT=256;
    private static final Set<String> ESTATES=Set.of("庄园","修院","磨坊","地租");
    record Bounds(int west,int north,int east,int south){
        int area(){
            long w=1L+east-west,h=1L+south-north;
            require(w>0 && h>0 && w<=Integer.MAX_VALUE && h<=Integer.MAX_VALUE && w*h<=Integer.MAX_VALUE,"请按西北至东南输入有效区块坐标");
            return (int)(w*h);
        }
        boolean contains(int x,int z){return west<=x&&x<=east&&north<=z&&z<=south;}
    }
    private final RealmStore s;
    Charters(RealmStore s){this.s=s;}
    RealmStore.Title acts(String id,String actor)throws SQLException{
        var t=s.title(id);
        require(t.seal().equals(actor),"须由该领实际持印人决定");
        require(t.lawful().equals(actor),"任命与官印有争议，暂不能行使授地权");
        return t;
    }
    static boolean sovereign(RealmStore.Title t){return Set.of("皇帝","国王").contains(t.rank());}
    int areaHeld(String actor)throws SQLException{
        return s.number("""
            SELECT count(*) FROM land JOIN title ON land.title=title.id
            WHERE charter='流官' AND (seal=? OR lawful=?)""",actor,actor);
    }
    void capacity(String actor,int extra)throws SQLException{
        require((long)areaHeld(actor)+extra<=GOVERNOR_LIMIT,"超出流官合计 256 区块上限");
    }
    private void available(RealmStore.Title from,Bounds b)throws SQLException{
        int area=b.area();
        require(!b.contains(from.x(),from.z()),"须保留授予者驻地区块");
        require(s.number("SELECT count(*) FROM land WHERE title=? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?",
                from.id(),b.west(),b.east(),b.north(),b.south())==area,"所选区块包含未登记地或他领土地");
        require(s.number("""
            SELECT count(*) FROM tax_right JOIN depot ON depot.id=tax_right.depot
            WHERE x BETWEEN ? AND ? AND z BETWEEN ? AND ?""",b.west(),b.east(),b.north(),b.south())==0,"选区已有独立收益封契");
    }
    private void transfer(String title,Bounds b)throws SQLException{
        s.update("UPDATE land SET title=? WHERE x BETWEEN ? AND ? AND z BETWEEN ? AND ?",title,b.west(),b.east(),b.north(),b.south());
    }
    private void newId(String id)throws SQLException{
        require(id.matches("[a-z][a-z0-9_-]{0,23}"),"代号须使用 1–24 位小写字母、数字、下划线或短横线");
        require(s.value("SELECT id FROM title WHERE id=?",id)==null,"头衔代号已存在");
    }
    String grant(String actor,String parent,String kind,String id,String name,String grantee,Bounds b,boolean preview)throws SQLException{
        return s.atomic(()->{
            var from=acts(parent,actor);newId(id);
            require(!from.charter().equals("流官"),"流官不得扩地或转封");
            boolean allowed=switch(kind){
                case "公爵","流官" -> sovereign(from);
                case "伯爵" -> from.rank().equals("公爵");
                default -> false;
            };
            require(allowed,"此层级不能设立该政治领地；伯爵可授庄园、修院、磨坊或地租");
            available(from,b);
            int area=b.area();
            String result=name+" · "+kind+" · 授予 "+area+" 区块";
            if(kind.equals("流官")){
                capacity(grantee,area);
                result+=" · 此人合计 "+(areaHeld(grantee)+area)+"/"+GOVERNOR_LIMIT;
            }
            if(!preview){
                s.titleRow(id,name,kind.equals("流官")?"辖区":kind,parent,kind.equals("流官")?"流官":"封契",grantee,b.west(),b.north());
                s.politics.attach(s.title(id));
                transfer(id,b);
            }
            return result;
        });
    }
    void patent(String actor,String empire,String rank,String id,String name,String grantee,Bounds b,String terms)throws SQLException{
        s.atomic(()->{
            var ruler=acts(empire,actor);require(ruler.rank().equals("皇帝"),"帝国直属特许须由皇帝签发");
            require(Set.of("伯爵","城市","修院").contains(rank),"直属特许可授伯爵、城市或修院");
            require(!terms.isBlank() && terms.length()<=120,"请写明特许缘由与条件（1–120字）");
            newId(id);available(ruler,b);
            s.titleRow(id,name,rank,empire,"直属特许",grantee,b.west(),b.north());
            s.politics.attach(s.title(id));
            transfer(id,b);
            s.update("INSERT INTO instrument(title,period,event) VALUES(?,COALESCE((SELECT v FROM setting WHERE k='period'),1),?)",
                    id,"帝国直属特许 · "+terms);
            return null;
        });
    }
    String add(String actor,String id,Bounds b,boolean preview)throws SQLException{
        return s.atomic(()->{
            var governor=s.title(id);require(governor.charter().equals("流官"),"仅可追加流官辖地");
            var from=acts(governor.parent(),actor);require(sovereign(from),"限任命该官的君主追加");
            available(from,b);capacity(governor.seal(),b.area());
            if(!governor.seal().equals(governor.lawful()))capacity(governor.lawful(),b.area());
            int total=areaHeld(governor.seal())+b.area();
            if(!preview)transfer(id,b);
            return governor.name()+" · 追加后此人合计 "+total+"/"+GOVERNOR_LIMIT+" 区块";
        });
    }
    void checkEstate(String actor,String parent,String kind,String id,RealmStore.Depot d)throws SQLException{
        var from=acts(parent,actor);newId(id);
        require(!from.charter().equals("流官") && Set.of("伯爵","公爵","国王","皇帝").contains(from.rank()),"此官职没有收益转授权");
        require(ESTATES.contains(kind),"可授庄园、修院、磨坊或地租");
        require(parent.equals(s.owner(d.x(),d.z())) && s.value("SELECT title FROM tax_right WHERE depot=?",d.id())==null,"该税源不在可处分的直辖收入中");
    }
    void estate(String actor,String parent,String kind,String id,String name,String grantee,RealmStore.Depot d)throws SQLException{
        s.atomic(()->{
            checkEstate(actor,parent,kind,id,d);
            s.titleRow(id,name,kind,parent,"收益封契",grantee,d.x(),d.z());
            s.politics.attach(s.title(id));
            s.update("INSERT INTO tax_right VALUES(?,?)",d.id(),id);return null;
        });
    }
}
