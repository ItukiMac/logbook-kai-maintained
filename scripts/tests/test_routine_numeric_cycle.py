import copy,json
from datetime import timedelta
from pathlib import Path
import sys,unittest
from zipfile import ZipFile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from numeric_cycle_fixture import Fixture,A1,FIFTEEN,RULE
import routine_numeric_policy as p
import routine_numeric_cycle as c
import maintenance_workbench as w

class NumericCycleTest(unittest.TestCase):
    def setUp(self):self.f=Fixture(self)
    def test_agreed_reviewed_values_reconcile_public_baseline_without_touching_original(self):
        before={n:w.digest(w.read_source(self.f.root,n)) for n in w.tracked(self.f.root)};result=self.f.prepare()
        self.assertEqual('unverified_test_candidate',result['status']);self.assertFalse(result['data_publication_eligible']);self.assertEqual({A1,FIFTEEN},set(result['changed_public_definitions']))
        self.assertEqual(before,{n:w.digest(w.read_source(self.f.root,n)) for n in w.tracked(self.f.root)})
        with ZipFile(self.f.out/'logbook-data-pack.zip') as z:
            self.assertEqual(b'{"untouched":true}',z.read('definitions/quest/1.json'));self.assertEqual(result['candidate_revision'],json.loads(z.read('manifest.json'))['source_revision'])
    def test_future_agreed_numeric_values_update_only_definition_and_fact_json(self):
        self.f.values['A1']['flagship_min_level']=6;self.f.capture();result=self.f.prepare()
        self.assertIn(A1,result['changed_source_paths']);self.assertTrue(set(result['changed_source_paths'])<={A1,p.FACTS,p.AUDIT})
        self.assertEqual(6,p.value(p.document((self.f.out/'candidate'/A1).read_bytes()),'flagship_min_level'))
    def test_conflict_stops_that_definition_without_selecting_one_source(self):
        right=copy.deepcopy(self.f.values);right['A1']['flagship_min_level']=6;self.f.capture(right)
        result=self.f.plan();self.assertNotIn(A1,result['selected']);self.assertIn(FIFTEEN,result['selected']);self.assertTrue(any(r['path']==A1 for r in result['blocked']))
    def test_source_change_without_matching_committed_facts_stops(self):
        file=self.f.root/A1;value=p.document(file.read_bytes());value['new_unreviewed']='no';file.write_bytes(p.encoded(value))
        result=self.f.plan();self.assertNotIn(A1,result['selected']);self.assertIn('committed numeric facts',next(r['reason'] for r in result['blocked'] if r['path']==A1))
    def test_unreviewed_public_structure_change_is_not_delivered(self):
        # A public root with a new nonnumeric field is not a recognized bootstrap.
        with ZipFile(self.f.baseline) as z:entries={n:z.read(n) for n in z.namelist()}
        doc=json.loads(entries['definitions/mission/1/A1.json']);doc['unreviewed']='do not erase';raw=p.encoded(doc);entries['definitions/mission/1/A1.json']=raw;m=json.loads(entries['manifest.json'])
        for row in m['files']:
            if row['path']=='definitions/mission/1/A1.json':row.update(bytes=len(raw),sha256=w.digest(raw))
        entries['manifest.json']=p.encoded(m)
        with ZipFile(self.f.baseline,'w') as z:
            for n,raw in entries.items():c.write_entry(z,n,raw)
        self.f.release['sha256']=w.digest(self.f.baseline.read_bytes());result=self.f.plan();self.assertNotIn(A1,result['selected'])
    def test_failed_stale_future_or_corrupt_source_cannot_create_candidate(self):
        self.f.save_evidence(False)
        with self.assertRaises(ValueError):self.f.prepare()
        self.assertFalse(self.f.out.exists());self.f.capture();self.f.entries[0]['checked_at']=(self.f.now-timedelta(days=2)).isoformat();self.f.save_evidence()
        with self.assertRaises(ValueError):self.f.prepare()
        self.f.capture();(self.f.evidence/'source-00.bin').write_bytes(b'changed')
        with self.assertRaises(ValueError):self.f.prepare()
    def test_pack_and_manifest_tampering_are_rejected(self):
        with self.assertRaises(ValueError):c.read_pack(self.f.baseline,'0'*64)
        with ZipFile(self.f.baseline,'a') as z:c.write_entry(z,'../outside',b'{}')
        with self.assertRaises(ValueError):c.read_pack(self.f.baseline,w.digest(self.f.baseline.read_bytes()))
    def test_existing_output_and_source_overlap_are_preserved(self):
        self.f.out.mkdir()
        with self.assertRaises(ValueError):self.f.prepare()
        self.assertTrue(self.f.out.is_dir())
        with self.assertRaises(ValueError):c.prepare(self.f.root,self.f.root/'inside',baseline=self.f.release,evidence_root=self.f.evidence,now=self.f.now,verify=False)
    def test_production_release_selection_matches_priority_and_rejects_untrusted_assets(self):
        def release(name='logbook-data-pack.zip',url=None,draft=False):return {'id':1,'tag_name':'data-v2026.10.25.1','draft':draft,'prerelease':False,'assets':[{'id':2,'name':name,'digest':'sha256:'+'a'*64,'size':100,'browser_download_url':url or 'https://github.com/'+c.REPO+'/releases/download/data-v2026.10.25.1/'+name}]}
        self.assertEqual('a'*64,c.latest_data_release([release(draft=True),release()])['sha256'])
        with self.assertRaises(ValueError):c.latest_data_release([release(url='https://untrusted.invalid/file')])
    def test_previous_failed_cycle_requires_review_but_readiness_or_pr_runs_do_not_lock(self):
        runs=[{'id':3,'head_branch':'master','event':'push','status':'in_progress'},{'id':2,'head_branch':'master','event':'push','status':'completed','conclusion':'failure'}]
        self.assertTrue(c.previous_failure(runs,3));runs[1]['conclusion']='cancelled';self.assertTrue(c.previous_failure(runs,3));runs[1]['conclusion']='success';self.assertFalse(c.previous_failure(runs,3))
