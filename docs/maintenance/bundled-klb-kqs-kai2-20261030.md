# Repository-owned KLB/KQS kai2 modules

The supported plugin scope is the user's KLB and KQS only. Both are maintained
in this host repository, using the same version metadata, exact core hash,
preflight, single-module update and retained-generation restore as the existing
calculators/charts/status modules. No new main-window menu is added.

Distribution v3 contains core plus five feature JARs. KLB and KQS lifecycle and
content-listener service registrations move to their owning JARs. Missing or
misplaced registrations and Jackson2 core/databind in any product JAR are rejected.
KQS uses Jackson3; Jackson2 remains test-scoped for historical JSON comparisons.
The shared Jackson annotation package remains required by Jackson3.

Sources and MIT licenses are recorded in modules/klb and modules/kqs. Original
repositories, branches, releases and runtime installations are untouched. A
separate kai2 repository is no longer required. Existing Java21 baseline remains;
changing the supported plugin scope does not change the user's installed Java.

This is a new distribution schema. Preserve the complete previous distribution,
its launcher/manager and store. Do not reuse an old v2 store with the v3 tool or
replace its launcher in place. Prepare a new isolated store for verification.
Do not load old external KLB/KQS JARs beside the bundled copies; personal runtime
migration and real Mint/browser/game communication validation remain pending.

Validation includes Python packaging/update/rejection regressions and CI KQS
export/generation suites against the actual split JARs on Java21/25. Native
verification, public release and production activation are separate steps.
