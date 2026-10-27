"""Real split base/support GUI, calculation, settings save/re-read, synthetic history."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_feature_distribution import build
from run_feature_distribution import FILES

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless((ROOT / 'target/logbook-kai.jar').is_file()
                     and shutil.which('xvfb-run') and shutil.which('javac'),
                     'Requires a built current JAR, javac and isolated Xvfb')
class FeatureSupportIntegrationTest(unittest.TestCase):
    def test_split_base_support_windows_calculate_save_and_reread(self):
        original = ROOT / 'target/logbook-kai.jar'
        source_hash = hashlib.sha256(original.read_bytes()).hexdigest()
        logs = []
        try:
            with tempfile.TemporaryDirectory(prefix='feature-support-fixture-') as temp:
                root = Path(temp)
                dist = root / 'distribution'
                build(original, dist, 'a' * 40)
                runtime = root / 'runtime'
                runtime.mkdir()
                classes = root / 'probe-classes'
                classes.mkdir()
                cp = os.pathsep.join(str(dist / name) for name in FILES if name.endswith('.jar'))
                compile_result = subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', cp, '-d', str(classes),
                    str(ROOT / 'scripts/java/SupportFeatureProbe.java')], capture_output=True, text=True, timeout=45)
                logs.append(compile_result.stdout + compile_result.stderr)
                self.assertEqual(0, compile_result.returncode, logs[-1][-12000:])
                env = {key: value for key, value in os.environ.items() if key not in (
                    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')}
                run = subprocess.run(['xvfb-run', '-a', 'java', '-Dlogbook.data.autoUpdate=false',
                    '-Duser.home=' + str(root / 'home'), '-cp', str(classes) + os.pathsep + cp,
                    'logbook.internal.gui.SupportFeatureProbe', 'synthetic-disposable-runtime'],
                    cwd=runtime, env=env, capture_output=True, text=True, timeout=60)
                logs.append(run.stdout + run.stderr)
                self.assertEqual(0, run.returncode, logs[-1][-16000:])
                self.assertIn('SPLIT_SUPPORT_GUI_SAVE_OK', logs[-1])
                self.assertEqual(source_hash, hashlib.sha256(original.read_bytes()).hexdigest())
        finally:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf8', suffix='.log',
                    prefix='split-support-', dir=ROOT / 'target', delete=False) as evidence:
                evidence.write('\n\n'.join(logs))
                name = evidence.name
        print('SPLIT_SUPPORT_GUI_SAVE_OK windows=2 synthetic-only; evidence=' + name)


if __name__ == '__main__':
    unittest.main()
