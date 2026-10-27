"""New physical layout and old v2 layouts are both explicit; never relax hash/ownership checks."""
import hashlib
import json
from pathlib import Path
import unittest
from zipfile import ZipFile
import test_feature_distribution as fixture
from build_feature_distribution import build
from run_feature_distribution import (verify, RECORD_UI_API, RECORD_UI_PROFILE,
    record_ui_entry, record_ui_required_entries)

class RecordDistributionProfileTest(unittest.TestCase):
    def setUp(self):
        fixture.FeatureDistributionTest.setUp(self)
    def source(self, modern=True):
        if modern:
            self.entries[RECORD_UI_API]=b'synthetic API marker'
            for name in record_ui_required_entries():self.entries[name]=b'synthetic record entry'
            self.entries['logbook/internal/gui/BattleLogController$UnitDialog.class']=b'nested dialog'
        fixture.FeatureDistributionTest.write(self)
        return build(self.jar,self.out,'a'*40)
    def manifest(self):return json.loads((self.out/'distribution.json').read_text())
    def save(self,report):(self.out/'distribution.json').write_text(json.dumps(report))
    def rewrite(self,name,transform):
        path=self.out/name
        with ZipFile(path) as z:entries={i.filename:z.read(i) for i in z.infolist()}
        transform(entries)
        # Synthetic fixture only. The package builder itself always uses new directories.
        with ZipFile(path,'w') as z:
            for key,value in entries.items():z.writestr(key,value)
        digest=hashlib.sha256(path.read_bytes()).hexdigest();report=self.manifest();report['files'][name]=digest
        if name=='logbook-core.jar':report['host']['sha256']=digest
        else:
            for module in report['modules']:
                if module['path']==name:module['sha256']=digest
        self.save(report)
    def test_new_profile_moves_all_record_views_and_nested_dialog_to_charts(self):
        report=self.source();self.assertEqual(RECORD_UI_PROFILE,report['record_ui_profile']);verify(self.out)
        with ZipFile(self.out/'logbook-core.jar') as core:
            self.assertIn(RECORD_UI_API,core.namelist());self.assertFalse(any(record_ui_entry(n) for n in core.namelist()))
        with ZipFile(self.out/'features/logbook-feature-charts.jar') as charts:
            self.assertTrue(record_ui_required_entries().issubset(charts.namelist()))
            self.assertIn('logbook/internal/gui/BattleLogController$UnitDialog.class',charts.namelist())
    def test_legacy_v2_without_the_data_api_keeps_its_previous_layout(self):
        self.entries['logbook/internal/gui/BattleLogController.class']=b'legacy controller'
        report=self.source(False);self.assertNotIn('record_ui_profile',report);verify(self.out)
        with ZipFile(self.out/'logbook-core.jar') as core:self.assertIn('logbook/internal/gui/BattleLogController.class',core.namelist())
    def test_profile_cannot_be_removed_to_bypass_new_ownership_checks(self):
        report=self.source();report.pop('record_ui_profile');self.save(report)
        with self.assertRaisesRegex(ValueError,'API and packaging profile'):verify(self.out)
    def test_profile_cannot_be_forged_on_an_old_host(self):
        report=self.source(False);report['record_ui_profile']=RECORD_UI_PROFILE;self.save(report)
        with self.assertRaisesRegex(ValueError,'API and packaging profile'):verify(self.out)
    def test_unknown_profile_is_rejected(self):
        report=self.source();report['record_ui_profile']='future-unknown';self.save(report)
        with self.assertRaisesRegex(ValueError,'Unsupported record UI'):verify(self.out)
    def test_missing_record_ui_is_rejected_before_creating_output(self):
        self.entries[RECORD_UI_API]=b'new API';fixture.FeatureDistributionTest.write(self)
        with self.assertRaisesRegex(ValueError,'Missing record UI input'):build(self.jar,self.out,'a'*40)
        self.assertFalse(self.out.exists())
    def test_a_rehashed_module_cannot_omit_a_required_record_class(self):
        self.source();name='logbook/internal/gui/BattleRecordDetail.class'
        self.rewrite('features/logbook-feature-charts.jar',lambda entries:entries.pop(name))
        with self.assertRaisesRegex(ValueError,'Missing required feature entry'):verify(self.out)
    def test_core_fallback_cannot_hide_the_absence_of_a_feature_class(self):
        self.source();name='logbook/internal/gui/BattleRecordDetail.class'
        self.rewrite('features/logbook-feature-charts.jar',lambda entries:entries.pop(name))
        self.rewrite('logbook-core.jar',lambda entries:entries.update({name:b'fallback'}))
        # Make all hashes/metadata otherwise coherent, so this asserts ownership,
        # not an earlier unrelated rejection by the exact-host guard.
        report=self.manifest();host_hash=report['host']['sha256']
        for module in report['modules']:module['host_sha256']=host_hash
        self.save(report)
        def rebind(entries):
            metadata=json.loads(entries['META-INF/logbook/feature.json'])
            metadata['host_sha256']=host_hash
            entries['META-INF/logbook/feature.json']=json.dumps(metadata).encode()
        for module in report['modules']:self.rewrite(module['path'],rebind)
        with self.assertRaisesRegex(ValueError,'Record UI remains in the host'):verify(self.out)
    def test_build_is_deterministic_and_keeps_original_source(self):
        report=self.source();before=self.jar.read_bytes();again=build(self.jar,self.root/'another','a'*40)
        self.assertEqual(report,again);self.assertEqual(before,self.jar.read_bytes())
