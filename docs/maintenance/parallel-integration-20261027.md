# Parallel implementation integration — 2026-10-27

This integration combines #49, B #51, C #53, D #52 and E #54 on the master containing #48/#50. It is source integration and disposable-fixture validation, not publication or native Mint certification. Original branch commits and user environments are retained.

## Input source identities

- Integration base: `ef43b7de77552c87bf77126554e0cd8c2fac8190`.
- Completion tracking #49: `50bea5ac7a66788ef42036e703e2caeb1b3e44e7`.
- Runtime migration B #51: `e6b9ef2711c1445d71118e660a60313d3e727ee3`.
- Seasonal conditions C #53: `9d470fd811932f7f76363feddf30f15ebf06eec2` (includes the reviewed 76-to-77 evidence-count correction).
- Arrival evidence D #52: `40c0ea7e9022b8cf1559a0eb34bba6b45e6f47f9`.
- Independent feature versions E #54: `7631de153f40cee969196a51c1718e12bfeb9b0e` (includes historical module-version reuse rejection).

## Corrections at the integration boundaries

### Arrival tracking after process restart

The original D recovery accepted a matching fleet, cell and newer capture timestamps. That cannot distinguish a continued sortie from an unobserved return-to-port followed by a different sortie. A regression reproduces the wrong credit before the correction. Even when those observations match, a saved active route now remains uncredited and is invalidated with `RESTART_CONTINUITY_UNPROVEN`. Journal/fleet/credited/pending bytes are retained. A new observed port boundary plus map/start starts a new tracked sortie; a four-JVM departure/resume/fresh-start/replay test verifies persistence and no double credit.

This deliberately does NOT promise automatic continuation credit after restarting during a sortie. No persistent server sortie identifier is invented. Without independent continuity evidence, undercounting is preferable to crediting another sortie under an old acceptance time. The continuous-observation paths, independent decks 3/4, confirmed retreat and original member positions remain supported.

### Migration of tracking and fleet evidence

B gains an explicit `arrival-tracking-v1` import/restore profile. It preserves the existing arrival/pending files and the complete `tracking-v1` and `fleet-evidence-v1` trees. The Python profile checks bounded shapes, sequence/hash chains, timestamp/reset boundaries, original slots and associations with credited arrivals. Java rechecks application transition semantics when reading; the Python storage review is not proof of capture continuity.

Older declared profiles still refuse the new folders. Corrupt or incomplete checkpoints block managed import rather than being deleted or repaired. Raw snapshots can still retain those exact bytes for diagnosis. Failed E `versions/pending-*` directories are preserved as forensic data and never treated as installable releases. Registered E manifests/history retain their identity checks during whole-store restore.

### B + E + D actual application exercise

A new mandatory test creates synthetic D records using real Java `PortArrivalJournal`, `PortArrivalStore`, tracker and semantic runtime validator. It then performs B snapshot/import, E managed normal launch with calculator A (input x2), a single-module update to B (input x3), and B whole-store restore into a NEW directory followed by normal launch of A again. Actual saved calculations must be `10,15,10`. Four D checkpoints, original retreat slot 2, published/pending records and interrupted stage bytes survive. The original source and input JAR are hash/inventory checked unchanged.

The combined full suite also exposed a real B/E test boundary: B's current-JAR Launcher probe requested strict managed mode while putting the unversioned fat JAR on the classpath. E correctly refused its missing embedded module descriptors; disabled probe logging initially hid the cause behind a GUI timeout. The current-JAR path now builds the real four-JAR distribution, runs separate-JVM preflight, and pins/watches every preflight input through B's approval and launch. It does not weaken E's production guard. Probe warnings remain visible.

The earlier B historical-JAR pair remains a separate test of the explicitly declared revisions and their unchanged stored schema. E's provided two-fixture adaptation is applied without weakening B production guards. Wrapping an old fat JAR for archival does not mean it passes modern managed feature preflight; its marker remains `split_bundle_launch_validated=false`.

### C + D real selected rules and original slots

Eight tests use the actual seasonal catalog service/resource loader, actual on-disk fleet evidence, collector and predicates. Synthetic clocks/identities isolate the inputs. They verify original third-slot survivors, no second-slot promotion, noRetreat enforcement, departure/arrival period boundaries, observation changes and expiry after collection, and corrupt fleet evidence refusing fallback. The two implementations' common files auto-merge textually; these tests, not that fact, are the behavioral evidence.

## Gates and limits

`verify_r3.sh` requires the combined B/D/E success marker, its structured report, and all eight C/D tests with no skips. R3 has a 25-minute runner ceiling to accommodate the additional real launches/copies; no correctness assertion is removed. Dedicated migration CI separately builds the two pinned historical application sources and requires its five tests without skips on Java21/25. The exact integration PR records actual run IDs, counts and downloaded artifact identities after those runs; this source document does not predeclare their success.

All current source integration tasks are separate from: native Mint/IME/real Chrome/game/deployed-plugin checks; unknown current-season IDs/windows or disputed rule semantics; full 148-rule certification; arbitrary cross-schema transformations; all-domain independent classloaders/plugin extraction; paid provider execution and billing; release/tag/publication approval. Historical code review records are not silently promoted to a clean review of later source. No program accesses user runtime data, deletes prior versions, overwrites a real installation, or dispatches publication in these tests.
