"""Actual product-JAR airbase GUI, synthetic state only."""
import hashlib,json,os,shutil,subprocess,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_feature_distribution import build
from run_feature_distribution import FILES,verify

@unittest.skipUnless((ROOT/'target/logbook-kai2.jar').is_file() and shutil.which('javac') and shutil.which('xvfb-run'),
                     'Requires built JAR and isolated display')
class AirBaseStatusUiTest(unittest.TestCase):
    def test_frozen_values_refresh_unknowns_and_identity_in_product_window(self):
        source=ROOT/'target/logbook-kai2.jar';before=hashlib.sha256(source.read_bytes()).hexdigest()
        fixture=Path(tempfile.mkdtemp(prefix='airbase-status-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        report={'fixture':str(fixture),'result':'not_completed','production_accessed':False};logs=[]
        try:
            dist=fixture/'distribution';build(source,dist,'a'*40);verify(dist)
            cp=os.pathsep.join(str(dist/name) for name in FILES if name.endswith('.jar'))
            classes=fixture/'classes';classes.mkdir();runtime=fixture/'runtime';runtime.mkdir()
            env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
            result=subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'scripts/java/AirBaseStatusGuiProbe.java')],env=env,capture_output=True,text=True,timeout=60)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1][-16000:])
            result=subprocess.run(['xvfb-run','-a','java','-Dlogbook.data.autoUpdate=false','-Duser.home='+str(fixture/'home'),'-cp',str(classes)+os.pathsep+cp,
                'logbook.internal.gui.AirBaseStatusGuiProbe','synthetic-airbase-only'],env=env,cwd=runtime,capture_output=True,text=True,timeout=90)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1][-22000:]);self.assertIn('AIRBASE_STATUS_GUI_OK',result.stdout)
            for marker in ('FXMLの初期化に失敗しました','画面の更新に失敗しました','FXMLのロードに失敗しました'):self.assertNotIn(marker,logs[-1])
            self.assertEqual(before,hashlib.sha256(source.read_bytes()).hexdigest())
            report.update(result='passed',product_status_jar=True,frozen_selection=True,identity_preserved=True,
                captured_power=40,refresh_power=60,sorted_planes=True,unknown_visible=True,stale_values_cleared=True,
                legacy_export=True,timeline_stopped=True,original_history_unchanged=True,source_jar_unchanged=True)
        finally:
            (evidence/(fixture.name+'.json')).write_text(json.dumps(report,indent=2)+'\n')
            (evidence/(fixture.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
        print('AIRBASE_STATUS_GUI_OK '+json.dumps(report))
if __name__=='__main__':unittest.main()
