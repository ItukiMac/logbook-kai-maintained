import hashlib,io,json,os,re,time,urllib.request,zipfile,xml.etree.ElementTree as E
from pathlib import Path,PurePosixPath
HEAD=os.environ['SOURCE_HEAD'];PR=int(os.environ['SOURCE_PR']);BASE='2aff776dc0112a36c65797dee7192a4a4d77f03e'
EXPECTED=set(map(int,os.environ['EXPECTED_RUN_IDS'].split(',')))
API='https://api.github.com/repos/ItukiMac/logbook-kai-maintained'
out=Path('.maintenance-evidence/editor-read-final-'+os.environ['GITHUB_RUN_ID']+'.json');out.parent.mkdir(exist_ok=True)
report={'head':HEAD,'pr':PR,'base':BASE,'verified':False,'artifact_code_executed':False,'production_accessed':False,'runs':[],'artifacts':[]}
def save():out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
def fetch(path,limit=10*1024*1024):
    req=urllib.request.Request(API+path,headers={'Accept':'application/vnd.github+json'})
    req.add_unredirected_header('Authorization','Bearer '+os.environ['GH_TOKEN'])
    with urllib.request.urlopen(req,timeout=60) as r:data=r.read(limit+1)
    assert len(data)<=limit,'Oversized response'
    return data
def obj(path):return json.loads(fetch(path))
trees={}
def tree(sha):
    assert re.fullmatch('[a-f0-9]{40}',sha)
    if sha not in trees:trees[sha]=obj('/git/commits/'+sha)['tree']['sha']
    return trees[sha]
def python_counts(text):
    matches=re.findall(r'^Ran (\d+) tests? in [^\n]*\n\s*\n?OK(?: \(skipped=(\d+)\))?\s*$',text,re.M)
    assert matches and not re.search(r'^FAILED \(',text,re.M),'No complete success summary'
    rows=[dict(discovered=int(n),skipped=int(s or 0),successful=int(n)-int(s or 0)) for n,s in matches]
    outer=max(rows,key=lambda x:x['discovered']);assert sum(x['discovered']==outer['discovered'] for x in rows)==1
    return {'outer':outer,'all_summaries':rows}
