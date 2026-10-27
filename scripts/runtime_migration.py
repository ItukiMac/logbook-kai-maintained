#!/usr/bin/env python3
"""Plan -> explicit copy -> explicit approved validation launch, never deploy.

POSIX copy migration of the current AppConfig path contract. Inputs are pinned
snapshots and ONLY caller-selected external paths. Every output is new; original
configuration bytes are archived. Unknown plugin/script paths block approval
unless the caller supplies a content-bound audit. Linux validation watches detect
writes to protected inputs, including write-then-restore. This is an audit, NOT an
OS sandbox for hostile programs. No arbitrary-version schema conversion is made.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path, PureWindowsPath
import re
import shutil
import signal
import stat
import struct
import subprocess
import sys
import uuid

import runtime_snapshot as snap
from migration_preflight import preflight
from runtime_managed_import import validate_app_config

FORMAT = 'logbook-runtime-migration-v1'
PLAN_FORMAT = 'logbook-runtime-migration-plan-v1'
APPROVAL_FORMAT = 'logbook-runtime-validation-approval-v1'
EXTERNAL = '.migration-external'
CONFIGS = (snap.APP_CONFIG, snap.APP_CONFIG + '.backup')
FILE_FIELDS = frozenset(('defaultNotifySound', 'ffmpegPath'))
# AppConfig.java defaults, with reportPath expressed relative to the runtime cwd.
DEFAULTS = dict(reportPath='.', performanceDir='performancelog', pluginsDir='./plugins/',
                resourcesDir='./resources/', battleLogDir='./battlelog/', captureDir=None,
                storeApiStart2Dir='', missionSoundDir='./sounds/mission/',
                ndockSoundDir='./sounds/ndock/', alertSoundDir='./sounds/alert/',
                defaultNotifySound=r'C:\Windows\media\Windows Notify System Generic.wav',
                ffmpegPath=None)
SCRIPT_SUFFIXES = frozenset(('.sh', '.bash', '.zsh', '.fish', '.ksh', '.bat', '.cmd', '.ps1',
                             '.command', '.desktop', '.py', '.pyw', '.rb', '.pl', '.vbs', '.js', '.lua'))


def _json(path, limit=snap.MAX_MANIFEST):
    def reject_constant(value):
        raise ValueError('Non-finite JSON value')
    return json.loads(snap.read_bytes(path, limit), object_pairs_hook=snap.unique,
                      parse_constant=reject_constant)


def _hash(value):
    return snap.digest(snap.encoded(value))


def _disjoint(left, right):
    if left.is_relative_to(right) or right.is_relative_to(left):
        raise ValueError('Input and output must be disjoint: ' + str(left))


def _inventory_path(path, limits):
    """Hash a selected file or directory without enumerating a file's siblings."""
    path = snap.checked(Path(path))
    before = path.lstat()
    if stat.S_ISDIR(before.st_mode):
        return snap.inventory(path, limits)
    if not stat.S_ISREG(before.st_mode):
        raise ValueError('Selected input must be a regular file or directory')
    if before.st_size > limits.bytes or limits.entries < 1:
        raise ValueError('Selected file exceeds limits')
    h = hashlib.sha256()
    size = 0
    with snap.opened(path) as stream:
        for block in iter(lambda: stream.read(snap.CHUNK), b''):
            size += len(block)
            if size > limits.bytes:
                raise ValueError('Selected file grew beyond limits')
            h.update(block)
    if snap.identity(before) != snap.identity(path.lstat()) or size != before.st_size:
        raise ValueError('Selected file changed during inventory')
    return {'.': dict(kind='file', mode=stat.S_IMODE(before.st_mode) & 0o777,
                      mtime_ns=before.st_mtime_ns, bytes=size, sha256=h.hexdigest())}


