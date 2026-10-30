import copy
from datetime import datetime,timezone,timedelta
import hashlib,json
from pathlib import Path
import subprocess,sys,tempfile,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import routine_scalar_candidate as r

WIKI=b'<table><tr><td>A1</td><td>'+ '兵站強化任務'.encode()+b'</td></tr><tr><td>Lv5<br>Lv10</td><td>reward</td></tr></table>'
ZEKA=('<table><tr><td>兵站強化任務</td><td>A1 00:25</td></tr><tr><td>編成例</td><td>unused</td></tr><tr><td>練度</td><td>旗艦:5<br>艦隊:10</td></tr></table>').encode()
BASE={'operator':'AND','unknown_preserved':{'value':'keep'},'conditions':[{'operator':'AND','conditions':[{'type':'艦娘','level':20,'order':1}]},{'type':'艦娘','ship_type':['駆逐艦']} ]}
class RoutineScalarTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.base=Path(self.temp.name);self.root=self.base/'repo';self.root.mkdir();self.evidence=self.base/'evidence';self.evidence.mkdir()
        self.now=datetime(2026,10,30,tzinfo=timezone.utc)
        for cat in ('quest','eventquest','mission','kousyou','map','supplemental'):
            p=self.root/f'src/main/resources/logbook/{cat}/fixture.json';p.parent.mkdir(parents=True);p.write_text('{}')
        p=self.root/r.PATH;p.parent.mkdir();p.write_text(json.dumps(BASE,ensure_ascii=False))
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        subprocess.run(['git','-C',str(self.root),'add','.'],check=True)
        subprocess.run(['git','-C',str(self.root),'-c','user.name=Fixture','-c','user.email=fixture@localhost','commit','-qm','fixture'],check=True)
        self.rows=[]
        for i,(url,raw) in enumerate(zip(r.URLS,[WIKI,ZEKA])):
            name=f'source-{i:02d}.bin';(self.evidence/name).write_bytes(raw)
            self.rows.append({'url':url,'status':'captured','file':name,'checked_at':self.now.isoformat(),'bytes':len(raw),'evidence_sha256':hashlib.sha256(raw).hexdigest()})
        self.save()
    def save(self,complete=True):
        (self.evidence/'evidence.json').write_text(json.dumps({'format':'logbook-public-evidence-v1','complete':complete,'entries':self.rows}))
    def test_both_sources_are_parsed_without_rule_inference(self):
        self.assertEqual({'flagship':5,'fleet':10},r.levels(WIKI,'wiki'));self.assertEqual(r.levels(WIKI,'wiki'),r.levels(ZEKA,'zekamashi'))
    def test_normal_recipe_stages_exact_scalars_and_preserves_original(self):
        original=(self.root/r.PATH).read_bytes();out=self.base/'stage';result=r.run(self.root,self.evidence,output=out,now=self.now)
        self.assertTrue(result['stage_complete']);self.assertEqual(original,(self.root/r.PATH).read_bytes());self.assertFalse(result['live_cycle_completed']);self.assertFalse(result['publication_allowed'])
        value=json.loads((out/'candidate/mission/1/A1.json').read_text());self.assertEqual(10,value['conditions'][0]['value']);self.assertEqual(5,value['conditions'][1]['conditions'][0]['level']);self.assertEqual(BASE['unknown_preserved'],value['unknown_preserved'])
    def test_agreed_but_unchanged_keeps_original_comments_and_bytes(self):
        content='/*keep comments*/\n'+r.replacement(json.dumps(BASE).encode(),{'flagship':5,'fleet':10})
        (self.root/r.PATH).write_text(content)
        result=r.run(self.root,self.evidence,now=self.now);self.assertEqual('unchanged',result['plan']['entries'][0]['status']);self.assertEqual(content,(self.root/r.PATH).read_text())
    def test_conflicting_sources_stop_before_output(self):
        raw=ZEKA.replace(b':5',b':6');(self.evidence/'source-01.bin').write_bytes(raw);self.rows[1].update(bytes=len(raw),evidence_sha256=hashlib.sha256(raw).hexdigest());self.save()
        with self.assertRaises(ValueError):r.run(self.root,self.evidence,output=self.base/'stage',now=self.now)
        self.assertFalse((self.base/'stage').exists())
    def test_failed_retrieval_and_stale_or_future_evidence_stop(self):
        self.save(False)
        with self.assertRaises(ValueError):r.run(self.root,self.evidence,now=self.now)
        for delta in [timedelta(days=-2),timedelta(seconds=1)]:
            self.rows[0]['checked_at']=(self.now+delta).isoformat();self.save()
            with self.assertRaises(ValueError):r.run(self.root,self.evidence,now=self.now)
    def test_corrupted_response_cannot_use_old_digest(self):
        (self.evidence/'source-00.bin').write_bytes(WIKI+b'changed')
        with self.assertRaises(ValueError):r.run(self.root,self.evidence,now=self.now)
    def test_duplicate_source_and_private_file_reference_refused(self):
        original=copy.deepcopy(self.rows)
        for edit in [lambda rows:rows.__setitem__(1,dict(rows[0])),lambda rows:rows[0].update(file='../private')]:
            self.rows=copy.deepcopy(original);edit(self.rows);self.save()
            with self.assertRaises(ValueError):r.run(self.root,self.evidence,now=self.now)
    def test_duplicate_a1_or_changed_source_layout_is_not_guessed(self):
        for raw in [WIKI+WIKI,WIKI.replace(b'Lv5',b'about5'),WIKI.replace(b'Lv5',b'Lv201')]:
            with self.assertRaises(ValueError):r.levels(raw,'wiki')
    def test_multiple_flagship_predicates_or_or_root_require_manual_review(self):
        for data in [{**BASE,'operator':'OR'},{**BASE,'conditions':BASE['conditions']*2}]:
            with self.assertRaises(ValueError):r.replacement(json.dumps(data).encode(),{'flagship':5,'fleet':10})
    def test_recipe_does_not_rewrite_any_other_condition(self):
        value=json.loads(r.replacement(json.dumps(BASE).encode(),{'flagship':5,'fleet':10}))
        self.assertEqual(BASE['conditions'][1],value['conditions'][2]);self.assertEqual(BASE['unknown_preserved'],value['unknown_preserved'])

    def test_levels_cannot_cross_table_boundary(self):
        for raw, source in [(WIKI, 'wiki'), (ZEKA, 'zekamashi')]:
            separated=raw.replace(b'</tr><tr>',b'</tr></table><table><tr>',1)
            with self.subTest(source=source), self.assertRaises(ValueError):
                r.levels(separated,source)

    def test_zekamashi_cannot_borrow_next_mission_levels(self):
        raw=ZEKA.replace('<tr><td>練度'.encode(),
            '<tr><td>海峡警備行動</td><td>A2 00:55</td></tr><tr><td>練度'.encode())
        with self.assertRaises(ValueError):
            r.levels(raw,'zekamashi')

    def test_cross_table_agreement_cannot_create_stage(self):
        for index in (0,1):
            raw=(WIKI,ZEKA)[index].replace(b'</tr><tr>',b'</tr></table><table><tr>',1)
            (self.evidence/f'source-{index:02d}.bin').write_bytes(raw)
            self.rows[index].update(bytes=len(raw),evidence_sha256=hashlib.sha256(raw).hexdigest())
        self.save()
        with self.assertRaises(ValueError):
            r.run(self.root,self.evidence,output=self.base/'stage',now=self.now)
        self.assertFalse((self.base/'stage').exists())
