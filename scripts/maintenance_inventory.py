#!/usr/bin/env python3
"""Read tracked source only; emit JSON to stdout. No network or repository writes."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

JAVA = "src/main/java/"
RESOURCES = "src/main/resources/"
GAME = {"quest", "eventquest", "mission", "map", "kousyou", "supplemental"}
LITERAL = re.compile(r'"([^"\n]*\.json)"')
LOOKUP = re.compile(r'\b(?:getResource|getResourceAsStream|resolveManagedResource|activeDefinitionsRoot)\s*\(')
AUTOCOMPLETE = re.compile(r'TextFields\.bindAutoCompletion|\bSuggestSupport\b|com\.sun\.javafx')
CACHE = re.compile(r'\bstatic\b.*\b(?:Map|List|Set|HashMap|ConcurrentHashMap|Cache)\b')
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def selected(path: str) -> bool:
    return (path == "pom.xml" or path.startswith(JAVA) and path.endswith(".java")
            or path.startswith(RESOURCES) and path.endswith(".json"))


def safe_path(root: Path, relative: str) -> Path:
    parts = PurePosixPath(relative)
    if parts.is_absolute() or ".." in parts.parts or "\\" in relative:
        raise ValueError("unsafe repository path")
    path = root
    for part in parts.parts:
        path = path / part
        if path.is_symlink():
            raise ValueError("symlink is not scanned")
    if not path.is_file():
        raise ValueError("tracked file missing or not regular")
    if path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("source file exceeds 2 MiB scan limit")
    return path


def collect(root: Path, paths: list[str]) -> dict:
    root = root.resolve(strict=True)
    report = {
        "schema_version": 1,
        "scope": "tracked Java/resource JSON/pom only; static candidates, NOT runtime coverage",
        "source_files": [], "json_categories": {}, "literal_json_references": [],
        "lookup_candidates": [], "autocomplete_candidates": [], "static_cache_candidates": [],
        "dependencies": [], "build_plugins": [], "skipped": [],
    }
    texts = {}
    for relative in sorted(set(paths)):
        if not selected(relative):
            continue
        try:
            path = safe_path(root, relative)
            data = path.read_bytes()
            text = data.decode("utf-8")
        except (OSError, ValueError, UnicodeError) as exc:
            report["skipped"].append({"path": relative, "reason": type(exc).__name__})
            continue
        report["source_files"].append({"path": relative, "sha256": hashlib.sha256(data).hexdigest()})
        texts[relative] = text
        if relative.startswith(RESOURCES) and relative.endswith(".json"):
            resource = PurePosixPath(relative[len(RESOURCES):])
            category = (resource.parts[1] if len(resource.parts) > 2
                        and resource.parts[0] == "logbook" and resource.parts[1] in GAME
                        else "excluded_user_or_other")
            report["json_categories"].setdefault(category, []).append(relative)
    resources = [p for p in texts if p.startswith(RESOURCES) and p.endswith(".json")]
    for relative, text in texts.items():
        if not relative.startswith(JAVA):
            continue
        for number, line in enumerate(text.splitlines(), 1):
            site = {"path": relative, "line": number}
            for match in LITERAL.finditer(line):
                literal = match.group(1)
                candidates = [p for p in resources if p.endswith("/" + literal.lstrip("/"))]
                report["literal_json_references"].append({**site, "literal": literal,
                                                          "candidate_paths": candidates})
            for pattern, key in [(LOOKUP, "lookup_candidates"), (AUTOCOMPLETE, "autocomplete_candidates"),
                                 (CACHE, "static_cache_candidates")]:
                if pattern.search(line):
                    report[key].append(site)
    if "pom.xml" in texts:
        pom = ET.fromstring(texts["pom.xml"])
        for query, key in [(".//m:dependency", "dependencies"), (".//m:build/m:plugins/m:plugin", "build_plugins")]:
            for node in pom.findall(query, NS):
                report[key].append({name: node.findtext("m:" + name, default="", namespaces=NS)
                                    for name in ("groupId", "artifactId", "version", "scope")})
    report["counts"] = {"java": sum(p.endswith(".java") for p in texts),
                        "json": len(resources), "source_files": len(texts),
                        "skipped": len(report["skipped"]),
                        "game_json": sum(len(v) for k, v in report["json_categories"].items() if k in GAME)}
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    args = parser.parse_args()
    try:
        root = args.root.resolve(strict=True)
        raw = subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"])
        paths = [p.decode("utf-8") for p in raw.split(b"\0") if p]
        report = collect(root, paths)
        report["commit"] = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
        return 1 if report["skipped"] else 0
    except (OSError, ValueError, ET.ParseError, subprocess.CalledProcessError) as exc:
        print(f"Inventory failed: {type(exc).__name__}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
