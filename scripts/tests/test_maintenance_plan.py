import sys,unittest,tempfile,hashlib
from pathlib import Path
from datetime import datetime,timezone,timedelta
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from maintenance_plan import plan,source_allowed

class MaintenancePlanTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        self.now=datetime(2026,10,26,tzinfo=timezone.utc)
        self.limit={'candidates':1,'input_tokens':10,'output_tokens':10,'micro_usd':10}
    def candidate(self,name='1.json',content='{}'):
        return {'path':'src/main/resources/logbook/quest/'+name,'expected_old_sha256':None,'content':content,
                'upper_bound':{'input_tokens':2,'output_tokens':3,'micro_usd':5},
                'evidence':[{'url':'https://wikiwiki.jp/kancolle/任務','checked_at':self.now.isoformat(),'summary':'reviewed fixture','evidence_sha256':'a'*64}]}
    def run_plan(self,values,approvals=None,previous=None):return plan(self.root,values,approvals or [],self.limit,previous=previous,now=self.now)
    def test_budget_is_reserved_before_processing_and_resume_advances(self):
        candidates=[self.candidate(),self.candidate('2.json')];a=self.run_plan(candidates)
        self.assertEqual(1,a['counts']['pending_budget']);b=self.run_plan(candidates,previous=a)
        self.assertEqual(0,b['counts']['pending_budget']);self.assertTrue(b['entries'][0]['reused']);self.assertEqual(5,b['used']['micro_usd'])
    def test_approval_is_bound_to_all_bytes_and_source_evidence(self):
        candidate=self.candidate();first=self.run_plan([candidate]);identity=first['entries'][0]['identity']
        approval={'identity':identity,'decision':'approve','reviewer':'test reviewer'}
        approved=self.run_plan([candidate],[approval],first);self.assertTrue(approved['ready_for_isolated_validation'])
        self.assertFalse(approved['applies_changes']);self.assertFalse(approved['calls_paid_apis'])
        candidate['content']='[]';changed=self.run_plan([candidate],[approval],approved)
        self.assertEqual('requires_approval',changed['entries'][0]['status'])
    def test_invalid_first_candidate_does_not_starve_resumption(self):
        candidates=[self.candidate(content='{broken'),self.candidate('2.json')]
        first=self.run_plan(candidates);second=self.run_plan(candidates,previous=first)
        self.assertEqual('error',second['entries'][0]['status']);self.assertEqual(0,second['counts']['pending_budget'])
    def test_stale_source_and_future_evidence_never_approved(self):
        for stamp in (self.now-timedelta(days=31),self.now+timedelta(days=1)):
            candidate=self.candidate();candidate['evidence'][0]['checked_at']=stamp.isoformat()
            self.assertEqual('requires_source_review',self.run_plan([candidate])['entries'][0]['status'])
    def test_unsafe_runtime_duplicate_and_missing_budget_rejected(self):
        for path in ('config/private.json','src/main/resources/logbook/quest/../x.json'):
            candidate=self.candidate();candidate['path']=path
            self.assertEqual('error',self.run_plan([candidate])['entries'][0]['status'])
        candidates=[self.candidate(),self.candidate()];self.assertEqual(1,self.run_plan(candidates)['counts']['error'])
        candidate=self.candidate();del candidate['upper_bound'];self.assertEqual('error',self.run_plan([candidate])['entries'][0]['status'])
    def test_changed_source_and_symlink_cannot_bypass_hash_lease(self):
        candidate=self.candidate();file=self.root/candidate['path'];file.parent.mkdir(parents=True);file.write_text('[]')
        before=file.read_bytes();self.assertEqual('error',self.run_plan([candidate])['entries'][0]['status']);self.assertEqual(before,file.read_bytes())
    def test_paid_work_is_blocked_by_zero_default_budget(self):
        self.limit.update(input_tokens=0,output_tokens=0,micro_usd=0)
        result=self.run_plan([self.candidate()]);self.assertEqual(1,result['counts']['pending_budget']);self.assertTrue(all(v==0 for v in result['used'].values()))
    def test_source_allowlist_rejects_spoofs_and_credentials(self):
        for url in ('https://wikiwiki.jp.evil/kancolle/任務','http://zekamashi.net/x','https://u:p@zekamashi.net/x','https://x.com/random/status/1'):
            self.assertFalse(source_allowed(url))
        self.assertTrue(source_allowed('https://x.com/KanColle_STAFF/status/123'))
    def test_empty_scope_is_not_complete(self):self.assertFalse(self.run_plan([])['ready_for_isolated_validation'])

    def test_repaired_missing_estimates_invalidate_cached_error(self):
        candidate=self.candidate();bounds=candidate.pop('upper_bound');first=self.run_plan([candidate])
        self.assertEqual('error',first['entries'][0]['status']);candidate['upper_bound']=bounds
        second=self.run_plan([candidate],previous=first)
        self.assertEqual('requires_approval',second['entries'][0]['status'])
        self.assertFalse(second['entries'][0].get('reused',False));self.assertEqual(5,second['used']['micro_usd'])
    def test_repaired_invalid_estimates_invalidate_cached_error(self):
        candidate=self.candidate();candidate['upper_bound']['input_tokens']=-1;first=self.run_plan([candidate])
        self.assertEqual('error',first['entries'][0]['status']);candidate['upper_bound']['input_tokens']=2
        self.assertEqual('requires_approval',self.run_plan([candidate],previous=first)['entries'][0]['status'])
    def test_changed_estimates_recheck_budget_without_discarding_other_progress(self):
        candidates=[self.candidate(),self.candidate('2.json')]
        first=self.run_plan(candidates);candidates[0]['upper_bound']['micro_usd']=11
        second=self.run_plan(candidates,previous=first)
        self.assertEqual('pending_budget',second['entries'][0]['status'])
        self.assertEqual('requires_approval',second['entries'][1]['status'])
        self.assertEqual(5,second['used']['micro_usd'])

if __name__=='__main__':unittest.main()
