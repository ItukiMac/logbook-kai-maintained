#!/usr/bin/env python3
"""Two-source A1 scalar recipe: fresh evidence -> scoped candidate -> isolated stage.

This is a bounded routine lane, not general rule inference or publication authority.
Missing/conflicting/changed source layouts stop before creating any stage.
"""
from __future__ import annotations
import argparse
import copy
from datetime import datetime, timezone, timedelta
from html.parser import HTMLParser
import hashlib
import json
from pathlib import Path
import re
import sys
from collect_maintenance_sources import canonical_url, MAX_BYTES
from maintenance_plan import digest, plan
from maintenance_workflow import run_gate
from audit_game_json import parse_jsonc
from maintenance_inventory import safe_path

PATH = 'src/main/resources/logbook/mission/1/A1.json'
URLS = [canonical_url('https://wikiwiki.jp/kancolle/遠征/詳細一覧表'),
        canonical_url('https://zekamashi.net/kancolle-kouryaku/ensei/')]
RECIPE = 'a1-flagship-and-fleet-level-v1'


class Tables(HTMLParser):
    def __init__(self):
        super().__init__();self.rows=[];self.row=[];self.cell=None
    def handle_starttag(self,tag,attrs):
        if tag=='tr':self.row=[]
        if tag in ('td','th'):
            if self.cell is not None:raise ValueError('Nested table layout is unsupported')
            self.cell=[]
        if tag=='br' and self.cell is not None:self.cell.append(' ')
    def handle_data(self,data):
        if self.cell is not None:self.cell.append(data)
    def handle_endtag(self,tag):
        if tag in ('td','th') and self.cell is not None:
            self.row.append(' '.join(''.join(self.cell).split()));self.cell=None
        if tag=='tr' and self.row:self.rows.append(self.row)


def levels(raw,source):
    if not isinstance(raw,bytes) or not 0<len(raw)<=MAX_BYTES:raise ValueError('Missing/bounded source required')
    text=raw.decode('utf-8-sig');parser=Tables();parser.feed(text)
    if source=='wiki':
        matches=[i for i,row in enumerate(parser.rows) if len(row)>1 and row[0]=='A1' and row[1]=='兵站強化任務']
        if len(matches)!=1:raise ValueError('Wiki A1 row is missing or ambiguous')
        following=parser.rows[matches[0]+1:matches[0]+2]
        if len(following)!=1:raise ValueError('Wiki levels row missing')
        # Rowspan means repeated ID/name cells are absent in the following row.
        match=re.fullmatch(r'Lv([0-9]+)\s+Lv([0-9]+)',following[0][0])
    elif source=='zekamashi':
        matches=[i for i,row in enumerate(parser.rows) if len(row)>1 and row[0]=='兵站強化任務' and re.fullmatch(r'A1\s+[0-9]{2}:[0-9]{2}',row[1])]
        if len(matches)!=1:raise ValueError('Zekamashi A1 row is missing or ambiguous')
        following=parser.rows[matches[0]+1:matches[0]+5]
        fields=[row[1] for row in following if len(row)>1 and row[0]=='練度']
        if len(fields)!=1:raise ValueError('Zekamashi levels row missing or ambiguous')
        match=re.fullmatch(r'旗艦:([0-9]+)\s+艦隊:([0-9]+)',fields[0])
    else:raise ValueError('Unknown recipe source')
    if match is None:raise ValueError('Source scalar layout changed')
    flagship,fleet=map(int,match.groups())
    if not 1<=flagship<=200 or not flagship<=fleet<=1200:raise ValueError('Source level range invalid')
    return {'flagship':flagship,'fleet':fleet}


