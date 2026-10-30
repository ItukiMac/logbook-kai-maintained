#!/usr/bin/env python3
"""Pure, bounded source/field policy for reviewed expedition numeric literals only."""
from __future__ import annotations
import copy
from datetime import datetime,timedelta
import json
from pathlib import Path
import re
from audit_game_json import parse_jsonc
from maintenance_workbench import bounded,checked_path,digest,unique,relative
from routine_scalar_candidate import Tables,URLS

POLICY='docs/maintenance/routine-numeric-policy.json'
FACTS='docs/maintenance/routine-numeric-facts.json'
AUDIT='docs/maintenance/routine-numeric-candidate.json'
PREFIX='src/main/resources/logbook/'
FIELDS={'flagship_min_level','fleet:レベル','fleet:火力','fleet:対空','fleet:対潜','fleet:索敵'}
MAX_JSON=2*1024*1024


def read_json(path):
    return json.loads(bounded(path,MAX_JSON),object_pairs_hook=unique,
        parse_constant=lambda _:(_ for _ in ()).throw(ValueError('Non-finite JSON')))


def literals(value):
    if value is None or type(value) in (bool,int,str):return
    if isinstance(value,list):
        for child in value:literals(child)
    elif isinstance(value,dict):
        for key,child in value.items():
            if not isinstance(key,str):raise ValueError('String JSON keys required')
            literals(child)
    else:raise ValueError('Noninteger numeric context requires review')


def document(raw):
    result=parse_jsonc(raw.decode('utf-8-sig'));literals(result)
    if not isinstance(result,dict):raise ValueError('Definition object required')
    return result


def encoded(value):return (json.dumps(value,ensure_ascii=False,sort_keys=True,indent=2,allow_nan=False)+'\n').encode()


def valid_value(field,value):
    maximum=200 if field=='flagship_min_level' else 1200 if field=='fleet:レベル' else 10000
    minimum=1 if field in ('flagship_min_level','fleet:レベル') else 0
    if field not in FIELDS or type(value) is not int or not minimum<=value<=maximum:
        raise ValueError('Unsupported numeric field/value')
    return value


def node_field(node):
    if not isinstance(node,dict):return None
    if set(node)=={'type','order','level'} and node.get('type')=='艦娘' and node.get('order')==1 and type(node['order']) is int:
        valid_value('flagship_min_level',node['level']);return 'flagship_min_level'
    if set(node)=={'type','count_type','value'} and node.get('type')=='艦隊':
        field='fleet:'+str(node.get('count_type'))
        if field in FIELDS:valid_value(field,node['value']);return field
    return None


def selected_node(doc,field):
    found=[]
    def walk(node,conjunctive=True):
        if not isinstance(node,dict):return
        if conjunctive and node_field(node)==field:found.append(node)
        children=node.get('conditions',[])
        if isinstance(children,list):
            for child in children:walk(child,conjunctive and node.get('operator')=='AND')
    walk(doc)
    if len(found)!=1:raise ValueError('Numeric clause must be unique, pure and globally conjunctive')
    return found[0]


def value(doc,field):
    node=selected_node(doc,field);return node['level' if field=='flagship_min_level' else 'value']


def replaced(doc,observed):
    result=copy.deepcopy(doc)
    for field,number in observed.items():
        valid_value(field,number);node=selected_node(result,field)
        node['level' if field=='flagship_min_level' else 'value']=number
    return result


def fingerprint(doc,fields):
    """Mask only existing pure numeric values. Boolean grouping/order/unknown keys remain."""
    literals(doc);result=copy.deepcopy(doc)
    targets=[(field,selected_node(result,field)) for field in sorted(fields)]
    for field,node in targets:node['level' if field=='flagship_min_level' else 'value']=['routine-numeric',field]
    return result


def allowed_delta(before,after,fields):
    if fingerprint(before,fields)!=fingerprint(after,fields):raise ValueError('Non-numeric/Boolean/unknown-field change requires review')


def allowed_public_delta(raw_before,after,fields,path,policy):
    before=document(raw_before)
    try:
        allowed_delta(before,after,fields);return
    except ValueError:
        approved=policy.get('reviewed_bootstrap',{}).get(path,{})
        if approved.get('public_definition_sha256')!=digest(raw_before):raise
        shape=digest(encoded(fingerprint(after,fields)))
        if approved.get('reviewed_source_shape_sha256')!=shape:raise ValueError('Unreviewed bootstrap shape')


def normalize_id(text):
    if not isinstance(text,str):raise ValueError('Mission ID text required')
    if re.fullmatch(r'[0-9]{1,3}',text):return str(int(text))
    if re.fullmatch(r'[A-Z][0-9]{1,2}',text):return text
    raise ValueError('Unsupported mission ID')


def source_rows(raw):
    if not isinstance(raw,bytes) or not 0<len(raw)<=MAX_JSON:raise ValueError('Bounded source required')
    parser=Tables();parser.feed(raw.decode('utf-8-sig'));return parser.rows


