#!/usr/bin/env python3
"""Read-only edition diagnostics; matching a calendar year never activates a quest rule."""
import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
from maintenance_inventory import safe_path

REGISTRY = 'docs/maintenance/seasonal-editions-20261026.json'

def audit(root: Path, as_of: date, observations=None):
    root = root.resolve(strict=True)
    if observations is None:
        observations = json.loads(safe_path(root, REGISTRY).read_text(encoding='utf8'))
    if observations.get('format') != 'logbook-seasonal-edition-observations-v1':
        raise ValueError('Unsupported observation registry')
    rows = observations.get('entries')
    if not isinstance(rows, list) or not rows: raise ValueError('Empty edition observations')
    report = {'as_of': as_of.isoformat(), 'read_only': True, 'active_selection_supported': False,
              'entries': [], 'certified_active_rules': 0}
    seen = set()
    for record in rows:
        item = {'id': record.get('id'), 'path': record.get('path'), 'active': None}
        try:
            name = record['path']; year = record['edition_year']
            if name in seen: raise ValueError('Duplicate definition observation')
            seen.add(name)
            if type(year) is not int or year < 2013 or year > 9999: raise ValueError('Invalid explicit edition year')
            if record.get('lifecycle_verified') is not False: raise ValueError('Registry cannot certify activation')
            if not (name.startswith('src/main/resources/logbook/quest/') or name.startswith('src/main/resources/logbook/eventquest/')):
                raise ValueError('Unmanaged rule path')
            raw = safe_path(root, name).read_bytes()
            item.update(edition_year=year, source_edition=record['source_edition'])
            if hashlib.sha256(raw).hexdigest() != record['definition_sha256']:
                item['status'] = 'requires_recomparison'
            elif year < as_of.year: item['status'] = 'historical_only'
            elif year > as_of.year: item['status'] = 'future_edition_not_active'
            else: item['status'] = 'same_year_lifecycle_unverified'
        except (KeyError, TypeError, ValueError, OSError) as error:
            item.update(status='error', reason=type(error).__name__)
        report['entries'].append(item)
    report['counts'] = {status: sum(r['status'] == status for r in report['entries']) for status in
                        ('historical_only', 'same_year_lifecycle_unverified', 'future_edition_not_active', 'requires_recomparison', 'error')}
    report['complete'] = False  # This diagnostic never means all semantic/lifecycle work is done.
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('.'))
    parser.add_argument('--as-of',type=date.fromisoformat,default=date.today())
    args=parser.parse_args();result=audit(args.root,args.as_of)
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 1 if result['counts']['error'] or result['counts']['requires_recomparison'] else 0

if __name__=='__main__':raise SystemExit(main())
