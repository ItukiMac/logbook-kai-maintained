# Maintenance worker contract v1

The durable task is the supplied task identity and acceptance criteria, not the last
successful command. Preserve pending tasks and failed attempts. Read current source
and evidence before continuing. Never infer completion from compilation or copying.

Work only from an explicit source checkpoint/commit and a new work area. Use a branch
and a reviewable PR. Keep code/dependency changes separate. Run the fixed applicable
checks and record exact source/test identities. Reject stale approvals or scope changes.

Never load production config, traffic, session data, tokens or personal files into CI.
The source checkpoint excludes ignored runtime folders and Git configuration/history.
Do not serialize process environment or credentials. Tool discovery/version probing
must use fixed read-only commands. No paid AI provider calls or automatic publishing.

High-risk protocol/API/bean/updater/workflow/build changes require a patch-specific
approval. Ordinary staging is not approval to merge, release or deploy. Candidate
content is trusted reviewed code, not sandboxed untrusted input. Execute checks only
in the separately provisioned isolated runner, with read-only repository permission
and no production credentials. Keep original and checkpoint bytes and failed outputs.

Resume by verifying the pinned checkpoint hash, creating a new checkout, verifying
all bytes/modes, checking measured Python/Java/Maven/Git versions, then reading the
saved task/progress/next-action record. A mismatch stops execution; it does not update
the required versions or erase old state. New Git history records source provenance;
it is never represented as the original commit history. No in-place environment reset.
