#!/usr/bin/env python3
"""Reject unclassified GUI source, missing rationale/tests and distribution owner drift."""
import json
from pathlib import Path
import argparse
from build_feature_distribution import owner
from maintenance_inventory import safe_path


def check(root, catalog):
    root=Path(root).resolve(strict=True)
    data=json.loads(Path(catalog).read_text())
    if data.get('format')!='logbook-gui-ownership-v1' or not isinstance(data.get('components'),list):
        raise ValueError('Unsupported ownership catalog')
    actual={p.relative_to(root).as_posix() for p in (root/'src/main/java/logbook/internal/gui').glob('*.java')}
    seen=set();counts={}
    for row in data['components']:
        name=row['path']
        safe_path(root,name)
        if name in seen or name not in actual:raise ValueError('Duplicate or stale component')
        seen.add(name)
        expected=owner('logbook/internal/gui/'+Path(name).stem+'.class',True,True,True,True,True) or 'host'
        if row.get('owner')!=expected:raise ValueError('Packaging owner drift: '+name)
        if not isinstance(row.get('reason'),str) or not row['reason'].strip() or not row.get('category'):
            raise ValueError('Explicit ownership reason required')
        tests=row.get('verification')
        if not isinstance(tests,list) or not tests:raise ValueError('Verification links required')
        for test in tests:
            if not test.startswith(('src/test/','scripts/')) or not safe_path(root,test).is_file():
                raise ValueError('Missing verification input')
        counts[expected]=counts.get(expected,0)+1
    if not actual or seen!=actual:raise ValueError('Unclassified GUI component')
    return {'components':len(actual),'owners':counts,'unclassified':0,'all_tests_execution_claimed':False}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path('.'));a=p.parse_args()
    print(json.dumps(check(a.root,a.root/'docs/maintenance/gui-ownership.json'),indent=2))
