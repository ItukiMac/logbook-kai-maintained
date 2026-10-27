"""Current Java journal + actual acceptance config before/after a byte-exact NEW restore."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from runtime_snapshot import Limits, inventory, snapshot, restore

ROOT=Path(__file__).resolve().parents[2]
JAR=ROOT/'target/logbook-kai.jar'

@unittest.skipUnless(JAR.is_file() and shutil.which('java') and shutil.which('javac'),
                    'Requires built current application JAR; mandatory in integrated Maven+Python CI')
class RuntimeSnapshotJavaTest(unittest.TestCase):
    def test_actual_arrival_and_acceptance_readers_survive_copy_restore_without_source_writes(self):
        with tempfile.TemporaryDirectory(prefix='logbook-runtime-fixture-') as temp:
            base=Path(temp);classes=base/'classes';classes.mkdir()
            subprocess.run(['javac','-proc:none','--release','21','-cp',str(JAR),'-d',str(classes),str(ROOT/'scripts/java/RuntimeSnapshotProbe.java')],check=True,capture_output=True,text=True,timeout=45)
            cp=os.pathsep.join((str(JAR),str(classes)))
            env={k:v for k,v in os.environ.items() if k not in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')}
            # Logging is explicitly console-only outside the tested root, even on read failure.
            logging=base/'probe-log4j.xml';logging.write_text('<Configuration><Appenders><Console name="C"/></Appenders><Loggers><Root level="off"><AppenderRef ref="C"/></Root></Loggers></Configuration>')
            command=['java','-Dlog4j2.configurationFile='+str(logging),'-cp',cp,'logbook.internal.kancolle.RuntimeSnapshotProbe']
            source=base/'source';limits=Limits(reserve=0)
            subprocess.run(command+['seed',str(source)],cwd=base,env=env,check=True,capture_output=True,text=True,timeout=30)
            before=inventory(source,limits)
            first=subprocess.run(command+['verify',str(source)],cwd=source,env=env,check=True,capture_output=True,text=True,timeout=30)
            self.assertIn('RUNTIME_COPY_JAVA_OK records=3 accepted=2',first.stdout)
            receipt=snapshot(source,base/'snapshot',stopped=True,limits=limits)
            restored=restore(base/'snapshot',base/'restored',receipt['manifest_sha256'],limits=limits)
            runtime=Path(restored['restored_to']);second=subprocess.run(command+['verify',str(runtime)],cwd=runtime,env=env,check=True,capture_output=True,text=True,timeout=30)
            self.assertIn('RUNTIME_COPY_JAVA_OK records=3 accepted=2',second.stdout)
            self.assertEqual(before,inventory(source,limits));self.assertEqual(before,inventory(runtime,limits))
            self.assertFalse(restored['launch_safe']);self.assertFalse(restored['schema_converted'])
            print('RUNTIME_SNAPSHOT_RESTORE_OK original_unchanged=true restored_bytes_equal=true arrival_records=3 accepted=2')

if __name__=='__main__':unittest.main()
