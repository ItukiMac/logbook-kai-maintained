# U1: existing application updater — staged adoption

**Scope of this PR:** switch the default update check / release download source to the maintained fork while retaining the upstream ZIP and staged-JAR protocol. Preserve an optional Java system property for testing or temporary rollback.

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

## Remaining work before production use (U1/U2)

- Confirm Github Release assets follow the legacy filename and ZIP/JAR path contract.
- Check downloaded archive integrity, contents, size limits and file destinations before staging. The existing extractor filters `*.jar` but does not validate each ZIP entry path; its lambda logs `IOException` and may still display success.
- Fail an incomplete download / staging transaction atomically, preserving the existing active JAR. Do not claim an update succeeded if an entry failed.
- Specify how release identity and integrity are authenticated, not just length/checksum downloaded from the same untrusted source.
- Test staging in an isolated directory without credentials or the live game session.
- For Linux, audit the *current* `auto.sh` (the production source of truth) before adding an update activation step. Keep the Chrome launch, :8891 KLB health wait, JavaFX compatibility arg, and all user configuration.
- Test upgrade, bad zip, missing jar, rollback and restart with current KLB/KQS binaries.
- Keep updater opt-in until the full maintenance and release gate exists.

**No production deployment, no Release publication, and no automatic merge is authorized or performed in this PR.**