def _copy_input(source, destination, entries):
    """Copies one already inventoried selection; no overwrite, no link sharing."""
    source, destination = snap.checked(source), snap.checked(destination)
    if entries['.']['kind'] == 'directory':
        destination.mkdir(mode=0o700, exist_ok=False)
        snap.copy_entries(source, destination, entries)
        return
    row = entries['.']
    h, count = hashlib.sha256(), 0
    with snap.opened(source) as src, destination.open('xb') as dst:
        for chunk in iter(lambda: src.read(snap.CHUNK), b''):
            count += len(chunk)
            if count > row['bytes']:
                raise ValueError('Selected source grew during copy')
            h.update(chunk)
            dst.write(chunk)
        dst.flush()
        os.fsync(dst.fileno())
    if count != row['bytes'] or h.hexdigest() != row['sha256']:
        raise ValueError('Selected source changed during copy')
    os.chmod(destination, row['mode'])
    os.utime(destination, ns=(row['mtime_ns'], row['mtime_ns']))


def _selection_map(selected_paths):
    if selected_paths is None:
        return {}
    if not isinstance(selected_paths, dict):
        raise ValueError('selected_paths must map exact configuration values to local paths')
    result = {}
    for raw, source in selected_paths.items():
        if not isinstance(raw, str) or not raw or not isinstance(source, (str, Path)):
            raise ValueError('Invalid selected path mapping')
        result[raw] = str(snap.checked(Path(source)))
    return result


def _subtree(entries, relative):
    if relative not in entries:
        raise ValueError('Explicit selected path is absent from the pinned snapshot; create a fresh snapshot')
    if relative == '.':
        return entries
    prefix = relative + '/'
    return {'.' if name == relative else name[len(prefix):]: row
            for name, row in entries.items() if name == relative or name.startswith(prefix)}


def _artifact_review(entries, plugin_roots, reviewed, file_paths, executable_paths):
    if not isinstance(reviewed, dict):
        raise ValueError('Artifact review must be a path-to-SHA256 mapping')
    artifacts = {}
    for name, row in entries.items():
        if row['kind'] != 'file':
            continue
        path = Path(name)
        plugin = any(path.is_relative_to(Path(root)) for root in plugin_roots)
        script = path.suffix.lower() in SCRIPT_SUFFIXES or bool(row['mode'] & 0o111)
        if not script and not plugin:
            with snap.opened(file_paths[name]) as stream:
                script = stream.read(2) == b'#!'
        if plugin or script or name in executable_paths:
            artifacts[name] = row['sha256']
    for name, checksum in reviewed.items():
        if name not in artifacts or checksum != artifacts[name]:
            raise ValueError('Artifact review hash/path is stale or outside the reviewed scope')
    missing = sorted(set(artifacts) - set(reviewed))
    return artifacts, missing


