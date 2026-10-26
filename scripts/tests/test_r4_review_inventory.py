"""Keep exact-byte evidence and unfinished definitions visible across R3/R4 reviews."""
import hashlib
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from maintenance_plan import source_allowed

ROOT = Path(__file__).resolve().parents[2]

class ReviewInventoryTest(unittest.TestCase):
    def setUp(self):
        self.new = json.loads((ROOT/'src/test/resources/maintenance/r4-periodic-contracts.json').read_text())
        self.old = json.loads((ROOT/'docs/maintenance/source-field-review-20261026.json').read_text())

    def test_review_hashes_and_sources_are_bound_to_actual_definitions(self):
        seen = set()
        for record in self.new['definitions']:
            self.assertNotIn(record['id'], seen); seen.add(record['id'])
            path = ROOT/'src/main/resources/logbook/quest'/f"{record['id']}.json"
            self.assertEqual(record['definition_sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertTrue(record['source_indices'])
            for index in record['source_indices']:
                source = self.new['sources'][index]
                self.assertTrue(source_allowed(source['url']))
                self.assertLessEqual(source['source_modified'], self.new['checked_on'])
            self.assertFalse(record['whole_rule_verified'])
        self.assertEqual(38, len(seen))
        self.assertEqual(71, sum(len(r['rules']) for r in self.new['definitions']))

    def test_union_keeps_unreviewed_records_and_partial_scope_explicit(self):
        prefix = 'src/main/resources/logbook/'
        inventory = {p.relative_to(ROOT).as_posix() for group in ('quest','eventquest','mission')
                     for p in (ROOT/prefix/group).rglob('*.json')}
        old = {r['path'] for r in self.old['definitions']}
        new = {prefix+'quest/'+str(r['id'])+'.json' for r in self.new['definitions']}
        self.assertFalse((old|new)-inventory)
        self.assertFalse(old & new)
        self.assertEqual(148, len(inventory))
        self.assertEqual(104, len(old|new))
        self.assertEqual(44, len(inventory-old-new))
        self.assertEqual(0, self.new['whole_rule_verified'])
        print('R4_REVIEW_COVERAGE '+json.dumps({'definitions':len(inventory),
            'with_partial_evidence':len(old|new),'no_comparison_yet':len(inventory-old-new),
            'whole_rule_verified':0,'complete':False,'unreviewed':sorted(inventory-old-new)}))

    def test_known_collector_and_classification_gaps_are_not_erased(self):
        by_id = {r['id']:r for r in self.new['definitions']}
        for ident in (211,220,257,854,872):
            self.assertTrue(by_id[ident]['unresolved'])
            self.assertFalse(by_id[ident]['whole_rule_verified'])

if __name__ == '__main__': unittest.main()
