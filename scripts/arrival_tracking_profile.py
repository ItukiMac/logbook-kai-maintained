"""Read-only validation of D's auxiliary arrival archive during B's explicit import.

This validates storage shapes, checksums, chains and fleet/arrival associations.
It does NOT prove capture continuity, credit an arrival, or repair partial data.
An unparseable checkpoint blocks import; raw snapshots still retain its bytes.
"""
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import PurePosixPath
import re
from uuid import UUID

TOKYO = timezone(timedelta(hours=9))
PREFIX = 'sortie-arrivals-v1/'
ROOTS = ('tracking-v1', 'fleet-evidence-v1')
FIELDS = {'format', 'sequence', 'previousDigest', 'kind', 'observedAt', 'payload', 'reason', 'digest'}
ROUTE = {'sortieId', 'startedAt', 'observedAt', 'deckId', 'instances', 'masters', 'escapedSlots', 'cell', 'eventId', 'fromCell'}
PAYLOAD = {'route', 'startKey', 'eventKeys', 'captureAt', 'recoverable', 'offeredSlots'}
KINDS = {'START', 'STEP', 'OFFER', 'RETREAT', 'GOAL', 'INVALID', 'BOUNDARY'}
HEX = re.compile('[0-9a-f]{64}')


def require(ok, why):
    if not ok:
        raise ValueError(why)


def number(value, minimum=0, maximum=2**63-1):
    return type(value) is int and minimum <= value <= maximum


def sha(value):
    return isinstance(value, str) and HEX.fullmatch(value) is not None


def uid(value):
    try:
        return isinstance(value, str) and str(UUID(value)) == value
    except ValueError:
        return False


def date_at(value):
    require(number(value, 1), 'Invalid tracking epoch')
    try:
        return datetime.fromtimestamp(value / 1000, TOKYO).date().isoformat()
    except (ValueError, OverflowError, OSError) as error:
        raise ValueError('Unsupported tracking epoch') from error


def observation(value):
    require(isinstance(value, dict) and set(value) == PAYLOAD, 'Unknown tracking observation')
    route = value['route']
    require(isinstance(route, dict) and set(route) == ROUTE, 'Unknown tracked route')
    require(uid(route['sortieId']) and number(route['startedAt'], 1)
            and number(route['observedAt'], route['startedAt'], route['startedAt'] + 86400000)
            and number(route['deckId'], 1, 4), 'Invalid tracked sortie')
    # Runtime refuses both JST midnight and 05:00 reset crossings.
    start, end = route['startedAt'], route['observedAt']
    require(date_at(start) == date_at(end) and date_at(start - 18000000) == date_at(end - 18000000),
            'Tracked route crosses a reset boundary')
    for key in ('instances', 'masters'):
        values = route[key]
        require(isinstance(values, list) and 1 <= len(values) <= 7
                and all(number(v, 1, 2**31-1) for v in values), 'Invalid tracked fleet')
    size = len(route['instances'])
    require(len(route['masters']) == size and len(set(route['instances'])) == size, 'Duplicate tracked instance')
    for key, maximum in (('escapedSlots', size-1),):
        values = route[key]
        require(isinstance(values, list) and len(values) <= maximum
                and all(number(v, 1, size) for v in values) and len(set(values)) == len(values), 'Invalid escaped slots')
    require(number(route['cell'], 0, 2**31-1) and number(route['eventId'], 0, 2**31-1)
            and (route['fromCell'] is None or number(route['fromCell'], 0, 2**31-1)), 'Invalid tracked cell')
    keys = value['eventKeys']; offered = value['offeredSlots']
    require(sha(value['startKey']) and isinstance(keys, list) and 1 <= len(keys) <= 128
            and all(sha(k) for k in keys) and keys[0] == value['startKey']
            and len(set(keys)) == len(keys), 'Invalid tracking event identities')
    require(number(value['captureAt']) and type(value['recoverable']) is bool
            and (not value['recoverable'] or value['captureAt'] > 0), 'Invalid capture evidence')
    require(isinstance(offered, list) and len(offered) <= 2
            and all(number(v, 1, size) and v not in route['escapedSlots'] for v in offered)
            and len(set(offered)) == len(offered), 'Invalid retreat offer')
    return route


