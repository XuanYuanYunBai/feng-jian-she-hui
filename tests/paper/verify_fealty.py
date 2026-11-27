"""Check the river-manor two-client renewal fixture against a read-only Paper save."""
import argparse
import sqlite3
from pathlib import Path


def verify(db):
    receipts = db.execute("""
        SELECT vassal,ruler,state,offered,accepted FROM fealty
        WHERE fief='riverlease' ORDER BY id""").fetchall()
    assert [(v, lord, state) for v, lord, state, _, _ in receipts] == [
        ("族人·1", "族人·9", "已终止"),
        ("族人·10", "族人·9", "已终止"),
        ("族人·10", "族人·11", "已受誓"),
    ], receipts
    assert receipts[1][4] is None
    for _, _, _, offered, accepted in (receipts[0], receipts[2]):
        assert accepted is not None and accepted >= offered
    assert db.execute("""
        SELECT v.person,l.person FROM fealty_holder v JOIN fealty_holder l ON l.id=v.parent
        WHERE v.id='riverlease'""").fetchone() == receipts[2][:2]

    manor = "h_eafd6f46d2a83c0f932b"
    rows = db.execute("""
        SELECT p.season,p.shortfall,a.reserve_ok,a.full_time
        FROM population_season p LEFT JOIN service_audit a USING(manor,season)
        WHERE p.manor=? ORDER BY p.season""", (manor,)).fetchall()
    assert any(reserve is None and full is None for _, _, reserve, full in rows)
    assert any((shortage, reserve, full) == (0, 1, 1) for _, shortage, reserve, full in rows)
    assert not db.execute("""
        SELECT 1 FROM service_audit a LEFT JOIN population_season p USING(manor,season)
        WHERE p.manor IS NULL LIMIT 1""").fetchone()
    print("通过 · 两代封君、两代封臣、三份誓书，旧季与新季供养凭据可区分")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path)
    args = parser.parse_args()
    with sqlite3.connect(args.database.resolve().as_uri() + "?mode=ro", uri=True) as db:
        verify(db)
