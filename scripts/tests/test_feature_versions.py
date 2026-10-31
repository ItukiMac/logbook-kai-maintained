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
        for service in ('logbook.plugin.lifecycle.StartUp', 'logbook.listener.ContentListenerSpi'):
            entries['META-INF/services/' + service] = (b'local.kancolle.bridge.KancolleBridgeStartUp\n' if service == 'logbook.plugin.lifecycle.StartUp' else b'') + b'local.kancolle.kqs.KqsLogbookPlugin\n'
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

    def retained_snapshot(self):
        """Include sources, retained versions, failed evidence and runtime data."""
        return {str(path.relative_to(self.root)):
                ('directory' if path.is_dir() else hashlib.sha256(path.read_bytes()).hexdigest())
                for path in self.root.rglob('*')}

    def assert_refused_without_changes(self, operation):
        before = self.retained_snapshot()
        with self.assertRaises(ValueError):
            operation()
        self.assertEqual(before, self.retained_snapshot(),
                         'Refusal must preserve every existing byte and create no stage/history entry')

    def selected_b_after_a(self):
        original = self.start_store()
        runtime = self.store / 'runtime'
        (runtime / 'plugins').mkdir()
        (runtime / 'backups').mkdir()
        for name, data in {
                'config.json': b'original synthetic configuration',
                'history.csv': b'original synthetic user history',
                'plugins/kept.jar': b'synthetic plugin bytes',
                'backups/config.json': b'older synthetic configuration'}.items():
            (runtime / name).write_bytes(data)
        candidate, _, candidate_id = self.candidate(version='1.1.0')
        updated = manager.stage_feature(self.store, candidate, candidate_id,
                                        'calculators', original)['version']
        manager.activate(self.store, updated, original)
        return original, updated

    def preserve_then_corrupt(self, path):
        # Fault injection only in this test's fresh synthetic fixture. Keep the
        # pre-fault bytes separately so even rejected evidence remains available.
        shutil.copy2(path, self.root / ('preserved-' + path.name))
        path.write_bytes(b'{ deliberately invalid synthetic metadata')

    def test_build_declares_versioned_module_contracts(self):
        self.assertEqual('logbook-feature-distribution-v4', self.report['format'])
        self.assertEqual(1, self.report['host']['api'])
        self.assertEqual({'calculators', 'charts', 'ships', 'equipment', 'quests', 'records', 'klb', 'kqs'},
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

    def test_all_eight_modules_keep_unselected_candidate_changes_out(self):
        candidate, report, _ = self.candidate(version='1.1.0')
        for module_id in ('charts', 'ships', 'equipment', 'quests', 'records', 'klb', 'kqs'):
            self.repack(candidate, report, module_id, version='1.1.0')
        candidate_id = self.rewrite(report, candidate)
        verify(candidate)
        current = self.start_store()
        for module_id in ('calculators', 'charts', 'ships', 'equipment', 'quests', 'records', 'klb', 'kqs'):
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

    def test_old_version_number_cannot_be_reused_after_a_newer_selection(self):
        original, current = self.selected_b_after_a()
        candidate, report, candidate_id = self.candidate(label='changed-a', version='1.0.0')
        original_module = next(m for m in self.report['modules'] if m['id'] == 'calculators')
        changed_module = next(m for m in report['modules'] if m['id'] == 'calculators')
        self.assertEqual(original_module['version'], changed_module['version'])
        self.assertNotEqual(original_module['sha256'], changed_module['sha256'])
        verify(candidate)  # The candidate is valid in isolation; its identity is the conflict.
        self.assert_refused_without_changes(lambda: manager.stage_feature(
            self.store, candidate, candidate_id, 'calculators', current))
        self.assertEqual(current, manager.selector(self.store)['current'])
        manager.bundle(self.store / 'versions' / original, original)

    def test_whole_stage_cannot_reuse_a_historical_module_version(self):
        _, current = self.selected_b_after_a()
        candidate, _, candidate_id = self.candidate(label='changed-a', version='1.0.0')
        verify(candidate)
        self.assert_refused_without_changes(lambda: manager.stage(
            self.store, candidate, candidate_id))
        self.assertEqual(current, manager.selector(self.store)['current'])

    def test_activate_rejects_conflicting_bundle_already_present_in_store(self):
        _, current = self.selected_b_after_a()
        candidate, _, candidate_id = self.candidate(label='changed-a', version='1.0.0')
        # Represent an older manager or out-of-band copy that skipped stage's gate.
        shutil.copytree(candidate, self.store / 'versions' / candidate_id)
        manager.bundle(self.store / 'versions' / candidate_id, candidate_id)
        self.assert_refused_without_changes(lambda: manager.activate(
            self.store, candidate_id, current))

    def test_conflicting_retained_identity_blocks_launch_before_any_java_process(self):
        self.selected_b_after_a()
        candidate, _, candidate_id = self.candidate(label='changed-a', version='1.0.0')
        shutil.copytree(candidate, self.store / 'versions' / candidate_id)
        with patch.object(manager.subprocess, 'run') as run:
            self.assert_refused_without_changes(lambda: manager.launch(self.store))
        run.assert_not_called()

    def test_conflicting_store_cannot_bypass_validation_with_noop_operations(self):
        original, current = self.selected_b_after_a()
        candidate, _, candidate_id = self.candidate(label='changed-a', version='1.0.0')
        shutil.copytree(candidate, self.store / 'versions' / candidate_id)
        b = self.root / 'b'
        b_id = hashlib.sha256((b / 'distribution.json').read_bytes()).hexdigest()
        operations = {
            'single-module no-op': lambda: manager.stage_feature(
                self.store, b, b_id, 'calculators', current),
            'whole-stage no-op': lambda: manager.stage(self.store, self.a, original),
            'activate no-op': lambda: manager.activate(self.store, current, current),
        }
        for name, operation in operations.items():
            with self.subTest(operation=name):
                self.assert_refused_without_changes(operation)

    def test_single_update_ignores_unselected_candidate_version_reuse(self):
        _, current = self.selected_b_after_a()
        candidate, report, _ = self.candidate(label='new-c', version='1.2.0')
        self.repack(candidate, report, 'charts', version='1.0.0')
        candidate_id = self.rewrite(report, candidate)
        verify(candidate)
        # A whole import would adopt the changed charts 1.0.0 and is refused.
        self.assert_refused_without_changes(lambda: manager.stage(
            self.store, candidate, candidate_id))
        # Selecting calculators keeps the retained charts bytes instead.
        result = manager.stage_feature(self.store, candidate, candidate_id,
                                       'calculators', current)
        selected = self.store / 'versions' / result['version']
        manager.bundle(selected, result['version'])
        chart_path = 'features/logbook-feature-charts.jar'
        self.assertEqual((self.a / chart_path).read_bytes(), (selected / chart_path).read_bytes())
        self.assertNotEqual((candidate / chart_path).read_bytes(), (selected / chart_path).read_bytes())

    def test_failed_activation_intents_allow_retry_without_erasing_history(self):
        self.store = self.root / 'store'
        manager.initialize(self.store)
        original = self.rewrite(self.report)
        manager.stage(self.store, self.a, original)
        with patch.object(manager.os, 'replace', side_effect=OSError('synthetic initial interruption')):
            with self.assertRaises(OSError):
                manager.activate(self.store, original, None)
        self.assertIsNone(manager.selector(self.store))
        b, _, b_id = self.candidate(version='1.1.0')
        manager.stage(self.store, b, b_id)
        manager.activate(self.store, b_id, None)
        with patch.object(manager.os, 'replace', side_effect=OSError('synthetic later interruption')):
            with self.assertRaises(OSError):
                manager.activate(self.store, original, b_id)
        preserved = self.retained_snapshot()
        c, _, c_id = self.candidate(label='new-c', version='1.2.0')
        staged = manager.stage_feature(self.store, c, c_id, 'calculators', b_id)['version']
        manager.activate(self.store, staged, b_id)
        manager.activate(self.store, '', staged, rollback=True)
        self.assertEqual(b_id, manager.selector(self.store)['current'])
        after = self.retained_snapshot()
        for name, identity in preserved.items():
            if name != 'store/current.json':
                self.assertEqual(identity, after[name], name)

    def test_unknown_history_entry_is_not_silently_ignored(self):
        _, current = self.selected_b_after_a()
        (self.store / 'history' / 'unknown.json').write_text('{}')
        candidate, _, candidate_id = self.candidate(label='new-c', version='1.2.0')
        self.assert_refused_without_changes(lambda: manager.stage_feature(
            self.store, candidate, candidate_id, 'calculators', current))

    def test_selector_cannot_rewind_behind_a_recorded_committed_state(self):
        original, current = self.selected_b_after_a()
        with patch.object(manager.os, 'replace', side_effect=OSError('synthetic switch interruption')):
            with self.assertRaises(OSError):
                manager.activate(self.store, original, current)
        failed_a = manager.read_json(next((self.store / 'history').glob('3-*.json')))['after']
        c, _, c_id = self.candidate(label='new-c', version='1.2.0')
        manager.stage(self.store, c, c_id)
        manager.activate(self.store, c_id, current)
        # This intent proves C at sequence 3 was the selected state, even though
        # its next switch fails. The older A intent at the same sequence was not.
        with patch.object(manager.os, 'replace', side_effect=OSError('synthetic later interruption')):
            with self.assertRaises(OSError):
                manager.activate(self.store, current, c_id)
        first_a = manager.read_json(next((self.store / 'history').glob('1-*.json')))['after']
        selected = self.store / 'current.json'
        shutil.copy2(selected, self.root / 'preserved-current.json')
        d, _, d_id = self.candidate(label='new-d', version='1.3.0')
        for stale in (first_a, failed_a):
            with self.subTest(sequence=stale['sequence']):
                selected.write_bytes(manager.encoded(stale))  # Synthetic corruption; original retained.
                self.assert_refused_without_changes(lambda: manager.stage_feature(
                    self.store, d, d_id, 'calculators', stale['current']))

    def test_whole_stage_checks_retained_versions_before_any_activation(self):
        self.store = self.root / 'store'
        manager.initialize(self.store)
        original = self.rewrite(self.report)
        manager.stage(self.store, self.a, original)
        self.assertIsNone(manager.selector(self.store))
        candidate, _, candidate_id = self.candidate(label='changed-a', version='1.0.0')
        self.assert_refused_without_changes(lambda: manager.stage(
            self.store, candidate, candidate_id))

    def test_single_update_checks_versions_staged_but_never_selected(self):
        current = self.start_store()
        candidate, _, candidate_id = self.candidate(version='1.1.0')
        kept = manager.stage_feature(self.store, candidate, candidate_id,
                                     'calculators', current)['version']
        changed, _, changed_id = self.candidate(label='changed-b', version='1.1.0',
            dependencies=[{'id': 'charts', 'min_version': '1.0.0',
                           'max_version_exclusive': '2.0.0'}])
        verify(changed)
        self.assert_refused_without_changes(lambda: manager.stage_feature(
            self.store, changed, changed_id, 'calculators', current))
        manager.bundle(self.store / 'versions' / kept, kept)

    def test_genuine_original_can_be_restaged_and_rolled_back(self):
        original, current = self.selected_b_after_a()
        preserved = self.retained_snapshot()
        whole = manager.stage(self.store, self.a, original)
        self.assertTrue(whole['already_staged'])
        self.assertEqual(preserved, self.retained_snapshot())
        restaged = manager.stage_feature(self.store, self.a, original,
                                        'calculators', current)['version']
        self.assertEqual((self.a / 'features/logbook-feature-calculators.jar').read_bytes(),
                         (self.store / 'versions' / restaged /
                          'features/logbook-feature-calculators.jar').read_bytes())
        manager.activate(self.store, restaged, current)
        self.assertEqual(restaged, manager.selector(self.store)['current'])
        manager.activate(self.store, '', restaged, rollback=True)
        self.assertEqual(current, manager.selector(self.store)['current'])
        manager.activate(self.store, original, current)
        self.assertEqual(original, manager.selector(self.store)['current'])
        after = self.retained_snapshot()
        for name, identity in preserved.items():
            if name != 'store/current.json':
                self.assertEqual(identity, after[name], name)

    def test_same_module_bytes_with_new_bundle_provenance_are_allowed(self):
        original, current = self.selected_b_after_a()
        candidate = self.root / 'same-a-new-manifest'
        shutil.copytree(self.a, candidate)
        report = deepcopy(self.report)
        report['source_revision'] = 'c' * 40
        candidate_id = self.rewrite(report, candidate)
        self.assertNotEqual(original, candidate_id)
        verify(candidate)
        result = manager.stage(self.store, candidate, candidate_id)
        self.assertFalse(result['already_staged'])
        manager.activate(self.store, candidate_id, current)
        self.assertEqual(candidate_id, manager.selector(self.store)['current'])

    def test_same_module_version_for_a_different_core_is_a_separate_identity(self):
        original = self.start_store()
        other_source = self.root / 'other-core-source.jar'
        with ZipFile(self.original) as source, ZipFile(other_source, 'x', ZIP_DEFLATED) as target:
            for entry in source.infolist():
                target.writestr(entry, source.read(entry))
            target.writestr('different-core-fixture.txt', b'distinct core; synthetic only')
        candidate = self.root / 'other-core'
        report = builder.build(other_source, candidate, 'd' * 40)
        self.assertNotEqual(self.report['host']['sha256'], report['host']['sha256'])
        self.assertEqual({m['version'] for m in self.report['modules']},
                         {m['version'] for m in report['modules']})
        candidate_id = hashlib.sha256((candidate / 'distribution.json').read_bytes()).hexdigest()
        manager.stage(self.store, candidate, candidate_id)
        manager.activate(self.store, candidate_id, original)
        manager.activate(self.store, '', candidate_id, rollback=True)
        self.assertEqual(original, manager.selector(self.store)['current'])
        manager.bundle(self.store / 'versions' / candidate_id, candidate_id)

    def test_corrupt_retained_manifest_blocks_new_identity_without_repairing_evidence(self):
        original, current = self.selected_b_after_a()
        self.preserve_then_corrupt(self.store / 'versions' / original / 'distribution.json')
        candidate, _, candidate_id = self.candidate(label='new-c', version='1.2.0')
        self.assert_refused_without_changes(lambda: manager.stage_feature(
            self.store, candidate, candidate_id, 'calculators', current))

    def test_malformed_history_blocks_stage_and_activate_including_existing_target(self):
        _, current = self.selected_b_after_a()
        candidate, _, candidate_id = self.candidate(label='new-c', version='1.2.0')
        # Stage a non-conflicting target first to cover the already-staged fast path.
        manager.stage(self.store, candidate, candidate_id)
        self.preserve_then_corrupt(next((self.store / 'history').glob('1-*.json')))
        operations = {
            'single-module stage': lambda: manager.stage_feature(
                self.store, candidate, candidate_id, 'calculators', current),
            'whole-stage no-op': lambda: manager.stage(self.store, candidate, candidate_id),
            'activate': lambda: manager.activate(self.store, candidate_id, current),
        }
        for name, operation in operations.items():
            with self.subTest(operation=name):
                self.assert_refused_without_changes(operation)

    def test_missing_history_record_blocks_new_identity_registration(self):
        _, current = self.selected_b_after_a()
        path = next((self.store / 'history').glob('1-*.json'))
        path.rename(self.root / ('preserved-' + path.name))
        candidate, _, candidate_id = self.candidate(label='new-c', version='1.2.0')
        self.assert_refused_without_changes(lambda: manager.stage_feature(
            self.store, candidate, candidate_id, 'calculators', current))

    def test_missing_older_bundle_referenced_by_history_is_not_forgotten(self):
        original, current = self.selected_b_after_a()
        newest, _, newest_id = self.candidate(label='new-c', version='1.2.0')
        manager.stage(self.store, newest, newest_id)
        manager.activate(self.store, newest_id, current)
        self.assertNotEqual(original, manager.selector(self.store)['previous'])
        # Keep every byte, but make the earlier A unavailable under its store ID.
        (self.store / 'versions' / original).rename(self.root / 'preserved-original-version')
        candidate, _, candidate_id = self.candidate(label='changed-a', version='1.0.0')
        self.assert_refused_without_changes(lambda: manager.stage_feature(
            self.store, candidate, candidate_id, 'calculators', newest_id))

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
