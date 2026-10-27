#!/usr/bin/env python3
"""Read-only source/catalog checks; date-only candidates never activate a quest rule."""
import argparse
from datetime import date, datetime, time, timedelta
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo
from maintenance_inventory import safe_path

REGISTRY = 'docs/maintenance/seasonal-editions-20261026.json'
CATALOG = 'src/main/resources/logbook/seasonal/catalog-v1.json'
SOURCE_CHECKS = 'docs/maintenance/seasonal-source-checks-20261027.json'
JST = ZoneInfo('Asia/Tokyo')
MANAGED_IDS = {441, 840, 841, 843, 906, 907, 909, 953, 954, 955, 987, 1039, 1040}
HASH = re.compile(r'[a-f0-9]{64}\Z')
EDITION_RESOURCE = re.compile(r'logbook/seasonal/editions/[a-zA-Z0-9-]+\.json\Z')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'Duplicate JSON key: ' + key)
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError('Non-finite JSON value: ' + value)


def read_json(root, name):
    raw = safe_path(root, name).read_bytes()
    if name == CATALOG:
        require(len(raw) <= 1_048_576, 'Catalog exceeds runtime byte limit')
    return json.loads(raw.decode('utf8'), object_pairs_hook=unique_pairs, parse_constant=reject_constant)


def digest(root, name):
    return hashlib.sha256(safe_path(root, name).read_bytes()).hexdigest()


def text(value, label):
    require(isinstance(value, str) and bool(value.strip()), label + ' must be nonempty text')
    return value


def positive_integer(value, label):
    require(type(value) is int and value > 0, label + ' must be a positive integer')
    return value


def instant(value):
    parsed = datetime.fromisoformat(text(value, 'Instant').replace('Z', '+00:00'))
    require(parsed.tzinfo is not None and parsed.utcoffset() is not None, 'Instant must include a timezone')
    return parsed


def indexed(rows, key):
    require(isinstance(rows, list) and bool(rows), 'Missing ' + key + ' records')
    result = {}
    for row in rows:
        require(isinstance(row, dict), 'Invalid ' + key + ' record')
        name = text(row.get(key), key)
        require(name not in result, 'Duplicate ' + key + ': ' + name)
        result[name] = row
    return result


def pointer_exists(document, pointer):
    require(isinstance(pointer, str) and pointer.startswith('/'), 'Invalid condition pointer')
    for part in pointer[1:].split('/'):
        part = part.replace('~1', '/').replace('~0', '~')
        if isinstance(document, list):
            require(part.isdecimal() and int(part) < len(document), 'Missing condition pointer')
            document = document[int(part)]
        else:
            require(isinstance(document, dict) and part in document, 'Missing condition pointer')
            document = document[part]


def period_status(as_of, start, end):
    """A calendar day is not an observed quest timestamp or a selection decision."""
    begin = datetime.combine(as_of, time.min, JST)
    after = begin + timedelta(days=1)
    if after <= start:
        return 'before_conservative_window'
    if begin >= end:
        return 'after_conservative_window'
    if start <= begin and after <= end:
        return 'within_date_candidate_only'
    return 'partial_date_requires_time'


