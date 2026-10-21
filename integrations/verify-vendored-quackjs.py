#!/usr/bin/env python3
"""Verify that a private migrated InnerTubeX copy contains this checkout's exact core."""
import hashlib
import json
from pathlib import Path
import sys

checkout = Path(__file__).resolve().parent.parent
module = Path(sys.argv[1]) / "quackjs"
provenance = json.loads((module / "UPSTREAM.json").read_text())
expected = {str(path.relative_to(checkout)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (checkout / "src/commonMain/kotlin/meow/nyx/quackjs").rglob("*.kt")}
actual = {str(path.relative_to(module)): hashlib.sha256(path.read_bytes()).hexdigest()
          for path in (module / "src/commonMain/kotlin/meow/nyx/quackjs").rglob("*.kt")}
assert expected, "Core sources are missing"
assert actual == expected, "Vendored core differs from this checkout; synchronize before publishing"
assert provenance["sha256"] == actual, "Vendored source provenance is stale"
print(f"Verified {len(actual)} vendored core files and provenance hashes")
