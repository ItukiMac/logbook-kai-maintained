"""Current combined evidence count; keep historical scopes and unresolved semantics distinct."""
import hashlib,json,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from maintenance_plan import source_allowed
ROOT=Path(__file__).resolve().parents[2]
class R6ReviewInventoryTest(unittest.TestCase):
    def setUp(self):
        self.facts=json.loads((ROOT/'src/test/resources/maintenance/r6-yearly-contracts.json').read_text())
    def test_all_current_hashes_and_source_references(self):
        seen=set()
        for row in self.facts['definitions']:
            self.assertNotIn(row['id'],seen);seen.add(row['id'])
            raw=(ROOT/f"src/main/resources/logbook/quest/{row['id']}.json").read_bytes()
            self.assertEqual(row['definition_sha256'],hashlib.sha256(raw).hexdigest())
            self.assertFalse(row['whole_rule_verified']);self.assertTrue(row['unresolved'])
            for idx in row['source_indices']:
                s=self.facts['sources'][idx];self.assertTrue(source_allowed(s['url']))
                if s['source_modified']: self.assertLessEqual(s['source_modified'],self.facts['checked_on'])
        self.assertEqual(16,len(seen));self.assertEqual(62,sum(len(x['rules']) for x in self.facts['definitions']))
    def test_known_gaps_and_port_proxy_are_not_certified(self):
        by={r['id']:r for r in self.facts['definitions']}
        for ident in (944,945,973,1012):self.assertGreater(len(by[ident]['unresolved']),1)
        for row in self.facts['definitions']:
            for rule in row['rules']:
                if rule['kind']=='goal':self.assertTrue(rule['unresolved'])
        self.assertEqual([1],by[948]['deck_ids'])
    def test_full_review_union_not_only_this_new_subset(self):
        prefix='src/main/resources/logbook/'
        inventory={p.relative_to(ROOT).as_posix() for group in ('quest','eventquest','mission') for p in (ROOT/prefix/group).rglob('*.json')}
        old=json.loads((ROOT/'docs/maintenance/source-field-review-20261026.json').read_text())
        covered={r['path'] for r in old['definitions']}
        for filename in ('r4-periodic-contracts.json','r5-expedition-contracts.json','r6-yearly-contracts.json'):
            data=json.loads((ROOT/'src/test/resources/maintenance'/filename).read_text())
            group={prefix+'quest/'+str(r['id'])+'.json' for r in data['definitions']}
            self.assertFalse(group&covered);covered|=group
        self.assertFalse(covered-inventory)
        self.assertEqual(148,len(inventory));self.assertEqual(133,len(covered));self.assertEqual(15,len(inventory-covered))
        print('R6_REVIEW_COVERAGE '+json.dumps(dict(definitions=148,partial=133,unreviewed=15,whole_rule_verified=0,complete=False,remaining=sorted(inventory-covered))))
if __name__=='__main__':unittest.main()
