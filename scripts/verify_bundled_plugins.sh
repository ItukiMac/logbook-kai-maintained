#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="${1:?Pass a freshly built split distribution}"
CP="$BUNDLE/logbook-kai2-core.jar"
for name in calculators charts ships equipment quests records bridge sharing; do CP="$CP:$BUNDLE/features/logbook-kai2-feature-$name.jar"; done
OUT="$(mktemp -d)"
# Keep verification output for inspection. No runtime installation is changed.
javac --release 21 -proc:none -encoding UTF-8 -cp "$CP" -d "$OUT" "$ROOT"/modules/sharing/tests/*.java "$ROOT/scripts/java/BundledPluginWiringProbe.java"
for name in QuestStoreTest QuestExporterTest QuestRegressionTest QuestGenerationTest; do
  java -cp "$OUT:$CP" "logbook.features.sharing.$name"
done
java -cp "$CP" logbook.core.FeatureBundlePreflight
java -cp "$OUT:$CP" BundledPluginWiringProbe
