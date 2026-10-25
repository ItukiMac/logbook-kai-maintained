# Dependency refresh baseline — 2026-10-26

## Scope and status

This series updates all the direct Maven dependencies grouped in the maintenance
ledger, retaining the Java 21 compilation baseline and Java 25 execution support.
The production Mint 25.0.9 application, its existing plugins, user data, auto.sh,
and verified backups are deliberately **not** changed by GitHub source updates.

| Area | Old | Selected stable baseline | Change |
| --- | --- | --- | --- |
| L1 Maven Compiler | 3.13.0 | 3.16.0 | Updated, PR #23 |
| L1 Maven Surefire | 3.5.2 | 3.6.0 | Updated, PR #23 |
| L1 Maven Assembly | implicit | 3.8.0 | Explicitly pinned, PR #23 |
| L1 JUnit Jupiter / Platform | 5.14.4 / 1.14.4 | 6.1.3 / 6.1.3 | Updated, PR #23 |
| L1 Mockito | 5.23.0 | 5.24.0 | Updated, PR #23 |
| L1 jsoup test | 1.17.2 | 1.23.2 | Updated, PR #23 |
| L1 Lombok | 1.18.48 | 1.18.48 | Already current |
| L2 Jakarta JSON API | 2.1.3 | 2.1.3 | Retained, no known newer stable 2.1 change needed |
| L2 Parsson | 1.1.7 | 1.1.9 | Updated, PR #24 |
| L2 Jackson Databind | 2.18.2 | 2.22.4 | Updated within compatible 2.x API, PR #24 |
| L2 Log4j API / Core | 2.26.0 | 2.26.1 | Updated together, PR #24 |
| L3 JavaFX (six modules) | 21.0.11 | 21.0.13 | Latest compatible 21 LTS patch, PR #25 |
| L3 ControlsFX | 11.0.3 | 11.2.5 | Updated, PR #25 |
| L4 Jetty server / servlet / proxy | 9.4.57 | 12.1.14 | Final 9.4.58 patch in PR #26, then supported 12.1 EE8 migration in PR #27 |

The direct Maven dependency groups have been checked. All named JDK21/JDK25,
plugin ABI and release-candidate workflows must pass for the final Jetty
migration to be mergeable.

## Jetty 12.1 EE8 migration details

Jetty 9 is no longer supported by the community. The Java 21 application
relied on Jetty 9-only classes, client HTTP APIs, and custom copy of an
old ProxyServlet implementation.

Jetty 12.1 provides Java EE 8 / `javax.servlet` support through
`org.eclipse.jetty.ee8:jetty-ee8-servlet` and
`org.eclipse.jetty.ee8:jetty-ee8-proxy`, backed by Jetty Core 12.1.
The new proxy adapter retains the *public application listener SPI* used
by KLB and KQS without changing their jars. The CONNECT handler is now the
core `org.eclipse.jetty.server.handler.ConnectHandler` and the Servlet
context is bridged to its core Handler with `context.get()`.

The legacy custom Jetty9 request retry and reflection workaround are not
reimplemented; the supported EE8 proxy implementation now owns the
HTTP transport and retry behavior. The `RequestContentListener` and
`ReverseProxyServlet` preserve the app-specific capture/notification
hooks. Transport tests use loopback-only HTTP GET, POST with a body,
and CONNECT that returns raw tunneled bytes; no game traffic is used in CI.

## Gates before installing on Linux Mint

1. Merge only after both JDKs, current KLB/KQS plugin API,
   loopback GET/POST/CONNECT, and updater/distribution CI pass.
2. Build and download an **explicitly staged** 25.0.10 application candidate.
   The existing 25.0.9 JAR and its known sha256 are not 25.0.10.
3. Test GUI and Jetty port 8888 with historical data in an isolated clone.
   Confirm KLB/Chrome port 8891 and KQS JSON output in that environment,
   then do supervised production update with rollback.
4. Do not remove or overwrite the 25.0.9 production JAR, either archived
   old JAR, 770 MB full backup, or user configuration automatically.
5. Keep `auto.sh` in `--dry-run` mode until separately authorized
   for live updates.

A passing CI does **not** prove the old game proxy integration on the real
Linux Mint installation; live KLB/KQS and GUI smoke checks are still required
for rollout of the new version. GitHub repository rename to
`ItukiMac/logbook-kai2` is likewise a separate owner action and must
be coordinated with release endpoint changes before the next publication.
