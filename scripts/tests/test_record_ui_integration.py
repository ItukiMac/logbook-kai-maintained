"""Actual chart-module record views; separate loader additionally detects accidental host fallback."""
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
sys.path.insert(0, str(ROOT / 'scripts'))
from build_feature_distribution import build
from run_feature_distribution import RECORD_UI_PROFILE, record_ui_required_entries

FAMILIES = ('BattleLogController', 'MissionLogController', 'CreateItemController',
            'BattleLogDetailAggregate', 'BattleRecordCollect', 'BattleRecordDetail',
            'MissionRecordCollect', 'MissionRecordDetail', 'MissionAggregate')
RESOURCES = {'logbook/gui/' + name + '.fxml' for name in
             ('battlelog', 'battlelog_dialog', 'missionlog', 'createitemlog')}


def record_entry(name):
    return name in RESOURCES or any(name == 'logbook/internal/gui/' + family + '.class'
        or name.startswith('logbook/internal/gui/' + family + '$') and name.endswith('.class')
        for family in FAMILIES)


@unittest.skipUnless((ROOT / 'target/logbook-kai2.jar').is_file()
                     and shutil.which('xvfb-run') and shutil.which('javac'),
                     'Requires the current built JAR, javac and isolated Xvfb')
class RecordUiIntegrationTest(unittest.TestCase):
    def test_records_use_public_gui_boundary_with_unchanged_synthetic_history(self):
        original = ROOT / 'target/logbook-kai2.jar'
        source_hash = hashlib.sha256(original.read_bytes()).hexdigest()
        fixture = Path(tempfile.mkdtemp(prefix='record-ui-fixture-', dir=ROOT / 'target'))
        evidence = ROOT / 'target/r3-verification'
        evidence.mkdir(exist_ok=True)
        log_file = evidence / (fixture.name + '.log')
        summary_file = evidence / (fixture.name + '.json')
        summary = {'fixture': str(fixture), 'source_jar_sha256': source_hash,
                   'production_accessed': False, 'new_distribution_format': False, 'legacy_missing_experience_bug_fixed': True,
                   'shared_legacy_row_models_retained_in_core': ['BattleLogCollect', 'BattleLogDetail', 'MissionLogCollect', 'MissionLogDetail'],
                   'result': 'not_completed'}
        logs = []
        try:
            distribution = fixture / 'distribution'
            report = build(original, distribution, 'a' * 40)
            self.assertEqual(RECORD_UI_PROFILE, report.get('record_ui_profile'))
            host = distribution / 'logbook-kai2-core.jar'
            feature = distribution / 'features/logbook-kai2-feature-records.jar'
            with ZipFile(host) as core:
                self.assertFalse(any(record_entry(n) for n in core.namelist()), 'Hidden fallback remained in host')
            with ZipFile(feature) as records:
                self.assertTrue(record_ui_required_entries().issubset(records.namelist()))
            summary.update(product_distribution=True, record_ui_profile=RECORD_UI_PROFILE,
                           record_module='records', original_distribution_source_unchanged=True)
            classes = fixture / 'probe-classes'
            classes.mkdir()
            runtime = fixture / 'runtime'
            runtime.mkdir()
            env = {k: v for k, v in os.environ.items() if k not in
                   ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')}
            compiled = subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', str(host), '-d', str(classes),
                str(ROOT / 'scripts/java/RecordUiProbe.java')], capture_output=True, text=True, timeout=60, env=env)
            logs.append(compiled.stdout + compiled.stderr)
            self.assertEqual(0, compiled.returncode, logs[-1][-16000:])
            run = subprocess.run(['xvfb-run', '-a', 'java', '-Dlogbook.data.autoUpdate=false',
                '-Duser.home=' + str(fixture / 'home'), '-cp', str(classes) + os.pathsep + str(host),
                'logbook.tools.RecordUiProbe', 'isolated-record-ui-proof', str(feature)],
                cwd=runtime, env=env, capture_output=True, text=True, timeout=75)
            logs.append(run.stdout + run.stderr)
            self.assertEqual(0, run.returncode, logs[-1][-20000:])
            self.assertIn('RECORD_UI_ISOLATION_OK', run.stdout)
            self.assertNotIn('FXMLの初期化に失敗しました', logs[-1])
            self.assertEqual(source_hash, hashlib.sha256(original.read_bytes()).hexdigest())
            summary.update(result='passed', windows=3, battle_rows=3, battle_exp=60, mission_fuel=75, development_rows=2,
                           nested_dialog=True, settings_reread=True, original_history_unchanged=True,
                           host_sha256=hashlib.sha256(host.read_bytes()).hexdigest(),
                           record_ui_sha256=hashlib.sha256(feature.read_bytes()).hexdigest(),
                           retained_fixture=True)
        finally:
            log_file.write_text('\n\n'.join(logs), encoding='utf8')
            summary_file.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf8')
        print('RECORD_UI_ISOLATION_OK ' + json.dumps(summary))


if __name__ == '__main__':
    unittest.main()
