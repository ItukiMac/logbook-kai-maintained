# L4/A3 stream contract and failure isolation

Independent continuation of PR28. A ContentListenerSpi.test exception previously ended dispatch for every subsequent listener. Catch per-listener predicate/setup failures and retain existing accept isolation without changing the SPI signatures.

ByteArrayInputStream2 now follows the InputStream argument/EOF/zero-length-read and mark/reset contracts, snapshots the segment list, and reports all remaining bytes. Reset before the first mark still returns to the beginning. Seven stream tests cover the contracts.

Four additional actual loopback HTTP tests cover forwarding POST/headers through a configured upstream proxy to a deliberately unresolvable origin name, refused-origin HTTP errors, and predicate/accept exception isolation with a healthy listener still receiving the full response. Tests restore in-memory proxy configuration and never save it. CI is not a real KLB/KQS/Mint/browser certification. Aggregate capture memory limits and long-lived real traffic remain separate work.
