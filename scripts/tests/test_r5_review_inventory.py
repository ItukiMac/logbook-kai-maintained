"""Combine evidence without promoting partial checks to complete game-rule certification."""
import hashlib
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_game_json import parse_jsonc
from maintenance_plan import source_allowed

ROOT = Path(__file__).resolve().parents[2]

class R5ReviewInventoryTest(unittest.TestCase):
    def load(self, name):
        return json.loads((ROOT / name).read_text(encoding='utf8'))
    def test_new_records_bind_actual_bytes_and_read_sources(self):
        facts = self.load('src/test/resources/maintenance/r5-expedition-contracts.json')
        seen = set()
        for row in facts['definitions']:
            self.assertNotIn(row['id'], seen); seen.add(row['id'])
            self.assertEqual(row['path'], f"src/main/resources/logbook/quest/{row['id']}.json")
            raw = (ROOT / row['path']).read_bytes()
            self.assertEqual(row['definition_sha256'], hashlib.sha256(raw).hexdigest())
            self.assertFalse(row['whole_rule_verified']); self.assertTrue(row['unresolved'])
            self.assertTrue(row['source_indices'])
            for index in row['source_indices']:
                source = facts['sources'][index]
                self.assertTrue(source_allowed(source['url']))
                if source['source_modified']:
                    self.assertLessEqual(source['source_modified'], facts['checked_on'])
        self.assertEqual(13, len(seen))
        self.assertEqual(41, sum(len(row['rules']) for row in facts['definitions']))
    def test_three_rebound_records_retain_prior_hashes_and_unfinished_scope(self):
        old = self.load('src/test/resources/maintenance/r4-periodic-contracts.json')
        prior = {257:'16235e4d44f4fb83eef1119c239f441f6504337305505bec5f9643bca5e51443',
                 854:'42204c61cdc91638d175b1f98e99c636bd704524fb06283312507cbaf35cfa10',
                 872:'43b29c33e8537ab31f54161217c834ebcbcc2685b9ddffde6cf53a782797c20e'}
        for row in old['definitions']:
            if row['id'] not in prior: continue
            raw = (ROOT / f"src/main/resources/logbook/quest/{row['id']}.json").read_bytes()
            self.assertEqual(row['previous_definition_sha256'], prior[row['id']])
            self.assertEqual(row['definition_sha256'], hashlib.sha256(raw).hexdigest())
            self.assertFalse(row['whole_rule_verified']); self.assertTrue(row['unresolved'])
            filt = parse_jsonc(raw.decode())['filter']
            if row['id'] == 257:
                self.assertIs(filt['noRetreat'], True); self.assertNotIn('deckIds', filt)
            else:
                self.assertEqual([1], filt['deckIds']); self.assertNotIn('noRetreat', filt)
    def test_combined_inventory_has_117_partial_and_31_unreviewed(self):
        prefix = 'src/main/resources/logbook/'
        actual = {p.relative_to(ROOT).as_posix() for group in ('quest','eventquest','mission')
                  for p in (ROOT / prefix / group).rglob('*.json')}
        r3 = {row['path'] for row in self.load('docs/maintenance/source-field-review-20261026.json')['definitions']}
        r4 = {prefix + 'quest/' + str(row['id']) + '.json'
              for row in self.load('src/test/resources/maintenance/r4-periodic-contracts.json')['definitions']}
        r5 = {row['path'] for row in self.load('src/test/resources/maintenance/r5-expedition-contracts.json')['definitions']}
        self.assertFalse(r3 & r4); self.assertFalse((r3 | r4) & r5)
        combined = r3 | r4 | r5
        self.assertFalse(combined - actual); self.assertEqual(148, len(actual))
        self.assertEqual(117, len(combined)); self.assertEqual(31, len(actual - combined))
        print('R5_REVIEW_COVERAGE ' + json.dumps(dict(definitions=len(actual),
            with_partial_evidence=len(combined), no_comparison_yet=len(actual-combined),
            whole_rule_verified=0, complete=False, unreviewed=sorted(actual-combined))))

if __name__ == '__main__': unittest.main()
