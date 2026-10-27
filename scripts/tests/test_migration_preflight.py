import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runtime_snapshot import snapshot, restore, Limits
from migration_preflight import preflight


class MigrationPreflightTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup)
        self.root=Path(t.name)
        self.live=self.root/'original';self.live.mkdir()
        (self.live/'config').mkdir();(self.live/'battlelog').mkdir();(self.live/'plugins').mkdir()
        (self.live/'sortie-arrivals-v1').mkdir()
        (self.live/'sortie-arrivals-v1'/'observed.json').write_text('{"fixture":true}')
        self.snapshot=self.root/'snapshot';self.clone=self.root/'clone'
        self.limits=Limits(reserve=0)
    def capture(self,paths):
        (self.live/'config/logbook.bean.AppConfig.json').write_text(json.dumps(paths))
        result=snapshot(self.live,self.snapshot,stopped=True,limits=self.limits)
        return result['manifest_sha256']
    def test_valid_snapshot_detects_relative_paths_without_declaring_launch_safe(self):
        sha=self.capture({'reportPath':'battlelog','pluginsDir':'plugins'})
        report=preflight(self.snapshot,sha,limits=self.limits)
        self.assertTrue(report['snapshot_verified']);self.assertEqual([],report['path_issues'])
        self.assertFalse(report['ready_for_launch']);self.assertFalse(report['ready_for_production'])
        self.assertFalse(report['cross_schema_migration_verified'])
    def test_external_absolute_and_missing_references_remain_blockers(self):
        sha=self.capture({'reportPath':'/old/app/logs','pluginsDir':'plugins',
                          'battleLogDir':'missing','captureDir':'C:\\Old\\captures'})
        report=preflight(self.snapshot,sha,limits=self.limits)
        issues={i['field']:i['status'] for i in report['path_issues']}
        self.assertIn('reportPath',issues);self.assertIn('battleLogDir',issues)
        self.assertIn('captureDir',issues)
        self.assertFalse(report['ready_for_launch'])
    def test_snapshot_and_restore_verify_same_bytes_before_reporting(self):
        sha=self.capture({'reportPath':'battlelog'})
        restore(self.snapshot,self.clone,sha,limits=self.limits)
        report=preflight(self.snapshot,sha,restored_copy=self.clone,limits=self.limits)
        self.assertTrue(report['restore_copy_verified'])
        (self.clone/'data/sortie-arrivals-v1/observed.json').write_text('tampered')
        with self.assertRaises(ValueError):preflight(self.snapshot,sha,restored_copy=self.clone,limits=self.limits)
    def test_wrong_manifest_hash_is_rejected(self):
        sha=self.capture({})
        with self.assertRaises(ValueError):preflight(self.snapshot,'0'*64,limits=self.limits)
    def test_wrong_restore_receipt_is_rejected(self):
        sha=self.capture({})
        restore(self.snapshot,self.clone,sha,limits=self.limits)
        p=self.clone/'restore.json';r=json.loads(p.read_text());r['snapshot_manifest_sha256']='0'*64
        p.write_text(json.dumps(r))
        with self.assertRaises(ValueError):preflight(self.snapshot,sha,restored_copy=self.clone,limits=self.limits)

if __name__=='__main__':unittest.main()