def plan(snapshot_root, manifest_sha256, output, *, selected_paths=None,
         reviewed_artifacts=None, limits=snap.Limits()):
    """Read-only plan. Unselected external references are NEVER stat'ed or read.

    selected_paths keys are EXACT raw primary/backup config strings; values are
    explicitly chosen local paths, including mappings from a different OS.
    reviewed_artifacts keys are output-runtime-relative plugin/script paths.
    """
    root, output = snap.checked(Path(snapshot_root)), snap.checked(Path(output))
    manifest = snap.verify(root, manifest_sha256, limits)
    if (manifest.get('application_stopped_confirmed_by_caller') is not True
            or manifest.get('schema_converted') is not False):
        raise ValueError('Snapshot stop confirmation or known schema boundary is missing')
    # Reuse PR48's read-only contract, never reinterpret its false launch flag.
    initial = preflight(root, manifest_sha256, limits=limits)
    original = snap.checked(Path(manifest['source']))
    _disjoint(output, root)
    _disjoint(output, original)
    if os.path.lexists(output):
        raise FileExistsError('Output already exists; use a new name')
    if not output.parent.is_dir():
        raise ValueError('Output parent must already exist')
    records = manifest['entries']
    active, review = snap.read_app_config(root / 'data')
    if active is None:
        raise ValueError('No usable AppConfig; defaults/fallback must be reviewed explicitly')
    selections = _selection_map(selected_paths)
    configs = {}
    for relative in CONFIGS:
        candidate = next(x for x in review['config_candidates'] if x['path'] == relative)
        configs[relative] = _json(root / 'data' / relative) if candidate['status'] == 'parsed' else dict(active)
    references, blockers, empty_dirs, used = [], [], set(), set()
    external_paths = set()
    configuration_outputs = {name: dict(data) for name, data in configs.items()}
    for relative, data in configs.items():
        for key, value in data.items():
            if key not in snap.PATH_FIELDS and value not in (None, '', False):
                if re.search(r'(path|dir|file|command|script|args)', key, re.IGNORECASE):
                    blockers.append('unknown_configuration_path:' + relative + ':' + key)
        for field in snap.PATH_FIELDS:
            raw = data.get(field, DEFAULTS[field])
            item = dict(config_file=relative, field=field, value=raw)
            if raw is None or raw == '':
                item.update(status='empty_or_disabled', target=raw)
                configuration_outputs[relative][field] = raw
            elif not isinstance(raw, str) or any(ord(c) < 32 for c in raw):
                item['status'] = 'invalid_path_value'
                blockers.append('invalid_path:' + relative + ':' + field)
            else:
                foreign = bool(PureWindowsPath(raw).drive or '\\' in raw)
                target = None if foreign else Path(os.path.abspath(original / raw))
                if raw in selections:
                    used.add(raw)
                    selected = snap.checked(Path(selections[raw]))
                    _disjoint(output, selected)
                    _disjoint(root, selected)
                    if selected.is_relative_to(original):
                        internal = selected.relative_to(original).as_posix()
                        expected = _subtree(records, internal)
                        if _inventory_path(selected, limits) != expected:
                            raise ValueError('Explicit selected internal path changed since snapshot; create a fresh snapshot')
                        item.update(status='snapshot_internal', target=internal)
                    else:
                        item.update(status='selected_external', source=str(selected))
                        external_paths.add(selected)
                elif target is not None and target.is_relative_to(original):
                    item.update(status='snapshot_internal', target=target.relative_to(original).as_posix())
                else:
                    item['status'] = 'unselected_external_or_other_os'
                    blockers.append('unselected_path:' + relative + ':' + field)
                if item['status'] == 'snapshot_internal':
                    inside = item['target']
                    wanted = 'file' if field in FILE_FIELDS else 'directory'
                    row = records.get(inside)
                    if row is None and wanted == 'directory':
                        # Absence INSIDE the pinned snapshot is verified. Creating an
                        # empty output directory never asserts an external file absent.
                        for parent in Path(inside).parents:
                            ancestor = records.get(parent.as_posix())
                            if ancestor and ancestor['kind'] != 'directory':
                                raise ValueError('Configured directory has a file ancestor')
                        empty_dirs.add(inside)
                        item['status'] = 'new_empty_internal_directory'
                    elif row is None or row['kind'] != wanted:
                        blockers.append('missing_or_wrong_kind:' + relative + ':' + field)
                        item['status'] = 'missing_or_wrong_kind'
                    configuration_outputs[relative][field] = inside
            references.append(item)
    if set(selections) != used:
        raise ValueError('Unreferenced selected paths are not copied')
    # Include directories the plan will create, so an absent configured path
    # cannot collide with the separate external copy namespace during execution.
    internal_targets = {item['target'] for item in references if item.get('target')}
    external_prefix = EXTERNAL
    for index in range(1, 1001):
        if external_prefix not in records and not any(
                Path(target).is_relative_to(Path(external_prefix)) for target in internal_targets):
            break
        external_prefix = EXTERNAL + '-' + str(index)
    else:
        raise ValueError('No unused migration namespace within bounds')
    # Coalesce identical selections and children of an explicitly selected parent.
    groups = []
    for source in sorted(external_paths, key=lambda x: (len(x.parts), str(x))):
        if any(source.is_relative_to(Path(group['source'])) for group in groups):
            continue
        entries = _inventory_path(source, limits)
        groups.append(dict(source=str(source), target=external_prefix + '/' + str(len(groups)), entries=entries))
    combined = dict(records)
    file_paths = {name: root / 'data' / name for name, row in records.items() if row['kind'] == 'file'}
    for group in groups:
        for name, row in group['entries'].items():
            target = group['target'] if name == '.' else group['target'] + '/' + name
            combined[target] = row
            if row['kind'] == 'file':
                file_paths[target] = Path(group['source']) if name == '.' else Path(group['source']) / name
    plugin_roots = set()
    for item in references:
        if item['status'] == 'selected_external':
            source = Path(item['source'])
            group = next(g for g in groups if source.is_relative_to(Path(g['source'])))
            suffix = source.relative_to(Path(group['source'])).as_posix()
            target = group['target'] if suffix == '.' else group['target'] + '/' + suffix
            item['target'] = target
            wanted = 'file' if item['field'] in FILE_FIELDS else 'directory'
            if combined.get(target, {}).get('kind') != wanted:
                item['status'] = 'missing_or_wrong_kind'
                blockers.append('missing_or_wrong_kind:' + item['config_file'] + ':' + item['field'])
            configuration_outputs[item['config_file']][item['field']] = target
        if item['field'] == 'pluginsDir' and isinstance(item.get('target'), str) and item['target']:
            plugin_roots.add(item['target'])
    # Conventional plugin paths are unknown even if a config temporarily disables them.
    if 'plugins' in records:
        plugin_roots.add('plugins')
    reviewed = {} if reviewed_artifacts is None else dict(reviewed_artifacts)
    executable_paths = {item['target'] for item in references
                        if item['field'] == 'ffmpegPath' and item.get('target')}
    artifacts, missing = _artifact_review(combined, plugin_roots, reviewed, file_paths, executable_paths)
    blockers.extend('unreviewed_artifact:' + name for name in missing)
    total_bytes = sum(row.get('bytes', 0) for row in combined.values())
    total_entries = len(combined)
    if total_bytes > limits.bytes or total_entries > limits.entries:
        raise ValueError('Aggregate migration byte/entry limits exceeded')
    result = dict(format=PLAN_FORMAT, snapshot=str(root), snapshot_manifest_sha256=manifest_sha256,
                  original=str(original), output=str(output), runtime=str(output / 'data'),
                  selected_paths=selections, reviewed_artifacts=reviewed,
                  source_entries=records, external_inputs=groups, references=references,
                  external_prefix=external_prefix,
                  configuration_outputs=configuration_outputs, config_review=review,
                  create_empty_directories=sorted(empty_dirs), artifacts=artifacts,
                  unreviewed_artifacts=missing, blockers=sorted(set(blockers)),
                  required_payload_bytes=total_bytes,
                  preflight_blockers=initial['blockers'],
                  schema_conversion='known AppConfig path fields only; originals archived',
                  launch_safe=False, ready_for_production=False)
    if len(snap.encoded(result)) > snap.MAX_MANIFEST:
        raise ValueError('Migration plan exceeds metadata limit')
    return result


