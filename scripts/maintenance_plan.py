#!/usr/bin/env python3
"""Read-only, approval/hash-bound maintenance candidate planning; never apply, publish or call paid APIs."""
from __future__ import annotations
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
from urllib.parse import urlparse
from audit_game_json import game_path, parse_jsonc
from maintenance_inventory import safe_path

VERSION = 'maintenance-candidate-plan-v1'

def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()

def json_digest(value) -> str:
    return digest(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode())

def integer(value):
    if type(value) is not int or value < 0:
        raise ValueError('Nonnegative integer budget required')
    return value

def source_allowed(url: str) -> bool:
    if not isinstance(url, str):
        return False
    parsed = urlparse(url)
    if parsed.scheme != 'https' or parsed.username or parsed.password or parsed.port not in (None, 443):
        return False
    if parsed.hostname == 'wikiwiki.jp':
        return parsed.path.startswith('/kancolle/')
    if parsed.hostname == 'zekamashi.net':
        return parsed.path.startswith('/') and parsed.path != '/'
    if parsed.hostname == 'x.com':
        return re.fullmatch(r'/(KanColle_STAFF|C2_STAFF)/status/[0-9]+/?', parsed.path) is not None
    return False

def evidence_valid(evidence, now):
    if not isinstance(evidence, list) or not evidence:
        return False
    for item in evidence:
        if not isinstance(item, dict) or not source_allowed(item.get('url', '')):
            return False
        checked = datetime.fromisoformat(item.get('checked_at', ''))
        if checked.tzinfo is None or checked > now or now - checked > timedelta(days=30):
            return False
        if not isinstance(item.get('summary'), str) or not item['summary'].strip():
            return False
        if not re.fullmatch('[0-9a-f]{64}', item.get('evidence_sha256', '')):
            return False
    return True

