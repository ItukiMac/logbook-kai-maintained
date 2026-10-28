"""Check test-only bounded readiness polling; not a substitute for actual Launcher migration."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
JAR=ROOT/'target/logbook-kai.jar'

@unittest.skipUnless(JAR.is_file() and shutil.which('javac') and shutil.which('java'),
                     'Requires built JAR and Java21+, mandatory within comprehensive verification')
class RuntimeMigrationReadinessTest(unittest.TestCase):
    def test_pending_fx_observation_respects_existing_deadline_and_rejects_real_errors(self):
        root=Path(tempfile.mkdtemp(prefix='migration-readiness-fixture-',dir=ROOT/'target'))
        evidence=ROOT/'target/r3-verification';evidence.mkdir(exist_ok=True)
        digest=hashlib.sha256(JAR.read_bytes()).hexdigest()
        report={'result':'not_completed','production_accessed':False,'launcher_initialized':False,'fixture':str(root)};logs=[]
        try:
            classes=root/'classes';classes.mkdir()
            environment={k:v for k,v in os.environ.items() if k not in ('CLASSPATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}
            result=subprocess.run(['javac','-proc:none','--release','21','-cp',str(JAR),'-d',str(classes),
                str(ROOT/'scripts/java/RuntimeMigrationProbe.java'),str(ROOT/'scripts/java/RuntimeMigrationReadinessProbe.java')],
                capture_output=True,text=True,timeout=60,env=environment)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1])
            result=subprocess.run(['java','-cp',str(JAR)+os.pathsep+str(classes),'logbook.internal.kancolle.RuntimeMigrationReadinessProbe','no-runtime-side-effects'],
                capture_output=True,text=True,timeout=15,cwd=root,env=environment)
            logs.append(result.stdout+result.stderr);self.assertEqual(0,result.returncode,logs[-1]);self.assertIn('MIGRATION_READINESS_POLL_OK cases=10',result.stdout)
            self.assertFalse((root/'config').exists());self.assertEqual(digest,hashlib.sha256(JAR.read_bytes()).hexdigest())
            report.update(result='passed',cases=10,overall_deadline_seconds=25,poll_slice_seconds=2,
                          errors_propagate=True,missing_window_rejected=True,source_jar_unchanged=True)
        finally:
            (evidence/(root.name+'.json')).write_text(json.dumps(report,indent=2)+'\n')
            (evidence/(root.name+'.log')).write_text('\n\n'.join(logs),encoding='utf8')
        print('MIGRATION_READINESS_POLL_OK '+json.dumps(report))

if __name__=='__main__':unittest.main()
