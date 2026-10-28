import hashlib,io,json,os,re,time,urllib.request,zipfile,xml.etree.ElementTree as E
from pathlib import Path,PurePosixPath

HEAD=os.environ['SOURCE_HEAD'];PR=int(os.environ['SOURCE_PR'])
EXPECTED=set(map(int,os.environ['EXPECTED_RUN_IDS'].split(',')))
API='https://api.github.com/repos/ItukiMac/logbook-kai-maintained'
out=Path('.maintenance-evidence/quest-documents-final-'+os.environ['GITHUB_RUN_ID']+'.json')
out.parent.mkdir(exist_ok=True)
report={'head':HEAD,'pr':PR,'verified':False,'artifact_code_executed':False,'production_accessed':False,'runs':[],'artifacts':[]}

def save():out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
def fetch(path,limit=10*1024*1024):
    request=urllib.request.Request(API+path,headers={'Accept':'application/vnd.github+json'})
    request.add_unredirected_header('Authorization','Bearer '+os.environ['GH_TOKEN'])
    with urllib.request.urlopen(request,timeout=60) as response:data=response.read(limit+1)
    assert len(data)<=limit,'Oversized API response'
    return data

def obj(path):return json.loads(fetch(path))
trees={}
def tree(sha):
    assert re.fullmatch('[a-f0-9]{40}',sha)
    if sha not in trees:trees[sha]=obj('/git/commits/'+sha)['tree']['sha']
    return trees[sha]

def python_counts(text):
    matches=re.findall(r'^Ran (\d+) tests? in [^\n]*\n\s*\n?OK(?: \(skipped=(\d+)\))?\s*$',text,re.M)
    assert matches and not re.search(r'^FAILED \(',text,re.M),'No complete successful Python summary'
    rows=[{'discovered':int(n),'skipped':int(s or 0),'successful':int(n)-int(s or 0)} for n,s in matches]
    outer=max(rows,key=lambda row:row['discovered'])
    assert sum(row['discovered']==outer['discovered'] for row in rows)==1,'Ambiguous enclosing test count'
    return {'outer':outer,'all_summaries':rows}

