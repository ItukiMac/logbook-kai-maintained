#!/usr/bin/env python3
"""Checkpoint tracked development inputs into a NEW directory and resume in a NEW checkout.

No production runtime, ignored files, credentials, environment dump, network, provider,
release or activation. Hashes establish byte identity, not publisher authorization.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import platform
import tempfile
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo

FORMAT = 'logbook-maintenance-workbench-v1'
MAX_FILES = 10000
MAX_FILE = 32 * 1024 * 1024
MAX_TOTAL = 256 * 1024 * 1024
MAX_MANIFEST = 4 * 1024 * 1024
HEX = re.compile(r'[0-9a-f]{64}')
EXTRA_PREFIXES = ('src/', 'scripts/', 'tools/', 'docs/', '.github/')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate manifest key')
        result[key] = value
    return result


def checked_path(path):
    path = Path(path)
    if '..' in path.parts:
        raise ValueError('Parent traversal is not accepted')
    path = path.absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Symlinked path is not accepted')
    return path


def relative(name):
    p = PurePosixPath(name)
    if (not isinstance(name, str) or not name or p.is_absolute() or '..' in p.parts
            or any(ord(c) < 32 for c in name) or '\\' in name or ':' in name or p.as_posix() != name or '.git' in p.parts):
        raise ValueError('Unsafe source entry')
    return name


def git(root, *args, input=None):
    # Ignore ambient user/system hooks, signing and credential configuration.
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
               GIT_TERMINAL_PROMPT='0')
    return subprocess.check_output(['git', '-c', 'core.hooksPath=' + os.devnull,
        '-c', 'commit.gpgSign=false', '-C', str(root), *args], env=env, stderr=subprocess.PIPE, input=input)


def tracked(root):
    rows = {}
    for entry in git(root, 'ls-files', '--stage', '-z').split(b'\0'):
        if not entry:
            continue
        info, raw_name = entry.split(b'\t', 1)
        mode, _, stage = info.decode().split()
        name = relative(raw_name.decode('utf8'))
        if stage != '0' or mode not in ('100644', '100755') or name in rows:
            raise ValueError('Unmerged, linked or non-regular tracked input')
        rows[name] = mode
    if not rows or len(rows) > MAX_FILES:
        raise ValueError('Empty or oversized source scope')
    return rows


def bounded(path, limit):
    path = checked_path(path)
    if not path.is_file() or path.stat().st_size > limit:
        raise ValueError('Missing or oversized input')
    with path.open('rb') as stream:
        raw = stream.read(limit + 1)
    if len(raw) > limit:
        raise ValueError('Input grew beyond limit')
    return raw


def read_source(root, name):
    return bounded(root / relative(name), MAX_FILE)


def disjoint_new(output, protected):
    out = checked_path(output)
    for root in protected:
        root = checked_path(root)
        if out == root or out in root.parents or root in out.parents:
            raise ValueError('Output overlaps protected inputs')
    if out.exists() or not out.parent.is_dir():
        raise ValueError('A new directory with an existing parent is required')
    return out


def write_new(path, raw):
    with path.open('xb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


TOOLS = {'python', 'java', 'maven', 'git'}


def validate_toolchain(value):
    if not isinstance(value, dict) or set(value) != TOOLS:
        raise ValueError('Exact Python/Java/Maven/Git version declarations required')
    for version in value.values():
        if not isinstance(version, str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+){0,3}(?:[+_-][A-Za-z0-9.-]+)?', version):
            raise ValueError('Invalid tool version declaration')


def measure_environment():
    observed = {'python': platform.python_version()}
    commands = {'git': (['git', '--version'], r'git version ([^\s]+)'),
        'java': (['java', '-version'], r'version "([^"]+)"'),
        'maven': (['mvn', '--version'], r'Apache Maven ([^\s]+)')}
    env = {k: v for k, v in os.environ.items() if k in
        ('PATH', 'JAVA_HOME', 'SYSTEMROOT', 'WINDIR', 'LD_LIBRARY_PATH')}
    env.update(LC_ALL='C', LANG='C')
    with tempfile.TemporaryDirectory(prefix='maintenance-tool-probe-') as home:
        env['HOME'] = home
        for name, (command, pattern) in commands.items():
            run = subprocess.run(command, env=env, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, text=True, timeout=20, check=True)
            output = re.sub(r'\x1b\[[0-9;]*m', '', run.stdout)
            match = re.search(pattern, output)
            if not match:
                raise ValueError('Cannot identify installed ' + name)
            observed[name] = match.group(1)
    validate_toolchain(observed)
    return observed


def check_environment(report, observed):
    required = report['toolchain']
    validate_toolchain(required)
    validate_toolchain(observed)
    mismatch = {name: {'required': required[name], 'observed': observed[name]}
                for name in TOOLS if required[name] != observed[name]}
    if mismatch:
        raise ValueError('Toolchain mismatch: ' + json.dumps(mismatch, sort_keys=True))
    return {'compatible': True, 'observed': observed, 'project_code_executed': False}


def checkpoint(root, output, *, progress, toolchain, include_new=()):
    root = checked_path(root)
    out = disjoint_new(output, [root])
    if not isinstance(progress, dict) or not isinstance(toolchain, dict) or not toolchain:
        raise ValueError('Explicit progress and toolchain declarations required')
    # JSON only; no pickles, shell commands, process environment or token lookup.
    json.dumps(progress, allow_nan=False)
    validate_toolchain(toolchain)
    original = tracked(root)
    paths = dict(original)
    for name in include_new:
        relative(name)
        if not name.startswith(EXTRA_PREFIXES) or name in paths:
            raise ValueError('New inputs must be explicit source paths')
        candidate = checked_path(root / name)
        paths[name] = '100755' if candidate.stat().st_mode & 0o111 else '100644'
    if len(paths) > MAX_FILES:
        raise ValueError('Too many source inputs')
    revision = git(root, 'rev-parse', 'HEAD').decode().strip()
    rows = []
    total = 0
    for name, mode in sorted(paths.items()):
        raw = read_source(root, name)
        mode = '100755' if checked_path(root / name).stat().st_mode & 0o111 else '100644'
        total += len(raw)
        if total > MAX_TOTAL:
            raise ValueError('Source budget exceeded')
        rows.append({'path': name, 'mode': mode, 'size': len(raw), 'sha256': digest(raw)})
    out.mkdir()
    with ZipFile(out / 'source.zip', 'x', compression=ZIP_DEFLATED) as archive:
        for row in rows:
            raw = read_source(root, row['path'])
            if len(raw) != row['size'] or digest(raw) != row['sha256']:
                raise ValueError('Source changed during checkpoint; incomplete output retained')
            info = ZipInfo(row['path'], (2020, 1, 1, 0, 0, 0))
            info.external_attr = int(row['mode'], 8) << 16
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, raw)
    # A success marker cannot hide membership or byte changes during capture.
    if tracked(root) != original or git(root, 'rev-parse', 'HEAD').decode().strip() != revision:
        raise ValueError('Source revision or index changed; incomplete output retained')
    for row in rows:
        if (digest(read_source(root, row['path'])) != row['sha256']
                or ('100755' if checked_path(root / row['path']).stat().st_mode & 0o111 else '100644') != row['mode']):
            raise ValueError('Source changed; incomplete output retained')
    report = {'format': FORMAT, 'origin_revision': revision,
        'created_at': datetime.now(timezone.utc).isoformat(), 'files': rows,
        'captured_uncommitted_changes': bool(include_new or git(root, 'diff', '--name-only', 'HEAD', '--').strip()),
        'archive_sha256': digest(bounded(out / 'source.zip', MAX_TOTAL)),
        'progress': progress, 'toolchain': toolchain,
        'scope': 'tracked source plus explicitly named new source files; no ignored runtime data',
        'paid_provider_calls': 0, 'production_accessed': False, 'publication_allowed': False}
    raw = (json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2, allow_nan=False) + '\n').encode()
    if len(raw) > MAX_MANIFEST:
        raise ValueError('Manifest too large; incomplete output retained')
    write_new(out / 'workbench.json', raw)
    return {'checkpoint_sha256': digest(raw), 'files': len(rows), 'origin_revision': revision,
            'source_unchanged': True, 'output': str(out), 'production_accessed': False}


def verify(root, expected):
    root = checked_path(root)
    if not isinstance(expected, str) or HEX.fullmatch(expected) is None:
        raise ValueError('Pinned checkpoint SHA256 required')
    manifest = checked_path(root / 'workbench.json')
    raw = bounded(manifest, MAX_MANIFEST)
    if digest(raw) != expected:
        raise ValueError('Checkpoint manifest mismatch')
    report = json.loads(raw, object_pairs_hook=unique,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Non-finite JSON')))
    if not isinstance(report, dict) or report.get('format') != FORMAT or not re.fullmatch(r'[0-9a-f]{40}', report.get('origin_revision', '')):
        raise ValueError('Unsupported checkpoint')
    if (type(report.get('paid_provider_calls')) is not int or report.get('paid_provider_calls') != 0 or report.get('production_accessed') is not False
            or report.get('publication_allowed') is not False):
        raise ValueError('Unsupported checkpoint permissions')
    if not isinstance(report.get('progress'), dict) or not isinstance(report.get('toolchain'), dict) or not report['toolchain']:
        raise ValueError('Missing checkpoint declarations')
    validate_toolchain(report['toolchain'])
    rows = report.get('files')
    if not isinstance(rows, list) or not 0 < len(rows) <= MAX_FILES:
        raise ValueError('Invalid source inventory')
    names = set()
    total = 0
    for row in rows:
        name = relative(row['path'])
        if (name in names or row.get('mode') not in ('100644', '100755')
                or type(row.get('size')) is not int or not 0 <= row['size'] <= MAX_FILE
                or not isinstance(row.get('sha256'), str) or HEX.fullmatch(row['sha256']) is None):
            raise ValueError('Invalid source entry metadata')
        names.add(name)
        total += row['size']
    if total > MAX_TOTAL:
        raise ValueError('Expanded source budget exceeded')
    path = checked_path(root / 'source.zip')
    archive_bytes = bounded(path, MAX_TOTAL)
    if digest(archive_bytes) != report['archive_sha256']:
        raise ValueError('Source archive mismatch')
    with ZipFile(io.BytesIO(archive_bytes)) as archive:
        infos = archive.infolist()
        if len(infos) != len(names) or {i.filename for i in infos} != names:
            raise ValueError('Duplicate or unlisted source entry')
        for row in rows:
            info = archive.getinfo(row['path'])
            if info.file_size != row['size'] or (info.external_attr >> 16) != int(row['mode'], 8):
                raise ValueError('Source size/mode mismatch')
            if digest(archive.read(info)) != row['sha256']:
                raise ValueError('Source content mismatch')
    return report


def restore(root, expected, output, *, protected=(), initialize_git=False):
    root = checked_path(root)
    out = disjoint_new(output, [root, *protected])
    report = verify(root, expected)  # validate every byte before materializing anything
    out.mkdir()
    archive_bytes = bounded(root / 'source.zip', MAX_TOTAL)
    if digest(archive_bytes) != report['archive_sha256']:
        raise ValueError('Source archive changed before restoration; empty output retained')
    with ZipFile(io.BytesIO(archive_bytes)) as archive:
        for row in report['files']:
            path = out / row['path']
            path.parent.mkdir(parents=True, exist_ok=True)
            write_new(path, archive.read(row['path']))
            path.chmod(int(row['mode'], 8) & 0o777)
    for row in report['files']:
        if digest(read_source(out, row['path'])) != row['sha256']:
            raise ValueError('Restored content changed; partial checkout retained')
    if initialize_git:
        git(out, '-c', 'init.templateDir=', 'init', '-q')
        git(out, 'add', '--force', '--all')
        git(out, '-c', 'user.name=Maintenance Workbench', '-c', 'user.email=workbench@localhost',
            'commit', '-qm', 'Restore source checkpoint ' + report['origin_revision'])
        if tracked(out) != {row['path']: row['mode'] for row in report['files']}:
            raise ValueError('New Git index does not preserve captured source scope/modes')
    return {'restored_to': str(out), 'files': len(report['files']),
        'origin_revision': report['origin_revision'], 'origin_history_copied': False,
        'initialized_new_git_history': initialize_git, 'source_unchanged': True,
        'toolchain': report['toolchain'], 'progress': report['progress'],
        'execution_performed': False, 'production_accessed': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    capture = sub.add_parser('checkpoint')
    capture.add_argument('--root', type=Path, required=True)
    capture.add_argument('--out', type=Path, required=True)
    capture.add_argument('--declarations', type=Path, required=True)
    capture.add_argument('--include-new', action='append', default=[])
    sub.add_parser('measure-tools')
    for name in ('verify', 'restore', 'preflight'):
        operation = sub.add_parser(name)
        operation.add_argument('--checkpoint', type=Path, required=True)
        operation.add_argument('--sha256', required=True)
        if name == 'restore':
            operation.add_argument('--out', type=Path, required=True)
            operation.add_argument('--protect', type=Path, action='append', default=[])
            operation.add_argument('--initialize-git', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'measure-tools':
            result = measure_environment()
        elif args.command == 'checkpoint':
            data = json.loads(args.declarations.read_bytes(), object_pairs_hook=unique)
            result = checkpoint(args.root, args.out, progress=data['progress'],
                toolchain=data['toolchain'], include_new=args.include_new)
        elif args.command == 'preflight':
            result = check_environment(verify(args.checkpoint, args.sha256), measure_environment())
        elif args.command == 'verify':
            data = verify(args.checkpoint, args.sha256)
            result = {'verified': True, 'files': len(data['files']), 'execution_performed': False}
        else:
            result = restore(args.checkpoint, args.sha256, args.out,
                protected=args.protect, initialize_git=args.initialize_git)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
        print(json.dumps({'completed': False, 'error_type': type(error).__name__,
                          'reason': str(error), 'partial_outputs_retained': True}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
