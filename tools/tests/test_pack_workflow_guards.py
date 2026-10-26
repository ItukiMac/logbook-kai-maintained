"""Execute only embedded Python validation/asset-selection blocks, never publish shell/API steps."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest
from zipfile import ZipFile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT))
from tools.build_data_pack import build, CATEGORIES

class PackWorkflowGuardTest(unittest.TestCase):
    def blocks(self):
        result=[]
        for name in ('build-data-pack.yml','manual-data-release.yml'):
            source=(ROOT/'.github/workflows'/name).read_text()
            for index, part in enumerate(source.split("<<'PY'\n")[1:]):
                code=textwrap.dedent(part.split('\n          PY',1)[0])
                result.append((name+':'+str(index),compile(code,name,'exec')))
        self.assertEqual(3,len(result))
        return result
    def execute(self, code, rule, mutation=None):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'src/main/resources/logbook'
            for category in CATEGORIES:
                folder=source/category;folder.mkdir(parents=True)
                (folder/'1.json').write_text(json.dumps(rule if category=='quest' else {}))
            original=root/'original.zip';build(source,original,'fixture')
            with ZipFile(original) as archive:
                files={name:archive.read(name) for name in archive.namelist()}
            manifest=json.loads(files['manifest.json'])
            if mutation:mutation(manifest)
            files['manifest.json']=json.dumps(manifest).encode()
            for folder in ('target','dist'):
                dest=root/folder;dest.mkdir()
                with ZipFile(dest/'logbook-data-pack.zip','x') as archive:
                    for name,data in files.items():archive.writestr(name,data)
            before=(root/'dist/logbook-data-pack.zip').read_bytes()
            old=Path.cwd()
            try:
                os.chdir(root)
                with contextlib.redirect_stdout(io.StringIO()):exec(code,{'__name__':'__main__'})
            finally:os.chdir(old)
            self.assertEqual(before,(root/'dist/logbook-data-pack.zip').read_bytes())
            copied=root/'dist/logbook-data-pack-v2.zip'
            if copied.exists():self.assertEqual(before,copied.read_bytes())
    def test_arrival_presence_is_recomputed_in_all_three_guards(self):
        for name,code in self.blocks():
            for value in (True,False,None):
                with self.subTest(block=name,value=value):self.execute(code,{'conditions':[{'arrival':value}]})
    def test_missing_arrival_capability_is_rejected(self):
        for name,code in self.blocks():
            with self.subTest(block=name),self.assertRaises(AssertionError):
                self.execute(code,{'conditions':[{'arrival':True}]},lambda m:m.update(required_capabilities=[]))
    def test_extra_undeclared_by_content_capability_is_rejected(self):
        for name,code in self.blocks():
            with self.subTest(block=name),self.assertRaises(AssertionError):
                self.execute(code,{'conditions':[{'arrival':True}]},lambda m:m['required_capabilities'].append('unknown-fixture'))
    def test_ordinary_legacy_pack_still_passes(self):
        for name,code in self.blocks():
            with self.subTest(block=name):self.execute(code,{'conditions':[{'area':['1-1'],'count':1}]})

if __name__=='__main__':unittest.main()