def _rebuild(plan_value, limits):
    if not isinstance(plan_value, dict) or plan_value.get('format') != PLAN_FORMAT:
        raise ValueError('Invalid migration plan')
    rebuilt = plan(plan_value['snapshot'], plan_value['snapshot_manifest_sha256'], plan_value['output'],
                   selected_paths=plan_value['selected_paths'],
                   reviewed_artifacts=plan_value['reviewed_artifacts'], limits=limits)
    if rebuilt != plan_value:
        raise ValueError('Migration plan changed or no longer matches verified inputs')
    return rebuilt


def apply(plan_value, *, expected_plan_sha256, confirm_copy=False, limits=snap.Limits()):
    if confirm_copy is not True:
        raise ValueError('Explicit confirm_copy required')
    if _hash(plan_value) != expected_plan_sha256:
        raise ValueError('Migration plan hash mismatch')
    p = _rebuild(plan_value, limits)
    root, output = Path(p['snapshot']), Path(p['output'])
    # An older checkpoint may be restored after the retained original evolved.
    # Preserve that CURRENT original, while copying the older pinned snapshot.
    # Never replace it or require destructive reversal to the old state.
    original_before = snap.inventory(Path(p['original']), limits)
    # Reserve for duplicated original configs, rewritten configs and receipts as
    # well as all selected payload, before creating the output boundary.
    metadata_bytes = len(snap.encoded(p)) * 3 + 1024 * 1024
    required = p['required_payload_bytes'] + metadata_bytes + sum(
        p['source_entries'].get(name, {}).get('bytes', 0) for name in CONFIGS)
    if shutil.disk_usage(output.parent).free < required + limits.reserve:
        raise ValueError('Insufficient free space with safety reserve')
    output.mkdir(mode=0o700, exist_ok=False)
    runtime = output / 'data'
    runtime.mkdir(mode=0o700)
    archive = output / 'original-config'
    archive.mkdir(mode=0o700)
    for relative in ('control', 'control/home', 'control/tmp', 'control/cache', 'validation-runs'):
        (output / relative).mkdir(mode=0o700)
    snap.write_new(output / 'intent.json', snap.encoded(dict(operation='copy-migration',
                   format=FORMAT, plan_sha256=expected_plan_sha256)))
    snap.write_new(output / 'plan.json', snap.encoded(p))
    # Do not overwrite even the newly copied configs: preserve original bytes in
    # a separate archive and create the rewritten primary/backup exactly once.
    copied_entries = {name: row for name, row in p['source_entries'].items() if name not in CONFIGS}
    snap.copy_entries(root / 'data', runtime, copied_entries)
    for relative in CONFIGS:
        if relative in p['source_entries']:
            snap.write_new(archive / Path(relative).name, snap.read_bytes(root / 'data' / relative, 2 * 1024**2))
    for name in p['create_empty_directories']:
        (runtime / name).mkdir(mode=0o700, parents=True, exist_ok=True)
    if p['external_inputs']:
        (runtime / p['external_prefix']).mkdir(mode=0o700, exist_ok=False)
    for group in p['external_inputs']:
        _copy_input(Path(group['source']), runtime / group['target'], group['entries'])
    for relative, data in p['configuration_outputs'].items():
        (runtime / relative).parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        snap.write_new(runtime / relative, snap.encoded(data))
    snap.verify(root, p['snapshot_manifest_sha256'], limits)
    for group in p['external_inputs']:
        if _inventory_path(Path(group['source']), limits) != group['entries']:
            raise ValueError('External input changed during copy; incomplete output retained')
        if _inventory_path(runtime / group['target'], limits) != group['entries']:
            raise ValueError('External copy differs; incomplete output retained')
    entries = snap.inventory(runtime, limits)
    for name, before in p['source_entries'].items():
        if name not in CONFIGS and before['kind'] == 'file' and entries.get(name) != before:
            raise ValueError('Non-config original file not preserved; incomplete output retained')
    if snap.inventory(Path(p['original']), limits) != original_before:
        raise ValueError('Retained original changed during copy; incomplete output retained')
    guards = [dict(path=p['original'], entries=original_before),
              dict(path=str(root), entries=snap.inventory(root, limits)),
              dict(path=str(archive), entries=snap.inventory(archive, limits))]
    guards.extend(dict(path=g['source'], entries=g['entries']) for g in p['external_inputs'])
    receipt = dict(format=FORMAT, plan_sha256=expected_plan_sha256,
                   snapshot_manifest_sha256=p['snapshot_manifest_sha256'], runtime=str(runtime),
                   output=str(output), entries=entries, protected_inputs=guards,
                   control_entries=snap.inventory(output / 'control', limits),
                   blockers=p['blockers'], original_config_archived=True, original_replaced=False,
                   original_matches_snapshot=original_before == p['source_entries'],
                   external_inputs_copied=len(p['external_inputs']), config_fields=list(snap.PATH_FIELDS),
                   launch_safe=False, approval_required=True, native_mint_tested=False,
                   ready_for_production=False)
    raw = snap.encoded(receipt)
    if len(raw) > snap.MAX_MANIFEST:
        raise ValueError('Migration receipt exceeds metadata limit; incomplete output retained')
    snap.write_new(output / 'migration.json', raw)
    return receipt