try:
    for attempt in range(60):
        runs=obj('/actions/runs?head_sha='+HEAD+'&event=pull_request&per_page=100')['workflow_runs']
        assert EXPECTED.issubset({run['id'] for run in runs}),'Missing expected workflow'
        assert all(run['head_sha']==HEAD for run in runs)
        report['runs']=[{k:run[k] for k in ('id','name','head_sha','status','conclusion','run_attempt')} for run in runs];save()
        if any(run['conclusion']=='action_required' for run in runs):raise RuntimeError('Workflow approval required; do not bypass')
        if all(run['status']=='completed' for run in runs):break
        time.sleep(12)
    assert all(run['status']=='completed' and run['conclusion']=='success' for run in runs),report['runs']
    pr=obj('/pulls/'+str(PR));assert pr['head']['sha']==HEAD
    report['source_tree']=tree(HEAD)
    report['pr_state']={k:pr[k] for k in ('state','draft','merged','merge_commit_sha')}
    report['master_at_inspection']=obj('/git/ref/heads/master')['object']['sha']
    needed={'R3 split features, source review and isolated candidate validation':{'r3-evidence-java21','r3-evidence-java25'},
            'Explicit JDK25 JavaFX27 runtime verification (no publish)':{'modern-runtime-evidence-java25'},
            'Runtime migration declared versions (no publish)':{'runtime-migration-java21','runtime-migration-java25'}}
    wanted=set().union(*needed.values());found=set()
    for run in runs:
        if run['name'] not in needed:continue
        all_artifacts=obj('/actions/runs/'+str(run['id'])+'/artifacts?per_page=100')['artifacts']
        for name in needed[run['name']]:
            candidates=[a for a in all_artifacts if a['name']==name and not a['expired']]
            assert candidates,'Missing evidence '+name
            artifact=max(candidates,key=lambda a:(a['created_at'],a['id']))
            assert artifact['size_in_bytes']<=10*1024*1024
            data=fetch('/actions/artifacts/'+str(artifact['id'])+'/zip')
            digest='sha256:'+hashlib.sha256(data).hexdigest();assert digest==artifact['digest'],'Artifact digest mismatch'
            archive=zipfile.ZipFile(io.BytesIO(data));infos=archive.infolist()
            assert sum(i.file_size for i in infos)<=80*1024*1024
            assert len(infos)==len({i.filename for i in infos})
            for i in infos:
                p=PurePosixPath(i.filename);assert not p.is_absolute() and '..' not in p.parts and '\\' not in i.filename
            assert archive.testzip() is None
            item={'id':artifact['id'],'name':name,'run':run['id'],'digest':digest,'java':{},'new_java':{},'python':{},'proofs':{},'other_same_name_ids':[a['id'] for a in candidates if a['id']!=artifact['id']]}
            summaries=[]
            for path in archive.namelist():
                if path.endswith('.xml') and '/TEST-' in '/'+path:
                    suite=E.fromstring(archive.read(path));counts={k:int(suite.get(k,0)) for k in ('tests','failures','errors','skipped')}
                    for k,v in counts.items():item['java'][k]=item['java'].get(k,0)+v
                    if 'QuestDocumentCommands' in suite.get('name',''):item['new_java'][suite.get('name')]=counts
                elif path.endswith('.json') and archive.getinfo(path).file_size<100000:
                    value=json.loads(archive.read(path))
                    if isinstance(value,dict) and 'quest_document_command_tests' in value:summaries.append(value)
                    if isinstance(value,dict) and 'jdk_baseline' in value and 'javafx' in value:item['modern_runtime']=value
                elif path.endswith(('python-scripts.log','python-tools.log')):
                    item['python'][path]=python_counts(archive.read(path).decode('utf8',errors='strict'))
                elif path.endswith('runtime-migration.log'):
                    text=archive.read(path).decode('utf8',errors='strict');item['migration']=python_counts(text)
                    item['migration_markers']=[line for line in text.splitlines() if line.startswith(('MIGRATION_CURRENT_SPLIT_PREFLIGHT_OK','RUNTIME_MIGRATION_'))]
            if name.startswith('runtime-migration'):
                assert item['migration']['outer']=={'discovered':5,'skipped':0,'successful':5}
                assert any('DECLARED_VERSIONS_OK' in line and 'real_launcher=true' in line for line in item['migration_markers'])
                assert any('source_unchanged=true' in line and 'external_unchanged=true' in line and 'old_state_restored=true' in line for line in item['migration_markers'])
            else:
                assert item['java']['failures']==item['java']['errors']==0 and item['java']['skipped']==1
                expected={'logbook.core.QuestDocumentCommandsTest':28,'logbook.internal.gui.QuestDocumentCommandsBoundaryTest':2,'logbook.internal.gui.QuestDocumentCommandsGuiTest':7}
                assert set(item['new_java'])==set(expected)
                for key,n in expected.items():assert item['new_java'][key]=={'tests':n,'failures':0,'errors':0,'skipped':0}
                assert len(summaries)==1
                summary=summaries[0];assert summary['java_tests']==item['java']
                assert summary['quest_document_command_tests']==37 and summary['quest_document_gui_tests']==7
                item['tested_checkout']=summary['checkout'];assert tree(summary['checkout'])==report['source_tree']
                for key in ('quest_document_command_tests','quest_document_gui_tests','quest_visibility','quest_list_read','airbase_status','parallel_integration','seasonal_retreat_integration_tests','isolated_typed_definitions'):
                    item['proofs'][key]=summary[key]
                for key in ('quest_visibility','quest_list_read','airbase_status','parallel_integration'):
                    assert summary[key]['result']=='passed' and not summary[key]['production_accessed']
                assert summary['quest_visibility']['originals_unchanged'] and summary['quest_visibility']['custom_slot_retained']
                assert summary['quest_list_read']['persisted_files_unchanged'] and summary['quest_list_read']['cached_custom_preserved']
                assert summary['parallel_integration']['calculations']==[10,15,10] and summary['parallel_integration']['original_unchanged']
                assert summary['seasonal_retreat_integration_tests']==8 and summary['isolated_typed_definitions']==152
                assert len(item['python'])==2
                for path,value in item['python'].items():
                    assert value['outer']['discovered']>=441 if path.endswith('python-scripts.log') else value['outer']=={'discovered':25,'skipped':0,'successful':25}
                if name.startswith('modern-runtime'):
                    assert item['modern_runtime']['jdk_baseline']==25 and item['modern_runtime']['javafx']=='27.0.1'
            found.add(name);report['artifacts'].append(item);save()
    assert found==wanted
    report['verified']=True;save()
    print('QUEST_DOCUMENT_FINAL_AUDIT_OK',json.dumps({'head':HEAD,'tree':report['source_tree'],'workflows':len(runs),'artifacts':len(found),'merged':pr['merged']}))
except Exception as failure:
    report['error']=type(failure).__name__+': '+str(failure);save();raise
