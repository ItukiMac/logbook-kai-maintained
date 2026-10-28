# Ship roster read boundary — 2026-10-28

This narrow step extends the existing record and equipment read-only contracts.
The current master base is `7b1b6351ab7f7b9f7ce512da37df8ebc5ae946ee` (merged PR #60).

## Implemented
- `ShipRosterV1` exposes immutable ship identities, master sort order, level,
  event sally-area ID, equipment IDs including expansion slot, manual labels and ordered fleet membership.
- The host service reads existing `ShipCollection`, `ShipMstCollection`,
  `DeckPortCollection`, and `ShipLabelCollection` as detached values.
- The **All** and **All fleets** tab membership/order in `ShipController` uses the new
  snapshot contract, then resolves current legacy beans because `ShipTablePane`
  still has interactive legacy ship/label editing and display dependencies.
- Missing masters and unresolved fleet members retain identities and return warnings;
  invalid/mismatched IDs and null list elements are not fabricated.
- No saved data schema, resource, config key, game/quest rule or outgoing API is modified.

## Important limits
Snapshot objects and returned containers are immutable *after capture*, but the
unmodified legacy writers have no common lock; capture is **not atomic**, and
separate queries can see different moments. This does not solve the full P1
thread/write ownership requirement. The ship roster controller and
`ShipTablePane` remain in core, and this change is **not** a new feature JAR or
an arbitrary-core-version compatibility guarantee.

The next substantial step is extracting mutable ship/label edits into a host
write-command contract, preserving UI save semantics and plugin ABI, then
moving the entire ship UI to a real feature JAR with tested rollback. That step
requires additional GUI and data regression coverage and is **not** claimed here.

Existing full-rule verification (0/148), Mint native testing, real user data,
release, tag and deployment remain separate gates. No OS, installed JAR,
`auto.sh`, configuration, history, plugins, backups or external providers
were touched in this source-only change.
