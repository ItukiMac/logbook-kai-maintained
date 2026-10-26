# A/D/M: full tracked game JSON syntax audit with bounded resumption

`python3 scripts/audit_game_json.py --root .` reads Git-tracked game definition JSON only, prints JSON to stdout and never changes source/runtime files or performs network/LLM requests. It supports legacy // and /* */ comments while preserving strings, rejects malformed/trailing JSON, duplicate object keys and non-finite constants. Symlinked/missing/oversized files are errors rather than silently omitted.

`--max-files 20` bounds fresh parse attempts; pending work remains explicitly pending and exits 2. Capture stdout to a NEW report filename. A later invocation with `--previous previous-report.json --max-files 20` can reuse matching path/SHA256/validator successes. Changed files are revalidated. Cached results are identified separately from freshly validated results. Treat a previous report as trusted local evidence, not an authenticated attestation. CI deliberately performs a fresh full audit without cache reuse. Errors exit 1; complete success exits 0.

This controls validation work units, not financial/token spending: no paid API is called. It does not validate the semantic correctness of quest conditions, resolve every dynamic Java lookup, update definitions from web sources, enable periodic jobs, automatically commit/merge or deploy. Those remain separate M/D tasks.
