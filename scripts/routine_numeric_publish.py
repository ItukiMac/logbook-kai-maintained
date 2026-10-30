#!/usr/bin/env python3
"""Isolated publisher role: verify exact JSON-only Git/pack artifacts, then push and release.

Executes no candidate code. Credentials belong only to this separately provisioned job.
"""
from __future__ import annotations
import argparse,json,os,re,subprocess,tempfile
from datetime import datetime,timedelta,timezone
from pathlib import Path
import maintenance_workbench as w
import routine_numeric_policy as p
from routine_numeric_cycle import REPO,MAX_PACK,gh_json,latest_data_release,read_pack


def master_context(context):
    if (context.get('GITHUB_REPOSITORY')!=REPO or context.get('GITHUB_REF')!='refs/heads/master'
            or context.get('GITHUB_EVENT_NAME') not in ('push','schedule','workflow_dispatch')
            or context.get('GITHUB_SERVER_URL','https://github.com')!='https://github.com'):
        raise ValueError('Publication requires the maintained repository trusted master context')


def fresh_evidence(evidence,now):
    from routine_scalar_candidate import URLS
    if not isinstance(evidence,list) or len(evidence)!=2 or {r.get('url') for r in evidence}!=set(URLS):raise ValueError('Two exact source receipts required')
    for row in evidence:
        checked=datetime.fromisoformat(row['checked_at'])
        if checked.tzinfo is None or not timedelta(0)<=now-checked<=timedelta(hours=24):raise ValueError('Publication source evidence expired')
        if not re.fullmatch(r'[0-9a-f]{64}',row.get('evidence_sha256','')) or type(row.get('bytes')) is not int or not 0<row['bytes']<=p.MAX_JSON:raise ValueError('Invalid evidence identity')


