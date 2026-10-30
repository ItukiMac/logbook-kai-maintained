# Host state boundary — 2026-10-30

Base: PR71/master `930b8e1009a71acbfbbf4ae4596c7bed3efd38d8`.

Host API handlers, local label commands and quest expiry now share one state monitor.
Manual label edits no longer use a separate class lock. Public ship/fleet, statistics,
equipment and air-base captures acquire this monitor before reading mutable inputs.
Pure copy adapters still accept caller-owned fixtures; they do not lock arbitrary maps.

Local changes publish an explicitly named host/local event only when changed. Reentrant
writes are rejected across API and local operations. Partial handler failures publish
updateSucceeded=false and rethrow the original failure; the lock is always released.
A local operation cannot use a URI-shaped name to impersonate quest-list traffic.

GameStateView gains a binary-compatible default latestQuestReceipt method. The host
retains the exact last actual quest-list result, including failure, across unrelated
and local updates. Reopening a quest window replays that actual receipt, not a new
observation built from cached state or the current clock. Existing providers that
only implement snapshot/subscribe retain their snapshot-only default behavior.

Tests exercise concurrent API/local/read operations and ordered events, unchanged
commands, partial failure, cross-direction reentrancy, forged local identifiers,
receipt identity, legacy providers, every public projection's blocking boundary,
and the real JavaFX window reshow path. Existing label/roster/statistics/equipment,
quest freshness, migration and legacy KLB/KQS checks remain mandatory.

This is the cooperative host boundary, not interception of arbitrary old plugins
that directly mutate public beans. Bean ABI is retained; such external writes remain
an explicit compatibility limitation. Host UI composition, rendering-only fields,
local user preferences and file document CAS are separate domains. No game request,
production state or existing user file is changed by this development work.
