"""Full Launcher A -> B -> A using synthetic bundles in a NEW disposable store.

Requires an already built current JAR/test hook and Xvfb. Never imports a user's
installation. A/B differ only in fixture markers, not application state schemas.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile, ZIP_DEFLATED
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import feature_bundle_manager as m
from build_feature_distribution import build

ROOT = Path(__file__).resolve().parents[2]
HOOK = 'logbook/internal/logger/ManagedLauncherSmokeHook.class'
MARK = 'META-INF/managed-generation-test.txt'

@unittest.skipUnless((ROOT/'target/logbook-kai.jar').is_file()
    and (ROOT/'target/test-classes'/HOOK).is_file() and shutil.which('xvfb-run'),
    'Requires built current JAR/test hook and isolated Xvfb')
class ManagedLauncherIntegrationTest(unittest.TestCase):
    def test_normal_launcher_whole_update_and_rollback_preserve_runtime(self):
        with tempfile.TemporaryDirectory(prefix='logbook-managed-fixture-') as tmp:
            base = Path(tmp)
            store = base/'store'
            original = ROOT/'target/logbook-kai.jar'
            before = hashlib.sha256(original.read_bytes()).hexdigest()
            pristine = base/'pristine'
            build(original, pristine, 'a'*40)
            ids = {}
            for label in ('A', 'B'):
                target = base/label
                shutil.copytree(pristine, target)
                report = json.loads((target/'distribution.json').read_text())
                report['source_revision'] = label.lower()*40
                for name in m.JARS:
                    path = target/name
                    # Only append a new entry in a newly copied fixture JAR. No
                    # duplicate entries, no recompression of third-party libraries.
                    with ZipFile(path, 'a', ZIP_DEFLATED) as archive:
                        self.assertNotIn(MARK, archive.namelist())
                        archive.writestr(MARK, label.encode())
                    report['files'][name] = hashlib.sha256(path.read_bytes()).hexdigest()
                (target/'distribution.json').write_text(json.dumps(report, sort_keys=True))
                ids[label] = hashlib.sha256((target/'distribution.json').read_bytes()).hexdigest()
            m.initialize(store)
            m.stage(store, base/'A', ids['A'])
            m.activate(store, ids['A'], None)
            runtime = store/'runtime'
            (runtime/'config').mkdir()
            (runtime/'plugins').mkdir()
            with socket.socket() as port:
                port.bind(('127.0.0.1', 0)); number = port.getsockname()[1]
            config = runtime/'config/logbook.bean.AppConfig.json'
            config.write_text(json.dumps(dict(checkUpdate=True, checkDoit=False,
                listenPort=number, allowOnlyFromLocalhost=True, useProxy=False,
                usePlugin=True, useSound=False, useToast=False)))
            with ZipFile(runtime/'plugins/isolated-smoke-hook.jar', 'x', ZIP_DEFLATED) as plugin:
                plugin.write(ROOT/'target/test-classes'/HOOK, HOOK)
                plugin.writestr('META-INF/services/logbook.plugin.lifecycle.StartUp',
                                'logbook.internal.logger.ManagedLauncherSmokeHook\n')
            sentinel = runtime/'retained-history-sentinel.csv'
            sentinel.write_bytes(b'isolated-user-history\n')
            hook_hash = hashlib.sha256((runtime/'plugins/isolated-smoke-hook.jar').read_bytes()).hexdigest()
            logs = []
            for label in ('A', 'B', 'A'):
                if label == 'B':
                    m.stage(store, base/'B', ids['B'])
                    m.activate(store, ids['B'], ids['A'])
                elif logs:
                    m.activate(store, '', ids['B'], rollback=True)
                (runtime/'expected-generation.txt').write_text(label)
                result = subprocess.run(['xvfb-run', '-a', sys.executable, '-S',
                    str(ROOT/'scripts/feature_bundle_manager.py'), '--store', str(store), 'launch'],
                    capture_output=True, text=True, timeout=45)
                logs.append(result.stdout + result.stderr)
                self.assertEqual(0, result.returncode, logs[-1][-12000:])
                self.assertIn('MANAGED_REAL_LAUNCHER_OK', logs[-1])
                self.assertEqual('MAIN_WINDOW_PROXY_AND_7_FEATURES_OK\n',
                                 (runtime/'managed-smoke-ready.txt').read_text())
                self.assertEqual(ids[label], m.selector(store)['current'])
                self.assertEqual(b'isolated-user-history\n', sentinel.read_bytes())
                self.assertEqual(hook_hash, hashlib.sha256((runtime/'plugins/isolated-smoke-hook.jar').read_bytes()).hexdigest())
                self.assertEqual(number, json.loads(config.read_text())['listenPort'])
                self.assertTrue(json.loads(config.read_text())['allowOnlyFromLocalhost'])
            self.assertEqual(before, hashlib.sha256(original.read_bytes()).hexdigest())
            for label in ids: m.bundle(store/'versions'/ids[label], ids[label])
            self.assertEqual(3, len(list((store/'history').glob('*.json'))))
            # Unique log file, preserving earlier evidence rather than replacing it.
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf8', suffix='.log',
                    prefix='managed-launcher-', dir=ROOT/'target', delete=False) as evidence:
                evidence.write('\n\n'.join(logs)); logname = evidence.name
            print('MANAGED_LAUNCH_UPDATE_ROLLBACK_OK A->B->A; main window+localhost proxy+7 features; retained runtime; evidence='+logname)

if __name__ == '__main__': unittest.main()
