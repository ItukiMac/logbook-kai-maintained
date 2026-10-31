#!/usr/bin/env python3
"""Validate the baseline Linux runtime dependency/notice inventory."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
manifest=json.loads((root/'licenses/runtime-inventory.json').read_text())
expected={r['artifact']:r for r in manifest['runtime_dependencies']}
actual=[Path(p) for p in (root/'target/runtime-classpath.txt').read_text().strip().split(':')]
if set(expected)!={p.name for p in actual}:raise SystemExit('Dependency inventory changed; refresh third-party notices')
for p in actual:
 row=expected[p.name]
 if hashlib.sha256(p.read_bytes()).hexdigest()!=row['sha256_linux']:raise SystemExit('Dependency bytes changed: '+p.name)
 if not row['notices']:raise SystemExit('Missing license coverage: '+p.name)
 for rel in row['notices']:
  if not (root/'licenses'/rel).is_file():raise SystemExit('Missing notice: '+rel)
print('RUNTIME_NOTICES_OK dependencies='+str(len(actual)))
