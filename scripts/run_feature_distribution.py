#!/usr/bin/env python3
"""Verify an experimental bundle without starting Java, a proxy, networking or the legacy updater."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path

FILES = ('logbook-core.jar', 'features/logbook-feature-calculators.jar',
         'features/logbook-feature-charts.jar', 'features/logbook-feature-status.jar',
         'run_feature_distribution.py')


def verify(root: Path) -> dict:
    root = root.absolute()
    if any(p.is_symlink() for p in (root, *root.parents)):
        raise ValueError('Symlinked bundle path')
    mp = root / 'distribution.json'
    if mp.is_symlink() or mp.stat().st_size > 256*1024:
        raise ValueError('Unsafe distribution manifest')
    report = json.loads(mp.read_text(encoding='utf8'))
    if report.get('format') != 'logbook-feature-distribution-v1' or set(report.get('files', {})) != set(FILES):
        raise ValueError('Unexpected distribution inventory')
    for name in FILES:
        path = root / name
        if any(p.is_symlink() for p in (path, path.parent)) or not path.is_file():
            raise ValueError('Missing or symbolic bundle component: ' + name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != report['files'][name]:
            raise ValueError('Bundle component hash mismatch: ' + name)
    return report



def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root', type=Path, default=Path(__file__).resolve().parent)
    a = p.parse_args()
    report = verify(a.root)
    print(json.dumps({'verified': True, 'source_revision': report['source_revision'],
                      'launched': False, 'production_ready': False}))

if __name__ == '__main__':
    main()
