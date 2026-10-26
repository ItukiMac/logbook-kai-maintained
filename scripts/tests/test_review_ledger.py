import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from datetime import datetime,timezone
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from definition_review_ledger import review, selected

class ReviewLedgerTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
        self.name='src/main/resources/logbook/mission/1/A1.json';p=self.root/self.name;p.parent.mkdir(parents=True)
        p.write_text('{"conditions":[{"type":"艦娘","order":1,"level":20}]}')
        self.facts={'format':'logbook-field-review-v1','checked_at':'2026-10-26T00:00:00+00:00','definitions':[
            {'path':self.name,'source_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'fields':[
             {'field':'flagship_min_level','observations':[{'url':'https://wikiwiki.jp/kancolle/test','value':[5]},
                                                         {'url':'https://zekamashi.net/test/','value':[5]}]}]}]}
        self.now=datetime(2026,10,26,tzinfo=timezone.utc)
    def test_difference_does_not_become_whole_rule_certification(self):
        r=review(self.root,[self.name],self.facts,now=self.now)
        self.assertEqual('needs_correction_review',r['entries'][0]['status']);self.assertFalse(r['complete'])
    def test_source_disagreement_is_held_not_majority_voted(self):
        self.facts['definitions'][0]['fields'][0]['observations'][1]['value']=[20]
        self.assertEqual('source_conflict',review(self.root,[self.name],self.facts,now=self.now)['entries'][0]['status'])
    def test_source_changes_invalidate_previous_review(self):
        (self.root/self.name).write_text('{"conditions":[]}')
        self.assertEqual('stale_review',review(self.root,[self.name],self.facts,now=self.now)['entries'][0]['status'])
    def test_empty_inventory_is_not_complete_and_orphan_visible(self):
        r=review(self.root,[],self.facts,now=self.now)
        self.assertFalse(r['complete']);self.assertEqual([self.name],r['orphaned_review_records'])
    def test_event_quests_included_and_user_custom_data_excluded(self):
        self.assertTrue(selected('src/main/resources/logbook/eventquest/441.json'))
        self.assertFalse(selected('config/customquest/441.json'))
    def test_old_and_future_review_timestamps_are_stale(self):
        for stamp in ('2026-01-01T00:00:00+00:00','2026-10-27T00:00:00+00:00'):
            self.facts['checked_at']=stamp
            self.assertEqual('stale_review',review(self.root,[self.name],self.facts,now=self.now)['entries'][0]['status'])

if __name__=='__main__':unittest.main()
