"""Real quest windows from split product JARs; synthetic runtime only."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_feature_distribution import build
from run_feature_distribution import FILES, QUEST_UI_PROFILE, quest_ui_entry, quest_ui_required_entries, verify

@unittest.skipUnless((ROOT/'target/logbook-kai.jar').is_file() and shutil.which('xvfb-run') and shutil.which('javac'),
                     'Requires built candidate, javac and isolated Xvfb')
class QuestUiIntegrationTest(unittest.TestCase):
    def test_real_product_quest_windows_and_guarded_note_roundtrip(self):
        original=ROOT/'target/logbook-kai.jar';before=hashlib.sha256(original.read_bytes()).hexdigest()
        fixture=Path(tempfile.mkdtemp(prefix='quest-ui-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        result={'fixture':str(fixture),'production_accessed':False,'result':'not_completed',
                'atomic_game_snapshot_claimed':False};logs=[]
        try:
            dist=fixture/'distribution';report=build(original,dist,'a'*40);verify(dist)
            self.assertEqual(QUEST_UI_PROFILE,report['quest_ui_profile'])
            with ZipFile(dist/'logbook-core.jar') as core:
                self.assertFalse(any(quest_ui_entry(n) for n in core.namelist()),'Hidden core fallback')
            with ZipFile(dist/'features/logbook-feature-status.jar') as status:
                self.assertTrue(quest_ui_required_entries()<=set(status.namelist()))
            classes=fixture/'classes';classes.mkdir();runtime=fixture/'runtime';runtime.mkdir()
            cp=os.pathsep.join(str(dist/name) for name in FILES if name.endswith('.jar'))
            env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
            compiled=subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),
                str(ROOT/'scripts/java/QuestUiProductProbe.java')],capture_output=True,text=True,timeout=60,env=env)
            logs.append(compiled.stdout+compiled.stderr);self.assertEqual(0,compiled.returncode,logs[-1][-16000:])
            run=subprocess.run(['xvfb-run','-a','java','-Dlogbook.data.autoUpdate=false','-Duser.home='+str(fixture/'home'),
                '-cp',str(classes)+os.pathsep+cp,'logbook.internal.gui.QuestUiProductProbe','synthetic-quest-ui-only'],
                cwd=runtime,env=env,capture_output=True,text=True,timeout=90)
            logs.append(run.stdout+run.stderr);self.assertEqual(0,run.returncode,logs[-1][-24000:])
            self.assertIn('QUEST_UI_PRODUCT_OK',run.stdout)
            for text in ('FXMLの初期化に失敗しました','画面の更新に失敗しました','FXMLのロードに失敗しました'):
                self.assertNotIn(text,logs[-1])
            self.assertEqual(before,hashlib.sha256(original.read_bytes()).hexdigest())
            result.update(result='passed',product_status_jar=True,quest_ui_profile=QUEST_UI_PROFILE,
                windows=4,guarded_note_roundtrip=True,source_jar_unchanged=True,
                host_sha256=hashlib.sha256((dist/'logbook-core.jar').read_bytes()).hexdigest(),
                status_sha256=hashlib.sha256((dist/'features/logbook-feature-status.jar').read_bytes()).hexdigest())
        finally:
            (evidence/(fixture.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
            (evidence/(fixture.name+'.json')).write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
        print('QUEST_UI_PRODUCT_OK '+json.dumps(result))

if __name__=='__main__':unittest.main()
