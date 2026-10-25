import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("inventory", Path(__file__).resolve().parents[1] / "maintenance_inventory.py")
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)

class InventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.paths = []

    def put(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        self.paths.append(path)
        return target

    def test_game_and_user_resources_are_separate(self):
        for category in sorted(M.GAME):
            self.put(f"src/main/resources/logbook/{category}/1.json", "{}")
        self.put("src/main/resources/logbook/capture/config.json", "{}")
        result = M.collect(self.root, self.paths)
        self.assertEqual(6, result["counts"]["game_json"])
        self.assertEqual(7, result["counts"]["json"])
        self.assertEqual(1, len(result["json_categories"]["excluded_user_or_other"]))

    def test_literal_and_dynamic_sites_are_not_claimed_as_coverage(self):
        self.put("src/main/resources/logbook/map/maps.json", "{}")
        self.put("src/main/java/logbook/Test.java", 'getResource("logbook/map/maps.json");\ngetResource(prefix + id + ".json");\nstatic Map<String,Object> cache;\nTextFields.bindAutoCompletion(editor);')
        result = M.collect(self.root, self.paths)
        self.assertEqual(2, len(result["literal_json_references"]))
        self.assertEqual(1, len(result["literal_json_references"][0]["candidate_paths"]))
        self.assertEqual([], result["literal_json_references"][1]["candidate_paths"])
        self.assertEqual(2, len(result["lookup_candidates"]))
        self.assertEqual(1, len(result["static_cache_candidates"]))
        self.assertEqual(1, len(result["autocomplete_candidates"]))

    def test_file_symlink_is_not_followed(self):
        outside = self.put("private.json", '{"private":"never read"}')
        path = "src/main/resources/logbook/quest/link.json"
        target = self.root / path
        target.parent.mkdir(parents=True)
        target.symlink_to(outside)
        result = M.collect(self.root, [path])
        self.assertEqual(1, result["counts"]["skipped"])
        self.assertEqual([], result["source_files"])

    def test_parent_symlink_is_not_followed(self):
        self.put("outside/a.json", "{}")
        parent = self.root / "src/main/resources/logbook/quest"
        parent.parent.mkdir(parents=True)
        parent.symlink_to(self.root / "outside", target_is_directory=True)
        result = M.collect(self.root, ["src/main/resources/logbook/quest/a.json"])
        self.assertEqual(1, result["counts"]["skipped"])

    def test_traversal_and_absolute_paths_are_refused(self):
        for path in ("../pom.xml", "/tmp/pom.xml", "src/../../pom.xml", "src\\pom.xml"):
            with self.assertRaises(ValueError):
                M.safe_path(self.root, path)

    def test_inventory_is_deterministic_and_does_not_modify_source(self):
        target = self.put("src/main/resources/logbook/quest/1.json", "{ /* legacy JSON comment */ }")
        before = target.read_bytes()
        first = M.collect(self.root, self.paths + self.paths)
        self.assertEqual(first, M.collect(self.root, list(reversed(self.paths))))
        self.assertEqual(before, target.read_bytes())
        self.assertEqual(hashlib.sha256(before).hexdigest(), first["source_files"][0]["sha256"])

    def test_pom_expressions_are_preserved_not_invented(self):
        self.put("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>${v}</version></dependency></dependencies></project>')
        self.assertEqual("${v}", M.collect(self.root, self.paths)["dependencies"][0]["version"])

    def test_private_runtime_files_are_not_scanned(self):
        self.put("config/player.json", "secret")
        self.put("logs/game.json", "secret")
        self.put("plugins/personal.java", "secret")
        self.assertEqual([], M.collect(self.root, self.paths)["source_files"])

    def test_missing_tracked_source_is_reported(self):
        result = M.collect(self.root, ["src/main/java/logbook/Missing.java"])
        self.assertEqual(1, result["counts"]["skipped"])

    def test_malformed_pom_fails_instead_of_claiming_complete(self):
        self.put("pom.xml", "<project>")
        with self.assertRaises(ET.ParseError):
            M.collect(self.root, self.paths)

if __name__ == "__main__":
    unittest.main()
