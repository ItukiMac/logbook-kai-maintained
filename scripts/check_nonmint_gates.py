#!/usr/bin/env python3
"""Validate a local progress ledger, not remote evidence or permission to deploy.

Exit 0 means a well-formed ledger; --require-nonmint-complete additionally exits
1 while independent work remains. Recorded evidence is a human-reviewed local
assertion, NOT authenticated CI results. This command never accesses a network,
changes state, approves publication, or calls a paid provider.
"""
from __future__ import annotations
import argparse
from datetime import date, datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
from urllib.parse import urlsplit

SCHEMA = 'logbook-mint-independent-gates-v2'
KEYS = ('runtime-data-cross-schema-migration', 'external-path-rewrite',
        'seasonal-rule-lifecycle', 'seasonal-840-841-843', 'arrival-906-954',
        'arrival-restart-recovery', 'retreat-survivor-evidence', 'expedition-A5-conflict',
        'quest-436-437-uncertainty', 'fleet-classification-roster', 'quest-acceptance-reset',
        'editor-arrival-controls', 'feature-independent-versions', 'maintenance-paid-provider',
        'mint-test-bundle', 'native-mint-gui-ime', 'native-game-chrome-klb-kqs',
        'native-newdata-migration', 'publication')
BASELINE = {f'R11-{n:02d}': (key, 'independent' if n <= 15 else
            'native' if n <= 18 else 'approval') for n, key in enumerate(KEYS, 1)}
STAGES = {'implementation': {'pending', 'partial', 'done', 'not_applicable'},
          'verification': {'pending', 'blocked', 'passed'},
          'integration': {'pending', 'pr_open', 'merged', 'not_applicable'},
          'native_validation': {'pending', 'passed', 'not_applicable'}}
STATUSES = {'open', 'in_progress', 'blocked', 'complete'}
SCOPES = {'independent', 'native', 'approval'}
SHA = re.compile(r'[0-9a-f]{40}')


def text(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError('Missing/non-string ' + label)
    return value


def integer(value, label):
    if type(value) is not int or value < 0:
        raise ValueError('Invalid nonnegative integer: ' + label)
    return value


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key: ' + key)
        result[key] = value
    return result


def evidence_file(root: Path, value: str) -> Path:
    text(value, 'evidence path')
    relative = PurePosixPath(value)
    if (not value.startswith('docs/maintenance/') or relative.is_absolute()
            or '..' in relative.parts or relative.as_posix() != value
            or '\\' in value or ':' in value):
        raise ValueError('Unsafe evidence path')
    path = root / value
    if not path.is_file() or any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('Missing or symlinked evidence: ' + value)
    return path


def scope_digest(task):
    # Editing acceptance criteria or reclassifying work invalidates its receipt.
    value = {k: task[k] for k in ('id', 'key', 'scope', 'acceptance', 'mint_independent')}
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':')).encode()).hexdigest()


def reference(value):
    parsed = urlsplit(text(value, 'evidence reference'))
    if (parsed.scheme != 'https' or not parsed.hostname or parsed.username
            or parsed.password or parsed.port not in (None, 443)):
        raise ValueError('Invalid HTTPS evidence reference')


def check_completion(task, root, as_of):
    receipt = task.get('completion')
    if not isinstance(receipt, dict) or receipt.get('scope_sha256') != scope_digest(task):
        raise ValueError('Missing completion receipt or changed acceptance scope')
    text(receipt.get('checked_by'), 'evidence reviewer')
    timestamp = datetime.fromisoformat(text(receipt.get('checked_at'), 'verification time'))
    if timestamp.tzinfo is None or timestamp.date() > as_of:
        raise ValueError('Invalid verification time')
    refs = receipt.get('references')
    if not isinstance(refs, list) or not refs:
        raise ValueError('Completion needs evidence references')
    for value in refs:
        reference(value)
    if task['scope'] == 'independent':
        if task['stages'] != dict(implementation='done', verification='passed',
                                 integration='merged', native_validation='not_applicable'):
            raise ValueError('Independent completion needs implementation, tests and merge')
        for key in ('source_revision', 'reviewed_revision', 'merge_revision'):
            value = receipt.get(key)
            if not isinstance(value, str) or SHA.fullmatch(value) is None:
                raise ValueError('Completion needs exact commit: ' + key)
        if receipt['source_revision'] != receipt['reviewed_revision']:
            raise ValueError('Review does not match tested source')
        if integer(receipt.get('pr_number'), 'PR') == 0:
            raise ValueError('Invalid PR')
        runs = receipt.get('workflow_runs')
        if not isinstance(runs, list) or not runs or any(type(r) is not int or r <= 0 for r in runs):
            raise ValueError('Completion needs successful workflow references')
    elif task['scope'] == 'native':
        if task['stages']['native_validation'] != 'passed' or task['stages']['verification'] != 'passed':
            raise ValueError('CI cannot replace native validation')
        evidence_file(root, receipt.get('native_record'))
    else:
        if receipt.get('explicit_user_approval') is not True or task['stages']['verification'] != 'passed':
            raise ValueError('Manual approval is a separate recorded gate')


