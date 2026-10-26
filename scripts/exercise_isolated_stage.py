#!/usr/bin/env python3
"""CI fixture only: exercise isolated staging, never create a production approval."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from maintenance_plan import plan
from stage_maintenance_candidates import stage


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,default=Path('.'))
    p.add_argument('--out',type=Path,required=True)
    a=p.parse_args()
    name='src/main/resources/logbook/mission/1/A1.json'
    raw=(a.root/name).read_bytes()
    # A comment-only candidate proves end-to-end changed-byte processing while retaining all game values.
    proposed=raw.decode('utf8')+'\n/* isolated CI staging fixture; not a production change */\n'
    now=datetime(2026,10,26,tzinfo=timezone.utc)
    c=[dict(path=name,content=proposed,expected_old_sha256=hashlib.sha256(raw).hexdigest(),
        upper_bound=dict(input_tokens=0,output_tokens=0,micro_usd=0),
        evidence=[dict(url='https://wikiwiki.jp/kancolle/遠征',checked_at=now.isoformat(),
                       evidence_sha256=hashlib.sha256(b'CI SYNTHETIC FIXTURE').hexdigest(),
                       summary='CI SYNTHETIC FIXTURE. Not a user or source-semantic approval.')])]
    budget=dict(candidates=1,input_tokens=0,output_tokens=0,micro_usd=0)
    record=plan(a.root,c,[],budget,now=now)['entries'][0]
    approvals=[dict(identity=record['identity'],decision='approve',reviewer='CI SYNTHETIC FIXTURE ONLY')]
    result=stage(a.root,a.out,c,approvals,budget,now=now)
    print(json.dumps({'fixture_only':True,'files':result['definition_count'],'pack_sha256':result['candidate_pack_sha256']}))

if __name__=='__main__':main()
