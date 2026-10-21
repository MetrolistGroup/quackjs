import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("snapshot_consumer", Path(__file__).with_name("snapshot-consumer.py"))
snapshot_consumer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(snapshot_consumer)


class SnapshotTest(unittest.TestCase):
    def test_dirty_tracked_bytes_only_and_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "original"
            source.mkdir()
            subprocess.run(["git", "init", "-q", str(source)], check=True)
            for name in ("source.kt", "deleted.kt", ".env", "local.properties"):
                (source / name).write_text("baseline")
            subprocess.run(["git", "-C", str(source), "add", "."], check=True)
            subprocess.run(["git", "-C", str(source), "-c", "user.name=Test", "-c", "user.email=test@localhost",
                            "-c", "core.hooksPath=/dev/null", "commit", "-qm", "chore: create fixture"], check=True)
            (source / "source.kt").write_text("dirty user contents")
            (source / "deleted.kt").unlink()
            (source / "untracked.txt").write_text("not copied")
            destination = root / "snapshot"
            manifest = snapshot_consumer.snapshot(source, destination)
            self.assertEqual("dirty user contents", (destination / "source.kt").read_text())
            self.assertEqual(["source.kt"], list(manifest["sha256"]))
            self.assertEqual({".env", "local.properties"}, set(manifest["excluded"]))
            self.assertEqual(["deleted.kt"], manifest["missing"])
            snapshot_consumer.verify(destination, json.loads(json.dumps(manifest)))
            (destination / "source.kt").write_text("changed")
            with self.assertRaises(ValueError):
                snapshot_consumer.verify(destination, manifest)
            self.assertEqual("dirty user contents", (source / "source.kt").read_text())
            with self.assertRaises(ValueError):
                snapshot_consumer.snapshot(source, source / "nested")


if __name__ == "__main__":
    unittest.main()
