import hashlib
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from audit_game_json import parse_jsonc
ROOT=Path(__file__).resolve().parents[2]

class R9ArrivalInventoryTest(unittest.TestCase):
    def test_three_hash_bound_recurring_changes_and_retained_prior_identity(self):
        facts=json.loads((ROOT/'docs/maintenance/r9-rule-changes.json').read_text())
        self.assertEqual(3,len(facts['changes']));self.assertFalse(facts['production_applied'])
        self.assertFalse(facts['complete']);self.assertEqual(0,facts['whole_rule_verified'])
        contracts=json.loads((ROOT/'src/test/resources/maintenance/r6-yearly-contracts.json').read_text())
        for change in facts['changes']:
            path=ROOT/change['path'];self.assertEqual(change['after_sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertNotEqual(change['before_sha256'],change['after_sha256'])
            row=next(r for r in contracts['definitions'] if r['id']==int(path.stem))
            self.assertEqual(change['before_sha256'],row['r9_amendment']['previous_definition_sha256'])
            self.assertFalse(row['whole_rule_verified'])
    def test_only_one_six_condition_switches_to_arrival_and_targets_stay(self):
        for number,target in ((905,1),(912,1),(945,2)):
            body=parse_jsonc((ROOT/f'src/main/resources/logbook/quest/{number}.json').read_text())
            goals=[c for c in body['conditions'] if c.get('arrival')]
            self.assertEqual([dict(area=['1-6'],arrival=True,count=target)],goals)
            self.assertTrue(all(not c.get('arrival') for c in body['conditions'] if c.get('area')!=['1-6']))
    def test_seasonal_goals_are_not_silently_relabelled_as_current(self):
        for number in (906,954):
            body=parse_jsonc((ROOT/f'src/main/resources/logbook/eventquest/{number}.json').read_text())
            self.assertFalse(any(c.get('arrival') for c in body['conditions']))
            self.assertTrue(any(c.get('cells')==['B','D'] for c in body['conditions']))

if __name__=='__main__':unittest.main()