def _verify_copy(root, expected_receipt_sha256, limits):
    root = snap.checked(Path(root))
    raw = snap.read_bytes(root / 'migration.json', snap.MAX_MANIFEST)
    if snap.digest(raw) != expected_receipt_sha256:
        raise ValueError('Migration receipt hash mismatch')
    receipt = _json(root / 'migration.json')
    if (receipt.get('format') != FORMAT or receipt.get('output') != str(root)
            or receipt.get('runtime') != str(root / 'data') or receipt.get('launch_safe') is not False):
        raise ValueError('Invalid migrated copy provenance')
    if snap.inventory(root / 'data', limits) != receipt['entries']:
        raise ValueError('Migrated copy inventory changed since verification')
    if snap.inventory(root / 'control', limits) != receipt['control_entries']:
        raise ValueError('Isolated home/tmp control inventory changed')
    if receipt['blockers']:
        raise ValueError('Migration has unresolved launch blockers: ' + ', '.join(receipt['blockers']))
    # JSON syntax alone cannot establish which Java Config fallback is used.
    # Validate known non-path types too, so invalid listenPort/map/etc cannot
    # discard the safe rewritten fields and silently activate Java defaults.
    for relative in CONFIGS:
        validate_app_config(_json(root / 'data' / relative), receipt['entries'])
    for guard in receipt['protected_inputs']:
        if _inventory_path(Path(guard['path']), limits) != guard['entries']:
            raise ValueError('Protected original/input changed since snapshot or copy')
    return receipt


