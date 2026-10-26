"""Hash-bound one-definition amendment; preserve incomplete whole-rule review."""
import hashlib
import json
import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_game_json import parse_jsonc
from maintenance_plan import source_allowed
ROOT = Path(__file__).resolve().parents[2]

class R8ReviewInventoryTest(unittest.TestCase):
    def setUp(self):
        self.facts = json.loads((ROOT/'docs/maintenance/r8-rule-changes.json').read_text())
        self.change = self.facts['changes'][0]
    def test_one_current_definition_with_retained_original_identity(self):
        self.assertEqual(1, len(self.facts['changes']))
        self.assertEqual('src/main/resources/logbook/quest/904.json', self.change['path'])
        self.assertEqual('787abdf56762fc8c86dd455b6a1412a06e1316518789906d8e9d7e3e72395534', self.change['before_sha256'])
        self.assertNotEqual(self.change['before_sha256'], self.change['after_sha256'])
        self.assertEqual(self.change['after_sha256'], hashlib.sha256((ROOT/self.change['path']).read_bytes()).hexdigest())
        self.assertFalse(self.facts['production_applied'])
    def test_map_rank_count_and_period_still_match_previous_contract(self):
        rule = parse_jsonc((ROOT/self.change['path']).read_text())
        self.assertEqual('イヤリー', rule['resetType']); self.assertEqual(2, rule['yearlyResetMonth'])
        self.assertEqual(['2-5','3-4','4-5','5-3'], rule['filter']['area'])
        self.assertEqual([dict(boss=True,area=[area],rank=['S'],count=1) for area in rule['filter']['area']], rule['conditions'])
        self.assertEqual({'area','fleet'}, set(rule['filter']))
    def test_amendment_does_not_discard_old_hash_or_claim_whole_completion(self):
        facts = json.loads((ROOT/'src/test/resources/maintenance/r6-yearly-contracts.json').read_text())
        row = next(r for r in facts['definitions'] if r['id']==904)
        self.assertEqual(self.change['after_sha256'], row['definition_sha256'])
        self.assertEqual(self.change['before_sha256'], row['r8_amendment']['previous_definition_sha256'])
        self.assertFalse(row['whole_rule_verified']); self.assertTrue(row['unresolved'])
        self.assertFalse(self.facts['complete']); self.assertEqual(0,self.facts['whole_rule_verified'])
    def test_sources_keep_article_dates_separate_from_read_date(self):
        self.assertEqual(2,len(self.change['sources']))
        for source in self.change['sources']:
            self.assertTrue(source_allowed(source['url']))
            self.assertLess(source['source_modified'],self.facts['checked_on'])
        self.assertEqual(['filter.fleet.conditions'],self.change['fields'])
        self.assertFalse(self.change['whole_rule_verified']); self.assertTrue(self.change['unresolved'])

if __name__ == '__main__': unittest.main()
