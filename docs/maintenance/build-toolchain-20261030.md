# Fixed build tools

CI uses Apache Maven 3.9.11 from the fixed Central artifact URL, verified against
the pinned publisher SHA512 before extraction. The composite action exposes only
the new temporary installation through GITHUB_PATH; it never replaces a local SDK.

The POM pins all default lifecycle plugins plus help/dependency report goals.
Versions match the previously validated effective lifecycle; report plugin versions
are explicitly declared. Java 21/25 resolution CI asserts the effective values.
The initial PR72 Java25 Central resolution failure is retained as infrastructure
evidence; version pinning removes default drift, and does not promise network uptime.

For local reproduction use Maven 3.9.11, Java21 (default) or the explicit modern
profile with Java25. Run mvn --batch-mode --no-transfer-progress verify, then the
applicable scripts/verify_r3.sh GUI suite in an isolated Xvfb runner. CI records
actual compiler, dependency resolution and full regression outcomes. No production
application, definitions, dependency versions, paid provider or release is changed.

Integration also fixes Temurin21.0.13+9 and25.0.5+8, the exact JDK build identities
observed in the verified PR72 Java XML reports. JavaFX versions remain declared in
the POM. New workbench/source-candidate/single-definition workflows use the same
pinned Maven action. Matrix labels21/25 remain logical labels, not floating downloads.
