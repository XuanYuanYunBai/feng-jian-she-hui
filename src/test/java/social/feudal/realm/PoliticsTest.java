package social.feudal.realm;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PoliticsTest {
    @TempDir Path tmp;
    static final String ALICE="00000000-0000-0000-0000-000000000001";
    static final String BOB="00000000-0000-0000-0000-000000000002";
    private RealmStore open(String db)throws Exception{
        var s=new RealmStore(tmp.resolve(db));s.initialize("world",0,0,"npc-皇廷署理");
        new Succession(s,new Charters(s),new FiscalLedger(s));return s;
    }
    private void country(RealmStore s,String id,String actor,boolean imperial)throws Exception{
        s.titleRow(id,id,imperial?"地主":"伯爵",null,"建领公议",actor,30,30);
        s.politics.newNation(id,imperial,false);
    }
    @Test void electionMovesOfficeButNotTheCandidatesCountries()throws Exception{
        try(var s=open("election")){
            country(s,"west",ALICE,true);country(s,"foreign",ALICE,false);
            assertFalse(s.politics.choiceOpen());
            s.politics.requestElection("west",ALICE);
            assertEquals(ALICE,s.title("crown").seal());
            assertNull(s.politics.nation("crown"));assertFalse(s.politics.imperial("foreign"));
            new Succession(s,new Charters(s),new FiscalLedger(s)).vacate("crown",ALICE);
            assertEquals(ALICE,s.title("foreign").seal());assertEquals(ALICE,s.title("west").seal());
            assertTrue(s.politics.choiceOpen());assertEquals(Succession.VACANT,s.title("crown").seal());
        }
        try(var s=new RealmStore(tmp.resolve("election"))){assertTrue(s.politics.choiceOpen());assertTrue(s.politics.member("empire","west"));}
    }
    @Test void treatiesAndFederationHaveSeparateLifetimes()throws Exception{
        try(var s=open("states")){
            country(s,"west",ALICE,true);country(s,"south",BOB,false);country(s,"east","c",false);
            var d=s.politics;
            d.draft("river","通航公约","条约","一年通航",List.of("west","south"),"west",ALICE);
            d.sign("river","south",BOB);assertNull(s.value("SELECT id FROM association WHERE id='river'"));
            d.draft("league","河运同盟","河运同盟","共修水道",List.of("west","south"),"west",ALICE);
            assertThrows(IllegalArgumentException.class,()->d.sign("league","south",ALICE));
            d.sign("league","south",BOB);assertThrows(IllegalArgumentException.class,()->d.sign("league","south",BOB));
            int n=d.propose("league","east","c","接纳","east","申请");
            d.vote(n,"west",ALICE,true);assertFalse(d.member("league","east"));
            assertThrows(IllegalArgumentException.class,()->d.vote(n,"west",ALICE,true));
            d.vote(n,"south",BOB,true);assertTrue(d.member("league","east"));assertFalse(d.imperial("east"));
            int charter=d.propose("league","west",ALICE,"修章","west","开设港仓");
            d.vote(charter,"south",BOB,true);d.vote(charter,"east","c",true);
            assertEquals("开设港仓",s.value("SELECT charter FROM association WHERE id='league'"));
            d.leave("league","west",ALICE);assertTrue(d.member("empire","west"));
            d.leave("league","east","c");d.expiry(1);
            assertEquals("归档",s.value("SELECT state FROM association WHERE id='league'"));
            assertEquals("生效",s.value("SELECT state FROM treaty WHERE id='river'"));
        }
    }
    @Test void landRequiresPublicNoticeServiceAndActualGrantOfPeerage()throws Exception{
        try(var s=open("settle")){
            var r=new SettlementRegister(s);var b=new Charters.Bounds(480,480,495,495);var chunks=new Charters.Bounds(30,30,31,30);
            r.estate("west","西庄",ALICE,481,64,481,b);
            assertThrows(IllegalArgumentException.class,()->r.propose(ALICE,"west","west","入帝",chunks,1,100));
            s.update("UPDATE manor SET households=6,service=1");
            assertThrows(IllegalArgumentException.class,()->r.propose(ALICE,"west","west","独立",chunks,1,100));
            int n=r.propose(ALICE,"west","west","入帝",chunks,1,100);
            r.object(n,BOB,31,30,"既有租约");s.update("INSERT INTO setting VALUES('season',1)");
            assertThrows(IllegalArgumentException.class,()->r.resolve(n,ALICE,100));
            r.resolve(n,ALICE,101);
            assertEquals("west",s.owner(30,30));assertNull(s.owner(31,30));assertEquals("地主",s.title("west").rank());
            r.peer(ALICE,"west","申请","请求承认");r.peer("npc-皇廷署理","west","驳回","待呈担保");
            assertEquals("地主",s.title("west").rank());assertTrue(s.politics.member("empire","west"));
            r.peer(ALICE,"west","申请","已呈担保");r.peer("npc-皇廷署理","west","批准","完成");
            assertEquals("伯爵",s.title("west").rank());
            assertThrows(IllegalArgumentException.class,()->r.resolve(n,ALICE,101));
        }
    }
    @Test void taxpayersOnlyPackActualTenderedGrain()throws Exception{
        try(var s=open("tax")){
            var f=new FiscalLedger(s);f.begin(1,java.util.Map.of("east",140));f.prepare(f.depot("east"),1);
            assertEquals(140,s.number("SELECT sum(amount) FROM crate WHERE depot='east'"));
            assertTrue(s.number("SELECT max(amount) FROM crate")<=64);
            assertEquals(0,s.number("SELECT count(*) FROM assessment WHERE depot='town'"));
            f.begin(1,java.util.Map.of("east",400));assertEquals(140,f.account("east",1).due());
        }
    }
}