def plan(root: Path, candidates: list, approvals: list, budget: dict, *, previous=None, now=None):
    root = root.resolve(strict=True)
    now = now or datetime.now(timezone.utc)
    if now.tzinfo is None: raise ValueError('Timezone-aware planning time required')
    limits = {key: integer(budget[key]) for key in ('candidates', 'input_tokens', 'output_tokens', 'micro_usd')}
    if not isinstance(candidates, list) or not isinstance(approvals, list):
        raise ValueError('Candidates and approvals must be lists')
    if len(candidates) > 2000 or len(approvals) > 2000: raise ValueError('Too many records')
    if previous is not None and (not isinstance(previous, dict) or previous.get('validator') != VERSION):
        raise ValueError('Incompatible checkpoint')
    cache = {row['identity']: row for row in (previous or {}).get('entries', []) if isinstance(row,dict)
             and row.get('status') not in ('pending_budget',) and 'identity' in row}
    approved = {}
    for approval in approvals:
        if not isinstance(approval, dict) or not isinstance(approval.get('identity'), str):
            raise ValueError('Invalid approval')
        if approval['identity'] in approved: raise ValueError('Duplicate approval')
        approved[approval['identity']] = approval
    output = {'validator': VERSION, 'generated_at': now.isoformat(), 'mode': 'plan_only',
              'applies_changes': False, 'calls_paid_apis': False,
              'cost_semantics': 'Declared conservative estimates reserved before candidate processing; not actual provider billing or token measurement.',
              'approval_semantics': 'Local review assertions bound to content hashes; not cryptographic publisher authentication.',
              'used': {key: 0 for key in limits}, 'limits': limits, 'entries': []}
    seen = set()
    for candidate in candidates:
        if not isinstance(candidate,dict): raise ValueError('Invalid candidate')
        row = {'path': candidate.get('path')}
        try:
            name = candidate['path']
            if not isinstance(name,str) or not game_path(name) or name in seen or PurePosixPath(name).as_posix() != name or '\\' in name or ':' in name:
                raise ValueError('Duplicate or unmanaged candidate')
            seen.add(name)
            # No removals or arbitrary runtime/private paths are supported by this planner.
            proposed = candidate['content']
            if not isinstance(proposed,str): raise ValueError('Full proposed JSON text required')
            raw = proposed.encode('utf-8')
            if len(raw) > 2 * 1024 * 1024: raise ValueError('Oversized proposal')
            target = root / name
            if target.exists() or target.is_symlink():
                old = digest(safe_path(root,name).read_bytes())
            else:
                # Validate existing parent chain even for a newly proposed file.
                if '..' in Path(name).parts or Path(name).is_absolute(): raise ValueError('Unsafe path')
                for parent in target.parents:
                    if parent == root: break
                    if parent.is_symlink(): raise ValueError('Symlinked parent')
                old = None
            if old != candidate.get('expected_old_sha256'): raise ValueError('Source changed since proposal')
            new = digest(raw)
            evidence = candidate.get('evidence', [])
            identity = json_digest({'path':name,'old':old,'new':new,'evidence':evidence})
            row.update(identity=identity, old_sha256=old, new_sha256=new)
            approval = approved.get(identity)
            approval_digest = json_digest(approval)
            row['approval_digest'] = approval_digest
            # Re-check current evidence age and safe source path on EVERY resumed invocation.
            evidence_ok = evidence_valid(evidence,now)
            row['evidence_valid'] = evidence_ok
            # Estimates are checkpoint inputs too: repairs must invalidate stale errors,
            # and increased declared costs must be checked against this run's budget.
            estimate_digest = json_digest(candidate.get('upper_bound'))
            row['estimate_digest'] = estimate_digest
            # Even a resume with a matching fingerprint must parse the CURRENT
            # proposal; checkpoint JSON is not trusted parser evidence.
            if old != new:
                parse_jsonc(proposed)
            previous_row = cache.get(identity)
            # Checkpoints are user-provided JSON, not signed execution receipts.
            # They may only defer an UNAPPROVED proposal. A cached 'approved_candidate'
            # or 'unchanged' assertion must never skip validation or the current
            # approval/budget/JSON gates, even if all its hashes were forged.
            if (previous_row and not approval and previous_row.get('status') in
                    ('requires_approval', 'requires_source_review', 'error')
                    and previous_row.get('approval_digest') == approval_digest
                    and previous_row.get('evidence_valid') == evidence_ok
                    and previous_row.get('estimate_digest') == estimate_digest):
                row.update(previous_row, reused=True)
            elif old == new:
                row.update(status='unchanged', evidence_valid=evidence_ok)
            else:
                estimates = candidate.get('upper_bound')
                if not isinstance(estimates,dict): raise ValueError('Explicit cost/token upper bounds required')
                cost = {'candidates':1, **{k:integer(estimates[k]) for k in ('input_tokens','output_tokens','micro_usd')}}
                if any(cost[k] > limits[k]-output['used'][k] for k in limits):
                    row.update(status='pending_budget', evidence_valid=evidence_ok)
                else:
                    for k in limits: output['used'][k] += cost[k]
                    parse_jsonc(proposed) # Strict syntax with legacy comments; semantic validation is a separate Java gate.
                    row['evidence_valid'] = evidence_ok
                    if not evidence_ok: row['status'] = 'requires_source_review'
                    elif not approval or approval.get('decision') != 'approve' or not isinstance(approval.get('reviewer'),str) or not approval['reviewer'].strip():
                        row['status'] = 'requires_approval'
                    else:
                        row['status'] = 'approved_candidate'
                        row['java_consumer_validation_required'] = True
        except (KeyError, TypeError, ValueError, OSError, RecursionError) as error:
            row.update(status='error', error=type(error).__name__, reason=str(error))
        output['entries'].append(row)
    output['counts'] = {name:sum(row['status']==name for row in output['entries']) for name in
                        ('unchanged','approved_candidate','requires_source_review','requires_approval','pending_budget','error')}
    output['all_examined'] = output['counts']['pending_budget'] == 0 and bool(candidates)
    output['ready_for_isolated_validation'] = bool(candidates) and all(row['status'] in ('approved_candidate','unchanged') for row in output['entries'])
    return output

def read_json(path):
    path = Path(path)
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('Unsafe/oversized input report')
    return json.loads(path.read_text(encoding='utf-8'))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('.'))
    parser.add_argument('--candidates',required=True)
    parser.add_argument('--approvals')
    parser.add_argument('--previous')
    parser.add_argument('--max-candidates',type=int,default=20)
    parser.add_argument('--input-tokens',type=int,default=0)
    parser.add_argument('--output-tokens',type=int,default=0)
    parser.add_argument('--micro-usd',type=int,default=0)
    args=parser.parse_args()
    try:
        result=plan(args.root,read_json(args.candidates),read_json(args.approvals) if args.approvals else [],
                    {'candidates':args.max_candidates,'input_tokens':args.input_tokens,'output_tokens':args.output_tokens,'micro_usd':args.micro_usd},
                    previous=read_json(args.previous) if args.previous else None)
        print(json.dumps(result,ensure_ascii=False,indent=2,sort_keys=True))
        return 0 if result['ready_for_isolated_validation'] else 2
    except (ValueError,TypeError,OSError) as error:
        print('Planning failed: '+str(error),file=sys.stderr);return 1

if __name__=='__main__':raise SystemExit(main())