def verify_transfer(root,artifact,*,now=None):
    root=w.checked_path(root);artifact=w.checked_path(artifact);now=now or datetime.now(timezone.utc)
    receipt=p.read_json(artifact/'receipt.json');policy,base_facts=p.load_policy(root)
    if policy.get('publication_enabled') is not True:raise ValueError('Reviewed numeric publication policy disabled')
    if (receipt.get('format')!='logbook-routine-numeric-cycle-v1' or receipt.get('status')!='verified_candidate'
            or receipt.get('data_publication_eligible') is not True or receipt.get('paid_provider_calls')!=0
            or receipt.get('production_accessed') is not False):raise ValueError('Missing completed candidate verification')
    if receipt.get('verification',{}).get('tests_executed') is not True or receipt['verification'].get('fixed_profiles')!=['canonical-r3-complete-pack-gui-python-and-packaged-probes']:raise ValueError('Fixed verification not executed')
    summary_raw=w.bounded(artifact/'canonical-r3-summary.json',p.MAX_JSON)
    if w.digest(summary_raw)!=receipt.get('r3_summary_sha256'):raise ValueError('Canonical verification summary changed')
    summary=json.loads(summary_raw,object_pairs_hook=w.unique)
    totals=summary.get('java_tests',{})
    if summary.get('checkout')!=receipt.get('candidate_revision') or totals.get('tests',0)<1000 or any(totals.get(k)!=0 for k in ('failures','errors')) or totals.get('skipped')!=1:raise ValueError('Incomplete canonical Java verification')
    fresh_evidence(receipt['evidence'],now)
    if receipt['policy_sha256']!=w.digest(w.read_source(root,p.POLICY)):raise ValueError('Publisher policy changed')
    base=receipt['base_revision'];revision=receipt['candidate_revision']
    if not re.fullmatch(r'[0-9a-f]{40}',base) or not re.fullmatch(r'[0-9a-f]{40}',revision):raise ValueError('Exact Git identities required')
    if w.git(root,'rev-parse','HEAD').decode().strip()!=base:raise ValueError('Publisher checkout does not match validated base')
    if receipt['verification']['candidate_revision']!=revision:raise ValueError('Verification revision mismatch')
    bundle=artifact/'candidate.bundle'
    if w.digest(w.bounded(bundle,32*1024*1024))!=receipt['bundle_sha256']:raise ValueError('Git transfer digest mismatch')
    # Fetch public Git objects only. Never check out or execute the candidate in this credentialed job.
    w.git(root,'bundle','verify',str(bundle))
    w.git(root,'fetch','--quiet',str(bundle),'HEAD:refs/heads/routine-numeric-candidate')
    if w.git(root,'rev-parse','refs/heads/routine-numeric-candidate').decode().strip()!=revision:raise ValueError('Transferred Git ref mismatch')
    if w.git(root,'rev-list','--parents','-n','1',revision).decode().split()!=[revision,base]:raise ValueError('Candidate is not one direct child of validated base')
    if w.git(root,'rev-parse',revision+'^{tree}').decode().strip()!=receipt['candidate_tree']:raise ValueError('Candidate tree mismatch')
    paths={v for v in w.git(root,'diff','--name-only','-z',base,revision).decode().split('\0') if v}
    allowed={r['path'] for r in policy['definitions']}|{p.FACTS,p.AUDIT}
    if not paths or not paths<=allowed or paths!=set(receipt['changed_source_paths']) or paths!=set(receipt['candidate_files']):raise ValueError('Candidate changed executable/unapproved paths')
    for path in paths:
        w.relative(path)
        if w.digest(w.git(root,'show',revision+':'+path))!=receipt['candidate_files'][path]:raise ValueError('Candidate blob mismatch')
    new_facts=json.loads(w.git(root,'show',revision+':'+p.FACTS),object_pairs_hook=w.unique)
    if set(new_facts)!=set(base_facts) or new_facts.get('format')!=base_facts['format'] or new_facts.get('whole_rule_verified') is not False or set(new_facts['definitions'])!=set(base_facts['definitions']):raise ValueError('Facts schema/scope changed')
    numeric={r['path']:r['fields'] for r in policy['definitions']}
    source_docs={}
    for path,fields in numeric.items():
        raw=w.git(root,'show',revision+':'+path);doc=p.document(raw);book=new_facts['definitions'][path]
        if set(book)!={'source_sha256','values'} or book['source_sha256']!=w.digest(raw) or book['values']!={f:p.value(doc,f) for f in fields}:raise ValueError('Candidate source/facts differ')
        if path in paths:p.allowed_delta(p.document(w.read_source(root,path)),doc,fields)
        source_docs[path]=(raw,doc)
    pack_manifest,pack=read_pack(artifact/'logbook-data-pack.zip',receipt['pack_sha256'])
    old_manifest,old=read_pack(artifact/'baseline.zip',receipt['baseline']['sha256'])
    if pack_manifest.get('source_revision')!=revision or set(pack)!=set(old):raise ValueError('Pack generation/inventory changed')
    if {k:v for k,v in pack_manifest.items() if k not in ('source_revision','files')}!={k:v for k,v in old_manifest.items() if k not in ('source_revision','files')}:raise ValueError('Pack capabilities/format changed')
    changed={p.PREFIX+n[len('definitions/'):] for n in pack if n!='manifest.json' and pack[n]!=old[n]}
    if changed!=set(receipt['changed_public_definitions']) or not changed<=set(numeric):raise ValueError('Non-numeric public payload changed')
    reconciled=set(receipt['reconciled_definitions'])
    if not changed<=reconciled or not reconciled<=set(numeric) or not 0<len(reconciled)<=policy['max_changed_definitions']:raise ValueError('Reconciled scope/budget invalid')
    new_records={r['path']:r for r in pack_manifest['files']};old_records={r['path']:r for r in old_manifest['files']}
    for name,row in new_records.items():
        path=p.PREFIX+name[len('definitions/'):]
        if path not in reconciled:
            if row!=old_records[name] or pack[name]!=old[name]:raise ValueError('Unapproved definition/metadata changed')
            continue
        raw,doc=source_docs[path]
        if json.loads(pack[name],object_pairs_hook=w.unique)!=doc or row.get('source_sha256')!=w.digest(raw):raise ValueError('Published literal differs from committed source')
        p.allowed_public_delta(old[name],doc,numeric[path],path,policy)
        if receipt['observed_values'][path]!={f:p.value(doc,f) for f in numeric[path]}:raise ValueError('Source observations do not match candidate values')
    if w.digest(w.bounded(artifact/'verification-application.jar',192*1024*1024))!=receipt['verification_jar_sha256']:raise ValueError('Verification JAR changed')
    expected_asset='logbook-data-pack-v2.zip' if pack_manifest['format']=='logbook-data-pack-v2' else 'logbook-data-pack.zip'
    if receipt['asset_name']!=expected_asset:raise ValueError('Wrong compatibility asset name')
    return receipt


