# Functional modules, shared export and persistent quest ledger

Status: implementation in PR #84; final-head CI pending. Not deployed.
The bundled catalog has 670 provenance-bearing source records, including draft and historical entries. It is not certified exhaustive for the live game.
Supersedes the proposal that status is a final functional module.

## Required module boundaries

- core: lifecycle, persistence transactions, game observations, public read snapshots and guarded commands.
- ships: roster, filters and repair candidates.
- equipment: equipment inventory, ownership and details.
- quests: quest views, condition editing and persistent management table.
- records: battle, expedition and development history views.
- statistics: graphs and aggregate views.
- calculators: calculation views (split further only where independently cohesive).
- sharing: repository-owned successor to KQS; export providers for every information domain, clipboard, preview and file export.
- klb: browser transport bridge.

JAR ownership, service registrations, feature descriptors, preflight, independent update,
retained-generation rollback and source/license provenance must follow one mechanism.
No direct internal GUI class-name/ID probing by sharing. Quest views expose an explicit
action extension point. Core state is not duplicated into each module.

## Persistent quest management

Current ApiReqQuestClearitemget removes AppQuestCollection and AppQuestDuration
entries. It must record a successful reward claim durably before discarding the active
entry. HTTP success alone is insufficient: require successful game api_result.

Maintain catalog and observation history separately. Catalog includes single-use,
recurring and time-limited quests; missing catalog metadata is explicitly unknown.
The game's current quest list does not enumerate every locked or previously completed
quest. Catalog completeness requires a provenance-bearing definition source and audit.

Identity: quest ID plus edition for reused IDs; recurring quests additionally need a
period key. Do not infer an edition from ID alone. Retain unresolved observations for
later reconciliation instead of assigning them to a guessed edition.

States: UNKNOWN, AVAILABLE, ACCEPTED, ACHIEVED_UNCLAIMED, CLAIMED, EXPIRED.
Absence from a response is never proof of CLAIMED or EXPIRED. CLAIMED requires a
successful reward-claim event or an explicit manual entry. Catalog expiry is distinct
from completion. Do not overwrite historical completion when a recurrence begins.

Every event retains identity, observed/effective time, source (game/manual/catalog),
previous state and correction reason. Automatic observations and manual corrections
remain separately visible. Manual updates use revision checking and append history;
later contradictory evidence is surfaced for reconciliation, never silently erased.
Manual state edits change only this application's ledger, never the game account.

Management table: ID, title, category, edition/period, observed state, effective state,
source, freshness, completion time and discrepancy indicator; filters, single/batch
manual correction with preview, history and correction reversal by appended event.

Persist atomically with recovery and backup; retain unknown fields/schema versions
where required by existing migration contracts. Completion persistence failures must
be visible and recoverable, not swallowed. Duplicate reward events are idempotent.

## All-information sharing

Inventory every data domain and map it to an export provider, including ships,
fleets, equipment, airbases, resources, repairs/builds, quests, completion ledger,
battles, expeditions, development and available statistics/definitions. Coverage must
be explicit; empty, unsupported and unobserved are different outcomes.

Versioned JSON is the full-fidelity format; tabular domains support CSV. Include
capture time, observation age, origin, schema and data-generation identity. Exports
must have consistent snapshots or explicitly identify weaker consistency. Do not
serialize raw configuration objects: credentials, api_token, cookies and connection
secrets are excluded by allowlisted projections. Offer per-domain and all-domain
exports through one UI. Retain existing KQS export compatibility through a provider.

## Required verification

- Exact JAR ownership, provider discovery and no duplicate classes/services.
- Each module update and whole-generation rollback; old distributions preserved.
- Successful/failed/duplicate claim events; restart persistence and write failures.
- Achieved versus claimed; hidden/locked/unobserved quests never guessed completed.
- Daily/weekly/monthly/quarterly cycles and seasonal ID reuse retain prior history.
- Offline manual completion, correction reversal, revision conflicts and later evidence.
- Export coverage, secret exclusion, schema stability and concurrent state updates.
- Actual management-table edits and export actions in GUI integration tests.

Implementation order: ledger contracts/persistence, successful-claim capture and
catalog identity, management table/manual commands, domain module extraction,
shared export providers/UI, integrated update/recovery verification.
