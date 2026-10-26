#!/usr/bin/env python3
"""Read-only, budgeted JSON/JSONC syntax audit of tracked game definitions."""
from __future__ import annotations
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
from maintenance_inventory import GAME, RESOURCES, safe_path

VALIDATOR = "game-jsonc-audit-v2"


def strip_comments(text: str) -> str:
    """Preserve string contents and line offsets while stripping // and /* */ comments."""
    out = list(text)
    state = "normal"
    escaped = False
    i = 0
    while i < len(text):
        c = text[i]
        nxt = text[i:i+2]
        if state == "string":
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                state = "normal"
        elif state == "line":
            if c in "\r\n":
                state = "normal"
            else:
                out[i] = " "
        elif state == "block":
            if nxt == "*/":
                out[i:i+2] = [" ", " "]
                i += 1
                state = "normal"
            elif c not in "\r\n":
                out[i] = " "
        elif c == '"':
            state = "string"
        elif nxt in ("//", "/*"):
            state = "line" if nxt == "//" else "block"
            out[i:i+2] = [" ", " "]
            i += 1
        i += 1
    if state == "block":
        raise ValueError("Unterminated block comment")
    return "".join(out)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate object key")
        result[key] = value
    return result


def parse_jsonc(text: str):
    def invalid_constant(value):
        raise ValueError("Non-finite JSON constant")
    return json.loads(strip_comments(text.lstrip("\ufeff")), object_pairs_hook=unique_object,
                      parse_float=Decimal, parse_constant=invalid_constant)


def game_path(path: str) -> bool:
    if not path.startswith(RESOURCES) or not path.endswith(".json"):
        return False
    parts = PurePosixPath(path[len(RESOURCES):]).parts
    return len(parts) > 2 and parts[0] == "logbook" and parts[1] in GAME


def audit(root: Path, paths: list[str], previous: dict | None = None,
          max_files: int | None = None) -> dict:
    if max_files is not None and (type(max_files) is not int or max_files < 0):
        raise ValueError("Nonnegative file budget required")
    if previous is not None and (not isinstance(previous, dict) or previous.get("validator") != VALIDATOR):
        raise ValueError("Previous validator version does not match")
    previous_entries = (previous or {}).get("entries", [])
    if not isinstance(previous_entries, list) or any(not isinstance(e, dict) for e in previous_entries):
        raise ValueError("Invalid previous report entries")
    cached = {e["path"]: e for e in previous_entries
              if e.get("status") in ("valid", "cached", "error") and isinstance(e.get("path"), str)}
    root = root.resolve(strict=True)
    result = {"schema_version": 1, "validator": VALIDATOR,
              "scope": "tracked game JSON syntax only; not game-rule or runtime-consumer validation",
              "entries": [], "counts": {"validated": 0, "reused": 0, "reused_errors": 0,
                                         "attempted": 0, "pending": 0, "errors": 0}}
    selected = sorted(set(p for p in paths if game_path(p)))
    if not selected:
        result["scope_error"] = "No tracked game definition files selected"
        result["counts"]["errors"] = 1
    for relative in selected:
        row = {"path": relative}
        try:
            # Recheck current path safety and bytes even when reusing parse evidence.
            raw = safe_path(root, relative).read_bytes()
            row["sha256"] = hashlib.sha256(raw).hexdigest()
            old = cached.get(relative)
            if old and old.get("sha256") == row["sha256"]:
                if old["status"] == "error":
                    row.update(status="error", error=old.get("error", "PreviousParseError"), evidence="reused")
                    result["counts"]["errors"] += 1
                    result["counts"]["reused_errors"] += 1
                else:
                    row.update(status="cached", root_type=old.get("root_type", "unknown"))
                    result["counts"]["reused"] += 1
            elif max_files is not None and result["counts"]["attempted"] >= max_files:
                row["status"] = "pending"
                result["counts"]["pending"] += 1
            else:
                result["counts"]["attempted"] += 1
                parsed = parse_jsonc(raw.decode("utf-8"))
                row.update(status="valid", root_type=type(parsed).__name__)
                result["counts"]["validated"] += 1
        except (OSError, ValueError, UnicodeError, RecursionError) as error:
            row.update(status="error", error=type(error).__name__, evidence="fresh")
            result["counts"]["errors"] += 1
        result["entries"].append(row)
    result["complete"] = not (result["counts"]["pending"] or result["counts"]["errors"])
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--max-files", type=int)
    args = parser.parse_args()
    try:
        previous = None
        if args.previous:
            if args.previous.is_symlink() or not args.previous.is_file() or args.previous.stat().st_size > 4 * 1024 * 1024:
                raise ValueError("Unsafe or oversized previous report")
            previous = json.loads(args.previous.read_text(encoding="utf-8"))
        root = args.root.resolve(strict=True)
        paths = [p.decode("utf-8") for p in subprocess.check_output(
            ["git", "-C", str(root), "ls-files", "-z"]).split(b"\0") if p]
        result = audit(root, paths, previous, args.max_files)
        result["commit"] = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
        return 1 if result["counts"]["errors"] else (2 if result["counts"]["pending"] else 0)
    except (OSError, ValueError, TypeError, KeyError, subprocess.CalledProcessError) as error:
        print(f"Audit failed: {type(error).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
