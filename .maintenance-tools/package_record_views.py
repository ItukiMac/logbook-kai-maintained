from pathlib import Path
import json
changed=[]
def edit(path,fn):
    p=Path(path);old=p.read_text();new=fn(old);assert old!=new,path;p.write_text(new);changed.append(path)
def add(path,text):
    p=Path(path);assert not p.exists(),path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);changed.append(path)
def once(s,a,b):
    assert s.count(a)==1,(a,s.count(a));return s.replace(a,b)

extension='''# Additive v2 packaging profile: existing three modules/classpath are retained.
# Presence of the immutable data API in the host is the profile discriminator.
RECORD_UI_PROFILE = 'record-ui-in-charts-v1'
RECORD_UI_API = 'logbook/plugin/api/RecordDataV1.class'
RECORD_UI_FAMILIES = ('BattleLogController', 'MissionLogController', 'CreateItemController',
    'BattleRecordCollect', 'BattleRecordDetail', 'BattleLogDetailAggregate',
    'MissionRecordCollect', 'MissionRecordDetail', 'MissionAggregate')
RECORD_UI_RESOURCES = ('battlelog', 'battlelog_dialog', 'missionlog', 'createitemlog')


def record_ui_entry(name):
    if name in {'logbook/gui/' + value + '.fxml' for value in RECORD_UI_RESOURCES}:
        return True
    return any(name == 'logbook/internal/gui/' + value + '.class' or
               name.startswith('logbook/internal/gui/' + value + '$') and name.endswith('.class')
               for value in RECORD_UI_FAMILIES)


def record_ui_required_entries():
    return {'logbook/internal/gui/' + value + '.class' for value in RECORD_UI_FAMILIES} | {
            'logbook/gui/' + value + '.fxml' for value in RECORD_UI_RESOURCES}


'''
def runner(s):
    s=once(s,'def required_entries(module_id):',extension+'def required_entries(module_id):')
    s=once(s,"    files = report.get('files')",'''    profile = report.get('record_ui_profile')
    if profile is not None and profile != RECORD_UI_PROFILE:
        raise ValueError('Unsupported record UI packaging profile')
    files = report.get('files')''')
    s=once(s,'def verify_jars(root, report, catalog):\n    classes, resources, total = set(), set(), 0','''def verify_jars(root, report, catalog):
    record_profile = report.get('record_ui_profile') == RECORD_UI_PROFILE
    classes, resources, total = set(), set(), 0''')
    s=once(s,"            if path == JARS[0]:\n                if SERVICE in names:",'''            if path == JARS[0]:
                if (RECORD_UI_API in names) != record_profile:
                    raise ValueError('Record data API and packaging profile disagree')
                if record_profile and any(record_ui_entry(name) for name in names):
                    raise ValueError('Record UI remains in the host instead of charts')
                if SERVICE in names:''')
    s=once(s,"            missing = required_entries(module['id']) - names",'''            required = required_entries(module['id'])
            if record_profile:
                if module['id'] == 'charts':
                    required |= record_ui_required_entries()
                elif any(record_ui_entry(name) for name in names):
                    raise ValueError('Record UI belongs to the charts module')
            missing = required - names''')
    return s
edit('scripts/run_feature_distribution.py',runner)

def builder(s):
    s=once(s,'GROUPS, EXTRA_CLASSES, required_entries)', 'GROUPS, EXTRA_CLASSES, required_entries, RECORD_UI_API, RECORD_UI_PROFILE,\n                                     record_ui_entry, record_ui_required_entries)')
    s=once(s,'def owner(name: str) -> str | None:', '''def owner(name: str, record_ui: bool = False) -> str | None:
    if record_ui and record_ui_entry(name):
        return 'charts' ''')
    # Remove only the insignificant trailing whitespace introduced above.
    s=s.replace("        return 'charts' \n", "        return 'charts'\n")
    s=once(s,'    for group in GROUPS:\n        for required in required_entries(group):', '''    record_ui = RECORD_UI_API in entries
    if record_ui:
        missing = record_ui_required_entries() - entries.keys()
        if missing:
            raise ValueError('Missing record UI input: ' + sorted(missing)[0])
    for group in GROUPS:
        for required in required_entries(group):''')
    s=once(s,'        group = owner(name)','        group = owner(name, record_ui)')
    s=once(s,"'extracted': {g: sorted(n for n in entries if owner(n) == g) for g in GROUPS}}", "'extracted': {g: sorted(n for n in entries if owner(n, record_ui) == g) for g in GROUPS}}\n    if record_ui:\n        manifest['record_ui_profile'] = RECORD_UI_PROFILE")
    return s
edit('scripts/build_feature_distribution.py',builder)

add('scripts/tests/test_record_distribution_profile.py',r'''"""New physical layout and old v2 layouts are both explicit; never relax hash/ownership checks."""
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
        with self.assertRaisesRegex(ValueError,'Record UI remains in the host'):verify(self.out)
    def test_build_is_deterministic_and_keeps_original_source(self):
        report=self.source();before=self.jar.read_bytes();again=build(self.jar,self.root/'another','a'*40)
        self.assertEqual(report,again);self.assertEqual(before,self.jar.read_bytes())
''')

# Use the actual distribution for the GUI test, not a hand-repacked proof archive.
def integration(s):
    s=once(s,'from build_feature_distribution import explicit_classpath_manifest','from build_feature_distribution import build\nfrom run_feature_distribution import RECORD_UI_PROFILE, record_ui_required_entries')
    start=s.index("            host = fixture / 'host-proof.jar'")
    end=s.index("            classes = fixture / 'probe-classes'",start)
    s=s[:start]+'''            distribution = fixture / 'distribution'
            report = build(original, distribution, 'a' * 40)
            self.assertEqual(RECORD_UI_PROFILE, report.get('record_ui_profile'))
            host = distribution / 'logbook-core.jar'
            feature = distribution / 'features/logbook-feature-charts.jar'
            with ZipFile(host) as core:
                self.assertFalse(any(record_entry(n) for n in core.namelist()), 'Hidden fallback remained in host')
            with ZipFile(feature) as records:
                self.assertTrue(record_ui_required_entries().issubset(records.namelist()))
            summary.update(product_distribution=True, record_ui_profile=RECORD_UI_PROFILE,
                           record_module='charts', original_distribution_source_unchanged=True)
'''+s[end:]
    s=once(s,"'shared_legacy_row_models_retained_in_core': ['BattleLogCollect', 'BattleLogDetail'],", "'shared_legacy_row_models_retained_in_core': ['BattleLogCollect', 'BattleLogDetail', 'MissionLogCollect', 'MissionLogDetail'],")
    s=s.replace("\"\"\"Record GUI runtime-package proof; not a new shipped bundle or production classloader policy.\"\"\"", "\"\"\"Actual chart-module record views; separate loader additionally detects accidental host fallback.\"\"\"")
    return s
edit('scripts/tests/test_record_ui_integration.py',integration)

Path('../evidence/packaging-paths.json').write_text(json.dumps(changed,indent=2)+'\n')
print('RECORD_PRODUCT_PACKAGING_PREPARED legacy_v2_supported=true modules_unchanged=3 records_module=charts')