def validate_auxiliary(root, entries, credited, read_json, arrival):
    """Return validated path membership/counts; preserve all input bytes and pending files."""
    handled, days, fleets = set(), {}, {}
    fleet_records = pending_fleets = checkpoints = 0
    for name, record in entries.items():
        if not name.startswith(PREFIX):
            continue
        parts = PurePosixPath(name).parts[1:]
        if parts[0] not in ROOTS:
            continue
        handled.add(name)
        group = parts[0]
        if len(parts) == 1:
            require(record['kind'] == 'directory', 'Auxiliary journal root must be a directory')
            continue
        if group == 'tracking-v1':
            if parts == ('tracking-v1', '.lock'):
                require(record['kind'] == 'file' and record['bytes'] == 0, 'Unknown tracking lock contents')
                continue
            require(len(parts) in (2, 3) and re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}', parts[1]),
                    'Unknown tracking layout')
            try:
                datetime.strptime(parts[1], '%Y-%m-%d')
            except ValueError as error:
                raise ValueError('Invalid tracking day') from error
            bucket = days.setdefault(parts[1], [])
            if len(parts) == 2:
                require(record['kind'] == 'directory', 'Tracking day must be a directory')
            else:
                require(record['kind'] == 'file' and re.fullmatch(r'[0-9]{8}\.json', parts[2]),
                        'Unknown tracking checkpoint filename')
                bucket.append((parts[2], name))
        else:
            require(len(parts) in (2, 3) and re.fullmatch(r'[0-9]{4}-(0[1-9]|1[0-2])', parts[1]),
                    'Unknown fleet evidence layout')
            if len(parts) == 2:
                require(record['kind'] == 'directory', 'Fleet evidence month must be a directory')
                continue
            require(record['kind'] == 'file', 'Fleet evidence must be a regular file')
            value = read_json(root / name, 16 * 1024)
            require(isinstance(value, dict) and set(value) == {'format', 'arrival', 'members'}
                    and value['format'] == 'logbook-port-arrival-fleet-v1', 'Unknown fleet evidence schema')
            key = arrival(value['arrival'], parts[1], parts[2])
            members = value['members']; prior = value['arrival']
            require(isinstance(members, list) and len(members) == len(prior['shipMasterIds']), 'Wrong observed fleet size')
            identities, retired = set(), 0
            for index, member in enumerate(members):
                require(isinstance(member, dict) and set(member) == {'slot', 'instanceId', 'masterId', 'retreated'},
                        'Unknown observed fleet member')
                require(number(member['slot'], index+1, index+1) and number(member['instanceId'], 1, 2**31-1)
                        and number(member['masterId'], 1, 2**31-1) and member['masterId'] == prior['shipMasterIds'][index]
                        and type(member['retreated']) is bool and member['instanceId'] not in identities,
                        'Invalid observed fleet identity or slot')
                identities.add(member['instanceId']); retired += int(member['retreated'])
            require(retired < len(members) and prior['noRetreat'] == (retired == 0), 'Conflicting observed retreat')
            require(key not in fleets or fleets[key] == value, 'Conflicting fleet evidence identity')
            require(key not in credited or credited[key] == prior, 'Fleet evidence differs from credited arrival')
            fleets[key] = value
            if parts[2].endswith('.pending'):
                pending_fleets += 1
            else:
                fleet_records += 1
    require(len(days) <= 8000, 'Too many tracking days')
    for day, paths in days.items():
        require(0 < len(paths) <= 20000, 'Empty/oversized tracking day')
        previous, last_time = '', 0
        for sequence, (filename, name) in enumerate(sorted(paths), 1):
            require(filename == f'{sequence:08d}.json', 'Missing tracking sequence')
            row = read_json(root / name, 16 * 1024)
            require(isinstance(row, dict) and set(row) == FIELDS
                    and row['format'] == 'logbook-port-arrival-tracking-v1', 'Unknown tracking schema')
            require(number(row['sequence'], sequence, sequence) and row['previousDigest'] == previous
                    and number(row['observedAt'], max(last_time, 1)) and date_at(row['observedAt']) == day
                    and isinstance(row['kind'], str) and row['kind'] in KINDS
                    and isinstance(row['reason'], str) and len(row['reason']) <= 256 and sha(row['digest']),
                    'Invalid tracking chain')
            payload = row['payload']
            if payload is None:
                require(row['kind'] in ('BOUNDARY', 'INVALID'), 'Missing tracked route')
            else:
                route = observation(payload)
                require(payload['captureAt'] <= route['observedAt'] <= row['observedAt'], 'Future checkpoint evidence')
            unsigned = {k: row[k] for k in ('format', 'sequence', 'previousDigest', 'kind', 'observedAt', 'payload', 'reason')}
            raw = json.dumps(unsigned, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf8')
            require(hashlib.sha256(raw).hexdigest() == row['digest'], 'Tracking checksum mismatch')
            previous, last_time = row['digest'], row['observedAt']; checkpoints += 1
    return handled, {'tracking_checkpoints': checkpoints, 'fleet_evidence_records': fleet_records,
                     'retained_pending_fleet_evidence': pending_fleets,
                     'capture_continuity_proven': False, 'application_semantics_rechecked_on_read': True}