def validate(doc: dict, root: Path) -> dict:
    if not isinstance(doc, dict) or doc.get('schema') != SCHEMA:
        raise ValueError('Invalid ledger schema; v1 is not silently upgraded')
    as_of = date.fromisoformat(text(doc.get('as_of'), 'ledger date'))
    basis = doc.get('basis')
    if not isinstance(basis, dict):
        raise ValueError('Missing basis')
    total = integer(basis.get('rules_total'), 'total rules')
    first = integer(basis.get('rules_first_pass'), 'first-pass rules')
    whole = integer(basis.get('rules_whole_verified'), 'whole rules')
    if not total or not whole <= first <= total:
        raise ValueError('Invalid rule coverage')
    tasks = doc.get('tasks')
    if not isinstance(tasks, list) or not tasks or len(tasks) > 2000:
        raise ValueError('Empty or oversized task ledger')
    seen, keys = {}, set()
    counts = {s: {'open': 0, 'complete': 0} for s in SCOPES}
    for task in tasks:
        if not isinstance(task, dict):
            raise ValueError('Invalid task')
        name = text(task.get('id'), 'task ID')
        key = text(task.get('key'), 'task key')
        scope, status = task.get('scope'), task.get('status')
        if name in seen or key in keys or not re.fullmatch(r'[A-Z][A-Z0-9-]*-[0-9]+', name):
            raise ValueError('Duplicate or invalid task identity')
        if not isinstance(scope, str) or scope not in SCOPES or not isinstance(status, str) or status not in STATUSES:
            raise ValueError('Unknown scope/status')
        if name in BASELINE and BASELINE[name] != (key, scope):
            raise ValueError('Original task identity/scope must be retained')
        if task.get('mint_independent') is not (scope == 'independent'):
            raise ValueError('Incorrect Mint classification')
        seen[name] = task
        keys.add(key)
        text(task.get('title'), 'task title')
        text(task.get('acceptance'), 'acceptance criteria')
        evidence_file(root, task.get('evidence'))
        stages = task.get('stages')
        if (not isinstance(stages, dict) or stages.keys() != STAGES.keys()
                or any(not isinstance(stages[k], str) or stages[k] not in values for k, values in STAGES.items())):
            raise ValueError('Invalid implementation/test/merge/native stages')
        if scope != 'native' and stages['native_validation'] != 'not_applicable':
            raise ValueError('Native testing must remain a separate task')
        complete = status == 'complete'
        if task.get('verified_complete') is not complete:
            raise ValueError('Completion flag disagrees with status')
        if complete:
            check_completion(task, root, as_of)
            if key == 'quest-acceptance-reset' and whole != total:
                raise ValueError('All-rule task requires whole-rule coverage')
        else:
            text(task.get('remaining'), 'unfinished scope')
            if task.get('completion') is not None:
                raise ValueError('Open task cannot carry a completion receipt')
        counts[scope]['complete' if complete else 'open'] += 1
    if not set(BASELINE).issubset(seen):
        raise ValueError('Original tasks cannot be dropped; add/split without deleting their scope')
    return {'schema': SCHEMA, 'as_of': doc['as_of'], 'tasks': len(tasks),
            'mint_independent_open': counts['independent']['open'],
            'mint_independent_complete': counts['independent']['complete'],
            'native_mint_open': counts['native']['open'], 'manual_approval_open': counts['approval']['open'],
            'nonmint_complete': counts['independent']['open'] == 0,
            'release_ready': all(c['open'] == 0 for c in counts.values()),
            'auto_publish_authorized': False, 'whole_rules_verified': whole,
            'evidence_semantics': 'Local reviewed assertions; remote CI/merge/approval must be verified separately.'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--ledger', default='docs/maintenance/non-mint-completion-gates.json')
    parser.add_argument('--require-nonmint-complete', action='store_true')
    args = parser.parse_args(argv)
    try:
        root = args.root.absolute()
        if '..' in root.parts or any(p.is_symlink() for p in (root, *root.parents)):
            raise ValueError('Unsafe checkout root')
        ledger = evidence_file(root, args.ledger)
        if ledger.stat().st_size > 2 * 1024 * 1024:
            raise ValueError('Oversized ledger')
        def reject(value):
            raise ValueError('Non-JSON numeric constant: ' + value)
        doc = json.loads(ledger.read_text(encoding='utf-8'), object_pairs_hook=unique, parse_constant=reject)
        report = validate(doc, root)
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))
        return 1 if args.require_nonmint_complete and not report['nonmint_complete'] else 0
    except (ValueError, OSError, TypeError, KeyError, RecursionError) as error:
        print('GATE_LEDGER_INVALID: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
