import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

HELPER = (
    pathlib.Path(__file__).resolve().parents[2]
    / "dist-includes/helpers/apply-staged-update.py"
)
spec = importlib.util.spec_from_file_location("logbook_staged_update", HELPER)
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)


def new_jar(path):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(
            "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\r\nMain-Class: logbook.internal.Launcher\r\n\r\n",
        )
        z.writestr("logbook/internal/Launcher.class", b"fake compiled class")
        z.writestr("payload.txt", b"new version")


class StagedUpdateTest(unittest.TestCase):
    def setUp(self):
        self.holder = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.holder.name) / "logbook-kai"
        self.root.mkdir()
        self.active = self.root / "logbook-kai.jar"
        self.active.write_bytes(b"PREVIOUS_LIVE_JAR")
        self.update = self.root / "update"
        self.update.mkdir()
        self.stage = self.update / "logbook-kai.jar"

    def tearDown(self):
        self.holder.cleanup()

    def test_no_update_is_noop(self):
        self.assertEqual(updater.apply_staged(self.root), "NO_UPDATE")
        self.assertEqual(self.active.read_bytes(), b"PREVIOUS_LIVE_JAR")

    def test_valid_stage_replaces_jar_and_keeps_backup(self):
        new_jar(self.stage)
        original_stage_bytes = self.stage.read_bytes()
        result = updater.apply_staged(self.root)
        self.assertTrue(result.startswith("UPDATED old_sha256="))
        self.assertEqual(self.active.read_bytes(), original_stage_bytes)
        self.assertFalse(self.stage.exists())
        backups = list((self.root / "launcher-backups/app-versions").glob("*.jar"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(backups[0].read_bytes(), b"PREVIOUS_LIVE_JAR")
        # A second invocation after successful installation is a no-op.
        self.assertEqual(updater.apply_staged(self.root), "NO_UPDATE")

    def test_invalid_stage_does_not_touch_live(self):
        self.stage.write_text("invalid zip")
        with self.assertRaises(ValueError):
            updater.apply_staged(self.root)
        self.assertEqual(self.active.read_bytes(), b"PREVIOUS_LIVE_JAR")
        self.assertTrue(self.stage.exists())
        self.assertFalse((self.root / "launcher-backups").exists())

    def test_dry_run_never_changes_live_or_stage(self):
        new_jar(self.stage)
        current = self.active.read_bytes()
        stage = self.stage.read_bytes()
        self.assertTrue(updater.apply_staged(self.root, dry_run=True).startswith("VALIDATED "))
        self.assertEqual(self.active.read_bytes(), current)
        self.assertEqual(self.stage.read_bytes(), stage)

    def test_staging_same_version_only_discards_redundant_stage(self):
        new_jar(self.stage)
        self.active.write_bytes(self.stage.read_bytes())
        self.assertEqual(updater.apply_staged(self.root), "ALREADY_INSTALLED")
        self.assertFalse(self.stage.exists())
        self.assertFalse((self.root / "launcher-backups").exists())

    def test_symlinked_candidate_is_rejected(self):
        outside = pathlib.Path(self.holder.name) / "outside.jar"
        new_jar(outside)
        self.stage.symlink_to(outside)
        with self.assertRaises(ValueError):
            updater.apply_staged(self.root)
        self.assertEqual(self.active.read_bytes(), b"PREVIOUS_LIVE_JAR")

    def test_symlinked_update_folder_is_rejected(self):
        self.update.rmdir()
        outside = pathlib.Path(self.holder.name) / "outside"
        outside.mkdir()
        self.update.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            updater.apply_staged(self.root)
        self.assertEqual(self.active.read_bytes(), b"PREVIOUS_LIVE_JAR")


if __name__ == "__main__":
    unittest.main()
