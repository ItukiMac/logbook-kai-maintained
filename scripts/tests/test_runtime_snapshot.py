"""All copy/mutation fixtures are newly-created TempDirs; never a user's installation."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import runtime_snapshot as module
from runtime_snapshot import Limits, inventory, path_review, snapshot, verify, restore


class RuntimeSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source = self.base / 'source'
        self.source.mkdir()
        self.output = self.base / 'backup-new'
        self.clone = self.base / 'restore-new'
        self.limits = Limits(reserve=0)
        self.write('config/logbook.bean.AppConfig.json', b'{"reportPath":".","battleLogDir":"battlelog"}')
        self.write('customquest/905.json', b'// user comment\r\n{"value":1}\r\n')
        self.write('battlelog/old.dat', bytes(range(256)))
        self.write('plugins/old-plugin.jar', b'opaque binary sentinel; never load')
        self.write('auto.sh', b'#!/bin/sh\n# retained; never executed\n').chmod(0o751)
        self.write('.hidden', b'private fixture')
        self.write('images/日本語.png', b'not a real picture')
        self.write('backup/old-version.jar', b'old-version')
        (self.source / 'empty').mkdir()
        pending = self.write('sortie-arrivals-v1/2026-10/.fixture.pending', b'{"opaque":"copy only"}')
        os.link(pending, pending.with_name('fixture.json'))

    def write(self, relative, value):
        p = self.source / relative
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(value)
        return p

    def backup(self):
        return snapshot(self.source, self.output, stopped=True, limits=self.limits)

    def test_complete_bytes_metadata_and_new_arrival_directory_roundtrip(self):
        before = inventory(self.source, self.limits)
        receipt = self.backup()
        verified = verify(self.output, receipt['manifest_sha256'], self.limits)
        self.assertEqual(before, verified['entries'])
        result = restore(self.output, self.clone, receipt['manifest_sha256'], limits=self.limits)
        self.assertEqual(before, inventory(self.clone/'data', self.limits))
        self.assertEqual(before, inventory(self.source, self.limits))
        self.assertFalse(result['launch_safe'])
        self.assertFalse(result['schema_converted'])
        self.assertFalse(result['original_replaced'])
        self.assertEqual(0o751, stat.S_IMODE((self.clone/'data/auto.sh').stat().st_mode))

    def test_source_and_copy_hardlinks_are_not_shared(self):
        self.backup()
        a = self.output/'data/sortie-arrivals-v1/2026-10/.fixture.pending'
        b = a.with_name('fixture.json')
        self.assertNotEqual(a.stat().st_ino, b.stat().st_ino)
        a.write_bytes(b'changed clone')
        self.assertEqual(b'{"opaque":"copy only"}', b.read_bytes())
        self.assertEqual(b'{"opaque":"copy only"}', (self.source/a.relative_to(self.output/'data')).read_bytes())

    def test_explicit_stop_confirmation_required_before_output_creation(self):
        with self.assertRaisesRegex(ValueError, 'stopped'):
            snapshot(self.source, self.output, limits=self.limits)
        self.assertFalse(self.output.exists())

    def test_existing_destination_is_never_overwritten_even_when_empty(self):
        self.output.mkdir()
        with self.assertRaises(FileExistsError): self.backup()
        self.assertEqual([], list(self.output.iterdir()))

    def test_nested_output_and_source_ancestor_are_rejected(self):
        for path in (self.source, self.source/'nested', self.base):
            with self.subTest(path=path), self.assertRaises(ValueError):
                snapshot(self.source, path, stopped=True, limits=self.limits)
        self.assertFalse((self.source/'nested').exists())

    def test_parent_traversal_aliases_are_rejected_before_writes(self):
        for source, output in ((self.base/'x/../source', self.output), (self.source,self.base/'x/../source/nested')):
            with self.subTest(source=source), self.assertRaises(ValueError):
                snapshot(source, output, stopped=True, limits=self.limits)
        self.assertFalse(self.output.exists())

    def test_source_symlink_and_dangling_link_are_rejected(self):
        link = self.source/'alias'
        for target in (self.base/'missing', self.source/'auto.sh'):
            link.symlink_to(target)
            with self.subTest(target=target), self.assertRaises(ValueError): self.backup()
            link.unlink()  # This test's temporary fixture only.
        self.assertFalse(self.output.exists())

    def test_symbolic_source_parent_is_rejected(self):
        alias = self.base/'alias'
        alias.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(ValueError): snapshot(alias,self.output,stopped=True)
        self.assertFalse(self.output.exists())

    def test_symbolic_destination_parent_is_rejected(self):
        alias = self.base/'alias'
        alias.symlink_to(self.base,target_is_directory=True)
        with self.assertRaises(ValueError): snapshot(self.source,alias/'out',stopped=True)
        self.assertFalse((self.base/'out').exists())

    def test_fifo_is_rejected_without_opening_or_blocking(self):
        os.mkfifo(self.source/'fifo')
        with self.assertRaises(ValueError): self.backup()
        self.assertFalse(self.output.exists())

    def test_entry_byte_and_depth_bounds_precede_output(self):
        for limits in (Limits(entries=0,reserve=0),Limits(bytes=1,reserve=0),Limits(depth=0,reserve=0)):
            with self.subTest(limits=limits), self.assertRaises(ValueError):
                snapshot(self.source,self.output,stopped=True,limits=limits)
            self.assertFalse(self.output.exists())

    def test_bad_limits_are_rejected(self):
        for options in ({'bytes':-1},{'entries':True},{'depth':1.0}):
            with self.subTest(options=options), self.assertRaises(ValueError): Limits(**options)

    def test_space_reserve_is_checked_before_creation(self):
        with patch.object(module.shutil,'disk_usage',return_value=module.shutil._ntuple_diskusage(100,100,0)):
            with self.assertRaisesRegex(ValueError,'space'): self.backup()
        self.assertFalse(self.output.exists())

    def test_missing_destination_parent_is_not_created(self):
        with self.assertRaises(ValueError):
            snapshot(self.source,self.base/'absent/out',stopped=True,limits=self.limits)
        self.assertFalse((self.base/'absent').exists())

    def test_file_change_during_copy_leaves_no_completion_marker(self):
        copier=module.copy_entries
        def mutate(*args):
            copier(*args)
            (self.source/'auto.sh').write_bytes(b'fixture changed by simulated writer')
        with patch.object(module,'copy_entries',side_effect=mutate),self.assertRaises(ValueError): self.backup()
        self.assertTrue((self.output/'data/auto.sh').exists())
        self.assertFalse((self.output/'snapshot.json').exists())

    def test_new_file_during_copy_is_not_silently_omitted(self):
        copier=module.copy_entries
        def mutate(*args):
            copier(*args)
            self.write('new-record.json',b'{}')
        with patch.object(module,'copy_entries',side_effect=mutate),self.assertRaises(ValueError): self.backup()
        self.assertFalse((self.output/'snapshot.json').exists())

    def test_interrupted_copy_retains_partial_copy_and_original(self):
        before=inventory(self.source,self.limits)
        def interrupted(source,dest,entries):
            (dest/'partial').write_bytes(b'kept as evidence')
            raise OSError('simulated failure')
        with patch.object(module,'copy_entries',side_effect=interrupted),self.assertRaises(OSError):self.backup()
        self.assertEqual(before,inventory(self.source,self.limits))
        self.assertTrue((self.output/'data/partial').exists())
        self.assertFalse((self.output/'snapshot.json').exists())

    def test_tampered_data_blocks_restore_before_output(self):
        receipt=self.backup()
        (self.output/'data/auto.sh').write_bytes(b'tampered')
        with self.assertRaises(ValueError):restore(self.output,self.clone,receipt['manifest_sha256'],limits=self.limits)
        self.assertFalse(self.clone.exists())

    def test_tampered_manifest_is_rejected_by_pinned_hash(self):
        receipt=self.backup()
        (self.output/'snapshot.json').write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'checksum'):verify(self.output,receipt['manifest_sha256'],self.limits)

    def test_extra_data_is_not_ignored(self):
        receipt=self.backup()
        (self.output/'data/extra').write_bytes(b'extra')
        with self.assertRaises(ValueError):verify(self.output,receipt['manifest_sha256'],self.limits)

    def test_extra_snapshot_top_level_entry_is_rejected(self):
        receipt=self.backup()
        (self.output/'extra').write_bytes(b'extra')
        with self.assertRaises(ValueError):verify(self.output,receipt['manifest_sha256'],self.limits)

    def test_manifest_paths_cannot_direct_restore_outside_output(self):
        self.backup()
        p=self.output/'snapshot.json';report=json.loads(p.read_text())
        report['entries']['../escape']={'kind':'file','bytes':0,'sha256':hashlib.sha256(b'').hexdigest(),'mode':0o600,'mtime_ns':0}
        raw=module.encoded(report);p.write_bytes(raw)
        with self.assertRaises(ValueError):restore(self.output,self.clone,module.digest(raw),limits=self.limits)
        self.assertFalse(self.clone.exists());self.assertFalse((self.base/'escape').exists())

    def test_restore_never_writes_inside_original_or_backup(self):
        receipt=self.backup()
        for output in (self.source/'nested',self.output/'nested',self.source,self.output):
            with self.subTest(output=output),self.assertRaises(ValueError):
                restore(self.output,output,receipt['manifest_sha256'],limits=self.limits)
        self.assertFalse((self.source/'nested').exists());self.assertFalse((self.output/'nested').exists())

    def test_restore_refuses_existing_empty_directory(self):
        receipt=self.backup();self.clone.mkdir()
        with self.assertRaises(FileExistsError):restore(self.output,self.clone,receipt['manifest_sha256'],limits=self.limits)
        self.assertEqual([],list(self.clone.iterdir()))

    def test_snapshot_change_during_restore_keeps_partial_clone_uncommitted(self):
        receipt=self.backup();copier=module.copy_entries
        def mutate(*args):
            copier(*args)
            (self.output/'data/.hidden').write_bytes(b'changed in fixture')
        with patch.object(module,'copy_entries',side_effect=mutate),self.assertRaises(ValueError):
            restore(self.output,self.clone,receipt['manifest_sha256'],limits=self.limits)
        self.assertTrue((self.clone/'data').exists());self.assertFalse((self.clone/'restore.json').exists())

    def test_missing_checksum_and_incomplete_snapshot_fail_closed(self):
        self.output.mkdir();(self.output/'data').mkdir()
        for checksum in (None,'abc','0'*64):
            with self.subTest(checksum=checksum),self.assertRaises(ValueError):verify(self.output,checksum,self.limits)

    def test_managed_store_running_is_rejected_and_lock_bytes_unchanged(self):
        self.write('store.json',module.encoded({'format':'logbook-managed-split-v1','state_rollback':False}))
        lock=self.write('store.lock',b'')
        with lock.open('rb') as stream:
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with self.assertRaisesRegex(ValueError,'running'):self.backup()
        self.assertFalse(self.output.exists());self.assertEqual(b'',lock.read_bytes())

    def test_runtime_child_honors_parent_managed_store_lock(self):
        self.write('store.json',module.encoded({'format':'logbook-managed-split-v1','state_rollback':False}))
        lock=self.write('store.lock',b'');runtime=self.source/'runtime';runtime.mkdir()
        with lock.open('rb') as stream:
            fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
            with self.assertRaisesRegex(ValueError,'running'):
                snapshot(runtime,self.output,stopped=True,limits=self.limits)
        self.assertFalse(self.output.exists())

    def test_legacy_paths_are_reported_not_followed_or_rewritten(self):
        external=self.base/'external';external.mkdir();(external/'private').write_bytes(b'not copied')
        config={'reportPath':str(external),'pluginsDir':str(self.source/'plugins'),
                'battleLogDir':'battlelog','defaultNotifySound':'C:\\Windows\\ding.wav'}
        self.write('config/logbook.bean.AppConfig.json',module.encoded(config))
        receipt=self.backup()
        states={e['field']:e['status'] for e in receipt['path_review']['references']}
        self.assertEqual('outside_root_not_copied',states['reportPath'])
        self.assertEqual('absolute_reference_to_original_needs_review',states['pluginsDir'])
        self.assertEqual('relative_within_root',states['battleLogDir'])
        self.assertEqual('other_platform_or_ambiguous',states['defaultNotifySound'])
        self.assertEqual(config,json.loads((self.output/'data/config/logbook.bean.AppConfig.json').read_text()))
        self.assertFalse((self.output/'data/external').exists())

    def test_bad_config_bytes_are_preserved_and_coverage_is_not_certified(self):
        self.write('config/logbook.bean.AppConfig.json',b'{bad')
        receipt=self.backup()
        self.assertTrue(receipt['path_review']['config_unreadable'])
        self.assertFalse(receipt['launch_safe'])
        self.assertEqual(b'{bad',(self.output/'data/config/logbook.bean.AppConfig.json').read_bytes())

    def test_intent_tampering_or_symbolic_intent_blocks_restore(self):
        receipt=self.backup();intent=self.output/'intent.json'
        original=intent.read_bytes();intent.write_bytes(b'{}')
        with self.assertRaisesRegex(ValueError,'intent'):verify(self.output,receipt['manifest_sha256'],self.limits)
        intent.write_bytes(original)
        retained=self.output/'saved-intent';intent.rename(retained)
        intent.symlink_to(retained)
        with self.assertRaises(ValueError):verify(self.output,receipt['manifest_sha256'],self.limits)

    def test_missing_data_and_permission_changes_are_detected(self):
        receipt=self.backup();target=self.output/'data/auto.sh'
        original=target.stat().st_mode;target.chmod(0o600)
        with self.assertRaises(ValueError):verify(self.output,receipt['manifest_sha256'],self.limits)
        target.chmod(stat.S_IMODE(original));target.unlink()  # only the newly copied fixture
        with self.assertRaises(ValueError):verify(self.output,receipt['manifest_sha256'],self.limits)

    def test_stopped_managed_store_can_be_copied_without_changing_selector_or_lock(self):
        self.write('store.json',module.encoded({'format':'logbook-managed-split-v1','state_rollback':False}))
        lock=self.write('store.lock',b'');self.write('current.json',b'{"synthetic":"retained as bytes"}')
        before=inventory(self.source,self.limits);self.backup()
        self.assertEqual(before,inventory(self.source,self.limits))
        with lock.open('rb') as stream:fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)

    def test_cli_inventory_changes_no_source_bytes_and_snapshot_without_ack_fails(self):
        script=Path(module.__file__);before=inventory(self.source,self.limits)
        p=subprocess.run([sys.executable,str(script),'inventory','--source',str(self.source)],capture_output=True,text=True,timeout=10)
        self.assertEqual(0,p.returncode,p.stderr);self.assertFalse(json.loads(p.stdout)['path_review']['launch_safe'])
        self.assertEqual(before,inventory(self.source,self.limits))
        p=subprocess.run([sys.executable,str(script),'snapshot','--source',str(self.source),'--out',str(self.output)],capture_output=True,text=True,timeout=10)
        self.assertEqual(1,p.returncode);self.assertFalse(self.output.exists())


if __name__=='__main__':unittest.main()
