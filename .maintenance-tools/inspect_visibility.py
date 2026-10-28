"""Inspect downloaded reports only; never execute artifact code or modify product branches."""
import hashlib,io,json,os,re,time,urllib.request,zipfile,xml.etree.ElementTree as ET
from pathlib import Path,PurePosixPath
HEAD=os.environ['PRODUCT_HEAD'];PR=int(os.environ['PRODUCT_PR'])
API='https://api.github.com/repos/ItukiMac/logbook-kai-maintained'
OUT=Path('.maintenance-evidence/quest-visibility-final-'+os.environ['GITHUB_RUN_ID']+'.json')
OUT.parent.mkdir(exist_ok=True)
REPORT={'head':HEAD,'pr':PR,'verified':False,'artifact_code_executed':False,'production_accessed':False,'runs':[],'artifacts':[]}
def save():OUT.write_text(json.dumps(REPORT,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
def read(path,limit=10*1024*1024):
    request=urllib.request.Request(API+path,headers={'Accept':'application/vnd.github+json'})
    request.add_unredirected_header('Authorization','Bearer '+os.environ['GH_TOKEN'])
    with urllib.request.urlopen(request,timeout=60) as response:raw=response.read(limit+1)
    assert len(raw)<=limit,'Response size exceeded'
    return raw
def obj(path):return json.loads(read(path))
TREES={}
def tree(revision):
    if revision not in TREES:TREES[revision]=obj('/git/commits/'+revision)['tree']['sha']
    return TREES[revision]
def python_counts(text):
    matches=re.findall(r'^Ran (\d+) tests? in [^\n]*\n\s*\n?OK(?: \(skipped=(\d+)\))?\s*$',text,re.M)
    assert matches and not re.search(r'^FAILED \(',text,re.M),'Missing successful outer test summary'
    counts=[{'discovered':int(n),'skipped':int(s or 0),'successful':int(n)-int(s or 0)} for n,s in matches]
    result=max(counts,key=lambda item:item['discovered'])
    assert sum(item['discovered']==result['discovered'] for item in counts)==1,'Ambiguous enclosing suite'
    return {'outer':result,'all_summaries':counts}
try:
    assert re.fullmatch('[0-9a-f]{40}',HEAD)
    for _ in range(48):
        runs=obj('/actions/runs?event=pull_request&head_sha='+HEAD+'&per_page=100')['workflow_runs']
        assert len(runs)>=15 and all(r['head_sha']==HEAD for r in runs),'Missing exact-head workflows'
        REPORT['runs']=[{k:r[k] for k in ('id','name','head_sha','status','conclusion','run_attempt')} for r in runs];save()
        if all(r['status']=='completed' for r in runs):break
        time.sleep(15)
    assert all(r['status']=='completed' and r['conclusion']=='success' for r in runs),'Incomplete or failed exact-head CI'
    REPORT['source_tree']=tree(HEAD)
    pr=obj('/pulls/'+str(PR));assert pr['head']['sha']==HEAD
    REPORT['pr_state']={k:pr[k] for k in ('state','draft','merged','merge_commit_sha')}
    REPORT['master_at_inspection']=obj('/git/ref/heads/master')['object']['sha']
    wanted={'r3-evidence-java21','r3-evidence-java25','modern-runtime-evidence-java25','runtime-migration-java21','runtime-migration-java25'}
    selected=[]
    for run in runs:
        if run['name'] not in ('R3 split features, source review and isolated candidate validation',
                              'Explicit JDK25 JavaFX27 runtime verification (no publish)',
                              'Runtime migration declared versions (no publish)'):continue
        available=obj('/actions/runs/'+str(run['id'])+'/artifacts?per_page=100')['artifacts']
        # Never silently discard a failed earlier artifact with the same name.
        relevant=[a for a in available if a['name'] in wanted]
        assert len({a['name'] for a in relevant})==len(relevant),'Multiple attempts require explicit inspection'
        selected.extend((run,a) for a in relevant)
    assert {a['name'] for _,a in selected}==wanted
    for run,a in selected:
        assert not a['expired'] and a['size_in_bytes']<=10*1024*1024 and a['workflow_run']['head_sha']==HEAD
        raw=read('/actions/artifacts/'+str(a['id'])+'/zip');digest='sha256:'+hashlib.sha256(raw).hexdigest()
        assert digest==a['digest'],'Artifact byte digest mismatch'
        archive=zipfile.ZipFile(io.BytesIO(raw));infos=archive.infolist()
        assert len(infos)==len({i.filename for i in infos}) and sum(i.file_size for i in infos)<=100*1024*1024
        for info in infos:
            path=PurePosixPath(info.filename)
            assert not path.is_absolute() and '..' not in path.parts and '\\' not in info.filename
        assert archive.testzip() is None
        result={'id':a['id'],'name':a['name'],'run':run['id'],'digest':digest,'java':{},'focused':{},'python':{},'proofs':{}}
        docs={};skips=[]
        for name in archive.namelist():
            if name.endswith('.xml') and '/TEST-' in '/'+name:
                xml=archive.read(name);assert b'<!DOCTYPE' not in xml.upper()
                suite=ET.fromstring(xml);counts={k:int(suite.get(k,0)) for k in ('tests','failures','errors','skipped')}
                for key,value in counts.items():result['java'][key]=result['java'].get(key,0)+value
                if 'QuestVisibility' in suite.get('name',''):result['focused'][suite.get('name')]=counts
                if counts['skipped']:skips.append(suite.get('name'))
            elif name.endswith('.json') and archive.getinfo(name).file_size<100000:docs[name]=json.loads(archive.read(name))
            elif name.endswith(('python-scripts.log','python-tools.log')):
                result['python'][name.split('/')[-1]]=python_counts(archive.read(name).decode('utf8'))
            elif name.endswith('runtime-migration.log'):
                text=archive.read(name).decode('utf8');result['migration']=python_counts(text)
                result['migration_markers']=[l for l in text.splitlines() if l.startswith(('MIGRATION_CURRENT_SPLIT_PREFLIGHT_OK','RUNTIME_MIGRATION_JAVA_OK','RUNTIME_MIGRATION_DECLARED_VERSIONS_OK','RUNTIME_MIGRATION_TEST_SUMMARY'))]
        if a['name'].startswith('runtime-migration'):
            assert result['migration']['outer']=={'discovered':5,'skipped':0,'successful':5}
            assert any('real_launcher=true' in l and 'source_unchanged=true' in l and 'external_unchanged=true' in l for l in result['migration_markers'])
            assert any('DECLARED_VERSIONS_OK' in l and 'real_launcher=true' in l for l in result['migration_markers'])
        else:
            assert result['java']['failures']==result['java']['errors']==0 and result['java']['skipped']==1
            assert skips==['logbook.core.PublicDataReleaseIntegrationTest'],skips
            assert len(result['focused'])==2 and sum(s['tests'] for s in result['focused'].values())==23
            assert all(s[k]==0 for s in result['focused'].values() for k in ('failures','errors','skipped'))
            summaries=[d for d in docs.values() if isinstance(d,dict) and 'quest_visibility' in d and 'java_tests' in d]
            assert len(summaries)==1;summary=summaries[0];assert summary['java_tests']==result['java']
            assert tree(summary['checkout'])==REPORT['source_tree'];result['tested_checkout']=summary['checkout']
            visibility=summary['quest_visibility']
            assert visibility['result']=='passed'
            assert all(visibility[k] for k in ('product_distribution','confirm_cancel','hide_restore_all','stale_rejected','originals_unchanged','acceptance_unchanged','custom_slot_retained','session_reset_in_new_process','source_jar_unchanged'))
            assert not any(visibility[k] for k in ('production_accessed','persisted_hide_state','physical_module_added'))
            assert summary['airbase_status']['result']=='passed' and summary['airbase_status']['captured_power']==40 and summary['airbase_status']['refresh_power']==60
            assert summary['quest_list_read']['result']=='passed' and summary['quest_list_read']['cached_custom_preserved']
            assert summary['parallel_integration']['calculations']==[10,15,10] and summary['parallel_integration']['original_unchanged']
            assert summary['seasonal_retreat_integration_tests']==8 and summary['isolated_typed_definitions']==152
            assert result['python']['python-scripts.log']['outer']['discovered']>440
            assert result['python']['python-scripts.log']['outer']['skipped']==1
            assert result['python']['python-tools.log']['outer']=={'discovered':25,'skipped':0,'successful':25}
            result['proofs']={k:summary[k] for k in ('quest_visibility','airbase_status','quest_list_read','seasonal_retreat_integration_tests','isolated_typed_definitions')}
            if a['name'].startswith('modern'):
                modern=[d for d in docs.values() if isinstance(d,dict) and d.get('javafx')=='27.0.1' and d.get('jdk_baseline')==25]
                assert modern,'Actual modern runtime proof missing';result['modern_runtime']=modern[0]
        REPORT['artifacts'].append(result);save()
    comparable=[a for a in REPORT['artifacts'] if a['java']]
    assert len(comparable)==3
    assert all(a['java']==comparable[0]['java'] for a in comparable)
    assert all(a['python']['python-scripts.log']['outer']==comparable[0]['python']['python-scripts.log']['outer'] for a in comparable)
    REPORT['verified']=True;save()
    print('QUEST_VISIBILITY_EVIDENCE_OK',HEAD,REPORT['source_tree'])
except Exception as error:
    REPORT['error']=type(error).__name__+': '+str(error);save();raise
