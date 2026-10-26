import sys
from pathlib import Path
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_game_json import audit, parse_jsonc


class ResumeFailureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.paths = []
    def write(self, name, text):
        relative = 'src/main/resources/logbook/quest/' + name
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')
        self.paths.append(relative)
        return path
    def test_unchanged_error_does_not_starve_later_files(self):
        self.write('1.json', '{broken')
        self.write('2.json', '{}')
        self.write('3.json', '[]')
        first = audit(self.root, self.paths, max_files=1)
        self.assertEqual(2, first['counts']['pending'])
        second = audit(self.root, self.paths, first, max_files=1)
        self.assertEqual(1, second['counts']['reused_errors'])
        self.assertEqual(1, second['counts']['validated'])
        self.assertEqual(1, second['counts']['pending'])
        third = audit(self.root, self.paths, second, max_files=1)
        self.assertEqual(0, third['counts']['pending'])
        self.assertEqual(1, third['counts']['errors'])
        self.assertFalse(third['complete'])
        self.assertEqual('error', third['entries'][0]['status'])
    def test_repaired_error_is_revalidated_not_reused(self):
        broken = self.write('1.json', '{broken')
        first = audit(self.root, self.paths, max_files=1)
        broken.write_text('{}', encoding='utf-8')
        second = audit(self.root, self.paths, first, max_files=1)
        self.assertTrue(second['complete'])
        self.assertEqual(1, second['counts']['validated'])
        self.assertEqual(0, second['counts']['reused_errors'])
    def test_missing_file_error_does_not_consume_parse_budget(self):
        self.paths.append('src/main/resources/logbook/quest/1.json')
        self.write('2.json', '{}')
        result = audit(self.root, self.paths, max_files=1)
        self.assertEqual(1, result['counts']['errors'])
        self.assertEqual(1, result['counts']['validated'])
        self.assertEqual(0, result['counts']['pending'])
    def test_empty_selection_is_not_a_complete_audit(self):
        result = audit(self.root, [])
        self.assertFalse(result['complete'])
        self.assertEqual(1, result['counts']['errors'])
    def test_large_valid_json_number_does_not_become_infinity(self):
        self.assertTrue(parse_jsonc('[1e999]')[0].is_finite())


if __name__ == '__main__':
    unittest.main()
