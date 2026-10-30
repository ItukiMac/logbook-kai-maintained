#!/usr/bin/env python3
"""Hash-bound source proposals into new workbench checkouts; never publish or activate."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys
import maintenance_workbench as w

FORMAT = 'maintenance-source-candidate-v1'
ORDINARY = ('docs/', 'src/test/', 'scripts/tests/', 'tools/tests/')
SOURCE = ('src/', 'scripts/', 'tools/', 'docs/', '.github/')


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False).encode()


def prepare(checkpoint, expected, changes):
    report = w.verify(checkpoint, expected)
    if not isinstance(changes, list) or not 1 <= len(changes) <= 32:
        raise ValueError('Explicit bounded changes required')
    old = {row['path']: row for row in report['files']}
    rows, seen, total = [], set(), 0
    for change in changes:
        if not isinstance(change, dict) or set(change) != {'path', 'expected_old_sha256', 'content'}:
            raise ValueError('Full source text and old hash required')
        name = w.relative(change['path'])
        if not name.startswith(SOURCE) or name in seen:
            raise ValueError('Duplicate or non-source path')
        seen.add(name)
        if not isinstance(change['content'], str) or '\0' in change['content']:
            raise ValueError('Text source only')
        raw = change['content'].encode('utf8')
        total += len(raw)
        if len(raw) > 2 * 1024 * 1024 or total > 8 * 1024 * 1024:
            raise ValueError('Proposal budget exceeded')
        before = old.get(name, {}).get('sha256')
        if before != change['expected_old_sha256'] or (before is not None and w.HEX.fullmatch(before) is None):
            raise ValueError('Stale source proposal')
        if before == w.digest(raw):
            raise ValueError('No-op proposal')
        # Tests and documentation may be automatically staged. Runtime, build,
        # workflows and tooling always require content-bound explicit approval.
        risk = 'ordinary' if name.startswith(ORDINARY) else 'high'
        rows.append({**change, 'sha256': w.digest(raw), 'risk': risk})
    names = set(old) | seen
    for name in seen:
        if any(str(parent) in names for parent in Path(name).parents if str(parent) != '.'):
            raise ValueError('Source file/directory collision')
    payload = {'format': FORMAT, 'checkpoint_sha256': expected, 'changes': sorted(rows, key=lambda x: x['path'])}
    return {**payload, 'identity': w.digest(canonical(payload)),
        'high_risk': any(row['risk'] == 'high' for row in rows),
        'publication_allowed': False, 'paid_provider_calls': 0}


def stage(checkpoint, expected, changes, output, *, approval=None, protected=()):
    proposal = prepare(checkpoint, expected, changes)
    if proposal['high_risk'] and (not isinstance(approval, dict)
            or set(approval) != {'identity', 'decision', 'reviewer'}
            or approval['identity'] != proposal['identity'] or approval['decision'] != 'approve'
            or not isinstance(approval['reviewer'], str) or not approval['reviewer'].strip()):
        raise ValueError('Patch-specific explicit approval required for high-risk source')
    # Validate every gate before any output is created. Reverify before restore.
    w.restore(checkpoint, expected, output, protected=protected, initialize_git=True)
    root = w.checked_path(output)
    for row in proposal['changes']:
        target = w.checked_path(root / row['path'])
        if target.exists():
            if w.digest(w.bounded(target, w.MAX_FILE)) != row['expected_old_sha256']:
                raise ValueError('Restored source differs; partial candidate retained')
            # This is solely a new disposable source checkout, never original inputs.
            target.write_bytes(row['content'].encode('utf8'))
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            w.write_new(target, row['content'].encode('utf8'))
    w.git(root, 'add', '--force', '--', *[row['path'] for row in proposal['changes']])
    actual = set(w.git(root, 'diff', '--cached', '--name-only', '-z').decode().split('\0')) - {''}
    if actual != {row['path'] for row in proposal['changes']}:
        raise ValueError('Unexpected candidate scope; partial checkout retained')
    w.git(root, '-c', 'user.name=Maintenance Candidate', '-c', 'user.email=candidate@localhost',
          'commit', '-m', 'Isolated source candidate ' + proposal['identity'])
    for row in proposal['changes']:
        if w.digest(w.read_source(root, row['path'])) != row['sha256']:
            raise ValueError('Candidate changed; no receipt')
    return {'format': FORMAT, 'identity': proposal['identity'],
        'candidate_revision': w.git(root, 'rev-parse', 'HEAD').decode().strip(),
        'checkpoint_sha256': expected, 'toolchain': w.verify(checkpoint, expected)['toolchain'], 'paths': sorted(actual),
        'high_risk': proposal['high_risk'], 'tests_executed': False,
        'paid_provider_calls': 0, 'publication_allowed': False,
        'original_source_unchanged': True}


def execute_check(command, root):
    return subprocess.run(command, cwd=root, check=True, timeout=1200)


def verify_candidate(root, receipt, *, isolated_runner=False):
    # A flag is an operator assertion, not a security sandbox. Provision the runner
    # separately with no production credentials and read-only repository access.
    if not isolated_runner:
        raise ValueError('Separately provisioned isolated runner required')
    root = w.checked_path(root)
    if w.git(root, 'rev-parse', 'HEAD').decode().strip() != receipt['candidate_revision']:
        raise ValueError('Candidate revision changed')
    if w.git(root, 'status', '--porcelain', '--untracked-files=all').strip():
        raise ValueError('Candidate is dirty')
    w.check_environment({'toolchain': receipt['toolchain']}, w.measure_environment())
    # Fixed profiles; proposal content can never inject shell commands or argv.
    execute_check(['bash', 'scripts/verify_r3.sh'], root)
    if w.git(root, 'diff', '--name-only', 'HEAD', '--').strip():
        raise ValueError('Verification mutated tracked source')
    return {**receipt, 'tests_executed': True, 'fixed_profiles': ['canonical-r3-complete-pack-gui-python-and-packaged-probes'],
            'isolated_runner_operator_assertion': True}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--checkpoint', type=Path, required=True)
    p.add_argument('--sha256', required=True)
    p.add_argument('--changes', type=Path, required=True)
    p.add_argument('--stage-to', type=Path)
    p.add_argument('--approval', type=Path)
    a = p.parse_args()
    try:
        changes = json.loads(w.bounded(a.changes, 8 * 1024 * 1024), object_pairs_hook=w.unique)
        if a.stage_to:
            approval = json.loads(w.bounded(a.approval, 8192), object_pairs_hook=w.unique) if a.approval else None
            result = stage(a.checkpoint, a.sha256, changes, a.stage_to, approval=approval)
        else:
            result = prepare(a.checkpoint, a.sha256, changes)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, TypeError, KeyError, OSError, subprocess.SubprocessError) as error:
        print('Source candidate refused: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
