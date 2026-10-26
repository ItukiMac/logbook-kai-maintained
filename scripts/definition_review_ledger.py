#!/usr/bin/env python3
"""Read-only, hash-bound FIELD comparison. Never claims whole game-rule correctness."""
from __future__ import annotations
import argparse
from collections import Counter
from datetime import datetime, timezone, timedelta
import hashlib
import json
from pathlib import Path
import subprocess
from audit_game_json import parse_jsonc
from maintenance_inventory import safe_path
from maintenance_plan import source_allowed

PREFIX = 'src/main/resources/logbook/'
SCOPES = ('quest/', 'eventquest/', 'mission/')


def selected(path):
    return path.startswith(PREFIX) and path[len(PREFIX):].startswith(SCOPES) and path.endswith('.json')


def nodes(value):
    if isinstance(value, dict):
        yield value
        for child in value.get('conditions', []):
            yield from nodes(child)


def extract(doc, field):
    if field == 'flagship_min_level':
        values = {n['level'] for n in nodes(doc) if n.get('order') == 1 and 'level' in n}
        return sorted(values) if values else None
    if field.startswith('fleet:'):
        values = {n['value'] for n in nodes(doc) if n.get('type') == '艦隊'
                  and n.get('count_type') == field.split(':', 1)[1] and 'value' in n}
        return sorted(values) if values else None
    if field == 'quest_reset':
        return doc.get('resetType')
    if field == 'quest_missions':
        return sorted([[n.get('missions'), n.get('count')] for n in doc.get('conditions', [])], key=str)
    raise ValueError('Unimplemented field comparator: ' + field)


def review(root, paths, facts, *, now=None):
    now = now or datetime.now(timezone.utc)
    if facts.get('format') != 'logbook-field-review-v1':
        raise ValueError('Unsupported review facts')
    when = datetime.fromisoformat(facts['checked_at'])
    fresh = when.tzinfo is not None and timedelta(0) <= now - when <= timedelta(days=30)
    by_path = {}
    for item in facts['definitions']:
        if item['path'] in by_path or not selected(item['path']):
            raise ValueError('Duplicate/unmanaged review record')
        if not item.get('fields'):
            raise ValueError('Empty field review')
        for field in item['fields']:
            sources = field.get('observations', [])
            if not sources or any(not source_allowed(x.get('url')) for x in sources):
                raise ValueError('Missing/unsafe field source')
        by_path[item['path']] = item
    entries = []
    inventory = sorted(set(p for p in paths if selected(p)))
    for name in inventory:
        raw = safe_path(root, name).read_bytes()
        h = hashlib.sha256(raw).hexdigest()
        item = by_path.get(name)
        row = {'path': name, 'sha256': h, 'status': 'unreviewed', 'fields': [],
               'whole_rule_verified': False}
        if item:
            doc = parse_jsonc(raw.decode('utf8'))
            stale = item.get('source_sha256') != h or not fresh
            for field in item['fields']:
                values = [x['value'] for x in field['observations']]
                actual = extract(doc, field['field'])
                if any(v != values[0] for v in values[1:]):
                    status = 'source_conflict'
                elif actual == values[0]:
                    status = 'field_match'
                elif field['field'] == 'flagship_min_level' and actual is None and values[0] == [1]:
                    status = 'implicit_level_one_not_explicitly_encoded'
                else:
                    status = 'difference'
                row['fields'].append({'field': field['field'], 'actual': actual,
                                      'status': status, 'observations': field['observations']})
            statuses = {x['status'] for x in row['fields']}
            row['status'] = ('stale_review' if stale else 'source_conflict' if 'source_conflict' in statuses
                             else 'needs_correction_review' if 'difference' in statuses else 'partially_reviewed')
        entries.append(row)
    # Orphaned/removed facts remain visible; a shortened inventory is not "complete".
    orphaned = sorted(set(by_path) - set(inventory))
    return {'format': 'logbook-review-ledger-v1', 'entries': entries,
            'counts': dict(Counter(r['status'] for r in entries)), 'definitions': len(entries),
            'field_comparisons': sum(len(r['fields']) for r in entries),
            'orphaned_review_records': orphaned, 'whole_rule_verified': 0,
            'whole_rule_pending': len(entries), 'complete': False,
            'scope': 'Only listed literal fields; logical alternatives, hull/equipment substitutions, '
                     'aggregation, success type, prerequisites and live behaviour remain separate reviews.',
            'freshness': 'Retrieval time is not the source publication/update date.'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, default=Path('.'))
    p.add_argument('--facts', type=Path, required=True)
    a = p.parse_args()
    paths = [p.decode() for p in subprocess.check_output(['git', '-C', str(a.root), 'ls-files', '-z']).split(b'\0') if p]
    print(json.dumps(review(a.root, paths, json.loads(a.facts.read_text())), ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
