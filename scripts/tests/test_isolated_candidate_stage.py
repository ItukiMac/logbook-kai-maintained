import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime,timezone
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from stage_maintenance_candidates import stage
from maintenance_plan import plan

class IsolatedStageTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.base=Path(t.name)
        self.root=self.base/'repo';self.root.mkdir();self.out=self.base/'stage'
        self.paths=[]
        for cat in ('quest','eventquest','mission','map','kousyou','supplemental'):
            p=self.root/f'src/main/resources/logbook/{cat}/1.json';p.parent.mkdir(parents=True);p.write_text('{}')
            self.paths.append(p)
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        subprocess.run(['git','-C',str(self.root),'add','.'],check=True)
        subprocess.run(['git','-C',str(self.root),'-c','user.name=Fixture','-c','user.email=fixture@example.invalid','commit','-qm','fixture'],check=True)
        self.now=datetime(2026,10,26,tzinfo=timezone.utc)
        self.c=[{'path':str(self.paths[0].relative_to(self.root)),'content':'{"fixture":1}',
            'expected_old_sha256':hashlib.sha256(b'{}').hexdigest(),
            'upper_bound':dict(input_tokens=0,output_tokens=0,micro_usd=0),
            'evidence':[dict(url='https://wikiwiki.jp/kancolle/test',checked_at=self.now.isoformat(),summary='synthetic fixture only',evidence_sha256='a'*64)]}]
        self.b=dict(candidates=1,input_tokens=0,output_tokens=0,micro_usd=0)
        identity=plan(self.root,self.c,[],self.b,now=self.now)['entries'][0]['identity']
        self.a=[dict(identity=identity,decision='approve',reviewer='synthetic test only')]
    def run_stage(self):return stage(self.root,self.out,self.c,self.a,self.b,now=self.now)
    def test_stage_keeps_originals_and_requires_java_validation(self):
        result=self.run_stage()
        self.assertEqual([b'{}']*6,[p.read_bytes() for p in self.paths])
        self.assertEqual(b'{}',(self.out/'original/quest/1.json').read_bytes())
        self.assertEqual(self.c[0]['content'],(self.out/'candidate/quest/1.json').read_text())
        self.assertTrue(result['java_consumer_validation_required']);self.assertFalse(result['publish_allowed'])
        self.assertEqual(result['candidate_pack_sha256'],hashlib.sha256((self.out/'candidate-data-pack.zip').read_bytes()).hexdigest())
    def test_missing_approval_and_zero_candidate_budget_create_nothing(self):
        self.a=[]
        with self.assertRaises(ValueError):self.run_stage()
        self.assertFalse(self.out.exists())
    def test_changed_bytes_invalidate_approval(self):
        self.paths[0].write_text('{"new":1}')
        with self.assertRaises(ValueError):self.run_stage()
        self.assertFalse(self.out.exists())
    def test_changed_proposal_requires_new_approval(self):
        self.c[0]['content']='[]'
        with self.assertRaises(ValueError):self.run_stage()
        self.assertFalse(self.out.exists())
    def test_existing_output_and_nested_source_are_never_replaced(self):
        self.out.mkdir();marker=self.out/'keep';marker.write_text('preserve')
        with self.assertRaises(FileExistsError):self.run_stage()
        self.assertEqual('preserve',marker.read_text())
        self.out=self.root/'nested'
        with self.assertRaises(ValueError):self.run_stage()
        self.assertFalse(self.out.exists())
    def test_parent_traversal_cannot_hide_output_inside_source(self):
        (self.base/'outside').mkdir()
        self.out=self.base/'outside/../repo/nested'
        with self.assertRaisesRegex(ValueError,'Parent-traversal'):self.run_stage()
        self.assertFalse((self.root/'nested').exists())
        self.assertEqual([b'{}']*6,[p.read_bytes() for p in self.paths])
    def test_parent_traversal_source_alias_is_rejected_before_writes(self):
        (self.base/'outside').mkdir()
        self.root=self.base/'outside/../repo'
        with self.assertRaisesRegex(ValueError,'Parent-traversal'):self.run_stage()
        self.assertFalse(self.out.exists())
    def test_symlinked_output_parent_is_rejected(self):
        alias=self.base/'alias';alias.symlink_to(self.base,target_is_directory=True);self.out=alias/'stage'
        with self.assertRaises(ValueError):self.run_stage()
    def test_unrelated_malformed_definition_blocks_whole_pack(self):
        self.paths[-1].write_text('{bad')
        with self.assertRaises(ValueError):self.run_stage()
        self.assertFalse(self.out.exists())
    def test_source_change_during_build_leaves_no_completion_manifest(self):
        import stage_maintenance_candidates as module
        original=module.build_pack
        def changed(*args):
            result=original(*args);self.paths[-1].write_text('[]');return result
        with patch.object(module,'build_pack',changed),self.assertRaises(ValueError):self.run_stage()
        self.assertFalse((self.out/'stage.json').exists())

if __name__=='__main__':unittest.main()
