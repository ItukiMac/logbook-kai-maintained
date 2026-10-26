"""Malformed evidence must stay inside its candidate, including CLI JSON output."""
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from maintenance_plan import plan, source_allowed

class EvidenceUrlBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.budget = dict(candidates=2, input_tokens=0, output_tokens=0, micro_usd=0)
    def candidates(self, bad_url):
        now = datetime.now(timezone.utc).isoformat()
        return [{'path': 'src/main/resources/logbook/quest/' + str(i) + '.json',
                 'content': '{}', 'expected_old_sha256': None,
                 'upper_bound': dict(input_tokens=0, output_tokens=0, micro_usd=0),
                 'evidence': [{'url': url, 'checked_at': now, 'summary': 'local test fixture',
                               'evidence_sha256': 'a' * 64}]}
                for i, url in enumerate((bad_url, 'https://wikiwiki.jp/kancolle/test'))]
    def test_all_nonstring_json_url_types_are_invalid_evidence(self):
        for value in (None, False, True, 123, 1.5, [], {}, ['https://wikiwiki.jp/kancolle/test']):
            with self.subTest(value=value):
                self.assertFalse(source_allowed(value))
                result = plan(self.root, self.candidates(value), [], self.budget)
                self.assertEqual('requires_source_review', result['entries'][0]['status'])
                self.assertEqual('requires_approval', result['entries'][1]['status'])
                self.assertFalse(result['calls_paid_apis'])
    def test_repaired_url_invalidates_evidence_identity_on_resume(self):
        first = plan(self.root, self.candidates(123), [], self.budget)
        repaired = self.candidates('https://zekamashi.net/kancolle/test/')
        second = plan(self.root, repaired, [], self.budget, previous=first)
        self.assertEqual('requires_approval', second['entries'][0]['status'])
        self.assertNotEqual(first['entries'][0]['identity'], second['entries'][0]['identity'])
    def test_cli_returns_report_with_malformed_url_and_later_candidate(self):
        file = self.root / 'candidates.json'
        file.write_text(json.dumps(self.candidates(123)), encoding='utf-8')
        script = Path(__file__).resolve().parents[1] / 'maintenance_plan.py'
        process = subprocess.run([sys.executable, str(script), '--root', str(self.root),
                                  '--candidates', str(file)], text=True, capture_output=True, timeout=10)
        self.assertEqual(2, process.returncode)
        self.assertNotIn('Traceback', process.stderr)
        result = json.loads(process.stdout)
        self.assertEqual(1, result['counts']['requires_source_review'])
        self.assertEqual(1, result['counts']['requires_approval'])
        self.assertFalse(result['applies_changes'])

if __name__ == '__main__': unittest.main()
