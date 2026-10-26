"""Tooling-only exact-source replay. Run in an isolated pinned checkout, never a live installation."""
from pathlib import Path
import hashlib
import json
import subprocess

BASE = '79cadc0e478718ffcabb737b8c6e9b2efa91918e'
EXPECTED = 'bdc2dddce0296366bf4756d0f7b9b637dd2ec0ebeecf753dfc188e17f75cd396'
OLD = {
 'src/main/java/logbook/bean/AppQuestCondition.java': '7af95f9339249e17a6e7579f87abf58b25e55d1ed969ef5159e5ed0e43c60a56',
 'src/main/java/logbook/internal/kancolle/QuestCollect.java': '91ad7aa8e9c075c9a226872c7f151364b6ce32c8d36a52ae0b8b02ea7772c3cc',
 'src/main/resources/logbook/quest/904.json': '787abdf56762fc8c86dd455b6a1412a06e1316518789906d8e9d7e3e72395534',
 'src/test/resources/maintenance/r6-yearly-contracts.json': '712cc2128a408385343494bf78a22cf710bf8eaf56a2bc488d319ec00ae6a2b2',
}
NEW = [
 'src/test/java/logbook/core/FleetPredicateIsolationTest.java',
 'src/test/java/logbook/core/NineteenthDestroyerQuestTest.java',
 'src/test/java/logbook/internal/kancolle/QuestFleetEvidenceTest.java',
 'scripts/tests/test_r8_review_inventory.py',
 'docs/maintenance/r8-progress.md',
 'docs/maintenance/r8-rule-changes.json',
]
assert subprocess.check_output(['git','rev-parse','HEAD'], text=True).strip() == BASE
assert not subprocess.check_output(['git','status','--porcelain'], text=True)
for path, expected in OLD.items():
    assert Path(path).is_file() and not Path(path).is_symlink()
    assert hashlib.sha256(Path(path).read_bytes()).hexdigest() == expected, path
for path in NEW:
    assert not Path(path).exists()
    assert (Path('../payload')/path).is_file()
    assert not (Path('../payload')/path).is_symlink()

def replace(text, old, new, count=1):
    eol = '\r\n' if '\r\n' in text else '\n'
    old, new = old.replace('\n', eol), new.replace('\n', eol)
    assert text.count(old) == count, old
    return text.replace(old, new)

out = {}
p = 'src/main/java/logbook/bean/AppQuestCondition.java'
s = Path(p).read_bytes().decode('utf8')
s = replace(s, '        public boolean test(List<ShipMst> ships) {\n', '''        public boolean test(List<ShipMst> ships) {
            // Unknown input entries are not evidence of a valid fleet. Keep them
            // distinct from the null markers consumed internally by AND clauses.
            if (ships == null || ships.stream().anyMatch(java.util.Objects::isNull)) return false;
            return evaluate(new ArrayList<>(ships));
        }

        private boolean evaluate(List<ShipMst> ships) {
''')
s = replace(s, 'this.difference ^ this.testShip0(ships.get(i))', 'matchesAvailableShip(ships.get(i))', 2)
s = replace(s, 'this.difference ^ this.testShip0(ships.get(this.order - 1))', 'matchesAvailableShip(ships.get(this.order - 1))')
s = replace(s, '        private boolean testShip0(ShipMst ship) {', '''        private boolean matchesAvailableShip(ShipMst ship) {
            // A consumed slot is not an excluded ship, nor an available match.
            return ship != null && (this.difference ^ this.testShip0(ship));
        }

        private boolean testShip0(ShipMst ship) {''')
s = replace(s, '''                if (predicate == null) {
                    predicate = condition;
                } else {
                    predicate = this.operator.endsWith("AND")
                            ? predicate.and(condition)
                            : predicate.or(condition);''', '''                if (predicate == null) {
                    predicate = condition::evaluate;
                } else {
                    predicate = this.operator.endsWith("AND")
                            ? predicate.and(condition::evaluate)
                            : predicate.or(condition::evaluate);''')
