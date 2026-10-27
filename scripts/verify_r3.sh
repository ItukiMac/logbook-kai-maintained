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
audit=json.loads((p/'json-audit.json').read_text());assert audit['complete'] and audit['counts']['validated']==152
review=json.loads((p/'review-ledger.json').read_text());assert review['definitions']==148 and not review['complete'] and review['whole_rule_verified']==0
assert review['field_comparisons']==77
report={'checkout':(p/'checkout.txt').read_text().strip(),'java_tests':totals,
        'split_features':7,'split_gui_windows':8,'isolated_typed_definitions':152,
        'review_ledger':{k:v for k,v in review.items() if k!='entries'},'production_deployed':False}
(p/'summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False))
PY
