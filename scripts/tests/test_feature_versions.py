"""Version contracts and copy-only single-module updates, using synthetic JARs."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile, ZIP_DEFLATED

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_feature_distribution as builder
import feature_bundle_manager as manager
from run_feature_distribution import verify, FEATURE_METADATA, METADATA_KEYS, EXTRA_CLASSES


class FeatureVersionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.original = self.root / 'original.jar'
        entries = {
            'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\n\r\n',
            'logbook/gui/application.css': b'.root {}',
            'logbook/internal/Launcher.class': b'not executable; unit fixture',
            'logbook/internal/updatecheck/ManagedBundleMode.class': b'guard fixture',
            builder.SERVICE: ('\n'.join('logbook.features.' + p[0]
                                       for p in builder.GROUPS.values()) + '\n').encode(),
        }
        for provider, controllers, resources in builder.GROUPS.values():
            for stem in ['logbook/features/' + provider] + [
                    'logbook/internal/gui/' + c for c in controllers]:
                entries[stem + '.class'] = b'synthetic class inventory'
            for resource in resources:
                entries['logbook/gui/' + resource + '.fxml'] = b'<fixture/>'
        for stems in EXTRA_CLASSES.values():
            for stem in stems:
                entries[stem + '.class'] = b'synthetic helper class'
        with ZipFile(self.original, 'x', compression=ZIP_DEFLATED) as jar:
            for name, data in entries.items():
                jar.writestr(name, data)
        self.a = self.root / 'a'
        self.report = builder.build(self.original, self.a, 'a' * 40)

    def rewrite(self, report, root=None):
        root = root or self.a
        (root / 'distribution.json').write_text(json.dumps(report), encoding='utf8')
        return hashlib.sha256((root / 'distribution.json').read_bytes()).hexdigest()

    def repack(self, root, report, module_id, **changes):
        module = next(m for m in report['modules'] if m['id'] == module_id)
        module.update(changes)
        path = root / module['path']
        with ZipFile(path) as jar:
            entries = {entry.filename: jar.read(entry) for entry in jar.infolist()}
        entries[FEATURE_METADATA] = json.dumps({key: module[key] for key in METADATA_KEYS}).encode()
        entries['META-INF/MANIFEST.MF'] = (
            'Manifest-Version: 1.0\r\nImplementation-Version: ' + module['version'] + '\r\n\r\n').encode()
        entries['fixture-release.txt'] = module['version'].encode()
        with ZipFile(path, 'w', ZIP_DEFLATED) as jar:
            for name, data in entries.items():
                jar.writestr(name, data)
        module['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        report['files'][module['path']] = module['sha256']
        return module

    def candidate(self, label='b', **changes):
        root = self.root / label
        shutil.copytree(self.a, root)
        report = deepcopy(self.report)
        self.repack(root, report, 'calculators', **changes)
        return root, report, self.rewrite(report, root)

    def start_store(self):
        self.store = self.root / 'store'
        manager.initialize(self.store)
        identity = self.rewrite(self.report)
        manager.stage(self.store, self.a, identity)
        manager.activate(self.store, identity, None)
        return identity

    def test_build_declares_versioned_module_contracts(self):
        self.assertEqual('logbook-feature-distribution-v2', self.report['format'])
        self.assertEqual(1, self.report['host']['api'])
        self.assertEqual({'calculators', 'charts', 'status'},
                         {m['id'] for m in self.report['modules']})
        self.assertEqual(7, sum(len(m['features']) for m in self.report['modules']))
        for module in self.report['modules']:
            self.assertEqual('1.0.0', module['version'])
            self.assertEqual(1, module['host_api'])
            self.assertEqual(self.report['files'][module['path']], module['sha256'])

    def test_missing_version_contract_is_rejected_before_java(self):
        report = deepcopy(self.report)
        report.pop('host', None)
        report.pop('modules', None)
        self.rewrite(report)
        with self.assertRaises(ValueError):
            verify(self.a)

    def test_duplicate_manifest_keys_are_not_last_writer_wins(self):
        raw = json.dumps(self.report)
        raw = raw[:-1] + ',"files":' + json.dumps(self.report['files']) + '}'
        (self.a / 'distribution.json').write_text(raw, encoding='utf8')
        with self.assertRaises(ValueError):
            verify(self.a)

    def test_single_module_update_and_whole_rollback_retain_all_original_data(self):
        candidate, _, candidate_id = self.candidate(version='1.1.0')
        current = self.start_store()
        original = {name: (self.a / name).read_bytes() for name in manager.FILES + ('distribution.json',)}
        runtime = self.store / 'runtime'
        (runtime / 'config.json').write_bytes(b'original synthetic settings')
        (runtime / 'history.csv').write_bytes(b'original synthetic history')
        result = manager.stage_feature(self.store, candidate, candidate_id, 'calculators', current)
        updated = result['version']
        self.assertFalse(result['activated'])
        self.assertEqual(current, manager.selector(self.store)['current'])
        selected = self.store / 'versions' / updated
        verify(selected)
        for name in manager.FILES:
            origin = candidate if name == 'features/logbook-feature-calculators.jar' else self.a
            self.assertEqual((origin / name).read_bytes(), (selected / name).read_bytes(), name)
        manager.activate(self.store, updated, current)
        manager.activate(self.store, '', updated, rollback=True)
        self.assertEqual(current, manager.selector(self.store)['current'])
        for name, data in original.items():
            self.assertEqual(data, (self.a / name).read_bytes(), name)
        for identity in (current, updated):
            manager.bundle(self.store / 'versions' / identity, identity)
        self.assertEqual(b'original synthetic settings', (runtime / 'config.json').read_bytes())
        self.assertEqual(b'original synthetic history', (runtime / 'history.csv').read_bytes())
        self.assertEqual(3, len(list((self.store / 'history').glob('*.json'))))

    def test_single_update_checks_current_consumers_not_only_candidate_graph(self):
        self.repack(self.a, self.report, 'charts', dependencies=[
            {'id': 'calculators', 'min_version': '1.0.0', 'max_version_exclusive': '1.1.0'}])
        self.rewrite(self.report)
        current = self.start_store()
        candidate, report, _ = self.candidate(version='1.1.0')
        self.repack(candidate, report, 'charts', version='1.1.0', dependencies=[
            {'id': 'calculators', 'min_version': '1.0.0', 'max_version_exclusive': '2.0.0'}])
        candidate_id = self.rewrite(report, candidate)
        verify(candidate)  # The entire proposed release is internally compatible.
        with self.assertRaisesRegex(ValueError, 'Incompatible dependency'):
            manager.stage_feature(self.store, candidate, candidate_id, 'calculators', current)
        self.assertEqual(current, manager.selector(self.store)['current'])
        self.assertFalse(list((self.store / 'versions').glob('pending-*')))

    def test_all_three_modules_keep_unselected_candidate_changes_out(self):
        candidate, report, _ = self.candidate(version='1.1.0')
        for module_id in ('charts', 'status'):
            self.repack(candidate, report, module_id, version='1.1.0')
        candidate_id = self.rewrite(report, candidate)
        verify(candidate)
        current = self.start_store()
        for module_id in ('calculators', 'charts', 'status'):
            with self.subTest(module=module_id):
                result = manager.stage_feature(self.store, candidate, candidate_id, module_id, current)
                staged = self.store / 'versions' / result['version']
                verify(staged)
                selected_path = 'features/logbook-feature-' + module_id + '.jar'
                for name in manager.FILES:
                    origin = candidate if name == selected_path else self.a
                    self.assertEqual((origin / name).read_bytes(), (staged / name).read_bytes(), name)
                self.assertEqual(current, manager.selector(self.store)['current'])

    def test_numeric_semver_range_accepts_tenth_minor(self):
        self.repack(self.a, self.report, 'calculators', version='1.10.0')
        self.repack(self.a, self.report, 'charts', dependencies=[
            {'id': 'calculators', 'min_version': '1.9.0', 'max_version_exclusive': '2.0.0'}])
        self.rewrite(self.report)
        verify(self.a)

    def test_changed_bytes_cannot_reuse_a_module_version(self):
        current = self.start_store()
        candidate, _, candidate_id = self.candidate(version='1.0.0')
        with self.assertRaisesRegex(ValueError, 'new module version'):
            manager.stage_feature(self.store, candidate, candidate_id, 'calculators', current)
        self.assertEqual(current, manager.selector(self.store)['current'])

    def test_same_module_is_noop_and_repeated_staging_is_idempotent(self):
        current = self.start_store()
        unchanged = manager.stage_feature(self.store, self.a, current, 'calculators', current)
        self.assertTrue(unchanged['unchanged'])
        candidate, _, candidate_id = self.candidate(version='1.1.0')
        first = manager.stage_feature(self.store, candidate, candidate_id, 'calculators', current)
        second = manager.stage_feature(self.store, candidate, candidate_id, 'calculators', current)
        self.assertEqual(first['version'], second['version'])
        self.assertTrue(second['already_staged'])
        self.assertEqual(current, manager.selector(self.store)['current'])

    def test_stale_current_and_unknown_module_refuse_before_staging(self):
        current = self.start_store()
        candidate, _, candidate_id = self.candidate(version='1.1.0')
        for module, expected in (('calculators', 'f' * 64), ('unknown', current)):
            with self.subTest(module=module, expected=expected):
                with self.assertRaises(ValueError):
                    manager.stage_feature(self.store, candidate, candidate_id, module, expected)
        self.assertEqual(current, manager.selector(self.store)['current'])
        self.assertFalse(list((self.store / 'versions').glob('pending-*')))

    def test_interrupted_single_copy_retains_pending_and_original_selector(self):
        current = self.start_store()
        candidate, _, candidate_id = self.candidate(version='1.1.0')
        original_write = manager.write_new
        copies = 0

        def fail_during_copy(path, data):
            nonlocal copies
            copies += 1
            if copies == 2:
                raise OSError('synthetic disk full')
            return original_write(path, data)

        with patch.object(manager, 'write_new', side_effect=fail_during_copy):
            with self.assertRaises(OSError):
                manager.stage_feature(self.store, candidate, candidate_id, 'calculators', current)
        self.assertEqual(current, manager.selector(self.store)['current'])
        pending = list((self.store / 'versions').glob('pending-*'))
        self.assertEqual(1, len(pending))
        self.assertTrue((pending[0] / 'logbook-core.jar').is_file())
        self.assertFalse((pending[0] / 'distribution.json').exists())
        manager.bundle(self.store / 'versions' / current, current)

    def test_legacy_v1_is_retained_but_not_silently_treated_as_versioned(self):
        report = deepcopy(self.report)
        report['format'] = 'logbook-feature-distribution-v1'
        report.pop('host')
        report.pop('modules')
        self.rewrite(report)
        before = (self.a / 'distribution.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'legacy v1 is not launchable'):
            verify(self.a)
        self.assertEqual(before, (self.a / 'distribution.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
