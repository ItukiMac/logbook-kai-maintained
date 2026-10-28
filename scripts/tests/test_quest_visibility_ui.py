"""Reversible quest visibility from real product archives; no real user runtime is used."""
import hashlib,json,os,shutil,subprocess,sys,tempfile,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts'))
from build_feature_distribution import build
from run_feature_distribution import FILES,verify

@unittest.skipUnless((ROOT/'target/logbook-kai.jar').is_file() and shutil.which('javac') and shutil.which('xvfb-run'),
                     'Requires built product JAR and an isolated display')
class QuestVisibilityUiTest(unittest.TestCase):
    def test_confirm_cancel_conflict_restore_and_process_reset_preserve_originals(self):
        source=ROOT/'target/logbook-kai.jar';digest=hashlib.sha256(source.read_bytes()).hexdigest()
        fixture=Path(tempfile.mkdtemp(prefix='quest-visibility-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        logs=[];report={'result':'not_completed','production_accessed':False,'persisted_hide_state':False,'physical_module_added':False}
        try:
            dist=fixture/'distribution';build(source,dist,'a'*40);verify(dist)
            cp=os.pathsep.join(str(dist/name) for name in FILES if name.endswith('.jar'))
            classes=fixture/'classes';classes.mkdir();runtime=fixture/'runtime';runtime.mkdir()
            env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
            result=subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'scripts/java/QuestVisibilityProbe.java')],
                env=env,capture_output=True,text=True,timeout=60)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1][-16000:])
            command=['java','-Dlogbook.data.autoUpdate=false','-Duser.home='+str(fixture/'home'),'-cp',str(classes)+os.pathsep+cp,'logbook.internal.gui.QuestVisibilityProbe']
            result=subprocess.run(['xvfb-run','-a',*command,'new-synthetic-quest-visibility-only'],cwd=runtime,env=env,capture_output=True,text=True,timeout=100)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1][-25000:])
            self.assertIn('QUEST_VISIBILITY_GUI_OK',result.stdout)
            for marker in ('FXMLの初期化に失敗しました','FXMLのロードに失敗しました'):self.assertNotIn(marker,logs[-1])
            saved={str(p.relative_to(runtime)):p.read_bytes() for directory in ('config','customquest') for p in (runtime/directory).rglob('*') if p.is_file()}
            result=subprocess.run([*command,'verify-new-process-session-only'],cwd=runtime,env=env,capture_output=True,text=True,timeout=20)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1][-12000:]);self.assertIn('QUEST_VISIBILITY_RESTART_OK',result.stdout)
            after={str(p.relative_to(runtime)):p.read_bytes() for directory in ('config','customquest') for p in (runtime/directory).rglob('*') if p.is_file()}
            self.assertEqual(saved,after);self.assertEqual(digest,hashlib.sha256(source.read_bytes()).hexdigest())
            report.update(result='passed',product_distribution=True,confirm_cancel=True,hide_restore_all=True,
                stale_rejected=True,originals_unchanged=True,acceptance_unchanged=True,custom_slot_retained=True,
                session_reset_in_new_process=True,source_jar_unchanged=True)
        finally:
            (evidence/(fixture.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
            (evidence/(fixture.name+'.json')).write_text(json.dumps(report,indent=2)+'\n',encoding='utf8')
        print('QUEST_VISIBILITY_GUI_OK '+json.dumps(report))
if __name__=='__main__':unittest.main()
