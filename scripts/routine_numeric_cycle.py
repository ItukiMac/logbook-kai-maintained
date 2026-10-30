#!/usr/bin/env python3
"""Read-only GitHub source role: live evidence -> new JSON-only candidate -> fixed verification.

Never pushes refs, publishes, loads personal runtime or invokes a paid model.
"""
from __future__ import annotations
import argparse,copy,hashlib,io,json,os,re,subprocess,sys,tempfile
from datetime import datetime,timezone
from pathlib import Path,PurePosixPath
from urllib.request import Request,urlopen
from zipfile import ZipFile,ZIP_DEFLATED
import routine_numeric_policy as p
import maintenance_workbench as w
import maintenance_source_candidate as candidates
from collect_maintenance_sources import collect
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from build_data_pack import normalized_json,write_entry

REPO='ItukiMac/logbook-kai-maintained'
MAX_PACK=16*1024*1024


def gh_json(endpoint,*,data=None):
    if not endpoint.startswith('repos/'+REPO+'/'):raise ValueError('Unexpected GitHub target')
    args=['gh','api',endpoint]
    if data is not None:args+=['--method','POST','--input','-']
    run=subprocess.run(args,input=None if data is None else json.dumps(data).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=30)
    return json.loads(run.stdout,object_pairs_hook=w.unique)


def latest_data_release(releases):
    if not isinstance(releases,list):raise ValueError('Release index required')
    for release in releases:
        tag=release.get('tag_name','')
        if release.get('draft') or release.get('prerelease') or not re.fullmatch(r'data-v[A-Za-z0-9._-]{1,80}',tag):continue
        assets=[a for a in release.get('assets',[]) if a.get('name') in ('logbook-data-pack-v2.zip','logbook-data-pack.zip')]
        if not assets:continue
        # Match production client priority exactly; ambiguous duplicate assets stop.
        preferred='logbook-data-pack-v2.zip' if any(a['name']=='logbook-data-pack-v2.zip' for a in assets) else 'logbook-data-pack.zip'
        assets=[a for a in assets if a['name']==preferred]
        if len(assets)!=1:raise ValueError('Ambiguous data asset')
        asset=assets[0];sha=asset.get('digest','')
        expected='https://github.com/'+REPO+'/releases/download/'+tag+'/'+preferred
        if not re.fullmatch(r'sha256:[0-9a-f]{64}',sha) or asset.get('browser_download_url')!=expected or not 0<asset.get('size',0)<=MAX_PACK:raise ValueError('Untrusted/oversized data asset')
        return {'release_id':release['id'],'tag':tag,'asset_name':preferred,'asset_id':asset['id'],'sha256':sha[7:],'url':expected,'bytes':asset['size']}
    raise ValueError('No trusted stable baseline data release')


def download_public(url,output,expected):
    if not url.startswith('https://github.com/'+REPO+'/releases/download/'):raise ValueError('Untrusted baseline URL')
    # No token is attached to cross-host asset redirects.
    with urlopen(Request(url,headers={'User-Agent':'logbook-numeric-maintenance/1.0'}),timeout=30) as response:
        raw=response.read(MAX_PACK+1)
    if not 0<len(raw)<=MAX_PACK or w.digest(raw)!=expected:raise ValueError('Baseline download mismatch')
    w.write_new(output,raw)


