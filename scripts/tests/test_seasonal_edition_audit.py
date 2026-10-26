import copy
from datetime import date
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from audit_seasonal_editions import audit, REGISTRY
ROOT=Path(__file__).resolve().parents[2]

class SeasonalEditionAuditTest(unittest.TestCase):
    def test_all_thirteen_observations_remain_unconfirmed_not_autoactivated(self):
        result=audit(ROOT,date(2026,10,26))
        self.assertEqual(13,len(result['entries']))
        self.assertEqual(7,result['counts']['historical_only'])
        self.assertEqual(6,result['counts']['same_year_lifecycle_unverified'])
        self.assertEqual(0,result['certified_active_rules'])
        self.assertFalse(result['complete']);self.assertFalse(result['active_selection_supported'])
        self.assertTrue(all(r['active'] is None for r in result['entries']))
    def test_changed_hash_requires_recomparison_not_year_based_acceptance(self):
        registry=json.loads((ROOT/REGISTRY).read_text());registry['entries'][0]['definition_sha256']='0'*64
        result=audit(ROOT,date(2026,10,26),registry)
        self.assertEqual(1,result['counts']['requires_recomparison'])
    def test_future_and_same_year_are_not_proof_of_current_lifecycle(self):
        registry=json.loads((ROOT/REGISTRY).read_text());registry['entries']=registry['entries'][:1]
        year=registry['entries'][0]['edition_year']
        self.assertEqual(1,audit(ROOT,date(year-1,1,1),registry)['counts']['future_edition_not_active'])
        self.assertEqual(1,audit(ROOT,date(year,1,1),registry)['counts']['same_year_lifecycle_unverified'])
    def test_source_bytes_are_unchanged_and_invalid_scope_cannot_hide_errors(self):
        registry=json.loads((ROOT/REGISTRY).read_text());p=ROOT/registry['entries'][0]['path'];before=p.read_bytes()
        audit(ROOT,date(2026,10,26));self.assertEqual(before,p.read_bytes())
        bad=copy.deepcopy(registry);bad['entries'][0]['path']='config/private.json'
        self.assertEqual(1,audit(ROOT,date(2026,10,26),bad)['counts']['error'])
        with self.assertRaises(ValueError):audit(ROOT,date(2026,10,26),{'format':registry['format'],'entries':[]})

if __name__=='__main__':unittest.main()
