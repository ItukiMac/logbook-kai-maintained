# Fixed local editor resources

The legacy helper loaded Ace1.4.3 from a CDN; offline initialization left the
editor without get/set functions. Bundle the fixed upstream ace-builds1.45.2
BSD-3-Clause distribution and license. The application HTML now uses local JAR
resources for the core, language tools, theme and text/JSON/JavaScript modes.
The JSON manifest records exact upstream version, source and all resource hashes.
No remote script is required to edit configuration text. Other Ace language modes
are not bundled; this is not a claim of arbitrary-language support.

The auxiliary GUI regression actually loads the local WebView and roundtrips
Japanese, quotes and newlines before and after read-only mode. Its initially
failing CDN-dependent result is retained separately. This dependency update is
reviewed separately from the auxiliary ownership catalog and host GUI tests.
No user configuration, runtime JAR, production folder or release is changed.
