#!/usr/bin/env python3
"""New trial pack: replace exactly the reviewed A1 definition in a pinned public baseline."""
import argparse,hashlib,json,re,subprocess,sys
from pathlib import Path,PurePosixPath
from zipfile import ZipFile,ZIP_DEFLATED,ZipInfo
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from build_data_pack import normalized_json,write_entry

BASELINE='0673bc4ee9de40f67afbefd8cf17d079860cddb11fe7826da03db3f84466fd70'
TARGET='definitions/mission/1/A1.json'
SOURCE='src/main/resources/logbook/mission/1/A1.json'


def digest(raw):return hashlib.sha256(raw).hexdigest()


def build(root,baseline,output,*,expected=BASELINE):
    root=Path(root).resolve(strict=True);baseline=Path(baseline);output=Path(output).absolute()
    if any(p.is_symlink() for p in (baseline,*baseline.absolute().parents,output,*output.parents)) or output.exists():raise ValueError('New nonsymlink output required')
    if baseline.stat().st_size>16*1024*1024:raise ValueError('Baseline too large')
    raw=baseline.read_bytes()
    if digest(raw)!=expected:raise ValueError('Pinned public baseline mismatch')
    with ZipFile(baseline) as z:
        infos=z.infolist()
        if len(infos)>1000 or len({i.filename for i in infos})!=len(infos) or sum(i.file_size for i in infos)>32*1024*1024:raise ValueError('Invalid baseline scope')
        for i in infos:
            p=PurePosixPath(i.filename)
            if p.is_absolute() or '..' in p.parts or '\\' in i.filename or i.file_size>2*1024*1024 or (i.external_attr>>16)&0o170000==0o120000:raise ValueError('Unsafe baseline entry')
        entries={i.filename:z.read(i) for i in infos}
    manifest=json.loads(entries['manifest.json'])
    if manifest.get('format')!='logbook-data-pack-v1' or not isinstance(manifest.get('files'),list):raise ValueError('Expected legacy baseline')
    names=[row['path'] for row in manifest['files']]
    if len(names)!=len(set(names)) or set(entries)!={'manifest.json',*names} or TARGET not in names:raise ValueError('Baseline manifest scope mismatch')
    for row in manifest['files']:
        data=entries[row['path']]
        if len(data)!=row['bytes'] or digest(data)!=row['sha256']:raise ValueError('Baseline definition mismatch')
        json.loads(data)
    revision=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip()
    source=subprocess.check_output(['git','-C',str(root),'show',revision+':'+SOURCE])
    if source!=(root/SOURCE).read_bytes():raise ValueError('Trial source is not committed')
    changed=normalized_json(source,SOURCE)
    if changed==entries[TARGET]:raise ValueError('Trial has no actual definition change')
    # Require the reviewed scalar outcome, and reject any unrelated A1 change.
    before=json.loads(entries[TARGET]);after=json.loads(changed)
    expected_rule=json.loads(json.dumps(before))
    flags=[]
    def visit(node):
        if isinstance(node,dict):
            if node.get('type')=='艦娘' and node.get('order')==1 and 'level' in node:flags.append(node)
            for value in node.values():visit(value)
        elif isinstance(node,list):
            for value in node:visit(value)
    visit(expected_rule)
    if len(flags)!=1 or flags[0]['level']!=20:raise ValueError('Unexpected baseline A1')
    flags[0]['level']=5
    expected_rule['conditions'].insert(0,{'type':'艦隊','count_type':'レベル','value':10})
    if after!=expected_rule:raise ValueError('Unreviewed A1 delta')
    baseline_revision=manifest['source_revision'];manifest['source_revision']=revision
    for row in manifest['files']:
        if row['path']==TARGET:row.update(sha256=digest(changed),source_sha256=digest(source),bytes=len(changed))
    entries[TARGET]=changed;entries['manifest.json']=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode()
    with output.open('xb') as stream:
        with ZipFile(stream,'w',compression=ZIP_DEFLATED) as z:
            for name,data in sorted(entries.items()):write_entry(z,name,data)
    if digest(baseline.read_bytes())!=expected:raise ValueError('Original baseline changed')
    return {'format':'single-reviewed-definition-trial-v1','baseline_sha256':expected,'baseline_source_revision':baseline_revision,
        'source_revision':revision,'source_path':SOURCE,'source_sha256':digest(source),'trial_sha256':digest(output.read_bytes()),
        'changed_definitions':[TARGET],'definition_count':len(names),'baseline_unchanged':True,'new_release_published':False,'production_applied':False}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path('.'));p.add_argument('--baseline',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    print(json.dumps(build(a.root,a.baseline,a.out),indent=2))