def read_pack(path,expected):
    raw=w.bounded(path,MAX_PACK)
    if w.digest(raw)!=expected:raise ValueError('Pinned baseline mismatch')
    with ZipFile(io.BytesIO(raw)) as z:
        infos=z.infolist()
        if len(infos)>2000 or len({i.filename for i in infos})!=len(infos) or sum(i.file_size for i in infos)>64*1024*1024:raise ValueError('Baseline scope/budget invalid')
        for item in infos:
            name=w.relative(item.filename)
            if name!='manifest.json' and not re.fullmatch(r'definitions/(quest|eventquest|mission|kousyou|map|supplemental)/[A-Za-z0-9_./-]+\.json',name):raise ValueError('Non-definition baseline entry')
            if item.file_size>p.MAX_JSON or (item.external_attr>>16)&0o170000==0o120000:raise ValueError('Unsafe baseline entry')
        entries={i.filename:z.read(i) for i in infos}
    manifest=json.loads(entries['manifest.json'],object_pairs_hook=w.unique)
    if manifest.get('format') not in ('logbook-data-pack-v1','logbook-data-pack-v2') or not isinstance(manifest.get('files'),list):raise ValueError('Unsupported baseline format')
    names=[r['path'] for r in manifest['files']]
    if len(names)!=len(set(names)) or set(entries)!={'manifest.json',*names}:raise ValueError('Baseline manifest inventory mismatch')
    for row in manifest['files']:
        data=entries[row['path']]
        if len(data)!=row['bytes'] or w.digest(data)!=row['sha256']:raise ValueError('Baseline file hash/size mismatch')
        json.loads(data,object_pairs_hook=w.unique)
    return manifest,entries


def plan_numeric(root,baseline,evidence_root,*,now):
    root=w.checked_path(root);policy,facts=p.load_policy(root)
    if policy.get('enabled') is not True:raise ValueError('Numeric policy disabled')
    rows,evidence=p.captured(evidence_root,now)
    manifest,entries=read_pack(baseline['path'],baseline['sha256'])
    updates={};public={};selected=[];new_facts=copy.deepcopy(facts);blocked=[];pending=[]
    for spec in policy['definitions']:
        path=spec['path'];fields=spec['fields'];name='definitions/'+path[len(p.PREFIX):]
        try:
            raw=w.read_source(root,path);doc=p.document(raw);book=facts['definitions'][path]
            if w.digest(raw)!=book['source_sha256'] or {f:p.value(doc,f) for f in fields}!=book['values']:raise ValueError('Current source differs from committed numeric facts')
            mission_id=Path(path).stem
            left=p.source_values(rows['wiki'],'wiki',mission_id);right=p.source_values(rows['zekamashi'],'zekamashi',mission_id)
            if left!=right:raise ValueError('Source disagreement or unsupported extra numeric requirement')
            observed={f:left[f] for f in fields}
            # A newly reported requirement outside the approved selector set is not guessed.
            for field,number in left.items():
                if field not in fields:
                    try:actual=p.value(doc,field)
                    except ValueError:
                        if field=='flagship_min_level' and number==1:continue
                        raise ValueError('Unreviewed extra numeric requirement')
                    if actual!=number:raise ValueError('Unreviewed extra numeric requirement')
            proposed=p.replaced(doc,observed);p.allowed_delta(doc,proposed,fields)
            if name not in entries:raise ValueError('New definitions require review')
            p.allowed_public_delta(entries[name],proposed,fields,path,policy)
            body=raw if proposed==doc else p.encoded(proposed)
            strict=normalized_json(body,path)
            source_changed=body!=raw;public_changed=json.loads(strict)!=json.loads(entries[name])
            if not source_changed and not public_changed:continue
            if len(selected)>=policy['max_changed_definitions']:
                pending.append(path);continue
            if source_changed:updates[path]=body
            selected.append(path)
            if public_changed:public[path]=strict
            new_facts['definitions'][path]={'source_sha256':w.digest(body),'values':observed}
        except (ValueError,KeyError,TypeError,OSError) as error:
            blocked.append({'path':path,'reason':str(error)})
    return {'policy_sha256':w.digest(w.read_source(root,p.POLICY)),'evidence':evidence,'updates':updates,
        'public':public,'selected':selected,'facts':new_facts,'blocked':blocked,'pending_budget':pending,'manifest':manifest,'entries':entries}


