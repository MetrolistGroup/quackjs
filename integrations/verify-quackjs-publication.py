#!/usr/bin/env python3
"""Check the locally published runtime variants, bytecode and absence of JS/native payloads."""
import io
import json
from pathlib import Path
import struct
import sys
import zipfile

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path.home() / ".m2/repository/meow/nyx"
version = "0.1.0-SNAPSHOT"
modules = ["quackjs", "quackjs-jvm", "quackjs-android", "quackjs-iosarm64", "quackjs-iossimulatorarm64", "quackjs-linuxx64"]
for module in modules:
    metadata = root / module / version / f"{module}-{version}.module"
    data = json.loads(metadata.read_text())
    for variant in data["variants"]:
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
