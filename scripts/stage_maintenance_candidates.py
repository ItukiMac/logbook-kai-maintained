#!/usr/bin/env python3
"""Revalidate approvals and copy a candidate data pack to a NEW isolated directory.

Never changes the source, production, active pointer, Git refs or releases.
A stage is not an installation. Java consumer validation remains mandatory.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from audit_game_json import game_path, parse_jsonc
from maintenance_inventory import safe_path
from maintenance_plan import plan, read_json
from build_feature_distribution import new_directory
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from build_data_pack import build as build_pack, CATEGORIES


def digest(data):
    return hashlib.sha256(data).hexdigest()


def tracked(root):
    return [p.decode('utf8') for p in subprocess.check_output(
        ['git', '-C', str(root), 'ls-files', '-z']).split(b'\0') if p]


def stage(root: Path, output: Path, candidates, approvals, budget, *, now=None):
    root = root.absolute()
    output = output.absolute()
    if '..' in root.parts or '..' in output.parts:
        raise ValueError('Parent-traversal paths are not staging boundaries')
    if any(p.is_symlink() for p in (root, *root.parents)):
        raise ValueError('Symlinked source root')
    if output.is_relative_to(root) or root.is_relative_to(output):
        raise ValueError('Stage and source must be separate directory trees')
    # No cached approved rows are accepted: recalculate from all current inputs.
    report = plan(root, candidates, approvals, budget, now=now)
    if not report['ready_for_isolated_validation']:
        raise ValueError('Every changed candidate needs valid evidence, budget and explicit approval')
    revision = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    paths = sorted(p for p in tracked(root) if game_path(p))
    if not paths:
        raise ValueError('Empty managed definition set')
    # This first implementation supports replacing tracked definitions, not removals/additions.
    if any(c['path'] not in paths for c in candidates):
        raise ValueError('Untracked definitions require a separate inventory change')
    original = {p: safe_path(root, p).read_bytes() for p in paths}
    if sum(map(len, original.values())) > 64 * 1024 * 1024:
        raise ValueError('Managed definition set exceeds 64 MiB')
    for data in original.values():
        parse_jsonc(data.decode('utf8'))
    for c in candidates:
        if digest(original[c['path']]) != c.get('expected_old_sha256'):
            raise ValueError('Source changed after planning')
    prefix = 'src/main/resources/logbook/'
    if set(p[len(prefix):].split('/')[0] for p in paths) != set(CATEGORIES):
        raise ValueError('Incomplete managed category inventory')
    replacements = {c['path']: c['content'].encode('utf8') for c in candidates}
    # All validation above occurs before any output creation.
    new_directory(output)
    for folder in ('original', 'candidate'):
        for category in CATEGORIES:
            (output / folder / category).mkdir(parents=True, exist_ok=False)
    for name, data in original.items():
        relative = name[len(prefix):]
        for folder, payload in (('original', data), ('candidate', replacements.get(name, data))):
            target = output / folder / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as file:
                file.write(payload)
    pack = build_pack(output / 'candidate', output / 'candidate-data-pack.zip', revision)
    # Re-read safe paths after preparation. Failed stages have no completion manifest.
    if any(safe_path(root, name).read_bytes() != data for name, data in original.items()):
        raise ValueError('Source changed during staging; no completion manifest written')
    result = {'format': 'logbook-isolated-candidate-v1', 'source_revision': revision,
              'source_files': {p: digest(b) for p, b in original.items()},
              'candidate_files': {p: digest(replacements.get(p, b)) for p, b in original.items()},
              'candidate_pack_sha256': pack['sha256'], 'definition_count': len(paths),
              'plan': report, 'production_applied': False, 'publish_allowed': False,
              'java_consumer_validation_required': True,
              'approval_scope': 'Local review assertions only; staging is not production authorization.'}
    with (output / 'stage.json').open('x', encoding='utf8') as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
        file.write('\n')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, default=Path('.'))
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--candidates', required=True)
    p.add_argument('--approvals', required=True)
    p.add_argument('--max-candidates', type=int, default=20)
    for key in ('input-tokens', 'output-tokens', 'micro-usd'):
        p.add_argument('--' + key, type=int, default=0)
    a = p.parse_args()
    budget = dict(candidates=a.max_candidates, input_tokens=a.input_tokens,
                  output_tokens=a.output_tokens, micro_usd=a.micro_usd)
    result = stage(a.root, a.out, read_json(a.candidates), read_json(a.approvals), budget)
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
