#!/usr/bin/env python3
"""Offline maintenance gate: audit -> plan -> optional explicitly approved staging.

Never reads personal application state, modifies tracked definitions, publishes,
launches the application, calls an LLM or bills a provider. All proposals must
be supplied explicitly, and EVERY staging attempt revalidates them from scratch.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from audit_game_json import audit, game_path
from maintenance_plan import plan, read_json
from maintenance_inventory import safe_path
from stage_maintenance_candidates import stage

FORMAT = 'logbook-maintenance-offline-gate-v1'
BUDGET_KEYS = ('candidates', 'input_tokens', 'output_tokens', 'micro_usd')


def tracked(root: Path) -> list[str]:
    entries = subprocess.check_output(['git', '-C', str(root), 'ls-files', '-z'])
    return [name.decode('utf-8') for name in entries.split(b'\0') if name]


def run_gate(root: Path, candidates, approvals, budget, *, output=None, now=None):
    """Report all gates. Stage only to a new disjoint directory when approved."""
    root = Path(root).absolute()
    if '..' in root.parts or root.is_symlink():
        raise ValueError('Unsafe source root')
    root = root.resolve(strict=True)
    paths = tracked(root)
    audit_result = audit(root, paths)
    if not audit_result['complete'] or audit_result['counts']['errors']:
        raise ValueError('Managed JSON validation failed; refusing candidate processing')
    # A scope mismatch must not silently permit a partial source checkout.
    actual = sum(1 for p in paths if game_path(p))
    if actual == 0 or audit_result['counts']['validated'] != actual:
        raise ValueError('Incomplete managed source scope')
    if not isinstance(candidates, list) or not candidates:
        raise ValueError('At least one explicit candidate is required')
    now = now or datetime.now(timezone.utc)
    prepared = plan(root, candidates, approvals, budget, now=now)  # no stale checkpoints
    revision = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    manifest = {
        'format': FORMAT, 'revision': revision,
        'tracked_definition_count': actual,
        'source_audit_complete': True,
        'proposed_count': len(candidates),
        'plan': prepared,
        'mode': 'explicit_isolated_staging' if output is not None else 'read_only',
        'approved_for_production': False,
        'publication_allowed': False,
        'paid_provider_calls': 0,
        'java_consumer_validation_required': True,
        'source_modified': False,
        'stage_complete': False,
    }
    if output is not None:
        if not prepared['ready_for_isolated_validation']:
            raise ValueError('A candidate failed evidence, approval, or budget checks; stage not created')
        stage_result = stage(root, Path(output), candidates, approvals, budget, now=now)
        if stage_result['source_revision'] != revision or stage_result['plan']['ready_for_isolated_validation'] is not True:
            raise ValueError('Stage validation changed; no completion receipt for this run')
        manifest['stage_complete'] = True
        manifest['stage_pack_sha256'] = stage_result['candidate_pack_sha256']
        manifest['stage_directory'] = str(output)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('.'))
    parser.add_argument('--candidates', type=Path, required=True)
    parser.add_argument('--approvals', type=Path)
    parser.add_argument('--stage-to', type=Path, help='Explicit opt-in, must be a NEW directory outside root')
    parser.add_argument('--max-candidates', type=int, default=20)
    parser.add_argument('--input-tokens', type=int, default=0)
    parser.add_argument('--output-tokens', type=int, default=0)
    parser.add_argument('--micro-usd', type=int, default=0)
    args = parser.parse_args()
    try:
        candidates = read_json(args.candidates)
        approvals = read_json(args.approvals) if args.approvals else []
        budget = {'candidates': args.max_candidates, 'input_tokens': args.input_tokens,
                  'output_tokens': args.output_tokens, 'micro_usd': args.micro_usd}
        result = run_gate(args.root, candidates, approvals, budget, output=args.stage_to)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if result['plan']['ready_for_isolated_validation'] else 2
    except (ValueError, TypeError, OSError, subprocess.CalledProcessError) as error:
        print(f'Offline gate refused: {type(error).__name__}: {error}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
