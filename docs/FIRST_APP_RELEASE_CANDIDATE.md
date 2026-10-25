# First maintained application candidate: 25.0.9

This is an **artifact-only, non-deploying** candidate of the maintained fork.
Do not treat a successful Maven build as completed GUI/Mint migration.

## Current repository status

- Fork upstream baseline: 25.0.8.
- Candidate version reserved: `25.0.9`.
- Candidate assembly format: `logbook-kai_25.0.9.zip` with
  `logbook-kai.jar` and `helpers/apply-staged-update.py`.
- Release tag when approved after testing: `v25.0.9`.
- GitHub Actions artifact is **not** a GitHub Release.
- No `v25.0.9` release/tag is published or created here.
- `logbook.data.autoUpdate` remains opt-in and unrelated to executable updates.

## Acceptance requirements before any application Release is published

1. Artifact build succeeds with full Java21 Maven tests, offline JSON generation
   validation, Java25 headless tests, pinned KLB/KQS source compatibility,
   and isolated official-JAR → maintained-JAR → restore test.
2. Verify actual archive name, ZIP CRC, included updater helper, manifest,
   launcher class, version, and SHA256 of the exact candidate bytes.
3. Assess JavaFX GUI on Linux Mint 22.1 with Java25, especially ControlsFX
   autocomplete. Keep the current `--add-exports` workaround until the
   permanent fix is tested.
4. Inspect the existing Mint `auto.sh` and dry-run hook in-place. **Do not**
   turn off `--dry-run` before application Release and rollback tests pass.
5. Validate **deployed** KLB v1.0.0 and KQS v0.2.0-rc.3 JARs (binary runtime),
   not only today's pinned source tests. Preserve plugins and configuration.
6. Keep the initial publication a separately gated, explicit operation. Avoid
   tag-based auto-release and auto-install to production.
7. Document a version-matched downgrade procedure and an application-startup
   health check, not merely a file-copy backup.

CI first: build the ZIP as an ephemeral artifact and collect SHA256 for review.
No account cookies, local configurations, or game traffic are part of the artifact.

## Controlled application publication and Mint migration

After passing the isolated Mint 22.1 + Java 25 smoke test and existing
KLB v1.0.0 / KQS v0.2.0-rc.3 live compatibility checks, use the new
`manual-app-release.yml` workflow to build an independently verified
**prerelease** from `master`:

- Dispatch on `master` with `app_tag=v25.0.9` only.
- The workflow reruns Java 21 and Java 25 Maven tests, audits the ZIP,
  validates embedded launcher/Main-Class and bundled updater helper, and
  generates a release-asset SHA256 checksum.
- The resulting GitHub release is explicitly marked prerelease. It is a
  **public download**, not an instruction to replace a running installation.
- The prerelease tag is created only after all tests succeed. Publishing
  refuses an existing tag or Release and cannot overwrite earlier bytes.
- `data-v...` JSON-only release tags remain completely separate.

**Not covered by publication**: Mint production settings/record persistence,
startup-health rollback, live KLB/KQS lifecycle after swap, and activation of
the existing `auto.sh` dry-run hook. Keep production JAR and `--dry-run`
unchanged until separately verified with a backup and a deliberate cutover.

The exact candidate ZIP used in the successful isolated Mint verification
has SHA256
`cb0ff7cbee9466ec8d56d7e8eacf1f8e491109461bbbe69562eef716c1eb764b`.
An archive *rebuilt* by a later GitHub Actions workflow may differ byte-for-byte
because the Maven ZIP build is not currently proven reproducible. Validate and
record the checksum of the **actual prerelease asset**, not a stale candidate
checksum. The initial publication remains an optional explicit human action.
