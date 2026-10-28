#!/usr/bin/env python3
"""Build a NEW, experimental core + three feature-JAR directory. Never install/update.

The legacy fat JAR is not changed. Features share the application's class loader;
each versioned module is pinned to the exact core it was built against.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from run_feature_distribution import (FORMAT, HOST_API, HOST_VERSION, FEATURES,
                                     PROVIDERS, FEATURE_METADATA, HOST_METADATA, verify,
                                     GROUPS, EXTRA_CLASSES, required_entries, RECORD_UI_API, RECORD_UI_PROFILE,
                                     record_ui_entry, record_ui_required_entries, EQUIPMENT_UI_API, EQUIPMENT_UI_PROFILE,
                                     equipment_ui_entry, equipment_ui_required_entries, SHIP_STATISTICS_API,
                                     SHIP_STATISTICS_PROFILE, ship_statistics_entry, ship_statistics_required_entries)

SERVICE = 'META-INF/services/logbook.plugin.api.FeatureModule'
STAMP = (2020, 1, 1, 0, 0, 0)


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def new_directory(path: Path) -> None:
    """Require a fresh directory and reject symlinked ancestors before writing."""
    path = path.absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Symlinked output path')
    path.mkdir(parents=False, exist_ok=False)


def owner(name: str, record_ui: bool = False, equipment_ui: bool = False, statistics_ui: bool = False) -> str | None:
    if statistics_ui and ship_statistics_entry(name):
        return 'charts'
    if equipment_ui and equipment_ui_entry(name):
        return 'status'
    if record_ui and record_ui_entry(name):
        return 'charts'
    for group, (provider, controllers, resources) in GROUPS.items():
        stems = ['logbook/features/' + provider]
        stems += ['logbook/internal/gui/' + c for c in controllers]
        stems += list(EXTRA_CLASSES.get(group, ()))
        if any(name == s + '.class' or name.startswith(s + '$') and name.endswith('.class') for s in stems):
            return group
        if name in {'logbook/gui/' + r + '.fxml' for r in resources}:
            return group
    return None


def write_jar(path: Path, entries: dict[str, bytes]) -> None:
    with ZipFile(path, 'x', compression=ZIP_DEFLATED) as out:
        for name, data in sorted(entries.items()):
            info = ZipInfo(name, STAMP)
            info.external_attr = 0o100644 << 16
            info.compress_type = ZIP_DEFLATED
            out.writestr(info, data)


def explicit_classpath_manifest(raw: bytes) -> bytes:
    """Remove the legacy fat-JAR's implicit dependency class path in the NEW copy."""
    result, dropping = [], False
    for line in raw.decode('utf8').replace('\r\n', '\n').replace('\r', '\n').split('\n'):
        if not line.startswith(' '):
            dropping = line.lower().startswith('class-path:')
        if not dropping:
            result.append(line)
    return '\r\n'.join(result).encode('utf8')


