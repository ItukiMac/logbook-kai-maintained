"""Reject incompatible or ambiguous split bundles without executing their contents.

Every mutation is confined to fresh synthetic JARs in a TemporaryDirectory.
The class bytes are inert inventory fixtures; real bytecode/preflight behavior is
covered by the Java contract suite and the separate managed-launch integration.
"""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import warnings
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_feature_distribution as builder
import run_feature_distribution as distribution


class FeatureVersionRejectionTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source = self.root / 'source.jar'
        self.entries = {
            'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\n\r\n',
            'logbook/internal/Launcher.class': b'inert host inventory',
            'logbook/internal/updatecheck/ManagedBundleMode.class': b'inert guard inventory',
            'logbook/gui/application.css': b'.root {}',
            builder.SERVICE: ('\n'.join('logbook.features.' + values[0]
                                       for values in builder.GROUPS.values()) + '\n').encode(),
        }
        for service in ('logbook.plugin.lifecycle.StartUp', 'logbook.listener.ContentListenerSpi'):
            self.entries['META-INF/services/' + service] = (b'local.kancolle.bridge.KancolleBridgeStartUp\n' if service == 'logbook.plugin.lifecycle.StartUp' else b'') + b'local.kancolle.kqs.KqsLogbookPlugin\n'
        for module_id in distribution.GROUPS:
            for entry in distribution.required_entries(module_id):
                self.entries[entry] = b'<fixture/>' if entry.endswith('.fxml') else b'inert feature inventory'
        self.write_archive(self.source, list(self.entries.items()))
        self.source_bytes = self.source.read_bytes()
        self.bundle = self.root / 'bundle'
        self.report = builder.build(self.source, self.bundle, 'a' * 40)
        self.assertEqual(self.report, distribution.verify(self.bundle))

    def tearDown(self):
        self.assertEqual(self.source_bytes, self.source.read_bytes(), 'source fixture must remain unchanged')

    @staticmethod
    def write_archive(path, entries):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)  # deliberate duplicate-entry fixtures
            with ZipFile(path, 'w', compression=ZIP_DEFLATED) as archive:
                for name, data in entries:
                    archive.writestr(name, data)

    @staticmethod
    def read_archive(path):
        with ZipFile(path) as archive:
            return [(entry.filename, archive.read(entry)) for entry in archive.infolist()]

    def write_manifest(self, report=None):
        (self.bundle / 'distribution.json').write_text(
            json.dumps(self.report if report is None else report), encoding='utf8')

    def module(self, name='calculators', report=None):
        report = self.report if report is None else report
        return next(module for module in report['modules'] if module['id'] == name)

    def rewrite_module_jar(self, entries, name='calculators'):
        module = self.module(name)
        path = self.bundle / module['path']
        self.write_archive(path, entries)
        module['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.report['files'][module['path']] = module['sha256']
        self.write_manifest()

    def change_module_entry(self, entry, content, name='calculators'):
        path = self.bundle / self.module(name)['path']
        entries = [(key, data) for key, data in self.read_archive(path) if key != entry]
        if content is not None:
            entries.append((entry, content))
        self.rewrite_module_jar(entries, name)

    def change_core_entry(self, entry, content):
        path = self.bundle / 'logbook-core.jar'
        entries = [(key, data) for key, data in self.read_archive(path) if key != entry]
        if content is not None:
            entries.append((entry, content))
        self.write_archive(path, entries)
        host_hash = hashlib.sha256(path.read_bytes()).hexdigest()
        self.report['host']['sha256'] = host_hash
        self.report['files']['logbook-core.jar'] = host_hash
        for module in self.report['modules']:
            module['host_sha256'] = host_hash
            metadata = {key: module[key] for key in distribution.METADATA_KEYS}
            self.change_module_entry(distribution.FEATURE_METADATA, json.dumps(metadata).encode(), module['id'])
        self.write_manifest()

    def embedded_module(self, name='calculators'):
        with ZipFile(self.bundle / self.module(name)['path']) as archive:
            return json.loads(archive.read(distribution.FEATURE_METADATA))

    def assert_manifest_refused(self, report):
        self.write_manifest(report)
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_missing_required_module_is_rejected(self):
        report = deepcopy(self.report)
        report['modules'].pop()
        self.assert_manifest_refused(report)

    def test_duplicate_module_id_is_rejected(self):
        report = deepcopy(self.report)
        report['modules'][1] = deepcopy(report['modules'][0])
        self.assert_manifest_refused(report)

    def test_unknown_module_id_is_rejected(self):
        report = deepcopy(self.report)
        report['modules'][0]['id'] = 'unregistered'
        self.assert_manifest_refused(report)

    def test_required_module_fields_cannot_be_missing(self):
        for field in distribution.METADATA_KEYS | {'path', 'sha256'}:
            with self.subTest(field=field):
                report = deepcopy(self.report)
                report['modules'][0].pop(field)
                self.assert_manifest_refused(report)

    def test_duplicate_and_missing_feature_ids_are_rejected(self):
        for features in [['experience', 'experience', 'probability', 'last-dance'],
                         ['experience', 'mission-check', 'probability'],
                         ['experience', 'mission-check', 'probability', 'repair-list']]:
            with self.subTest(features=features):
                report = deepcopy(self.report)
                report['modules'][0]['features'] = features
                self.assert_manifest_refused(report)

    def test_invalid_feature_id_types_are_rejected(self):
        for invalid in [None, True, 1, [], {}]:
            with self.subTest(invalid=invalid):
                report = deepcopy(self.report)
                report['modules'][0]['features'][0] = invalid
                self.assert_manifest_refused(report)

    def test_unknown_and_noninteger_host_apis_are_rejected(self):
        for invalid in [0, 2, True, 1.0, '1', None]:
            with self.subTest(invalid=invalid):
                report = deepcopy(self.report)
                report['host']['api'] = invalid
                self.assert_manifest_refused(report)

    def test_unknown_and_noninteger_module_apis_are_rejected(self):
        for invalid in [0, 2, True, 1.0, '1', None]:
            with self.subTest(invalid=invalid):
                report = deepcopy(self.report)
                report['modules'][0]['host_api'] = invalid
                self.assert_manifest_refused(report)

    def test_malformed_module_versions_are_rejected(self):
        for invalid in ['', '1', '1.0', '01.0.0', '1.00.0', '1.0.01', '1.0.0-rc1',
                        '1.0.0+build', ' 1.0.0', '1.0.0\n', '-1.0.0', '+1.0.0',
                        '2147483648.0.0', None, True, 1]:
            with self.subTest(invalid=invalid):
                report = deepcopy(self.report)
                report['modules'][0]['version'] = invalid
                self.assert_manifest_refused(report)

    def test_unknown_and_duplicate_dependencies_are_rejected(self):
        for dependencies in [
                [{'id': 'absent', 'min_version': '1.0.0', 'max_version_exclusive': '2.0.0'}],
                [{'id': 'charts', 'min_version': '1.0.0', 'max_version_exclusive': '2.0.0'}] * 2]:
            with self.subTest(dependencies=dependencies):
                report = deepcopy(self.report)
                report['modules'][0]['dependencies'] = dependencies
                self.assert_manifest_refused(report)

    def test_dependency_metadata_must_be_complete_and_typed(self):
        for dependencies in [None, {}, [None], [{'id': 'charts'}],
                [{'id': True, 'min_version': '1.0.0', 'max_version_exclusive': '2.0.0'}],
                [{'id': 'charts', 'min_version': True, 'max_version_exclusive': '2.0.0'}],
                [{'id': 'charts', 'min_version': '1.0.0', 'max_version_exclusive': '2.0.0', 'extra': 0}]]:
            with self.subTest(dependencies=dependencies):
                report = deepcopy(self.report)
                report['modules'][0]['dependencies'] = dependencies
                self.assert_manifest_refused(report)

    def test_incompatible_and_empty_version_ranges_are_rejected(self):
        for minimum, maximum in [('1.0.1', '2.0.0'), ('0.0.0', '1.0.0'),
                                 ('1.0.0', '1.0.0'), ('2.0.0', '1.0.0'), ('1.0', '2.0.0')]:
            with self.subTest(minimum=minimum, maximum=maximum):
                report = deepcopy(self.report)
                report['modules'][0]['dependencies'] = [
                    {'id': 'charts', 'min_version': minimum, 'max_version_exclusive': maximum}]
                self.assert_manifest_refused(report)

    def test_self_dependency_is_rejected(self):
        report = deepcopy(self.report)
        report['modules'][0]['dependencies'] = [
            {'id': 'calculators', 'min_version': '1.0.0', 'max_version_exclusive': '2.0.0'}]
        self.assert_manifest_refused(report)

    def test_circular_dependency_is_rejected(self):
        report = deepcopy(self.report)
        for source, target in [('calculators', 'charts'), ('charts', 'status'), ('status', 'calculators')]:
            self.module(source, report)['dependencies'] = [
                {'id': target, 'min_version': '1.0.0', 'max_version_exclusive': '2.0.0'}]
        self.assert_manifest_refused(report)

    def test_incorrect_module_host_digest_is_rejected(self):
        report = deepcopy(self.report)
        report['modules'][0]['host_sha256'] = '0' * 64
        self.assert_manifest_refused(report)

    def test_noncanonical_module_path_is_rejected(self):
        for path in ['../feature.jar', '/tmp/feature.jar', 'features/../features/logbook-feature-calculators.jar',
                     'features/logbook-feature-charts.jar', 'features\\logbook-feature-calculators.jar']:
            with self.subTest(path=path):
                report = deepcopy(self.report)
                report['modules'][0]['path'] = path
                self.assert_manifest_refused(report)

    def test_manifest_hash_syntax_and_component_hash_mismatch_are_rejected(self):
        path = self.report['modules'][0]['path']
        for invalid in ['', 'a' * 63, 'A' * 64, True, '0' * 64]:
            with self.subTest(invalid=invalid):
                report = deepcopy(self.report)
                report['modules'][0]['sha256'] = invalid
                report['files'][path] = invalid
                self.assert_manifest_refused(report)

    def test_missing_component_is_rejected(self):
        path = self.bundle / self.module()['path']
        path.rename(path.with_suffix('.retained'))
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_symbolic_component_is_rejected_even_with_matching_bytes(self):
        path = self.bundle / self.module()['path']
        retained = path.with_suffix('.retained')
        path.rename(retained)
        path.symlink_to(retained.name)
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_distribution_duplicate_json_keys_are_rejected(self):
        raw = json.dumps(self.report)
        raw = raw[:-1] + ',"modules":' + json.dumps(self.report['modules']) + '}'
        (self.bundle / 'distribution.json').write_text(raw, encoding='utf8')
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
            distribution.verify(self.bundle)

    def test_embedded_module_duplicate_json_keys_are_rejected(self):
        raw = json.dumps(self.embedded_module())
        raw = raw[:-1] + ',"host_api":1}'
        self.change_module_entry(distribution.FEATURE_METADATA, raw.encode())
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
            distribution.verify(self.bundle)

    def test_embedded_host_duplicate_json_keys_are_rejected(self):
        self.change_core_entry(distribution.HOST_METADATA, b'{"api":1,"api":1,"version":"1.0.0"}')
        with self.assertRaisesRegex(ValueError, 'Duplicate JSON key'):
            distribution.verify(self.bundle)

    def test_embedded_module_mismatch_is_rejected_after_hashes_are_refreshed(self):
        metadata = self.embedded_module()
        metadata['version'] = '1.1.0'
        self.change_module_entry(distribution.FEATURE_METADATA, json.dumps(metadata).encode())
        with self.assertRaisesRegex(ValueError, 'Embedded module metadata'):
            distribution.verify(self.bundle)

    def test_embedded_module_boolean_api_is_not_integer_one(self):
        metadata = self.embedded_module()
        metadata['host_api'] = True
        self.change_module_entry(distribution.FEATURE_METADATA, json.dumps(metadata).encode())
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_embedded_module_float_api_is_not_integer_one(self):
        metadata = self.embedded_module()
        metadata['host_api'] = 1.0
        self.change_module_entry(distribution.FEATURE_METADATA, json.dumps(metadata).encode())
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_embedded_host_boolean_api_is_not_integer_one(self):
        self.change_core_entry(distribution.HOST_METADATA, b'{"api":true,"version":"1.0.0"}')
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_embedded_host_float_api_is_not_integer_one(self):
        self.change_core_entry(distribution.HOST_METADATA, b'{"api":1.0,"version":"1.0.0"}')
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_missing_embedded_metadata_is_rejected(self):
        self.change_module_entry(distribution.FEATURE_METADATA, None)
        with self.assertRaisesRegex(ValueError, 'Missing embedded feature metadata'):
            distribution.verify(self.bundle)

    def test_missing_provider_class_is_rejected(self):
        entry = self.module()['provider'].replace('.', '/') + '.class'
        self.change_module_entry(entry, None)
        with self.assertRaisesRegex(ValueError, 'Missing required feature entry'):
            distribution.verify(self.bundle)

    def test_each_required_controller_model_and_fxml_must_be_present(self):
        for name in distribution.GROUPS:
            original = self.read_archive(self.bundle / self.module(name)['path'])
            for required in distribution.required_entries(name):
                with self.subTest(module=name, missing=required):
                    self.rewrite_module_jar([(entry, data) for entry, data in original if entry != required], name)
                    with self.assertRaisesRegex(ValueError, 'Missing required feature entry'):
                        distribution.verify(self.bundle)
            self.rewrite_module_jar(original, name)

    def test_service_provider_mismatch_is_rejected(self):
        self.change_module_entry(distribution.SERVICE, b'logbook.features.ChartFeatures\n')
        with self.assertRaisesRegex(ValueError, 'Service provider does not match'):
            distribution.verify(self.bundle)

    def test_duplicate_service_provider_is_rejected(self):
        provider = (self.module()['provider'] + '\n').encode()
        self.change_module_entry(distribution.SERVICE, provider + provider)
        with self.assertRaisesRegex(ValueError, 'Service provider does not match'):
            distribution.verify(self.bundle)

    def test_host_cannot_publish_feature_service(self):
        self.change_core_entry(distribution.SERVICE, b'logbook.features.CalculatorFeatures\n')
        with self.assertRaisesRegex(ValueError, 'provider still present in the host'):
            distribution.verify(self.bundle)

    def test_jar_implementation_version_must_match_declared_version(self):
        raw = b'Manifest-Version: 1.0\r\nImplementation-Version: 1.1.0\r\n\r\n'
        self.change_module_entry('META-INF/MANIFEST.MF', raw)
        with self.assertRaisesRegex(ValueError, 'implementation version does not match'):
            distribution.verify(self.bundle)

    def test_duplicate_zip_names_are_rejected(self):
        path = self.bundle / self.module()['path']
        entries = self.read_archive(path)
        entries.append(entries[0])
        self.rewrite_module_jar(entries)
        with self.assertRaisesRegex(ValueError, 'Duplicate JAR entry'):
            distribution.verify(self.bundle)

    def test_class_owned_by_two_jars_is_rejected(self):
        self.change_module_entry('logbook/internal/gui/ResourceChartController.class', b'conflicting class')
        with self.assertRaisesRegex(ValueError, 'Class is owned by more than one JAR'):
            distribution.verify(self.bundle)

    def test_multirelease_class_cannot_shadow_another_modules_class(self):
        self.change_module_entry('META-INF/MANIFEST.MF',
                b'Manifest-Version: 1.0\r\nImplementation-Version: 1.0.0\r\nMulti-Release: true\r\n\r\n')
        self.change_module_entry('META-INF/versions/21/logbook/internal/gui/ResourceChartController.class',
                                 b'versioned cross-module collision')
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_core_multirelease_class_cannot_shadow_a_feature_class(self):
        self.change_core_entry('META-INF/MANIFEST.MF',
                b'Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n')
        self.change_core_entry('META-INF/versions/21/logbook/internal/gui/ResourceChartController.class',
                               b'versioned core collision')
        with self.assertRaisesRegex(ValueError, 'Class is owned by more than one JAR'):
            distribution.verify(self.bundle)

    def test_core_may_keep_its_own_multi_release_variant(self):
        self.change_core_entry('META-INF/MANIFEST.MF',
                b'Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n')
        self.change_core_entry('META-INF/versions/21/logbook/internal/Launcher.class',
                               b'versioned same-owner class')
        self.assertEqual(self.report, distribution.verify(self.bundle))

    def test_fxml_owned_by_two_jars_is_rejected(self):
        self.change_module_entry('logbook/gui/resource_chart.fxml', b'<wrong-module/>')
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_jar_traversal_absolute_and_noncanonical_names_are_rejected(self):
        original = self.read_archive(self.bundle / self.module()['path'])
        for name in ['../escape.class', '/absolute.class', 'logbook\\Escape.class',
                     './logbook/Escape.class', 'logbook//Escape.class']:
            with self.subTest(name=name):
                self.rewrite_module_jar(original + [(name, b'inert')])
                with self.assertRaisesRegex(ValueError, 'Unsafe JAR entry'):
                    distribution.verify(self.bundle)

    def test_symbolic_zip_entry_is_rejected(self):
        entries = self.read_archive(self.bundle / self.module()['path'])
        link = ZipInfo('logbook/linked-resource')
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        entries.append((link, b'../outside'))
        self.rewrite_module_jar(entries)
        with self.assertRaisesRegex(ValueError, 'Unsafe JAR entry'):
            distribution.verify(self.bundle)

    def test_implicit_classpath_and_folded_classpath_are_rejected(self):
        for header in [b'Class-Path: outside.jar\r\n', b'cLaSs-PaTh: outside.jar\r\n',
                       b'Class-Path: out\r\n side.jar\r\n']:
            with self.subTest(header=header):
                raw = b'Manifest-Version: 1.0\r\nImplementation-Version: 1.0.0\r\n' + header + b'\r\n'
                self.change_module_entry('META-INF/MANIFEST.MF', raw)
                with self.assertRaisesRegex(ValueError, 'Implicit JAR class paths'):
                    distribution.verify(self.bundle)

    def test_cr_only_manifest_cannot_hide_an_implicit_classpath(self):
        self.change_core_entry('META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\rClass-Path: outside.jar\r\r')
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_unicode_whitespace_cannot_hide_a_nonempty_classpath(self):
        self.change_core_entry('META-INF/MANIFEST.MF',
                'Manifest-Version: 1.0\r\nClass-Path: \u00a0\r\n\r\n'.encode())
        with self.assertRaises(ValueError):
            distribution.verify(self.bundle)

    def test_duplicate_manifest_attributes_are_rejected_case_insensitively(self):
        self.change_module_entry('META-INF/MANIFEST.MF',
                b'Manifest-Version: 1.0\r\nImplementation-Version: 1.0.0\r\nimplementation-version: 1.0.0\r\n\r\n')
        with self.assertRaisesRegex(ValueError, 'Duplicate JAR manifest attribute'):
            distribution.verify(self.bundle)

    def test_builder_strips_cr_only_classpath_from_new_copy_only(self):
        source = self.root / 'source-cr.jar'
        entries = dict(self.entries)
        entries['META-INF/MANIFEST.MF'] = b'Manifest-Version: 1.0\rClass-Path: outside.jar\r\r'
        self.write_archive(source, list(entries.items()))
        before = source.read_bytes()
        output = self.root / 'new-cr-bundle'
        builder.build(source, output, 'b' * 40)
        self.assertEqual(before, source.read_bytes())
        with ZipFile(output / 'logbook-core.jar') as archive:
            manifest = archive.read('META-INF/MANIFEST.MF')
        self.assertNotIn(b'Class-Path:', manifest)
        distribution.verify(output)


if __name__ == '__main__':
    unittest.main()