def observed(evidence_root,*,now):
    root=Path(evidence_root).resolve(strict=True)
    report_path=safe_path(root,'evidence.json')
    if report_path.stat().st_size>16384:raise ValueError('Evidence report too large')
    report=json.loads(report_path.read_text())
    if report.get('format')!='logbook-public-evidence-v1' or report.get('complete') is not True:
        raise ValueError('Complete retrieved source evidence required')
    entries=report.get('entries')
    if not isinstance(entries,list) or len(entries)!=2:raise ValueError('Exactly two sources required')
    rows={}
    for entry in entries:
        url=canonical_url(entry['url'])
        if url not in URLS or url in rows or entry.get('status')!='captured':raise ValueError('Unexpected/duplicate source')
        checked=datetime.fromisoformat(entry['checked_at'])
        if checked.tzinfo is None or not timedelta(0)<=now-checked<=timedelta(hours=24):raise ValueError('Stale/future evidence')
        name=entry['file']
        if not isinstance(name,str) or not re.fullmatch(r'source-[0-9]{2}\.bin',name):raise ValueError('Unsafe evidence name')
        path=safe_path(root,name)
        if path.stat().st_size>MAX_BYTES:raise ValueError('Evidence too large')
        raw=path.read_bytes()
        if len(raw)!=entry['bytes'] or digest(raw)!=entry['evidence_sha256']:raise ValueError('Evidence changed')
        kind='wiki' if url==URLS[0] else 'zekamashi'
        rows[url]=(levels(raw,kind),entry)
    if set(rows)!=set(URLS) or rows[URLS[0]][0]!=rows[URLS[1]][0]:raise ValueError('Sources disagree; manual source review required')
    evidence=[{key:rows[url][1][key] for key in ('url','checked_at','evidence_sha256')} for url in URLS]
    for row in evidence:row['summary']='Exact A1 flagship/fleet scalar agreement under '+RECIPE
    return rows[URLS[0]][0],evidence


def replacement(raw,values):
    source=parse_jsonc(raw.decode('utf-8-sig'));result=copy.deepcopy(source)
    if not isinstance(result,dict) or result.get('operator')!='AND' or not isinstance(result.get('conditions'),list):raise ValueError('Unsupported A1 source structure')
    flags=[];fleets=[]
    def walk(node):
        if isinstance(node,dict):
            if node.get('type')=='艦娘' and node.get('order')==1 and 'level' in node:flags.append(node)
            if node.get('type')=='艦隊' and node.get('count_type')=='レベル' and 'value' in node:fleets.append(node)
            for value in node.values():walk(value)
        elif isinstance(node,list):
            for value in node:walk(value)
    walk(result)
    if len(flags)!=1 or len(fleets)>1:raise ValueError('Ambiguous existing predicate')
    flags[0]['level']=values['flagship']
    if fleets:fleets[0]['value']=values['fleet']
    else:result['conditions'].insert(0,{'type':'艦隊','count_type':'レベル','value':values['fleet']})
    return raw.decode('utf8') if result==source else json.dumps(result,ensure_ascii=False,indent=2)+'\n'


def run(root,evidence_root,*,output=None,now=None):
    now=now or datetime.now(timezone.utc)
    if now.tzinfo is None:raise ValueError('Timezone-aware clock required')
    root=Path(root).resolve(strict=True);values,evidence=observed(evidence_root,now=now)
    raw=safe_path(root,PATH).read_bytes();content=replacement(raw,values)
    candidate={'path':PATH,'expected_old_sha256':digest(raw),'content':content,'evidence':evidence,
        'upper_bound':{'input_tokens':0,'output_tokens':0,'micro_usd':0}}
    budget={'candidates':1,'input_tokens':0,'output_tokens':0,'micro_usd':0}
    initial=plan(root,[candidate],[],budget,now=now)
    entry=initial['entries'][0]
    if entry['status'] not in ('unchanged','requires_approval'):raise ValueError('Candidate failed ordinary gates')
    # Delegated recipe assertion, not a fabricated per-update user or publisher signature.
    approvals=[] if entry['status']=='unchanged' else [{'identity':entry['identity'],'decision':'approve','reviewer':'delegated bounded recipe '+RECIPE}]
    result=run_gate(root,[candidate],approvals,budget,output=output,now=now)
    result.update(recipe=RECIPE,recipe_scope='A1 flagship/fleet numeric fields only',observed=values,
        approval_origin='local delegated recipe; not authenticated human/publisher approval',
        unattended_publication_enabled=False,live_cycle_completed=False)
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path('.'));p.add_argument('--evidence',type=Path,required=True);p.add_argument('--stage-to',type=Path);a=p.parse_args()
    try:print(json.dumps(run(a.root,a.evidence,output=a.stage_to),ensure_ascii=False,indent=2))
    except (ValueError,KeyError,TypeError,OSError) as error:
        print('Routine source gate stopped: '+str(error),file=sys.stderr);raise SystemExit(1)