out[p] = s.encode('utf8')
p = 'src/main/java/logbook/internal/kancolle/QuestCollect.java'
s = Path(p).read_bytes().decode('utf8')
s = replace(s, 'import logbook.bean.ShipMst;', 'import logbook.bean.Ship;\nimport logbook.bean.ShipMst;')
s = replace(s, '''                    List<ShipMst> ships = p.getAfterFriend().stream()
                            .filter(Objects::nonNull)
                            .map(Ships::shipMst)
                            .map(s -> s.orElse(null))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    if (!filter.getFleet().test(ships)) {''', '''                    List<ShipMst> ships = recordedFleet(p.getAfterFriend());
                    if (ships == null || !filter.getFleet().test(ships)) {''')
s = replace(s, '    private static void count(String map, SimpleBattleLog log, Count count) {', '''    /** Resolve the recorded positions without promoting an escort into a missing slot. */
    private static List<ShipMst> recordedFleet(List<Ship> recorded) {
        if (recorded == null) return null;
        int size = recorded.size();
        // Saved fleets may contain trailing unused slots, but an interior gap is not padding.
        while (size > 0 && recorded.get(size - 1) == null) size--;
        if (size == 0) return null;
        List<ShipMst> resolved = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            Ship member = recorded.get(slot);
            if (member == null) return null;
            ShipMst master = Ships.shipMst(member).orElse(null);
            if (master == null) return null;
            resolved.add(master);
        }
        return resolved;
    }

    private static void count(String map, SimpleBattleLog log, Count count) {''')
out[p] = s.encode('utf8')
p = 'src/main/resources/logbook/quest/904.json'
s = Path(p).read_bytes().decode('utf8')
s = replace(s, '''                {"name": ["綾波改二", "敷波改二"], "count": 2, "operator": "GE"},
                {"stype": ["*"], "count": 6, "operator": "GE"}''', '''                {"name": ["綾波改二"], "count": 1, "operator": "GE"},
                {"name": ["敷波改二"], "count": 1, "operator": "GE"},
                {"stype": ["*"], "count": 6, "operator": "EQ"}''')
out[p] = s.encode('utf8')
p = 'src/test/resources/maintenance/r6-yearly-contracts.json'
s = Path(p).read_bytes().decode('utf8'); facts = json.loads(s)
row = next(r for r in facts['definitions'] if r['id']==904)
original = json.dumps(row,ensure_ascii=False,separators=(',',':'))
old = row['definition_sha256']
row['definition_sha256'] = hashlib.sha256(out['src/main/resources/logbook/quest/904.json']).hexdigest()
row['unresolved'] = ['Acceptance, prerequisites, server reset and live collection are outside this review.','Future remodel variants and live server changes are not inferred from the present named roster.']
row['r8_amendment'] = {'previous_definition_sha256':old,'checked_on':'2026-10-26','reason':'Require both named remodels independently and exactly six ships. Aggregate map/rank/count/month rules unchanged. Prior unverified six-ship assessment is superseded only for this explicit fleet condition.','source_url':'https://zekamashi.net/kancolle-kouryaku/kaiboukan-odorideru/','source_modified':'2026-07-25','corroborating_url':'https://wikiwiki.jp/kancolle/任務/出撃定期','corroborating_modified':'2026-07-04'}
assert s.count(original)==1
out[p] = s.replace(original,json.dumps(row,ensure_ascii=False,separators=(',',':'))).encode('utf8')
for p in NEW: out[p] = (Path('../payload')/p).read_bytes()
h = hashlib.sha256()
for name,data in out.items(): h.update(name.encode()+b'\0'+data+b'\0')
assert len(out)==10 and h.hexdigest()==EXPECTED, (h.hexdigest(),EXPECTED)
for name,data in out.items():
    p=Path(name);p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
Path('../r8-verified-paths.json').write_text(json.dumps(list(out)))
print('VERIFIED_EXACT_SOURCE',len(out),h.hexdigest())
