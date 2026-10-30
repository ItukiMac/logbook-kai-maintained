import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import feature_bundle_manager as m
from run_feature_distribution import (FORMAT, HOST_METADATA, FEATURE_METADATA,
                                     SERVICE, FEATURES, PROVIDERS, required_entries)


class BundleManagerTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.base = Path(temp.name); self.store = self.base / 'store'
        m.initialize(self.store)
        self.a, self.aid = self.make_bundle('a')
        self.b, self.bid = self.make_bundle('b')

    def make_bundle(self, label):
        root = self.base / label;root.mkdir();(root / 'features').mkdir()
        with ZipFile(root / m.JARS[0], 'x') as jar:
            jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n\r\n')
            jar.writestr(HOST_METADATA, json.dumps({'api': 1, 'version': '1.0.0'}))
            jar.writestr('fixture.txt', label)
            jar.writestr('logbook/internal/updatecheck/ManagedBundleMode.class', b'unit-fixture-not-executable')
        host_hash = hashlib.sha256((root / m.JARS[0]).read_bytes()).hexdigest()
        modules = []
        for module_id, features in FEATURES.items():
            name = 'features/logbook-feature-' + module_id + '.jar'
            metadata = {'id': module_id, 'version': '1.0.0', 'host_api': 1,
                        'host_sha256': host_hash, 'dependencies': [],
                        'provider': PROVIDERS[module_id], 'features': list(features)}
            with ZipFile(root / name, 'x') as jar:
                jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\nImplementation-Version: 1.0.0\r\n\r\n')
                jar.writestr('fixture.txt', label + name)
                jar.writestr(FEATURE_METADATA, json.dumps(metadata))
                jar.writestr(SERVICE, PROVIDERS[module_id] + '\n')
                if module_id in ('klb', 'kqs'):
                    provider = {'klb': 'local.kancolle.bridge.KancolleBridgeStartUp', 'kqs': 'local.kancolle.kqs.KqsLogbookPlugin'}[module_id]
                    for service in ('logbook.plugin.lifecycle.StartUp', 'logbook.listener.ContentListenerSpi'):
                        if module_id == 'kqs' or service == 'logbook.plugin.lifecycle.StartUp':
                            jar.writestr('META-INF/services/' + service, provider + '\n')
                for entry_name in sorted(required_entries(module_id)):
                    jar.writestr(entry_name, b'unit-fixture-not-executable')
            modules.append({**metadata, 'path': name,
                            'sha256': hashlib.sha256((root / name).read_bytes()).hexdigest()})
        (root / m.FILES[-1]).write_text('# never executed by manager\n')
        report = {'format': FORMAT, 'source_revision':label * 40,
                  'host': {'api': 1, 'version': '1.0.0', 'path': m.JARS[0], 'sha256': host_hash},
                  'modules': modules,
                  'files':{n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in m.FILES}}
        (root/'distribution.json').write_text(json.dumps(report))
        return root, hashlib.sha256((root/'distribution.json').read_bytes()).hexdigest()

    def activate_a(self):
        m.stage(self.store, self.a, self.aid); m.activate(self.store, self.aid, None)

    def test_init_never_adopts_or_overwrites_existing_directory(self):
        marker = self.store/'runtime/keep';marker.write_text('keep')
        with self.assertRaises(FileExistsError):m.initialize(self.store)
        self.assertEqual('keep', marker.read_text())

    def test_stage_is_copy_only_and_idempotent(self):
        before = {n:(self.a/n).read_bytes() for n in m.FILES}
        m.stage(self.store,self.a,self.aid)
        self.assertIsNone(m.selector(self.store))
        self.assertTrue(m.stage(self.store,self.a,self.aid)['already_staged'])
        self.assertEqual(before,{n:(self.a/n).read_bytes() for n in m.FILES})

    def test_whole_update_rollback_preserves_versions_and_user_files(self):
        self.activate_a()
        marker=self.store/'runtime/history.csv';marker.write_bytes(b'original-user-history')
        m.stage(self.store,self.b,self.bid)
        m.activate(self.store,self.bid,self.aid)
        self.assertEqual(self.aid,m.selector(self.store)['previous'])
        m.activate(self.store,'',self.bid,rollback=True)
        self.assertEqual(self.aid,m.selector(self.store)['current'])
        for label in (self.aid,self.bid):m.bundle(self.store/'versions'/label,label)
        self.assertEqual(b'original-user-history',marker.read_bytes())
        self.assertEqual(3,len(list((self.store/'history').glob('*.json'))))

    def test_stale_expected_current_is_rejected_without_switch(self):
        self.activate_a();m.stage(self.store,self.b,self.bid)
        with self.assertRaises(ValueError):m.activate(self.store,self.bid,None)
        self.assertEqual(self.aid,m.selector(self.store)['current'])

    def test_failed_atomic_replace_preserves_current_and_retains_history(self):
        self.activate_a();m.stage(self.store,self.b,self.bid)
        with patch.object(m.os,'replace',side_effect=OSError('simulated interrupted commit')):
            with self.assertRaises(OSError):m.activate(self.store,self.bid,self.aid)
        self.assertEqual(self.aid,m.selector(self.store)['current'])
        self.assertTrue(list(self.store.glob('selector-*.tmp')))
        self.assertEqual(2,len(list((self.store/'history').glob('*.json'))))

    def test_interrupted_copy_never_activates_partial_version(self):
        self.activate_a()
        with patch.object(m,'write_new',side_effect=OSError('simulated disk full')):
            with self.assertRaises(OSError):m.stage(self.store,self.b,self.bid)
        self.assertEqual(self.aid,m.selector(self.store)['current'])
        self.assertFalse((self.store/'versions'/self.bid).exists())
        self.assertTrue(list((self.store/'versions').glob('pending-*')))

    def test_corrupt_candidate_or_swapped_component_is_rejected(self):
        self.activate_a()
        (self.b/m.JARS[-1]).write_bytes((self.a/m.JARS[-1]).read_bytes())
        with self.assertRaises(ValueError):m.stage(self.store,self.b,self.bid)
        self.assertEqual(self.aid,m.selector(self.store)['current'])

    def test_corrupted_active_version_can_be_rolled_back_without_trusting_it(self):
        self.activate_a();m.stage(self.store,self.b,self.bid);m.activate(self.store,self.bid,self.aid)
        (self.store/'versions'/self.bid/m.JARS[0]).write_bytes(b'broken fixture')
        with self.assertRaises(ValueError):m.launch(self.store)
        m.activate(self.store,'',self.bid,rollback=True)
        self.assertEqual(self.aid,m.selector(self.store)['current'])

    def test_launch_uses_one_explicit_generation_and_sanitizes_injected_java_options(self):
        self.activate_a()
        with patch.dict(m.os.environ,{'JAVA_TOOL_OPTIONS':'injected','CLASSPATH':'other.jar'}):
            with patch.object(m.subprocess,'run',return_value=subprocess.CompletedProcess(
                    [], 0, stdout='FEATURE_BUNDLE_PREFLIGHT_OK\n', stderr='')) as run:
                self.assertEqual(0,m.launch(self.store))
        self.assertEqual(2, run.call_count)
        self.assertEqual('logbook.core.FeatureBundlePreflight', run.call_args_list[0].args[0][-1])
        args, kwargs=run.call_args
        self.assertEqual('logbook.internal.Launcher',args[0][-1])
        self.assertEqual(self.store/'runtime',kwargs['cwd'])
        self.assertNotIn('JAVA_TOOL_OPTIONS',kwargs['env'])
        self.assertNotIn('CLASSPATH',kwargs['env'])
        cp=args[0][args[0].index('-cp')+1]
        self.assertEqual(6,len(cp.split(m.os.pathsep)))
        self.assertTrue(all(self.aid in n for n in cp.split(m.os.pathsep)))
        self.assertTrue(kwargs['pass_fds'])

    def test_preflight_failure_or_missing_marker_never_starts_launcher(self):
        self.activate_a()
        for returncode, output in ((2, ''), (0, ''), (0, 'prefix FEATURE_BUNDLE_PREFLIGHT_OK suffix\n')):
            with self.subTest(returncode=returncode, output=output):
                with patch.object(m.subprocess, 'run', return_value=subprocess.CompletedProcess(
                        [], returncode, stdout=output, stderr='fixture refusal')) as run:
                    with self.assertRaisesRegex(ValueError, 'Launcher was not started'):
                        m.launch(self.store)
                self.assertEqual(1, run.call_count)
                self.assertEqual('logbook.core.FeatureBundlePreflight', run.call_args.args[0][-1])

    def test_update_cannot_run_while_launch_lease_is_held(self):
        self.activate_a()
        with m.locked(self.store):
            with self.assertRaises(RuntimeError):m.activate(self.store,self.aid,self.aid)

    def test_child_keeps_lease_after_parent_descriptor_is_closed(self):
        with m.locked(self.store) as (_,fd):
            child=subprocess.Popen(['/bin/sleep','0.4'],pass_fds=(fd,))
        try:
            with self.assertRaises(RuntimeError):
                with m.locked(self.store):pass
        finally:
            try:child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.terminate();child.wait(timeout=5)
                self.fail('Isolated lease fixture did not exit')
        with m.locked(self.store):pass

    def test_symlinked_store_or_bundle_and_parent_traversal_are_rejected(self):
        alias=self.base/'alias';alias.symlink_to(self.a,target_is_directory=True)
        with self.assertRaises(ValueError):m.stage(self.store,alias,self.aid)
        with self.assertRaises(ValueError):m.stage(self.store,self.base/'a/../a',self.aid)
        alias2=self.base/'store-alias';alias2.symlink_to(self.store,target_is_directory=True)
        with self.assertRaises(ValueError):
            with m.locked(alias2):pass

    def test_malformed_manifest_fails_before_output_or_selector_change(self):
        (self.b/'distribution.json').write_text('[]')
        h=hashlib.sha256((self.b/'distribution.json').read_bytes()).hexdigest()
        with self.assertRaises(ValueError):m.stage(self.store,self.b,h)
        self.assertIsNone(m.selector(self.store))

    def test_duplicate_metadata_keys_and_wrong_identity_are_rejected(self):
        with self.assertRaises(ValueError):m.stage(self.store,self.a,self.bid)
        p=self.b/'distribution.json';p.write_text('{"files":{},"files":{}}')
        with self.assertRaises(ValueError):m.stage(self.store,self.b,hashlib.sha256(p.read_bytes()).hexdigest())

    def test_no_previous_version_or_missing_target_is_not_a_rollback(self):
        self.activate_a()
        with self.assertRaises(ValueError):m.activate(self.store,'',self.aid,rollback=True)
        with self.assertRaises(ValueError):m.activate(self.store,self.bid,self.aid)
        self.assertEqual(self.aid,m.selector(self.store)['current'])

if __name__=='__main__':unittest.main()
