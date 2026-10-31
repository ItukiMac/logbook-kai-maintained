#!/usr/bin/env python3
"""Opt-in POSIX split-bundle store: retain all versions; change one selector, not JARs.

Only a newly initialized store is accepted. No import of a live installation,
network download, release publication, cleanup or automatic rollback is provided.
The selector rollback restores application files, NEVER user data/schema changes.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid
from zipfile import ZipFile, BadZipFile
from run_feature_distribution import verify, validate_manifest, parse_json, FILES

FORMAT = 'logbook-managed-split-v1'
HEX = re.compile(r'[0-9a-f]{64}')
JARS = tuple(n for n in FILES if n.endswith('.jar'))


def checked(path: Path) -> Path:
    if '..' in path.parts:
        raise ValueError('Parent traversal is not a store boundary')
    path = path.absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Symbolic paths are not supported')
    return path


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def read_json(path: Path, limit=262144):
    checked(path)
    if not path.is_file() or path.stat().st_size > limit:
        raise ValueError('Missing or oversized metadata')
    return json.loads(path.read_text(encoding='utf8'), object_pairs_hook=unique)


def write_new(path: Path, data: bytes):
    checked(path)
    with path.open('xb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())


def encoded(data):
    return (json.dumps(data, sort_keys=True, indent=2) + '\n').encode()


def sync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def initialize(root: Path):
    root = checked(root)
    root.mkdir(mode=0o700, parents=False, exist_ok=False)
    for name in ('versions', 'runtime', 'history'):
        (root / name).mkdir(mode=0o700)
    (root / 'runtime' / 'home').mkdir(mode=0o700)
    write_new(root / 'store.lock', b'')
    # A final marker distinguishes initialized stores from arbitrary app directories.
    write_new(root / 'store.json', encoded({'format': FORMAT, 'state_rollback': False}))
    sync_dir(root)
    return {'initialized': str(root), 'production_migrated': False}


@contextmanager
def locked(root: Path):
    import fcntl  # POSIX/Mint only; no false cross-platform lock guarantee.
    root = checked(root)
    if read_json(root / 'store.json') != {'format': FORMAT, 'state_rollback': False}:
        raise ValueError('Not a recognized split store')
    for name in ('versions', 'runtime', 'history', 'runtime/home'):
        if not checked(root / name).is_dir():
            raise ValueError('Missing store directory')
    path = checked(root / 'store.lock')
    fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as ex:
            raise RuntimeError('Store is in use; stop the managed application first') from ex
        yield root, fd
    finally:
        # Do NOT unlock explicitly: a launched child may still hold the inherited
        # descriptor after the wrapper is terminated. Closing preserves its lease.
        os.close(fd)


def identifier(value):
    if not isinstance(value, str) or HEX.fullmatch(value) is None:
        raise ValueError('A complete manifest SHA-256 is required')
    return value


def bundle_manifest(path: Path, expected: str):
    """Read the immutable contract, even when an old JAR needs recovery."""
    path = checked(path)
    expected = identifier(expected)
    mp = checked(path / 'distribution.json')
    if not mp.is_file() or mp.stat().st_size > 262144:
        raise ValueError('Missing or oversized manifest')
    raw = mp.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError('Distribution identity mismatch')
    report = parse_json(raw)
    if (not isinstance(report, dict) or not isinstance(report.get('files'), dict)
            or set(report['files']) != set(FILES)
            or not isinstance(report.get('source_revision'), str)
            or re.fullmatch('[0-9a-f]{40}', report['source_revision']) is None):
        raise ValueError('Invalid bundle inventory or source revision')
    validate_manifest(report)
    if mp.read_bytes() != raw:
        raise ValueError('Manifest changed while validating')
    return raw, report


def bundle(path: Path, expected: str):
    path = checked(path)
    raw, report = bundle_manifest(path, expected)
    total = 0
    for name in FILES:
        identifier(report['files'][name])
        p = checked(path / name)
        if not p.is_file():
            raise ValueError('Missing bundle component')
        total += p.stat().st_size
        if p.stat().st_size > 192*1024*1024 or total > 256*1024*1024:
            raise ValueError('Bundle size limit exceeded')
    if report != verify(path) or (path / 'distribution.json').read_bytes() != raw:
        raise ValueError('Manifest changed while validating')
    for name in JARS:
        p = path / name
        with ZipFile(p) as jar:
            entries = jar.infolist()
            if len({i.filename for i in entries}) != len(entries):
                raise ValueError('Duplicate JAR entry')
            if sum(i.file_size for i in entries) > 256*1024*1024 or jar.testzip() is not None:
                raise ValueError('Invalid or oversized JAR')
    return raw, report


def validate_selector(value):
    if not isinstance(value, dict) or set(value) != {'format', 'sequence', 'current', 'previous'} or value['format'] != FORMAT:
        raise ValueError('Invalid current selector')
    identifier(value['current'])
    if value['previous'] is not None:
        identifier(value['previous'])
    if type(value['sequence']) is not int or value['sequence'] < 1:
        raise ValueError('Invalid selector sequence')
    if (value['sequence'] == 1) != (value['previous'] is None):
        raise ValueError('Invalid selector predecessor')
    return value


def selector(root):
    p = root / 'current.json'
    if not p.exists() and not p.is_symlink():
        return None
    return validate_selector(read_json(p))


def check_module_identities(identities, report):
    """A module version has one JAR identity within its exact host core."""
    for module in report['modules']:
        key = (report['host']['sha256'], module['id'], module['version'])
        previous = identities.setdefault(key, module['sha256'])
        if previous != module['sha256']:
            raise ValueError('Changed module bytes require a new module version: '
                             + module['id'] + ' ' + module['version'])


def retained_module_identities(root):
    """Validate retained contracts and history under the caller's store lock.

    Manifest hashes fix prior identities without trusting a broken old JAR.
    Candidate/selected JARs still undergo full bundle validation. Incomplete
    pending copies are preserved evidence, not registered module releases.
    """
    identities, versions = {}, set()
    for path in sorted((root / 'versions').iterdir()):
        if not checked(path).is_dir():
            raise ValueError('Unknown retained version entry')
        if re.fullmatch(r'pending-[0-9a-f]{32}', path.name):
            continue
        version_id = identifier(path.name)
        _, report = bundle_manifest(path, version_id)
        check_module_identities(identities, report)
        versions.add(version_id)

    def state(value):
        if value is None:
            return None
        validate_selector(value)
        for version_id in (value['current'], value['previous']):
            if version_id is not None and version_id not in versions:
                raise ValueError('Selector/history references a missing retained version')
        return value['sequence'], value['current'], value['previous']

    states, predecessors = set(), set()
    for path in sorted((root / 'history').iterdir()):
        match = re.fullmatch(r'([1-9][0-9]*)-[0-9a-f]{32}\.json', path.name)
        if match is None:
            raise ValueError('Unknown activation history entry')
        record = read_json(path)
        if (not isinstance(record, dict)
                or set(record) != {'before', 'after', 'user_data_restored'}
                or record['user_data_restored'] is not False):
            raise ValueError('Invalid activation history')
        before, after = state(record['before']), state(record['after'])
        if (after is None or after[0] != int(match.group(1))
                or after[0] != (before[0] + 1 if before else 1)
                or after[2] != (before[1] if before else None)
                or after[1] == after[2]):
            raise ValueError('Invalid activation history transition')
        # Failed selector replacements leave valid intent records. Duplicate
        # sequences/branches are allowed; every predecessor must remain known.
        states.add(after)
        predecessors.add(before)
    if any(before is not None and before not in states for before in predecessors):
        raise ValueError('Incomplete activation history')
    current = state(selector(root))
    if ((current is not None and current not in states)
            or (current is None and any(before is not None for before in predecessors))):
        raise ValueError('Selector does not match retained activation history')
    selected_states = {}
    for before in predecessors:
        if before is None:
            continue
        # Unlike an after intent, a before record proves which state was
        # selected. It cannot fork or run ahead of the current selector.
        known = selected_states.setdefault(before[0], before)
        if (known != before or before[0] > current[0]
                or (before[0] == current[0] and before != current)):
            raise ValueError('Conflicting selector/activation history')
    return identities


def stage(root: Path, source: Path, expected: str):
    source = checked(source)
    with locked(root) as (root, _):
        if source.is_relative_to(root) or root.is_relative_to(source):
            raise ValueError('Source and managed store must be disjoint')
        raw, report = bundle(source, expected)
        check_module_identities(retained_module_identities(root), report)
        target = root / 'versions' / expected
        if target.exists() or target.is_symlink():
            bundle(target, expected)
            return {'version': expected, 'already_staged': True, 'activated': False}
        pending = root / 'versions' / ('pending-' + uuid.uuid4().hex)
        pending.mkdir(mode=0o700)
        (pending / 'features').mkdir()
        for name in FILES:
            data = checked(source / name).read_bytes()
            if hashlib.sha256(data).hexdigest() != report['files'][name]:
                raise ValueError('Source component changed during copy')
            write_new(pending / name, data)
        write_new(pending / 'distribution.json', raw)
        bundle(pending, expected)
        sync_dir(pending / 'features')
        sync_dir(pending)
        # On failure, retain pending files as evidence. No selector has changed.
        os.rename(pending, target)
        sync_dir(root / 'versions')
        return {'version': expected, 'already_staged': False, 'activated': False}


def stage_feature(root: Path, source: Path, expected: str, module_id: str,
                  expected_current: str):
    """Compose one module into a NEW retained bundle; never activate or edit data.

    Source is a complete hash-pinned distribution, not an arbitrary untrusted
    JAR. Only its selected module is used. The exact host and all other current
    modules are retained, and their combined dependency graph must be valid.
    """
    source = checked(source)
    with locked(root) as (root, _):
        if source.is_relative_to(root) or root.is_relative_to(source):
            raise ValueError('Source and managed store must be disjoint')
        old = selector(root)
        if old is None or old['current'] != identifier(expected_current):
            raise ValueError('Current version changed; refresh before single-module staging')
        current_id = old['current']
        current_root = root / 'versions' / current_id
        _, current = bundle(current_root, current_id)
        _, candidate = bundle(source, expected)
        if candidate['host'] != current['host']:
            raise ValueError('Independent module updates require the exact same host')
        current_modules = validate_manifest(current)
        candidate_modules = validate_manifest(candidate)
        if module_id not in current_modules or module_id not in candidate_modules:
            raise ValueError('Unknown module selected for update')
        before, replacement = current_modules[module_id], candidate_modules[module_id]
        identities = retained_module_identities(root)
        if replacement == before:
            return {'version': current_id, 'already_staged': True, 'activated': False,
                    'module': module_id, 'unchanged': True}
        if replacement['version'] == before['version']:
            raise ValueError('Changed module bytes require a new module version')
        report = deepcopy(current)
        report['modules'] = [deepcopy(replacement) if module['id'] == module_id else module
                             for module in report['modules']]
        report['files'][replacement['path']] = replacement['sha256']
        report['composition'] = {
            'base_manifest_sha256': current_id,
            'candidate_manifest_sha256': identifier(expected),
            'candidate_source_revision': candidate['source_revision'],
            'updated_module': module_id,
            'runtime_data_modified': False,
        }
        # This validation includes dependents of the replaced module. Validate
        # before creating even a pending directory, not only candidate in isolation.
        validate_manifest(report)
        check_module_identities(identities, report)
        raw = encoded(report)
        version_id = hashlib.sha256(raw).hexdigest()
        target = root / 'versions' / version_id
        if target.exists() or target.is_symlink():
            bundle(target, version_id)
            return {'version': version_id, 'already_staged': True, 'activated': False,
                    'module': module_id}
        pending = root / 'versions' / ('pending-' + uuid.uuid4().hex)
        pending.mkdir(mode=0o700)
        (pending / 'features').mkdir()
        for name in FILES:
            origin = source if name == replacement['path'] else current_root
            data = checked(origin / name).read_bytes()
            if hashlib.sha256(data).hexdigest() != report['files'][name]:
                raise ValueError('Component changed during single-module copy')
            write_new(pending / name, data)
        write_new(pending / 'distribution.json', raw)
        bundle(pending, version_id)
        # Source versions are immutable by convention. Recheck them before the
        # staged bundle becomes addressable. Failed pending evidence is retained.
        bundle(current_root, current_id)
        bundle(source, expected)
        sync_dir(pending / 'features')
        sync_dir(pending)
        os.rename(pending, target)
        sync_dir(root / 'versions')
        return {'version': version_id, 'already_staged': False, 'activated': False,
                'module': module_id, 'previous_version': before['version'],
                'new_version': replacement['version']}


def activate(root: Path, target: str, expected_current: str | None, *, rollback=False):
    with locked(root) as (root, _):
        old = selector(root)
        actual = old['current'] if old else None
        if actual != expected_current:
            raise ValueError('Current version changed; refresh before retrying')
        if rollback:
            if old is None or old['previous'] is None:
                raise ValueError('No retained previous version')
            target = old['previous']
        target = identifier(target)
        retained_module_identities(root)
        bundle(root / 'versions' / target, target)
        if target == actual:
            return old
        new = {'format': FORMAT, 'sequence': old['sequence'] + 1 if old else 1,
               'current': target, 'previous': actual}
        # Durable, append-only before/after record before the sole replace operation.
        token = uuid.uuid4().hex
        write_new(root / 'history' / (str(new['sequence']) + '-' + token + '.json'),
                  encoded({'before': old, 'after': new, 'user_data_restored': False}))
        sync_dir(root / 'history')
        temp = root / ('selector-' + token + '.tmp')
        write_new(temp, encoded(new))
        os.replace(temp, root / 'current.json')
        sync_dir(root)
        return new


def launch(root: Path, java: str = 'java', bridge_port: int = 8892, proxy_port: int = 8889) -> int:
    if not (1024 <= bridge_port <= 65535 and 1024 <= proxy_port <= 65535) or bridge_port == proxy_port:
        raise ValueError('Distinct bridge/proxy ports from 1024 to 65535 are required')
    with locked(root) as (root, fd):
        current = selector(root)
        if current is None:
            raise ValueError('Select a staged version before launch')
        retained_module_identities(root)
        version = root / 'versions' / current['current']
        bundle(version, current['current'])
        with ZipFile(version / JARS[0]) as jar:
            if 'logbook/internal/updatecheck/ManagedBundleMode.class' not in jar.namelist():
                raise ValueError('Host lacks the managed-bundle updater guard')
        executable = shutil.which(java)
        if executable is None:
            raise ValueError('Java executable not found')
        runtime = root / 'runtime'
        env = {k:v for k,v in os.environ.items() if k not in (
            'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH')}
        common = [executable, '-Dlogbook.runtime.managedBundle=true',
                   '-Dlogbook.bridge.port=' + str(bridge_port),
                   '-Dlogbook.proxy.port=' + str(proxy_port),
                   '-Dlogbook.data.autoUpdate=false', '-Duser.home=' + str(runtime / 'home'),
                   '-cp', os.pathsep.join(str(version / n) for n in JARS)]
        # Validate actual provider bytecode before Launcher/Config/proxy/plugins
        # initialization. JSON alone cannot prove that code declares the same API.
        preflight = subprocess.run(common + ['logbook.core.FeatureBundlePreflight'],
                                   cwd=runtime, env=env, pass_fds=(fd,), check=False,
                                   capture_output=True, text=True, timeout=30)
        if (preflight.returncode != 0 or
                'FEATURE_BUNDLE_PREFLIGHT_OK' not in (preflight.stdout or '').splitlines()):
            details = ((preflight.stdout or '') + (preflight.stderr or ''))[-4000:]
            raise ValueError('Packaged feature preflight refused; Launcher was not started\n' + details)
        command = common + ['logbook.internal.Launcher']
        # Hold one version and one workspace for the entire process. The child
        # inherits the lease so killing this Python parent cannot enable an update.
        return subprocess.run(command, cwd=runtime, env=env, pass_fds=(fd,), check=False).returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--store', type=Path, required=True)
    sub = parser.add_subparsers(dest='operation', required=True)
    sub.add_parser('init')
    st = sub.add_parser('stage'); st.add_argument('--bundle', type=Path, required=True)
    st.add_argument('--expected-sha256', required=True)
    sf = sub.add_parser('stage-feature', help='Stage one module against the selected same-host bundle')
    sf.add_argument('--bundle', type=Path, required=True)
    sf.add_argument('--expected-sha256', required=True)
    sf.add_argument('--module', required=True)
    sf.add_argument('--expected-current', required=True)
    for action in ('activate', 'rollback'):
        p = sub.add_parser(action);p.add_argument('--expected-current', required=True)
        if action == 'activate':p.add_argument('--version', required=True)
    sub.add_parser('status')
    start = sub.add_parser('launch');start.add_argument('--java', default='java')
    start.add_argument('--bridge-port', type=int, default=8892)
    start.add_argument('--proxy-port', type=int, default=8889)
    a = parser.parse_args()
    if a.operation == 'init': result = initialize(a.store)
    elif a.operation == 'stage': result = stage(a.store, a.bundle, a.expected_sha256)
    elif a.operation == 'stage-feature':
        result = stage_feature(a.store, a.bundle, a.expected_sha256, a.module,
                               identifier(a.expected_current))
    elif a.operation in ('activate', 'rollback'):
        result = activate(a.store, getattr(a, 'version', ''),
                          None if a.expected_current == 'none' else identifier(a.expected_current),
                          rollback=a.operation == 'rollback')
    elif a.operation == 'status':
        with locked(a.store) as (root, _): result = selector(root)
    else: return launch(a.store, a.java, a.bridge_port, a.proxy_port)
    print(json.dumps(result, indent=2))
    return 0

if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, BadZipFile) as error:
        raise SystemExit('Managed bundle operation refused: ' + str(error))
