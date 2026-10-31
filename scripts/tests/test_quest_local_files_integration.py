"""Actual quest-list renderer from product split archives, without starting the main proxy."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from build_feature_distribution import build
from run_feature_distribution import FILES


@unittest.skipUnless((ROOT / 'target/logbook-kai2.jar').is_file()
                     and shutil.which('xvfb-run') and shutil.which('javac'),
                     'Requires current built JAR, javac and an isolated display')
class QuestLocalFilesIntegrationTest(unittest.TestCase):
    def test_actual_renderer_refreshes_same_metadata_custom_and_arsenal_files(self):
        source = ROOT / 'target/logbook-kai2.jar'
        before = hashlib.sha256(source.read_bytes()).hexdigest()
        fixture = Path(tempfile.mkdtemp(prefix='quest-local-files-fixture-', dir=ROOT / 'target'))
        evidence = ROOT / 'target/r3-verification'
        evidence.mkdir(exist_ok=True)
        report = {'source_jar_sha256': before, 'result': 'not_completed', 'production_accessed': False,
                  'new_physical_module': False, 'atomic_with_all_writers_claimed': False,
                  'explicit_remove_executed': False}
        logs = []
        try:
            distribution = fixture / 'distribution'
            build(source, distribution, 'a' * 40)
            runtime = fixture / 'runtime'; runtime.mkdir()
            classes = fixture / 'probe-classes'; classes.mkdir()
            cp = os.pathsep.join(str(distribution / name) for name in FILES if name.endswith('.jar'))
            env = {k: v for k, v in os.environ.items() if k not in
                   ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')}
            compilation = subprocess.run(['javac', '-proc:none', '-encoding', 'UTF-8', '-cp', cp,
                '-d', str(classes), str(ROOT / 'scripts/java/QuestLocalFilesProbe.java')],
                env=env, capture_output=True, text=True, timeout=60)
            logs.append(compilation.stdout + compilation.stderr)
            self.assertEqual(0, compilation.returncode, logs[-1][-14000:])
            run = subprocess.run(['xvfb-run', '-a', 'java', '-Dlogbook.data.autoUpdate=false',
                '-Duser.home=' + str(fixture / 'home'), '-cp', str(classes) + os.pathsep + cp,
                'logbook.internal.gui.QuestLocalFilesProbe', 'synthetic-local-files-only'],
                cwd=runtime, env=env, capture_output=True, text=True, timeout=90)
            logs.append(run.stdout + run.stderr)
            self.assertEqual(0, run.returncode, logs[-1][-24000:])
            self.assertIn('QUEST_LOCAL_FILES_GUI_OK custom_reset_compatibility=6', run.stdout)
            self.assertNotIn('FXMLの初期化に失敗しました', logs[-1])
            self.assertEqual(before, hashlib.sha256(source.read_bytes()).hexdigest())
            report.update(result='passed', custom_reset_compatibility=6, product_distribution=True, same_size_mtime_refresh=True,
                custom_and_arsenal=True, unreadable_visible=True, external_bytes_preserved=True,
                immutable_capture=True, cached_quests_preserved=True, source_jar_unchanged=True)
        finally:
            (evidence / (fixture.name + '.log')).write_text('\n\n'.join(logs), encoding='utf8')
            (evidence / (fixture.name + '.json')).write_text(json.dumps(report, indent=2) + '\n', encoding='utf8')
        print('QUEST_LOCAL_FILES_GUI_OK ' + json.dumps(report))


if __name__ == '__main__':
    unittest.main()
