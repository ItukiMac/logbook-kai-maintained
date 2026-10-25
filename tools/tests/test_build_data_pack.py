import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from tools.build_data_pack import CATEGORIES, build, normalized_json, without_comments


class DataPackTest(unittest.TestCase):
    def test_comments_removed_without_touching_strings(self):
        original = r'''/* heading */ {"url":"https://example.invalid/a//b","note":"/*literal*/","n":1} // trailer'''
        parsed = json.loads(without_comments(original))
        self.assertEqual(parsed["url"], "https://example.invalid/a//b")
        self.assertEqual(parsed["note"], "/*literal*/")
        self.assertEqual(parsed["n"], 1)

    def test_invalid_json_is_rejected(self):
        with self.assertRaises(ValueError):
            normalized_json(b'{"data":,}', "invalid.json")
        with self.assertRaises(ValueError):
            without_comments("/* never closed")

    def test_pack_hashes_match_real_contents(self):
        with tempfile.TemporaryDirectory() as work:
            base = Path(work)
            source = base / "source"
            for group in CATEGORIES:
                (source / group).mkdir(parents=True)
            sample = b'/* legacy header */\n{"type":"test"}\n'
            (source / "quest" / "1045.json").write_bytes(sample)
            output = base / "output.zip"
            result = build(source, output, "0123456")
            self.assertEqual(result["files"], 1)
            with ZipFile(output) as archive:
                manifest = json.loads(archive.read("manifest.json"))
                data = archive.read("definitions/quest/1045.json")
            self.assertEqual(json.loads(data), {"type": "test"})
            self.assertEqual(manifest["format"], "logbook-data-pack-v1")
            self.assertEqual(manifest["source_revision"], "0123456")
            self.assertEqual(manifest["files"][0]["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual(manifest["files"][0]["source_sha256"], hashlib.sha256(sample).hexdigest())


if __name__ == "__main__":
    unittest.main()
