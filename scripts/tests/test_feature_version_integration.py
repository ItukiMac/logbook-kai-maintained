"""Actual feature bytecode A -> B -> A through the ordinary Launcher/menu.

Only freshly copied, synthetic bundles and runtime directories are used. A and B
have different code, declared versions, calculation results and saved values.
No deployed plugin, account, Mint machine or production installation is read.
The generated bundles, failed stages, requests and receipts are retained in a
new temporary directory for review; evidence is written under target and the
source fat JAR is never changed.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZIP_DEFLATED

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import feature_bundle_manager as m
from build_feature_distribution import build, write_jar

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).with_name('fixtures') / 'feature_version'
HOOK = 'logbook/internal/logger/FeatureVersionSmokeHook.class'
CALCULATORS = 'features/logbook-kai2-feature-calculators.jar'
DESCRIPTOR = 'META-INF/logbook/feature.json'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root):
    return {p.relative_to(root).as_posix(): digest(p)
            for p in sorted(root.rglob('*')) if p.is_file()}


def clean_environment():
    return {k: v for k, v in os.environ.items() if k not in (
        'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')}


@unittest.skipUnless((ROOT / 'target/logbook-kai2.jar').is_file()
                    and shutil.which('javac') and shutil.which('xvfb-run'),
                    'Requires built current JAR, javac, and isolated Xvfb')
class FeatureVersionIntegrationTest(unittest.TestCase):
    def run_checked(self, command, *, cwd, logs, timeout=60):
        result = subprocess.run(command, cwd=cwd, env=clean_environment(),
                                capture_output=True, text=True, timeout=timeout)
        logs.append(result.stdout + result.stderr)
        self.assertEqual(0, result.returncode, logs[-1][-16000:])
        return result

    def variant(self, pristine, target, version, api, multiplier, logs, declared_api=None):
        """Recompile real provider/controller bytecode; rewrite only a NEW copy."""
        shutil.copytree(pristine, target)
        report = json.loads((target / 'distribution.json').read_text(encoding='utf8'))
        module = next(item for item in report['modules'] if item['id'] == 'calculators')
        source = target.parent / (target.name + '-source')
        classes = target.parent / (target.name + '-classes')
        source.mkdir(); classes.mkdir()
        substitutions = {'@VERSION@': version, '@HOST_API@': str(api),
                         '@MULTIPLIER@': str(multiplier),
                         '@API_CHECK@': 'requireFutureApi();' if api != 1 else ''}
        sources = []
        for template in sorted(FIXTURES.glob('*.java*')):
            text = template.read_text(encoding='utf8')
            for key, value in substitutions.items():
                text = text.replace(key, value)
            path = source / template.name.removesuffix('.in')
            path.write_text(text, encoding='utf8'); sources.append(str(path))
        pristine_before_compile = inventory(pristine)
        classpath = os.pathsep.join(str(pristine / name) for name in m.JARS)
        self.run_checked(['javac', '-proc:none', '--release', '21', '-encoding', 'UTF-8',
                          '-cp', classpath, '-d', str(classes), *sources], cwd=source, logs=logs)
        self.assertEqual(pristine_before_compile, inventory(pristine),
                         'Compiling a fixture must not change the pristine distribution')
        module.update(version=version, host_api=api if declared_api is None else declared_api)
        metadata = {key: value for key, value in module.items() if key not in ('path', 'sha256')}
        with ZipFile(pristine / CALCULATORS) as jar:
            entries = {item.filename: jar.read(item) for item in jar.infolist() if not item.is_dir()}
        for path in classes.rglob('*.class'):
            entries[path.relative_to(classes).as_posix()] = path.read_bytes()
        entries['logbook/gui/prob_calc.fxml'] = (FIXTURES / 'prob_calc.fxml').read_bytes()
        entries[DESCRIPTOR] = (json.dumps(metadata, sort_keys=True, indent=2) + '\n').encode()
        manifest = entries['META-INF/MANIFEST.MF'].decode('utf8')
        manifest, replacements = re.subn(r'(?m)^Implementation-Version: [^\r\n]+',
                                         'Implementation-Version: ' + version, manifest)
        self.assertEqual(1, replacements, 'The feature JAR must carry its implementation version')
        entries['META-INF/MANIFEST.MF'] = manifest.encode('utf8')
        # Preserve even the pre-edit copy instead of replacing it in place.
        path = target / CALCULATORS
        original_copy = target.parent / (target.name + '-original-calculators.jar')
        shutil.copyfile(path, original_copy)
        revised = target.parent / (target.name + '-revised-calculators.jar')
        write_jar(revised, entries)
        # This is a new isolated fixture directory, never an installed version.
        shutil.copyfile(revised, path)
        report['files'][CALCULATORS] = digest(path)
        module['sha256'] = report['files'][CALCULATORS]
        saved_manifest = target.parent / (target.name + '-original-distribution.json')
        shutil.copyfile(target / 'distribution.json', saved_manifest)
        (target / 'distribution.json').write_text(json.dumps(report, sort_keys=True, indent=2) + '\n',
                                                 encoding='utf8')
        return digest(target / 'distribution.json')

    def install_smoke_hook(self, runtime):
        self.assertTrue((ROOT / 'target/test-classes' / HOOK).is_file(),
                        'Maven must compile the dedicated real-Launcher test hook')
        (runtime / 'config').mkdir(); (runtime / 'plugins').mkdir()
        with socket.socket() as port:
            port.bind(('127.0.0.1', 0)); number = port.getsockname()[1]
        config = dict(checkUpdate=True, checkDoit=False, listenPort=number,
                      allowOnlyFromLocalhost=True, useProxy=False, usePlugin=True,
                      useSound=False, useToast=False)
        path = runtime / 'config/logbook.bean.AppConfig.json'
        path.write_text(json.dumps(config), encoding='utf8')
        (runtime / 'original-fixture-config.json').write_bytes(path.read_bytes())
        with ZipFile(runtime / 'plugins/feature-version-test-hook.jar', 'x', ZIP_DEFLATED) as jar:
            jar.write(ROOT / 'target/test-classes' / HOOK, HOOK)
            jar.writestr('META-INF/services/logbook.plugin.lifecycle.StartUp',
                         'logbook.internal.logger.FeatureVersionSmokeHook\n')
        (runtime / 'retained-history-sentinel.csv').write_bytes(b'synthetic history retained\n')
        (runtime / 'feature-fixture-requests').mkdir()
        return number

    def launch(self, store, run_id, version, expected, number, logs):
        runtime = store / 'runtime'
        request = runtime / 'feature-fixture-requests' / (run_id + '.properties')
        with request.open('x', encoding='utf8') as stream:
            stream.write(f'run={run_id}\nversion={version}\ninput=5\nexpected={expected}\n')
        result = self.run_checked(['xvfb-run', '-a', sys.executable, '-S',
                                  str(ROOT / 'scripts/feature_bundle_manager.py'),
                                  '--store', str(store), 'launch'], cwd=runtime, logs=logs)
        self.assertIn('FEATURE_VERSION_REAL_LAUNCHER_OK', result.stdout + result.stderr)
        saved = f'{version}\n{expected}\n'
        self.assertEqual(saved, (runtime / 'feature-fixture-results' / (run_id + '.txt')).read_text())
        receipt = runtime / 'feature-fixture-receipts' / (run_id + '.txt')
        self.assertEqual('MAIN_MENU_WINDOW_CALCULATION_SAVE_OK\n', receipt.read_text())
        config = json.loads((runtime / 'config/logbook.bean.AppConfig.json').read_text())
        self.assertEqual(number, config['listenPort'])
        self.assertTrue(config['allowOnlyFromLocalhost'])
        self.assertFalse(config['useProxy'])
        self.assertEqual(b'synthetic history retained\n',
                         (runtime / 'retained-history-sentinel.csv').read_bytes())

    def test_real_module_update_refusal_failure_and_whole_rollback(self):
        # Keep the running store separate from Maven's output tree; parallel
        # builds must not treat an in-use fixture JAR as a build artifact.
        base = Path(tempfile.mkdtemp(prefix='logbook-kai2-feature-version-fixture-'))
        logs = []
        evidence = {'fixture_root': str(base), 'production_accessed': False,
                    'original_and_failed_outputs_retained': True}
        try:
            original = ROOT / 'target/logbook-kai2.jar'
            original_hash = digest(original)
            pristine = base / 'pristine'
            revision = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=ROOT, check=True,
                                      capture_output=True, text=True).stdout.strip()
            evidence['checkout'] = revision
            report = build(original, pristine, revision)
            self.assertEqual('logbook-kai2-feature-distribution-v4', report['format'])
            variants = [('A', '1.0.0', 1, 2), ('B', '1.1.0', 1, 3),
                        ('C', '2.0.0', 2, 5), ('D', '1.2.0', 1, 4),
                        ('F', '1.0.0', 1, 4)]
            ids = {label: self.variant(pristine, base / label, version, api, multiplier, logs)
                   for label, version, api, multiplier in variants}
            # A self-consistent JSON contract can still contradict its executable
            # provider. The separate JVM preflight must detect this before Launcher.
            ids['E'] = self.variant(pristine, base / 'E', '2.0.1', 2, 7, logs, declared_api=1)
            snapshots = {label: inventory(base / label) for label in ids}
            self.assertNotEqual(digest(base / 'A' / CALCULATORS), digest(base / 'B' / CALCULATORS))
            with ZipFile(base / 'A' / CALCULATORS) as a, ZipFile(base / 'B' / CALCULATORS) as b:
                controller = 'logbook/internal/gui/FeatureVersionFixtureController.class'
                self.assertNotEqual(a.read(controller), b.read(controller), 'A/B must have distinct executable code')
            with ZipFile(base / 'A' / CALCULATORS) as a, ZipFile(base / 'F' / CALCULATORS) as f:
                self.assertNotEqual(a.read(controller), f.read(controller),
                                    'Reused version F must contain different executable code from A')
            m.bundle(base / 'F', ids['F'])  # Valid alone, but conflicts with retained A.
            f_preflight = base / 'reused-version-preflight'
            f_preflight.mkdir(); (f_preflight / 'home').mkdir()
            f_classpath = os.pathsep.join(str(base / 'F' / name) for name in m.JARS)
            f_result = self.run_checked(['java', '-Dlogbook.runtime.managedBundle=true',
                '-Duser.home=' + str(f_preflight / 'home'), '-cp', f_classpath,
                'logbook.core.FeatureBundlePreflight'], cwd=f_preflight, logs=logs)
            self.assertIn('FEATURE_BUNDLE_PREFLIGHT_OK', f_result.stdout.splitlines())
            store = base / 'store'
            m.initialize(store); m.stage(store, base / 'A', ids['A']); m.activate(store, ids['A'], None)
            runtime = store / 'runtime'
            number = self.install_smoke_hook(runtime)
            preserved_hook = digest(runtime / 'plugins/feature-version-test-hook.jar')
            self.launch(store, '01-A', '1.0.0', 10, number, logs)

            before_runtime = inventory(runtime)
            before_selector = (store / 'current.json').read_bytes()
            with self.assertRaises(ValueError):
                m.stage_feature(store, base / 'C', ids['C'], 'calculators', ids['A'])
            self.assertEqual(before_selector, (store / 'current.json').read_bytes())
            self.assertEqual(before_runtime, inventory(runtime))
            reject = base / 'registry-rejection'; reject.mkdir(); (reject / 'home').mkdir()
            cp = os.pathsep.join(str(base / 'C' / name) for name in m.JARS)
            result = self.run_checked(['java', '-Dlogbook.runtime.managedBundle=true',
                                      '-Duser.home=' + str(reject / 'home'), '-cp', cp,
                                      'logbook.fixture.FeatureVersionRegistryProbe'], cwd=reject, logs=logs)
            self.assertIn('INCOMPATIBLE_REGISTRY_REJECTED_BEFORE_FEATURES', result.stdout + result.stderr)
            self.assertFalse((reject / 'incompatible-features-entered.txt').exists())

            refused_store = base / 'refused-store'
            m.initialize(refused_store)
            m.stage(refused_store, base / 'E', ids['E'])
            m.activate(refused_store, ids['E'], None)
            self.install_smoke_hook(refused_store / 'runtime')
            refused_before = inventory(refused_store / 'runtime')
            refused = subprocess.run(['xvfb-run', '-a', sys.executable, '-S',
                str(ROOT / 'scripts/feature_bundle_manager.py'), '--store', str(refused_store), 'launch'],
                cwd=refused_store / 'runtime', env=clean_environment(), capture_output=True,
                text=True, timeout=60)
            logs.append(refused.stdout + refused.stderr)
            self.assertNotEqual(0, refused.returncode, logs[-1])
            self.assertIn('Unknown host API 2', logs[-1])
            self.assertNotIn('FEATURE_BUNDLE_PREFLIGHT_OK', logs[-1])
            self.assertNotIn('FEATURE_VERSION_REAL_LAUNCHER_OK', logs[-1])
            self.assertEqual(refused_before, inventory(refused_store / 'runtime'),
                             'A rejected provider must not start Launcher or change runtime data')

            # An actual partially written stage must remain inspectable, and its
            # failure must not touch the active selector or any runtime data.
            real_write = m.write_new
            writes = 0
            def interrupted_write(path, data):
                nonlocal writes
                writes += 1
                if writes == 2:
                    raise OSError('synthetic feature-copy interruption')
                return real_write(path, data)
            with patch.object(m, 'write_new', side_effect=interrupted_write):
                with self.assertRaises(OSError):
                    m.stage_feature(store, base / 'D', ids['D'], 'calculators', ids['A'])
            self.assertGreaterEqual(writes, 2)
            self.assertEqual(before_selector, (store / 'current.json').read_bytes())
            self.assertEqual(before_runtime, inventory(runtime))
            self.assertTrue(list((store / 'versions').glob('pending-*')))

            selected = m.stage_feature(store, base / 'B', ids['B'], 'calculators', ids['A'])
            bid = selected['version']
            self.assertFalse(selected['activated'])
            self.assertEqual(ids['A'], m.selector(store)['current'])
            self.assertEqual(before_runtime, inventory(runtime))
            bdir = store / 'versions' / bid
            for name in m.FILES:
                if name != CALCULATORS:
                    self.assertEqual((base / 'A' / name).read_bytes(), (bdir / name).read_bytes(), name)
            self.assertEqual((base / 'B' / CALCULATORS).read_bytes(), (bdir / CALCULATORS).read_bytes())
            m.activate(store, bid, ids['A'])
            self.assertEqual(before_runtime, inventory(runtime))
            self.launch(store, '02-B', '1.1.0', 15, number, logs)

            # F declares A's old version but computes input * 4. Compare against
            # retained A even while B is selected; neither staging path may admit
            # a second executable identity for that same core/module/version.
            before_store = inventory(store)
            before_entries = sorted(p.relative_to(store).as_posix() for p in store.rglob('*'))
            for operation in ('stage-feature', 'stage'):
                with self.assertRaisesRegex(ValueError, 'new module version', msg=operation):
                    if operation == 'stage-feature':
                        m.stage_feature(store, base / 'F', ids['F'], 'calculators', bid)
                    else:
                        m.stage(store, base / 'F', ids['F'])
                self.assertEqual(before_store, inventory(store),
                                 'Refused version reuse must preserve all store/runtime bytes')
                self.assertEqual(before_entries, sorted(
                    p.relative_to(store).as_posix() for p in store.rglob('*')),
                    'Refused version reuse must not create a pending/history entry')
            logs.append('FEATURE_VERSION_REUSE_REFUSED same_core=true version=1.0.0 '
                        'different_bytecode=true single_stage=true whole_stage=true '
                        'store_and_runtime_unchanged=true')

            before_runtime = inventory(runtime)
            with patch.object(m.os, 'replace', side_effect=OSError('synthetic selector interruption')):
                with self.assertRaises(OSError):
                    m.activate(store, '', bid, rollback=True)
            self.assertEqual(bid, m.selector(store)['current'])
            self.assertEqual(before_runtime, inventory(runtime))
            self.assertTrue(list(store.glob('selector-*.tmp')))
            self.launch(store, '03-B-after-failed-switch', '1.1.0', 15, number, logs)
            before_runtime = inventory(runtime)
            m.activate(store, '', bid, rollback=True)
            self.assertEqual(ids['A'], m.selector(store)['current'])
            self.assertEqual(before_runtime, inventory(runtime))
            self.launch(store, '04-A-restored', '1.0.0', 10, number, logs)

            self.assertEqual(original_hash, digest(original))
            for label, snapshot in snapshots.items():
                self.assertEqual(snapshot, inventory(base / label))
            for identity in (ids['A'], bid):
                m.bundle(store / 'versions' / identity, identity)
            self.assertEqual(4, len(list((store / 'history').glob('*.json'))))
            self.assertEqual(preserved_hook, digest(runtime / 'plugins/feature-version-test-hook.jar'))
            self.assertEqual('1.0.0\n10\n', (runtime / 'feature-fixture-results/01-A.txt').read_text())
            self.assertEqual('1.1.0\n15\n', (runtime / 'feature-fixture-results/02-B.txt').read_text())
            evidence.update(result='passed', initial_bundle=ids['A'], updated_bundle=bid,
                            calculations=[10, 15, 15, 10], launch_count=4,
                            refused_launch_count=1, executable_metadata_mismatch_refused=True,
                            incompatible_rejected_before_features=True, interrupted_stage_retained=True,
                            failed_selector_retained=True, source_bytes_unchanged=True,
                            historical_version_reuse_single_stage_refused=True,
                            historical_version_reuse_whole_stage_refused=True,
                            version_reuse_store_and_runtime_unchanged=True,
                            reused_version_candidate_preflight_accepted=True)
            print('FEATURE_SINGLE_UPDATE_ROLLBACK_OK versions=1.0.0->1.1.0->1.0.0 '
                  'calculation=10,15,15,10 actual_menu_and_save=true incompatible_refused=true '
                  'partial_failure_retained=true original_and_runtime_retained=true '
                  'historical_version_reuse_refused=true')
        except BaseException as failure:
            evidence.update(result='failed', failure=repr(failure))
            raise
        finally:
            location = ROOT / 'target/r3-verification'
            if not location.is_dir():
                location = ROOT / 'target'
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf8', suffix='.log',
                    prefix='feature-version-', dir=location, delete=False) as output:
                output.write('\n\n'.join(logs)); evidence['log'] = output.name
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf8', suffix='.json',
                    prefix='feature-version-', dir=location, delete=False) as output:
                json.dump(evidence, output, indent=2); output.write('\n')
                print('FEATURE_VERSION_EVIDENCE=' + output.name)


if __name__ == '__main__':
    unittest.main()
