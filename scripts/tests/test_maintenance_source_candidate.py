import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import maintenance_workbench as w
import maintenance_source_candidate as c
import test_maintenance_workbench as fixtures

class SourceCandidateTest(unittest.TestCase):
    setUp=fixtures.WorkbenchTest.setUp
    state=fixtures.WorkbenchTest.state
    capture=fixtures.WorkbenchTest.capture
    def proposal(self,path='docs/change.txt',content='reviewable change\n',old=None):
        return [{'path':path,'expected_old_sha256':old,'content':content}]
    def test_ordinary_proposal_stages_exact_source_and_preserves_original(self):
        r=self.capture();before=self.state();out=self.root/'candidate';changes=self.proposal()
        result=c.stage(self.out,r['checkpoint_sha256'],changes,out,protected=[self.repo])
        self.assertEqual('reviewable change\n',(out/'docs/change.txt').read_text())
        self.assertEqual(before,self.state());self.assertFalse(result['tests_executed']);self.assertFalse(result['publication_allowed'])
        self.assertFalse(w.git(out,'status','--porcelain').strip())
    def test_runtime_requires_specific_approval_before_output_exists(self):
        r=self.capture();out=self.root/'candidate'
        with self.assertRaises(ValueError):c.stage(self.out,r['checkpoint_sha256'],self.proposal('src/main/java/Runtime.java'),out)
        self.assertFalse(out.exists())
    def test_approved_runtime_source_can_stage_only_into_new_checkout(self):
        r=self.capture();changes=self.proposal('src/main/java/Runtime.java');p=c.prepare(self.out,r['checkpoint_sha256'],changes)
        approval={'identity':p['identity'],'decision':'approve','reviewer':'explicit test review'}
        result=c.stage(self.out,r['checkpoint_sha256'],changes,self.root/'candidate',approval=approval,protected=[self.repo])
        self.assertTrue(result['high_risk']);self.assertFalse(result['tests_executed'])
    def test_changed_content_invalidates_approval(self):
        r=self.capture();changes=self.proposal('scripts/tool.py');p=c.prepare(self.out,r['checkpoint_sha256'],changes)
        changes[0]['content']='different source'
        with self.assertRaises(ValueError):c.stage(self.out,r['checkpoint_sha256'],changes,self.root/'candidate',approval={'identity':p['identity'],'decision':'approve','reviewer':'review'})
        self.assertFalse((self.root/'candidate').exists())
    def test_source_base_hash_mismatch_refuses_before_restore(self):
        r=self.capture()
        with self.assertRaises(ValueError):c.stage(self.out,r['checkpoint_sha256'],self.proposal('src/value.txt',old='a'*64),self.root/'candidate')
        self.assertFalse((self.root/'candidate').exists())
    def test_existing_source_update_checks_before_and_after_hashes(self):
        r=self.capture();old=w.digest((self.repo/'scripts/check.py').read_bytes());changes=self.proposal('scripts/check.py',"print('CANDIDATE_RESUMED_OK')\n",old)
        p=c.prepare(self.out,r['checkpoint_sha256'],changes)
        c.stage(self.out,r['checkpoint_sha256'],changes,self.root/'candidate',approval={'identity':p['identity'],'decision':'approve','reviewer':'review'})
        run=subprocess.run([sys.executable,'scripts/check.py'],cwd=self.root/'candidate',text=True,capture_output=True,check=True)
        self.assertEqual('CANDIDATE_RESUMED_OK',run.stdout.strip())
    def test_duplicate_and_parent_collision_refused(self):
        r=self.capture()
        for changes in [self.proposal()*2,self.proposal('src/value.txt/child')]:
            with self.assertRaises(ValueError):c.prepare(self.out,r['checkpoint_sha256'],changes)
    def test_private_paths_and_traversal_refused(self):
        r=self.capture()
        for name in ['config/private.json','docs/../escape','/docs/absolute','docs/.git/config','C:/docs/outside']:
            with self.subTest(name=name),self.assertRaises(ValueError):c.prepare(self.out,r['checkpoint_sha256'],self.proposal(name))
    def test_executable_and_workflow_changes_are_high_risk(self):
        r=self.capture()
        for name in ['.github/workflows/new.yml','scripts/tool.py','tools/tool.py','src/main/resources/logbook/game/test.json']:
            self.assertTrue(c.prepare(self.out,r['checkpoint_sha256'],self.proposal(name))['high_risk'])
    def test_binary_oversize_empty_and_unknown_command_keys_refused(self):
        r=self.capture()
        for changes in [[],self.proposal(content='nul\0byte'),self.proposal(content='x'*(2*1024*1024+1)),[{**self.proposal()[0],'command':'untrusted'}]]:
            with self.assertRaises(ValueError):c.prepare(self.out,r['checkpoint_sha256'],changes)
    def test_verify_cannot_execute_without_explicit_runner_assertion(self):
        with patch.object(c,'execute_check') as run:
            with self.assertRaises(ValueError):c.verify_candidate(self.repo,{})
            run.assert_not_called()
    def test_fixed_verification_stops_on_first_failure_and_does_not_claim_success(self):
        r=self.capture();result=c.stage(self.out,r['checkpoint_sha256'],self.proposal(),self.root/'candidate')
        with patch.object(w,'measure_environment',return_value=self.tools),patch.object(c,'execute_check',side_effect=subprocess.CalledProcessError(1,['fixed-check'])) as run:
            with self.assertRaises(subprocess.CalledProcessError):c.verify_candidate(self.root/'candidate',result,isolated_runner=True)
        self.assertEqual(1,run.call_count);self.assertFalse(result['tests_executed'])
    def test_checks_that_mutate_candidate_source_cannot_issue_verified_receipt(self):
        r=self.capture();before=self.state();out=self.root/'candidate'
        result=c.stage(self.out,r['checkpoint_sha256'],self.proposal(),out)
        def mutate(command,root):
            (root/'docs/change.txt').write_text('changed during verification')
            return subprocess.CompletedProcess(command,0)
        with patch.object(w,'measure_environment',return_value=self.tools),patch.object(c,'execute_check',side_effect=mutate):
            with self.assertRaises(ValueError):c.verify_candidate(out,result,isolated_runner=True)
        self.assertFalse(result['tests_executed']);self.assertEqual(before,self.state())
