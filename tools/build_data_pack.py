#!/usr/bin/env python3
"""Build a deterministic, read-only JSON data pack from bundled game definitions.

Does not publish, activate or modify source JSON. Java's legacy quest files may
contain comments; distribution uses strict JSON while retaining the source tree.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

CATEGORIES = ("quest", "eventquest", "kousyou", "mission", "map", "supplemental")
FORMAT = "logbook-data-pack-v1"
STAMP = (2020, 1, 1, 0, 0, 0)


def without_comments(source: str) -> str:
    """Remove Java/JS comments, but keep quoted strings and escape sequences."""
    result = []
    i = 0
    quoted = False
    while i < len(source):
        char = source[i]
        if quoted:
            result.append(char)
            if char == "\\" and i + 1 < len(source):
                i += 1
                result.append(source[i])
            elif char == '"':
                quoted = False
            i += 1
            continue
        if char == '"':
            quoted = True
            result.append(char)
            i += 1
            continue
        if char == "/" and i + 1 < len(source) and source[i + 1] == "/":
            i += 2
            while i < len(source) and source[i] not in "\r\n":
                i += 1
            continue
        if char == "/" and i + 1 < len(source) and source[i + 1] == "*":
            end = source.find("*/", i + 2)
            if end < 0:
                raise ValueError("unterminated JSON comment")
            i = end + 2
            continue
        result.append(char)
        i += 1
    if quoted:
        raise ValueError("unterminated JSON string")
    return "".join(result)


def normalized_json(source: bytes, name: str) -> bytes:
    text = without_comments(source.decode("utf-8-sig"))
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f"{name}: {error}") from error
    return (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def write_entry(archive: ZipFile, name: str, payload: bytes) -> None:
    info = ZipInfo(name, date_time=STAMP)
    info.compress_type = ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, payload, compress_type=ZIP_DEFLATED)


def build(source_root: Path, output: Path, source_sha: str) -> dict:
    entries = []
    for category in CATEGORIES:
        folder = source_root / category
        if not folder.is_dir():
            raise FileNotFoundError(f"Missing data directory: {folder}")
        for source_file in sorted(folder.glob("*.json")):
            raw = source_file.read_bytes()
            payload = normalized_json(raw, str(source_file))
            relative = f"definitions/{category}/{source_file.name}"
            entries.append((relative, payload, raw))

    manifest = {
        "format": FORMAT,
        "source_revision": source_sha,
        "files": [
            {
                "path": path,
                "sha256": hashlib.sha256(payload).hexdigest(),
                "source_sha256": hashlib.sha256(raw).hexdigest(),
                "bytes": len(payload),
            }
            for path, payload, raw in entries
        ],
    }
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w") as archive:
        write_entry(archive, "manifest.json", manifest_bytes)
        for name, payload, _raw in entries:
            write_entry(archive, name, payload)
    return {"files": len(entries), "archive": str(output), "sha256": hashlib.sha256(output.read_bytes()).hexdigest()}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", default="src/main/resources/logbook")
    parser.add_argument("--out", default="target/logbook-data-pack.zip")
    parser.add_argument("--source-sha", default=os.getenv("GITHUB_SHA", "local-unverified"))
    args = parser.parse_args()
    result = build(Path(args.source), Path(args.out), args.source_sha)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