def write_pack(plan,revision,output):
    manifest=copy.deepcopy(plan['manifest']);entries=dict(plan['entries']);manifest['source_revision']=revision
    for path in plan['selected']:
        name='definitions/'+path[len(p.PREFIX):];body=plan['public'].get(path,entries[name]);entries[name]=body
        matches=[r for r in manifest['files'] if r['path']==name]
        if len(matches)!=1:raise ValueError('Changed path is not uniquely indexed')
        matches[0].update(sha256=w.digest(body),bytes=len(body),source_sha256=plan['facts']['definitions'][path]['source_sha256'])
    entries['manifest.json']=p.encoded(manifest)
    with output.open('xb') as stream:
        with ZipFile(stream,'w',compression=ZIP_DEFLATED) as z:
            for name,body in sorted(entries.items()):write_entry(z,name,body)
    return w.digest(w.bounded(output,MAX_PACK))


def previous_failure(runs,current_id):
    for run in runs:
        if str(run.get('id'))==str(current_id) or run.get('event')=='pull_request':continue
        if run.get('head_branch')!='master' or run.get('status')!='completed':continue
        return run.get('conclusion') not in ('success','skipped','cancelled')
    return False


def prepare(root,output,*,baseline,evidence_root,now=None,verify=True):
    root=w.checked_path(root);output=w.disjoint_new(output,[root,evidence_root]);now=now or datetime.now(timezone.utc)
    plan=plan_numeric(root,baseline,evidence_root,now=now)
    result={'format':'logbook-routine-numeric-cycle-v1','created_at':now.isoformat(),'base_revision':w.git(root,'rev-parse','HEAD').decode().strip(),
        'policy_sha256':plan['policy_sha256'],'baseline':{k:v for k,v in baseline.items() if k!='path'},'evidence':plan['evidence'],
        'blocked':plan['blocked'],'pending_budget':plan['pending_budget'],'status':'no_numeric_change',
        'paid_provider_calls':0,'production_accessed':False,'live_cycle_completed':False,'data_publication_eligible':False}
    output.mkdir()
    if not plan['selected']:
        w.write_new(output/'receipt.json',p.encoded(result));return result
    original={n:w.digest(w.read_source(root,n)) for n in w.tracked(root)}
    candidate=output/'candidate'
    w.git(root,'clone','--no-hardlinks','--quiet',str(root),str(candidate))
    if w.git(candidate,'rev-parse','HEAD').decode().strip()!=result['base_revision']:raise ValueError('Cloned source moved')
    for path,raw in plan['updates'].items():(candidate/path).write_bytes(raw)
    audit={'format':'logbook-routine-numeric-candidate-v1','base_revision':result['base_revision'],'checked_at':now.isoformat(),
        'policy_sha256':plan['policy_sha256'],'baseline_sha256':baseline['sha256'],'evidence':plan['evidence'],
        'changed_public_definitions':sorted(plan['public']),'reconciled_definitions':sorted(plan['selected']),'changed_source_definitions':sorted(plan['updates']),
        'values':{k:plan['facts']['definitions'][k]['values'] for k in sorted(plan['selected'])},
        'state':'candidate_not_publication_receipt','whole_rule_verified':False}
    (candidate/p.FACTS).write_bytes(p.encoded(plan['facts']));(candidate/p.AUDIT).write_bytes(p.encoded(audit))
    paths=sorted({*plan['updates'],p.FACTS,p.AUDIT})
    w.git(candidate,'add','--force','--',*paths)
    w.git(candidate,'-c','user.name=Routine Numeric Maintenance','-c','user.email=routine@users.noreply.github.com','commit','-qm','Synchronize reviewed numeric definition literals')
    revision=w.git(candidate,'rev-parse','HEAD').decode().strip();tree=w.git(candidate,'rev-parse','HEAD^{tree}').decode().strip()
    changed=[v for v in w.git(candidate,'diff','--name-only','-z',result['base_revision'],'HEAD').decode().split('\0') if v]
    if not set(changed)<={*plan['updates'],p.FACTS,p.AUDIT} or not changed:raise ValueError('Candidate changed executable/unapproved inputs')
    verification={'candidate_revision':revision,'toolchain':w.measure_environment(),'tests_executed':False,'publication_allowed':False}
    if verify:verification=candidates.verify_candidate(candidate,verification,isolated_runner=True)
    result.update(status='verified_candidate' if verify else 'unverified_test_candidate',candidate_revision=revision,candidate_tree=tree,
        verification=verification,changed_source_paths=changed,changed_public_definitions=sorted(plan['public']),reconciled_definitions=sorted(plan['selected']),
        observed_values={k:plan['facts']['definitions'][k]['values'] for k in sorted(plan['selected'])},
        candidate_files={n:w.digest(w.read_source(candidate,n)) for n in changed})
    result['pack_sha256']=write_pack(plan,revision,output/'logbook-data-pack.zip')
    result['asset_name']='logbook-data-pack-v2.zip' if plan['manifest']['format']=='logbook-data-pack-v2' else 'logbook-data-pack.zip'
    # Retained actual public baseline and actual application JAR are verification inputs, never Release assets.
    w.write_new(output/'baseline.zip',w.bounded(baseline['path'],MAX_PACK))
    if verify:
        summary=w.bounded(candidate/'target/r3-verification/summary.json',p.MAX_JSON)
        w.write_new(output/'canonical-r3-summary.json',summary)
        result['r3_summary_sha256']=w.digest(summary)
        w.write_new(output/'verification-application.jar',w.bounded(candidate/'target/logbook-kai.jar',192*1024*1024))
        result['verification_jar_sha256']=w.digest(w.bounded(output/'verification-application.jar',192*1024*1024))
    w.git(candidate,'bundle','create',str(output/'candidate.bundle'),result['base_revision']+'..HEAD')
    result['bundle_sha256']=w.digest(w.bounded(output/'candidate.bundle',32*1024*1024))
    if original!={n:w.digest(w.read_source(root,n)) for n in w.tracked(root)}:raise ValueError('Original source changed')
    result['original_source_unchanged']=True;result['data_publication_eligible']=bool(verify)
    w.write_new(output/'receipt.json',p.encoded(result))
    return result


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=Path('.'));parser.add_argument('--out',type=Path,required=True);parser.add_argument('--resume-after-failure',action='store_true');a=parser.parse_args()
    root=w.checked_path(a.root);temp=Path(tempfile.mkdtemp(prefix='numeric-source-',dir=os.environ.get('RUNNER_TEMP')))
    output=w.checked_path(a.out);output.parent.mkdir(parents=True,exist_ok=True)
    base={'format':'logbook-routine-numeric-cycle-v1','status':'blocked_source','live_cycle_completed':False,'data_publication_eligible':False,'paid_provider_calls':0,'production_accessed':False}
    if not a.resume_after_failure:
        try:
            runs=gh_json('repos/'+REPO+'/actions/workflows/routine-numeric-maintenance.yml/runs?per_page=20')['workflow_runs']
            if previous_failure(runs,os.environ.get('GITHUB_RUN_ID')):raise ValueError('Previous failed numeric cycle requires explicit reviewed resume')
        except subprocess.CalledProcessError:
            # New workflow is not on the default branch yet; PR validation cannot publish.
            if os.environ.get('GITHUB_EVENT_NAME')!='pull_request':raise
    captured=collect(__import__('routine_scalar_candidate').URLS,temp/'sources')
    if not captured['complete']:
        output.mkdir();base['source_status']=[{k:v for k,v in e.items() if k!='file'} for e in captured['entries']];w.write_new(output/'receipt.json',p.encoded(base));print(json.dumps(base));return
    release=latest_data_release(gh_json('repos/'+REPO+'/releases?per_page=100'));release['path']=temp/'baseline.zip'
    download_public(release['url'],release['path'],release['sha256'])
    result=prepare(root,output,baseline=release,evidence_root=temp/'sources')
    print(json.dumps({k:v for k,v in result.items() if k not in ('candidate_files',)},ensure_ascii=False))

if __name__=='__main__':main()
