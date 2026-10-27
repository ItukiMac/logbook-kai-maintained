#!/usr/bin/env python3
"""Validate a versioned split bundle before executing Java or touching runtime data.

The three module JARs use a shared application class loader. Host API 1 and an
exact core digest are both required: existing public model types are not yet an
independently versioned ABI. Hashes establish identity, not publisher trust.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
from zipfile import ZipFile

FORMAT = 'logbook-feature-distribution-v2'
HOST_API = 1
HOST_VERSION = '1.0.0'
SERVICE = 'META-INF/services/logbook.plugin.api.FeatureModule'
FEATURE_METADATA = 'META-INF/logbook/feature.json'
HOST_METADATA = 'META-INF/logbook/host-api.json'
FEATURES = {
    'calculators': ('experience', 'mission-check', 'probability', 'last-dance'),
    'charts': ('resource-chart', 'experience-chart'),
    'status': ('repair-list',),
}
PROVIDERS = {'calculators': 'logbook.features.CalculatorFeatures',
             'charts': 'logbook.features.ChartFeatures',
             'status': 'logbook.features.StatusFeatures'}
# API 1 retains these entry points used by host menus and feature FXML. Moving
# any of them requires a new packaging contract, not silently dropping a window.
GROUPS = {
    'calculators': ('CalculatorFeatures',
        ('CalcExpController', 'CalcExpSeaAreaEditorController', 'MissionCheck',
         'ProbCalcController', 'LastDanceCalcController', 'ShortageShipItem'),
        ('calc_exp', 'calc_exp_area', 'missioncheck', 'prob_calc', 'last_dance_calc')),
    'charts': ('ChartFeatures', ('ResourceChartController', 'ExpChartController', 'ResourceTable'),
               ('resource_chart', 'exp_chart')),
    'status': ('StatusFeatures', ('RequireNdockController', 'RequireNdock',
                                'AirBaseController', 'UseitemController', 'UseitemItem'),
               ('require_ndock', 'airbase', 'useitem')),
}
EXTRA_CLASSES = {'calculators': ('logbook/internal/calc/ProbabilityCalculator',
                                 'logbook/internal/calc/ProbabilityInput')}
METADATA_KEYS = {'id', 'version', 'host_api', 'host_sha256', 'dependencies',
                 'provider', 'features'}
HEX = re.compile(r'[0-9a-f]{64}')
IDENTIFIER = re.compile(r'[a-z0-9][a-z0-9._-]{0,127}')
VERSION = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')
MAX_JAR = 192 * 1024 * 1024
MAX_BUNDLE = 256 * 1024 * 1024

FILES = ('logbook-core.jar', 'features/logbook-feature-calculators.jar',
         'features/logbook-feature-charts.jar', 'features/logbook-feature-status.jar',
         'run_feature_distribution.py')
JARS = tuple(name for name in FILES if name.endswith('.jar'))


# Additive v2 packaging profile: existing three modules/classpath are retained.
# Presence of the immutable data API in the host is the profile discriminator.
RECORD_UI_PROFILE = 'record-ui-in-charts-v1'
RECORD_UI_API = 'logbook/plugin/api/RecordDataV1.class'
RECORD_UI_FAMILIES = ('BattleLogController', 'MissionLogController', 'CreateItemController',
    'BattleRecordCollect', 'BattleRecordDetail', 'BattleLogDetailAggregate',
    'MissionRecordCollect', 'MissionRecordDetail', 'MissionAggregate')
RECORD_UI_RESOURCES = ('battlelog', 'battlelog_dialog', 'missionlog', 'createitemlog')


def record_ui_entry(name):
    if name in {'logbook/gui/' + value + '.fxml' for value in RECORD_UI_RESOURCES}:
        return True
    return any(name == 'logbook/internal/gui/' + value + '.class' or
               name.startswith('logbook/internal/gui/' + value + '$') and name.endswith('.class')
               for value in RECORD_UI_FAMILIES)


def record_ui_required_entries():
    return {'logbook/internal/gui/' + value + '.class' for value in RECORD_UI_FAMILIES} | {
            'logbook/gui/' + value + '.fxml' for value in RECORD_UI_RESOURCES}


def required_entries(module_id):
    provider, controllers, resources = GROUPS[module_id]
    stems = ['logbook/features/' + provider]
    stems += ['logbook/internal/gui/' + controller for controller in controllers]
    stems += list(EXTRA_CLASSES.get(module_id, ()))
    return {stem + '.class' for stem in stems} | {'logbook/gui/' + resource + '.fxml' for resource in resources}


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key: ' + key)
        result[key] = value
    return result


def parse_json(raw):
    def invalid_constant(value):
        raise ValueError('Non-JSON numeric constant: ' + value)
    return json.loads(raw, object_pairs_hook=unique, parse_constant=invalid_constant)


def same_json(left, right):
    # Python considers True == 1 == 1.0. Contracts must retain JSON value types.
    return json.dumps(left, sort_keys=True) == json.dumps(right, sort_keys=True)


def version(value):
    if not isinstance(value, str) or VERSION.fullmatch(value) is None:
        raise ValueError('Expected a numeric major.minor.patch version')
    result = tuple(int(part) for part in value.split('.'))
    if any(part > 2147483647 for part in result):
        raise ValueError('Version component exceeds the host API limit')
    return result


def digest(value):
    if not isinstance(value, str) or HEX.fullmatch(value) is None:
        raise ValueError('Expected a complete SHA-256')
    return value


def validate_manifest(report):
    """Validate the complete compatibility graph without opening files or Java."""
    if not isinstance(report, dict) or report.get('format') != FORMAT:
        raise ValueError('Versioned distribution manifest required; legacy v1 is not launchable')
    profile = report.get('record_ui_profile')
    if profile is not None and profile != RECORD_UI_PROFILE:
        raise ValueError('Unsupported record UI packaging profile')
    files = report.get('files')
    if not isinstance(files, dict) or set(files) != set(FILES):
        raise ValueError('Unexpected distribution inventory')
    for value in files.values():
        digest(value)
    revision = report.get('source_revision')
    if not isinstance(revision, str) or re.fullmatch('[0-9a-f]{40}', revision) is None:
        raise ValueError('Exact source revision required')
    host = report.get('host')
    if not isinstance(host, dict) or set(host) != {'api', 'version', 'path', 'sha256'}:
        raise ValueError('Missing host contract')
    if type(host['api']) is not int or host['api'] != HOST_API:
        raise ValueError('Unknown host API')
    version(host['version'])
    if host['path'] != JARS[0] or host['sha256'] != files[JARS[0]]:
        raise ValueError('Host identity does not match its JAR')
    modules = report.get('modules')
    if not isinstance(modules, list) or len(modules) != len(FEATURES):
        raise ValueError('Missing or duplicate required module')
    catalog, all_features, all_providers = {}, set(), set()
    for module in modules:
        if not isinstance(module, dict) or set(module) != METADATA_KEYS | {'path', 'sha256'}:
            raise ValueError('Incomplete or unknown module metadata')
        name = module['id']
        if not isinstance(name, str) or name not in FEATURES or name in catalog:
            raise ValueError('Unknown or duplicate module ID')
        catalog[name] = module
        version(module['version'])
        if type(module['host_api']) is not int or module['host_api'] != host['api']:
            raise ValueError('Module requires an unavailable host API: ' + name)
        if module['host_sha256'] != host['sha256']:
            raise ValueError('Module was built for a different host: ' + name)
        path = 'features/logbook-feature-' + name + '.jar'
        if module['path'] != path or module['sha256'] != files[path]:
            raise ValueError('Module identity does not match its JAR: ' + name)
        provider = module['provider']
        if provider != PROVIDERS[name] or provider in all_providers:
            raise ValueError('Unknown or duplicate feature provider')
        all_providers.add(provider)
        features = module['features']
        if (not isinstance(features, list) or any(not isinstance(f, str) or
                IDENTIFIER.fullmatch(f) is None for f in features)
                or len(features) != len(set(features))
                or set(features) != set(FEATURES[name])
                or all_features.intersection(features)):
            raise ValueError('Missing, unknown or duplicate feature IDs: ' + name)
        all_features.update(features)
        if not isinstance(module['dependencies'], list):
            raise ValueError('Missing module dependencies')
    if set(catalog) != set(FEATURES):
        raise ValueError('Missing required module')
    for name, module in catalog.items():
        seen = set()
        for dependency in module['dependencies']:
            if not isinstance(dependency, dict) or set(dependency) != {
                    'id', 'min_version', 'max_version_exclusive'}:
                raise ValueError('Invalid dependency contract')
            dependency_id = dependency['id']
            if not isinstance(dependency_id, str) or dependency_id not in catalog:
                raise ValueError('Missing dependency for ' + name)
            if dependency_id in seen:
                raise ValueError('Duplicate dependency for ' + name)
            seen.add(dependency_id)
            minimum, maximum = version(dependency['min_version']), version(dependency['max_version_exclusive'])
            if minimum >= maximum:
                raise ValueError('Empty dependency version range')
            if not minimum <= version(catalog[dependency_id]['version']) < maximum:
                raise ValueError('Incompatible dependency version for ' + name)
    visiting, visited = set(), set()

    def visit(name):
        if name in visiting:
            raise ValueError('Cyclic module dependency')
        if name in visited:
            return
        visiting.add(name)
        for dependency in catalog[name]['dependencies']:
            visit(dependency['id'])
        visiting.remove(name)
        visited.add(name)

    for name in catalog:
        visit(name)
    return catalog


def manifest_attributes(raw):
    """Read only the main manifest section, with continuation and duplicate checks."""
    attributes, current = {}, None
    for line in raw.decode('utf8').replace('\r\n', '\n').replace('\r', '\n').split('\n'):
        if not line:
            break
        if line.startswith(' '):
            if current is None:
                raise ValueError('Invalid JAR manifest continuation')
            attributes[current] += line[1:]
        else:
            if ': ' not in line:
                raise ValueError('Invalid JAR manifest')
            key, value = line.split(': ', 1)
            current = key.lower()
            if current in attributes:
                raise ValueError('Duplicate JAR manifest attribute')
            attributes[current] = value
    if 'class-path' in attributes:
        raise ValueError('Implicit JAR class paths are not permitted')
    return attributes


def verify_jars(root, report, catalog):
    record_profile = report.get('record_ui_profile') == RECORD_UI_PROFILE
    classes, resources, total = set(), set(), 0
    for path in JARS:
        with ZipFile(root / path) as jar:
            entries = jar.infolist()
            names = {entry.filename for entry in entries}
            if len(names) != len(entries):
                raise ValueError('Duplicate JAR entry')
            jar_classes, jar_resources = set(), set()
            for entry in entries:
                name = entry.filename
                normalized = name[:-1] if entry.is_dir() else name
                pure = PurePosixPath(normalized)
                if (pure.is_absolute() or '..' in pure.parts or '\\' in name
                        or pure.as_posix() != normalized
                        or (entry.external_attr >> 16) & 0o170000 == 0o120000):
                    raise ValueError('Unsafe JAR entry')
                total += entry.file_size
                if entry.file_size > MAX_JAR or total > MAX_BUNDLE:
                    raise ValueError('Expanded JAR size limit exceeded')
                if path != JARS[0] and name.startswith('META-INF/versions/'):
                    raise ValueError('Multi-release feature JARs are not supported')
                if name.endswith('.class'):
                    # Core may contain third-party multi-release implementations;
                    # they own the same logical class within that ONE artifact.
                    logical = re.sub(r'^META-INF/versions/[0-9]+/', '', name)
                    jar_classes.add(logical)
                if name.endswith('.fxml'):
                    jar_resources.add(name)
            overlap = classes.intersection(jar_classes)
            if overlap:
                raise ValueError('Class is owned by more than one JAR: ' + sorted(overlap)[0])
            overlap = resources.intersection(jar_resources)
            if overlap:
                raise ValueError('FXML is owned by more than one JAR: ' + sorted(overlap)[0])
            classes.update(jar_classes)
            resources.update(jar_resources)
            if jar.testzip() is not None:
                raise ValueError('Corrupt JAR entry')
            if 'META-INF/MANIFEST.MF' not in names:
                raise ValueError('Missing JAR manifest')
            attributes = manifest_attributes(jar.read('META-INF/MANIFEST.MF'))
            if path != JARS[0] and attributes.get('multi-release', '').lower() == 'true':
                raise ValueError('Multi-release feature JARs are not supported')
            if path == JARS[0]:
                if (RECORD_UI_API in names) != record_profile:
                    raise ValueError('Record data API and packaging profile disagree')
                if record_profile and any(record_ui_entry(name) for name in names):
                    raise ValueError('Record UI remains in the host instead of charts')
                if SERVICE in names:
                    raise ValueError('Feature provider still present in the host')
                if HOST_METADATA not in names or jar.getinfo(HOST_METADATA).file_size > 65536:
                    raise ValueError('Missing host API metadata')
                if not same_json(parse_json(jar.read(HOST_METADATA)), {
                        'api': report['host']['api'], 'version': report['host']['version']}):
                    raise ValueError('Embedded host API does not match distribution')
                continue
            module = next(m for m in catalog.values() if m['path'] == path)
            required = required_entries(module['id'])
            if record_profile:
                if module['id'] == 'charts':
                    required |= record_ui_required_entries()
                elif any(record_ui_entry(name) for name in names):
                    raise ValueError('Record UI belongs to the charts module')
            missing = required - names
            if missing:
                raise ValueError('Missing required feature entry: ' + sorted(missing)[0])
            if FEATURE_METADATA not in names or jar.getinfo(FEATURE_METADATA).file_size > 65536:
                raise ValueError('Missing embedded feature metadata')
            if not same_json(parse_json(jar.read(FEATURE_METADATA)), {k: module[k] for k in METADATA_KEYS}):
                raise ValueError('Embedded module metadata does not match distribution')
            if attributes.get('implementation-version') != module['version']:
                raise ValueError('JAR implementation version does not match module')
            if SERVICE not in names or jar.getinfo(SERVICE).file_size > 65536:
                raise ValueError('Missing feature service descriptor')
            providers = [line.split('#', 1)[0].strip() for line in jar.read(SERVICE).decode('utf8').splitlines()]
            if [provider for provider in providers if provider] != [module['provider']]:
                raise ValueError('Service provider does not match module')
            if module['provider'].replace('.', '/') + '.class' not in names:
                raise ValueError('Missing feature provider class')


def verify(root: Path) -> dict:
    if '..' in root.parts:
        raise ValueError('Parent traversal is not a bundle boundary')
    root = root.absolute()
    if any(p.is_symlink() for p in (root, *root.parents)):
        raise ValueError('Symlinked bundle path')
    mp = root / 'distribution.json'
    if mp.is_symlink() or not mp.is_file() or mp.stat().st_size > 256*1024:
        raise ValueError('Unsafe distribution manifest')
    raw = mp.read_bytes()
    report = parse_json(raw)
    catalog = validate_manifest(report)
    total = 0
    for name in FILES:
        path = root / name
        if any(p.is_symlink() for p in (path, path.parent)) or not path.is_file():
            raise ValueError('Missing or symbolic bundle component: ' + name)
        total += path.stat().st_size
        if path.stat().st_size > MAX_JAR or total > MAX_BUNDLE:
            raise ValueError('Bundle size limit exceeded')
        if hashlib.sha256(path.read_bytes()).hexdigest() != report['files'][name]:
            raise ValueError('Bundle component hash mismatch: ' + name)
    verify_jars(root, report, catalog)
    if mp.read_bytes() != raw:
        raise ValueError('Manifest changed during validation')
    return report



def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, default=Path(__file__).resolve().parent)
    a = p.parse_args()
    report = verify(a.root)
    print(json.dumps({'verified': True, 'source_revision': report['source_revision'],
                      'launched': False, 'production_ready': False}))

if __name__ == '__main__':
    main()
