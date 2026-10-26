"""Every definition has comparison evidence, not every rule is certified correct."""
import hashlib
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from maintenance_plan import source_allowed
ROOT = Path(__file__).resolve().parents[2]

class R7ReviewInventoryTest(unittest.TestCase):
    def setUp(self):
        self.facts = json.loads((ROOT/'src/test/resources/maintenance/r7-final-cohort-contracts.json').read_text())
    def test_exact_current_hashes_source_editions_and_unresolved_scope(self):
        paths = set()
        for row in self.facts['definitions']:
            self.assertNotIn(row['path'], paths); paths.add(row['path'])
            self.assertEqual(f"src/main/resources/logbook/{row['group']}/{row['id']}.json", row['path'])
            self.assertEqual(row['definition_sha256'], hashlib.sha256((ROOT/row['path']).read_bytes()).hexdigest())
            self.assertTrue(source_allowed(row['source_url']))
            self.assertTrue(row['source_edition']); self.assertTrue(row['source_read']); self.assertTrue(row['unresolved'])
            self.assertFalse(row['whole_rule_verified'])
            if row['source_modified']: self.assertLessEqual(row['source_modified'], self.facts['checked_on'])
        self.assertEqual(15, len(paths)); self.assertEqual(55, sum(len(r['rules']) for r in self.facts['definitions']))
    def test_full_inventory_union_has_no_uncompared_definition_but_no_false_completion(self):
        prefix = 'src/main/resources/logbook/'
        inventory = {p.relative_to(ROOT).as_posix() for group in ('quest','eventquest','mission') for p in (ROOT/prefix/group).rglob('*.json')}
        old = json.loads((ROOT/'docs/maintenance/source-field-review-20261026.json').read_text())
        covered = {r['path'] for r in old['definitions']}
        for name in ('r4-periodic-contracts.json','r5-expedition-contracts.json','r6-yearly-contracts.json'):
            rows = json.loads((ROOT/'src/test/resources/maintenance'/name).read_text())['definitions']
            cohort = {prefix+'quest/'+str(r['id'])+'.json' for r in rows}
            self.assertFalse(covered & cohort); covered |= cohort
        new = {r['path'] for r in self.facts['definitions']}
        self.assertFalse(covered & new); covered |= new
        self.assertEqual(inventory, covered); self.assertEqual(148,len(covered))
        print('R7_REVIEW_COVERAGE '+json.dumps(dict(definitions=148,partially_compared=148,never_compared=0,whole_rule_verified=0,complete=False)))
    def test_known_conflicts_and_failed_body_fetch_are_not_erased(self):
        by = {r['id']:r for r in self.facts['definitions']}
        self.assertNotEqual(by[843]['source_period'], by[843]['encoded_period'])
        self.assertEqual('T',by[843]['rules'][-1]['source_cell']); self.assertIsNone(by[843]['rules'][-1]['encoded_cell'])
        self.assertIsNone(by[906]['source_period']); self.assertIn('timed out', by[906]['source_read'])
        self.assertIn('2021',by[909]['source_edition'])
        for n in (987,1039,1040,840,841): self.assertIn('2025',by[n]['source_edition'])
        for n in (906,954): self.assertTrue(any(r['kind']=='goal' and r['unresolved'] for r in by[n]['rules']))
    def test_changes_only_three_product_definitions_and_corrected_flagship_assessment(self):
        changes = json.loads((ROOT/'docs/maintenance/r7-rule-changes.json').read_text())
        self.assertEqual({973,1012,1019},{int(Path(c['path']).stem) for c in changes['changes']})
        self.assertFalse(changes['production_applied'])
        for change in changes['changes']:
            self.assertNotEqual(change['before_sha256'],change['after_sha256'])
            self.assertEqual(change['after_sha256'],hashlib.sha256((ROOT/change['path']).read_bytes()).hexdigest())
        yearly = json.loads((ROOT/'src/test/resources/maintenance/r6-yearly-contracts.json').read_text())
        by = {r['id']:r for r in yearly['definitions']}
        for n in (944,945): self.assertIn('false',by[n]['r7_assessment_correction'])
        for n in (973,1012): self.assertIn('r7_amendment',by[n])

if __name__ == '__main__': unittest.main()