def build(source: Path, output: Path, revision: str) -> dict:
    if source.is_symlink() or any(p.is_symlink() for p in source.absolute().parents):
        raise ValueError('Symlinked input')
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Exact source revision required')
    original = source.read_bytes()
    with ZipFile(io.BytesIO(original)) as archive:
        infos = [i for i in archive.infolist() if not i.is_dir()]
        if len({i.filename for i in infos}) != len(infos):
            raise ValueError('Duplicate archive entries')
        for i in infos:
            p = PurePosixPath(i.filename)
            if p.is_absolute() or '..' in p.parts or '\\' in i.filename:
                raise ValueError('Unsafe archive entry')
            if i.file_size > 192*1024*1024 or (i.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Oversized or symbolic archive entry')
        if sum(i.file_size for i in infos) > 256*1024*1024:
            raise ValueError('Oversized archive')
        entries = {i.filename: archive.read(i) for i in infos}
    if any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', n, re.I) for n in entries):
        raise ValueError('Signed archives cannot be repackaged')
    providers = {line.strip() for line in entries[SERVICE].decode().splitlines() if line.strip() and not line.startswith('#')}
    expected = {'logbook.features.' + values[0] for values in GROUPS.values()}
    if providers != expected:
        raise ValueError('Feature service list changed; update the extraction contract')
    statistics_ui = SHIP_STATISTICS_API in entries
    if statistics_ui:
        missing = ship_statistics_required_entries() - entries.keys()
        if missing:
            raise ValueError('Missing ship statistics input: ' + sorted(missing)[0])
    equipment_ui = EQUIPMENT_UI_API in entries
    if equipment_ui:
        missing = equipment_ui_required_entries() - entries.keys()
        if missing:
            raise ValueError('Missing equipment UI input: ' + sorted(missing)[0])
    record_ui = RECORD_UI_API in entries
    if record_ui:
        missing = record_ui_required_entries() - entries.keys()
        if missing:
            raise ValueError('Missing record UI input: ' + sorted(missing)[0])
    for group in GROUPS:
        for required in required_entries(group):
            if required not in entries:
                raise ValueError('Missing required input: ' + required)
    # Shared relative CSS URLs must resolve inside each feature JAR as well.
    styles = {n: d for n, d in entries.items() if n.startswith('logbook/gui/') and n.endswith('.css')}
    groups = {g: {} for g in GROUPS}
    core = {}
    for name, data in entries.items():
        group = owner(name, record_ui, equipment_ui, statistics_ui)
        if group:
            groups[group][name] = data
        elif name != SERVICE:
            core[name] = data
    # The managed launcher supplies an explicit four-JAR class path. Retain all
    # other legacy attributes but never let Class-Path silently add other JARs.
    core['META-INF/MANIFEST.MF'] = explicit_classpath_manifest(core['META-INF/MANIFEST.MF'])
    core[HOST_METADATA] = (json.dumps({'api': HOST_API, 'version': HOST_VERSION},
                                     sort_keys=True) + '\n').encode()
    license_text = (Path(__file__).resolve().parents[1] / 'LICENSE').read_bytes()
    core['META-INF/LICENSE-logbook-kai.txt'] = license_text
    new_directory(output)
    (output / 'features').mkdir()
    write_jar(output / 'logbook-core.jar', core)
    files = ['logbook-core.jar']
    host_hash = sha((output / 'logbook-core.jar').read_bytes())
    modules = []
    for group, values in groups.items():
        provider = GROUPS[group][0]
        values.update(styles)
        values['META-INF/LICENSE-logbook-kai.txt'] = license_text
        values[SERVICE] = ('logbook.features.' + provider + '\n').encode()
        metadata = {'id': group, 'version': '1.0.0', 'host_api': HOST_API,
                    'host_sha256': host_hash, 'dependencies': [],
                    'provider': PROVIDERS[group], 'features': list(FEATURES[group])}
        values[FEATURE_METADATA] = (json.dumps(metadata, sort_keys=True, indent=2) + '\n').encode()
        values['META-INF/MANIFEST.MF'] = (
            'Manifest-Version: 1.0\r\nImplementation-Title: logbook-feature-' + group +
            '\r\nImplementation-Version: 1.0.0\r\nBundle-License: MIT\r\n\r\n').encode()
        name = 'features/logbook-feature-' + group + '.jar'
        write_jar(output / name, values)
        files.append(name)
        modules.append({**metadata, 'path': name, 'sha256': sha((output / name).read_bytes())})
    launcher = Path(__file__).with_name('run_feature_distribution.py').read_bytes()
    (output / 'run_feature_distribution.py').write_bytes(launcher)
    files.append('run_feature_distribution.py')
    manifest = {'format': FORMAT, 'source_revision': revision,
                'source_jar_sha256': sha(original), 'production_ready': False,
                'update_policy': 'explicit managed launch; same-core independent module updates; no legacy updater integration',
                'classloader_isolation': False,
                'host': {'api': HOST_API, 'version': HOST_VERSION,
                         'path': 'logbook-core.jar', 'sha256': host_hash},
                'modules': modules,
                'files': {n: sha((output / n).read_bytes()) for n in files},
                'extracted': {g: sorted(n for n in entries if owner(n, record_ui, equipment_ui, statistics_ui) == g) for g in GROUPS}}
    if statistics_ui:
        manifest['ship_statistics_profile'] = SHIP_STATISTICS_PROFILE
    if equipment_ui:
        manifest['equipment_ui_profile'] = EQUIPMENT_UI_PROFILE
    if record_ui:
        manifest['record_ui_profile'] = RECORD_UI_PROFILE
    # Written last. A failed/interrupted build has no valid distribution manifest.
    if source.read_bytes() != original:
        raise ValueError('Input changed during packaging')
    (output / 'distribution.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
    verify(output)
    return manifest


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--revision', required=True)
    a = p.parse_args()
    print(json.dumps(build(a.jar, a.out, a.revision), indent=2))

if __name__ == '__main__':
    main()
