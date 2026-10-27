"""Copy-only migration, approval and transient-write regressions in fresh fixtures."""
import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import runtime_snapshot as snap


class RuntimeMigrationTest(unittest.TestCase):
    def setUp(self):
        self.m = importlib.import_module('runtime_migration')
        self.temp = tempfile.TemporaryDirectory(prefix='runtime-migration-fixture-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source = self.base / 'source'
        self.source.mkdir()
        self.external = self.base / 'external'
        self.external.mkdir()
        (self.external / 'old.csv').write_bytes(b'private synthetic history\n')
        (self.external / 'nested').mkdir()
        (self.external / 'nested/picture.bin').write_bytes(b'\x00\x01\xff')
        for name in ('config', 'performancelog', 'plugins', 'resources', 'battlelog',
                     'sounds/mission', 'sounds/ndock', 'sounds/alert'):
            (self.source / name).mkdir(parents=True, exist_ok=True)
        (self.source / 'sounds/notify.wav').write_bytes(b'synthetic sound')
        self.config = dict(reportPath='.', performanceDir='performancelog',
                           pluginsDir='plugins', resourcesDir='resources', battleLogDir='battlelog',
                           captureDir=None, storeApiStart2Dir='', missionSoundDir='sounds/mission',
                           ndockSoundDir='sounds/ndock', alertSoundDir='sounds/alert',
                           defaultNotifySound='sounds/notify.wav', ffmpegPath=None,
                           usePlugin=False, useSound=False, checkUpdate=False)
        self.save_config()
        (self.source / 'customquest').mkdir()
        (self.source / 'customquest/fixture.json').write_bytes(b'// retained comment\r\n{}\r\n')
        self.snapshot = self.base / 'snapshot'
        self.output = self.base / 'migrated'
        self.limits = snap.Limits(reserve=0)

    def save_config(self, *, backup=False, raw=None):
        name = snap.APP_CONFIG + ('.backup' if backup else '')
        (self.source / name).write_bytes(snap.encoded(self.config) if raw is None else raw)

    def freeze(self):
        receipt = snap.snapshot(self.source, self.snapshot, stopped=True, limits=self.limits)
        self.manifest_sha = receipt['manifest_sha256']
        self.before = snap.inventory(self.source, self.limits)
        return receipt

    def plan(self, **kwargs):
        return self.m.plan(self.snapshot, self.manifest_sha, self.output,
                           limits=self.limits, **kwargs)

    def apply(self, plan):
        return self.m.apply(plan, expected_plan_sha256=snap.digest(snap.encoded(plan)),
                            confirm_copy=True, limits=self.limits)

    def approve(self, command, **kwargs):
        return self.m.approve(self.output,
                              snap.digest((self.output / 'migration.json').read_bytes()),
                              command=command, confirm=True, limits=self.limits, **kwargs)

    def test_copy_rebinds_selected_paths_and_preserves_every_other_original_byte(self):
        self.config.update(reportPath=str(self.external), resourcesDir=str(self.source / 'resources'))
        self.save_config()
        self.freeze()
        external_before = snap.inventory(self.external, self.limits)
        p = self.plan(selected_paths={str(self.external): self.external})
        self.assertEqual([], p['blockers'])
        receipt = self.apply(p)
        runtime = Path(receipt['runtime'])
        copied = json.loads((runtime / snap.APP_CONFIG).read_bytes())
        self.assertTrue(set(snap.PATH_FIELDS).issubset(copied))
        self.assertFalse(Path(copied['reportPath']).is_absolute())
        self.assertEqual(b'private synthetic history\n', (runtime / copied['reportPath'] / 'old.csv').read_bytes())
        self.assertEqual('resources', copied['resourcesDir'])
        self.assertEqual(self.before, snap.inventory(self.source, self.limits))
        self.assertEqual(external_before, snap.inventory(self.external, self.limits))
        self.assertEqual((self.source / snap.APP_CONFIG).read_bytes(),
                         (self.output / 'original-config' / Path(snap.APP_CONFIG).name).read_bytes())
        self.assertEqual((self.source / 'customquest/fixture.json').read_bytes(),
                         (runtime / 'customquest/fixture.json').read_bytes())
        self.assertFalse(receipt['launch_safe'])
        self.assertFalse(receipt['ready_for_production'])

    def test_unselected_external_path_is_reported_without_reading_it(self):
        self.config['reportPath'] = '/unselected-private-mount/never-read'
        self.save_config()
        self.freeze()
        with patch.object(self.m, '_inventory_path', side_effect=AssertionError('external traversal')):
            p = self.plan()
        self.assertTrue(p['blockers'])
        self.assertEqual([], p['external_inputs'])
        self.assertFalse(self.output.exists())

    def test_backup_only_config_is_rebound_and_original_remains_backup_only(self):
        self.config['reportPath'] = str(self.external)
        self.save_config(backup=True)
        (self.source / snap.APP_CONFIG).unlink()  # own fixture only
        self.freeze()
        receipt = self.apply(self.plan(selected_paths={str(self.external): self.external}))
        runtime = Path(receipt['runtime'])
        for path in (snap.APP_CONFIG, snap.APP_CONFIG + '.backup'):
            self.assertFalse(Path(json.loads((runtime / path).read_bytes())['reportPath']).is_absolute())
        self.assertFalse((self.source / snap.APP_CONFIG).exists())
        self.assertEqual(self.before, snap.inventory(self.source, self.limits))

    def test_malformed_primary_is_archived_before_valid_fallback_emitted(self):
        self.save_config(backup=True)
        self.save_config(raw=b'{broken primary')
        self.freeze()
        receipt = self.apply(self.plan())
        self.assertEqual(b'{broken primary',
                         (self.output / 'original-config' / Path(snap.APP_CONFIG).name).read_bytes())
        self.assertEqual('.', json.loads((Path(receipt['runtime']) / snap.APP_CONFIG).read_bytes())['reportPath'])
        self.assertEqual(self.before, snap.inventory(self.source, self.limits))

    def test_unused_backup_external_path_also_blocks_approval(self):
        self.save_config(backup=True, raw=snap.encoded(dict(self.config, reportPath=str(self.external))))
        self.freeze()
        p = self.plan()
        self.assertTrue(any('unselected' in x for x in p['blockers']))
        self.apply(p)
        with self.assertRaisesRegex(ValueError, 'unresolved|block'):
            self.approve([sys.executable, '-c', 'pass'])

    def test_foreign_windows_reference_needs_explicit_local_mapping(self):
        raw = r'D:\legacy\history'
        self.config['reportPath'] = raw
        self.save_config()
        self.freeze()
        self.assertTrue(self.plan()['blockers'])
        p = self.plan(selected_paths={raw: self.external})
        self.assertFalse(p['blockers'])
        self.apply(p)

    def test_relative_external_parent_path_is_mapped_only_when_selected(self):
        self.config['reportPath'] = '../external'
        self.save_config()
        self.freeze()
        self.assertTrue(self.plan()['blockers'])
        p = self.plan(selected_paths={'../external': self.external})
        self.assertFalse(p['blockers'])
        self.apply(p)

    def test_same_and_nested_external_directory_selections_are_copied_once(self):
        self.config.update(reportPath=str(self.external), battleLogDir=str(self.external),
                           resourcesDir=str(self.external / 'nested'))
        self.save_config()
        self.freeze()
        p = self.plan(selected_paths={str(self.external): self.external,
                                      str(self.external / 'nested'): self.external / 'nested'})
        self.assertEqual(1, len(p['external_inputs']))
        runtime = Path(self.apply(p)['runtime'])
        config = json.loads((runtime / snap.APP_CONFIG).read_bytes())
        self.assertEqual(config['reportPath'], config['battleLogDir'])
        self.assertEqual(Path(config['reportPath']) / 'nested', Path(config['resourcesDir']))

    def test_selected_symlink_is_rejected_before_any_output(self):
        self.config['reportPath'] = str(self.external)
        self.save_config()
        self.freeze()
        alias = self.base / 'alias'
        alias.symlink_to(self.external, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'Symbolic|symbolic|Symlink'):
            self.plan(selected_paths={str(self.external): alias})
        self.assertFalse(self.output.exists())

    def test_output_overlap_with_original_snapshot_or_selected_path_is_rejected(self):
        self.config['reportPath'] = str(self.external)
        self.save_config()
        self.freeze()
        for output in (self.source / 'inside', self.snapshot / 'inside', self.external / 'inside'):
            with self.subTest(output=output), self.assertRaises(ValueError):
                self.m.plan(self.snapshot, self.manifest_sha, output,
                            selected_paths={str(self.external): self.external}, limits=self.limits)
            self.assertFalse(output.exists())

    def test_unreferenced_selection_is_not_copied(self):
        self.freeze()
        with self.assertRaisesRegex(ValueError, 'unreferenced|Unreferenced'):
            self.plan(selected_paths={'not-a-config-reference': self.external})

    def test_aggregate_capacity_checked_before_creation(self):
        self.freeze()
        p = self.plan()
        with patch.object(self.m.shutil, 'disk_usage', return_value=self.m.shutil._ntuple_diskusage(1, 1, 0)):
            with self.assertRaisesRegex(ValueError, 'space'):
                self.apply(p)
        self.assertFalse(self.output.exists())

    def test_existing_destination_is_not_touched(self):
        self.freeze()
        p = self.plan()
        self.output.mkdir()
        (self.output / 'sentinel').write_bytes(b'keep')
        with self.assertRaises(FileExistsError):
            self.apply(p)
        self.assertEqual(b'keep', (self.output / 'sentinel').read_bytes())

    def test_changed_external_source_invalidates_plan_before_output(self):
        self.config['reportPath'] = str(self.external)
        self.save_config()
        self.freeze()
        p = self.plan(selected_paths={str(self.external): self.external})
        (self.external / 'old.csv').write_bytes(b'changed by synthetic other writer')
        with self.assertRaisesRegex(ValueError, 'plan|Plan|changed'):
            self.apply(p)
        self.assertFalse(self.output.exists())

    def test_source_change_during_copy_retains_incomplete_evidence(self):
        self.config['reportPath'] = str(self.external)
        self.save_config()
        self.freeze()
        p = self.plan(selected_paths={str(self.external): self.external})
        copier = self.m._copy_input
        def mutate(source, destination, entries):
            copier(source, destination, entries)
            (self.external / 'old.csv').write_bytes(b'concurrent synthetic writer')
        with patch.object(self.m, '_copy_input', side_effect=mutate), self.assertRaises(ValueError):
            self.apply(p)
        self.assertTrue((self.output / 'intent.json').exists())
        self.assertFalse((self.output / 'migration.json').exists())
        self.assertEqual(self.before, snap.inventory(self.source, self.limits))

    def test_edited_plan_cannot_choose_an_arbitrary_write_path(self):
        self.freeze()
        p = self.plan()
        p['configuration_outputs'][snap.APP_CONFIG]['reportPath'] = '../../escape'
        with self.assertRaisesRegex(ValueError, 'plan|Plan'):
            self.apply(p)
        self.assertFalse(self.output.exists())

    def test_copy_and_launch_each_require_their_own_explicit_confirmation(self):
        self.freeze()
        p = self.plan()
        with self.assertRaisesRegex(ValueError, 'confirm'):
            self.m.apply(p, expected_plan_sha256=snap.digest(snap.encoded(p)), limits=self.limits)
        self.assertFalse(self.output.exists())
        self.apply(p)
        command = [sys.executable, '-c', 'from pathlib import Path;Path("ran").write_text("yes")']
        auth = self.approve(command)
        with self.assertRaisesRegex(ValueError, 'confirm'):
            self.m.launch(self.output, auth, limits=self.limits)
        self.assertFalse((self.output / 'data/ran').exists())

    def test_unreviewed_plugin_and_startup_script_block_launch_even_when_plugins_disabled(self):
        plugin = self.source / 'plugins/opaque.jar'
        script = self.source / 'auto.sh'
        plugin.write_bytes(b'opaque plugin not loaded')
        script.write_bytes(b'#!/bin/sh\n# not executed\n')
        self.freeze()
        p = self.plan()
        self.assertGreaterEqual(len(p['unreviewed_artifacts']), 2)
        self.apply(p)
        with self.assertRaisesRegex(ValueError, 'unresolved|block'):
            self.approve([sys.executable, '-c', 'pass'])

    def test_hash_bound_artifact_review_does_not_transfer_to_changed_plugin(self):
        plugin = self.source / 'plugins/opaque.jar'
        plugin.write_bytes(b'opaque plugin not loaded')
        self.freeze()
        with self.assertRaisesRegex(ValueError, 'review|hash'):
            self.plan(reviewed_artifacts={'plugins/opaque.jar': '0' * 64})

    def test_approved_validation_launch_writes_clone_and_preserves_originals(self):
        self.freeze()
        self.apply(self.plan())
        auth = self.approve([sys.executable, '-c',
                             'import os;from pathlib import Path;'
                             'assert Path(os.environ["HOME"]).is_dir();'
                             'Path("clone-only").write_text("yes")'])
        result = self.m.launch(self.output, auth, confirm_launch=True, limits=self.limits)
        self.assertEqual(0, result['returncode'])
        self.assertTrue(result['originals_unchanged'])
        self.assertEqual([], result['external_write_events'])
        self.assertFalse(result['ready_for_production'])
        self.assertEqual(self.before, snap.inventory(self.source, self.limits))

    def test_clone_edit_after_approval_blocks_process_creation(self):
        self.freeze()
        self.apply(self.plan())
        auth = self.approve([sys.executable, '-c', 'raise SystemExit("must not run")'])
        (self.output / 'data/unexpected').write_bytes(b'new')
        with self.assertRaisesRegex(ValueError, 'changed|inventory|approval'):
            self.m.launch(self.output, auth, confirm_launch=True, limits=self.limits)

    def test_transient_write_then_restore_to_original_is_detected(self):
        self.freeze()
        self.apply(self.plan())
        target = str(self.source / 'customquest/fixture.json')
        code = ('from pathlib import Path; p=Path(' + repr(target) + ');'
                'old=p.read_bytes();p.write_bytes(b"transient");p.write_bytes(old)')
        auth = self.approve([sys.executable, '-c', code])
        with self.assertRaisesRegex(ValueError, 'original|write|protected'):
            self.m.launch(self.output, auth, confirm_launch=True, limits=self.limits)
        reports = list((self.output / 'validation-runs').glob('*/result.json'))
        self.assertEqual(1, len(reports))
        self.assertTrue(json.loads(reports[0].read_bytes())['external_write_events'])

    def test_command_input_change_after_approval_blocks_launch(self):
        self.freeze()
        self.apply(self.plan())
        fixture = self.base / 'probe-input.txt'
        fixture.write_bytes(b'original executable input')
        auth = self.approve([sys.executable, '-c', 'pass'],
                            command_inputs={str(fixture): snap.digest(fixture.read_bytes())})
        fixture.write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'input|changed|hash'):
            self.m.launch(self.output, auth, confirm_launch=True, limits=self.limits)

    def test_migrated_portable_runtime_can_be_copied_again_without_namespace_collision(self):
        self.config['reportPath'] = str(self.external)
        self.save_config()
        self.freeze()
        runtime = Path(self.apply(self.plan(selected_paths={str(self.external): self.external}))['runtime'])
        second_snapshot = self.base / 'second-snapshot'
        second = snap.snapshot(runtime, second_snapshot, stopped=True, limits=self.limits)
        second_output = self.base / 'second-copy'
        p = self.m.plan(second_snapshot, second['manifest_sha256'], second_output, limits=self.limits)
        self.assertEqual([], p['blockers'])
        result = self.m.apply(p, expected_plan_sha256=snap.digest(snap.encoded(p)),
                              confirm_copy=True, limits=self.limits)
        second_runtime = Path(result['runtime'])
        config = json.loads((second_runtime / snap.APP_CONFIG).read_bytes())
        self.assertEqual(b'private synthetic history\n',
                         (second_runtime / config['reportPath'] / 'old.csv').read_bytes())

    def test_python_and_extensionless_startup_scripts_remain_unreviewed(self):
        (self.source / 'start.py').write_bytes(b'print("retained, never run")\n')
        (self.source / 'startup').write_bytes(b'#!/bin/sh\n# retained\n')
        self.freeze()
        p = self.plan()
        self.assertIn('start.py', p['unreviewed_artifacts'])
        self.assertIn('startup', p['unreviewed_artifacts'])

    def test_new_empty_config_path_cannot_collide_with_external_copy_namespace(self):
        self.config.update(reportPath='.migration-external/logs', resourcesDir=str(self.external))
        self.save_config()
        self.freeze()
        p = self.plan(selected_paths={str(self.external): self.external})
        result = self.apply(p)
        runtime = Path(result['runtime'])
        config = json.loads((runtime / snap.APP_CONFIG).read_bytes())
        self.assertTrue((runtime / config['reportPath']).is_dir())
        self.assertEqual(b'private synthetic history\n',
                         (runtime / config['resourcesDir'] / 'old.csv').read_bytes())

    def test_older_snapshot_restoration_baselines_retained_current_original(self):
        self.freeze()
        (self.source / 'customquest/fixture.json').write_bytes(b'current state after old snapshot')
        current = snap.inventory(self.source, self.limits)
        result = self.apply(self.plan())
        self.assertFalse(result['original_matches_snapshot'])
        auth = self.approve([sys.executable, '-c', 'pass'])
        self.m.launch(self.output, auth, confirm_launch=True, limits=self.limits)
        self.assertEqual(current, snap.inventory(self.source, self.limits))
        self.assertEqual(b'// retained comment\r\n{}\r\n',
                         (self.output / 'data/customquest/fixture.json').read_bytes())

    def test_replaced_validation_log_directory_is_rejected_before_any_original_write(self):
        (self.source / 'empty-protected').mkdir()
        self.freeze()
        self.apply(self.plan())
        auth = self.approve([sys.executable, '-c', 'pass'])
        logs = self.output / 'validation-runs'
        logs.rename(self.output / 'retained-validation-runs')
        logs.symlink_to(self.source / 'empty-protected', target_is_directory=True)
        with patch.object(self.m.subprocess, 'Popen', side_effect=AssertionError('must not launch')):
            with self.assertRaisesRegex(ValueError, 'Symbolic|symbolic|log'):
                self.m.launch(self.output, auth, confirm_launch=True, limits=self.limits)
        self.assertEqual(self.before, snap.inventory(self.source, self.limits))

    def test_explicit_internal_selection_missing_from_snapshot_is_never_copied_empty(self):
        self.config['reportPath'] = r'D:\history'
        self.save_config()
        self.freeze()
        later = self.source / 'later'
        later.mkdir()
        (later / 'new.csv').write_bytes(b'must not be omitted')
        with self.assertRaisesRegex(ValueError, 'selected|snapshot'):
            self.plan(selected_paths={r'D:\history': later})
        self.assertFalse(self.output.exists())

    def test_invalid_nonpath_config_type_blocks_approval_instead_of_java_defaults(self):
        self.config['listenPort'] = {'not': 'an integer'}
        self.save_config()
        self.freeze()
        self.apply(self.plan())
        with self.assertRaisesRegex(ValueError, 'AppConfig|integer'):
            self.approve([sys.executable, '-c', 'pass'])
        self.assertFalse((self.output / 'approval.json').exists())

    def test_legacy_snapshot_path_summary_cannot_hide_backup_paths_from_new_migration(self):
        self.config['reportPath'] = str(self.external)
        self.save_config(backup=True)
        (self.source / snap.APP_CONFIG).unlink()  # own new fixture only
        self.freeze()
        manifest_file = self.snapshot / 'snapshot.json'
        manifest = json.loads(manifest_file.read_bytes())
        manifest['path_review'] = dict(config_present=False, references=[],
                                       third_party_paths_audited=False, launch_safe=False)
        raw = snap.encoded(manifest)
        manifest_file.write_bytes(raw)
        self.manifest_sha = snap.digest(raw)
        p = self.plan()
        self.assertTrue(any('unselected_path:' in blocker for blocker in p['blockers']))
        mapped = self.plan(selected_paths={str(self.external): self.external})
        self.assertEqual([], mapped['blockers'])
        self.apply(mapped)


if __name__ == '__main__':
    unittest.main()
