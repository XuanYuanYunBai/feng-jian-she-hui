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
    @Test void homesteadSearchAndRegistrationRespectLandAndHouseholds()throws Exception{
        try(var s=open("first-holding")){
            var r=new SettlementRegister(s);
            assertNull(r.nearestYard(ALICE,0,0,-16,-16,16,16,(x,z)->SettlementRegister.Ground.DRY));
            int[] site=r.nearestYard(ALICE,-4,-4,-500,-500,500,500,(x,z)->SettlementRegister.Ground.DRY);
            assertNotNull(site);
            assertNull(r.nearestYard(ALICE,-4,-4,-500,-500,500,500,(x,z)->SettlementRegister.Ground.UNSUITABLE));
            assertArrayEquals(site,r.nearestYard(ALICE,-4,-4,-500,-500,500,500,(x,z)->SettlementRegister.Ground.UNSEEN));
            var yard=FirstHolding.yard(site[0],site[1]);
            r.available(ALICE,yard);
            var cottage=new SettlementRegister.Manor("brook","溪庄",ALICE,site[0],64,site[1],yard,100,0,true);
            r.estate(cottage);
            assertEquals(100,r.manor("brook").residents());
            assertTrue(r.manor("brook").cottage());
            assertFalse(java.util.Arrays.equals(site,r.nearestYard(BOB,-4,-4,-500,-500,500,500,(x,z)->SettlementRegister.Ground.DRY)));
            assertThrows(IllegalArgumentException.class,()->r.available(BOB,yard));
            var next=FirstHolding.yard(-404,-404);
            assertThrows(IllegalArgumentException.class,()->r.estate(new SettlementRegister.Manor("again","新庄",ALICE,-404,64,-404,next,100,0,true)));
            assertThrows(IllegalArgumentException.class,()->r.estate("ordinary","分庄",ALICE,-404,64,-404,next));
            assertEquals(100,r.manor("brook").residents());
            assertNull(s.owner(site[0]>>4,site[1]>>4));
        }
    }
    @Test void landRequiresPublicNoticeServiceAndActualGrantOfPeerage()throws Exception{
        try(var s=open("settle")){
            var r=new SettlementRegister(s);var b=new Charters.Bounds(480,480,495,495);var chunks=new Charters.Bounds(30,30,31,30);
            r.estate("west","西庄",ALICE,481,64,481,b);
            assertThrows(IllegalArgumentException.class,()->r.propose(ALICE,"west","west","入帝",chunks,1,100));
            s.update("UPDATE manor SET residents=600,service=1");
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
    @Test void obligationRequiresActualMatchingFreightAndCanBeCreditedOnce()throws Exception{
        Path manifest=tmp.resolve("freight.sqlite");
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+manifest);var q=db.createStatement()){
            q.execute("CREATE TABLE routes(name TEXT,world TEXT,dx INTEGER,dz INTEGER)");
            q.execute("INSERT INTO routes VALUES('wharf','world',1024,0)");
            q.execute("CREATE TABLE trips(id TEXT,route TEXT,material TEXT,amount INTEGER,phase TEXT,sender TEXT,signer TEXT,delivered_at TEXT)");
        }
        try(var s=open("dues")){
            country(s,"west",ALICE,true);var ledger=new Contributions(s,manifest);
            int pledge=ledger.pledge("empire","west",ALICE,"wharf","WHEAT",16,2);
            try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+manifest);var q=db.createStatement()){
                q.execute("INSERT INTO trips VALUES('load','wharf','WHEAT',8,'TRAVEL','"+ALICE+"',null,null)");
                assertThrows(IllegalArgumentException.class,()->ledger.credit(pledge,ALICE,"load"));
                q.execute("UPDATE trips SET phase='DELIVERED',signer='buyer',delivered_at=datetime('now','+1 second')");
            }
            ledger.credit(pledge,ALICE,"load");
            assertThrows(IllegalArgumentException.class,()->ledger.credit(pledge,ALICE,"load"));
            assertEquals(8,s.number("SELECT received FROM contribution WHERE id=?",pledge));
        }
    }
    @Test void onlyUncontestedConnectedFrontierCanPassAndCapitalsFollowTheirCountry()throws Exception{
        try(var s=open("frontier")){
            var reg=new SettlementRegister(s);
            var bounds=new Charters.Bounds(72,0,73,0);
            reg.estate("border","东岸边庄","东境伯家",1154,64,4,new Charters.Bounds(1152,0,1167,15));
            assertThrows(IllegalArgumentException.class,()->reg.propose("东境伯家","border","march","开拓",bounds,0,1));
            reg.permit("npc-皇廷署理","march",bounds);
            int id=reg.propose("东境伯家","border","march","开拓",bounds,0,1);
            reg.object(id,"other",73,0,"边界待议");
            s.update("UPDATE manor SET service=1");s.update("INSERT INTO setting VALUES('season',1)");
            reg.resolve(id,"东境伯家",2);assertEquals("march",s.owner(72,0));assertNull(s.owner(73,0));
            reg.moveCapital("march","border","东境伯家");
            assertEquals(72,s.title("march").x());assertEquals("东境伯家",reg.manor("border").owner());
            assertTrue(s.politics.imperial("march"));assertFalse(s.politics.member("empire","crown"));
        }
    }
    @Test void legacyWorldIsBackedUpBeforeAttributingPublicRights()throws Exception{
        Path path=tmp.resolve("legacy.sqlite");
        try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path);var q=db.createStatement()){
            q.execute("CREATE TABLE setting(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
            q.execute("INSERT INTO setting VALUES('world','old')");
            q.execute("CREATE TABLE title(id TEXT PRIMARY KEY,name TEXT,rank TEXT,parent TEXT,charter TEXT,lawful TEXT,seal TEXT,x INTEGER,z INTEGER)");
            q.execute("INSERT INTO title VALUES('crown','河湾帝国','皇帝',NULL,'皇室','npc-代理','npc-代理',0,0)");
            q.execute("INSERT INTO title VALUES('march','东境伯领','伯爵','crown','直属特许','东境伯家','东境伯家',64,0)");
            q.execute("CREATE TABLE treasury(title TEXT PRIMARY KEY,grain INTEGER)");
            q.execute("INSERT INTO treasury VALUES('crown',18)");
        }
        try(var s=new RealmStore(path)){
            assertEquals("皇室旧领（待清册）",s.title("crown").name());assertNull(s.politics.nation("crown"));
            assertEquals("东境伯领",s.polity("march"));assertTrue(s.politics.member("empire","march"));
            assertEquals(18,s.number("SELECT grain FROM treasury WHERE title='crown'"));
        }
        try(var copy=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path+".pre-0.5.0");var q=copy.createStatement();
            var row=q.executeQuery("SELECT name FROM title WHERE id='crown'")){
            assertTrue(row.next());assertEquals("河湾帝国",row.getString(1));
        }
    }
    @Test void anEmperorCanSignForTheEmpireOnlyWithATreatySpecificMandate()throws Exception{
        try(var s=open("mandate")){
            country(s,"duchy",ALICE,true);country(s,"abroad",BOB,false);
            var law=s.politics;law.requestElection("duchy",ALICE);
            law.draft("riverpact","跨境通航","条约","准许两岸粮船靠港",List.of("abroad","@empire"),"abroad",BOB);
            assertThrows(IllegalArgumentException.class,()->law.signOrganisation("riverpact","empire",ALICE));
            int n=law.propose("empire","march","东境伯家","外交授权","riverpact","duchy");
            law.vote(n,"march","东境伯家",true);law.vote(n,"abbey","主教座堂",true);
            assertThrows(IllegalArgumentException.class,()->law.signOrganisation("riverpact","empire",BOB));
            law.signOrganisation("riverpact","empire",ALICE);
            assertEquals("生效",s.value("SELECT state FROM treaty WHERE id='riverpact'"));
            assertNull(s.value("SELECT nation FROM signatory WHERE treaty='riverpact' AND nation='duchy'"));
            assertEquals(ALICE,s.value("SELECT signer FROM organisation_signature WHERE org='empire'"));
        }
    }
}
