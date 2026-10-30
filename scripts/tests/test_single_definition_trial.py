import copy,hashlib,json
from pathlib import Path
import subprocess,sys,tempfile,unittest
from zipfile import ZipFile
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import build_single_definition_trial as t

class SingleDefinitionTrialTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name);self.root=self.base/'repo';self.root.mkdir();self.old=self.base/'baseline.zip';self.out=self.base/'trial.zip'
        self.before={'operator':'AND','conditions':[{'operator':'AND','conditions':[{'type':'艦娘','order':1,'level':20}]}]}
        self.after=copy.deepcopy(self.before);self.after['conditions'][0]['conditions'][0]['level']=5;self.after['conditions'].insert(0,{'type':'艦隊','count_type':'レベル','value':10})
        p=self.root/t.SOURCE;p.parent.mkdir(parents=True);p.write_text(json.dumps(self.after))
        subprocess.run(['git','init','-q',str(self.root)],check=True);self.commit()
        raw=json.dumps(self.before).encode();other=b'{"untouched":true}'
        self.manifest={'format':'logbook-data-pack-v1','source_revision':'a'*40,'files':[{'path':n,'bytes':len(v),'sha256':t.digest(v),'source_sha256':t.digest(v)} for n,v in [(t.TARGET,raw),('definitions/quest/1.json',other)]]}
        with ZipFile(self.old,'w') as z:
            t.write_entry(z,t.TARGET,raw);t.write_entry(z,'definitions/quest/1.json',other);t.write_entry(z,'manifest.json',json.dumps(self.manifest).encode())
        self.sha=t.digest(self.old.read_bytes())
    def commit(self):
        subprocess.run(['git','-C',str(self.root),'add','.'],check=True)
        subprocess.run(['git','-C',str(self.root),'-c','user.name=Fixture','-c','user.email=fixture@localhost','commit','-qm','fixture'],check=True)
    def test_only_selected_definition_changes_and_baseline_is_preserved(self):
        before=self.old.read_bytes();r=t.build(self.root,self.old,self.out,expected=self.sha)
        self.assertEqual(before,self.old.read_bytes());self.assertEqual([t.TARGET],r['changed_definitions']);self.assertFalse(r['new_release_published'])
        with ZipFile(self.old) as old,ZipFile(self.out) as new:
            self.assertEqual(old.read('definitions/quest/1.json'),new.read('definitions/quest/1.json'))
            self.assertNotEqual(old.read(t.TARGET),new.read(t.TARGET))
    def test_unpinned_baseline_never_creates_output(self):
        with self.assertRaises(ValueError):t.build(self.root,self.old,self.out,expected='0'*64)
        self.assertFalse(self.out.exists())
    def test_uncommitted_or_unreviewed_a1_change_refused(self):
        p=self.root/t.SOURCE;p.write_text(json.dumps({**self.after,'unreviewed':True}))
        with self.assertRaises(ValueError):t.build(self.root,self.old,self.out,expected=self.sha)
        self.commit()
        with self.assertRaises(ValueError):t.build(self.root,self.old,self.out,expected=self.sha)
        self.assertFalse(self.out.exists())
    def test_existing_trial_and_symlink_output_are_preserved(self):
        self.out.write_bytes(b'keep')
        with self.assertRaises(ValueError):t.build(self.root,self.old,self.out,expected=self.sha)
        self.assertEqual(b'keep',self.out.read_bytes())
        link=self.base/'link.zip';link.symlink_to(self.old)
        with self.assertRaises(ValueError):t.build(self.root,self.old,link,expected=self.sha)
        self.assertEqual(self.sha,t.digest(self.old.read_bytes()))
    def test_duplicate_zip_entry_refused_even_if_caller_pins_new_hash(self):
        with ZipFile(self.old,'a') as z:t.write_entry(z,t.TARGET,b'{}')
        with self.assertRaises(ValueError):t.build(self.root,self.old,self.out,expected=t.digest(self.old.read_bytes()))
        self.assertFalse(self.out.exists())
