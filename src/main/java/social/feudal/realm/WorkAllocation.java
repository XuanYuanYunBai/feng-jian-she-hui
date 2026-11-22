package social.feudal.realm;

import java.sql.SQLException;
import static social.feudal.realm.RealmStore.require;

/** Farmers, miners and smiths draw from the same local season. Farm shifts reserve up to 64 hours. */
final class WorkAllocation {
    private final RealmStore s;
    private final SettlementRegister register;
    WorkAllocation(RealmStore s,SettlementRegister register)throws SQLException{
        this.s=s;this.register=register;
        s.update("""
            CREATE TABLE IF NOT EXISTS work_allocation(manor TEXT REFERENCES manor(id),season INTEGER,
            job TEXT PRIMARY KEY,hours INTEGER CHECK(hours>0))""");
    }
    int remaining(String manor,int season)throws SQLException{
        int residents=register.manor(manor).residents();
        int missing=s.number("SELECT shortfall FROM population_season WHERE manor=? ORDER BY season DESC LIMIT 1",manor);
        int budget=(int)(Math.max(0L,residents-(long)missing)*64/100);
        int farms=s.number("SELECT count(*) FROM farmhand JOIN mill ON mill.id=farmhand.mill WHERE manor=? AND season=?",manor,season);
        int other=s.number("SELECT coalesce(sum(hours),0) FROM work_allocation WHERE manor=? AND season=?",manor,season);
        return Math.max(0,budget-farms*64-other);
    }
    void reserve(String manor,int hours,String job)throws SQLException{
        int season=register.season();require(remaining(manor,season)>=hours,"本庄本季可用工时不足");
        s.update("INSERT INTO work_allocation VALUES(?,?,?,?)",manor,season,job,hours);
    }
}
