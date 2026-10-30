"""Synthetic source/CI receipts for local guard tests only; never real publisher authorization."""
import copy,json,tempfile
from datetime import datetime,timezone
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import routine_numeric_policy as p
import routine_numeric_cycle as c
import maintenance_workbench as w

A1='src/main/resources/logbook/mission/1/A1.json'
FIFTEEN='src/main/resources/logbook/mission/2/15.json'
FLAG=lambda n:{'type':'艦娘','order':1,'level':n}
FLEET=lambda n:{'type':'艦隊','count_type':'レベル','value':n}
COMP={'operator':'AND','conditions':[{'type':'艦娘','ship_type':['駆逐艦']}]}
RULE=lambda n:{'operator':'AND','conditions':[{'operator':'AND','conditions':[FLAG(n)]},copy.deepcopy(COMP)]}
TOOLS={'python':'3.12.15','java':'21.0.13+9-LTS','maven':'3.9.11','git':'2.56.0'}


def sources(values,kind):
    rows=[]
    for id,fields in values.items():
        flag=fields['flagship_min_level'];fleet=fields.get('fleet:レベル')
        if kind=='wiki':rows += [[id,'synthetic mission'],['Lv'+str(flag)+(' Lv'+str(fleet) if fleet else ''),'reward'],['minimum fixed fleet']]
        else:rows += [['synthetic mission',id+' 00:25'],['編成例','fixed'],['練度','旗艦:'+str(flag)+(' 艦隊:'+str(fleet) if fleet else '')],['ステータス','-'],['出現条件','unused']]
    return ('<table>'+''.join('<tr>'+''.join('<td>'+cell+'</td>' for cell in row)+'</tr>' for row in rows)+'</table>').encode()


class Fixture:
    def __init__(self,test):
        self.temp=tempfile.TemporaryDirectory();test.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name);self.root=self.base/'source';self.root.mkdir();self.evidence=self.base/'evidence';self.evidence.mkdir();self.out=self.base/'candidate-output';self.now=datetime(2026,10,30,tzinfo=timezone.utc)
        a1=RULE(5);a1['conditions'].insert(0,FLEET(10));fifteen=RULE(8);self.docs={A1:a1,FIFTEEN:fifteen}
        self.policy={'format':'logbook-reviewed-numeric-policy-v1','enabled':True,'publication_enabled':True,'max_changed_definitions':20,'paid_provider_calls':0,'definitions':[{'path':A1,'fields':['flagship_min_level','fleet:レベル']},{'path':FIFTEEN,'fields':['flagship_min_level']}],'deferred':[]}
        book={'format':'logbook-reviewed-numeric-facts-v1','whole_rule_verified':False,'definitions':{}}
        for path,doc in self.docs.items():
            file=self.root/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(p.encoded(doc));fields=next(r['fields'] for r in self.policy['definitions'] if r['path']==path)
            book['definitions'][path]={'source_sha256':w.digest(file.read_bytes()),'values':{f:p.value(doc,f) for f in fields}}
        self.old_docs={A1:RULE(20),FIFTEEN:RULE(9)}
        self.old_bytes={k:p.encoded(v) for k,v in self.old_docs.items()}
        self.policy['reviewed_bootstrap']={A1:{'public_definition_sha256':w.digest(self.old_bytes[A1]),'reviewed_source_shape_sha256':w.digest(p.encoded(p.fingerprint(a1,['flagship_min_level','fleet:レベル'])))}}
        (self.root/p.POLICY).parent.mkdir(parents=True);(self.root/p.POLICY).write_bytes(p.encoded(self.policy));(self.root/p.FACTS).write_bytes(p.encoded(book))
        w.git(self.root,'-c','init.templateDir=','init','-q');w.git(self.root,'add','--all');w.git(self.root,'-c','user.name=Fixture','-c','user.email=fixture@localhost','commit','-qm','source')
        self.baseline=self.base/'baseline.zip';entries={'definitions/'+k[len(p.PREFIX):]:v for k,v in self.old_bytes.items()};entries['definitions/quest/1.json']=b'{"untouched":true}'
        manifest={'format':'logbook-data-pack-v1','source_revision':'a'*40,'files':[{'path':n,'bytes':len(v),'sha256':w.digest(v),'source_sha256':w.digest(v)} for n,v in entries.items()]}
        with ZipFile(self.baseline,'w') as z:
            for name,raw in entries.items():c.write_entry(z,name,raw)
            c.write_entry(z,'manifest.json',p.encoded(manifest))
        self.release={'path':self.baseline,'sha256':w.digest(self.baseline.read_bytes()),'release_id':1,'asset_id':2,'asset_name':'logbook-data-pack.zip','tag':'data-v2026.10.25.1','url':'https://github.com/'+c.REPO+'/releases/download/data-v2026.10.25.1/logbook-data-pack.zip','bytes':self.baseline.stat().st_size}
        self.values={'A1':{'flagship_min_level':5,'fleet:レベル':10},'15':{'flagship_min_level':8}}
        self.capture()
    def capture(self,right=None):
        from routine_scalar_candidate import URLS
        self.entries=[]
        for i,kind in enumerate(['wiki','zekamashi']):
            raw=sources(right if right is not None and i else self.values,kind);name=f'source-{i:02d}.bin';(self.evidence/name).write_bytes(raw)
            self.entries.append({'url':URLS[i],'file':name,'status':'captured','checked_at':self.now.isoformat(),'bytes':len(raw),'evidence_sha256':w.digest(raw)})
        self.save_evidence()
    def save_evidence(self,complete=True):
        (self.evidence/'evidence.json').write_bytes(p.encoded({'format':'logbook-public-evidence-v1','complete':complete,'entries':self.entries}))
    def plan(self):return c.plan_numeric(self.root,self.release,self.evidence,now=self.now)
    def prepare(self):
        with patch.object(w,'measure_environment',return_value=TOOLS):return c.prepare(self.root,self.out,baseline=self.release,evidence_root=self.evidence,now=self.now,verify=False)
    def synthetic_verified_receipt(self):
        receipt=self.prepare();receipt.update(status='verified_candidate',data_publication_eligible=True)
        receipt['verification'].update(tests_executed=True,fixed_profiles=['canonical-r3-complete-pack-gui-python-and-packaged-probes'])
        summary={'checkout':receipt['candidate_revision'],'java_tests':{'tests':1094,'failures':0,'errors':0,'skipped':1}}
        raw=p.encoded(summary);(self.out/'canonical-r3-summary.json').write_bytes(raw);receipt['r3_summary_sha256']=w.digest(raw)
        (self.out/'verification-application.jar').write_bytes(b'synthetic-local-guard-fixture-not-a-real-application');receipt['verification_jar_sha256']=w.digest((self.out/'verification-application.jar').read_bytes())
        (self.out/'receipt.json').write_bytes(p.encoded(receipt));return receipt
