#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUNDLE="${1:?Pass a freshly built split distribution}"
CP="$BUNDLE/logbook-core.jar"
for name in calculators charts status klb kqs; do CP="$CP:$BUNDLE/features/logbook-feature-$name.jar"; done
OUT="$(mktemp -d)"
# Keep verification output for inspection. No runtime installation is changed.
javac --release 21 -proc:none -encoding UTF-8 -cp "$CP" -d "$OUT" "$ROOT"/modules/kqs/tests/*.java "$ROOT/scripts/java/BundledPluginWiringProbe.java"
for name in QuestStoreTest QuestExporterTest QuestRegressionTest QuestGenerationTest; do
  java -cp "$OUT:$CP" "local.kancolle.kqs.$name"
done
java -cp "$CP" logbook.core.FeatureBundlePreflight
java -cp "$OUT:$CP" BundledPluginWiringProbe
