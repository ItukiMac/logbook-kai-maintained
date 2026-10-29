import hashlib,io,json,os,re,time,urllib.request,zipfile,xml.etree.ElementTree as E
from pathlib import Path,PurePosixPath
HEAD=os.environ['SOURCE_HEAD'];PR=int(os.environ['SOURCE_PR'])
EXPECTED=set(map(int,os.environ['EXPECTED_RUN_IDS'].split(',')))
API='https://api.github.com/repos/ItukiMac/logbook-kai-maintained'
out=Path('.maintenance-evidence/local-quest-final-'+os.environ['GITHUB_RUN_ID']+'.json');out.parent.mkdir(exist_ok=True)
report={'head':HEAD,'pr':PR,'verified':False,'artifact_code_executed':False,'production_accessed':False,'runs':[],'artifacts':[]}
def save():out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
def fetch(path):
    req=urllib.request.Request(API+path,headers={'Accept':'application/vnd.github+json'})
    req.add_unredirected_header('Authorization','Bearer '+os.environ['GH_TOKEN'])
    with urllib.request.urlopen(req,timeout=60) as r:data=r.read(10*1024*1024+1)
    assert len(data)<=10*1024*1024
    return data
def obj(path):return json.loads(fetch(path))
trees={}
def tree(revision):
    assert re.fullmatch('[a-f0-9]{40}',revision)
    if revision not in trees:trees[revision]=obj('/git/commits/'+revision)['tree']['sha']
    return trees[revision]
def python_counts(text):
    matches=re.findall(r'^Ran (\d+) tests? in [^\n]*\n\s*\n?OK(?: \(skipped=(\d+)\))?\s*$',text,re.M)
    assert matches and not re.search(r'^FAILED \(',text,re.M)
    rows=[{'discovered':int(n),'skipped':int(s or 0),'successful':int(n)-int(s or 0)} for n,s in matches]
    outer=max(rows,key=lambda r:r['discovered']);assert sum(r['discovered']==outer['discovered'] for r in rows)==1
    return {'outer':outer,'all_summaries':rows}
