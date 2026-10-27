"""Runtime-catalog audit errors must never become a current-season assertion."""
import copy
from datetime import date
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_seasonal_editions import audit, REGISTRY

ROOT = Path(__file__).resolve().parents[2]
CATALOG = 'src/main/resources/logbook/seasonal/catalog-v1.json'
CHECKS = 'docs/maintenance/seasonal-source-checks-20261027.json'


class SeasonalRuntimeAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.catalog = json.loads((ROOT / CATALOG).read_text(encoding='utf8'))
        self.checks = json.loads((ROOT / CHECKS).read_text(encoding='utf8'))
        observed = json.loads((ROOT / REGISTRY).read_text(encoding='utf8'))
        paths = {REGISTRY, CATALOG, CHECKS}
        paths.update(row['path'] for row in observed['entries'])
        paths.update(row['definition_path'] for row in self.checks['editions'])
        for name in paths:
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)

    def save(self, name, value):
        (self.root / name).write_text(json.dumps(value, ensure_ascii=False), encoding='utf8')

    def result(self, on=date(2026, 10, 27)):
        return audit(self.root, on)

    def assertRejected(self):
        result = self.result()
        self.assertTrue(result.get('runtime_errors'), result)
        self.assertEqual(0, result['certified_active_rules'])
        self.assertFalse(result.get('active_selection_evaluated', True))
        self.assertFalse(result['complete'])
        self.assertTrue(all(r.get('active') is None for r in result.get('runtime_editions', [])))
        return result

    def test_valid_catalog_exposes_evidence_without_evaluating_current_selection(self):
        result = self.result()
        self.assertTrue(result['active_selection_supported'])
        self.assertFalse(result.get('active_selection_evaluated', True))
        self.assertFalse(result.get('runtime_errors', ['audit unavailable']))
        self.assertEqual(13, len(result['entries']))
        self.assertEqual(5, len(result.get('runtime_editions', [])))
        for row in result['runtime_editions']:
            self.assertIsNone(row['active'])
            self.assertTrue(row['verified_conditions'])
            self.assertTrue(row['unresolved_conditions'])
            self.assertTrue(row['sources'])
            self.assertTrue(row['definition_sha256'])
            self.assertTrue(row['legacy_definition_sha256'])
        self.assertEqual(148, result['initial_comparison_count'])
        self.assertEqual(0, result['full_condition_certified_count'])

    def test_date_inside_a_window_only_reports_a_candidate(self):
        result = self.result(date(2025, 2, 1))
        rows = {r['edition_id']: r for r in result.get('runtime_editions', [])}
        self.assertEqual(5, len(rows))
        self.assertEqual('within_date_candidate_only', rows['2025-setsubun-840']['period_status'])
        self.assertIsNone(rows['2025-setsubun-840']['active'])
        self.assertEqual(0, result['certified_active_rules'])
        self.assertFalse(result['active_selection_evaluated'])

    def test_conservative_window_excludes_both_boundary_days(self):
        for on, expected in [(date(2025, 1, 28), 'before_conservative_window'),
                             (date(2025, 1, 29), 'within_date_candidate_only'),
                             (date(2025, 2, 28), 'after_conservative_window')]:
            with self.subTest(on=on):
                rows = {r['edition_id']: r for r in self.result(on).get('runtime_editions', [])}
                self.assertTrue(rows)
                self.assertEqual(expected, rows['2025-setsubun-840']['period_status'])
                self.assertIsNone(rows['2025-setsubun-840']['active'])

    def test_candidate_file_byte_change_requires_recomparison(self):
        candidate = self.root / self.checks['editions'][0]['definition_path']
        candidate.write_bytes(candidate.read_bytes() + b'\n')
        result = self.assertRejected()
        self.assertEqual(1, result['runtime_counts']['requires_recomparison'])

    def test_original_file_change_cannot_fall_back_to_the_reviewed_candidate(self):
        old = self.root / self.checks['editions'][0]['legacy_definition_path']
        old.write_bytes(old.read_bytes() + b'\n')
        result = self.assertRejected()
        self.assertEqual(1, result['counts']['requires_recomparison'])
        self.assertEqual(1, result['runtime_counts']['requires_recomparison'])

    def test_catalog_hashes_must_match_the_independent_review_record(self):
        for field in ('baseDefinitionSha256', 'definitionSha256'):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.catalog)
                changed['editions'][0][field] = '0' * 64
                self.save(CATALOG, changed)
                self.assertRejected()

    def test_every_identity_operand_and_year_must_match_the_review_record(self):
        for field, value in [('title', 'same season, different title'), ('detail', 'shortened details'),
                             ('category', 4), ('type', 3), ('questId', 841), ('editionYear', 2026)]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.catalog)
                changed['editions'][0][field] = value
                self.save(CATALOG, changed)
                self.assertRejected()

    def test_missing_manual_condition_evidence_is_rejected(self):
        for field, value in [('identityVerified', False), ('windowVerified', False),
                             ('modeledConditionsVerified', False), ('verifiedConditions', []),
                             ('unresolvedConditions', []), ('selectionBlockers', ['missing internal ID'])]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.catalog)
                changed['editions'][0][field] = value
                self.save(CATALOG, changed)
                self.assertRejected()

    def test_source_dates_urls_and_manual_review_flags_must_match(self):
        for field, value in [('publishedOn', '2026-10-27'), ('accessedOn', '2025-01-01'),
                             ('url', 'https://example.invalid/not-reviewed'), ('manuallyReviewed', False)]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.catalog)
                changed['editions'][0]['sources'][0][field] = value
                self.save(CATALOG, changed)
                self.assertRejected()

    def test_window_must_equal_the_reviewed_conservative_window(self):
        for field, value in [('validFrom', '2025-01-27T15:00:00Z'),
                             ('validUntil', '2026-10-28T15:00:00Z'),
                             ('validFrom', '2025-01-29T00:00:00')]:
            with self.subTest(field=field):
                changed = copy.deepcopy(self.catalog)
                changed['editions'][0][field] = value
                self.save(CATALOG, changed)
                self.assertRejected()

    def test_duplicate_edition_ids_do_not_hide_ambiguity(self):
        self.catalog['editions'].append(copy.deepcopy(self.catalog['editions'][0]))
        self.save(CATALOG, self.catalog)
        self.assertRejected()

    def test_unsafe_definition_path_is_not_read(self):
        sentinel = self.root / 'sentinel.json'
        sentinel.write_text('do not scan', encoding='utf8')
        self.catalog['editions'][0]['definitionResource'] = '../sentinel.json'
        self.save(CATALOG, self.catalog)
        self.assertRejected()
        self.assertEqual('do not scan', sentinel.read_text(encoding='utf8'))

    def test_corrupt_or_missing_catalog_is_a_reported_error(self):
        for value in ('{', '[]', 'null', '{"format":"wrong"}',
                      '{"format":"logbook-seasonal-rules-v1","format":"wrong"}'):
            with self.subTest(value=value):
                (self.root / CATALOG).write_text(value, encoding='utf8')
                self.assertRejected()
        # Only the new fixture is moved; repository/user files are never changed.
        (self.root / CATALOG).rename(self.root / 'preserved-invalid-catalog.json')
        self.assertRejected()

    def test_missing_or_modified_source_checks_cannot_certify_conditions(self):
        changed = copy.deepcopy(self.checks)
        changed['editions'][0]['verified_conditions'][0]['finding'] = 'different meaning'
        self.save(CHECKS, changed)
        self.assertRejected()
        (self.root / CHECKS).write_text('{', encoding='utf8')
        self.assertRejected()

    def test_full_completion_claims_in_evidence_do_not_change_the_audit(self):
        self.checks['complete'] = True
        self.checks['full_condition_certified_count'] = 148
        self.save(CHECKS, self.checks)
        result = self.assertRejected()
        self.assertFalse(result['complete'])
        self.assertEqual(0, result['full_condition_certified_count'])

    def test_date_window_needs_a_source_and_public_text_cannot_be_called_a_live_packet(self):
        for change in ('period_source', 'live_packet', 'noninteger_category'):
            with self.subTest(change=change):
                changed = copy.deepcopy(self.checks)
                fact = changed['editions'][0]
                if change == 'period_source':
                    fact['source_period']['source_ids'] = []
                elif change == 'live_packet':
                    fact['expected_observation']['live_api_observation_captured'] = True
                else:
                    fact['expected_observation']['category'] = 2.0
                self.save(CHECKS, changed)
                self.assertRejected()

    def test_verified_condition_must_resolve_to_the_reviewed_definition(self):
        self.checks['editions'][0]['verified_conditions'][0]['definition_pointer'] = '/filter/missing'
        self.catalog['editions'][0]['verifiedConditions'][0]['definition_pointer'] = '/filter/missing'
        self.save(CHECKS, self.checks)
        self.save(CATALOG, self.catalog)
        self.assertRejected()

    def test_cli_returns_failure_for_invalid_runtime_catalog(self):
        (self.root / CATALOG).write_text('{', encoding='utf8')
        run = subprocess.run([sys.executable, str(ROOT / 'scripts/audit_seasonal_editions.py'),
                              '--root', str(self.root), '--as-of', '2026-10-27'],
                             capture_output=True, text=True, check=False)
        self.assertEqual(1, run.returncode)
        result = json.loads(run.stdout)
        self.assertTrue(result['runtime_errors'])
        self.assertFalse(result['complete'])


if __name__ == '__main__':
    unittest.main()