def _command_inputs(command_inputs, limits):
    if command_inputs is None:
        return {}
    if not isinstance(command_inputs, dict):
        raise ValueError('command_inputs must map paths to expected hashes')
    checked = {}
    for raw, expected in command_inputs.items():
        path = snap.checked(Path(raw))
        entries = _inventory_path(path, limits)
        if entries['.']['kind'] != 'file' or entries['.']['sha256'] != expected:
            raise ValueError('Command input hash changed or is not a regular file')
        checked[str(path)] = expected
    return checked


def approve(copy_root, expected_receipt_sha256, *, command, command_inputs=None,
            confirm=False, limits=snap.Limits()):
    """Bind a reviewed argv and current clone to a new approval, never execute it."""
    if confirm is not True:
        raise ValueError('Explicit confirmation of validation command required')
    root = snap.checked(Path(copy_root))
    _verify_copy(root, expected_receipt_sha256, limits)
    if (not isinstance(command, (list, tuple)) or not command
            or any(not isinstance(value, str) or '\x00' in value for value in command)):
        raise ValueError('Command must be an explicit nonempty argv; no shell string')
    # Resolving the caller-chosen tool executable is distinct from dereferencing
    # runtime/source symlinks, which remain forbidden.
    executable = shutil.which(command[0])
    if executable is None:
        raise ValueError('Validation executable not found')
    executable = Path(executable).resolve(strict=True)
    argv = [str(executable), *command[1:]]
    substitutions = dict(runtime=str(root / 'data'), home=str(root / 'control/home'),
                         tmp=str(root / 'control/tmp'))
    for index, value in enumerate(argv[1:], 1):
        for name, replacement in substitutions.items():
            value = value.replace('{' + name + '}', replacement)
        argv[index] = value
    inputs = _command_inputs(command_inputs, limits)
    inputs[str(executable)] = _inventory_path(executable, limits)['.']['sha256']
    record = dict(format=APPROVAL_FORMAT, output=str(root),
                  receipt_sha256=expected_receipt_sha256, command=argv, command_inputs=inputs,
                  validation_only=True, approved_by_caller=True, native_mint_tested=False,
                  ready_for_production=False)
    raw = snap.encoded(record)
    snap.write_new(root / 'approval.json', raw)
    return dict(record, approval_sha256=snap.digest(raw))


