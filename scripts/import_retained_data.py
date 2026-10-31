#!/usr/bin/env python3
"""Copy explicitly selected irreplaceable records into a NEW portable directory.

No source deletion, settings import, cache import, merge or schema conversion.
Only use a stopped runtime or a stable copy; a changing source aborts acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat

CONFIG_FILES = {'logbook.bean.AppQuestCondition.json', 'logbook.bean.ShipLabelCollection.json',
                'logbook.bean.AppDeckCollection.json', 'logbook.bean.AppSeaAreaExpCollection.json',
                'logbook.bean.AppExpRecords.json', 'logbook.bean.ArsenalQuestSetting.json'}
DIRECTORIES = {'battlelog', 'battle', 'reports', 'config/quest-ledger',
               'sortie-arrivals-v1'}


def allowed(path):
    return (path in DIRECTORIES or path in {'config/' + x for x in CONFIG_FILES}
            or '/' not in path and path.endswith('.csv'))


def regular_bytes(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise ValueError('Not a regular file: ' + str(path))
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            return stream.read()
    finally:
        os.close(fd)


def inventory(source, selections):
    result = {}
    for selected in selections:
        if not allowed(selected):
            raise ValueError('Not an approved retained-data path: ' + selected)
        path = source / selected
        if any(p.is_symlink() for p in (path, *path.parents)):
            raise ValueError('Symlinks are not imported')
        if not path.exists():
            raise ValueError('Selected source is missing: ' + selected)
        paths = sorted(path.rglob('*')) if path.is_dir() else [path]
        for item in paths:
            if item.is_symlink():
                raise ValueError('Symlinks are not imported')
            if item.is_dir():
                continue
            data = regular_bytes(item)
            result[item.relative_to(source).as_posix()] = {'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
    return result


def import_data(source, output, selections):
    source = Path(source).absolute(); output = Path(output).absolute()
    if '..' in source.parts or '..' in output.parts:
        raise ValueError('Parent traversal is not allowed')
    if output == source or source in output.parents or output in source.parents:
        raise ValueError('Source and output must be independent')
    if any(p.is_symlink() for p in (source, *source.parents, output, *output.parents)):
        raise ValueError('Symlinked roots are not allowed')
    before = inventory(source, selections)
    if not before:
        raise ValueError('No retained files selected')
    output.mkdir(exist_ok=False)
    # A failure leaves a visibly incomplete copy; the caller never loses originals.
    (output / 'IMPORT_INCOMPLETE').write_text('Do not install: import has not completed.\n')
    for relative, expected in before.items():
        data = regular_bytes(source / relative)
        if hashlib.sha256(data).hexdigest() != expected['sha256']:
            raise ValueError('Source changed during import: ' + relative)
        target = output / relative; target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
        if hashlib.sha256(regular_bytes(target)).hexdigest() != expected['sha256']:
            raise ValueError('Copy verification failed: ' + relative)
    if inventory(source, selections) != before:
        raise ValueError('Source changed during import')
    report = {'format': 'logbook-kai2-retained-import-v1', 'source_unchanged': True,
              'files': before, 'count': len(before), 'settings_imported': False,
              'schema_conversion': False}
    (output / 'import-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    (output / 'IMPORT_INCOMPLETE').unlink()  # Only this tool's own completion marker.
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--include', action='append', required=True)
    args = parser.parse_args()
    print(json.dumps(import_data(args.source, args.out, args.include), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
