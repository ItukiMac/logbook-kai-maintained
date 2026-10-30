"""Real Java readers/writers and Launcher, using NEW synthetic runtime trees only.

The two fixture generations share the current declared arrival/acceptance schema.
They are not a claim of arbitrary historical application-schema conversion.
Managed store envelopes test preservation only. Historical Java readers/writers
and Launcher still execute the unchanged original fat JARs; this suite does not
certify those older providers for the v2 split launcher or bypass its preflight.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZIP_DEFLATED, ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runtime_snapshot import Limits, PATH_FIELDS, digest, encoded, inventory, restore, snapshot

ROOT = Path(__file__).resolve().parents[2]
JAR = ROOT / 'target/logbook-kai.jar'
CLASS = 'logbook.internal.kancolle.RuntimeMigrationProbe'
SOURCE = ROOT / 'scripts/java/RuntimeMigrationProbe.java'
LIMITS = Limits(reserve=0)
OLD_REVISION = 'a094771d73ce420f2bd2ddf237e0c69cf2005067'
NEW_REVISION = '06be3c9dd4137efbffa5d4f91800881dbc07a5ff'


def stamp_synthetic_bundle(bundle, label):
    """Mark NEW test output only, then re-pin every v2 contract affected by the core."""
    from run_feature_distribution import FEATURE_METADATA, METADATA_KEYS, verify

    report = json.loads((bundle / 'distribution.json').read_bytes())
    marker = 'META-INF/runtime-migration-synthetic-generation.txt'

    def rewrite(path, additions):
        with ZipFile(path) as archive:
            entries = {entry.filename: archive.read(entry) for entry in archive.infolist()}
        entries.update(additions)
        temporary = path.with_name(path.name + '.synthetic-new')
        with ZipFile(temporary, 'x', ZIP_DEFLATED) as archive:
            for name, content in sorted(entries.items()):
                archive.writestr(name, content)
        # Only generated TempDir fixtures are replaced. Input fat JARs and
        # any staged/retained application versions are never passed here.
        os.replace(temporary, path)

    core_path = bundle / report['host']['path']
    rewrite(core_path, {marker: label.encode()})
    core_sha = digest(core_path.read_bytes())
    report['host']['sha256'] = core_sha
    report['files'][report['host']['path']] = core_sha
    for module in report['modules']:
        module['host_sha256'] = core_sha
        metadata = {key: module[key] for key in METADATA_KEYS}
        path = bundle / module['path']
        rewrite(path, {marker: label.encode(), FEATURE_METADATA: encoded(metadata)})
        module['sha256'] = digest(path.read_bytes())
        report['files'][module['path']] = module['sha256']
    (bundle / 'distribution.json').write_bytes(encoded(report))
    verify(bundle)


@unittest.skipUnless(JAR.is_file() and shutil.which('java') and shutil.which('javac'),
                    'Requires the current built JAR and Java 21+; required in migration CI')
class RuntimeMigrationJavaTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='logbook-migration-java-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.classes = self.base / 'classes'
        self.classes.mkdir()
        compiled = subprocess.run(
            ['javac', '-proc:none', '--release', '21', '-cp', str(JAR), '-d', str(self.classes), str(SOURCE)],
            capture_output=True, text=True, timeout=60)
        self.assertEqual(0, compiled.returncode, compiled.stderr)
        self.logging = self.base / 'console-log4j.xml'
        self.logging.write_text('<Configuration><Appenders><Console name="C"/></Appenders>'
                                '<Loggers><Root level="warn"><AppenderRef ref="C"/></Root></Loggers>'
                                '</Configuration>')
        self.environment = {key: value for key, value in os.environ.items() if key not in (
            'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')}
        self.command = [str(Path(shutil.which('java')).resolve()),
                        '-Dlog4j2.configurationFile=' + str(self.logging),
                        '-Dlogbook.runtime.managedBundle=true', '-Dlogbook.data.autoUpdate=false',
                        '-cp', os.pathsep.join((str(JAR), str(self.classes))), CLASS]
        self.command_inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                               for path in (JAR, self.logging, *self.classes.rglob('*.class'))}

    def java_command(self, jar=None):
        command = list(self.command)
        if jar is not None:
            command[command.index('-cp') + 1] = os.pathsep.join((str(jar), str(self.classes)))
        return command

    def run_java(self, mode, root, *extra, cwd=None, jar=None):
        return subprocess.run(self.java_command(jar) + [mode, str(root), *map(str, extra)],
                              cwd=cwd or root, env=self.environment,
                              capture_output=True, text=True, timeout=45)

    def seed(self, *, backup_only=False, jar=None):
        source = self.base / 'legacy'
        external = self.base / 'external'
        result = self.run_java('seed-backup' if backup_only else 'seed', source, external, cwd=self.base, jar=jar)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('MIGRATION_FIXTURE_SEEDED records=3 accepted=2', result.stdout)
        config = source / 'config/logbook.bean.AppConfig.json'
        if backup_only:
            config = config.with_name(config.name + '.backup')
        settings = json.loads(config.read_text())
        selected = {value: Path(value) for key, value in settings.items()
                    if key in PATH_FIELDS and Path(value).is_absolute()}
        reviewed = {'plugins/retained.jar': hashlib.sha256((source / 'plugins/retained.jar').read_bytes()).hexdigest()}
        source_inventory = inventory(source, LIMITS)
        external_inventory = inventory(external, LIMITS)
        copied = snapshot(source, self.base / 'source-snapshot', stopped=True, limits=LIMITS)
        return source, external, selected, reviewed, source_inventory, external_inventory, copied

    def migrate(self, selected, reviewed, copied):
        import runtime_migration as migration
        output = self.base / 'migrated'
        plan = migration.plan(self.base / 'source-snapshot', copied['manifest_sha256'], output,
                              selected_paths=selected, reviewed_artifacts=reviewed, limits=LIMITS)
        # Explicitly review exactly the two inert fixture artifacts we created.
        # Never turn every arbitrary discovered plugin/script into an approval.
        ffmpeg_targets = {item['target'] for item in plan['references']
                          if item['field'] == 'ffmpegPath'}
        self.assertEqual(1, len(ffmpeg_targets))
        ffmpeg_target = ffmpeg_targets.pop()
        self.assertEqual({'plugins/retained.jar', ffmpeg_target}, set(plan['artifacts']))
        expected_ffmpeg = hashlib.sha256((self.base / 'external/ffmpegPath.bin').read_bytes()).hexdigest()
        self.assertEqual(expected_ffmpeg, plan['artifacts'][ffmpeg_target])
        reviewed = {**reviewed, ffmpeg_target: expected_ffmpeg}
        plan = migration.plan(self.base / 'source-snapshot', copied['manifest_sha256'], output,
                              selected_paths=selected, reviewed_artifacts=reviewed, limits=LIMITS)
        receipt = migration.apply(plan, expected_plan_sha256=digest(encoded(plan)),
                                  confirm_copy=True, limits=LIMITS)
        self.assertFalse(receipt.get('launched', False))
        runtime = output / 'data'
        config = json.loads((runtime / 'config/logbook.bean.AppConfig.json').read_text())
        for field in PATH_FIELDS:
            raw = config[field]
            self.assertFalse(Path(raw).is_absolute(), field)
            self.assertTrue((runtime / raw).resolve().is_relative_to(runtime), field)
            self.assertTrue((runtime / raw).exists(), field)
        return output, runtime

    def approved_launch(self, output, mode, *, jar=None):
        import runtime_migration as migration
        base_command = self.java_command(jar)
        command = base_command[:1] + ['-Duser.home={home}', '-Djava.io.tmpdir={tmp}'] + base_command[1:] + [mode, '{runtime}']
        command_inputs = dict(self.command_inputs)
        if jar is None and mode == 'launcher':
            # E's managed mode requires provider descriptors in separate feature
            # JARs. An intact fat JAR is not a valid managed distribution. Keep
            # historical unmodified JAR checks below separate from this current
            # application path; never bypass FeatureRegistry to make it launch.
            from build_feature_distribution import build
            from feature_bundle_manager import JARS
            distribution = self.base / 'current-managed-launch'
            revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
            build(JAR, distribution, revision)
            jars = [distribution / name for name in JARS]
            command[command.index('-cp') + 1] = os.pathsep.join(map(str, [*jars, self.classes]))
            preflight = self.java_command()
            preflight[preflight.index('-cp') + 1] = os.pathsep.join(map(str, jars))
            preflight[-1] = 'logbook.core.FeatureBundlePreflight'
            checked = subprocess.run(preflight, cwd=self.base, env=self.environment,
                                     capture_output=True, text=True, timeout=30)
            self.assertEqual(0, checked.returncode, checked.stdout + checked.stderr)
            self.assertIn('FEATURE_BUNDLE_PREFLIGHT_OK', checked.stdout.splitlines())
            # Revalidate and watch all exact preflight inputs at approval/launch.
            for path in [*jars, distribution / 'distribution.json']:
                command_inputs[str(path)] = digest(path.read_bytes())
            print(f'MIGRATION_CURRENT_SPLIT_PREFLIGHT_OK jars={len(jars)} strict_descriptor_validation=true')
        if jar is not None:
            command_inputs[str(jar)] = digest(jar.read_bytes())
        receipt_hash = digest((output / 'migration.json').read_bytes())
        with self.assertRaises(ValueError):
            migration.approve(output, receipt_hash, command=command,
                              command_inputs=command_inputs, confirm=False, limits=LIMITS)
        authorization = migration.approve(output, receipt_hash, command=command,
                                          command_inputs=command_inputs, confirm=True, limits=LIMITS)
        with self.assertRaises(ValueError):
            migration.launch(output, authorization, confirm_launch=False, limits=LIMITS)
        try:
            result = migration.launch(output, authorization, confirm_launch=True, timeout=60, limits=LIMITS)
        except ValueError as error:
            # Include retained gate evidence in CI output before the synthetic
            # TemporaryDirectory is disposed; never hide a failed real launch.
            evidence = [path.read_text() for path in sorted((output / 'validation-runs').glob('*/result.json'))]
            self.fail(str(error) + '\n' + '\n'.join(evidence))
        self.assertEqual(0, result['returncode'], result.get('stdout', '') + result.get('stderr', ''))
        return result

    def exercise_roundtrip(self, *, backup_only=False, launcher=False):
        source, external, selected, reviewed, source_before, external_before, copied = self.seed(backup_only=backup_only)
        # A byte-exact restore still contains the old external references. The Java
        # check must reject it before Config.store or any journal append is allowed.
        unsafe = restore(self.base / 'source-snapshot', self.base / 'unsafe-copy', copied['manifest_sha256'], limits=LIMITS)
        refused = self.run_java('verify', Path(unsafe['restored_to']))
        self.assertNotEqual(0, refused.returncode)
        self.assertIn('Path escapes the copied runtime', refused.stdout + refused.stderr)
        output, runtime = self.migrate(selected, reviewed, copied)
        before = inventory(runtime, LIMITS)
        # This snapshot contains the old state AND every preserved application,
        # plugin and image byte. A later selector rollback alone is not sufficient.
        old = snapshot(runtime, self.base / 'before-launch', stopped=True, limits=LIMITS)
        result = self.approved_launch(output, 'launcher' if launcher else 'mutate')
        self.assertIn('MIGRATION_REAL_JAVA_CHANGED records=4 accepted=1', result['stdout'])
        if launcher:
            self.assertIn('MIGRATION_REAL_LAUNCHER_OK', result['stdout'])
        changed = self.run_java('verify-changed', runtime)
        self.assertEqual(0, changed.returncode, changed.stdout + changed.stderr)
        self.assertNotEqual(before, inventory(runtime, LIMITS))
        recovered = restore(self.base / 'before-launch', self.base / 'recovered-old', old['manifest_sha256'], limits=LIMITS)
        recovered_runtime = Path(recovered['restored_to'])
        self.assertEqual(before, inventory(recovered_runtime, LIMITS))
        recovered_result = self.run_java('verify', recovered_runtime)
        self.assertEqual(0, recovered_result.returncode, recovered_result.stdout + recovered_result.stderr)
        self.assertIn('MIGRATION_REAL_JAVA_OK records=3 accepted=2', recovered_result.stdout)
        self.assertEqual(source_before, inventory(source, LIMITS))
        self.assertEqual(external_before, inventory(external, LIMITS))
        self.assertTrue((runtime / 'generation-B-added.txt').is_file())
        self.assertFalse((recovered_runtime / 'generation-B-added.txt').exists())
        print('RUNTIME_MIGRATION_JAVA_OK backup_only=' + str(backup_only).lower()
              + ' real_launcher=' + str(launcher).lower()
              + ' source_unchanged=true external_unchanged=true old_state_restored=true')

    def test_real_java_copy_rewrite_approved_mutation_and_old_state_restore(self):
        self.exercise_roundtrip()

    def test_backup_only_config_is_rewritten_and_restored_with_real_java(self):
        self.exercise_roundtrip(backup_only=True)

    def test_real_java_schema_import_generations_and_whole_managed_store_restore(self):
        self.exercise_managed_pair(JAR, JAR, declared_versions=False)

    @unittest.skipUnless(os.environ.get('LOGBOOK_MIGRATION_OLD_JAR') and os.environ.get('LOGBOOK_MIGRATION_NEW_JAR'),
                         'Exact R10/PR50 JAR fixtures are built by the dedicated migration CI')
    def test_declared_historical_application_versions_restore_real_old_state(self):
        old_jar = Path(os.environ['LOGBOOK_MIGRATION_OLD_JAR']).resolve()
        new_jar = Path(os.environ['LOGBOOK_MIGRATION_NEW_JAR']).resolve()
        for jar, revision in ((old_jar, OLD_REVISION), (new_jar, NEW_REVISION)):
            provenance = json.loads((jar.parent / 'source-provenance.json').read_text())
            self.assertEqual(revision, provenance['source_commit'])
            self.assertTrue(provenance['tracked_source_unchanged'])
            self.assertEqual(digest(jar.read_bytes()), provenance['jar_sha256'])
        # These exact real application revisions share the known state schema,
        # but have different compiled UI code; no generation-marker substitution.
        with ZipFile(old_jar) as old, ZipFile(new_jar) as new:
            for name in ('logbook/bean/AppConfig.class', 'logbook/bean/AppQuestDuration.class',
                         'logbook/internal/Config.class', 'logbook/internal/kancolle/PortArrival.class',
                         'logbook/internal/kancolle/PortArrivalStore.class',
                         'logbook/internal/kancolle/PortArrivalRuntime.class'):
                self.assertEqual(old.read(name), new.read(name), name)
            self.assertNotEqual(old.read('logbook/internal/gui/QuestConditionEditController.class'),
                                new.read('logbook/internal/gui/QuestConditionEditController.class'))
        # Retained historical four-JAR applications must use their retained v2
        # packaging tools, not be relabelled as a six-JAR kai2 distribution.
        tools_root = Path(os.environ['LOGBOOK_LEGACY_SPLIT_TOOLS']).resolve()
        pinned = '40cb99c46715715bafcb3f4322db39218f0af7f6'
        actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=tools_root, text=True).strip()
        self.assertEqual(pinned, actual)
        subprocess.run(['git', 'diff', '--exit-code'], cwd=tools_root, check=True)
        worker = """
