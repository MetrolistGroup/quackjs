#!/usr/bin/env python3
"""Copy current tracked worktree bytes (including dirty files), never .git or credentials.

Use a new private destination. The manifest records the upstream commit, dirty status,
submodule commits and SHA-256 of every copied file. Nothing is written to the source.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


EXCLUDED_DIRS = {".git", "build", ".gradle", ".kotlin", ".claude", "secrets"}
EXCLUDED_NAMES = {"local.properties", ".env", "innertube_cookie.txt"}
EXCLUDED_SUFFIXES = {".har", ".jks", ".keystore", ".p12", ".pem"}


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def excluded(path):
    return (bool(set(path.parts) & EXCLUDED_DIRS)
            or path.name in EXCLUDED_NAMES or path.name.startswith(".env.")
            or path.suffix in EXCLUDED_SUFFIXES)


def snapshot(source, destination):
    source = source.resolve()
    destination = destination.resolve()
    if destination == source or source in destination.parents:
        raise ValueError("Destination must be outside the original repository")
    destination.mkdir(mode=0o700, parents=True, exist_ok=False)
    manifest = {"repository": source.name, "baseCommit": git(source, "rev-parse", "HEAD").decode().strip(),
                "status": git(source, "status", "--porcelain=v1").decode(),
                "submodules": {}, "sha256": {}, "excluded": [], "missing": []}

    def copy_tracked(repository, prefix=Path()):
        for record in git(repository, "ls-files", "--stage", "-z").split(b"\0"):
            if not record:
                continue
            metadata, name = record.decode().split("\t", 1)
            mode, commit, stage = metadata.split()
            if stage != "0":
                raise ValueError("Unmerged index entry: " + name)
            relative = prefix / name
            if excluded(relative):
                manifest["excluded"].append(str(relative))
                continue
            original = repository / name
            if mode == "160000":
                if not (original / ".git").exists():
                    raise ValueError("Initialize the submodule in a separate copy: " + str(relative))
                manifest["submodules"][str(relative)] = {
                    "indexCommit": commit, "worktreeCommit": git(original, "rev-parse", "HEAD").decode().strip()}
                copy_tracked(original, relative)
                continue
            if original.is_symlink():
                raise ValueError("Refusing tracked symlink; inspect it manually: " + str(relative))
            if not original.is_file():
                manifest["missing"].append(str(relative))
                continue
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(original, target)
            manifest["sha256"][str(relative)] = hashlib.sha256(target.read_bytes()).hexdigest()

    copy_tracked(source)
    return manifest


def verify(root, manifest):
    failures = [name for name, expected in manifest["sha256"].items()
                if not (root / name).is_file() or hashlib.sha256((root / name).read_bytes()).hexdigest() != expected]
    if failures:
        raise ValueError("Snapshot differs: " + ", ".join(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path, nargs="?")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--verify", action="store_true", help="Only verify SOURCE against an existing manifest")
    args = parser.parse_args()
    if args.verify:
        verify(args.source, json.loads(args.manifest.read_text()))
        print("All baseline file hashes match")
    else:
        if args.destination is None:
            parser.error("destination is required when copying")
        manifest_path = args.manifest.resolve()
        for directory in (args.source.resolve(), args.destination.resolve()):
            if manifest_path == directory or directory in manifest_path.parents:
                parser.error("manifest must be outside the source and snapshot")
        if args.manifest.exists():
            parser.error("manifest already exists")
        result = snapshot(args.source, args.destination)
        # Write outside the snapshot so the baseline itself stays an exact source tree.
        with args.manifest.open("x") as output:
            os.chmod(args.manifest, 0o600)
            json.dump(result, output, indent=2, sort_keys=True)
            output.write("\n")
        verify(args.destination, result)
        print(f"Copied and verified {len(result['sha256'])} tracked files")


if __name__ == "__main__":
    main()
