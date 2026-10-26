#!/usr/bin/env python3
"""Opt-in POSIX split-bundle store: retain all versions; change one selector, not JARs.

Only a newly initialized store is accepted. No import of a live installation,
network download, release publication, cleanup or automatic rollback is provided.
The selector rollback restores application files, NEVER user data/schema changes.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import uuid
from zipfile import ZipFile, BadZipFile
from run_feature_distribution import verify, FILES

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


def bundle(path: Path, expected: str):
    path = checked(path)
    expected = identifier(expected)
    mp = checked(path / 'distribution.json')
    if not mp.is_file() or mp.stat().st_size > 262144:
        raise ValueError('Missing or oversized manifest')
    raw = mp.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError('Distribution identity mismatch')
    report = read_json(mp)
    if (not isinstance(report, dict) or not isinstance(report.get('files'), dict)
            or set(report['files']) != set(FILES)
            or not isinstance(report.get('source_revision'), str)
            or re.fullmatch('[0-9a-f]{40}', report['source_revision']) is None):
        raise ValueError('Invalid bundle inventory or source revision')
    total = 0
    for name in FILES:
        identifier(report['files'][name])
        p = checked(path / name)
        if not p.is_file():
            raise ValueError('Missing bundle component')
        total += p.stat().st_size
        if p.stat().st_size > 192*1024*1024 or total > 256*1024*1024:
            raise ValueError('Bundle size limit exceeded')
    if report != verify(path) or mp.read_bytes() != raw:
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


def selector(root):
    p = root / 'current.json'
    if not p.exists() and not p.is_symlink():
        return None
    value = read_json(p)
    if not isinstance(value, dict) or set(value) != {'format', 'sequence', 'current', 'previous'} or value['format'] != FORMAT:
        raise ValueError('Invalid current selector')
    identifier(value['current'])
    if value['previous'] is not None:
        identifier(value['previous'])
    if type(value['sequence']) is not int or value['sequence'] < 1:
        raise ValueError('Invalid selector sequence')
    return value


def stage(root: Path, source: Path, expected: str):
    source = checked(source)
    with locked(root) as (root, _):
        if source.is_relative_to(root) or root.is_relative_to(source):
            raise ValueError('Source and managed store must be disjoint')
        raw, report = bundle(source, expected)
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


def launch(root: Path, java: str = 'java') -> int:
    with locked(root) as (root, fd):
        current = selector(root)
        if current is None:
            raise ValueError('Select a staged version before launch')
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
        command = [executable, '-Dlogbook.runtime.managedBundle=true',
                   '-Dlogbook.data.autoUpdate=false', '-Duser.home=' + str(runtime / 'home'),
                   '-cp', os.pathsep.join(str(version / n) for n in JARS),
                   'logbook.internal.Launcher']
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
    for action in ('activate', 'rollback'):
        p = sub.add_parser(action);p.add_argument('--expected-current', required=True)
        if action == 'activate':p.add_argument('--version', required=True)
    sub.add_parser('status')
    start = sub.add_parser('launch');start.add_argument('--java', default='java')
    a = parser.parse_args()
    if a.operation == 'init': result = initialize(a.store)
    elif a.operation == 'stage': result = stage(a.store, a.bundle, a.expected_sha256)
    elif a.operation in ('activate', 'rollback'):
        result = activate(a.store, getattr(a, 'version', ''),
                          None if a.expected_current == 'none' else identifier(a.expected_current),
                          rollback=a.operation == 'rollback')
    elif a.operation == 'status':
        with locked(a.store) as (root, _): result = selector(root)
    else: return launch(a.store, a.java)
    print(json.dumps(result, indent=2))
    return 0

if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, BadZipFile) as error:
        raise SystemExit('Managed bundle operation refused: ' + str(error))
