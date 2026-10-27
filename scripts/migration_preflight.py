#!/usr/bin/env python3
"""Read-only deployment preflight for a SHA-pinned offline runtime snapshot.

Never dereferences paths outside the snapshot or modifies the restored data.
A passing byte copy is NOT permission to launch, migrate schema or deploy.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
from runtime_snapshot import verify, inventory, read_bytes, unique, checked, Limits


def preflight(snapshot_root, manifest_hash, *, restored_copy=None, limits=Limits()):
    snapshot_root = checked(Path(snapshot_root))
    manifest = verify(snapshot_root, manifest_hash, limits)
    blockers = []
    records = manifest['entries']
    review = manifest.get('path_review')
    if not isinstance(review, dict) or review.get('config_unreadable'):
        blockers.append('config_unreadable_or_review_missing')
        references = []
    else:
        references = review.get('references', [])
        if not isinstance(references, list):
            blockers.append('invalid_reference_inventory');references=[]
        if not review.get('third_party_paths_audited') is False:
            blockers.append('third_party_path_scope_unknown')
    issues = []
    for item in references:
        if not isinstance(item, dict) or not isinstance(item.get('field'), str):
            blockers.append('invalid_path_record');continue
        status = item.get('status')
        raw = item.get('value')
        if status != 'relative_within_root':
            issues.append({'field': item['field'], 'status': status or 'unknown',
                           'action': 'review_and_copy_external_data_or_reconfigure_in_disposable_clone'})
            continue
        # A path being syntactically within the copy is not proof that it exists;
        # inventory only, no filesystem traversal of private/external paths.
        if not isinstance(raw, str) or not raw or '\\' in raw or '..' in Path(raw).parts or Path(raw).is_absolute():
            issues.append({'field': item['field'], 'status': 'unresolved_relative_reference'})
            continue
        relative = Path(raw).as_posix()
        if relative not in records and not any(name.startswith(relative.rstrip('/') + '/') for name in records):
            issues.append({'field': item['field'], 'status': 'missing_within_snapshot'})
    if not manifest.get('application_stopped_confirmed_by_caller'):
        blockers.append('no_stop_confirmation')
    if manifest.get('schema_converted') is not False:
        blockers.append('unexpected_schema_conversion')
    if restored_copy is not None:
        restore_dir = checked(Path(restored_copy))
        receipt = json.loads(read_bytes(restore_dir/'restore.json', 128*1024), object_pairs_hook=unique)
        if not isinstance(receipt,dict) or (receipt.get('format')!='logbook-runtime-restore-v1'
            or receipt.get('snapshot_manifest_sha256') != manifest_hash
            or receipt.get('restored_to') != str(restore_dir/'data')
            or receipt.get('bytes_verified') is not True
            or receipt.get('launch_safe') is not False):
            raise ValueError('Copy provenance or state does not match the pinned snapshot')
        if inventory(restore_dir/'data',limits)!=records:
            raise ValueError('Restore no longer equals snapshot inventory')
    return {'format':'logbook-migration-preflight-v1','snapshot_verified':True,
            'restore_copy_verified':restored_copy is not None,
            'items':len(records),'path_issues':issues,'blockers':sorted(set(blockers)),
            'third_party_paths_audited':False,
            'cross_schema_migration_verified':False,
            'native_mint_tested':False,'ready_for_launch':False,
            'ready_for_production':False,
            'next_action':'resolve path issues in a separate disposable clone, then test startup/GUI/traffic on Mint'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot',type=Path,required=True)
    parser.add_argument('--manifest-sha256',required=True)
    parser.add_argument('--restored-copy',type=Path)
    a=parser.parse_args()
    try:
        report=preflight(a.snapshot,a.manifest_sha256,restored_copy=a.restored_copy)
        print(json.dumps(report,ensure_ascii=False,indent=2))
        return 2 if report['path_issues'] or report['blockers'] else 0
    except (OSError,ValueError,TypeError,KeyError) as e:
        print('Migration preflight refused: '+str(e),file=__import__('sys').stderr)
        return 1
if __name__=='__main__':raise SystemExit(main())
