import json
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile
from tools.build_data_pack import CATEGORIES, FORMAT, CAPABILITY_FORMAT, build

class DataPackCapabilitiesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root/'source'
        for cat in CATEGORIES: (self.source/cat).mkdir(parents=True)
    def pack(self, doc, category='quest'):
        path = self.source/category/'1.json'; path.write_text(json.dumps(doc))
        output = self.root/'pack.zip'; result = build(self.source, output, 'fixture')
        with ZipFile(output) as archive: manifest=json.loads(archive.read('manifest.json'))
        self.assertEqual(json.dumps(doc), path.read_text())
        return manifest
    def test_legacy_definitions_keep_legacy_format(self):
        m=self.pack({'filter':{'area':['1-1']}})
        self.assertEqual(FORMAT,m['format']); self.assertNotIn('required_capabilities',m)
    def test_deck_guard_requires_new_format(self):
        m=self.pack({'filter':{'deckIds':[1]}})
        self.assertEqual(CAPABILITY_FORMAT,m['format']); self.assertEqual(['quest-filter-deck-ids-v1'],m['required_capabilities'])
    def test_retreat_guard_and_event_quest_are_versioned(self):
        m=self.pack({'filter':{'deckIds':[1],'noRetreat':True}},'eventquest')
        self.assertEqual(['quest-filter-deck-ids-v1','quest-filter-no-retreat-v1'],m['required_capabilities'])
    def test_false_or_null_extended_field_is_not_disguised_as_old_schema(self):
        for value in (False,None):
            self.assertEqual(CAPABILITY_FORMAT,self.pack({'filter':{'noRetreat':value}})['format'])
    def test_nonsemantic_similarly_named_keys_do_not_change_format(self):
        self.assertEqual(FORMAT,self.pack({'description':{'deckIds':[1]}})['format'])
        self.assertEqual(FORMAT,self.pack({'filter':{'deckIds':[1]}},'supplemental')['format'])


class StrictPackJsonTest(unittest.TestCase):
    def test_duplicate_and_nonfinite_values_cannot_hide_capability_fields(self):
        from tools.build_data_pack import normalized_json
        for value in ('{"filter":{"deckIds":[1]},"filter":{}}', '{"x":NaN}', '{"x":1e999}'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                normalized_json(value.encode(), 'fixture')

if __name__ == '__main__': unittest.main()