class _WriteWatch:
    """Linux inotify write/change audit; event loss and watch loss fail closed."""
    MASK = 0x00000002 | 0x00000004 | 0x00000008 | 0x00000040 | 0x00000080 | 0x00000100 | 0x00000200 | 0x00000400 | 0x00000800

    def __init__(self, paths, limits):
        if not sys.platform.startswith('linux'):
            raise ValueError('Validation write monitoring currently requires Linux')
        self.lib = ctypes.CDLL(None, use_errno=True)
        self.lib.inotify_init1.argtypes = [ctypes.c_int]
        self.lib.inotify_init1.restype = ctypes.c_int
        self.lib.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
        self.lib.inotify_add_watch.restype = ctypes.c_int
        self.fd = self.lib.inotify_init1(os.O_NONBLOCK | os.O_CLOEXEC)
        if self.fd < 0:
            raise OSError(ctypes.get_errno(), 'Cannot initialize input write monitor')
        self.names = {}
        try:
            for raw in paths:
                path = snap.checked(Path(raw))
                entries = _inventory_path(path, limits)
                # Watch files too: a direct hardlink write can avoid the containing
                # directory's pathname events while changing the same original inode.
                for name in entries:
                    target = path if name == '.' else path / name
                    wd = self.lib.inotify_add_watch(self.fd, os.fsencode(target), self.MASK | 0x02000000)
                    if wd < 0:
                        raise OSError(ctypes.get_errno(), 'Cannot watch protected input: ' + str(target))
                    self.names.setdefault(wd, set()).add(str(target))
        except BaseException:
            os.close(self.fd)
            raise

    def events(self):
        events = []
        while True:
            try:
                raw = os.read(self.fd, 256 * 1024)
            except BlockingIOError:
                break
            if not raw:
                break
            cursor = 0
            while cursor + 16 <= len(raw):
                wd, mask, cookie, length = struct.unpack_from('iIII', raw, cursor)
                name = raw[cursor + 16:cursor + 16 + length].split(b'\0', 1)[0]
                cursor += 16 + length
                events.append(dict(watched=sorted(self.names.get(wd, {'watch_overflow_or_loss'})),
                                   name=os.fsdecode(name), mask=mask))
        return events

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        os.close(self.fd)


