import copy,hashlib,json
from datetime import datetime,timezone,timedelta
from pathlib import Path
import sys,tempfile,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import routine_numeric_policy as p

FLAG=lambda n:{'type':'艦娘','order':1,'level':n}
FLEET=lambda n:{'type':'艦隊','count_type':'レベル','value':n}
COMP={'type':'艦娘','ship_type':['駆逐艦'],'unknown':{'keep':True}}
RULE=lambda n:{'operator':'AND','conditions':[FLAG(n),copy.deepcopy(COMP)]}

class NumericPolicyTest(unittest.TestCase):
    def test_current_catalog_and_facts_are_complete_and_hash_bound(self):
        root=Path(__file__).resolve().parents[2];policy,facts=p.load_policy(root)
        self.assertEqual(55,len(policy['definitions']));self.assertEqual(62,sum(len(r['fields']) for r in policy['definitions']))
        self.assertFalse(any(r['path'].endswith('/A5.json') for r in policy['definitions']))
        for row in policy['definitions']:
            raw=(root/row['path']).read_bytes();self.assertEqual(p.digest(raw),facts['definitions'][row['path']]['source_sha256'])
            self.assertEqual(facts['definitions'][row['path']]['values'],{f:p.value(p.document(raw),f) for f in row['fields']})
    def test_only_literal_numeric_value_can_change(self):
        before=RULE(5);after=p.replaced(before,{'flagship_min_level':6})
        p.allowed_delta(before,after,['flagship_min_level']);self.assertEqual(5,p.value(before,'flagship_min_level'));self.assertEqual(6,p.value(after,'flagship_min_level'));self.assertEqual(COMP,after['conditions'][1])
    def test_boolean_grouping_order_composition_and_unknown_keys_are_never_normalized_away(self):
        before=RULE(5)
        for mutation in [lambda r:r.update(operator='OR'),lambda r:r['conditions'].reverse(),lambda r:r['conditions'][1].update(ship_type=['海防艦']),lambda r:r['conditions'][1]['unknown'].update(keep=False),lambda r:r.update(extra='unknown')]:
            after=copy.deepcopy(before);mutation(after)
            with self.assertRaises(ValueError):p.allowed_delta(before,after,['flagship_min_level'])
    def test_or_branches_coupled_or_duplicate_numeric_nodes_require_review(self):
        for doc in [{'operator':'OR','conditions':[FLAG(5)]},{'operator':'AND','conditions':[FLAG(5),FLAG(5)]},{**FLAG(5),'ship_type':['駆逐艦']}]:
            with self.assertRaises(ValueError):p.value(doc,'flagship_min_level')
    def test_numeric_ranges_bool_and_fractional_context_rejected(self):
        for value in [True,0,201,5.5,'5']:
            with self.assertRaises(ValueError):p.replaced(RULE(5),{'flagship_min_level':value})
        with self.assertRaises(ValueError):p.document(b'{"unknown":0.1}')
    def test_only_exact_reviewed_bootstrap_bytes_and_shape_are_accepted(self):
        raw=json.dumps(RULE(20),ensure_ascii=False).encode();after=RULE(5);after['conditions'].insert(0,FLEET(10));fields=['flagship_min_level','fleet:レベル'];path='src/main/resources/logbook/mission/1/A1.json'
        policy={'reviewed_bootstrap':{path:{'public_definition_sha256':p.digest(raw),'reviewed_source_shape_sha256':p.digest(p.encoded(p.fingerprint(after,fields)))}}}
        p.allowed_public_delta(raw,after,fields,path,policy)
        with self.assertRaises(ValueError):p.allowed_public_delta(raw+b' ',after,fields,path,policy)
        bad=copy.deepcopy(after);bad['conditions'].append({'type':'艦娘','ship_type':['海防艦']})
        with self.assertRaises(ValueError):p.allowed_public_delta(raw,bad,fields,path,policy)
    def test_wiki_and_zekamashi_extract_ids_levels_and_stats_without_guessing(self):
        wiki=[['B2','name'],['Lv45 Lv220','reward'],['最低4隻 対空160 対潜160 索敵140']]
        zeka=[['name','B2 08:00'],['編成例','unchanged'],['練度','旗艦:45 艦隊:220'],['ステータス','対空160 対潜160 索敵140'],['出現条件','unused']]
        expected={'flagship_min_level':45,'fleet:レベル':220,'fleet:対空':160,'fleet:対潜':160,'fleet:索敵':140}
        self.assertEqual(expected,p.source_values(wiki,'wiki','B2'));self.assertEqual(expected,p.source_values(zeka,'zekamashi','B2'))
    def test_source_layout_duplicates_footnotes_and_conflicting_stat_values_rejected(self):
        valid=[['1','name'],['Lv5','reward'],['対空160']]
        for rows in [valid+valid,[['1','name'],['Lv5*1','reward'],['対空160']],[['1','name'],['Lv5','reward'],['対空160 または 対空170']]]:
            with self.assertRaises(ValueError):p.source_values(rows,'wiki','01')
    def test_unsupported_ids_and_missing_rows_do_not_default_to_zero(self):
        for id in ['../1','A:1','a1','']:
            with self.assertRaises(ValueError):p.normalize_id(id)
        with self.assertRaises(ValueError):p.source_values([],'wiki','1')
    def test_known_live_a1_fixture_is_supported(self):
        self.assertEqual({'flagship_min_level':5,'fleet:レベル':10},p.source_values([['A1','兵站強化任務'],['Lv5 Lv10','15EXP'],['最低4隻']], 'wiki','A1'))
