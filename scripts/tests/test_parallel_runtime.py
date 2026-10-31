from pathlib import Path
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import prepare_parallel_runtime as parallel
import test_feature_bundle_manager as fixtures

class ParallelRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.fixture=fixtures.BundleManagerTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
    def test_new_store_and_launcher_do_not_modify_legacy(self):
        legacy=self.fixture.base/'legacy';legacy.mkdir();(legacy/'record.csv').write_text('old-data')
        out=self.fixture.base/'kai2 new'
        with patch.object(parallel,'port_free') as ports:
            report=parallel.prepare(self.fixture.a,out)
        self.assertEqual(ports.call_count,2);self.assertFalse(report['legacy_modified'])
        self.assertIn('--bridge-port 8892',(out/'launch-kai2.sh').read_text())
        self.assertIn('--proxy-port 8889',(out/'launch-kai2.sh').read_text())
        self.assertEqual((legacy/'record.csv').read_text(),'old-data')
        self.assertTrue((out/'runtime/home').is_dir())
        with patch.object(parallel,'port_free'):
            with self.assertRaises(FileExistsError):parallel.prepare(self.fixture.a,out)
    def test_busy_or_conflicting_ports_refuse_before_writing(self):
        out=self.fixture.base/'new'
        with self.assertRaises(ValueError):parallel.prepare(self.fixture.a,out,8891,8889)
        with patch.object(parallel,'port_free',side_effect=OSError('busy')):
            with self.assertRaises(OSError):parallel.prepare(self.fixture.a,out)
        self.assertFalse(out.exists())
