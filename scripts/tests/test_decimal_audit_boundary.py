"""Regression for PR35's delayed review: conversion errors cannot abort a report."""
import json
import subprocess
import sys
import tempfile
import unittest
from decimal import InvalidOperation, localcontext
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_game_json import audit, parse_jsonc
from maintenance_plan import plan

HUGE = '[1e9999999999999999999]'

class DecimalAuditBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.paths = []
    def write(self, name, value):
        relative = 'src/main/resources/logbook/quest/' + name
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding='utf-8')
        self.paths.append(relative)
        return path
    def test_extreme_positive_and_negative_exponents_are_value_errors(self):
        for value in (HUGE, '[1e-9999999999999999999]'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_jsonc(value)
    def test_disabled_decimal_trap_still_cannot_accept_nonfinite_result(self):
        with localcontext() as context:
            context.traps[InvalidOperation] = False
            with self.assertRaises(ValueError): parse_jsonc(HUGE)
    def test_report_continues_after_conversion_failure(self):
        self.write('1.json', HUGE)
        self.write('2.json', '{}')
        result = audit(self.root, self.paths)
        self.assertEqual(1, result['counts']['errors'])
        self.assertEqual(1, result['counts']['validated'])
        self.assertFalse(result['complete'])
        self.assertEqual('ValueError', result['entries'][0]['error'])
        json.dumps(result)
    def test_budgeted_resume_and_repair_preserve_failure_evidence(self):
        broken = self.write('1.json', HUGE)
        self.write('2.json', '{}')
        first = audit(self.root, self.paths, max_files=1)
        second = audit(self.root, self.paths, first, max_files=1)
        self.assertEqual(1, second['counts']['reused_errors'])
        self.assertEqual(0, second['counts']['pending'])
        broken.write_text('[1e999]', encoding='utf-8')
        third = audit(self.root, self.paths, second, max_files=1)
        self.assertTrue(third['complete'])
    def test_cli_emits_resumable_json_instead_of_traceback(self):
        self.write('1.json', HUGE)
        self.write('2.json', '{}')
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        subprocess.run(['git', '-C', str(self.root), 'add', '--', *self.paths], check=True)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Test',
                        '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture'], check=True)
        script = Path(__file__).resolve().parents[1] / 'audit_game_json.py'
        process = subprocess.run([sys.executable, str(script), '--root', str(self.root)],
                                 text=True, capture_output=True, timeout=10)
        self.assertEqual(1, process.returncode)
        self.assertNotIn('Traceback', process.stderr)
        result = json.loads(process.stdout)
        self.assertEqual(1, result['counts']['validated'])
        self.assertEqual(1, result['counts']['errors'])
    def test_shared_candidate_parser_keeps_later_candidates(self):
        candidates = [{'path': 'src/main/resources/logbook/quest/' + str(i) + '.json',
                       'content': content, 'expected_old_sha256': None, 'evidence': [],
                       'upper_bound': {'input_tokens': 0, 'output_tokens': 0, 'micro_usd': 0}}
                      for i, content in enumerate((HUGE, '{}'))]
        result = plan(self.root, candidates, [],
                      {'candidates': 2, 'input_tokens': 0, 'output_tokens': 0, 'micro_usd': 0})
        self.assertEqual('error', result['entries'][0]['status'])
        self.assertEqual('requires_source_review', result['entries'][1]['status'])
        self.assertFalse(result['applies_changes'])

if __name__ == '__main__': unittest.main()
