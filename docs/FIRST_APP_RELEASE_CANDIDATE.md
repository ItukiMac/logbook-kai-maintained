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
