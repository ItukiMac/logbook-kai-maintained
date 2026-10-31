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
python3 scripts/check_gui_ownership.py --root . > "$EVIDENCE/gui-ownership.json"
python3 scripts/audit_game_json.py --root . > "$EVIDENCE/json-audit.json"
python3 scripts/audit_seasonal_editions.py --root . > "$EVIDENCE/seasonal-editions.json"
python3 scripts/definition_review_ledger.py --facts docs/maintenance/source-field-review-20261026.json > "$EVIDENCE/review-ledger.json"
python3 scripts/build_feature_distribution.py --jar target/logbook-kai.jar --out "$EVIDENCE/split" --revision "$REVISION" > "$EVIDENCE/feature-manifest.json"
python3 scripts/run_feature_distribution.py --root "$EVIDENCE/split"
CP="$EVIDENCE/split/logbook-core.jar:$EVIDENCE/split/features/logbook-feature-calculators.jar:$EVIDENCE/split/features/logbook-feature-charts.jar:$EVIDENCE/split/features/logbook-feature-ships.jar:$EVIDENCE/split/features/logbook-feature-equipment.jar:$EVIDENCE/split/features/logbook-feature-quests.jar:$EVIDENCE/split/features/logbook-feature-records.jar:$EVIDENCE/split/features/logbook-feature-klb.jar:$EVIDENCE/split/features/logbook-feature-kqs.jar"
mkdir "$EVIDENCE/probe-classes"
javac -proc:none --release 21 -cp "$CP" -d "$EVIDENCE/probe-classes" scripts/java/*.java
bash scripts/verify_bundled_plugins.sh "$EVIDENCE/split" > "$EVIDENCE/bundled-plugins.log" 2>&1
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
assert records['product_distribution'] and records['record_ui_profile']=='record-ui-in-records-v2'
assert records['record_module']=='records' and records['windows']==3 and records['battle_rows']==3
assert records['original_history_unchanged'] and records['settings_reread'] and records['legacy_missing_experience_bug_fixed']
assert not records['production_accessed']
assert json.loads((p/'feature-manifest.json').read_text())['record_ui_profile']=='record-ui-in-records-v2'
record_suites=[s for s in suites if s.get('name') in {'logbook.core.RecordDataContractTest','logbook.internal.gui.RecordDataViewTest'}]
assert len(record_suites)==2 and all(int(s.get('skipped',0))==0 for s in record_suites)
record_test_count=sum(int(s.get('tests',0)) for s in record_suites)
assert record_test_count>=24
# Equipment verification uses the actual status module, not a host fallback or skipped test.
equipment_reports=[json.loads(f.read_text()) for f in p.glob('equipment-ui-fixture-*.json')]
assert len(equipment_reports)==1 and equipment_reports[0]['result']=='passed'
equipment=equipment_reports[0]
assert equipment['product_distribution'] and equipment['equipment_ui_profile']=='equipment-ui-in-equipment-v2'
assert equipment['equipment_module']=='equipment' and equipment['tabs']==2 and equipment['owned_instances']==6
assert equipment['normal_and_expansion_owner'] and equipment['locked_export']==3
assert equipment['settings_reread'] and equipment['original_history_unchanged'] and equipment['source_jar_unchanged']
assert not equipment['production_accessed']
assert json.loads((p/'feature-manifest.json').read_text())['equipment_ui_profile']=='equipment-ui-in-equipment-v2'
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
airbase=[json.loads(f.read_text()) for f in p.glob('airbase-status-fixture-*.json')]
assert len(airbase)==1 and airbase[0]['result']=='passed'
assert airbase[0]['product_status_jar'] and airbase[0]['captured_power']==40 and airbase[0]['refresh_power']==60
assert all(airbase[0][k] for k in ('frozen_selection','identity_preserved','sorted_planes','unknown_visible','stale_values_cleared','legacy_export','timeline_stopped','original_history_unchanged','source_jar_unchanged'))
assert not airbase[0]['production_accessed']
airbase_suites=[s for s in suites if s.get('name') in {'logbook.core.AirBaseStatusContractTest','logbook.internal.gui.AirBaseStatusBoundaryTest'}]
assert len(airbase_suites)==2 and all(int(s.get('skipped',0))==0 for s in airbase_suites)
assert sum(int(s.get('tests',0)) for s in airbase_suites)>=23
# Quest list reads cannot silently mutate cached state or pass with a skipped GUI probe.
quest_reports=[json.loads(f.read_text()) for f in p.glob('quest-list-read-fixture-*.json')]
assert len(quest_reports)==1 and quest_reports[0]['result']=='passed'
quest_list=quest_reports[0]
assert quest_list['cached_custom_preserved'] and quest_list['persisted_files_unchanged']
assert quest_list['hash_collision_refresh'] and quest_list['active_custom_inactive_order']
assert quest_list['observed_at_preserved'] and quest_list['unchanged_rows_reused']
assert quest_list['product_distribution'] and not quest_list['production_accessed']
assert not quest_list['explicit_remove_executed'] and not quest_list['atomic_with_all_writers_claimed']
quest_suites=[s for s in suites if s.get('name')=='logbook.core.QuestListReadContractTest']
assert len(quest_suites)==1 and int(quest_suites[0].get('tests',0))>=17 and int(quest_suites[0].get('skipped',0))==0
# Non-destructive quest visibility: actual confirmations, restoration and source preservation are mandatory.
visibility_reports=[json.loads(f.read_text()) for f in p.glob('quest-visibility-fixture-*.json')]
assert len(visibility_reports)==1 and visibility_reports[0]['result']=='passed'
quest_visibility=visibility_reports[0]
assert all(quest_visibility[k] for k in ('product_distribution','confirm_cancel','hide_restore_all','stale_rejected','originals_unchanged','acceptance_unchanged','custom_slot_retained','session_reset_in_new_process','source_jar_unchanged'))
assert not any(quest_visibility[k] for k in ('production_accessed','persisted_hide_state','physical_module_added'))
visibility_suites=[s for s in suites if s.get('name') in {'logbook.core.QuestVisibilityCommandTest','logbook.internal.gui.QuestVisibilityBoundaryTest'}]
assert len(visibility_suites)==2 and sum(int(s.get('tests',0)) for s in visibility_suites)>=23
assert all(int(s.get(k,0))==0 for s in visibility_suites for k in ('failures','errors','skipped'))
# Bound document commands must execute alongside the existing editor and store regressions.
document_suites=[s for s in suites if s.get('name') in {'logbook.core.QuestDocumentCommandsTest','logbook.internal.gui.QuestDocumentCommandsBoundaryTest','logbook.internal.gui.QuestDocumentCommandsGuiTest'}]
assert len(document_suites)==3 and all(int(s.get(k,0))==0 for s in document_suites for k in ('failures','errors','skipped'))
document_test_count=sum(int(s.get('tests',0)) for s in document_suites)
assert document_test_count>=37
document_gui=[s for s in document_suites if s.get('name')=='logbook.internal.gui.QuestDocumentCommandsGuiTest']
assert len(document_gui)==1 and int(document_gui[0].get('tests',0))>=7
# Pending FX observations keep the original deadline; missing GUI/proxy is never success.
readiness_reports=[json.loads(f.read_text()) for f in p.glob('migration-readiness-fixture-*.json')]
assert len(readiness_reports)==1 and readiness_reports[0]['result']=='passed'
readiness=readiness_reports[0]
assert readiness['cases']==10 and readiness['overall_deadline_seconds']==25 and readiness['poll_slice_seconds']==2
assert readiness['errors_propagate'] and readiness['missing_window_rejected'] and readiness['source_jar_unchanged']
assert not readiness['production_accessed'] and not readiness['launcher_initialized']
# Local presentation must detect content-only edits without mutating original files.
local_reports=[json.loads(f.read_text()) for f in p.glob('quest-local-files-fixture-*.json')]
assert len(local_reports)==1 and local_reports[0]['result']=='passed'
local_quests=local_reports[0]
assert all(local_quests[k] for k in ('product_distribution','same_size_mtime_refresh','custom_and_arsenal','unreadable_visible','external_bytes_preserved','immutable_capture','cached_quests_preserved','source_jar_unchanged'))
assert not local_quests['production_accessed'] and not local_quests['explicit_remove_executed'] and not local_quests['new_physical_module']
local_suites=[s for s in suites if s.get('name') in {'logbook.core.QuestLocalFilesReadTest','logbook.internal.gui.QuestLocalFilesBoundaryTest'}]
assert len(local_suites)==2 and sum(int(s.get('tests',0)) for s in local_suites)>=26
assert local_quests['custom_reset_compatibility']==6
progress_suites=[s for s in suites if s.get('name')=='logbook.core.QuestProgressInputTest']
assert len(progress_suites)==1 and int(progress_suites[0].get('tests',0))>=4
assert all(int(progress_suites[0].get(k,0))==0 for k in ('failures','errors','skipped'))
assert all(int(s.get(k,0))==0 for s in local_suites for k in ('failures','errors','skipped'))
# Editor inputs must use read-only projections while retaining existing write safeguards.
editor_expected={'logbook.core.QuestEditorReadTest':26,'logbook.internal.gui.QuestEditorReadBoundaryTest':2,'logbook.internal.gui.QuestEditorReadGuiTest':6}
editor_read_suites=[x for x in suites if x.get('name') in editor_expected]
assert len(editor_read_suites)==3
for x in editor_read_suites:
    assert int(x.get('tests',0))>=editor_expected[x.get('name')]
    assert all(int(x.get(k,0))==0 for k in ('failures','errors','skipped'))
editor_read_count=sum(int(x.get('tests',0)) for x in editor_read_suites)
# Factory-note commands and physical quest UI extraction must actually run.
arsenal_expected={'logbook.core.ArsenalQuestDocumentsTest':11,
    'logbook.internal.gui.ArsenalQuestDocumentsBoundaryTest':1,
    'logbook.internal.gui.ArsenalQuestDocumentsGuiTest':6,
    'logbook.internal.gui.QuestUiPackagingBoundaryTest':1}
arsenal_suites=[x for x in suites if x.get('name') in arsenal_expected]
assert len(arsenal_suites)==len(arsenal_expected)
for x in arsenal_suites:
    assert int(x.get('tests',0))>=arsenal_expected[x.get('name')]
    assert all(int(x.get(k,0))==0 for k in ('failures','errors','skipped'))
quest_ui_reports=[json.loads(f.read_text()) for f in p.glob('quest-ui-fixture-*.json')]
assert len(quest_ui_reports)==1 and quest_ui_reports[0]['result']=='passed'
quest_ui=quest_ui_reports[0]
assert quest_ui['windows']==4 and quest_ui['guarded_note_roundtrip']
assert quest_ui['product_status_jar'] and quest_ui['source_jar_unchanged'] and not quest_ui['production_accessed']
# Host-cooperating writers and public read adapters share one boundary.
host_state=[x for x in suites if x.get('name')=='logbook.core.HostStateBoundaryTest']
assert len(host_state)==1 and int(host_state[0].get('tests',0))>=9
assert all(int(host_state[0].get(k,0))==0 for k in ('failures','errors','skipped'))
quest_replay=[x for x in suites if x.get('name')=='logbook.internal.gui.QuestObservationUiBindingGuiTest']
assert len(quest_replay)==1 and int(quest_replay[0].get('tests',0))>=8
assert all(int(quest_replay[0].get(k,0))==0 for k in ('failures','errors','skipped'))
auxiliary={s.get('name'):s for s in suites if s.get('name') in {'logbook.internal.gui.ScreenCaptureImageTest','logbook.internal.gui.AuxiliaryHostGuiTest','logbook.internal.gui.OfflineTextEditorGuiTest','logbook.internal.gui.HostTimerNotificationGuiTest'}}
assert len(auxiliary)==4
for name,count in [('logbook.internal.gui.ScreenCaptureImageTest',6),('logbook.internal.gui.AuxiliaryHostGuiTest',3),('logbook.internal.gui.OfflineTextEditorGuiTest',3),('logbook.internal.gui.HostTimerNotificationGuiTest',5)]:
    suite=auxiliary[name]
    assert int(suite.get('tests',0))==count and all(int(suite.get(k,0))==0 for k in ('failures','errors','skipped')),name
ownership=json.loads((p/'gui-ownership.json').read_text())
assert ownership['components']==95 and ownership['unclassified']==0 and ownership['owners']['host']==57
report={'timer_notification_gui_tests':5,'auxiliary_image_tests':6,'auxiliary_gui_tests':3,'offline_editor_gui_tests':3,'gui_ownership':ownership,'host_state_boundary_tests':int(host_state[0].get('tests',0)),'quest_observation_gui_tests':int(quest_replay[0].get('tests',0)),'quest_ui_packaging':quest_ui,'arsenal_document_tests':sum(int(x.get('tests',0)) for x in arsenal_suites),'quest_editor_read_tests':editor_read_count,'quest_editor_read_gui_tests':6,'quest_local_files':local_quests,'migration_readiness':readiness,'quest_document_command_tests':document_test_count,'quest_document_gui_tests':int(document_gui[0].get('tests',0)),'quest_visibility':quest_visibility,'airbase_status':airbase[0],'quest_list_read':quest_list,'ship_list_ui':ship_list,'ship_statistics':statistics[0],'parallel_integration':combined[0],'seasonal_retreat_integration_tests':8,'checkout':(p/'checkout.txt').read_text().strip(),'java_tests':totals,
        'split_features':7,'split_gui_windows':8,'isolated_typed_definitions':152,
        'record_ui_packaging':records,'record_data_contract_tests':record_test_count,
        'ship_label_editing':ship_labels,'equipment_ui_packaging':equipment,'equipment_data_contract_tests':equipment_test_count,
        'review_ledger':{k:v for k,v in review.items() if k!='entries'},'production_deployed':False}
(p/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
PY
