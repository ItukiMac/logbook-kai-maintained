"""Actual shipped ship list and retained core ABI; synthetic runtime, never user data."""
import hashlib,json,os,shutil,subprocess,sys,tempfile,unittest
from pathlib import Path
from zipfile import ZipFile
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_feature_distribution import build
from run_feature_distribution import FILES,JARS,SHIP_UI_PROFILE,SHIP_UI_API,ship_ui_entry,ship_ui_required_entries,verify

@unittest.skipUnless((ROOT/'target/logbook-kai.jar').is_file() and shutil.which('xvfb-run') and shutil.which('javac'),
                     'Requires current built JAR, javac and isolated display')
class ShipListUiIntegrationTest(unittest.TestCase):
    def test_real_product_ship_window_tabs_filters_exports_and_close(self):
        source=ROOT/'target/logbook-kai.jar';before=hashlib.sha256(source.read_bytes()).hexdigest()
        fixture=Path(tempfile.mkdtemp(prefix='ship-list-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        result={'fixture':str(fixture),'production_accessed':False,'result':'not_completed'};logs=[]
        try:
            dist=fixture/'distribution';report=build(source,dist,'a'*40);verify(dist)
            self.assertEqual(SHIP_UI_PROFILE,report['ship_ui_profile'])
            with ZipFile(dist/JARS[0]) as z:
                self.assertIn(SHIP_UI_API,z.namelist());self.assertFalse(any(ship_ui_entry(n) for n in z.namelist()))
                self.assertIn('logbook/internal/gui/ShipItem.class',z.namelist())
            with ZipFile(dist/'features/logbook-feature-status.jar') as z:
                self.assertTrue(ship_ui_required_entries()<=set(z.namelist()))
                self.assertIn('logbook/internal/gui/ShipTablePane$KanmusuList.class',z.namelist())
            classes=fixture/'classes';classes.mkdir();runtime=fixture/'runtime';runtime.mkdir()
            cp=os.pathsep.join(str(dist/name) for name in FILES if name.endswith('.jar'))
            env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
            compiled=subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),
                str(ROOT/'scripts/java/ShipLabelGuiProbe.java'),str(ROOT/'scripts/java/ShipListGuiProbe.java')],
                capture_output=True,text=True,timeout=60,env=env)
            logs.append(compiled.stdout+compiled.stderr);self.assertEqual(0,compiled.returncode,logs[-1][-16000:])
            run=subprocess.run(['xvfb-run','-a','java','-Dlogbook.data.autoUpdate=false','-Duser.home='+str(fixture/'home'),
                '-cp',str(classes)+os.pathsep+cp,'logbook.internal.gui.ShipListGuiProbe','new-synthetic-ship-list-only'],
                cwd=runtime,env=env,capture_output=True,text=True,timeout=90)
            logs.append(run.stdout+run.stderr);self.assertEqual(0,run.returncode,logs[-1][-18000:])
            self.assertIn('SHIP_LIST_PRODUCT_GUI_OK',run.stdout)
            for text in ('FXMLの初期化に失敗しました','画面の更新に失敗しました','形式でクリップボードコピーに失敗'):
                self.assertNotIn(text,logs[-1])
            self.assertEqual(before,hashlib.sha256(source.read_bytes()).hexdigest())
            result.update(result='passed',product_distribution=True,ship_ui_profile=SHIP_UI_PROFILE,
                no_core_fallback=True,tabs_verified=True,filters_verified=True,legacy_exports_verified=True,
                timeline_stopped=True,settings_reread=True,original_history_unchanged=True,source_jar_unchanged=True,
                shared_legacy_models_retained=True,global_atomic_state_claimed=False,
                core_sha256=hashlib.sha256((dist/JARS[0]).read_bytes()).hexdigest(),
                status_sha256=hashlib.sha256((dist/'features/logbook-feature-status.jar').read_bytes()).hexdigest())
        finally:
            (evidence/(fixture.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
            (evidence/(fixture.name+'.json')).write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
        print('SHIP_LIST_PRODUCT_GUI_OK '+json.dumps(result))
if __name__=='__main__':unittest.main()
