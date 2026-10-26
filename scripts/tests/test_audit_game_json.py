import sys
from pathlib import Path
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_game_json import audit, parse_jsonc


class JsonAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.paths = []
    def file(self, name, text):
        path = 'src/main/resources/logbook/quest/' + name
        full = self.root / path
        full.parent.mkdir(parents=True, exist_ok=True)
        full.write_text(text, encoding='utf-8')
        self.paths.append(path)
        return full
    def test_comments_are_supported_but_urls_are_untouched(self):
        self.assertEqual({'url': 'https://host/*x*/'}, parse_jsonc('// header\n{"url":"https://host/*x*/" /* comment */}'))
    def test_escaped_quotes_do_not_end_strings(self):
        self.assertEqual({'x': '"//not a comment'}, parse_jsonc('{"x":"\\\"//not a comment"}'))
    def test_unterminated_block_and_trailing_document_are_rejected(self):
        for text in ('{} /* unfinished', '{} {}'):
            with self.assertRaises(ValueError): parse_jsonc(text)
    def test_duplicate_keys_and_nonfinite_constants_are_rejected(self):
        for text in ('{"x":1,"x":2}', '{"x":NaN}', '[Infinity]'):
            with self.assertRaises(ValueError): parse_jsonc(text)
    def test_budget_resume_and_changed_file_revalidation(self):
        first = self.file('1.json', '{}')
        self.file('2.json', '[]')
        one = audit(self.root, self.paths, max_files=1)
        self.assertFalse(one['complete'])
        self.assertEqual(1, one['counts']['pending'])
        two = audit(self.root, self.paths, one, max_files=1)
        self.assertTrue(two['complete'])
        self.assertEqual(1, two['counts']['reused'])
        first.write_text('{broken', encoding='utf-8')
        three = audit(self.root, self.paths, two)
        self.assertFalse(three['complete'])
        self.assertEqual(1, three['counts']['errors'])
    def test_zero_budget_is_pending_not_success(self):
        self.file('1.json', '{}')
        result = audit(self.root, self.paths, max_files=0)
        self.assertFalse(result['complete'])
        self.assertEqual(1, result['counts']['pending'])
    def test_private_runtime_and_other_resources_are_not_scanned(self):
        self.file('1.json', '{}')
        result = audit(self.root, self.paths + ['config/private.json', 'src/main/resources/logbook/bouyomi/settings.json'])
        self.assertEqual(1, len(result['entries']))
    def test_symlink_and_missing_file_are_errors(self):
        actual = self.file('1.json', '{}')
        alias = actual.with_name('2.json')
        alias.symlink_to(actual)
        paths = self.paths + ['src/main/resources/logbook/quest/2.json', 'src/main/resources/logbook/quest/3.json']
        result = audit(self.root, paths)
        self.assertEqual(2, result['counts']['errors'])
    def test_readonly_and_deterministic_output(self):
        actual = self.file('1.json', '{}')
        before = actual.read_bytes()
        self.assertEqual(audit(self.root, self.paths), audit(self.root, list(reversed(self.paths))))
        self.assertEqual(before, actual.read_bytes())
    def test_previous_validator_and_negative_budget_are_rejected(self):
        with self.assertRaises(ValueError): audit(self.root, [], {'validator': 'older'})
        with self.assertRaises(ValueError): audit(self.root, [], max_files=-1)


if __name__ == '__main__':
    unittest.main()
