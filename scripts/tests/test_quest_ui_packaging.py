"""Synthetic archives only: verify additive profile ownership without changing retained legacy bundles."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from zipfile import ZipFile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_feature_distribution import build, write_jar
from run_feature_distribution import (GROUPS, PROVIDERS, JARS, FEATURE_METADATA, SERVICE,
    QUEST_UI_API, QUEST_UI_PROFILE, quest_ui_required_entries, required_entries, verify)

class QuestPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
    def source(self,modern=True,missing=None):
        entries={'META-INF/MANIFEST.MF':b'Manifest-Version: 1.0\r\n\r\n',
                 SERVICE:('\n'.join(PROVIDERS.values())+'\n').encode()}
        for service in ('logbook.plugin.lifecycle.StartUp', 'logbook.listener.ContentListenerSpi'):
            entries['META-INF/services/' + service] = (b'local.kancolle.bridge.KancolleBridgeStartUp\n' if service == 'logbook.plugin.lifecycle.StartUp' else b'') + b'local.kancolle.kqs.KqsLogbookPlugin\n'
        for group in GROUPS:
            entries.update({name:b'synthetic' for name in required_entries(group)})
        entries.update({name:b'synthetic quest UI' for name in quest_ui_required_entries()})
        if modern:entries[QUEST_UI_API]=b'synthetic host API'
        if missing:entries.pop(missing)
        path=self.root/'input.jar';write_jar(path,entries);return path
    def bundle(self,modern=True):
        source=self.source(modern);return self.root/'bundle',build(source,self.root/'bundle','a'*40)
    def tamper(self,root,changes):
        # Only new test-generated copies. Recompute all hashes so ownership failures
        # cannot be accidentally satisfied by the earlier hash-mismatch guard.
        out=self.root/'tampered';shutil.copytree(root,out)
        report=json.loads((out/'distribution.json').read_text())
        contents={}
        for path in JARS:
            with ZipFile(out/path) as z:contents[path]={n:z.read(n) for n in z.namelist()}
        for path,edit in changes.items():edit(contents[path])
        core=out/JARS[0]
        with ZipFile(core,'w') as z:
            for name,data in contents[JARS[0]].items():z.writestr(name,data)
        host_hash=hashlib.sha256(core.read_bytes()).hexdigest();report['host']['sha256']=host_hash
        for module in report['modules']:
            path=module['path'];raw=contents[path];metadata=json.loads(raw[FEATURE_METADATA]);metadata['host_sha256']=host_hash
            raw[FEATURE_METADATA]=json.dumps(metadata).encode()
            with ZipFile(out/path,'w') as z:
                for name,data in raw.items():z.writestr(name,data)
            module['host_sha256']=host_hash;module['sha256']=hashlib.sha256((out/path).read_bytes()).hexdigest()
        report['files']={p:hashlib.sha256((out/p).read_bytes()).hexdigest() for p in report['files']}
        (out/'distribution.json').write_text(json.dumps(report));return out
    def test_legacy_profile_remains_valid_with_ship_list_UI_in_core(self):
        root,report=self.bundle(False);self.assertNotIn('quest_ui_profile',report);verify(root)
        with ZipFile(root/JARS[0]) as z:self.assertTrue(quest_ui_required_entries()<=set(z.namelist()))
    def test_new_profile_extracts_ship_list_UI_and_preserves_input(self):
        source=self.source();before=source.read_bytes();root=self.root/'bundle';report=build(source,root,'a'*40)
        self.assertEqual(QUEST_UI_PROFILE,report['quest_ui_profile']);verify(root);self.assertEqual(before,source.read_bytes())
        with ZipFile(root/JARS[0]) as z:self.assertFalse(quest_ui_required_entries()&set(z.namelist()))
        with ZipFile(root/'features/logbook-feature-status.jar') as z:self.assertTrue(quest_ui_required_entries()<=set(z.namelist()))
    def test_incomplete_new_source_is_rejected_before_output_exists(self):
        source=self.source(missing='logbook/gui/quest_progress.fxml');out=self.root/'no-output'
        with self.assertRaisesRegex(ValueError,'Missing quest UI input'):build(source,out,'a'*40)
        self.assertFalse(out.exists())
    def test_unknown_profile_is_rejected(self):
        root,report=self.bundle();report['quest_ui_profile']='not-supported';(root/'distribution.json').write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError,'Unsupported quest UI'):verify(root)
    def test_missing_profile_cannot_silently_keep_new_api(self):
        root,report=self.bundle();report.pop('quest_ui_profile');(root/'distribution.json').write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError,'Quest UI API and packaging profile disagree'):verify(root)
    def test_old_core_cannot_claim_new_profile(self):
        root,report=self.bundle(False);report['quest_ui_profile']=QUEST_UI_PROFILE;(root/'distribution.json').write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError,'Quest UI API and packaging profile disagree'):verify(root)
    def test_new_ship_list_UI_cannot_be_left_in_host(self):
        root,_=self.bundle();entry='logbook/internal/gui/QuestPane.class'
        out=self.tamper(root,{JARS[0]:lambda d:d.update({entry:b'wrong host owner'})})
        with self.assertRaisesRegex(ValueError,'Quest UI remains in the host'):verify(out)
    def test_ship_list_UI_required_component_cannot_be_omitted(self):
        root,_=self.bundle();entry='logbook/gui/quest_progress.fxml'
        out=self.tamper(root,{'features/logbook-feature-status.jar':lambda d:d.pop(entry)})
        with self.assertRaisesRegex(ValueError,'Missing required feature entry'):verify(out)
    def test_ship_list_UI_cannot_be_moved_to_another_module(self):
        root,_=self.bundle();entry='logbook/gui/quest_progress.fxml'
        out=self.tamper(root,{'features/logbook-feature-calculators.jar':lambda d:d.update({entry:b'wrong module'})})
        with self.assertRaisesRegex(ValueError,'Quest UI belongs to the status'):verify(out)
if __name__=='__main__':unittest.main()