try:
    for attempt in range(75):
        runs=obj('/actions/runs?head_sha='+HEAD+'&event=pull_request&per_page=100')['workflow_runs']
        assert EXPECTED.issubset({r['id'] for r in runs})
        assert all(r['head_sha']==HEAD for r in runs)
        report['runs']=[{k:r[k] for k in ('id','name','head_sha','status','conclusion','run_attempt')} for r in runs];save()
        if any(r['conclusion']=='action_required' for r in runs):raise RuntimeError('Approval required, do not bypass')
        if all(r['status']=='completed' for r in runs):break
        time.sleep(12)
    assert all(r['status']=='completed' and r['conclusion']=='success' for r in runs),report['runs']
    pr=obj('/pulls/'+str(PR));assert pr['head']['sha']==HEAD
    report['source_tree']=tree(HEAD);report['master']=obj('/git/ref/heads/master')['object']['sha']
    report['pr_state']={k:pr[k] for k in ('state','draft','merged','merge_commit_sha')}
    store=obj('/contents/src/main/java/logbook/internal/util/QuestDocumentStore.java?ref='+HEAD)
    assert store['sha']=='2482dd2bc0002af9cd14d3ef9ff9b23472211944';report['existing_store_blob']=store['sha']
    needed={'R3 split features, source review and isolated candidate validation':{'r3-evidence-java21','r3-evidence-java25'},
            'Explicit JDK25 JavaFX27 runtime verification (no publish)':{'modern-runtime-evidence-java25'},
            'Runtime migration declared versions (no publish)':{'runtime-migration-java21','runtime-migration-java25'}}
    wanted=set().union(*needed.values());found=set()
    for run in runs:
        if run['name'] not in needed:continue
        all_artifacts=obj('/actions/runs/'+str(run['id'])+'/artifacts?per_page=100')['artifacts']
        for name in needed[run['name']]:
            candidates=[a for a in all_artifacts if a['name']==name and not a['expired']];assert candidates
            a=max(candidates,key=lambda a:(a['created_at'],a['id']));assert a['size_in_bytes']<=10*1024*1024
            data=fetch('/actions/artifacts/'+str(a['id'])+'/zip');digest='sha256:'+hashlib.sha256(data).hexdigest();assert digest==a['digest']
            z=zipfile.ZipFile(io.BytesIO(data));infos=z.infolist();assert sum(i.file_size for i in infos)<=80*1024*1024
            assert len(infos)==len({i.filename for i in infos})
            for i in infos:
                p=PurePosixPath(i.filename);assert not p.is_absolute() and '..' not in p.parts and '\\' not in i.filename
            assert z.testzip() is None
            item={'id':a['id'],'name':name,'run':run['id'],'digest':digest,'java':{},'new_java':{},'python':{},'proofs':{}}
            summaries=[]
            for path in z.namelist():
                if path.endswith('.xml') and '/TEST-' in '/'+path:
                    s=E.fromstring(z.read(path));counts={k:int(s.get(k,0)) for k in ('tests','failures','errors','skipped')}
                    for k,v in counts.items():item['java'][k]=item['java'].get(k,0)+v
                    if 'QuestLocalFiles' in s.get('name',''):item['new_java'][s.get('name')]=counts
                elif path.endswith('.json') and z.getinfo(path).file_size<100000:
                    v=json.loads(z.read(path))
                    if isinstance(v,dict) and 'quest_local_files' in v and 'java_tests' in v:summaries.append(v)
                    if isinstance(v,dict) and 'jdk_baseline' in v and 'javafx' in v:item['modern_runtime']=v
                elif path.endswith(('python-scripts.log','python-tools.log')):
                    item['python'][path]=python_counts(z.read(path).decode('utf8'))
                elif path.endswith('runtime-migration.log'):
                    text=z.read(path).decode('utf8');item['migration']=python_counts(text)
                    item['migration_markers']=[line for line in text.splitlines() if line.startswith(('MIGRATION_CURRENT_SPLIT_PREFLIGHT_OK','RUNTIME_MIGRATION_'))]
            if name.startswith('runtime-migration'):
                assert item['migration']['outer']=={'discovered':5,'skipped':0,'successful':5}
                assert any('DECLARED_VERSIONS_OK' in line and 'real_launcher=true' in line for line in item['migration_markers'])
                assert any('source_unchanged=true' in line and 'external_unchanged=true' in line and 'old_state_restored=true' in line for line in item['migration_markers'])
            else:
                assert item['java']['failures']==item['java']['errors']==0 and item['java']['skipped']==1
                expected={'logbook.core.QuestLocalFilesReadTest':24,'logbook.internal.gui.QuestLocalFilesBoundaryTest':2}
                assert set(item['new_java'])==set(expected)
                for key,n in expected.items():assert item['new_java'][key]=={'tests':n,'failures':0,'errors':0,'skipped':0}
                assert len(summaries)==1;s=summaries[0];assert s['java_tests']==item['java']
                item['tested_checkout']=s['checkout'];assert tree(s['checkout'])==report['source_tree']
                local=s['quest_local_files'];assert local['result']=='passed'
                assert all(local[k] for k in ('product_distribution','same_size_mtime_refresh','custom_and_arsenal','unreadable_visible','external_bytes_preserved','immutable_capture','cached_quests_preserved','source_jar_unchanged'))
                assert not any(local[k] for k in ('production_accessed','new_physical_module','atomic_with_all_writers_claimed','explicit_remove_executed'))
                assert s['quest_document_command_tests']==37 and s['quest_document_gui_tests']==7
                assert s['quest_visibility']['originals_unchanged'] and s['quest_list_read']['persisted_files_unchanged']
                assert s['parallel_integration']['calculations']==[10,15,10] and s['parallel_integration']['original_unchanged']
                assert s['seasonal_retreat_integration_tests']==8 and s['isolated_typed_definitions']==152
                assert s['migration_readiness']['cases']==10 and s['migration_readiness']['errors_propagate']
                for key in ('quest_local_files','quest_document_command_tests','quest_document_gui_tests','migration_readiness','quest_visibility','quest_list_read','airbase_status','parallel_integration'):
                    item['proofs'][key]=s[key]
                assert len(item['python'])==2
                for path,v in item['python'].items():
                    if path.endswith('python-scripts.log'):assert v['outer']['discovered']>=443 and v['outer']['skipped']==1
                    else:assert v['outer']=={'discovered':25,'skipped':0,'successful':25}
                if name.startswith('modern-runtime'):assert item['modern_runtime']['jdk_baseline']==25 and item['modern_runtime']['javafx']=='27.0.1'
            found.add(name);report['artifacts'].append(item);save()
    assert found==wanted;report['verified']=True;save()
    print('LOCAL_QUEST_FINAL_EVIDENCE_OK',json.dumps({'head':HEAD,'tree':report['source_tree'],'artifacts':len(found),'workflows':len(runs),'merged':pr['merged']}))
except Exception as error:
    report['error']=type(error).__name__+': '+str(error);save();raise
