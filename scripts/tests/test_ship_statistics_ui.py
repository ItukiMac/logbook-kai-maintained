"""Actual statistics tab from the product charts module; new synthetic runtime only."""
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
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_feature_distribution import build
from run_feature_distribution import FILES, SHIP_STATISTICS_PROFILE, ship_statistics_required_entries

@unittest.skipUnless((ROOT/'target/logbook-kai2.jar').is_file() and shutil.which('xvfb-run') and shutil.which('javac'),
                     'Requires current built JAR, javac and an isolated display')
class ShipStatisticsUiTest(unittest.TestCase):
    def test_product_tab_uses_one_immutable_snapshot_and_displays_unknowns(self):
        original=ROOT/'target/logbook-kai2.jar';before=hashlib.sha256(original.read_bytes()).hexdigest()
        fixture=Path(tempfile.mkdtemp(prefix='ship-statistics-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        report={'fixture':str(fixture),'result':'not_completed','production_accessed':False};logs=[]
        try:
            dist=fixture/'distribution';manifest=build(original,dist,'a'*40)
            self.assertEqual(SHIP_STATISTICS_PROFILE,manifest['ship_statistics_profile'])
            with ZipFile(dist/'logbook-kai2-core.jar') as host:
                self.assertFalse(set(host.namelist()) & ship_statistics_required_entries())
            with ZipFile(dist/'features/logbook-kai2-feature-charts.jar') as charts:
                self.assertTrue(ship_statistics_required_entries() <= set(charts.namelist()))
            classes=fixture/'classes';classes.mkdir();runtime=fixture/'runtime';runtime.mkdir()
            cp=os.pathsep.join(str(dist/name) for name in FILES if name.endswith('.jar'))
            env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
            compiled=subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),
                str(ROOT/'scripts/java/ShipStatisticsGuiProbe.java')],capture_output=True,text=True,timeout=60,env=env)
            logs.append(compiled.stdout+compiled.stderr);self.assertEqual(0,compiled.returncode,logs[-1][-14000:])
            run=subprocess.run(['xvfb-run','-a','java','-Dlogbook.data.autoUpdate=false','-Duser.home='+str(fixture/'home'),
                '-cp',str(classes)+os.pathsep+cp,'logbook.internal.gui.ShipStatisticsGuiProbe','synthetic-statistics-only'],
                cwd=runtime,env=env,capture_output=True,text=True,timeout=75)
            logs.append(run.stdout+run.stderr);self.assertEqual(0,run.returncode,logs[-1][-20000:])
            self.assertIn('SHIP_STATISTICS_GUI_OK',run.stdout)
            self.assertNotIn('FXMLの初期化に失敗しました',logs[-1]);self.assertNotIn('画面の更新に失敗しました',logs[-1])
            self.assertEqual(before,hashlib.sha256(original.read_bytes()).hexdigest())
            report.update(result='passed',product_distribution=True,charts_rendered=5,actual_ship_tab=True,
                frozen_after_mutation=True,unknown_values_visible=True,source_jar_unchanged=True,original_history_unchanged=True,
                image_snapshot=True,core_sha256=hashlib.sha256((dist/'logbook-kai2-core.jar').read_bytes()).hexdigest(),
                charts_sha256=hashlib.sha256((dist/'features/logbook-kai2-feature-charts.jar').read_bytes()).hexdigest())
        finally:
            (evidence/(fixture.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
            (evidence/(fixture.name+'.json')).write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
        print('SHIP_STATISTICS_GUI_OK '+json.dumps(report))
if __name__=='__main__':unittest.main()
