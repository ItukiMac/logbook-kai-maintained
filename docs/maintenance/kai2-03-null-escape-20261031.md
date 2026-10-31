# KAI2-03: live 1-6 tracking failure and null escape regression (2026-10-31)

## Scope and evidence
- Mint live evaluation: first 61 entries were 52 BOUNDARY and 9 INVALID (all payload null), with no credited arrivals. After one 1-6 sortie the journal had 68 entries: 56 BOUNDARY, 10 INVALID, 1 START, 1 STEP, 0 GOAL; arrival count remained zero.
- Observed 66 START(TRACKING), 67 STEP(TRACKING), 68 INVALID(INVALID_OBSERVATION), with a retained non-null payload for the last three.
- Live log at 2026-10-31 19:16:42 JST: `ClassCastException: jakarta.json.JsonValueImpl cannot be cast to jakarta.json.JsonObject`, at `PortArrivalRuntime.eventKey`, followed by `TRACKING_IO_OR_FORMAT_FAILURE`. The installed binary's stack line is 646; the source tree has different line numbering. Do not infer exact binary identity from line numbers alone.
- This identifies an unchecked JSON object cast in event key creation, not a verified live value of `api_escape`. The code's only direct `getJsonObject()` inside `eventKey` is for `api_escape`.

## Reproduction (synthetic, no live data)
- JUnit test `PortArrivalKai203Test.explicitNullEscapeInOrdinaryBattleResultMustNotBlockObservedGoal`: original code reported [BOUNDARY, START, STEP, INVALID] rather than [BOUNDARY, START, STEP, GOAL].
- JUnit test `PortArrivalKai203Test.explicitNullEscapeOnConnectedMapGoalMustNotBlockArrival`: original code reported [BOUNDARY, START, INVALID] rather than [BOUNDARY, START, GOAL].
- Java 25 CI for test-only commit `b4960d73e21bdd5bf1e68148d5ecb1899cf288ca`, run `41193792189`, job `124515770195` reproduced both test failures. Red baseline is intentional, not a passing gate.

## Correction
- In `PortArrivalRuntime.eventKey`, distinguish absent `api_escape`, explicit JSON null, JSON object, and malformed non-null non-object values.
- Treat absent and null values as no structured retreat evidence for the purpose of *key construction only*. A non-null unsupported type throws and blocks tracking, preserving fail-closed behavior.
- Preserve all pre-existing retirement/recovery/route/fleet/goal validation, existing store format and append-only journal semantics; **do not** backfill the missed arrival from battle, port or partial route data.
- Add three focused regression cases: normal battle with JSON null, map goal with JSON null, and malformed array rejection. Existing KAI2-03 tests cover outside-1-6 rejection, normal goal, and boundary invalidation.

## Release gate (not approved by this PR)
- Confirm passing Java 21 and Java 25 unit/compatibility runs at exact final commit, no unexpected regressions.
- Verify the **installed** source/build against this patch before any deployment: live stack lines differ from repository source lines.
- Test an independently built candidate with separate process/config/storage paths and a fresh temporary journal; never run it against the active evaluation `sortie-arrivals-v1` or existing plugin/configs.
- Explicitly capture one real 1-6 START -> STEP -> GOAL sequence and verify exactly one credited arrival with matching fleet evidence and no duplicate on repeated observations.
- Preserve old logbook, klb, current kai2, all journal bytes, pending files and backups. No restart, deletion, overwriting, merge, release or live switch authorized.
- Residual risk: actual live `api_escape` representation still unverified without a privacy-safe capture; malformed-but-valid object semantics and network ordering need separate native coverage. A blocked current runtime may require a later *explicitly planned* fresh run, not immediate restart.