def audit_runtime(root, as_of, report):
    rows = report['runtime_editions']
    errors = report['runtime_errors']
    global_errors = 0
    try:
        catalog = read_json(root, CATALOG)
        checks = read_json(root, SOURCE_CHECKS)
        require(isinstance(catalog, dict) and catalog.get('format') == 'logbook-seasonal-rules-v1', 'Unsupported catalog')
        require(catalog.get('verificationRecord') == SOURCE_CHECKS, 'Catalog verification record differs')
        require(isinstance(checks, dict) and checks.get('format') == 'logbook-seasonal-source-checks-v1', 'Unsupported source checks')
        require(checks.get('complete') is False and type(checks.get('full_condition_certified_count')) is int
                and checks['full_condition_certified_count'] == 0,
                'Initial comparisons are not complete semantic certification')
        require(type(checks.get('initial_comparison_count')) is int and checks['initial_comparison_count'] == 148,
                'Initial comparison cohort differs; review the coverage record explicitly')
        require(checks.get('live_game_verified') is False, 'This offline audit does not certify live observations')
        require(text(checks.get('method'), 'Review method').startswith('Manual semantic reading'),
                'Automatic retrieval alone is not a semantic review')
        checked_on = date.fromisoformat(text(checks.get('checked_on'), 'Review date'))
        previous = checks['previous_observation_registry']
        require(isinstance(previous, dict) and previous.get('path') == REGISTRY and previous.get('preserved') is True,
                'Historical observation registry must be preserved')
        require(digest(root, REGISTRY) == previous.get('sha256'), 'Historical observation registry changed')
        managed = catalog.get('managedQuestIds')
        require(isinstance(managed, list), 'Missing managed quest IDs')
        for value in managed:
            positive_integer(value, 'Managed quest ID')
        require(len(set(managed)) == len(managed) and MANAGED_IDS.issubset(managed), 'Missing or duplicate managed quest IDs')
        definitions = indexed(catalog.get('editions'), 'editionId')
        require(len(definitions) <= 128, 'Too many runtime editions')
        evidence = indexed(checks.get('editions'), 'edition_id')
        require(set(definitions) == set(evidence), 'Catalog and source-check edition sets differ')
        sources = indexed(checks.get('sources'), 'source_id')
        for source in sources.values():
            parsed = urlsplit(text(source.get('url'), 'Source URL'))
            require(parsed.scheme == 'https' and parsed.hostname in {'wikiwiki.jp', 'zekamashi.net'}, 'Unreviewed source host')
            require(text(source.get('read_method'), 'Source reading method').startswith('manual reading'), 'Source was not manually read')
            text(source.get('locator'), 'Source locator')
            accessed = date.fromisoformat(text(source.get('accessed_on'), 'Access date'))
            require(accessed <= checked_on, 'Source accessed after the claimed review')
            for field in ('published_on', 'modified_on'):
                value = source.get(field)
                if value is None:
                    text(source.get('date_note'), 'Unknown source-date explanation')
                else:
                    require(date.fromisoformat(text(value, field)) <= accessed, 'Source date after access')
        report['unresolved_newer_editions'] = checks.get('unresolved_newer_editions', [])
        for newer in report['unresolved_newer_editions']:
            require(isinstance(newer, dict) and newer.get('internal_id') is None and newer.get('selectable') is False,
                    'Unverified newer edition cannot infer a reused internal ID')
        windows = []
        for name, definition in definitions.items():
            item = {'edition_id': name, 'active': None, 'status': 'error'}
            try:
                fact = evidence[name]
                quest_id = positive_integer(definition.get('questId'), 'Quest ID')
                year = positive_integer(definition.get('editionYear'), 'Edition year')
                require(quest_id in managed and 2013 <= year <= 9999, 'Unmanaged ID or invalid year')
                require(quest_id == fact.get('quest_id') and year == fact.get('edition_year'), 'Edition identity differs from source checks')
                origin = definition.get('origin')
                require(origin in {'quest', 'eventquest'}, 'Invalid rule origin')
                old_path = 'src/main/resources/logbook/' + origin + '/' + str(quest_id) + '.json'
                resource = text(definition.get('definitionResource'), 'Definition resource')
                require(EDITION_RESOURCE.fullmatch(resource) is not None, 'Unsafe definition resource')
                new_path = 'src/main/resources/' + resource
                require(new_path == fact.get('definition_path') and old_path == fact.get('legacy_definition_path'), 'Definition path differs from source checks')
                item.update(id=quest_id, edition_year=year, path=new_path, legacy_path=old_path,
                            definition_sha256=definition.get('definitionSha256'),
                            legacy_definition_sha256=definition.get('baseDefinitionSha256'))
                for field, fact_field in [('definitionSha256', 'definition_sha256'), ('baseDefinitionSha256', 'legacy_definition_sha256')]:
                    value = definition.get(field)
                    require(isinstance(value, str) and HASH.fullmatch(value) is not None, 'Invalid definition hash')
                    require(value == fact.get(fact_field), 'Catalog hash differs from source checks')
                identity = fact['expected_observation']
                require(isinstance(identity, dict) and identity.get('runtime_api_observation_required') is True,
                        'Fresh runtime identity must remain required')
                require(identity.get('live_api_observation_captured') is False,
                        'Public text must not be relabelled as a captured live observation')
                require(type(identity.get('category')) is int and type(identity.get('type')) is int,
                        'Reviewed API identity fields must be integers')
                for field, fact_field in [('title', 'exactTitle'), ('detail', 'exactDetail'), ('category', 'category'), ('type', 'type')]:
                    require(definition.get(field) == identity.get(fact_field), 'Catalog ' + field + ' differs from reviewed identity')
                text(definition.get('title'), 'Title'); text(definition.get('detail'), 'Detail')
                require(type(definition.get('category')) is int and definition['category'] in {2, 4}, 'Invalid API category')
                require(type(definition.get('type')) is int and 1 <= definition['type'] <= 5, 'Invalid API period')
                item['expected_observation'] = identity
                require(fact.get('selectable_candidate') is True and fact.get('core_conditions_verified') is True,
                        'Modeled conditions are not reviewed')
                require(fact.get('legacy_definition_preserved') is True, 'Original rule must remain preserved')
                id_fact = fact['internal_id_evidence']
                require(isinstance(id_fact, dict) and id_fact.get('verified') is True
                        and id_fact.get('not_inferred_from_title') is True, 'Internal ID evidence missing')
                require(id_fact.get('source_id') in sources and str(quest_id) in text(id_fact.get('locator'), 'Internal ID locator'),
                        'Internal ID source/locator missing')
                for field in ('identityVerified', 'windowVerified', 'modeledConditionsVerified'):
                    require(definition.get(field) is True, 'Missing ' + field)
                require(definition.get('selectionBlockers') == [], 'Selection blockers remain')
                require(definition.get('wholeRuleCertified') is False and fact.get('whole_rule_certified') is False,
                        'Modeled-condition review is not whole-rule certification')
                verified = fact.get('verified_conditions'); unresolved = fact.get('unresolved_conditions')
                require(isinstance(verified, list) and bool(verified) and definition.get('verifiedConditions') == verified,
                        'Confirmed conditions differ from source checks')
                require(isinstance(unresolved, list) and bool(unresolved) and definition.get('unresolvedConditions') == unresolved,
                        'Unresolved conditions were lost or changed')
                source_ids = fact.get('source_ids')
                require(isinstance(source_ids, list) and len(set(source_ids)) == len(source_ids), 'Invalid source IDs')
                require(id_fact['source_id'] in source_ids, 'Internal ID evidence is not among this edition sources')
                selected_sources = [sources[s] for s in source_ids]
                require({urlsplit(s['url']).hostname for s in selected_sources} == {'wikiwiki.jp', 'zekamashi.net'}, 'Both named sources required')
                expected_sources = [{'url': s['url'], 'publishedOn': s['published_on'], 'modifiedOn': s['modified_on'],
                    'accessedOn': s['accessed_on'], 'manuallyReviewed': True, 'locator': s['locator']} for s in selected_sources]
                require(definition.get('sources') == expected_sources, 'Catalog source metadata differs from reviewed evidence')
                candidate = read_json(root, new_path)
                require(isinstance(candidate, dict), 'Candidate definition must be an object')
                for condition in verified:
                    require(isinstance(condition, dict), 'Invalid confirmed condition')
                    text(condition.get('condition_id'), 'Condition ID'); text(condition.get('finding'), 'Condition finding')
                    refs = condition.get('source_ids')
                    require(isinstance(refs, list) and len(set(refs)) == len(refs)
                            and set(refs) == set(source_ids), 'Condition lacks both reviewed sources')
                    pointer_exists(candidate, condition.get('definition_pointer'))
                item.update(verified_conditions=verified, unresolved_conditions=unresolved, sources=expected_sources)
                start = instant(definition.get('validFrom')); end = instant(definition.get('validUntil'))
                window = fact['conservative_selection_window']; period = fact['source_period']
                require(isinstance(window, dict) and isinstance(period, dict) and period.get('precision') == 'date', 'Unknown period evidence')
                period_refs = period.get('source_ids')
                require(isinstance(period_refs, list) and bool(period_refs) and set(period_refs).issubset(source_ids),
                        'Period source evidence is missing')
                require(period.get('actual_start_instant') is None and period.get('actual_end_instant') is None,
                        'Date-only source bounds are not exact service instants')
                require(start == instant(window['from_inclusive']) and end == instant(window['until_exclusive']), 'Catalog window differs from source checks')
                first_day = date.fromisoformat(period['start_date']); last_day = date.fromisoformat(period['end_date'])
                require(start == datetime.combine(first_day + timedelta(days=1), time.min, JST)
                        and end == datetime.combine(last_day, time.min, JST), 'Window is not limited to interior days')
                require(start < end and start.astimezone(JST).year == year
                        and (end - timedelta(microseconds=1)).astimezone(JST).year == year, 'Window/year conflict')
                require(definition.get('windowPolicy') == window.get('policy'), 'Window policy differs')
                item.update(valid_from=definition['validFrom'], valid_until=definition['validUntil'],
                            period_status=period_status(as_of, start, end))
                if digest(root, old_path) != definition['baseDefinitionSha256'] or digest(root, new_path) != definition['definitionSha256']:
                    item.update(status='requires_recomparison', reason='Reviewed original/candidate bytes changed')
                    errors.append(name + ': ' + item['reason'])
                else:
                    item['status'] = 'reviewed_candidate'
                windows.append((quest_id, definition['title'], definition['detail'], definition['category'],
                                definition['type'], start, end, name))
            except (KeyError, TypeError, ValueError, OSError, UnicodeError) as error:
                item.update(status='error', reason=str(error))
                errors.append(name + ': ' + str(error))
            rows.append(item)
        for i, left in enumerate(windows):
            for right in windows[i + 1:]:
                if left[:5] == right[:5] and max(left[5], right[5]) < min(left[6], right[6]):
                    errors.append('Conflicting identical-identity windows: ' + left[7] + ' / ' + right[7])
                    global_errors += 1
    except (KeyError, TypeError, ValueError, OSError, UnicodeError) as error:
        errors.append('Runtime catalog/source checks: ' + str(error))
        global_errors += 1
    report['runtime_catalog_valid'] = not errors
    report['runtime_counts'] = {
        'reviewed_candidates': sum(r['status'] == 'reviewed_candidate' for r in rows),
        'requires_recomparison': sum(r['status'] == 'requires_recomparison' for r in rows),
        'error': sum(r['status'] == 'error' for r in rows) + global_errors,
    }

