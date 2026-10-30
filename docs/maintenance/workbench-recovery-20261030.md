# M3/M4 source workbench and recovery

The workbench checkpoint contains tracked development files, explicitly named new
source inputs, exact tool declarations, the agent contract, and a JSON task/progress
record. The manifest and ZIP are pinned independently. Capture/restore never reads
ignored runtime data, Git config/history, tokens, or the process environment contents.

`maintenance_workbench.py measure-tools` probes only Python itself and fixed
`java -version`, `mvn --version`, `git --version` commands with a small clean environment
and temporary HOME. Save the returned exact versions as the toolchain declaration.
The checkpoint pins the complete source for workflows, prompts, tools and policies.
It does not snapshot the whole OS, container image, SDK binaries or Maven cache.

Create a checkpoint into a new directory outside the source. Supply the task identity,
acceptance, current phase, failures and next action explicitly; arbitrary state is not
inferred from "last command succeeded". New untracked source requires explicit inclusion.

Verify its SHA256 before restoration. Restore into another NEW directory, passing all
protected original roots. Optional Git initialization creates a new local checkpoint
commit; it does not fabricate or replace the original history. Re-read and compare
all files and executable modes. Run `preflight` to compare actual tool versions before
execution. Missing/different tools stop rather than silently downloading/upgrading.

A failed capture retains its partial ZIP without a completion manifest. Failed restore
retains the partial new checkout and never falls back to replacing the old environment.
No cleanup, removal, move, format, paid provider, release/tag, activation or production
rollback is performed. "Rollback" here means continued use of the unchanged old area.
Checksums are byte identity, not publisher trust or independent review.

Unit and isolated-runner evidence must cover dirty tracked source and explicit new
source, actual new Git checkout and resumed validation, tool mismatch, path/mode/ZIP
corruption, source races, and original byte preservation. Native Mint and deployed
provider/account/environment validation remain separate from this portable source
recovery capability. Paid-provider measurements require an actual approved provider;
no receipt or test fixture may be substituted for real billing evidence.
