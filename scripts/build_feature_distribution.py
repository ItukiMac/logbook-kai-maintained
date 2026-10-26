#!/usr/bin/env python3
"""Build a NEW, experimental core + three feature-JAR directory. Never install/update.

The legacy fat JAR is not changed. Features share the application's class loader:
legacy package-private GUI calls are deliberately not split across loaders.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

SERVICE = 'META-INF/services/logbook.plugin.api.FeatureModule'
STAMP = (2020, 1, 1, 0, 0, 0)
GROUPS = {
    'calculators': ('CalculatorFeatures',
        ('CalcExpController', 'CalcExpSeaAreaEditorController', 'MissionCheck',
         'ProbCalcController', 'LastDanceCalcController'),
        ('calc_exp', 'calc_exp_area', 'missioncheck', 'prob_calc', 'last_dance_calc')),
    'charts': ('ChartFeatures', ('ResourceChartController', 'ExpChartController'),
               ('resource_chart', 'exp_chart')),
    'status': ('StatusFeatures', ('RequireNdockController',), ('require_ndock',)),
}


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def new_directory(path: Path) -> None:
    """Require a fresh directory and reject symlinked ancestors before writing."""
    path = path.absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Symlinked output path')
    path.mkdir(parents=False, exist_ok=False)


def owner(name: str) -> str | None:
    for group, (provider, controllers, resources) in GROUPS.items():
        stems = ['logbook/features/' + provider]
        stems += ['logbook/internal/gui/' + c for c in controllers]
        if group == 'calculators':
            stems += ['logbook/internal/calc/ProbabilityCalculator', 'logbook/internal/calc/ProbabilityInput']
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
    for provider, controllers, resources in GROUPS.values():
        for required in (['logbook/features/' + provider + '.class'] +
                         ['logbook/internal/gui/' + c + '.class' for c in controllers] +
                         ['logbook/gui/' + r + '.fxml' for r in resources]):
            if required not in entries:
                raise ValueError('Missing required input: ' + required)
    # Shared relative CSS URLs must resolve inside each feature JAR as well.
    styles = {n: d for n, d in entries.items() if n.startswith('logbook/gui/') and n.endswith('.css')}
    groups = {g: {} for g in GROUPS}
    core = {}
    for name, data in entries.items():
        group = owner(name)
        if group:
            groups[group][name] = data
        elif name != SERVICE:
            core[name] = data
    # Only the original manifest is kept in core. The normal java -jar startup is
    # NOT valid for this experimental form. The supplied script only verifies it.
    license_text = (Path(__file__).resolve().parents[1] / 'LICENSE').read_bytes()
    core['META-INF/LICENSE-logbook-kai.txt'] = license_text
    new_directory(output)
    (output / 'features').mkdir()
    write_jar(output / 'logbook-core.jar', core)
    files = ['logbook-core.jar']
    for group, values in groups.items():
        provider = GROUPS[group][0]
        values.update(styles)
        values['META-INF/LICENSE-logbook-kai.txt'] = license_text
        values[SERVICE] = ('logbook.features.' + provider + '\n').encode()
        values['META-INF/MANIFEST.MF'] = (
            'Manifest-Version: 1.0\r\nImplementation-Title: logbook-feature-' + group +
            '\r\nImplementation-Version: 1\r\nBundle-License: MIT\r\n\r\n').encode()
        name = 'features/logbook-feature-' + group + '.jar'
        write_jar(output / name, values)
        files.append(name)
    launcher = Path(__file__).with_name('run_feature_distribution.py').read_bytes()
    (output / 'run_feature_distribution.py').write_bytes(launcher)
    files.append('run_feature_distribution.py')
    manifest = {'format': 'logbook-feature-distribution-v1', 'source_revision': revision,
                'source_jar_sha256': sha(original), 'production_ready': False,
                'update_policy': 'validation only; normal app startup and legacy updater integration are not enabled',
                'files': {n: sha((output / n).read_bytes()) for n in files},
                'extracted': {g: sorted(n for n in entries if owner(n) == g) for g in GROUPS}}
    # Written last. A failed/interrupted build has no valid distribution manifest.
    if source.read_bytes() != original:
        raise ValueError('Input changed during packaging')
    (output / 'distribution.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf8')
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
