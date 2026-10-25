# D1: inventory and delivery contract for game definitions

**Stage:** inventory/specification only. No runtime loader or distribution repository has been installed yet.

## Legacy resources, Git baseline `2090aee9092c7b6a264a82b79a170312a74d5d0b`

These counts were enumerated from the upstream-compatible Git tree on 2026-10-25 JST.

| Path (`src/main/resources/`) | Count | Likely role | Delivery policy |
| --- | ---: | --- | --- |
| `logbook/quest/*.json` | 75 | permanent and repeatable quest conditions | first external-JSON pilot |
| `logbook/eventquest/*.json` | 10 | time/event-scoped quest conditions | second pilot; handle reuse of quest IDs by context |
| `logbook/kousyou/kousyou.json` | 1 | equipment improvement/day/support ship mapping | data-only candidate, verify all consumers |
| `logbook/mission/*.json` | 63 | expedition-related definitions | data-only candidate, verify calculations |
| `logbook/map/*.json` | 1 | sea-area / map definition | mixed: values vs hard-coded screen behavior |
| `logbook/supplemental/*.json` | 2 | supplemental metadata | review separately |
| `logbook/capture_options/*.json` | 5 | recording/capture configuration | **exclude** from remote game-data auto-updates by default |
| `logbook/bouyomi/settings.json` | 1 | audio/voice configuration | **exclude** from remote game-data auto-updates by default |
| **Total** | **158** | | |

The path-based grouping is not yet a full dependency audit. The `customquest/` directory is user-managed and absent from `src/main/resources`; **never** overwrite it with remote updates.

## Compatibility observations

- Existing quest files can start with `/* comments */`, e.g. `logbook/quest/1045.json`. They are **not all strict RFC 8259 JSON**. Preserve original source files and parsing behavior in D1. A data validation/publishing pipeline must normalize or safely handle comments in a way matching the Java consumer, rather than rejecting or silently rewriting everything.
- `LogBookCoreServices.getQuestResource(int, boolean)` currently retrieves `logbook/quest/{id}.json` or `logbook/eventquest/{id}.json` from the application/plugin classloader.
- `LogBookCoreServices.getCustomQuestResource(int)` checks `./customquest/{id}.json`. This existing user-override mechanism is **not** a remote update channel.
- Quest IDs are not sufficient to disambiguate seasonal/event definitions; keep event/edition identity, effective dates, and supporting evidence outside the existing unmodified quest schema.
- A data-only update is valid only if the current evaluator understands all referenced condition types. Unknown fields/operations must fail validation or remain pending, rather than produce silent incorrect results.

## Proposed distribution layout (not created yet)

```text
logbook-kai-data/
  definitions/
    quest/
    eventquest/
    kousyou/
    mission/
    map/
    supplemental/
  sources/
    provenance.json
  schemas/
  manifest.json
  validation/
```

The publisher creates a versioned release archive. The app obtains a verified release manifest and stages the entire set of JSON definitions in a new generation. Manifest fields will cover schema version, minimum host feature/API version, individual checksums, release identity, and verification method. The app must not write directly to any user-owned `config/`, `customquest/` or game-observation data. Enforce rollback and immutable previous generation.

**Authoring source format:** JSON (retained). No YAML generation requirement. Where legacy files contain comments, treat them as legacy JSON-with-comments and maintain a separate, strictly parseable distribution/manifest where needed.

## Pilot acceptance / staged execution

1. D1a: inventory file identities and current Java consumers; record data vs code update classification for each group.
2. D1b: create the separate data repository when the repository-creation mechanism is available; copy unchanged files into a tracked baseline and compare file checksums.
3. D2: test local generation loading without changing the bundled resource fallback; preserve `customquest` user overrides and event identity.
4. D3: download + verify + switch definitions **without changing `logbook-kai.jar`**. Treat interrupted downloads, partially missing definitions, invalid signatures/manifests, and unknown operators as failures.
5. D4: update in-memory caches and observers at a safe boundary; confirm KQS reads the same active definition generation.
6. D5: use one independently corroborated quest change to prove data-only publication and rollback.

Never auto-fetch or auto-commit user account information. References for editorial verification: https://wikiwiki.jp/kancolle/ and https://zekamashi.net. 