try:
    for attempt in range(70):
        runs=obj('/actions/runs?head_sha='+HEAD+'&event=pull_request&per_page=100')['workflow_runs']
        assert EXPECTED.issubset({r['id'] for r in runs}),'Missing expected workflow'
        assert all(r['head_sha']==HEAD for r in runs)
        report['runs']=[{k:r[k] for k in ('id','name','head_sha','status','conclusion','run_attempt')} for r in runs];save()
        if any(r['conclusion']=='action_required' for r in runs):raise RuntimeError('Execution approval required; do not bypass')
        if all(r['status']=='completed' for r in runs):break
        time.sleep(10)
    assert all(r['status']=='completed' and r['conclusion']=='success' for r in runs),report['runs']
    pr=obj('/pulls/'+str(PR));assert pr['head']['sha']==HEAD and pr['base']['sha']==BASE
    report['source_tree']=tree(HEAD);report['pr_state']={k:pr[k] for k in ('state','draft','merged','merge_commit_sha')}
    report['master']=obj('/git/ref/heads/master')['object']['sha']
    report['preserved_components']={}
    for path in ('src/main/java/logbook/core/QuestDocumentService.java','src/main/java/logbook/core/QuestDocumentValidation.java','src/main/java/logbook/internal/util/QuestDocumentStore.java','src/main/java/logbook/internal/gui/QuestEditorDocument.java','src/main/resources/logbook/gui/quest_condition_edit.fxml'):
        before=obj('/contents/'+path+'?ref='+BASE)['sha'];after=obj('/contents/'+path+'?ref='+HEAD)['sha']
        assert before==after,(path,before,after);report['preserved_components'][path]=after
    needed={'R3 split features, source review and isolated candidate validation':{'r3-evidence-java21','r3-evidence-java25'},'Explicit JDK25 JavaFX27 runtime verification (no publish)':{'modern-runtime-evidence-java25'},'Runtime migration declared versions (no publish)':{'runtime-migration-java21','runtime-migration-java25'}}
    wanted=set().union(*needed.values());found=set()
    for run in runs:
        if run['name'] not in needed:continue
        available=obj('/actions/runs/'+str(run['id'])+'/artifacts?per_page=100')['artifacts']
        for name in needed[run['name']]:
            candidates=[a for a in available if a['name']==name and not a['expired']];assert candidates
            artifact=max(candidates,key=lambda a:(a['created_at'],a['id']));assert artifact['size_in_bytes']<=10*1024*1024
            raw=fetch('/actions/artifacts/'+str(artifact['id'])+'/zip');digest='sha256:'+hashlib.sha256(raw).hexdigest();assert digest==artifact['digest']
            z=zipfile.ZipFile(io.BytesIO(raw));infos=z.infolist()
            assert len(infos)==len({i.filename for i in infos}) and sum(i.file_size for i in infos)<=80*1024*1024
            for i in infos:
                p=PurePosixPath(i.filename);assert not p.is_absolute() and '..' not in p.parts and '\\' not in i.filename
            assert z.testzip() is None
            item={'id':artifact['id'],'name':name,'run':run['id'],'digest':digest,'java':{},'required_suites':{},'python':{},'proofs':{}}
            required={'logbook.core.QuestEditorReadTest':26,'logbook.internal.gui.QuestEditorReadBoundaryTest':2,'logbook.internal.gui.QuestEditorReadGuiTest':6,'logbook.internal.gui.ArrivalConditionEditorGuiTest':12,'logbook.internal.gui.QuestDocumentCommandsGuiTest':7}
            summaries=[]
            for path in z.namelist():
                if path.endswith('.xml') and '/TEST-' in '/'+path:
                    s=E.fromstring(z.read(path));counts={k:int(s.get(k,0)) for k in ('tests','failures','errors','skipped')}
                    for k,v in counts.items():item['java'][k]=item['java'].get(k,0)+v
                    if s.get('name') in required:item['required_suites'][s.get('name')]=counts
                elif path.endswith('.json') and z.getinfo(path).file_size<100000:
                    value=json.loads(z.read(path))
                    if isinstance(value,dict) and 'quest_editor_read_tests' in value:summaries.append(value)
                    if isinstance(value,dict) and 'jdk_baseline' in value and 'javafx' in value:item['modern_runtime']=value
                elif path.endswith(('python-scripts.log','python-tools.log')):item['python'][path]=python_counts(z.read(path).decode('utf8'))
                elif path.endswith('runtime-migration.log'):
                    text=z.read(path).decode('utf8');item['migration']=python_counts(text)
                    item['migration_markers']=[s for s in text.splitlines() if s.startswith(('MIGRATION_CURRENT_SPLIT_PREFLIGHT_OK','RUNTIME_MIGRATION_'))]
            if name.startswith('runtime-migration'):
                assert item['migration']['outer']==dict(discovered=5,skipped=0,successful=5)
                assert any('DECLARED_VERSIONS_OK' in s and 'real_launcher=true' in s for s in item['migration_markers'])
                assert any('source_unchanged=true' in s and 'external_unchanged=true' in s and 'old_state_restored=true' in s for s in item['migration_markers'])
            else:
                assert item['java']==dict(tests=1046,failures=0,errors=0,skipped=1),item['java']
                assert set(item['required_suites'])==set(required)
                for key,n in required.items():assert item['required_suites'][key]==dict(tests=n,failures=0,errors=0,skipped=0)
                assert len(summaries)==1;summary=summaries[0];assert summary['java_tests']==item['java']
                assert summary['quest_editor_read_tests']==34 and summary['quest_editor_read_gui_tests']==6
                assert tree(summary['checkout'])==report['source_tree'];item['tested_checkout']=summary['checkout']
                for k in ('quest_editor_read_tests','quest_editor_read_gui_tests','quest_local_files','quest_document_command_tests','quest_document_gui_tests','migration_readiness','quest_visibility','quest_list_read','airbase_status','parallel_integration'):
                    item['proofs'][k]=summary[k]
                assert summary['quest_document_command_tests']==37 and summary['quest_document_gui_tests']==7
                for k in ('quest_local_files','quest_visibility','quest_list_read','airbase_status','parallel_integration'):
                    assert summary[k]['result']=='passed' and not summary[k]['production_accessed']
                assert summary['quest_local_files']['custom_reset_compatibility']==6 and summary['quest_local_files']['external_bytes_preserved']
                assert summary['quest_visibility']['originals_unchanged'] and summary['quest_visibility']['custom_slot_retained']
                assert summary['quest_list_read']['persisted_files_unchanged']
                assert summary['parallel_integration']['calculations']==[10,15,10] and summary['parallel_integration']['original_unchanged']
                assert summary['seasonal_retreat_integration_tests']==8 and summary['isolated_typed_definitions']==152
                assert len(item['python'])==2
                for path,counts in item['python'].items():
                    assert counts['outer']==(dict(discovered=443,skipped=1,successful=442) if path.endswith('python-scripts.log') else dict(discovered=25,skipped=0,successful=25))
                if name.startswith('modern-runtime'):assert item['modern_runtime']['jdk_baseline']==25 and item['modern_runtime']['javafx']=='27.0.1'
            found.add(name);report['artifacts'].append(item);save()
    assert found==wanted;report['verified']=True;save()
    print('QUEST_EDITOR_READ_FINAL_AUDIT_OK',json.dumps({'head':HEAD,'tree':report['source_tree'],'workflows':len(runs),'artifacts':len(found)}))
except Exception as error:
    report['error']=type(error).__name__+': '+str(error);save();raise
