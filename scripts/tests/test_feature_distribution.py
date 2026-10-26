import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from zipfile import ZipFile, ZIP_DEFLATED
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_feature_distribution import build, GROUPS, SERVICE, owner
from run_feature_distribution import verify

class FeatureDistributionTest(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory();self.addCleanup(t.cleanup);self.root=Path(t.name)
        self.jar=self.root/'original.jar';self.out=self.root/'candidate'
        self.entries={'META-INF/MANIFEST.MF':b'Manifest-Version: 1.0\r\n\r\n',
          'logbook/gui/application.css':b'.root {}','logbook/internal/Launcher.class':b'core',
          SERVICE:('\n'.join('logbook.features.'+v[0] for v in GROUPS.values())+'\n').encode()}
        for provider, controllers, fxml in GROUPS.values():
            for stem in ['logbook/features/'+provider]+['logbook/internal/gui/'+c for c in controllers]:
                self.entries[stem+'.class']=b'fixture';self.entries[stem+'$1.class']=b'nested'
            for f in fxml:self.entries['logbook/gui/'+f+'.fxml']=b'<fixture/>'
    def write(self):
        with ZipFile(self.jar,'w',compression=ZIP_DEFLATED) as z:
            for n,b in self.entries.items():z.writestr(n,b)
    def build(self):self.write();return build(self.jar,self.out,'a'*40)
    def test_physical_ownership_and_input_preservation(self):
        self.write();before=self.jar.read_bytes();r=build(self.jar,self.out,'a'*40)
        self.assertEqual(before,self.jar.read_bytes());verify(self.out)
        with ZipFile(self.out/'logbook-core.jar') as core:
            self.assertNotIn(SERVICE,core.namelist())
            self.assertFalse(any(owner(n) for n in core.namelist()))
        for g in GROUPS:
            with ZipFile(self.out/f'features/logbook-feature-{g}.jar') as z:
                self.assertTrue(all(n in z.namelist() for n in r['extracted'][g]))
                self.assertIn('logbook/gui/application.css',z.namelist())
                self.assertIn(b'Copyright (c) 2015 sanaehirotaka', z.read('META-INF/LICENSE-logbook-kai.txt'))
    def test_repeat_is_deterministic_and_never_replaces_original_output(self):
        r=self.build();other=self.root/'second';r2=build(self.jar,other,'a'*40)
        self.assertEqual(r,r2)
        saved=(self.out/'distribution.json').read_bytes()
        with self.assertRaises(FileExistsError):build(self.jar,self.out,'a'*40)
        self.assertEqual(saved,(self.out/'distribution.json').read_bytes())
    def test_missing_controller_rejected_before_output(self):
        del self.entries['logbook/internal/gui/CalcExpController.class'];self.write()
        with self.assertRaises(ValueError):build(self.jar,self.out,'a'*40)
        self.assertFalse(self.out.exists())
    def test_signed_and_traversal_archives_rejected(self):
        for bad in ('META-INF/sign.SF','../escape'):
            self.entries[bad]=b'bad';self.write()
            with self.assertRaises(ValueError):build(self.jar,self.out,'a'*40)
            self.assertFalse(self.out.exists());del self.entries[bad]
    def test_duplicate_archive_entry_is_rejected(self):
        import warnings
        self.write()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore')
            with ZipFile(self.jar,'a') as z:z.writestr(SERVICE,b'duplicate')
        with self.assertRaises(ValueError):build(self.jar,self.out,'a'*40)
    def test_modified_component_and_manifest_inventory_are_rejected(self):
        self.build();p=self.out/'features/logbook-feature-charts.jar';p.write_bytes(b'bad')
        with self.assertRaises(ValueError):verify(self.out)
        m=json.loads((self.out/'distribution.json').read_text());m['files']['../x']='0'*64
        (self.out/'distribution.json').write_text(json.dumps(m))
        with self.assertRaises(ValueError):verify(self.out)
    def test_symlink_source_and_bundle_parent_are_rejected(self):
        self.write();alias=self.root/'alias';alias.symlink_to(self.jar)
        with self.assertRaises(ValueError):build(alias,self.out,'a'*40)
        self.build();folder=self.root/'folder';folder.symlink_to(self.out,target_is_directory=True)
        with self.assertRaises(ValueError):verify(folder)
    def test_service_contract_change_is_not_silently_dropped(self):
        self.entries[SERVICE]+=b'other.Module\n';self.write()
        with self.assertRaises(ValueError):build(self.jar,self.out,'a'*40)

if __name__=='__main__':unittest.main()
