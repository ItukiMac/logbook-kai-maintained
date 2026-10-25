# U2: Linux Mint staged updater integration

Status: helper and offline tests; **not enabled on a real Mint machine**.

The existing auto.sh is user-owned. It is not present in this repository and
must not be overwritten by a new launcher. Preserve Chrome startup, KLB/CDP
port 8891 health checks, JVM flags, and all saved user settings.

## Proposed existing-auto.sh integration (NOT applied)

Run the following once, immediately before the Java launch stanza in the
existing auto.sh. Verify the actual current file before modifying it:

    if [ -f "$HOME/logbook-kai/helpers/apply-staged-update.py" ]; then
        if ! python3 "$HOME/logbook-kai/helpers/apply-staged-update.py" --root "$HOME/logbook-kai"; then
            if command -v notify-send >/dev/null 2>&1; then
                notify-send "艦これ環境" "航海日誌改: 更新失敗。旧版で起動します"
            fi
        fi
    fi

This snippet is only a proposed patch. It must not be inserted twice.

## Staged JAR lifecycle

1. The existing application updater fetches and verifies a Release ZIP and
   places a validated application JAR in update/logbook-kai.jar.
2. If no staged JAR exists, the helper returns NO_UPDATE without changing anything.
3. On a valid changed JAR, preserve the original in
   launcher-backups/app-versions/ and verify its checksum.
4. Validate the new JAR, copy into the same filesystem, then atomically replace
   the active JAR. Remove the staged JAR only after successful activation.
5. When validation fails, preserve the active JAR and report failure, allowing
   auto.sh to start the existing version.
6. On next start, no staged JAR remains, so no repeated activation occurs.

## Before actual Mint rollout

- Confirm the complete Release includes helpers/apply-staged-update.py.
- Inspect the *current* auto.sh on Mint; retain existing JVM, Chrome and KLB
  logic. Add the guarded call once only, with a saved backup.
- Test release download, stopped-app activation, startup health, rollback,
  permissions, interrupted copies, and existing KLB/KQS JAR compatibility.
- A verified backup is not itself an automatic rollback mechanism. Reliable
  recovery after a runtime start failure still requires a health signal.
- The helper validates local ZIP/JAR contents but depends on the Release
  updater to authenticate origin. Do not bypass publisher validation.

No GitHub Release or user environment is modified by this PR.
