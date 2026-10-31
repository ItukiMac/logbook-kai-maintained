#!/usr/bin/env python3
"""Package a verified Linux parallel-test kit, never install it on a machine."""
import argparse
import hashlib
import json
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from run_feature_distribution import verify, FILES

ROOT=Path(__file__).resolve().parents[1]
SCRIPTS=('prepare_parallel_runtime.py','feature_bundle_manager.py','run_feature_distribution.py',
         'import_retained_data.py','runtime_snapshot.py')


def build(bundle, output):
    report=verify(bundle)
    contents={f'bundle/{name}':bundle/name for name in (*FILES,'distribution.json')}
    contents.update({'scripts/'+n:ROOT/'scripts'/n for n in SCRIPTS})
    for folder in ['extensions/browser-bridge','licenses']:
        for p in (ROOT/folder).rglob('*'):
            if p.is_file():contents[p.relative_to(ROOT).as_posix()]=p
    for name in ['LICENSE','THIRD_PARTY_NOTICES.md']:
        contents[name]=ROOT/name
    contents['README.md']=ROOT/'docs/user/linux-parallel-test.md'
    contents['DATA-IMPORT.md']=ROOT/'docs/user/data-import.md'
    checksums={n:hashlib.sha256(p.read_bytes()).hexdigest() for n,p in sorted(contents.items())}
    with ZipFile(output,'x',ZIP_DEFLATED) as z:
        for name,p in sorted(contents.items()):z.write(p,name)
        z.writestr('checksums.json',json.dumps(checksums,indent=2)+'\n')
        z.writestr('candidate.json',json.dumps({'source_revision':report['source_revision'],
            'hardware_tested':False,'published_release':False,'purpose':'Linux parallel test'},indent=2)+'\n')
    return {'archive':str(output),'source_revision':report['source_revision'],
            'sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'files':len(contents)+2}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();print(json.dumps(build(a.bundle,a.out),indent=2))
