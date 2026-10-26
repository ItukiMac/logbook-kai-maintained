import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from tools.build_data_pack import build, CATEGORIES

class ArrivalPackCapabilityTest(unittest.TestCase):
    def manifest(self, body, category='quest'):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)/'source'
            for name in CATEGORIES:(root/name).mkdir(parents=True)
            (root/category/'1.json').write_text(json.dumps(body))
            out=Path(tmp)/'new.zip';build(root,out,'fixture')
            with ZipFile(out) as archive:return json.loads(archive.read('manifest.json'))
    def test_arrival_declaration_for_true_false_and_null(self):
        for value in (True,False,None):
            m=self.manifest({'conditions':[{'arrival':value}]})
            self.assertEqual('logbook-data-pack-v2',m['format'])
            self.assertEqual(['quest-port-arrival-v1'],m['required_capabilities'])
    def test_event_conditions_share_capability_but_unrelated_metadata_does_not(self):
        self.assertEqual(['quest-port-arrival-v1'],self.manifest({'conditions':[{'arrival':True}]},'eventquest')['required_capabilities'])
        self.assertEqual('logbook-data-pack-v1',self.manifest({'description':{'arrival':True}})['format'])
    def test_combined_capabilities_are_all_declared(self):
        m=self.manifest({'filter':{'deckIds':[1],'noRetreat':True},'conditions':[{'arrival':True}]})
        self.assertEqual(['quest-filter-deck-ids-v1','quest-filter-no-retreat-v1','quest-port-arrival-v1'],m['required_capabilities'])

if __name__=='__main__':unittest.main()
