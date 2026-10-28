#!/usr/bin/env bash
# Isolated CI only. Keeps source and existing build artifacts; refuses an old R3 report directory.
set -euo pipefail
mkdir -p target
mkdir target/r3-verification
EVIDENCE="$PWD/target/r3-verification"
REVISION="$(git rev-parse HEAD)"
printf '%s\n' "$REVISION" > "$EVIDENCE/checkout.txt"
python3 tools/build_data_pack.py --source-sha "$REVISION" --out "$EVIDENCE/data-pack.zip"
xvfb-run -a mvn --batch-mode --no-transfer-progress -DargLine=-XX:-EnableDynamicAgentLoading \
  -Dlogbook.gui.tests=true -Dlogbook.test.dataPackPath="$EVIDENCE/data-pack.zip" verify > "$EVIDENCE/maven.log" 2>&1 || { tail -100 "$EVIDENCE/maven.log"; exit 1; }
python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v > "$EVIDENCE/python-scripts.log" 2>&1
python3 -m unittest discover -s tools/tests -p 'test_*.py' -v > "$EVIDENCE/python-tools.log" 2>&1
python3 scripts/audit_game_json.py --root . > "$EVIDENCE/json-audit.json"
python3 scripts/audit_seasonal_editions.py --root . > "$EVIDENCE/seasonal-editions.json"
python3 scripts/definition_review_ledger.py --facts docs/maintenance/source-field-review-20261026.json > "$EVIDENCE/review-ledger.json"
python3 scripts/build_feature_distribution.py --jar target/logbook-kai.jar --out "$EVIDENCE/split" --revision "$REVISION" > "$EVIDENCE/feature-manifest.json"
python3 scripts/run_feature_distribution.py --root "$EVIDENCE/split"
CP="$EVIDENCE/split/logbook-core.jar:$EVIDENCE/split/features/logbook-feature-calculators.jar:$EVIDENCE/split/features/logbook-feature-charts.jar:$EVIDENCE/split/features/logbook-feature-status.jar"
mkdir "$EVIDENCE/probe-classes"
javac -proc:none --release 21 -cp "$CP" -d "$EVIDENCE/probe-classes" scripts/java/*.java
ISOLATED="$(mktemp -d "${RUNNER_TEMP:-/tmp}/logbook-r3-check.XXXXXX")"
# A fresh fixture root has no configuration/history/plugin files from any user.
(cd "$ISOLATED" && LC_ALL=C.UTF-8 LANG=C.UTF-8 timeout 120s xvfb-run -a java -cp "$CP:$EVIDENCE/probe-classes" logbook.internal.gui.FeatureDistributionProbe) > "$EVIDENCE/split-gui.log" 2>&1
python3 scripts/exercise_isolated_stage.py --out "$ISOLATED/stage" > "$EVIDENCE/stage-fixture.json"
java -cp "$CP:$EVIDENCE/probe-classes" logbook.core.IsolatedDefinitionValidationProbe "$ISOLATED/stage" "$ISOLATED/validation" > "$EVIDENCE/stage-java.log"
java -cp target/logbook-kai.jar:target/test-classes logbook.internal.logger.PackagedLoggingProbe
python3 - <<'PY'
import json, xml.etree.ElementTree as ET
from pathlib import Path
p=Path('target/r3-verification')
suites=[ET.parse(f).getroot() for f in Path('target/surefire-reports').glob('TEST-*.xml')]
totals={k:sum(int(s.get(k,0)) for s in suites) for k in ('tests','failures','errors','skipped')}
assert totals['tests']>=187 and totals['failures']==totals['errors']==0 and totals['skipped']==1,totals
assert all(int(s.get('skipped',0))==0 or s.get('name')=='logbook.core.PublicDataReleaseIntegrationTest' for s in suites)
assert 'SPLIT_GUI_OK features=7 windows=8' in (p/'split-gui.log').read_text()
assert 'ISOLATED_TYPED_VALIDATION_OK files=152' in (p/'stage-java.log').read_text()
assert 'RUNTIME_SNAPSHOT_RESTORE_OK original_unchanged=true restored_bytes_equal=true arrival_records=3 accepted=2' in (p/'python-scripts.log').read_text()
assert 'PARALLEL_BDE_INTEGRATION_OK import_launch_update_newcopy_restore=true calculations=10,15,10 tracking=4 original_unchanged=true' in (p/'python-scripts.log').read_text()
audit=json.loads((p/'json-audit.json').read_text());assert audit['complete'] and audit['counts']['validated']==152
review=json.loads((p/'review-ledger.json').read_text());assert review['definitions']==148 and not review['complete'] and review['whole_rule_verified']==0
assert review['field_comparisons']==77
combined=[json.loads(f.read_text()) for f in p.glob('parallel-integration-*.json')]
assert len(combined)==1 and combined[0]['result']=='passed' and combined[0]['original_unchanged']
assert combined[0]['calculations']==[10,15,10] and combined[0]['tracking_checkpoints']==4
required=[r for r in suites if r.get('name','').endswith('.SeasonalRetreatIntegrationTest')]
assert len(required)==1 and int(required[0].get('tests',0))==8 and int(required[0].get('skipped',0))==0
# Neither a skipped GUI probe nor a hand-repacked test-only archive closes this gate.
record_reports=[json.loads(f.read_text()) for f in p.glob('record-ui-fixture-*.json')]
assert len(record_reports)==1 and record_reports[0]['result']=='passed'
records=record_reports[0]
assert records['product_distribution'] and records['record_ui_profile']=='record-ui-in-charts-v1'
assert records['record_module']=='charts' and records['windows']==3 and records['battle_rows']==3
assert records['original_history_unchanged'] and records['settings_reread'] and records['legacy_missing_experience_bug_fixed']
assert not records['production_accessed']
assert json.loads((p/'feature-manifest.json').read_text())['record_ui_profile']=='record-ui-in-charts-v1'
record_suites=[s for s in suites if s.get('name') in {'logbook.core.RecordDataContractTest','logbook.internal.gui.RecordDataViewTest'}]
assert len(record_suites)==2 and all(int(s.get('skipped',0))==0 for s in record_suites)
record_test_count=sum(int(s.get('tests',0)) for s in record_suites)
assert record_test_count>=24
# Equipment verification uses the actual status module, not a host fallback or skipped test.
equipment_reports=[json.loads(f.read_text()) for f in p.glob('equipment-ui-fixture-*.json')]
assert len(equipment_reports)==1 and equipment_reports[0]['result']=='passed'
equipment=equipment_reports[0]
assert equipment['product_distribution'] and equipment['equipment_ui_profile']=='equipment-ui-in-status-v1'
assert equipment['equipment_module']=='status' and equipment['tabs']==2 and equipment['owned_instances']==6
assert equipment['normal_and_expansion_owner'] and equipment['locked_export']==3
assert equipment['settings_reread'] and equipment['original_history_unchanged'] and equipment['source_jar_unchanged']
assert not equipment['production_accessed']
assert json.loads((p/'feature-manifest.json').read_text())['equipment_ui_profile']=='equipment-ui-in-status-v1'
equipment_suites=[s for s in suites if s.get('name') in {'logbook.core.EquipmentDataContractTest','logbook.internal.gui.EquipmentProjectionTest','logbook.internal.gui.EquipmentRemodelSchemaTest'}]
assert len(equipment_suites)==3 and all(int(s.get('skipped',0))==0 for s in equipment_suites)
equipment_test_count=sum(int(s.get('tests',0)) for s in equipment_suites)
assert equipment_test_count>=28
# User-confirmed label edits must execute the real GUI and command checks.
ship_label_reports=[json.loads(f.read_text()) for f in p.glob('ship-label-fixture-*.json')]
assert len(ship_label_reports)==1 and ship_label_reports[0]['result']=='passed'
ship_labels=ship_label_reports[0]
assert ship_labels['dialog_cancel_no_change'] and ship_labels['dialog_confirm_add_remove']
assert ship_labels['stale_batch_rejected'] and ship_labels['settings_reread'] and ship_labels['original_history_unchanged']
assert not ship_labels['production_accessed']
label_suites=[s for s in suites if s.get('name') in {'logbook.core.ShipLabelCommandTest','logbook.internal.gui.ShipLabelUiBoundaryTest'}]
assert len(label_suites)==2 and all(int(s.get('skipped',0))==0 for s in label_suites)
assert sum(int(s.get('tests',0)) for s in label_suites)>=23
statistics=[json.loads(f.read_text()) for f in p.glob('ship-statistics-fixture-*.json')]
assert len(statistics)==1 and statistics[0]['result']=='passed'
assert statistics[0]['product_distribution'] and statistics[0]['charts_rendered']==5
assert statistics[0]['frozen_after_mutation'] and statistics[0]['source_jar_unchanged']
assert not statistics[0]['production_accessed']
statistics_suites=[s for s in suites if s.get('name') in {'logbook.core.ShipStatisticsContractTest','logbook.internal.gui.ShipStatisticsBoundaryTest'}]
assert len(statistics_suites)==2 and all(int(s.get('skipped',0))==0 for s in statistics_suites)
assert sum(int(s.get('tests',0)) for s in statistics_suites)>=21
ship_list_reports=[json.loads(f.read_text()) for f in p.glob('ship-list-fixture-*.json')]
assert len(ship_list_reports)==1 and ship_list_reports[0]['result']=='passed'
ship_list=ship_list_reports[0]
assert ship_list['product_status_jar'] and ship_list['all_rows']==2 and ship_list['filters']==6
assert ship_list['numeric_controls'] and ship_list['ordered_fleet'] and ship_list['label_tabs']
assert ship_list['sorted_copy'] and ship_list['deck_export'] and ship_list['settings_reread']
assert ship_list['timeline_stopped'] and ship_list['original_history_unchanged'] and not ship_list['production_accessed']
list_suites=[s for s in suites if s.get('name') in {'logbook.internal.gui.ShipListUiBoundaryTest','logbook.internal.gui.ShipListUiContractTest'}]
assert len(list_suites)==2 and all(int(s.get('skipped',0))==0 for s in list_suites)
assert sum(int(s.get('tests',0)) for s in list_suites)>=4
report={'ship_list_ui':ship_list,'ship_statistics':statistics[0],'parallel_integration':combined[0],'seasonal_retreat_integration_tests':8,'checkout':(p/'checkout.txt').read_text().strip(),'java_tests':totals,
        'split_features':7,'split_gui_windows':8,'isolated_typed_definitions':152,
        'record_ui_packaging':records,'record_data_contract_tests':record_test_count,
        'ship_label_editing':ship_labels,'equipment_ui_packaging':equipment,'equipment_data_contract_tests':equipment_test_count,
        'review_ledger':{k:v for k,v in review.items() if k!='entries'},'production_deployed':False}
(p/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
PY
