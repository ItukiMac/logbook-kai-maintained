"""Synthetic ledger assertions only; tests never approve a deployment or use live data."""
from contextlib import redirect_stdout, redirect_stderr
from copy import deepcopy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_nonmint_gates import BASELINE, main, scope_digest, unique, validate

ROOT = Path(__file__).resolve().parents[2]
LEDGER = json.loads((ROOT / 'docs/maintenance/non-mint-completion-gates.json').read_text(encoding='utf8'))


class GateLedgerTest(unittest.TestCase):
    def setUp(self):
        self.doc = deepcopy(LEDGER)
        self.doc['as_of'] = '2026-10-27'
        self.doc['basis']['rules_whole_verified'] = 0
        # Validator scenarios must not freeze the live ledger's future progress.
        for task in self.doc['tasks']:
            task.update(status='open', verified_complete=False,
                        remaining='Synthetic unfinished scope', completion=None)
            task['stages'] = dict(implementation='pending', verification='pending',
                                 integration='pending', native_validation=(
                                     'pending' if task['scope'] == 'native' else 'not_applicable'))
        self.completed_fixture(self.task('R11-12'))

    def reject(self):
        with self.assertRaises(ValueError):
            validate(self.doc, ROOT)

    def task(self, name):
        return next(t for t in self.doc['tasks'] if t['id'] == name)

    def completed_fixture(self, task):
        # The checker validates recorded assertions, not remote facts. These are
        # deliberately synthetic receipts for validator tests, never ledger edits.
        template = {'source_revision': 'a' * 40, 'reviewed_revision': 'a' * 40,
                    'merge_revision': 'b' * 40, 'pr_number': 1, 'workflow_runs': [1],
                    'references': ['https://example.invalid/synthetic-evidence'],
                    'checked_at': '2026-10-27T00:00:00+00:00'}
        task.update(status='complete', verified_complete=True, remaining='',
                    stages=dict(implementation='done', verification='passed',
                                integration='merged', native_validation='not_applicable'))
        template.update(scope_sha256=scope_digest(task), checked_by='synthetic fixture only')
        task['completion'] = template

    def test_live_ledger_is_valid_without_automatic_publication(self):
        result = validate(deepcopy(LEDGER), ROOT)
        self.assertEqual(len(LEDGER['tasks']), result['tasks'])
        self.assertFalse(result['auto_publish_authorized'])

    def test_synthetic_ui_completion_does_not_close_native_work(self):
        result = validate(self.doc, ROOT)
        self.assertTrue(self.task('R11-12')['verified_complete'])
        self.assertGreaterEqual(result['mint_independent_complete'], 1)
        self.assertGreater(result['mint_independent_open'], 0)
        self.assertEqual(sum(t['scope'] == 'native' for t in self.doc['tasks']), result['native_mint_open'])
        self.assertFalse(result['release_ready'])
        self.assertFalse(result['auto_publish_authorized'])

    def test_verified_completion_can_be_recorded(self):
        old = validate(self.doc, ROOT)['mint_independent_open']
        self.completed_fixture(self.task('R11-02'))
        self.assertEqual(old - 1, validate(self.doc, ROOT)['mint_independent_open'])

    def test_all_nonmint_done_does_not_complete_native_or_approval(self):
        self.doc['basis']['rules_whole_verified'] = self.doc['basis']['rules_total']
        for task in self.doc['tasks']:
            if task['mint_independent']:
                self.completed_fixture(task)
        result = validate(self.doc, ROOT)
        self.assertTrue(result['nonmint_complete'])
        self.assertFalse(result['release_ready'])
        self.assertEqual(sum(t['scope'] == 'native' for t in self.doc['tasks']), result['native_mint_open'])
        self.assertEqual(sum(t['scope'] == 'approval' for t in self.doc['tasks']), result['manual_approval_open'])

    def test_unverified_complete_flag_rejected(self):
        self.task('R11-01')['verified_complete'] = True
        self.reject()

    def test_complete_without_receipt_rejected(self):
        self.task('R11-12')['completion'] = None
        self.reject()

    def test_open_pr_is_not_source_integration(self):
        self.task('R11-12')['stages']['integration'] = 'pr_open'
        self.reject()

    def test_missing_test_or_implementation_rejected(self):
        for stage, value in [('verification', 'pending'), ('implementation', 'partial')]:
            self.setUp()
            self.task('R11-12')['stages'][stage] = value
            self.reject()

    def test_changed_acceptance_invalidates_receipt(self):
        self.task('R11-12')['acceptance'] += ' and native IME certification'
        self.reject()

    def test_old_review_cannot_cover_a_new_source_revision(self):
        self.task('R11-12')['completion']['source_revision'] = 'b' * 40
        self.reject()

    def test_invalid_receipt_types_and_dates_rejected(self):
        for field, value in [('workflow_runs', []), ('workflow_runs', [True]),
                             ('pr_number', True), ('source_revision', 'short'),
                             ('references', ['file:///private']),
                             ('references', [123]), ('checked_by', ''),
                             ('checked_at', '2099-01-01T00:00:00Z'),
                             ('checked_at', '2026-10-27T00:00:00')]:
            with self.subTest(field=field, value=value):
                self.setUp()
                self.task('R11-12')['completion'][field] = value
                self.reject()

    def test_original_task_cannot_be_dropped(self):
        self.doc['tasks'].pop()
        self.reject()

    def test_additional_tasks_are_supported_without_freezing_count(self):
        extra = deepcopy(self.task('R11-15'))
        extra.update(id='R12-01', key='new-work', title='Additional retained work')
        self.doc['tasks'].append(extra)
        self.assertEqual(len(LEDGER['tasks']) + 1, validate(self.doc, ROOT)['tasks'])

    def test_original_identity_and_scope_cannot_be_relabelled(self):
        for field, value in [('key', 'pretend-other-task'), ('scope', 'independent'),
                             ('mint_independent', True)]:
            self.setUp()
            self.task('R11-16')[field] = value
            self.reject()

    def test_duplicate_task_identity_or_key_is_rejected(self):
        for field in ('id', 'key'):
            self.setUp()
            self.doc['tasks'][0][field] = self.doc['tasks'][1][field]
            self.reject()

    def test_boolean_or_impossible_rule_counts_rejected(self):
        for value in (True, -1, self.doc['basis']['rules_total'] + 1):
            self.doc['basis']['rules_whole_verified'] = value
            self.reject()

    def test_whole_rule_completion_requires_whole_rule_coverage(self):
        self.completed_fixture(self.task('R11-11'))
        self.doc['basis']['rules_whole_verified'] = 0
        self.reject()

    def test_native_task_cannot_be_completed_by_ci_only(self):
        task = self.task('R11-16')
        self.completed_fixture(task)
        task['stages']['native_validation'] = 'pending'
        self.reject()

    def test_approval_is_not_implicitly_given(self):
        task = self.task('R11-19')
        self.completed_fixture(task)
        self.reject()

    def test_nonstring_status_stages_or_missing_remaining_rejected(self):
        for field, value in [('status', {}), ('scope', []), ('stages', []), ('remaining', '')]:
            self.setUp()
            self.task('R11-01')[field] = value
            self.reject()

    def test_unsafe_or_missing_evidence_paths_rejected(self):
        for value in ('/tmp/private', 'docs/maintenance/../private',
                      'docs/maintenance//r9-progress.md', 'docs/maintenance/not-real.md'):
            self.setUp()
            self.task('R11-12')['evidence'] = value
            self.reject()

    def test_symlinked_parent_directory_is_not_trusted_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'docs').symlink_to(ROOT / 'docs', target_is_directory=True)
            with self.assertRaises(ValueError):
                validate(self.doc, root)

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaises(ValueError):
            json.loads('{"tasks": [], "tasks": []}', object_pairs_hook=unique)

    def test_cli_validation_and_completion_have_distinct_exit_codes(self):
        original = (ROOT / 'docs/maintenance/non-mint-completion-gates.json').read_bytes()
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(['--root', str(ROOT)]))
            expected = int(not validate(deepcopy(LEDGER), ROOT)['nonmint_complete'])
            self.assertEqual(expected, main(['--root', str(ROOT), '--require-nonmint-complete']))
        self.assertEqual(original, (ROOT / 'docs/maintenance/non-mint-completion-gates.json').read_bytes())

    def test_cli_rejects_unmanaged_ledger_path(self):
        with redirect_stderr(io.StringIO()):
            self.assertEqual(2, main(['--root', str(ROOT), '--ledger', '/tmp/not-allowed.json']))


if __name__ == '__main__':
    unittest.main()
