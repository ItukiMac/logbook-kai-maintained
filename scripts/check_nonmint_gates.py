#!/usr/bin/env python3
"""Fail closed on incomplete maintenance gates; read-only, no network or installation."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys

SCHEMA = "logbook-mint-independent-gates-v1"
STATUSES = {"development", "blocked_evidence", "native_mint_required", "manual_approval_required"}
MINIMUM_IDS = {"R11-%02d" % n for n in range(1, 20)}


def validate(doc: dict, root: Path) -> dict:
    if not isinstance(doc, dict) or doc.get("schema") != SCHEMA:
        raise ValueError("invalid gate ledger schema")
    basis = doc.get("basis")
    if not isinstance(basis, dict) or basis.get("rules_first_pass") != 148:
        raise ValueError("missing tracked definition scope")
    if not isinstance(basis.get("rules_whole_verified"), int) or basis["rules_whole_verified"] < 0 or basis["rules_whole_verified"] > 148:
        raise ValueError("invalid complete-rule tally")
    tasks = doc.get("tasks")
    if not isinstance(tasks, list) or not tasks:
        raise ValueError("empty task ledger")
    seen_ids, seen_keys, counters = set(), set(), {s: 0 for s in STATUSES}
    for task in tasks:
        if not isinstance(task, dict):
            raise ValueError("malformed task")
        name, key, status = task.get("id"), task.get("key"), task.get("status")
        if not isinstance(name, str) or name in seen_ids or not isinstance(key, str) or key in seen_keys:
            raise ValueError("duplicate or invalid task identity")
        seen_ids.add(name)
        seen_keys.add(key)
        if status not in STATUSES:
            raise ValueError("unknown task status")
        counters[status] += 1
        if task.get("verified_complete") is not False:
            raise ValueError("unfinished gate cannot be marked complete without verification")
        if not isinstance(task.get("title"), str) or not task["title"].strip():
            raise ValueError("missing title")
        if not isinstance(task.get("acceptance"), str) or not task["acceptance"].strip():
            raise ValueError("missing acceptance gate")
        intended = status in {"development", "blocked_evidence"}
        if task.get("mint_independent") is not intended:
            raise ValueError("incorrect Mint-only classification")
        evidence = task.get("evidence")
        if not isinstance(evidence, str) or not evidence.startswith("docs/maintenance/") or ".." in Path(evidence).parts:
            raise ValueError("invalid evidence path")
        path = root / evidence
        if not path.is_file() or path.is_symlink():
            raise ValueError("missing evidence document: " + evidence)
    if seen_ids != MINIMUM_IDS:
        raise ValueError("expected full initial ledger: %s" % sorted(MINIMUM_IDS - seen_ids))
    if basis["rules_whole_verified"] == 0 and any(t.get("key") == "quest-acceptance-reset" and t.get("verified_complete") for t in tasks):
        raise ValueError("whole rule verification is not complete")
    return {"schema": SCHEMA, "as_of": doc.get("as_of"),
            "tasks": len(tasks), "mint_independent_open": sum(counters[s] for s in ("development", "blocked_evidence")),
            "native_mint_open": counters["native_mint_required"],
            "manual_approval": counters["manual_approval_required"],
            "release_ready": False, "auto_publish_authorized": False,
            "whole_rules_verified": basis["rules_whole_verified"]}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--ledger", default="docs/maintenance/non-mint-completion-gates.json")
    args = parser.parse_args()
    root = args.root.resolve(strict=True)
    ledger = (root / args.ledger).resolve(strict=True)
    if not ledger.is_relative_to(root):
        raise ValueError("ledger outside checkout")
    report = validate(json.loads(ledger.read_text(encoding="utf-8")), root)
    print(json.dumps(report, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, json.JSONDecodeError) as ex:
        print("GATE_LEDGER_INVALID: " + str(ex), file=sys.stderr)
        sys.exit(2)
