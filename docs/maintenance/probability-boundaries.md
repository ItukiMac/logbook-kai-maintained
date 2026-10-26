# P2 probability input boundaries

The calculator runs on the UI event thread. Reject a conservative prospective row count (`limit / step + 5`) above 10,000 before allocating or iterating. Invalid/excessive input returns an empty immutable result, as other invalid inputs already do; no rows are silently sampled. A large limit remains supported when step is also large.

Keep the ordinary-input legacy formula and existing 180-case bitwise equivalence tests. For p < 1e-8, log1p/expm1 avoid cancellation. An unrepresentable milestone is omitted rather than displayed at Integer.MAX_VALUE with the wrong claimed threshold. Percent inputs that underflow when divided by 100 are rejected.

Seven additional boundary tests cover the workload bound, tiny probabilities, overflow-sized limits, monotonicity and underflow. Full Maven CI and GUI/Mint validation are separate gates. A user-facing reason for excessive input remains a presentation improvement; production files and configuration are untouched.
