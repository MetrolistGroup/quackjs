#!/usr/bin/env python3
"""Check the locally published runtime variants, bytecode and absence of JS/native payloads."""
import hashlib
import io
import json
from pathlib import Path
import struct
import sys
import zipfile

if len(sys.argv) != 3:
    raise SystemExit("Usage: verify-publication.py <Maven-group-directory> <version>")
root = Path(sys.argv[1])
version = sys.argv[2]
group = "com.github.MetrolistGroup.quackjs"
modules = ["quackjs", "quackjs-jvm", "quackjs-android", "quackjs-iosarm64", "quackjs-iossimulatorarm64", "quackjs-linuxx64"]
for module in modules:
    metadata = root / module / version / f"{module}-{version}.module"
    data = json.loads(metadata.read_text())
    assert data["component"]["group"] == group, data["component"]
    assert data["component"]["module"] == "quackjs", data["component"]
    if module != "quackjs":
        owner = metadata.parent / data["component"]["url"]
        assert owner.resolve() == (root / "quackjs" / version / f"quackjs-{version}.module").resolve(), owner
    assert data["component"]["version"] == version, data["component"]
    for variant in data["variants"]:
        if "available-at" in variant:
            target = variant["available-at"]
            assert target["group"] == group and target["version"] == version, target
            assert target["module"] in modules, target
            expected = root / target["module"] / version / f"{target['module']}-{version}.module"
            assert (metadata.parent / target["url"]).resolve() == expected.resolve(), target
            assert expected.is_file(), expected
        for artifact in variant.get("files", []):
            payload = metadata.parent / artifact["url"]
            assert payload.is_file(), payload
            if "sha256" in artifact:
                assert hashlib.sha256(payload.read_bytes()).hexdigest() == artifact["sha256"], payload
        for dependency in variant.get("dependencies", []):
            assert (dependency["group"], dependency["module"]) == ("org.jetbrains.kotlin", "kotlin-stdlib"), dependency
    print(f"VERIFIED stdlib-only metadata: {module}")


def check_classes(archive):
    classes = 0
    for name in archive.namelist():
        assert not any(part in name.lower() for part in ("quickjs", "yt_ejs")), name
        assert not name.endswith((".so", ".dll", ".dylib", ".js")), name
        if name.endswith(".class"):
            contents = archive.read(name)
            assert contents[:4] == b"\xca\xfe\xba\xbe", name
            assert struct.unpack(">H", contents[6:8])[0] == 61, name  # Java 17
            classes += 1
    assert classes > 0, "No class files in runtime archive"
    assert "meow/nyx/quackjs/PlayerProgram.class" in archive.namelist()
    return classes


jvm = root / "quackjs-jvm" / version / f"quackjs-jvm-{version}.jar"
with zipfile.ZipFile(jvm) as archive:
    print(f"VERIFIED JVM 17: {check_classes(archive)} classes, no JS/native payload")
android = root / "quackjs-android" / version / f"quackjs-android-{version}.aar"
with zipfile.ZipFile(android) as archive:
    assert "AndroidManifest.xml" in archive.namelist()
    assert not any(name.startswith("jni/") for name in archive.namelist())
    with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
        print(f"VERIFIED Android JVM 17: {check_classes(classes)} classes, no JNI payload")
