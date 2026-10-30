"""Managed import/whole-store recovery use new synthetic TempDirs only."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import feature_bundle_manager as manager
import build_feature_distribution as feature_builder
from run_feature_distribution import PROVIDERS, required_entries
import runtime_snapshot as snapshots
import runtime_managed_import as migration


class RuntimeManagedImportTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='logbook-managed-import-fixture-')
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        self.source = self.base / 'original'
        self.source.mkdir()
        self.out = self.base / 'store'
        self.checkpoint = self.base / 'snapshot'
        self.limits = snapshots.Limits(reserve=0)
        self.config = {
            'reportPath': '.', 'performanceDir': 'performance', 'pluginsDir': 'plugins',
            'resourcesDir': 'resources', 'battleLogDir': 'battlelog', 'captureDir': 'images',
            'storeApiStart2Dir': 'api-start2', 'missionSoundDir': 'sounds/mission',
            'ndockSoundDir': 'sounds/ndock', 'alertSoundDir': 'sounds/alert',
            'defaultNotifySound': 'sounds/notify.wav', 'ffmpegPath': '',
            'usePlugin': False, 'checkUpdate': False, 'useSound': False,
        }
        for name in ('performance', 'plugins', 'resources', 'battlelog', 'images',
                     'api-start2', 'sounds/mission', 'sounds/ndock', 'sounds/alert', 'empty'):
            (self.source / name).mkdir(parents=True, exist_ok=True)
        self.write_config()
        self.write('config/logbook.bean.AppQuestDuration.json', json.dumps({
            'map': {'2025-06-01 05:00:00': {'905': [
                {'from': '2025-05-10 09:00:00', 'to': '2025-05-11 00:00:00'}]}}}).encode())
        self.write('customquest/10001.json', b'// preserved comment\r\n{"startDate":"2025-05-10 09:00:00"}\r\n')
        self.write('battlelog/legacy.bin', bytes(range(256)))
        self.write('images/fixture.png', b'opaque-image-sentinel')
        self.write('plugins/fixture.jar', b'opaque-plugin-never-executed')
        self.write('sounds/notify.wav', b'opaque-sound')
        self.write('retained-old/old.jar', b'old-application-sentinel')
        self.write('auto.sh', b'#!/bin/sh\n# preserve, never execute\n').chmod(0o751)
        self.write('.private', b'synthetic-private-sentinel')
        self.a, self.aid = self.make_bundle('a')
        self.b, self.bid = self.make_bundle('b')

    def write(self, name, value):
        target = self.source / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(value)
        return target

    def write_config(self, suffix=''):
        return self.write('config/logbook.bean.AppConfig.json' + suffix,
                          json.dumps(self.config, ensure_ascii=False).encode())

    def make_bundle(self, label):
        # This is an inert archive fixture, not an executable historical release.
        # Use the current packager so import/recovery exercises the same v2
        # hashes, embedded contracts and required inventory as the E manager.
        source = self.base / ('fixture-source-' + label + '.jar')
        entries = {
            'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\n\r\n',
            'fixture.txt': ('synthetic generation ' + label).encode(),
            'logbook/internal/updatecheck/ManagedBundleMode.class': b'unit-test-only',
            feature_builder.SERVICE: ('\n'.join(PROVIDERS.values()) + '\n').encode(),
        }
        for module_id in PROVIDERS:
            for name in required_entries(module_id):
                entries[name] = ('inert fixture ' + label + ':' + name).encode()
        for service in ('logbook.plugin.lifecycle.StartUp', 'logbook.listener.ContentListenerSpi'):
            entries['META-INF/services/' + service] = b'local.kancolle.bridge.KancolleBridgeStartUp\nlocal.kancolle.kqs.KqsLogbookPlugin\n'
        with ZipFile(source, 'x') as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        root = self.base / ('bundle-' + label)
        feature_builder.build(source, root, label * 40)
        raw = (root / 'distribution.json').read_bytes()
        return root, hashlib.sha256(raw).hexdigest()

    def add_arrival(self):
        record = {'format': 'logbook-port-arrival-v1',
                  'sortieId': '11111111-1111-4111-8111-111111111111',
                  'startedAt': 1746835260000, 'arrivedAt': 1746835800000,
                  'map': '1-6', 'deckId': 3, 'shipMasterIds': [101, 102], 'noRetreat': True}
        name = 'sortie-arrivals-v1/2025-05/' + record['sortieId'] + '.json'
        published = self.write(name, json.dumps(record, sort_keys=True).encode())
        pending = published.with_name('.' + record['sortieId'] + '-22222222-2222-4222-8222-222222222222.pending')
        os.link(published, pending)
        return published, pending

    def capture(self):
        self.original_inventory = snapshots.inventory(self.source, self.limits)
        self.sha = snapshots.snapshot(self.source, self.checkpoint, stopped=True,
                                      limits=self.limits)['manifest_sha256']

    def perform_import(self, profile='legacy-config-v1', **options):
        kwargs = dict(profile=profile, bundles=[(self.a, self.aid), (self.b, self.bid)],
                      selected_bundle_sha256=self.aid, confirm_import=True, limits=self.limits)
        kwargs.update(options)
        return migration.import_snapshot(self.checkpoint, self.out, self.sha, **kwargs)

    def test_arrival_import_preserves_both_generations_and_every_original_byte(self):
        published, pending = self.add_arrival()
        self.capture()
        report = self.perform_import('arrival-journal-v1')
        runtime = Path(report['runtime'])
        for name, row in self.original_inventory.items():
            if row['kind'] == 'file':
                self.assertEqual((self.source / name).read_bytes(), (runtime / name).read_bytes(), name)
        self.assertEqual(self.original_inventory, snapshots.inventory(self.source, self.limits))
        self.assertEqual(self.aid, manager.selector(self.out)['current'])
        for root, identity in ((self.a, self.aid), (self.b, self.bid)):
            self.assertEqual(manager.bundle(root, identity), manager.bundle(self.out / 'versions' / identity, identity))
        p = runtime / published.relative_to(self.source)
        q = runtime / pending.relative_to(self.source)
        self.assertNotEqual(p.stat().st_ino, q.stat().st_ino)
        self.assertNotEqual(p.stat().st_ino, published.stat().st_ino)
        self.assertFalse(report['launch_safe'])
        self.assertFalse(report['schema_converted'])
        self.assertFalse(report['third_party_paths_audited'])

    def test_legacy_profile_imports_without_fabricating_arrivals(self):
        self.capture()
        report = self.perform_import()
        self.assertFalse((Path(report['runtime']) / 'sortie-arrivals-v1').exists())
        self.assertTrue((Path(report['runtime']) / 'home').is_dir())
        self.assertFalse(report['ready_for_production'])

    def test_import_requires_separate_explicit_confirmation(self):
        self.capture()
        with self.assertRaisesRegex(ValueError, 'confirm'):
            self.perform_import(confirm_import=False)
        self.assertFalse(self.out.exists())

    def test_unknown_profile_fails_before_output(self):
        self.capture()
        with self.assertRaisesRegex(ValueError, 'profile'):
            self.perform_import('future-unknown-v99')
        self.assertFalse(self.out.exists())

    def test_arrival_journal_cannot_be_declared_legacy(self):
        self.add_arrival()
        self.capture()
        with self.assertRaisesRegex(ValueError, 'arrival'):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_unknown_arrival_schema_preserves_original_and_refuses_import(self):
        record, _ = self.add_arrival()
        data = json.loads(record.read_bytes())
        data['format'] = 'logbook-port-arrival-v2'
        record.write_text(json.dumps(data))
        self.capture()
        with self.assertRaisesRegex(ValueError, 'arrival'):
            self.perform_import('arrival-journal-v1')
        self.assertFalse(self.out.exists())
        self.assertEqual(self.original_inventory, snapshots.inventory(self.source, self.limits))

    def test_unknown_journal_directory_cannot_be_silently_copied_as_known(self):
        self.write('sortie-arrivals-v2/record.json', b'{}')
        self.capture()
        with self.assertRaisesRegex(ValueError, 'journal'):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_malformed_pending_arrival_is_preserved_but_not_imported(self):
        _, pending = self.add_arrival()
        pending.write_bytes(b'{"partial":')
        self.capture()
        with self.assertRaises(ValueError):
            self.perform_import('arrival-journal-v1')
        self.assertFalse(self.out.exists())
        self.assertEqual(self.original_inventory, snapshots.inventory(self.source, self.limits))

    def test_unknown_acceptance_schema_is_refused(self):
        self.write('config/logbook.bean.AppQuestDuration.json', b'{"map":{},"version":2}')
        self.capture()
        with self.assertRaisesRegex(ValueError, 'acceptance'):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_reversed_acceptance_interval_is_refused(self):
        target = self.source / 'config/logbook.bean.AppQuestDuration.json'
        data = json.loads(target.read_bytes())
        data['map']['2025-06-01 05:00:00']['905'][0]['to'] = '2025-05-09 00:00:00'
        target.write_text(json.dumps(data))
        self.capture()
        with self.assertRaisesRegex(ValueError, 'acceptance'):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_unknown_appconfig_property_is_not_silently_accepted(self):
        self.config['futureExternalCachePath'] = '/old/private'
        self.write_config()
        self.capture()
        with self.assertRaisesRegex(ValueError, 'AppConfig'):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_public_appconfig_validator_rejects_types_that_trigger_java_fallback(self):
        entries = snapshots.inventory(self.source, self.limits)
        for bad in ({'listenPort': {}}, {'listenPort': True}, {'usePlugin': []},
                    {'fontFamily': 3}, {'resultRank': 'future-rank'},
                    {'windowLocationMap': {'main': {'x': 'not-a-number'}}},
                    {'columnWidthMap': {'fleet': {'name': {}}}}):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                migration.validate_app_config({**self.config, **bad}, entries)

    def test_divider_positions_match_the_actual_scalar_double_map(self):
        entries = snapshots.inventory(self.source, self.limits)
        migration.validate_app_config({**self.config, 'dividerPositionMap': {'main': 0.375}}, entries)
        with self.assertRaisesRegex(ValueError, 'AppConfig'):
            migration.validate_app_config({**self.config, 'dividerPositionMap': {'main': [0.375]}}, entries)

    def test_implicit_path_defaults_must_be_resolved_first(self):
        del self.config['defaultNotifySound']
        self.write_config()
        self.capture()
        with self.assertRaisesRegex(ValueError, 'explicit'):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_external_path_in_backup_is_refused_even_when_primary_is_portable(self):
        self.config['reportPath'] = '/old-machine/private-logs'
        self.write_config('.backup')
        self.capture()
        with self.assertRaisesRegex(ValueError, 'relative'):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_backup_only_portable_config_is_preserved_without_source_rewrite(self):
        primary = self.source / 'config/logbook.bean.AppConfig.json'
        primary.rename(primary.with_suffix(primary.suffix + '.backup'))
        self.capture()
        report = self.perform_import()
        self.assertFalse((Path(report['runtime']) / 'config/logbook.bean.AppConfig.json').exists())
        self.assertEqual(self.original_inventory, snapshots.inventory(self.source, self.limits))

    def test_duplicate_json_keys_are_not_a_known_schema(self):
        self.write('config/logbook.bean.AppQuestDuration.json', b'{"map":{},"map":{}}')
        self.capture()
        with self.assertRaises(ValueError):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_existing_store_is_never_adopted_or_overwritten(self):
        self.capture()
        self.out.mkdir()
        sentinel = self.out / 'keep'
        sentinel.write_bytes(b'keep')
        with self.assertRaises(FileExistsError):
            self.perform_import()
        self.assertEqual(b'keep', sentinel.read_bytes())
        self.assertEqual(['keep'], [p.name for p in self.out.iterdir()])

    def test_destination_must_be_disjoint_from_snapshot_original_and_bundles(self):
        self.capture()
        for target in (self.source / 'child', self.checkpoint / 'child', self.a / 'child', self.base):
            with self.subTest(target=target):
                self.out = target
                with self.assertRaises(ValueError):
                    self.perform_import()
                if target != self.base:
                    self.assertFalse(target.exists())

    def test_symbolic_output_parent_is_refused(self):
        self.capture()
        link = self.base / 'alias'
        link.symlink_to(self.base, target_is_directory=True)
        self.out = link / 'through-link'
        with self.assertRaises(ValueError):
            self.perform_import()
        self.assertFalse((self.base / 'through-link').exists())

    def test_carried_earlier_original_roots_prevent_nested_and_ancestor_import_outputs(self):
        earlier = self.base / 'earlier-originals' / 'old-source'
        earlier.mkdir(parents=True)
        (earlier / 'keep.bin').write_bytes(b'earlier-original')
        before = snapshots.inventory(earlier.parent, self.limits)
        self.capture()
        for target in (earlier, earlier / 'new-import', earlier.parent):
            with self.subTest(target=target):
                self.out = target
                with self.assertRaisesRegex(ValueError, 'disjoint'):
                    self.perform_import(protected_roots=[earlier])
                self.assertEqual(before, snapshots.inventory(earlier.parent, self.limits))

    def test_carried_provenance_is_recorded_without_copying_earlier_sources(self):
        earlier = self.base / 'earlier-original'
        earlier.mkdir()
        (earlier / 'not-selected.bin').write_bytes(b'do-not-copy')
        self.capture()
        report = self.perform_import(protected_roots=[earlier])
        self.assertIn(str(earlier), report['protected_roots'])
        self.assertFalse((self.out / 'runtime/not-selected.bin').exists())

    def test_snapshot_tampering_fails_before_output(self):
        self.capture()
        (self.checkpoint / 'data/images/fixture.png').write_bytes(b'tampered')
        with self.assertRaises(ValueError):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_wrong_manifest_pin_fails_before_output(self):
        self.capture()
        self.sha = '0' * 64
        with self.assertRaises(ValueError):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_space_budget_includes_runtime_and_all_bundles(self):
        self.capture()
        with patch.object(migration.shutil, 'disk_usage', return_value=migration.shutil._ntuple_diskusage(100, 100, 0)):
            with self.assertRaisesRegex(ValueError, 'space'):
                self.perform_import()
        self.assertFalse(self.out.exists())

    def test_all_copy_bytes_must_fit_explicit_budget(self):
        self.capture()
        total = max(sum(row.get('bytes', 0) for row in snapshots.inventory(root, self.limits).values())
                    for root in (self.source, self.a, self.b))
        with self.assertRaisesRegex(ValueError, 'budget'):
            self.perform_import(limits=snapshots.Limits(bytes=total, reserve=0))
        self.assertFalse(self.out.exists())

    def test_interrupted_runtime_copy_retains_evidence_without_completion_or_selection(self):
        self.capture()
        copier = snapshots.copy_entries
        def stop_after_copy(*args, **kwargs):
            copier(*args, **kwargs)
            raise OSError('synthetic full disk')
        with patch.object(snapshots, 'copy_entries', side_effect=stop_after_copy):
            with self.assertRaises(OSError):
                self.perform_import()
        self.assertTrue((self.out / 'import-intent.json').is_file())
        self.assertTrue((self.out / 'runtime/images/fixture.png').is_file())
        self.assertFalse((self.out / 'import.json').exists())
        self.assertFalse((self.out / 'current.json').exists())
        self.assertEqual(self.original_inventory, snapshots.inventory(self.source, self.limits))

    def test_snapshot_change_during_copy_prevents_completion(self):
        self.capture()
        copier = snapshots.copy_entries
        def mutate_copy_source(*args, **kwargs):
            copier(*args, **kwargs)
            (self.checkpoint / 'data/images/fixture.png').write_bytes(b'synthetic concurrent writer')
        with patch.object(snapshots, 'copy_entries', side_effect=mutate_copy_source):
            with self.assertRaises(ValueError):
                self.perform_import()
        self.assertFalse((self.out / 'import.json').exists())
        self.assertFalse((self.out / 'current.json').exists())
        self.assertEqual(self.original_inventory, snapshots.inventory(self.source, self.limits))

    def test_bundle_change_during_import_prevents_completion(self):
        self.capture()
        stage = manager.stage
        def mutate_bundle(*args, **kwargs):
            result = stage(*args, **kwargs)
            if args[1] == self.b:
                (self.b / manager.FILES[-1]).write_bytes(b'changed-after-stage')
            return result
        with patch.object(manager, 'stage', side_effect=mutate_bundle):
            with self.assertRaises(ValueError):
                self.perform_import()
        self.assertFalse((self.out / 'import.json').exists())
        self.assertFalse((self.out / 'current.json').exists())

    def test_existing_home_directory_contents_are_copied_without_overwrite(self):
        self.write('home/.synthetic-preference', b'preserve-home')
        self.capture()
        report = self.perform_import()
        self.assertEqual(b'preserve-home', (Path(report['runtime']) / 'home/.synthetic-preference').read_bytes())
        self.assertEqual(self.original_inventory, snapshots.inventory(Path(report['runtime']), self.limits))

    def test_home_file_conflict_is_refused_before_store_creation(self):
        self.write('home', b'cannot-be-a-managed-home-directory')
        self.capture()
        with self.assertRaisesRegex(ValueError, 'home'):
            self.perform_import()
        self.assertFalse(self.out.exists())

    def test_import_does_not_execute_plugin_script_or_application(self):
        self.capture()
        with patch.object(manager, 'launch', side_effect=AssertionError('must not launch')):
            report = self.perform_import()
        self.assertFalse(report['launch_safe'])
        self.assertFalse(report['third_party_paths_audited'])
        self.assertEqual(self.original_inventory, snapshots.inventory(self.source, self.limits))

    def test_whole_store_restore_recovers_old_selection_and_all_old_runtime_in_new_directory(self):
        self.add_arrival()
        self.capture()
        self.perform_import('arrival-journal-v1')
        before = snapshots.inventory(self.out, self.limits)
        checkpoint = self.base / 'before-launch'
        pin = snapshots.snapshot(self.out, checkpoint, stopped=True, limits=self.limits)['manifest_sha256']
        manager.activate(self.out, self.bid, self.aid)
        (self.out / 'runtime/images/fixture.png').write_bytes(b'changed-in-copy')
        (self.out / 'runtime/config/logbook.bean.AppQuestDuration.json').write_bytes(b'{"map":{}}')
        (self.out / 'runtime/new-after-start').write_bytes(b'new-copy-data')
        current = snapshots.inventory(self.out, self.limits)
        target = self.base / 'recovered'
        report = migration.restore_store(checkpoint, target, pin, profile='arrival-journal-v1',
                                         confirm_restore=True, limits=self.limits)
        restored = Path(report['restored_store'])
        self.assertEqual(before, snapshots.inventory(restored, self.limits))
        self.assertEqual(self.aid, manager.selector(restored)['current'])
        self.assertEqual(current, snapshots.inventory(self.out, self.limits))
        self.assertEqual(self.original_inventory, snapshots.inventory(self.source, self.limits))
        self.assertFalse(report['launch_safe'])
        self.assertTrue(report['whole_store_restored'])
        self.assertTrue(report['runtime_data_restored'])
        self.assertFalse(report['original_replaced'])

    def test_restore_requires_separate_explicit_confirmation(self):
        self.capture()
        self.perform_import()
        checkpoint = self.base / 'store-snapshot'
        pin = snapshots.snapshot(self.out, checkpoint, stopped=True, limits=self.limits)['manifest_sha256']
        target = self.base / 'restored'
        with self.assertRaisesRegex(ValueError, 'confirm'):
            migration.restore_store(checkpoint, target, pin, profile='legacy-config-v1', limits=self.limits)
        self.assertFalse(target.exists())

    def test_restore_rejects_unknown_runtime_schema_before_output(self):
        self.add_arrival()
        self.capture()
        self.perform_import('arrival-journal-v1')
        future = self.out / 'runtime/sortie-arrivals-v2'
        future.mkdir()
        checkpoint = self.base / 'future-store-snapshot'
        pin = snapshots.snapshot(self.out, checkpoint, stopped=True, limits=self.limits)['manifest_sha256']
        target = self.base / 'restored'
        with self.assertRaisesRegex(ValueError, 'journal'):
            migration.restore_store(checkpoint, target, pin, profile='arrival-journal-v1',
                                    confirm_restore=True, limits=self.limits)
        self.assertFalse(target.exists())

    def test_whole_restore_preserves_explicit_earlier_original_namespaces(self):
        earlier = self.base / 'earlier-original'
        earlier.mkdir()
        (earlier / 'keep.bin').write_bytes(b'earlier-original')
        before = snapshots.inventory(earlier, self.limits)
        self.capture()
        self.perform_import(protected_roots=[earlier])
        checkpoint = self.base / 'store-snapshot'
        pin = snapshots.snapshot(self.out, checkpoint, stopped=True, limits=self.limits)['manifest_sha256']
        target = earlier / 'restored-copy'
        with self.assertRaisesRegex(ValueError, 'disjoint'):
            migration.restore_store(checkpoint, target, pin, profile='legacy-config-v1',
                                    confirm_restore=True, protected_roots=[earlier], limits=self.limits)
        self.assertFalse(target.exists())
        self.assertEqual(before, snapshots.inventory(earlier, self.limits))

    def test_whole_restore_inherits_earlier_original_roots_from_verified_import_receipts(self):
        earlier = self.base / 'earlier-original'
        earlier.mkdir()
        (earlier / 'keep.bin').write_bytes(b'earlier-original')
        before = snapshots.inventory(earlier, self.limits)
        self.capture()
        self.perform_import(protected_roots=[earlier])
        checkpoint = self.base / 'store-snapshot'
        pin = snapshots.snapshot(self.out, checkpoint, stopped=True, limits=self.limits)['manifest_sha256']
        for target in (earlier / 'restored-copy', earlier):
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, 'disjoint'):
                migration.restore_store(checkpoint, target, pin, profile='legacy-config-v1',
                                        confirm_restore=True, limits=self.limits)
        self.assertEqual(before, snapshots.inventory(earlier, self.limits))
        result = migration.restore_store(checkpoint, self.base / 'safe-restore', pin,
            profile='legacy-config-v1', confirm_restore=True, limits=self.limits)
        self.assertIn(str(earlier), result['protected_roots'])
        self.assertEqual(['import.json', 'import-intent.json'], result['protected_roots_inherited_from'])

    def test_whole_restore_rejects_malformed_known_receipt_protected_roots(self):
        self.capture()
        self.perform_import()
        receipt_path = self.out / 'import.json'
        original = json.loads(receipt_path.read_bytes())
        bad_values = ({'format': 'future-import-v2'}, {'protected_roots': 'not-a-list'}, {'protected_roots': []},
                      {'protected_roots': ['relative-original']}, {'protected_roots': [None]},
                      {'protected_roots': [17]}, {'protected_roots': ['//ambiguous/original']})
        for index, bad in enumerate(bad_values):
            with self.subTest(bad=bad):
                receipt_path.write_text(json.dumps({**original, **bad}))
                checkpoint = self.base / ('malformed-receipt-' + str(index))
                pin = snapshots.snapshot(self.out, checkpoint, stopped=True, limits=self.limits)['manifest_sha256']
                target = self.base / ('restore-' + str(index))
                with self.assertRaisesRegex(ValueError, 'receipt'):
                    migration.restore_store(checkpoint, target, pin, profile='legacy-config-v1',
                                            confirm_restore=True, limits=self.limits)
                self.assertFalse(target.exists())


if __name__ == '__main__':
    unittest.main()
