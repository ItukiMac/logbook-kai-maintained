#!/usr/bin/env python3
"""Explicit portable-runtime import and whole-store recovery into NEW directories.

The explicit named profiles describe known storage shapes, not compatibility with every
old application release. No schema conversion, application/plugin/script launch,
source rewrite, cleanup or in-place recovery is provided. Unknown structured data
is refused; opaque retained files are copied without interpretation. The caller
must first resolve every AppConfig path in a separate migration copy.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import math
import os
from pathlib import Path, PureWindowsPath
import re
import shutil
import sys
from uuid import UUID
from zipfile import BadZipFile

import feature_bundle_manager as manager
import runtime_snapshot as snapshots

FORMAT = 'logbook-runtime-managed-import-v1'
RESTORE_FORMAT = 'logbook-managed-whole-restore-v1'
PROFILES = ('legacy-config-v1', 'arrival-journal-v1', 'arrival-tracking-v1')
JOURNAL = 'sortie-arrivals-v1'
APP_CONFIG = 'config/logbook.bean.AppConfig.json'
ACCEPTANCE_CONFIG = 'config/logbook.bean.AppQuestDuration.json'
MAX_JSON = 2 * 1024**2
METADATA_ALLOWANCE = 1024**2
TOKYO = timezone(timedelta(hours=9))

# These are the actual AppConfig properties at the declared R10/R11 baseline.
# A new property must be reviewed instead of being silently ignored by Jackson.
APP_STRINGS = frozenset(('windowStyle', 'fontFamily', 'fontSize', 'toastLocation',
    'tabColorNoDamage', 'tabColorLessThanSlightDamage', 'tabColorSlightDamage',
    'tabColorHalfDamage', 'tabColorBadlyDamage', 'tabColorNoMission',
    'tabColorNeedRefuel', 'proxyHost', 'captureFormat', 'ffmpegArgs', 'ffmpegExt',
    *snapshots.PATH_FIELDS))
APP_BOOLEANS = frozenset(('useNotification', 'alertBadlyStart', 'alertBadlyNext',
    'ignoreSecondFlagship', 'useSound', 'useToast', 'useCondRecoverToast',
    'useRemind', 'showAchievement', 'showMission', 'showNdock', 'showQuest',
    'visibleExpGauge', 'applyBattle', 'applyResult', 'allDecksTab', 'deckTabs',
    'labelTabs', 'onTop', 'checkDoit', 'checkUpdate', 'performanceLog',
    'connectionClose', 'allowOnlyFromLocalhost', 'useProxy', 'usePlugin',
    'indefiniteExpires', 'compressBattleLogs', 'includeRawData',
    'shipImageCompress', 'hideShipImageFromShipTablePane',
    'hideItemImageFromShipTablePane', 'visiblePoseImageOnFleetTab', 'storeApiStart2'))
APP_INTEGERS = frozenset(('remind', 'shipFullyThreshold', 'itemFullyThreshold',
    'imageZoomRate', 'soundLevel', 'materialLogInterval', 'listenPort', 'proxyPort',
    'battleLogExpires', 'seaAreaIndex'))
APP_MAPS = frozenset(('windowLocationMap', 'columnVisibleMap', 'columnWidthMap',
    'columnSortOrderMap', 'columnOrderMap', 'dividerPositionMap'))
APP_ENUMS = {
    'resultRank': frozenset(('S完全勝利', 'S勝利', 'A勝利', 'B戦術的勝利',
                             'C戦術的敗北', 'D敗北', 'E敗北')),
    'shipImageCacheStrategy': frozenset(('ALL', 'USED', 'LIMIT')),
}
APP_FIELDS = APP_STRINGS | APP_BOOLEANS | APP_INTEGERS | APP_MAPS | APP_ENUMS.keys()
FILE_PATHS = frozenset(('defaultNotifySound', 'ffmpegPath'))
NULL_PATHS = frozenset(('captureDir', 'ffmpegPath'))
ARRIVAL_FIELDS = frozenset(('format', 'sortieId', 'startedAt', 'arrivedAt',
                           'map', 'deckId', 'shipMasterIds', 'noRetreat'))


def require(condition, message):
    if not condition:
        raise ValueError(message)


def _constant(value):
    raise ValueError('Non-finite JSON number is not a known schema')


def read_json(path, limit=MAX_JSON):
    return json.loads(snapshots.read_bytes(path, limit),
                      object_pairs_hook=snapshots.unique, parse_constant=_constant)


def _number(value):
    if type(value) not in (int, float):
        return False
    try:
        return math.isfinite(value)
    except OverflowError:
        return False


def _app_maps(key, value):
    require(isinstance(value, dict), 'Unknown AppConfig map shape: ' + key)
    for child in value.values():
        if key == 'windowLocationMap':
            valid = (isinstance(child, dict) and set(child) <= {'x', 'y', 'width', 'height'}
                     and all(_number(x) for x in child.values()))
        elif key in ('columnVisibleMap', 'columnOrderMap'):
            valid = isinstance(child, list) and all(isinstance(x, str) for x in child)
        elif key == 'columnWidthMap':
            valid = isinstance(child, dict) and all(_number(x) for x in child.values())
        elif key == 'columnSortOrderMap':
            valid = isinstance(child, dict) and all(isinstance(x, str) for x in child.values())
        else:  # dividerPositionMap
            valid = _number(child)
        require(valid, 'Unknown AppConfig map value: ' + key)


def _portable_path(key, value, entries):
    if value is None:
        require(key in NULL_PATHS, 'AppConfig path must be explicit and relative: ' + key)
        return
    require(isinstance(value, str), 'Invalid AppConfig path value: ' + key)
    # An empty optional path is an explicit disabled/default-local setting. The
    # importer does not execute ffmpeg or interpret sound configuration.
    if value == '':
        return
    path = Path(value)
    require('\x00' not in value and '\\' not in value and not PureWindowsPath(value).drive
            and not path.is_absolute() and '..' not in path.parts,
            'AppConfig path must be portable and relative: ' + key)
    record = entries.get(path.as_posix())
    require(isinstance(record, dict), 'AppConfig path is missing from the runtime copy: ' + key)
    expected = 'file' if key in FILE_PATHS else 'directory'
    require(record.get('kind') == expected, 'AppConfig path has the wrong kind: ' + key)


def validate_app_config(value, entries):
    """Reject unknown bean shapes/default fallbacks and unresolved copied paths.

    ``entries`` is the safe runtime inventory after path rewriting. This reusable
    gate validates every known AppConfig property, including non-path fields such
    as listenPort whose type errors would make Java Config fall back to a backup
    or defaults. Call it for both executable primary and backup configurations.
    """
    require(isinstance(value, dict) and set(value) <= APP_FIELDS, 'Unknown AppConfig schema/property')
    require(set(snapshots.PATH_FIELDS) <= set(value), 'All 12 AppConfig paths must be explicit before import')
    for key, item in value.items():
        if key in APP_STRINGS:
            require(item is None or isinstance(item, str), 'Unknown AppConfig string value: ' + key)
        elif key in APP_BOOLEANS:
            require(type(item) is bool, 'Unknown AppConfig boolean value: ' + key)
        elif key in APP_INTEGERS:
            require(type(item) is int and -(2**31) <= item < 2**31, 'Unknown AppConfig integer value: ' + key)
        elif key in APP_MAPS:
            _app_maps(key, item)
        else:
            require(isinstance(item, str) and item in APP_ENUMS[key], 'Unknown AppConfig enumeration: ' + key)
    for key in snapshots.PATH_FIELDS:
        _portable_path(key, value[key], entries)


def _date(value):
    require(isinstance(value, str) and re.fullmatch(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}', value),
            'Invalid acceptance date')
    try:
        return datetime.strptime(value, '%Y-%m-%d %H:%M:%S')
    except ValueError as error:
        raise ValueError('Invalid acceptance date') from error


def _acceptance(value):
    require(isinstance(value, dict) and set(value) <= {'map'}, 'Unknown acceptance schema')
    periods = value.get('map', {})
    require(isinstance(periods, dict), 'Unknown acceptance map')
    count = 0
    for expiry, by_quest in periods.items():
        _date(expiry)
        require(isinstance(by_quest, dict), 'Unknown acceptance quest map')
        for quest, windows in by_quest.items():
            require(re.fullmatch('[1-9][0-9]{0,9}', quest) is not None and int(quest) < 2**31,
                    'Invalid acceptance quest identity')
            require(isinstance(windows, list), 'Unknown acceptance interval list')
            for window in windows:
                require(isinstance(window, dict) and 'from' in window and set(window) <= {'from', 'to'},
                        'Unknown acceptance interval schema')
                start = _date(window['from'])
                end = window.get('to')
                require(end is None or _date(end) >= start, 'Reversed acceptance interval')
                count += 1
    return count


def _uuid(value):
    if not isinstance(value, str):
        return False
    try:
        return str(UUID(value)) == value
    except (ValueError, AttributeError):
        return False


def _arrival(value, month, filename):
    require(isinstance(value, dict) and set(value) == ARRIVAL_FIELDS, 'Unknown arrival schema')
    require(value['format'] == 'logbook-port-arrival-v1', 'Unknown arrival format')
    identity = value['sortieId']
    require(_uuid(identity), 'Invalid arrival identity')
    start, end, deck = value['startedAt'], value['arrivedAt'], value['deckId']
    require(type(start) is int and type(end) is int and 0 < start <= end < 2**63
            and end - start <= 86_400_000, 'Invalid arrival timestamps')
    require(value['map'] == '1-6' and type(deck) is int and 1 <= deck <= 4,
            'Unknown arrival map/deck schema')
    ships = value['shipMasterIds']
    require(isinstance(ships, list) and 1 <= len(ships) <= 7
            and all(type(x) is int and 0 < x < 2**31 for x in ships)
            and type(value['noRetreat']) is bool, 'Invalid arrival fleet schema')
    try:
        expected_month = datetime.fromtimestamp(end // 1000, TOKYO).strftime('%Y-%m')
    except (ValueError, OverflowError, OSError) as error:
        raise ValueError('Unsupported arrival timestamp range') from error
    require(month == expected_month, 'Misplaced arrival month')
    if filename.endswith('.json'):
        require(filename == identity + '.json', 'Misplaced arrival filename')
    else:
        prefix = '.' + identity + '-'
        require(filename.startswith(prefix) and filename.endswith('.pending')
                and _uuid(filename[len(prefix):-len('.pending')]), 'Unknown arrival pending filename')
    return identity


def validate_runtime(root, profile, entries=None, *, limits=snapshots.Limits()):
    """Validate the declared storage profile without modifying or executing it."""
    require(profile in PROFILES, 'Unknown runtime schema profile')
    root = snapshots.checked(Path(root))
    entries = snapshots.inventory(root, limits) if entries is None else entries
    require(entries.get('.', {}).get('kind') == 'directory', 'Missing runtime root')
    if 'home' in entries:
        require(entries['home']['kind'] == 'directory', 'Managed home conflicts with a runtime file')
    require('store.json' not in entries, 'Import a runtime snapshot, not a whole managed store')
    configs = []
    intervals = 0
    for name in (APP_CONFIG, APP_CONFIG + '.backup', ACCEPTANCE_CONFIG, ACCEPTANCE_CONFIG + '.backup'):
        if name not in entries:
            continue
        require(entries[name]['kind'] == 'file', 'Structured configuration must be a regular file')
        value = read_json(root / name)
        if name.startswith(APP_CONFIG):
            validate_app_config(value, entries)
            configs.append(name)
        else:
            intervals += _acceptance(value)
    require(configs, 'A known primary or backup AppConfig is required')
    journals = {name.split('/')[0] for name in entries if name.split('/')[0].startswith('sortie-arrivals-')}
    require(journals <= {JOURNAL}, 'Unknown arrival journal schema directory')
    if profile == 'legacy-config-v1':
        require(not journals, 'Legacy profile cannot import an arrival journal')
    else:
        require(entries.get(JOURNAL, {}).get('kind') == 'directory', 'Arrival profile requires its v1 journal directory')
    observed = {}
    auxiliary = {name for name in entries if name.startswith(JOURNAL + '/')
                 and Path(name).parts[1] in ('tracking-v1', 'fleet-evidence-v1')}
    require(not auxiliary or profile == 'arrival-tracking-v1',
            'Tracking evidence requires the explicit arrival-tracking-v1 profile')
    published, pending = 0, 0
    for name, row in entries.items():
        if not name.startswith(JOURNAL + '/') or name in auxiliary:
            continue
        parts = Path(name).parts
        require(len(parts) in (2, 3) and re.fullmatch(r'[0-9]{4}-(0[1-9]|1[0-2])', parts[1]),
                'Unknown arrival journal layout')
        if len(parts) == 2:
            require(row['kind'] == 'directory', 'Arrival month must be a directory')
            continue
        require(row['kind'] == 'file', 'Arrival entry must be a regular file')
        value = read_json(root / name, 16 * 1024)
        identity = _arrival(value, parts[1], parts[2])
        require(identity not in observed or observed[identity] == value, 'Conflicting arrival identity')
        observed[identity] = value
        if parts[2].endswith('.json'):
            published += 1
        else:
            pending += 1
    extra = {}
    if profile == 'arrival-tracking-v1':
        from arrival_tracking_profile import validate_auxiliary
        handled, extra = validate_auxiliary(root, entries, observed, read_json, _arrival)
        require(handled == auxiliary, 'Unvalidated tracking archive entry')
    return {**extra, 'profile': profile, 'appconfig_files': configs,
            'acceptance_intervals_including_backups': intervals,
            'published_arrivals': published, 'retained_pending_arrivals': pending,
            'schema_converted': False, 'opaque_files_schema_validated': False,
            'third_party_paths_audited': False, 'launch_safe': False}


def _disjoint(output, sources):
    for source in sources:
        if output.is_relative_to(source) or source.is_relative_to(output):
            raise ValueError('Output must be disjoint from snapshot, original and bundle sources')


def _protected_roots(roots):
    require(isinstance(roots, (list, tuple)), 'Protected roots must be an explicit path list')
    return list(dict.fromkeys(snapshots.checked(Path(root)) for root in roots))


def _receipt_protected_roots(root, entries):
    """Read lineage only from known receipts already inside the verified snapshot.

    Receipts protect their explicit original namespaces without opening any data
    in those namespaces. Unknown/malformed known-receipt files are a blocker, not
    an excuse to drop protection. Direct runtime snapshots have no such receipts;
    their earlier ancestry still has to be supplied by the caller.
    """
    roots, receipts = [], []
    for name in ('import.json', 'import-intent.json'):
        if name not in entries:
            continue
        require(entries[name]['kind'] == 'file', 'Invalid import receipt file')
        value = read_json(root / name)
        require(isinstance(value, dict) and value.get('format') == FORMAT,
                'Unknown import receipt format')
        inherited = value.get('protected_roots')
        require(isinstance(inherited, list) and inherited, 'Invalid protected-root list in import receipt')
        for raw in inherited:
            require(isinstance(raw, str) and '\x00' not in raw and Path(raw).anchor == '/',
                    'Invalid absolute protected root in import receipt')
            try:
                roots.append(snapshots.checked(Path(raw)))
            except (OSError, ValueError) as error:
                raise ValueError('Unsafe protected root in import receipt') from error
        receipts.append(name)
    return list(dict.fromkeys(roots)), receipts


def _new_destination(output, sources, required, limits):
    output = snapshots.checked(Path(output))
    _disjoint(output, sources)
    if os.path.lexists(output):
        raise FileExistsError('Output already exists; choose a NEW directory')
    require(output.parent.is_dir(), 'Output parent must already exist')
    require(required <= limits.bytes, 'Aggregate import byte budget exceeded')
    require(shutil.disk_usage(output.parent).free >= required + limits.reserve + METADATA_ALLOWANCE,
            'Insufficient free space for runtime, bundles and safety reserve')
    return output


def _snapshot(root, manifest_hash, limits):
    report = snapshots.verify(root, manifest_hash, limits)
    require(report.get('application_stopped_confirmed_by_caller') is True,
            'Snapshot lacks stopped-application confirmation')
    require(report.get('schema_converted') is False, 'Snapshot has an unsupported schema conversion')
    return report


def _bundle(source, identity, limits):
    source = snapshots.checked(Path(source))
    manager.identifier(identity)
    before = snapshots.inventory(source, limits)
    require(set(before) == {'.', 'features', 'distribution.json', *manager.FILES},
            'Unknown bundle entries; refusing to omit unrecognized files')
    manager.bundle(source, identity)
    require(before == snapshots.inventory(source, limits), 'Bundle source changed while validating')
    return source, before


def _copy_runtime(source, target, entries):
    # initialize() creates exactly runtime/home. Preserve a source home directory
    # and its contents without removing or replacing that new empty directory.
    require(set(snapshots.inventory(target)) == {'.', 'home'}, 'New managed runtime is not empty')
    selected = {name: row for name, row in entries.items() if name != 'home'}
    snapshots.copy_entries(source, target, selected)
    if 'home' in entries:
        row = entries['home']
        os.chmod(target / 'home', row['mode'])
        os.utime(target / 'home', ns=(row['mtime_ns'], row['mtime_ns']))


def _runtime_matches(target, entries, limits):
    actual = snapshots.inventory(target, limits)
    if 'home' not in entries:
        # The one new managed home is empty and adds no executable/user content.
        require(actual.get('home', {}).get('kind') == 'directory'
                and not any(name.startswith('home/') for name in actual), 'Managed home changed during import')
        actual = {name: row for name, row in actual.items() if name != 'home'}
    require(actual == entries, 'Copied runtime differs from snapshot; incomplete output retained')


def import_snapshot(snapshot_root, output_store, manifest_sha256, *, profile, bundles,
                    selected_bundle_sha256, confirm_import=False, protected_roots=(),
                    limits=snapshots.Limits()):
    """Copy a portable, SHA-pinned runtime into a new managed store; never start it.

    Carry earlier original/external roots explicitly when snapshotting a migrated
    copy: a snapshot records its immediate source, not an implicit lineage graph.
    Protected roots are only checked as boundaries, never inventoried or copied.
    """
    require(confirm_import is True, 'Explicit import confirmation required')
    require(profile in PROFILES, 'Unknown runtime schema profile')
    snapshot_root = snapshots.checked(Path(snapshot_root))
    report = _snapshot(snapshot_root, manifest_sha256, limits)
    source = snapshot_root / 'data'
    entries = report['entries']
    schema = validate_runtime(source, profile, entries, limits=limits)
    require(isinstance(bundles, (list, tuple)) and len(bundles) > 0, 'Explicit known bundle list required')
    sources = [snapshot_root, snapshots.checked(Path(report['source'])), *_protected_roots(protected_roots)]
    checked_bundles = {}
    required = sum(row.get('bytes', 0) for row in entries.values())
    for supplied in bundles:
        require(isinstance(supplied, (list, tuple)) and len(supplied) == 2, 'Invalid bundle selection')
        path, identity = supplied
        path, bundle_entries = _bundle(path, identity, limits)
        require(identity not in checked_bundles, 'Duplicate bundle identity')
        checked_bundles[identity] = (path, bundle_entries)
        sources.append(path)
        required += sum(row.get('bytes', 0) for row in bundle_entries.values())
    require(selected_bundle_sha256 in checked_bundles, 'Selected bundle must be explicitly supplied')
    output_store = _new_destination(output_store, sources, required, limits)
    manager.initialize(output_store)
    snapshots.write_new(output_store / 'import-intent.json', snapshots.encoded({
        'format': FORMAT, 'snapshot_manifest_sha256': manifest_sha256, 'profile': profile,
        'selected_bundle_sha256': selected_bundle_sha256, 'protected_roots': [str(p) for p in sources],
        'launch_safe': False}))
    _copy_runtime(source, output_store / 'runtime', entries)
    _runtime_matches(output_store / 'runtime', entries, limits)
    for identity, (path, _) in checked_bundles.items():
        manager.stage(output_store, path, identity)
    # Re-read every source after copying/staging, before the selector exists.
    _snapshot(snapshot_root, manifest_sha256, limits)
    for identity, (path, original) in checked_bundles.items():
        _, current = _bundle(path, identity, limits)
        require(current == original, 'Bundle source changed during import; incomplete output retained')
        manager.bundle(output_store / 'versions' / identity, identity)
    _runtime_matches(output_store / 'runtime', entries, limits)
    validate_runtime(output_store / 'runtime', profile, limits=limits)
    manager.activate(output_store, selected_bundle_sha256, None)
    receipt = {'format': FORMAT, 'store': str(output_store), 'runtime': str(output_store / 'runtime'),
               'snapshot_manifest_sha256': manifest_sha256, 'profile': profile,
               'protected_roots': [str(p) for p in sources],
               'bundle_sha256s': list(checked_bundles), 'selected_bundle_sha256': selected_bundle_sha256,
               'runtime_inventory_sha256': snapshots.digest(snapshots.encoded(snapshots.inventory(output_store / 'runtime', limits))),
               'source_bytes_written': False, 'original_replaced': False, 'schema_converted': False,
               'whole_runtime_bytes_preserved': True, 'schema_review': schema,
               'third_party_paths_audited': False, 'launch_safe': False,
               'native_mint_tested': False, 'ready_for_production': False}
    raw = snapshots.encoded(receipt)
    snapshots.write_new(output_store / 'import.json', raw)  # Completion marker is last.
    return {**receipt, 'receipt_sha256': snapshots.digest(raw)}


def _store(root, entries, profile, limits):
    require(read_json(root / 'store.json', 262144) == {'format': manager.FORMAT, 'state_rollback': False},
            'Unknown managed store schema')
    for name in ('versions', 'runtime', 'history', 'runtime/home'):
        require(entries.get(name, {}).get('kind') == 'directory', 'Incomplete managed store directory: ' + name)
    require(entries.get('store.lock', {}).get('kind') == 'file', 'Missing managed store lock')
    runtime_entries = {'.' if name == 'runtime' else name[len('runtime/'):]: row
                       for name, row in entries.items() if name == 'runtime' or name.startswith('runtime/')}
    schema = validate_runtime(root / 'runtime', profile, runtime_entries, limits=limits)
    selector = manager.selector(root)
    require(selector is not None, 'Whole-store recovery requires a retained selection')
    versions = [name[len('versions/'):] for name in entries
                if name.startswith('versions/') and '/' not in name[len('versions/'):]]
    # Interrupted E stages are retained forensic bytes, not registered releases.
    # Validate all registered versions and E's history graph without interpreting
    # incomplete pending directories as installable bundles.
    pending_versions = [v for v in versions if re.fullmatch(r'pending-[0-9a-f]{32}', v)]
    versions = [v for v in versions if v not in pending_versions]
    for identity in versions:
        _bundle(root / 'versions' / identity, identity, limits)
    manager.retained_module_identities(root)
    require(selector['current'] in versions and (selector['previous'] is None or selector['previous'] in versions),
            'Selected/current previous bundle is missing from the whole snapshot')
    return selector, versions, schema


def restore_store(snapshot_root, output, manifest_sha256, *, profile,
                  confirm_restore=False, protected_roots=(), limits=snapshots.Limits()):
    """Recover the entire checked store, including old runtime state, to output/data.

    This is intentionally different from selector rollback, which changes only
    application files and retains the currently modified runtime. Earlier roots
    recorded in known import receipts within the pinned whole-store snapshot are
    automatically protected, in addition to explicit caller-supplied roots.
    """
    require(confirm_restore is True, 'Explicit whole-store restore confirmation required')
    require(profile in PROFILES, 'Unknown runtime schema profile')
    snapshot_root = snapshots.checked(Path(snapshot_root))
    report = _snapshot(snapshot_root, manifest_sha256, limits)
    output = snapshots.checked(Path(output))
    inherited, inherited_receipts = _receipt_protected_roots(snapshot_root / 'data', report['entries'])
    sources = list(dict.fromkeys((snapshot_root, snapshots.checked(Path(report['source'])),
                                 *_protected_roots(protected_roots), *inherited)))
    _disjoint(output, sources)
    selector, versions, schema = _store(snapshot_root / 'data', report['entries'], profile, limits)
    restored = snapshots.restore(snapshot_root, output, manifest_sha256, limits=limits)
    target = Path(restored['restored_to'])
    restored_selector, restored_versions, _ = _store(target, report['entries'], profile, limits)
    require(restored_selector == selector and restored_versions == versions, 'Whole-store state changed during restore')
    require(snapshots.inventory(target, limits) == report['entries'], 'Whole-store bytes changed during restore')
    result = {**restored, 'format': RESTORE_FORMAT, 'restored_store': str(target),
              'profile': profile, 'selected_bundle_sha256': selector['current'], 'bundle_sha256s': versions,
              'protected_roots': [str(p) for p in sources],
              'protected_roots_inherited_from': inherited_receipts,
              'whole_store_restored': True, 'runtime_data_restored': True,
              'schema_review': schema, 'third_party_paths_audited': False,
              'launch_safe': False, 'native_mint_tested': False, 'ready_for_production': False}
    snapshots.write_new(target.parent / 'managed-restore.json', snapshots.encoded(result))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('import', 'restore-store'))
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--manifest-sha256', required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--profile', choices=PROFILES, required=True)
    parser.add_argument('--bundle', nargs=2, metavar=('PATH', 'MANIFEST_SHA256'), action='append', default=[])
    parser.add_argument('--selected-bundle-sha256')
    parser.add_argument('--confirm-import', action='store_true')
    parser.add_argument('--confirm-restore', action='store_true')
    parser.add_argument('--protected-root', type=Path, action='append', default=[],
                        help='Earlier original/external root to preserve after copying a copy; repeat as needed')
    parser.add_argument('--max-bytes', type=int, default=snapshots.Limits.bytes)
    args = parser.parse_args()
    limits = snapshots.Limits(bytes=args.max_bytes)
    if args.operation == 'import':
        result = import_snapshot(args.snapshot, args.out, args.manifest_sha256, profile=args.profile,
            bundles=args.bundle, selected_bundle_sha256=args.selected_bundle_sha256,
            confirm_import=args.confirm_import, protected_roots=args.protected_root, limits=limits)
    else:
        result = restore_store(args.snapshot, args.out, args.manifest_sha256, profile=args.profile,
            confirm_restore=args.confirm_restore, protected_roots=args.protected_root, limits=limits)
    print(json.dumps(result, ensure_ascii=True, indent=2, sort_keys=True))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, TypeError, KeyError, RecursionError, RuntimeError, BadZipFile) as error:
        print('Managed runtime import/recovery refused: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
