"""Run actual ship label dialogs on a new synthetic runtime; no user settings or history."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_feature_distribution import build
from run_feature_distribution import FILES

@unittest.skipUnless((ROOT/'target/logbook-kai.jar').is_file() and shutil.which('xvfb-run') and shutil.which('javac'),
                     'Requires current built JAR, javac and an isolated display')
class ShipLabelUiIntegrationTest(unittest.TestCase):
    def test_real_label_dialogs_cancel_confirm_and_reject_stale_state(self):
        original=ROOT/'target/logbook-kai.jar';before=hashlib.sha256(original.read_bytes()).hexdigest()
        fixture=Path(tempfile.mkdtemp(prefix='ship-label-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        result={'fixture':str(fixture),'production_accessed':False,'result':'not_completed'};logs=[]
        try:
            dist=fixture/'distribution';build(original,dist,'a'*40)
            classes=fixture/'classes';classes.mkdir();runtime=fixture/'runtime';runtime.mkdir()
            cp=os.pathsep.join(str(dist/name) for name in FILES if name.endswith('.jar'))
            env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
            compiled=subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),
                str(ROOT/'scripts/java/ShipLabelGuiProbe.java')],capture_output=True,text=True,timeout=60,env=env)
            logs.append(compiled.stdout+compiled.stderr);self.assertEqual(0,compiled.returncode,logs[-1][-14000:])
            run=subprocess.run(['xvfb-run','-a','java','-Dlogbook.data.autoUpdate=false','-Duser.home='+str(fixture/'home'),
                '-cp',str(classes)+os.pathsep+cp,'logbook.internal.gui.ShipLabelGuiProbe','synthetic-ship-labels-only'],
                cwd=runtime,env=env,capture_output=True,text=True,timeout=75)
            logs.append(run.stdout+run.stderr);self.assertEqual(0,run.returncode,logs[-1][-18000:])
            self.assertIn('SHIP_LABEL_GUI_OK',run.stdout);self.assertNotIn('FXMLの初期化に失敗しました',logs[-1])
            self.assertEqual(before,hashlib.sha256(original.read_bytes()).hexdigest())
            result.update(result='passed',dialog_cancel_no_change=True,dialog_confirm_add_remove=True,
                stale_batch_rejected=True,settings_reread=True,original_history_unchanged=True,source_jar_unchanged=True)
        finally:
            (evidence/(fixture.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
            (evidence/(fixture.name+'.json')).write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
        print('SHIP_LABEL_GUI_OK '+json.dumps(result))

if __name__=='__main__':unittest.main()
