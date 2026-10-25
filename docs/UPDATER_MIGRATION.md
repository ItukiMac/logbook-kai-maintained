# U1: existing application updater — staged adoption

**Scope of this PR:** change the default update source while retaining the upstream ZIP and staged-JAR protocol; validate Release SHA-256 digest, reject malformed update archives, and stage only an expected application JAR atomically. Preserve a system property for test/rollback.

## Verified implementation

The existing `CheckUpdate` class:
1. Queries GitHub tags and release metadata from a static repository path.
2. Displays an opt-in application update dialog; the user selects `自動更新`.
3. Downloads `releases/download/v{version}/logbook-kai_{version}.zip`.
4. Extracts JAR entries under the running app's `update/` directory.
5. Asks the user to restart.

The upstream Windows `dist-includes/launch.bat` copies staged `update/logbook-kai.jar` into the active application on launch. This does **not** prove Linux `auto.sh` already implements the same step. The user's Mint launcher is not tracked in this source tree.

## First compatibility change

Default: `ItukiMac/logbook-kai-maintained` for update metadata + downloads.
Explicit override: `-Dlogbook.update.repository=owner/repo` in the JVM arguments.
No automatic GitHub release exists yet for this fork. The application should not be switched into service until it has a verified release and a tested Linux restart path.

## Implemented in this draft branch (still unmerged)

- The expected release archive is taken from the maintained fork (system property override allowed).
- The corresponding GitHub Release asset metadata must include a `sha256:` digest, and downloaded bytes must match it.
- A bounded download and archive reader reject oversized ZIPs, unsafe paths, nested/unexpected JARs, or payloads that are not a logbook-kai application JAR.
- Only a temporary file inside the `update/` directory is extracted, and the staged JAR is atomically replaced after validation. The **running** `logbook-kai.jar` is not touched.
- Isolated tests cover a good archive, traversal, non-JAR payload, and missing JAR.

## Remaining work before production use (U1/U2)

- Publish a maintained **test** Release and verify ZIP/JAR filename and SHA-256 metadata against real GitHub assets. (No Release has been published yet.)
- Decide and implement stronger publisher identity/signature checks if needed; the GitHub digest retrieved over TLS is not an independently signed artifact.
- Confirm fail-closed behavior with download interrupted mid-stream, incorrect Release digest, and rollback cases in integration tests.
- Test staging in an isolated directory without credentials or the live game session.
- For Linux, audit the *current* `auto.sh` (the production source of truth) before adding an update activation step. Keep the Chrome launch, :8891 KLB health wait, JavaFX compatibility arg, and all user configuration.
- Test upgrade, bad zip, missing jar, rollback and restart with current KLB/KQS binaries.
- Keep updater opt-in until the full maintenance and release gate exists.

**No production deployment, no Release publication, and no automatic merge is authorized or performed in this PR.**
