#!/usr/bin/env python3
"""Offline POSIX runtime snapshot/restore into NEW disjoint directories only.

No launch, deletion, overwrite, link traversal, schema conversion or path rewriting.
The manifest proves local byte equality, NOT authenticity or whole-machine coverage.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import re
import shutil
import stat
import sys

FORMAT = 'logbook-runtime-snapshot-v1'
MAX_MANIFEST = 32 * 1024 * 1024
CHUNK = 1024 * 1024
PATH_FIELDS = ('reportPath', 'performanceDir', 'pluginsDir', 'resourcesDir', 'battleLogDir',
               'captureDir', 'storeApiStart2Dir', 'missionSoundDir', 'ndockSoundDir',
               'alertSoundDir', 'defaultNotifySound', 'ffmpegPath')
APP_CONFIG = 'config/logbook.bean.AppConfig.json'


@dataclass(frozen=True)
class Limits:
    entries: int = 200000
    bytes: int = 8 * 1024**3
    depth: int = 64
    reserve: int = 64 * 1024**2

    def __post_init__(self):
        if any(type(x) is not int or x < 0 for x in (self.entries, self.bytes, self.depth, self.reserve)):
            raise ValueError('Limits must be nonnegative integers')


def checked(path: Path) -> Path:
    path = Path(path)
    if '..' in path.parts:
        raise ValueError('Parent traversal is not a snapshot boundary')
    # POSIX reserves // for implementation-defined roots; pathlib preserves the
    # distinct lexical anchor while Linux aliases it to /. Accepting both lets
    # is_relative_to() miss a destination physically inside the original tree.
    if path.anchor == '//':
        raise ValueError('Ambiguous double-root paths are not supported')
    path = path.absolute()
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError('Symbolic paths are not supported')
    return path


def identity(s):
    return (s.st_dev, s.st_ino, s.st_mode, s.st_size, s.st_mtime_ns, s.st_ctime_ns)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True, indent=2) + '\n').encode()


def unique(pairs):
    value = {}
    for key, entry in pairs:
        if key in value:
            raise ValueError('Duplicate manifest key')
        value[key] = entry
    return value


def read_bytes(path, limit):
    path = checked(path)
    with opened(path) as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        raise ValueError('Metadata exceeds size limit')
    return value


@contextmanager
def opened(path):
    before = checked(path).lstat()
    if not stat.S_ISREG(before.st_mode):
        raise ValueError('Only regular files can be read')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        if identity(before) != identity(os.fstat(fd)):
            raise ValueError('File changed before read')
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            yield stream
        if identity(before) != identity(os.fstat(fd)) or identity(before) != identity(checked(path).lstat()):
            raise ValueError('File changed during read')
    finally:
        os.close(fd)


def structure(root, limits):
    root = checked(root)
    base = root.lstat()
    if not stat.S_ISDIR(base.st_mode):
        raise ValueError('Source must be a directory')
    found = {'.': ('directory', identity(base))}
    stack = [(root, 0)]
    total = 0
    while stack:
        parent, depth = stack.pop()
        if depth > limits.depth:
            raise ValueError('Directory depth limit exceeded')
        with os.scandir(checked(parent)) as iterator:
            for entry in iterator:
                if len(found) - 1 >= limits.entries:
                    raise ValueError('Entry limit exceeded')
                p = Path(entry.path)
                s = entry.stat(follow_symlinks=False)
                if s.st_dev != base.st_dev:
                    raise ValueError('Mounted subtrees need a separate snapshot')
                if stat.S_ISDIR(s.st_mode):
                    kind = 'directory'
                    stack.append((p, depth + 1))
                elif stat.S_ISREG(s.st_mode):
                    kind = 'file'
                    total += s.st_size
                    if total > limits.bytes:
                        raise ValueError('Byte limit exceeded')
                else:
                    raise ValueError('Symlink, socket, FIFO or device is not copied')
                found[p.relative_to(root).as_posix()] = (kind, identity(s))
    return found


def inventory(root, limits=Limits()):
    root = checked(root)
    before = structure(root, limits)
    entries = {}
    for name, (kind, stamp) in sorted(before.items()):
        mode, length, mtime = stamp[2], stamp[3], stamp[4]
        row = {'kind': kind, 'mode': stat.S_IMODE(mode) & 0o777, 'mtime_ns': mtime}
        if kind == 'file':
            h = hashlib.sha256()
            count = 0
            with opened(root / name) as stream:
                for chunk in iter(lambda: stream.read(CHUNK), b''):
                    count += len(chunk)
                    if count > length:
                        raise ValueError('Source grew during hashing')
                    h.update(chunk)
            if count != length:
                raise ValueError('Source length changed')
            row.update(bytes=count, sha256=h.hexdigest())
        entries[name] = row
    if before != structure(root, limits):
        raise ValueError('Source tree changed during inventory')
    return entries


def _inside_kind(root, relative):
    """lstat only from the root outward, stopping BEFORE any symbolic ancestor.

    Calling Path.is_symlink() on a leaf first would traverse a symbolic parent.
    Missing paths are reported without looking up any descendants.
    """
    cursor = root
    mode = root.lstat().st_mode
    for part in Path(relative).parts:
        cursor = cursor / part
        try:
            mode = cursor.lstat().st_mode
        except FileNotFoundError:
            return 'missing'
        except OSError:
            return 'unreadable'
        if stat.S_ISLNK(mode):
            return 'symbolic'
        if not stat.S_ISREG(mode) and not stat.S_ISDIR(mode):
            return 'special'
    return 'directory' if stat.S_ISDIR(mode) else 'file'


def _path_references(root, data):
    references = []
    for key in PATH_FIELDS:
        raw = data.get(key)
        if raw is None or raw == '':
            continue
        if not isinstance(raw, str) or any(ord(char) < 32 for char in raw):
            references.append({'field': key, 'status': 'invalid_path_value'})
            continue
        win = PureWindowsPath(raw)
        kind = None
        if win.drive or '\\' in raw:
            status = 'other_platform_or_ambiguous'
        elif '..' in Path(raw).parts:
            status = 'parent_traversal_needs_review'
        else:
            target = Path(os.path.abspath(root / raw))
            if not target.is_relative_to(root):
                # Do not stat, resolve, read or check ancestors of an external target.
                status = 'outside_root_not_copied'
            else:
                kind = _inside_kind(root, target.relative_to(root))
                if kind in ('symbolic', 'special', 'unreadable'):
                    status = kind + '_reference_unreviewed'
                elif Path(raw).is_absolute():
                    status = 'absolute_reference_to_original_needs_review'
                else:
                    status = 'relative_within_root'
        row = {'field': key, 'value': raw, 'status': status}
        if kind is not None:
            row['target_kind'] = kind
        references.append(row)
    return references


def read_app_config(root):
    """Read the primary and backup without writing either; return data, review.

    Missing, empty or invalid primary JSON selects the backup, as Config.read()
    does. A JSON null selects defaults rather than the backup. This structural
    reader does NOT certify Jackson bean binding: both candidates' references
    remain visible and a typed Java validation is still required for migration.
    """
    root = checked(Path(root))
    candidates, parsed = [], {}
    for relative in (APP_CONFIG, APP_CONFIG + '.backup'):
        kind = _inside_kind(root, relative)
        row = {'path': relative, 'status': kind, 'references': []}
        if kind == 'symbolic':
            row['status'] = 'symbolic_config_unreviewed'
        elif kind == 'file':
            try:
                raw = read_bytes(root / relative, 2 * 1024**2)
                row['sha256'] = digest(raw)
                if not raw.strip():
                    row['status'] = 'empty'
                else:
                    try:
                        def reject_constant(value):
                            raise ValueError('Non-finite JSON constant')
                        data = json.loads(raw, object_pairs_hook=unique,
                                          parse_constant=reject_constant)
                        if data is None:
                            row['status'] = 'null_config'
                            parsed[relative] = None
                        elif isinstance(data, dict):
                            row['status'] = 'parsed'
                            row['references'] = _path_references(root, data)
                            parsed[relative] = data
                        else:
                            row['status'] = 'invalid_json'
                    except (ValueError, UnicodeError, RecursionError):
                        row['status'] = 'invalid_json'
            except (OSError, ValueError):
                row['status'] = 'unreadable'
        candidates.append(row)
    selected = next((row['path'] for row in candidates if row['path'] in parsed), None)
    data = parsed.get(selected)
    review = {
        'config_present': candidates[0]['status'] != 'missing',
        'backup_present': candidates[1]['status'] != 'missing',
        'config_source': selected,
        'config_fallback': selected == APP_CONFIG + '.backup',
        'config_candidates': candidates,
        'config_errors': [{'path': row['path'], 'status': row['status']} for row in candidates
                          if row['status'] not in ('missing', 'parsed')],
        'typed_config_validation_required': True,
        'defaulted_fields': [field for field in PATH_FIELDS if data is None or field not in data],
    }
    if data is None and any(row['status'] != 'missing' for row in candidates):
        review['config_unreadable'] = True
    return data, review


def path_review(root):
    """Report known primary AND fallback paths; never access external targets."""
    _, result = read_app_config(root)
    selected = next((row for row in result['config_candidates']
                     if row['path'] == result['config_source']), None)
    backup = result['config_candidates'][1]
    result.update({
        'references': selected['references'] if selected else [],
        'backup_references': backup['references'],
        'third_party_paths_audited': False,
        'unreviewed_scopes': ['plugin_configuration', 'startup_scripts'],
        'launch_safe': False,
        'note': 'Known AppConfig paths only; both primary and fallback need copy-side review. Plugin configuration and startup scripts remain unreviewed. No paths were rewritten.',
    })
    return result


@contextmanager
def managed_lease(root):
    """Recognize an existing managed store/root.runtime; never create a lock in the source."""
    import fcntl
    store = root if (root / 'store.json').exists() else root.parent if root.name == 'runtime' and (root.parent / 'store.json').exists() else None
    if store is None:
        yield
        return
    metadata = json.loads(read_bytes(store / 'store.json', 262144), object_pairs_hook=unique)
    if metadata != {'format': 'logbook-managed-split-v1', 'state_rollback': False}:
        raise ValueError('Unrecognized managed store')
    lock = checked(store / 'store.lock')
    if not stat.S_ISREG(lock.lstat().st_mode):
        raise ValueError('Invalid existing store lock')
    fd = os.open(lock, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError('Managed application is running; no snapshot made') from error
        if (os.fstat(fd).st_dev, os.fstat(fd).st_ino) != (lock.stat().st_dev, lock.stat().st_ino):
            raise ValueError('Managed store lock changed')
        yield
    finally:
        os.close(fd)


def new_output(source, output, required, limits):
    source, output = checked(source), checked(output)
    if output.is_relative_to(source) or source.is_relative_to(output):
        raise ValueError('Source and output must be disjoint')
    if os.path.lexists(output):
        raise FileExistsError('Output already exists; use a new name')
    if not output.parent.is_dir():
        raise ValueError('Output parent must already exist')
    if shutil.disk_usage(output.parent).free < required + limits.reserve:
        raise ValueError('Insufficient free space with safety reserve')
    output.mkdir(mode=0o700, exist_ok=False)
    (output / 'data').mkdir(mode=0o700)
    return output


def write_new(path, raw):
    with checked(path).open('xb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def copy_entries(source, destination, entries):
    for name, row in sorted(entries.items(), key=lambda item: (item[0].count('/'), item[0])):
        if name == '.':
            continue
        target = destination / name
        if row['kind'] == 'directory':
            checked(target).mkdir(mode=0o700)
        else:
            h, size = hashlib.sha256(), 0
            with opened(source / name) as src, checked(target).open('xb') as dst:
                for block in iter(lambda: src.read(CHUNK), b''):
                    size += len(block)
                    if size > row['bytes']:
                        raise ValueError('Source grew during copy')
                    dst.write(block)
                    h.update(block)
                dst.flush()
                os.fsync(dst.fileno())
            if size != row['bytes'] or h.hexdigest() != row['sha256']:
                raise ValueError('Copied bytes do not match source inventory')
            os.chmod(target, row['mode'])
            os.utime(target, ns=(row['mtime_ns'], row['mtime_ns']))
    # Hardlinks become independent files. This keeps .pending and published arrival bytes without aliasing the source.
    for name, row in sorted(entries.items(), key=lambda item: item[0].count('/'), reverse=True):
        if row['kind'] == 'directory':
            target = destination if name == '.' else destination / name
            os.chmod(target, row['mode'])
            os.utime(target, ns=(row['mtime_ns'], row['mtime_ns']))


def snapshot(source, output, *, stopped=False, limits=Limits()):
    if stopped is not True:
        raise ValueError('Explicit stopped-application confirmation required')
    source = checked(source)
    with managed_lease(source):
        entries = inventory(source, limits)
        review = path_review(source)
        required = sum(row.get('bytes', 0) for row in entries.values())
        output = new_output(source, output, required, limits)
        write_new(output / 'intent.json', encoded({'operation': 'snapshot', 'format': FORMAT, 'source': str(source)}))
        copy_entries(source, output / 'data', entries)
        if inventory(source, limits) != entries or inventory(output / 'data', limits) != entries:
            raise ValueError('Source changed or copied inventory differs; incomplete output retained')
        report = {'format': FORMAT, 'source': str(source), 'created_at': datetime.now(timezone.utc).isoformat(),
                  'entries': entries, 'path_review': review, 'scope': 'selected-root-bytes-only',
                  'application_stopped_confirmed_by_caller': True, 'launch_safe': False,
                  'schema_converted': False, 'source_bytes_written': False,
                  'metadata_limits': 'POSIX mode and mtime only; no ACL, xattr, owner, sparse layout or hardlink topology preservation'}
        raw = encoded(report)
        if len(raw) > MAX_MANIFEST:
            raise ValueError('Manifest too large; incomplete output retained')
        # Completion marker is last. Incomplete output is retained, never resumed or removed automatically.
        write_new(output / 'snapshot.json', raw)
        return {'snapshot': str(output), 'manifest_sha256': digest(raw), 'files': sum(e['kind']=='file' for e in entries.values()),
                'bytes': required, 'launch_safe': False, 'path_review': review}


def verify(snapshot_root, expected_sha256, limits=Limits()):
    root = checked(snapshot_root)
    if not isinstance(expected_sha256, str) or re.fullmatch('[0-9a-f]{64}', expected_sha256) is None:
        raise ValueError('Expected manifest SHA256 must come from the saved creation receipt')
    if {p.name for p in root.iterdir()} != {'data', 'intent.json', 'snapshot.json'}:
        raise ValueError('Snapshot incomplete or contains unexpected entries')
    raw = read_bytes(root / 'snapshot.json', MAX_MANIFEST)
    if digest(raw) != expected_sha256:
        raise ValueError('Manifest checksum mismatch')
    report = json.loads(raw, object_pairs_hook=unique)
    if not isinstance(report, dict) or report.get('format') != FORMAT or not isinstance(report.get('entries'), dict):
        raise ValueError('Invalid snapshot manifest')
    intent = json.loads(read_bytes(root / 'intent.json', 16384), object_pairs_hook=unique)
    if intent != {'operation': 'snapshot', 'format': FORMAT, 'source': report.get('source')}:
        raise ValueError('Snapshot intent mismatch')
    if inventory(root / 'data', limits) != report['entries']:
        raise ValueError('Snapshot bytes/paths/metadata changed')
    # JSON paths are never used for writes until exact equality with the safe, walked disk inventory.
    return report


def restore(snapshot_root, output, expected_sha256, *, limits=Limits()):
    source = checked(snapshot_root)
    report = verify(source, expected_sha256, limits)
    entries = report['entries']
    required = sum(row.get('bytes', 0) for row in entries.values())
    output = checked(output)
    # Also forbid a restore INTO the original tree retained in the hash-pinned manifest.
    original = checked(Path(report['source']))
    if output.is_relative_to(original) or original.is_relative_to(output):
        raise ValueError('Restore must not overlap the original source')
    output = new_output(source, output, required, limits)
    write_new(output / 'intent.json', encoded({'operation': 'restore-copy', 'snapshot_manifest_sha256': expected_sha256}))
    copy_entries(source / 'data', output / 'data', entries)
    verify(source, expected_sha256, limits)
    if inventory(output / 'data', limits) != entries:
        raise ValueError('Restored copy differs; incomplete output retained')
    receipt = {'format': 'logbook-runtime-restore-v1', 'snapshot_manifest_sha256': expected_sha256,
               'bytes_verified': True, 'restored_to': str(output / 'data'), 'launch_safe': False,
               'original_replaced': False, 'schema_converted': False, 'path_review': report['path_review']}
    write_new(output / 'restore.json', encoded(receipt))
    return receipt


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation', choices=('inventory', 'snapshot', 'verify', 'restore'))
    p.add_argument('--source', type=Path, required=True)
    p.add_argument('--out', type=Path)
    p.add_argument('--confirm-stopped', action='store_true')
    p.add_argument('--manifest-sha256')
    p.add_argument('--max-entries', type=int, default=Limits.entries)
    p.add_argument('--max-bytes', type=int, default=Limits.bytes)
    a = p.parse_args()
    try:
        limits = Limits(entries=a.max_entries, bytes=a.max_bytes)
        if a.operation == 'inventory':
            root = checked(a.source)
            result = {'entries': inventory(root, limits), 'path_review': path_review(root), 'source_bytes_written': False}
        elif a.operation == 'snapshot':
            if a.out is None: raise ValueError('--out is required')
            result = snapshot(a.source, a.out, stopped=a.confirm_stopped, limits=limits)
        elif a.operation == 'verify':
            result = verify(a.source, a.manifest_sha256, limits)
        else:
            if a.out is None: raise ValueError('--out is required')
            result = restore(a.source, a.out, a.manifest_sha256, limits=limits)
        print(json.dumps(result, ensure_ascii=True, sort_keys=True, indent=2))
        return 0
    except (OSError, ValueError, TypeError, KeyError, RecursionError) as error:
        print('Runtime copy refused: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