def launch(copy_root, authorization, *, confirm_launch=False, timeout=45, limits=snap.Limits()):
    """Run one explicitly approved validation command; keep logs on every outcome.

    Known protected roots are checked before/after and monitored for transient
    writes. No claim of blocking arbitrary hostile/daemonized code is made.
    """
    if confirm_launch is not True:
        raise ValueError('Explicit confirm_launch required; approval never auto-starts')
    if type(timeout) not in (int, float) or not 0 < timeout <= 60:
        raise ValueError('Validation timeout must be between zero and 60 seconds')
    root = snap.checked(Path(copy_root))
    if not isinstance(authorization, dict) or not isinstance(authorization.get('approval_sha256'), str):
        raise ValueError('Pinned saved approval required')
    raw = snap.read_bytes(root / 'approval.json', snap.MAX_MANIFEST)
    if snap.digest(raw) != authorization['approval_sha256']:
        raise ValueError('Saved approval changed')
    record = _json(root / 'approval.json')
    if dict(record, approval_sha256=snap.digest(raw)) != authorization:
        raise ValueError('Approval arguments changed')
    if (record.get('format') != APPROVAL_FORMAT or record.get('output') != str(root)
            or record.get('validation_only') is not True or record.get('approved_by_caller') is not True):
        raise ValueError('Invalid approval scope')
    receipt = _verify_copy(root, record['receipt_sha256'], limits)
    _command_inputs(record['command_inputs'], limits)
    protected = [g['path'] for g in receipt['protected_inputs']] + list(record['command_inputs'])
    run_parent = snap.checked(root / 'validation-runs')
    if not stat.S_ISDIR(run_parent.lstat().st_mode):
        raise ValueError('Validation log path must be a real directory')
    if list(run_parent.iterdir()):
        raise ValueError('Validation approval already used; make a new snapshot/copy')
    with _WriteWatch(protected, limits) as watch:
        # Close the gap between input hash checks and installing all watches.
        _verify_copy(root, record['receipt_sha256'], limits)
        _command_inputs(record['command_inputs'], limits)
        if watch.events():
            raise ValueError('Protected inputs changed while installing write watches')
        snap.checked(run_parent)
        run_dir = snap.checked(run_parent / uuid.uuid4().hex)
        run_dir.mkdir(mode=0o700)
        env = {k: v for k, v in os.environ.items() if k not in
               ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH',
                'PYTHONPATH', 'PYTHONHOME', 'LD_PRELOAD', 'LD_LIBRARY_PATH')}
        env.update(HOME=str(root / 'control/home'), TMPDIR=str(root / 'control/tmp'),
                   TMP=str(root / 'control/tmp'), TEMP=str(root / 'control/tmp'),
                   XDG_CONFIG_HOME=str(root / 'control/home'), XDG_DATA_HOME=str(root / 'control/home'),
                   XDG_CACHE_HOME=str(root / 'control/cache'), PYTHONDONTWRITEBYTECODE='1')
        timed_out, process_error = False, None
        with (run_dir / 'stdout.txt').open('xb') as stdout, (run_dir / 'stderr.txt').open('xb') as stderr:
            process = None
            try:
                process = subprocess.Popen(record['command'], cwd=root / 'data', env=env,
                                           stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr,
                                           start_new_session=True, close_fds=True)
                try:
                    process.wait(timeout=timeout)
                except subprocess.TimeoutExpired:
                    timed_out = True
            except OSError as error:
                process_error = str(error)
            finally:
                if process is not None:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait(timeout=5)
        events = watch.events()
        unchanged, changed = True, []
        for guard in receipt['protected_inputs']:
            try:
                equal = _inventory_path(Path(guard['path']), limits) == guard['entries']
            except (OSError, ValueError):
                equal = False
            if not equal:
                unchanged = False
                changed.append(guard['path'])
        try:
            _command_inputs(record['command_inputs'], limits)
        except (OSError, ValueError):
            unchanged = False
            changed.append('command_inputs')
        events.extend(watch.events())
        result = dict(format='logbook-runtime-validation-result-v1',
                      returncode=process.returncode if process is not None else None,
                      timed_out=timed_out, process_error=process_error,
                      originals_unchanged=unchanged and not events, changed_inputs=changed,
                      external_write_events=events, output=str(run_dir),
                      native_mint_tested=False, ready_for_production=False)
        for name in ('stdout', 'stderr'):
            with (run_dir / (name + '.txt')).open('rb') as stream:
                data = stream.read(2 * 1024 * 1024)
            result[name] = data.decode('utf-8', errors='replace')
        snap.write_new(run_dir / 'result.json', snap.encoded(result))
    if events or not unchanged:
        raise ValueError('Validation wrote or changed protected original/input; evidence retained at ' + str(run_dir))
    if timed_out or process_error or result['returncode'] != 0:
        raise ValueError('Validation command failed; evidence retained at ' + str(run_dir))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True)
    p = sub.add_parser('plan')
    p.add_argument('--snapshot', type=Path, required=True)
    p.add_argument('--manifest-sha256', required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--selected-paths', type=Path, help='JSON map: exact raw config value -> chosen local path')
    p.add_argument('--reviewed-artifacts', type=Path, help='JSON map: runtime-relative plugin/script -> audited SHA256')
    p = sub.add_parser('copy')
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--plan-sha256', required=True)
    p.add_argument('--confirm-copy', action='store_true')
    p = sub.add_parser('approve-validation')
    p.add_argument('--copy', type=Path, required=True)
    p.add_argument('--receipt-sha256', required=True)
    p.add_argument('--command-json', type=Path, required=True, help='JSON argv array; never a shell string')
    p.add_argument('--command-inputs', type=Path, help='JSON map: reviewed executable input file -> SHA256')
    p.add_argument('--confirm', action='store_true')
    p = sub.add_parser('launch-validation')
    p.add_argument('--copy', type=Path, required=True)
    p.add_argument('--approval-sha256', required=True)
    p.add_argument('--confirm-launch', action='store_true')
    args = parser.parse_args()
    try:
        if args.operation == 'plan':
            result = plan(args.snapshot, args.manifest_sha256, args.out,
                          selected_paths=_json(args.selected_paths) if args.selected_paths else None,
                          reviewed_artifacts=_json(args.reviewed_artifacts) if args.reviewed_artifacts else None)
        elif args.operation == 'copy':
            result = apply(_json(args.plan), expected_plan_sha256=args.plan_sha256, confirm_copy=args.confirm_copy)
        elif args.operation == 'approve-validation':
            result = approve(args.copy, args.receipt_sha256, command=_json(args.command_json),
                             command_inputs=_json(args.command_inputs) if args.command_inputs else None,
                             confirm=args.confirm)
        else:
            approval = dict(_json(args.copy / 'approval.json'), approval_sha256=args.approval_sha256)
            result = launch(args.copy, approval, confirm_launch=args.confirm_launch)
        print(snap.encoded(result).decode(), end='')
        return 0
    except (OSError, ValueError, TypeError, KeyError, RecursionError) as error:
        print('Runtime migration refused: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