import sys
from pathlib import Path
sys.path.insert(0, sys.argv[1])
import run_feature_distribution, build_feature_distribution, feature_bundle_manager, runtime_managed_import
assert run_feature_distribution.FORMAT == 'logbook-feature-distribution-v2'
sys.path.insert(0, sys.argv[2])
from scripts.tests.test_runtime_migration_java import RuntimeMigrationJavaTest
case = RuntimeMigrationJavaTest()
try:
    case.setUp()
    case.exercise_managed_pair(Path(sys.argv[3]), Path(sys.argv[4]), declared_versions=True)
finally:
    case.doCleanups()
print('HISTORICAL_SPLIT_V2_TOOLS_OK')
"""
        checked = subprocess.run([sys.executable, '-c', worker, str(tools_root / 'scripts'),
                                  str(ROOT), str(old_jar), str(new_jar)], cwd=ROOT,
                                 env=self.environment, capture_output=True, text=True, timeout=240)
        self.assertEqual(0, checked.returncode, checked.stdout + checked.stderr)
        self.assertIn('HISTORICAL_SPLIT_V2_TOOLS_OK', checked.stdout)
        print(checked.stdout, end='')
        print('RUNTIME_MIGRATION_DECLARED_VERSIONS_OK old=' + OLD_REVISION + ' new=' + NEW_REVISION
              + ' schema_conversion=false old_reader_new_writer_old_reader=true'
              + ' real_launcher=' + str(bool(os.environ.get('DISPLAY'))).lower()
              + ' split_bundle_launch_validated=false')

    def exercise_managed_pair(self, old_jar, new_jar, *, declared_versions):
        import feature_bundle_manager as manager
        import runtime_managed_import as managed
        from build_feature_distribution import build
        original_jars = {path: digest(path.read_bytes()) for path in (old_jar, new_jar)}
        source, external, selected, reviewed, source_before, external_before, copied = self.seed(jar=old_jar)
        output, runtime = self.migrate(selected, reviewed, copied)
        bundles, identities = [], {}
        for label, jar, revision in (('A', old_jar, OLD_REVISION), ('B', new_jar, NEW_REVISION)):
            bundle = self.base / ('bundle-' + label)
            build(jar, bundle, revision if declared_versions else label.lower() * 40)
            if not declared_versions:
                stamp_synthetic_bundle(bundle, label)
            # These envelopes exercise byte-preserving managed import/restore.
            # In the historical case, v2 metadata does not certify the old
            # provider ABI: all real Java executions below use old_jar/new_jar
            # unchanged. Managed v2 launch remains a separate E preflight gate.
            identities[label] = digest((bundle / 'distribution.json').read_bytes())
            bundles.append((bundle, identities[label]))
        old_runtime = snapshot(runtime, self.base / 'old-runtime', stopped=True, limits=LIMITS)
        old_store = self.base / 'store-A'
        imported = managed.import_snapshot(self.base / 'old-runtime', old_store, old_runtime['manifest_sha256'],
            profile='arrival-journal-v1', bundles=bundles, selected_bundle_sha256=identities['A'],
            confirm_import=True, protected_roots=(source, external), limits=LIMITS)
        self.assertFalse(imported['launch_safe'])
        self.assertEqual(identities['A'], manager.selector(old_store)['current'])
        verified = self.run_java('verify', old_store / 'runtime', jar=old_jar)
        self.assertEqual(0, verified.returncode, verified.stdout + verified.stderr)
        whole = snapshot(old_store, self.base / 'whole-A', stopped=True, limits=LIMITS)
        old_store_inventory = inventory(old_store, LIMITS)
        use_launcher = declared_versions and bool(os.environ.get('DISPLAY'))
        launched = self.approved_launch(output, 'launcher' if use_launcher else 'mutate', jar=new_jar)
        if use_launcher:
            self.assertIn('MIGRATION_REAL_LAUNCHER_OK', launched['stdout'])
        new_runtime = snapshot(runtime, self.base / 'changed-runtime', stopped=True, limits=LIMITS)
        new_store = self.base / 'store-B'
        managed.import_snapshot(self.base / 'changed-runtime', new_store, new_runtime['manifest_sha256'],
            profile='arrival-journal-v1', bundles=bundles, selected_bundle_sha256=identities['B'],
            confirm_import=True, protected_roots=(source, external, old_store), limits=LIMITS)
        self.assertEqual(identities['B'], manager.selector(new_store)['current'])
        verified = self.run_java('verify-changed', new_store / 'runtime', jar=new_jar)
        self.assertEqual(0, verified.returncode, verified.stdout + verified.stderr)
        recovered = managed.restore_store(self.base / 'whole-A', self.base / 'recovered-store-A',
            whole['manifest_sha256'], profile='arrival-journal-v1', confirm_restore=True,
            protected_roots=(source, external, old_store, new_store), limits=LIMITS)
        recovered_store = Path(recovered['restored_store'])
        self.assertFalse(recovered['launch_safe'])
        self.assertEqual(old_store_inventory, inventory(recovered_store, LIMITS))
        self.assertEqual(identities['A'], manager.selector(recovered_store)['current'])
        for identity in identities.values():
            manager.bundle(recovered_store / 'versions' / identity, identity)
        verified = self.run_java('verify', recovered_store / 'runtime', jar=old_jar)
        self.assertEqual(0, verified.returncode, verified.stdout + verified.stderr)
        self.assertEqual(old_store_inventory, inventory(old_store, LIMITS))
        self.assertEqual(source_before, inventory(source, LIMITS))
        self.assertEqual(external_before, inventory(external, LIMITS))
        for path, checksum in original_jars.items():
            self.assertEqual(checksum, digest(path.read_bytes()))
        print('RUNTIME_MIGRATION_MANAGED_JAVA_OK versions=2 whole_store_restored=true'
              ' source_unchanged=true external_unchanged=true old_arrivals=3 old_accepted=2'
              ' new_arrivals=4 new_accepted=1 split_bundle_launch_validated=false')

    @unittest.skipUnless(shutil.which('xvfb-run'), 'Real Launcher needs Xvfb; required in migration CI')
    def test_actual_launcher_start_change_restore_with_external_write_monitor(self):
        # The gate must see Java directly, not an unpinned GUI wrapper. Launch a
        # fresh instance of this test under Xvfb when the caller is headless.
        if not os.environ.get('DISPLAY'):
            xvfb_log = self.base / 'xvfb.log'
            result = subprocess.run(['xvfb-run', '-a', '-e', str(xvfb_log), sys.executable, '-m', 'unittest',
                                     'scripts.tests.test_runtime_migration_java.RuntimeMigrationJavaTest.'
                                     'test_actual_launcher_start_change_restore_with_external_write_monitor', '-v'],
                                    cwd=ROOT, env=self.environment,
                                    capture_output=True, text=True, timeout=120)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr
                             + (xvfb_log.read_text() if xvfb_log.is_file() else ''))
            self.assertIn('real_launcher=true', result.stdout)
            print(result.stdout, end='')
            return
        self.exercise_roundtrip(launcher=True)


if __name__ == '__main__':
    unittest.main()
