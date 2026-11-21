package social.feudal.realm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static social.feudal.realm.Charters.Bounds;

class ChartersTest {
 @TempDir Path temp;
 @Test void limitedDepthAndSeparateBeneficiary() throws Exception{
    try(var s=new RealmStore(temp.resolve("r"))){
        s.initialize("w",0,0,"rex");var f=new FiscalLedger(s);f.begin(1);var c=new Charters(s);
        var b=new Bounds(4,0,7,2);c.grant("rex","crown","公爵","duke","河东","a",b,true);
        assertEquals("crown",s.owner(7,0));c.grant("rex","crown","公爵","duke","河东","a",b,false);
        c.grant("a","duke","伯爵","count","东渡","b",new Bounds(7,0,7,1),false);
        assertThrows(IllegalArgumentException.class,()->c.grant("b","count","伯爵","bad","bad","c",b,false));
        c.estate("b","count","庄园","farm","渡口庄园","fam",f.depot("ford"));
        assertEquals("count",s.owner(7,0));assertEquals("farm",f.right(f.depot("ford")));
        assertThrows(IllegalArgumentException.class,()->f.collect("ford",1,"rex"));
        assertEquals("farm",f.collect("ford",1,"fam").title());
    }
 }
 @Test void governorCannotGrowAndCapIsShared()throws Exception{
    try(var s=new RealmStore(temp.resolve("r"))){
        s.initialize("w",0,0,"rex");new FiscalLedger(s);var c=new Charters(s);
        c.grant("rex","crown","流官","gov","南区","g",new Bounds(-8,-8,7,-1),false);
        assertThrows(IllegalArgumentException.class,()->c.add("g","gov",new Bounds(0,1,7,1),false));
        assertThrows(IllegalArgumentException.class,()->c.grant("g","gov","伯爵","no","no","c",new Bounds(1,-6,2,-4),false));
        c.grant("rex","crown","流官","other","北区","g",new Bounds(-8,1,7,7),false);
        c.register("rex","crown",new Bounds(8,0,8,0));
        c.register("rex","crown",new Bounds(8,1,9,1));
        c.add("rex","gov",new Bounds(1,0,8,0),false);c.add("rex","other",new Bounds(-8,0,-1,0),false);
        assertEquals(256,c.areaHeld("g"));
        assertThrows(IllegalArgumentException.class,()->c.add("rex","gov",new Bounds(9,1,9,1),false));
        assertEquals("crown",s.owner(9,1));assertEquals(256,c.areaHeld("g"));
    }
 }
 @Test void imperialPatentCreatesAnImmediateCountyWithoutOpeningAnExtraRank()throws Exception{
    try(var s=new RealmStore(temp.resolve("patent"))){
        s.initialize("world",0,0,"rex");var f=new FiscalLedger(s);var c=new Charters(s);new Succession(s,c,f);
        var b=new Bounds(5,0,6,0);
        assertThrows(IllegalArgumentException.class,()->c.grant("rex","crown","伯爵","newcount","郡","a",b,false));
        c.patent("rex","crown","伯爵","newcount","郡","a",b,"守渡特许");
        assertEquals("直属特许",s.title("newcount").charter());
        assertEquals("crown",s.title("newcount").parent());
        assertThrows(IllegalArgumentException.class,()->c.patent("rex","crown","国王","bad","郡","a",b,"上封"));
        assertEquals(1,s.number("SELECT count(*) FROM instrument WHERE title='newcount'"));
    }
 }

}