def source_values(rows,kind,mission_id):
    mission_id=normalize_id(mission_id)
    if kind=='wiki':
        matches=[]
        for i,row in enumerate(rows):
            if len(row)<2:continue
            try:found=normalize_id(row[0])
            except ValueError:continue
            if found==mission_id:matches.append(i)
        if len(matches)!=1:raise ValueError('Missing/duplicate wiki expedition row')
        i=matches[0]
        if len(rows)<=i+2 or not rows[i+1]:raise ValueError('Wiki expedition rows missing')
        levels=re.fullmatch(r'Lv\s*([0-9]+)(?:\s+Lv\s*([0-9]+))?',rows[i+1][0])
        if levels is None:raise ValueError('Wiki level layout changed')
        result={'flagship_min_level':int(levels.group(1))}
        if levels.group(2):result['fleet:レベル']=int(levels.group(2))
        stats=' '.join(rows[i+2])
    elif kind=='zekamashi':
        matches=[]
        for i,row in enumerate(rows):
            if len(row)<2:continue
            header=re.fullmatch(r'([A-Z]?[0-9]{1,3})\s+[0-9]{1,3}:[0-9]{2}',row[1])
            if header and normalize_id(header.group(1))==mission_id:matches.append(i)
        if len(matches)!=1:raise ValueError('Missing/duplicate Zekamashi expedition row')
        following=rows[matches[0]+1:matches[0]+5]
        fields=[row[1] for row in following if len(row)>1 and row[0]=='練度']
        stat_rows=[row[1] for row in following if len(row)>1 and row[0]=='ステータス']
        if len(fields)!=1 or len(stat_rows)!=1:raise ValueError('Zekamashi expedition layout changed')
        levels=re.fullmatch(r'旗艦:([0-9]+)(?:\s+艦隊:([0-9]+))?',fields[0])
        if levels is None:raise ValueError('Zekamashi level layout changed')
        result={'flagship_min_level':int(levels.group(1))}
        if levels.group(2):result['fleet:レベル']=int(levels.group(2))
        stats=stat_rows[0]
    else:raise ValueError('Unknown source adapter')
    for label in ('火力','対空','対潜','索敵'):
        numbers={int(v) for v in re.findall(re.escape(label)+r'\s*[:：]?\s*([0-9]+)(?=[^0-9]|$)',stats)}
        if len(numbers)>1:raise ValueError('Ambiguous fleet statistic')
        if numbers:result['fleet:'+label]=numbers.pop()
    for field,number in result.items():valid_value(field,number)
    return result


def captured(root,now):
    root=checked_path(root);report=read_json(root/'evidence.json')
    if report.get('format')!='logbook-public-evidence-v1' or report.get('complete') is not True:raise ValueError('Complete direct source evidence required')
    entries=report.get('entries')
    if not isinstance(entries,list) or len(entries)!=2:raise ValueError('Exactly two permitted sources required')
    raw={};evidence=[]
    for entry in entries:
        url=entry['url']
        if url not in URLS or url in raw or entry.get('status')!='captured':raise ValueError('Unexpected/duplicate source')
        checked=datetime.fromisoformat(entry['checked_at'])
        if checked.tzinfo is None or not timedelta(0)<=now-checked<=timedelta(hours=24):raise ValueError('Stale/future source')
        if not re.fullmatch(r'source-[0-9]{2}\.bin',entry['file']):raise ValueError('Unsafe source name')
        body=bounded(root/entry['file'],MAX_JSON)
        if len(body)!=entry['bytes'] or digest(body)!=entry['evidence_sha256']:raise ValueError('Captured source changed')
        raw[url]=source_rows(body)
        evidence.append({k:entry[k] for k in ('url','checked_at','evidence_sha256','bytes')})
    return {'wiki':raw[URLS[0]],'zekamashi':raw[URLS[1]]},evidence


def load_policy(root):
    root=checked_path(root);policy=read_json(root/POLICY);facts=read_json(root/FACTS)
    if policy.get('format')!='logbook-reviewed-numeric-policy-v1' or facts.get('format')!='logbook-reviewed-numeric-facts-v1':raise ValueError('Unknown numeric policy/facts')
    if policy.get('max_changed_definitions')!=20 or type(policy.get('paid_provider_calls')) is not int or policy.get('paid_provider_calls')!=0:raise ValueError('Unsupported budget/provider policy')
    if set(facts)!={'format','definitions','whole_rule_verified'} or facts.get('whole_rule_verified') is not False:raise ValueError('Numeric facts cannot claim whole-rule certification')
    seen=set()
    for row in policy['definitions']:
        path=relative(row['path'])
        if not re.fullmatch(r'src/main/resources/logbook/mission/[0-9]+/[A-Z]?[0-9]+\.json',path) or path in seen:raise ValueError('Invalid/duplicate numeric definition')
        seen.add(path);fields=row['fields']
        if not isinstance(fields,list) or not fields or len(set(fields))!=len(fields) or not set(fields)<=FIELDS:raise ValueError('Unsupported numeric selector')
        entry=facts['definitions'].get(path)
        if not isinstance(entry,dict) or set(entry.get('values',{}))!=set(fields) or not re.fullmatch(r'[0-9a-f]{64}',entry.get('source_sha256','')):raise ValueError('Facts scope mismatch')
        for field,number in entry['values'].items():valid_value(field,number)
    if set(facts['definitions'])!=seen or not seen:raise ValueError('Facts inventory mismatch')
    return policy,facts
