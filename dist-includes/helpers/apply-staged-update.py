#!/usr/bin/env python3
"""Apply an already staged logbook-kai JAR at the next Linux launcher start.

This tool is NOT a replacement launcher. It is intentionally inert unless
update/logbook-kai2.jar exists. Intended to be called by the existing auto.sh
*before* it starts Java. It does not interact with Chrome, KLB, or game data.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

JAR_NAME = "logbook-kai2.jar"
MAX_JAR_SIZE = 256 * 1024 * 1024
MAX_EXPANDED_SIZE = 768 * 1024 * 1024


def is_regular_no_link(path: Path) -> bool:
    return not path.is_symlink() and path.is_file()


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def validate_jar(path: Path) -> None:
    if not is_regular_no_link(path):
        raise ValueError(f"Not an ordinary staged JAR: {path}")
    if not (0 < path.stat().st_size <= MAX_JAR_SIZE):
        raise ValueError("Unexpected JAR size")
    try:
        with zipfile.ZipFile(path) as jar:
            entries = jar.infolist()
            if len(entries) > 30000 or sum(e.file_size for e in entries) > MAX_EXPANDED_SIZE:
                raise ValueError("JAR exceeds expanded-size limit")
            attrs = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
            main_classes = [
                line.partition(":")[2].strip()
                for line in attrs.splitlines()
                if line.lower().startswith("main-class:")
            ]
            if main_classes != ["logbook.internal.Launcher"]:
                raise ValueError("Unexpected application Main-Class")
            if "logbook/internal/Launcher.class" not in jar.namelist():
                raise ValueError("Missing application launcher class")
            bad = jar.testzip()
            if bad is not None:
                raise ValueError(f"Corrupt JAR entry: {bad}")
    except (OSError, KeyError, UnicodeError, zipfile.BadZipFile) as exc:
        raise ValueError("Staged file is not a valid logbook-kai JAR") from exc


def copied_temporary(source: Path, directory: Path, prefix: str) -> Path:
    fd, temporary = tempfile.mkstemp(prefix=prefix, suffix=".tmp", dir=directory)
    path = Path(temporary)
    try:
        with os.fdopen(fd, "wb") as out, source.open("rb") as src:
            shutil.copyfileobj(src, out, length=1024 * 1024)
            out.flush()
            os.fsync(out.fileno())
        if sha256(path) != sha256(source):
            raise OSError("Copy failed checksum validation")
        return path
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def apply_staged(root: Path, *, dry_run: bool = False) -> str:
    root = Path(os.path.abspath(root))
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Application root is absent or symlinked")
    active = root / JAR_NAME
    staging_dir = root / "update"
    staged = staging_dir / JAR_NAME

    if staging_dir.is_symlink():
        raise ValueError("Refusing symlinked update directory")
    if not staged.exists() and not staged.is_symlink():
        return "NO_UPDATE"
    if not is_regular_no_link(active):
        raise ValueError("Current application JAR missing or symlinked")

    validate_jar(staged)
    existing_digest = sha256(active)
    candidate_digest = sha256(staged)
    if dry_run:
        return f"VALIDATED {candidate_digest}"
    if existing_digest == candidate_digest:
        staged.unlink()
        return "ALREADY_INSTALLED"

    backups = root / "launcher-backups"
    backup_dir = backups / "app-versions"
    if backups.is_symlink() or backup_dir.is_symlink():
        raise ValueError("Refusing symlinked backup directory")
    backup_dir.mkdir(parents=True, exist_ok=True)
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    backup_fd, backup_path = tempfile.mkstemp(
        prefix=f"logbook-kai-{timestamp}-{existing_digest[:12]}-",
        suffix=".jar", dir=backup_dir)
    os.close(backup_fd)
    backup = Path(backup_path)

    candidate = None
    try:
        # Back up before touching the active JAR. Never purge previous versions.
        shutil.copy2(active, backup)
        with backup.open("rb") as stream:
            os.fsync(stream.fileno())
        if sha256(backup) != existing_digest:
            raise OSError("Existing version backup did not verify")

        candidate = copied_temporary(staged, root, ".next-logbook-kai-")
        validate_jar(candidate)
        # Same directory; rename is atomic on ordinary local filesystems.
        os.replace(candidate, active)
        candidate = None
    except BaseException:
        if candidate is not None:
            candidate.unlink(missing_ok=True)
        # If the replacement failed, os.replace preserves the prior active JAR.
        raise

    staged.unlink()
    return f"UPDATED old_sha256={existing_digest} new_sha256={candidate_digest} backup={backup}"



def restore_backup(root: Path, backup_arg: Path, *, dry_run: bool = False) -> str:
    """Explicit rollback to a verified earlier application JAR.

    This never runs automatically. Caller must supply an exact backup file;
    the tool never guesses which prior version was healthy.
    """
    root = Path(os.path.abspath(root))
    if root.is_symlink() or not root.is_dir():
        raise ValueError("Application root missing or symlinked")
    active = root / JAR_NAME
    backups = root / "launcher-backups"
    folder = backups / "app-versions"
    if backups.is_symlink() or folder.is_symlink() or not folder.is_dir():
        raise ValueError("Untrusted or absent backup directory")

    backup = Path(os.path.abspath(backup_arg))
    if backup.parent != folder or not is_regular_no_link(backup):
        raise ValueError("Backup must be an ordinary file directly in the backup directory")
    matched = re.fullmatch(
        r"logbook-kai-\d{8}T\d{6}Z-([0-9a-f]{12})-[^/]+\.jar",
        backup.name,
    )
    if matched is None:
        raise ValueError("Backup filename does not match the expected format")

    validate_jar(backup)
    backup_digest = sha256(backup)
    if not backup_digest.startswith(matched.group(1)):
        raise ValueError("Backup does not match the recorded original checksum")
    if not is_regular_no_link(active):
        raise ValueError("Active application JAR missing or symlinked")
    active_digest = sha256(active)
    if dry_run:
        return f"RESTORE_VALIDATED backup_sha256={backup_digest}"
    if active_digest == backup_digest:
        return "ALREADY_RESTORED"

    # Preserve the currently active JAR as an audit/forensic copy before
    # replacement. Even a failed startup version must not be discarded.
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    current_fd, current_path = tempfile.mkstemp(
        prefix=f"rollback-source-{timestamp}-{active_digest[:12]}-",
        suffix=".jar", dir=folder
    )
    os.close(current_fd)
    current_snapshot = Path(current_path)
    replacement = None
    try:
        shutil.copy2(active, current_snapshot)
        if sha256(current_snapshot) != active_digest:
            raise OSError("Snapshot of current JAR did not verify")
        replacement = copied_temporary(backup, root, ".restore-logbook-kai-")
        validate_jar(replacement)
        os.replace(replacement, active)
        replacement = None
    finally:
        if replacement is not None:
            replacement.unlink(missing_ok=True)

    return f"RESTORED sha256={backup_digest} previous_sha256={active_digest} original={current_snapshot}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--restore-backup", type=Path,
                        help="Explicitly restore the specified verified older application JAR")
    args = parser.parse_args()
    try:
        if args.restore_backup:
            result = restore_backup(args.root, args.restore_backup, dry_run=args.dry_run)
        else:
            result = apply_staged(args.root, dry_run=args.dry_run)
        print(result)
        return 0
    except (OSError, ValueError) as exc:
        print(f"STAGED_UPDATE_FAILED: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
