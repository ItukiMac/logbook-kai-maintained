"""Credentialed-role guards with synthetic local receipts; no network or publication."""
import copy,json,sys,unittest
from pathlib import Path
from unittest.mock import patch
from datetime import timedelta
from numeric_cycle_fixture import Fixture,A1
import routine_numeric_publish as pub
import routine_numeric_policy as p
import maintenance_workbench as w

class PublisherGuardTest(unittest.TestCase):
    def fixture(self):
        f=Fixture(self);r=f.synthetic_verified_receipt();return f,r
    def save(self,f,r):(f.out/'receipt.json').write_bytes(p.encoded(r))
    def test_valid_transfer_is_verified_without_checking_out_candidate(self):
        f,r=self.fixture();head=w.git(f.root,'rev-parse','HEAD');self.assertEqual(r,pub.verify_transfer(f.root,f.out,now=f.now));self.assertEqual(head,w.git(f.root,'rev-parse','HEAD'))
    def test_pr_other_repo_and_other_branch_cannot_publish(self):
        good={'GITHUB_REPOSITORY':pub.REPO,'GITHUB_REF':'refs/heads/master','GITHUB_EVENT_NAME':'push'}
        pub.master_context(good)
        for key,value in [('GITHUB_REPOSITORY','other/repo'),('GITHUB_REF','refs/heads/test'),('GITHUB_EVENT_NAME','pull_request'),('GITHUB_SERVER_URL','https://other.invalid')]:
            with self.subTest(key=key),self.assertRaises(ValueError):pub.master_context(dict(good,**{key:value}))
    def test_missing_verification_or_incorrect_summary_stops(self):
        for mode in ['unverified','profile','summary','revision','skip']:
            with self.subTest(mode=mode):
                f,r=self.fixture()
                if mode=='unverified':r['data_publication_eligible']=False
                elif mode=='profile':r['verification']['fixed_profiles']=[]
                elif mode=='summary':r['r3_summary_sha256']='0'*64
                else:
                    s=json.loads((f.out/'canonical-r3-summary.json').read_text());s['checkout' if mode=='revision' else 'java_tests']='0'*40 if mode=='revision' else {'tests':1094,'errors':0,'failures':0,'skipped':7}
                    raw=p.encoded(s);(f.out/'canonical-r3-summary.json').write_bytes(raw);r['r3_summary_sha256']=w.digest(raw)
                self.save(f,r)
                with self.assertRaises(ValueError):pub.verify_transfer(f.root,f.out,now=f.now)
    def test_expired_or_changed_source_receipts_stop(self):
        for mode in ['expired','url','hash']:
            f,r=self.fixture()
            if mode=='expired':r['evidence'][0]['checked_at']=(f.now-timedelta(days=2)).isoformat()
            elif mode=='url':r['evidence'][0]['url']='https://other.invalid/'
            else:r['evidence'][0]['evidence_sha256']='unknown'
            self.save(f,r)
            with self.assertRaises(ValueError):pub.verify_transfer(f.root,f.out,now=f.now)
    def test_bundle_pack_jar_and_scope_tampering_stop(self):
        for mode in ['candidate.bundle','logbook-data-pack.zip','verification-application.jar','scope']:
            f,r=self.fixture()
            if mode=='scope':r['changed_source_paths'].append('pom.xml');self.save(f,r)
            else:(f.out/mode).write_bytes(b'changed')
            with self.assertRaises(ValueError):pub.verify_transfer(f.root,f.out,now=f.now)
    def test_master_advance_stops_before_push_or_release(self):
        f,r=self.fixture();context={'GITHUB_REPOSITORY':pub.REPO,'GITHUB_REF':'refs/heads/master','GITHUB_EVENT_NAME':'push','GITHUB_RUN_NUMBER':'1'}
        with patch.object(pub,'gh_json',side_effect=[{'object':{'sha':'0'*40}},[]]),patch.object(pub,'latest_data_release',return_value=r['baseline']),patch.object(pub,'gh_write') as writes:
            with self.assertRaisesRegex(ValueError,'Master advanced'):pub.publish(f.root,f.out,context=context,now=f.now)
            writes.assert_not_called()
    def test_withdrawal_never_deletes_or_reverts_git(self):
        f,r=self.fixture();context={'GITHUB_REPOSITORY':pub.REPO,'GITHUB_REF':'refs/heads/master','GITHUB_EVENT_NAME':'push','GITHUB_RUN_ID':'9'}
        state={'run_id':'9','release_id':17,'tag':'data-v2026.10.30.1','source_commit':r['candidate_revision'],'pack_sha256':r['pack_sha256']}
        (f.out/'publication-draft.json').write_bytes(p.encoded(state));release={'tag_name':state['tag'],'target_commitish':state['source_commit'],'draft':False,'assets':[{'name':'logbook-data-pack.zip','digest':'sha256:'+state['pack_sha256']}]}
        with patch.object(pub,'gh_json',return_value=release),patch.object(pub,'gh_write',return_value={}) as write:
            result=pub.withdraw(f.out,context=context);self.assertTrue(result['withdrawn_to_retained_draft']);write.assert_called_once_with('PATCH','repos/'+pub.REPO+'/releases/17',{'draft':True,'make_latest':'false'})
        with self.assertRaises(ValueError):pub.withdraw(f.out,context=dict(context,GITHUB_RUN_ID='10'))
    def test_no_owned_release_is_a_preserving_noop(self):
        f=Fixture(self)
        with patch.object(pub,'gh_json') as read:
            self.assertTrue(pub.withdraw(f.evidence,context={'GITHUB_REPOSITORY':pub.REPO,'GITHUB_REF':'refs/heads/master','GITHUB_EVENT_NAME':'push'})['no_owned_publication_to_withdraw']);read.assert_not_called()

if __name__=='__main__':unittest.main()