def audit(root: Path, as_of: date, observations=None):
    root = root.resolve(strict=True)
    require(type(as_of) is date, '--as-of is a date, not a runtime observation timestamp')
    if observations is None:
        observations = read_json(root, REGISTRY)
    if not isinstance(observations, dict) or observations.get('format') != 'logbook-seasonal-edition-observations-v1':
        raise ValueError('Unsupported observation registry')
    rows = observations.get('entries')
    if not isinstance(rows, list) or not rows: raise ValueError('Empty edition observations')
    report = {'as_of': as_of.isoformat(), 'as_of_timezone': 'Asia/Tokyo', 'read_only': True,
              'active_selection_supported': True, 'active_selection_evaluated': False,
              'selection_note': 'The application supports runtime selection. This audit has no live observation and checks date-only candidate periods, never current applicability.',
              'entries': [], 'runtime_editions': [], 'runtime_errors': [], 'certified_active_rules': 0,
              'initial_comparison_count': 148, 'full_condition_certified_count': 0,
              'typed_runtime_validation_required': True}
    seen = set()
    for record in rows:
        item = {'id': None, 'path': None, 'active': None}
        try:
            if not isinstance(record, dict): raise ValueError('Invalid observation record')
            item.update(id=record.get('id'), path=record.get('path'))
            name = record['path']; year = record['edition_year']
            if not isinstance(name, str): raise ValueError('Definition path must be text')
            if name in seen: raise ValueError('Duplicate definition observation')
            seen.add(name)
            if type(year) is not int or year < 2013 or year > 9999: raise ValueError('Invalid explicit edition year')
            if record.get('lifecycle_verified') is not False: raise ValueError('Registry cannot certify activation')
            if not (name.startswith('src/main/resources/logbook/quest/') or name.startswith('src/main/resources/logbook/eventquest/')):
                raise ValueError('Unmanaged rule path')
            raw = safe_path(root, name).read_bytes()
            item.update(edition_year=year, source_edition=record['source_edition'],
                        verified_conditions=[], unresolved_conditions=record.get('unresolved', []),
                        source_url=record.get('source_url'), source_modified=record.get('source_modified'),
                        source_accessed=observations.get('checked_on'))
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
    audit_runtime(root, as_of, report)
    report['complete'] = False  # This diagnostic never means all semantic/lifecycle work is done.
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=Path('.'))
    parser.add_argument('--as-of',type=date.fromisoformat,default=datetime.now(JST).date())
    args=parser.parse_args();result=audit(args.root,args.as_of)
    print(json.dumps(result,ensure_ascii=False,indent=2))
    return 1 if result['counts']['error'] or result['counts']['requires_recomparison'] or result['runtime_errors'] else 0

if __name__=='__main__':raise SystemExit(main())
