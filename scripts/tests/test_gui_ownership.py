import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from check_gui_ownership import check

class GuiOwnershipTest(unittest.TestCase):
    def setUp(self):
        self.root=Path(__file__).resolve().parents[2]
        self.catalog=json.loads((self.root/'docs/maintenance/gui-ownership.json').read_text())
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.file=Path(self.temp.name)/'catalog.json'
    def run_catalog(self,data):
        self.file.write_text(json.dumps(data));return check(self.root,self.file)
    def test_actual_complete_source_has_zero_unclassified_and_matches_packaging(self):
        report=self.run_catalog(self.catalog)
        self.assertEqual(95,report['components']);self.assertEqual(0,report['unclassified']);self.assertEqual(57,report['owners']['host'])
    def test_missing_component_is_not_silently_assigned_to_host(self):
        self.catalog['components'].pop()
        with self.assertRaises(ValueError):self.run_catalog(self.catalog)
    def test_duplicate_and_stale_component_rejected(self):
        for row in [copy.deepcopy(self.catalog['components'][0]),{'path':'src/main/java/logbook/internal/gui/Gone.java'}]:
            data=copy.deepcopy(self.catalog);data['components'].append(row)
            with self.assertRaises((ValueError,FileNotFoundError)):self.run_catalog(data)
    def test_wrong_packaging_owner_is_refused(self):
        self.catalog['components'][0]['owner']='wrong'
        with self.assertRaises(ValueError):self.run_catalog(self.catalog)
    def test_host_requires_reason_and_real_verification_links(self):
        for key,value in [('reason',''),('verification',[]),('verification',['src/test/MissingTest.java'])]:
            data=copy.deepcopy(self.catalog);data['components'][0][key]=value
            with self.assertRaises((ValueError,FileNotFoundError)):self.run_catalog(data)
