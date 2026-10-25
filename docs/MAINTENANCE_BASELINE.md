# Maintained fork: migration baseline (A1)

**Status:** fork verified; first inventory completed; full functional inventory and binary/GUI verification pending.

## Provenance
- Upstream: https://github.com/sakura0689/logbook-kai (MIT; retain original notices).
- Fork: https://github.com/ItukiMac/logbook-kai-maintained
- Verified baseline (2026-10-25 JST): `2090aee9092c7b6a264a82b79a170312a74d5d0b` on upstream and fork `master`.
- Upstream comparison at baseline: identical (ahead 0, behind 0).
- This is a **source/CI-only** migration. Do not replace the Linux Mint installation, `auto.sh`, `config/`, `plugins/`, KLB, or KQS.

## Initial repository inventory
These are counts from the baseline Git tree, **not** an estimate of test coverage or data quality.

| Kind | Count | Root |
| --- | ---: | --- |
| Total tracked entries (files + directories) | 748 | whole repository |
| Java source files (including tests) | 338 | `src/` |
| Java source tests/resources under `src/test/` | 64 entries | `src/test/` |
| JSON files under `src/main/resources/` | 158 | `src/main/resources/` |
| Regular quest JSON definitions | 75 | `src/main/resources/logbook/quest/` |
| Event quest JSON definitions | 10 | `src/main/resources/logbook/eventquest/` |
| Existing GitHub workflows | 0 | `.github/workflows/` |

Relevant implementation touchpoints:
- `logbook/core/LogBookCoreServices.java`: regular, event, and `./customquest/<questNo>.json` resource paths.
- `logbook/core/LogBookCoreContainer.java`: plugin class loader; ServiceLoader-based plugin lifecycle.
- `logbook/internal/updatecheck/CheckUpdate.java`: upstream GitHub Release check + ZIP download + `update/` JAR staging.
- `dist-includes/launch.bat`: Windows restart-time JAR copy. **Linux `auto.sh` is user-managed and is NOT in this repository.**
- `logbook/internal/gui/ItemItemController.java`: ControlsFX autocompletion has required JavaFX internal export on Java 25.
- `pom.xml`: Maven build targets Java 21, while Linux Mint currently uses Liberica Java 25. They need separate verification.

## Migration strategy
1. **Data-only changes:** keep legacy quest JSON schema; establish separate curated JSON distribution with validation, source attribution, versioning and rollback. Never require a JAR replacement for compatible definition changes. Do not silently rewrite original comment-bearing JSON as strict JSON.
2. **Code/API changes:** retain the existing release ZIP / `update/` / restart workflow; audit download integrity and Linux activation before using it for unattended updates.
3. **Plugin boundaries:** centralize game protocol interpretation and normalized application state in the host. Keep KLB/KQS binary compatibility until proven otherwise; extract one self-contained feature at a time.
4. **Dependency modernization:** analyze complete Maven dependency tree first; update build/test, shared JSON/logging, JavaFX, and Jetty separately, each with regression evidence.
5. **Automation:** create proposals and run validation in isolation. Do **not** enable unattended production deployment, merge policy changes, or access to user game traffic as part of baseline setup.

## Development rules
- Source changes only on a feature branch; use pull requests and CI evidence before merging into `master`.
- CI runs without production configuration, game login, tokens or Chrome extension.
- Never upload game request bodies, session data, secrets, `config/`, `logs/`, or user files to public GitHub.
- A failed test is a blocker, not a pass. Distinguish compilation from tests, headless tests from JavaFX GUI and real-game validation.
- Keep MIT and third-party license notices.
- Every task checkpoint: task ID, upstream/base commit, branch/head commit, output, CI run, known problems, next action and rollback.

## Tracking (existing Drive location)
Migration tasks and statuses are recorded in the existing Personal Context spreadsheet, tabs:
- `航海日誌_計画` (decisions and acceptance criteria);
- `航海日誌_タスク` (A1–M4 task status; authoritative work ledger for this project);
- `航海日誌_記録` (attempts/results).

See: https://docs.google.com/spreadsheets/d/11iDuiRjZ3QgJLtKleh_KMI1bmPmX4-kjgXoBpLDQ8VE/edit

### Immediate next tasks
- A1: finish the per-feature and storage/JSON decoder inventory.
- A2: make the baseline Maven build and tests green, preserving the upstream production code.
- A3/A4: develop fixture-based compatibility tests and isolated GUI test plan.
- D1: move **copies** of definition JSON into the data-repository design, first as an exact inventory, not a rewrite.
