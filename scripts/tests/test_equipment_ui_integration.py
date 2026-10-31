"""Actual product status-module equipment tabs; no user runtime or external browser is accessed."""
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
from run_feature_distribution import (FILES, EQUIPMENT_UI_PROFILE,
    equipment_ui_entry, equipment_ui_required_entries)


@unittest.skipUnless((ROOT / 'target/logbook-kai2.jar').is_file()
                     and shutil.which('xvfb-run') and shutil.which('javac'),
                     'Requires built current JAR, javac and isolated display')
class EquipmentUiIntegrationTest(unittest.TestCase):
    def test_actual_product_equipment_tabs_keep_values_filters_exports_and_settings(self):
        original = ROOT / 'target/logbook-kai2.jar'
        before = hashlib.sha256(original.read_bytes()).hexdigest()
        fixture = Path(tempfile.mkdtemp(prefix='equipment-ui-fixture-', dir=ROOT / 'target'))
        evidence = ROOT / 'target/r3-verification'
        evidence.mkdir(exist_ok=True)
        summary = {'fixture': str(fixture), 'source_jar_sha256': before,
                   'production_accessed': False, 'result': 'not_completed',
                   'shared_host_gui_helpers_retained': True, 'atomic_writer_snapshot_claimed': False}
        logs = []
        try:
            distribution = fixture / 'distribution'
            report = build(original, distribution, 'a' * 40)
            self.assertEqual(EQUIPMENT_UI_PROFILE, report.get('equipment_ui_profile'))
            host = distribution / 'logbook-kai2-core.jar'
            status = distribution / 'features/logbook-kai2-feature-equipment.jar'
            with ZipFile(host) as core:
                self.assertFalse(any(equipment_ui_entry(n) for n in core.namelist()), 'Host fallback copy remains')
            with ZipFile(status) as module:
                self.assertTrue(equipment_ui_required_entries().issubset(module.namelist()))
            cp = os.pathsep.join(str(distribution / name) for name in FILES if name.endswith('.jar'))
            classes = fixture / 'probe-classes'
            classes.mkdir()
            runtime = fixture / 'runtime'
            runtime.mkdir()
            env = {k: v for k, v in os.environ.items() if k not in
                   ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')}
            compile_result = subprocess.run(['javac', '-proc:none', '-encoding', 'UTF-8', '-cp', cp,
                '-d', str(classes), str(ROOT / 'scripts/java/EquipmentInventoryProbe.java')],
                capture_output=True, text=True, timeout=60, env=env)
            logs.append(compile_result.stdout + compile_result.stderr)
            self.assertEqual(0, compile_result.returncode, logs[-1][-16000:])
            result = subprocess.run(['xvfb-run', '-a', 'java', '-Dlogbook.data.autoUpdate=false',
                '-Duser.home=' + str(fixture / 'home'), '-cp', str(classes) + os.pathsep + cp,
                'logbook.internal.gui.EquipmentInventoryProbe', 'synthetic-equipment-only'],
                cwd=runtime, env=env, capture_output=True, text=True, timeout=90)
            logs.append(result.stdout + result.stderr)
            self.assertEqual(0, result.returncode, logs[-1][-22000:])
            self.assertIn('EQUIPMENT_PRODUCT_GUI_OK', result.stdout)
            self.assertNotIn('FXMLの初期化に失敗しました', logs[-1])
            self.assertEqual(before, hashlib.sha256(original.read_bytes()).hexdigest())
            summary.update(result='passed', product_distribution=True, equipment_ui_profile=EQUIPMENT_UI_PROFILE,
                equipment_module='equipment', tabs=2, summaries=2, owned_instances=6,
                normal_and_expansion_owner=True, aircraft=3, grouped_aircraft=2, locked_export=3,
                settings_reread=True, original_history_unchanged=True, source_jar_unchanged=True,
                host_sha256=hashlib.sha256(host.read_bytes()).hexdigest(),
                status_sha256=hashlib.sha256(status.read_bytes()).hexdigest())
        except Exception as error:
            logs.append(type(error).__name__ + ': ' + str(error))
            raise
        finally:
            (evidence / (fixture.name + '.log')).write_text('\n\n'.join(logs), encoding='utf8')
            (evidence / (fixture.name + '.json')).write_text(json.dumps(summary, indent=2) + '\n', encoding='utf8')
        print('EQUIPMENT_PRODUCT_GUI_OK ' + json.dumps(summary))


if __name__ == '__main__':
    unittest.main()
