"""Verify the 0.9.0 river fair integration fixture after stopping or restarting Paper."""
import argparse
import sqlite3
from pathlib import Path


def open_db(directory, name):
    return sqlite3.connect((directory / name).resolve().as_uri() + "?mode=ro", uri=True)


def verify(directory, governor_cap=False):
    with open_db(directory, "freight.sqlite") as freight:
        legs = freight.execute("""
            SELECT t.route,t.phase,t.material,t.amount,t.cargo,r.source_seal,r.dest_seal,r.path
            FROM trips t JOIN routes r ON t.route=r.name
            WHERE t.id IN ('autumn1','autumn2','autumn3') ORDER BY t.id""").fetchall()
        assert [r[0] for r in legs] == ["fieldport", "estuary", "dockmarket"]
        assert all(r[1:4] == ("DELIVERED", "WHEAT", 7) for r in legs)
        assert len({r[4] for r in legs}) == 1
        assert legs[0][6] == legs[1][5] and legs[1][6] == legs[2][5]
        assert [r[7].split("|")[1] for r in legs] == ["road", "water", "road"]
    with open_db(directory, "realm.sqlite") as realm:
        def one(sql):
            return realm.execute(sql).fetchone()[0]

        titles = realm.execute("""
            SELECT t.id,t.rank,t.parent,count(l.title) FROM title t LEFT JOIN land l ON l.title=t.id
            WHERE t.id IN ('flood','upland','millshire','millright','bail') GROUP BY t.id ORDER BY t.id""").fetchall()
        assert titles == [("bail","辖区","crown",5), ("flood","国王","crown",26), ("millright","磨坊","millshire",0),
                          ("millshire","伯爵","upland",9), ("upland","公爵","flood",15)]
        assert one("SELECT count(*) FROM member WHERE org='empire' AND nation='flood'") == 1
        assert one("SELECT count(*) FROM title WHERE id IN ('lower','illegal')") == 0
        assert one("SELECT title FROM land WHERE x=-5 AND z=-2") == "millshire"
        assert one("SELECT title FROM tax_right WHERE depot='mill'") == "millright"
        assert one("SELECT hereditary FROM office WHERE id='millshire'") == 1
        assert one("""SELECT count(*) FROM title WHERE id='millshire'
                   AND lawful=seal AND seal='77ae157c-6f15-3b37-b8da-d2d3449e70af'""") == 1
        if governor_cap:
            jurisdictions = realm.execute("""
                SELECT l.title,count(*) FROM land l JOIN title t ON t.id=l.title
                WHERE t.charter='流官' AND t.lawful=(SELECT lawful FROM title WHERE id='bail')
                GROUP BY l.title ORDER BY l.title""").fetchall()
            assert jurisdictions == [("bail",5),("upperbail",251)]
            assert one("SELECT title FROM land WHERE x=335 AND z=333") == "crown"
        trades = realm.execute("""
            SELECT b.ask,b.floating,t.paid FROM trade t JOIN bid b ON b.id=t.bid
            WHERE b.stall='eastquay' AND b.id IN (3,4,5) ORDER BY t.rowid""").fetchall()
        assert trades == [(1,0,4),(1,1,7),(0,1,7)]
        assert realm.execute("SELECT silver,samples FROM market_reference WHERE goods='小麦'").fetchone() == (3.25,2)
    print("通过 · 三段运单与两次同柜换装、五份领权记录及继任、三笔玩家交割")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="本测试实例的 plugins/FeudalSurvey 目录")
    parser.add_argument("--governor-cap", action="store_true", help="同时核对第 257 块未划拨的边界场景")
    args = parser.parse_args()
    verify(args.directory, args.governor_cap)
