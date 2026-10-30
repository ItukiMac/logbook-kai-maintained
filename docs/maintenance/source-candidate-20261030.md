# M3 source proposal and isolated verification

A proposal supplies complete UTF8 text, exact old hash and source path for each
change. Its identity binds the verified source checkpoint and all content/risk
classifications. It cannot supply shell commands, delete source, change Git metadata,
access personal runtime paths, or reuse approval after modifying content/base.

Documentation and tests can be automatically staged into a NEW source checkout.
Runtime sources, tooling, workflows and build changes require an explicit identity,
reviewer and approve decision. This local assertion is not authenticated publisher
identity. The original source, source checkpoint and failed candidates remain intact.

Staging is not verification. `verify_candidate` requires a separately provisioned
isolated runner, exact measured toolchain, exact clean candidate revision and the
fixed Python script tests plus Java GUI Maven verification. The operator flag is
not an OS sandbox. CI uses a disposable Ubuntu runner with contents:read and no
persisted checkout credential. Candidate code is trusted reviewed code. Never run
untrusted proposals on production or on a machine with private credentials.

The dedicated workflow stages a new inventory test against the actual complete
source in a new Git checkout and actually runs Python and Maven GUI checks there.
It records candidate revision, checkpoint/proposal identities, test totals, source
preservation and failure logs. A failed check never produces a successful receipt.
No paid model, merge, release or deployment is invoked; a model can supply proposals
through this same bounded input contract without being given application privileges.

Artifact review found6 real-definition cases skipped when no data pack was supplied.
Final integration reuses the canonical scripts/verify_r3.sh: it builds the complete
committed candidate pack and actual Maven JAR before running script/tool Python
suites and packaged feature/runtime probes. This also avoids skipping Java-backed
Python integration cases before the application JAR exists. CI now
requires the real-definition suite with zero skips and permits only the dedicated
public-network test skip. The initial7-skip verification artifact is retained and
not represented as complete definition validation. A negative case also proves that
checks mutating tracked candidate source cannot issue a verified receipt.
