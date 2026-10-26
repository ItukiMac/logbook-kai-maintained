# L1/L2: CI action and runtime test wiring refresh

Use verified official action releases: checkout v7.0.1, setup-java v6.1.0, upload-artifact v7.0.2, download-artifact v8.0.2. Existing triggers, permission boundaries and release guards stay unchanged; no release workflow is dispatched. New integrated verification runs Java21/25 with real Xvfb GUI tests, Python safety tests, inventory, game JSON audit when present, assembled-JAR logging probe and upload/download checksum roundtrip. It creates artifacts only, never a Release or deployment.

Mockito uses an explicit startup javaagent for Surefire, with an empty default argLine for late argument replacement. The integrated job disables dynamic agent loading to prove the tests do not depend on self-attachment. This setting affects test forks only, not application launchers.

Add the SLF4J2-to-Log4j bridge at runtime, using the same version as existing Log4j API/Core. Verify exactly one provider in both the test classpath and the assembled application JAR. Do not add the reverse log4j-to-slf4j bridge. Legacy deprecation warnings unrelated to these changes are not concealed.

Sources checked 2026-10-26: official actions repositories/releases; https://javadoc.io/static/org.mockito/mockito-core/5.24.0/org.mockito/org/mockito/Mockito.html#0.3 ; https://logging.apache.org/log4j/2.x/manual/installation.html . Existing library and application versions remain unchanged except adding the matching logging bridge. Final Mint/real plugin checks and deployment are separate pending stages.