def gh_write(method,endpoint,data):
    if not endpoint.startswith('repos/'+REPO+'/'):raise ValueError('Wrong writer target')
    run=subprocess.run(['gh','api','--method',method,endpoint,'--input','-'],input=json.dumps(data).encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=30)
    return json.loads(run.stdout,object_pairs_hook=w.unique)


def run_gh(args,*,timeout=180):
    return subprocess.run(['gh',*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=timeout)


def publish(root,artifact,*,context=None,now=None):
    context=dict(os.environ) if context is None else context;master_context(context)
    now=now or datetime.now(timezone.utc);root=w.checked_path(root);artifact=w.checked_path(artifact)
    receipt=verify_transfer(root,artifact,now=now)
    base=receipt['base_revision'];revision=receipt['candidate_revision']
    current=gh_json('repos/'+REPO+'/git/ref/heads/master')['object']['sha']
    baseline=latest_data_release(gh_json('repos/'+REPO+'/releases?per_page=100'))
    if current!=base:raise ValueError('Master advanced since verification; no merge/rebase/push attempted')
    if (baseline['release_id'],baseline['sha256'])!=(receipt['baseline']['release_id'],receipt['baseline']['sha256']):raise ValueError('Public baseline changed; revalidation required')
    number=context.get('GITHUB_RUN_NUMBER','')
    if not re.fullmatch(r'[1-9][0-9]{0,8}',number):raise ValueError('Bounded workflow sequence required')
    tag='data-v'+now.strftime('%Y.%m.%d')+'.'+number
    # A normal non-force push is the atomic fast-forward guard. A concurrent writer causes rejection.
    w.git(root,'-c','credential.https://github.com.helper=!gh auth git-credential','push','origin',revision+':refs/heads/master')
    public={k:v for k,v in receipt.items() if k not in ('candidate_files',)}
    public.update(tag=tag,source_commit=revision,kind='reviewed_numeric_only',whole_rule_verified=False)
    files=Path(tempfile.mkdtemp(prefix='numeric-publication-',dir=context.get('RUNNER_TEMP')))
    asset=files/receipt['asset_name'];w.write_new(asset,w.bounded(artifact/'logbook-data-pack.zip',MAX_PACK))
    audit=files/'routine-numeric-receipt.json';w.write_new(audit,p.encoded(public))
    sums=files/'SHA256SUMS-data.txt';w.write_new(sums,(receipt['pack_sha256']+'  '+asset.name+'\n').encode())
    notes=('Verified numeric-only data update. No application JAR or runtime is published.\n'
        +'Source commit: '+revision+'\nBaseline: '+receipt['baseline']['tag']+'\n'
        +'Changed definitions: '+', '.join(receipt['changed_public_definitions'])+'\n'
        +'SHA256: '+receipt['pack_sha256']+'\nTwo live permitted source observations are in the receipt; no whole-rule certification.\n')
    draft=gh_write('POST','repos/'+REPO+'/releases',{'tag_name':tag,'target_commitish':revision,'name':'Game data '+tag,'body':notes,'draft':True,'prerelease':False,'make_latest':'false'})
    release_id=draft['id']
    draft_state={'format':'logbook-numeric-publication-v1','tag':tag,'release_id':release_id,'url':draft['html_url'],'source_commit':revision,'pack_sha256':receipt['pack_sha256'],'base_revision':base,'published':False,'run_id':context.get('GITHUB_RUN_ID'),'paid_provider_calls':0}
    w.write_new(artifact/'publication-draft.json',p.encoded(draft_state))
    run_gh(['release','upload',tag,str(asset),str(audit),str(sums),'--repo',REPO])
    assets=gh_json('repos/'+REPO+'/releases/'+str(release_id)+'/assets')
    if {a['name'] for a in assets}!={asset.name,audit.name,sums.name}:raise ValueError('Draft asset inventory mismatch; draft retained')
    desired={asset.name:receipt['pack_sha256'],audit.name:w.digest(audit.read_bytes()),sums.name:w.digest(sums.read_bytes())}
    for item in assets:
        if item.get('digest')!='sha256:'+desired[item['name']]:raise ValueError('Uploaded draft asset hash mismatch; draft retained')
    published=gh_write('PATCH','repos/'+REPO+'/releases/'+str(release_id),{'draft':False,'make_latest':'false'})
    if published.get('draft') or published.get('prerelease') or published.get('tag_name')!=tag:raise ValueError('Release publication state unexpected')
    result={'format':'logbook-numeric-publication-v1','tag':tag,'release_id':release_id,'url':published['html_url'],
        'source_commit':revision,'pack_sha256':receipt['pack_sha256'],'base_revision':base,'published':True,
        'consumer_verified':False,'production_runtime_changed':False,'paid_provider_calls':0,'run_id':context.get('GITHUB_RUN_ID')}
    w.write_new(artifact/'publication.json',p.encoded(result));return result


def withdraw(artifact,*,context=None):
    context=dict(os.environ) if context is None else context;master_context(context);artifact=w.checked_path(artifact)
    path=artifact/'publication.json'
    if not path.exists():path=artifact/'publication-draft.json'
    if not path.exists():return {'no_owned_publication_to_withdraw':True,'original_release_deleted':False}
    result=p.read_json(path)
    if str(result.get('run_id'))!=str(context.get('GITHUB_RUN_ID')):raise ValueError('Publication belongs to a different workflow run')
    release=gh_json('repos/'+REPO+'/releases/'+str(result['release_id']))
    if release.get('tag_name')!=result['tag'] or release.get('target_commitish')!=result['source_commit']:raise ValueError('Refusing to withdraw a different publication')
    if release.get('draft'):return {'retained_draft':True,'release_id':result['release_id'],'original_release_deleted':False}
    assets=[r for r in release.get('assets',[]) if r['name'] in ('logbook-data-pack.zip','logbook-data-pack-v2.zip')]
    if len(assets)!=1 or assets[0].get('digest')!='sha256:'+result['pack_sha256']:raise ValueError('Refusing to withdraw changed assets')
    gh_write('PATCH','repos/'+REPO+'/releases/'+str(result['release_id']),{'draft':True,'make_latest':'false'})
    return {'withdrawn_to_retained_draft':True,'release_id':result['release_id'],'source_git_reverted':False,'original_release_deleted':False}

if __name__=='__main__':
    a=argparse.ArgumentParser(description=__doc__);a.add_argument('action',choices=['publish','withdraw']);a.add_argument('--root',type=Path,default=Path('.'));a.add_argument('--artifact',type=Path,required=True);args=a.parse_args()
    print(json.dumps(publish(args.root,args.artifact) if args.action=='publish' else withdraw(args.artifact),indent=2))
