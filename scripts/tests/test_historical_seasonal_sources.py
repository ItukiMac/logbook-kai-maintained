"""Bind manually reviewed historical candidates to preserved originals and their sources."""
from datetime import date, datetime, timedelta
import hashlib
import json
from pathlib import Path
import unittest
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
RECORD = 'docs/maintenance/seasonal-source-checks-20261027.json'


class HistoricalSeasonalSourcesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.record = json.loads((ROOT / RECORD).read_text(encoding='utf8'))

    def test_original_bytes_are_retained_and_candidates_are_distinct(self):
        entries = self.record['editions']
        self.assertEqual(5, len(entries))
        self.assertEqual(5, len({e['edition_id'] for e in entries}))
        for row in entries:
            with self.subTest(edition=row['edition_id']):
                self.assertTrue(row['legacy_definition_preserved'])
                old = ROOT / row['legacy_definition_path']
                candidate = ROOT / row['definition_path']
                self.assertNotEqual(old, candidate)
                self.assertTrue(row['definition_path'].startswith('src/main/resources/logbook/seasonal/editions/'))
                self.assertEqual(row['legacy_definition_sha256'], hashlib.sha256(old.read_bytes()).hexdigest())
                self.assertEqual(row['definition_sha256'], hashlib.sha256(candidate.read_bytes()).hexdigest())
                self.assertNotEqual(old.read_bytes(), candidate.read_bytes())
                json.loads(candidate.read_text(encoding='utf8'))

    def test_every_verified_condition_has_both_named_sources(self):
        sources = {s['source_id']: s for s in self.record['sources']}
        self.assertEqual(len(sources), len(self.record['sources']))
        for row in self.record['editions']:
            self.assertTrue(row['verified_conditions'])
            self.assertTrue(row['unresolved_conditions'])
            for condition in row['verified_conditions']:
                with self.subTest(edition=row['edition_id'], condition=condition['condition_id']):
                    hosts = {urlsplit(sources[s]['url']).hostname for s in condition['source_ids']}
                    self.assertEqual({'wikiwiki.jp', 'zekamashi.net'}, hosts)
                    self.assertTrue(condition['finding'])
            identity = row['internal_id_evidence']
            self.assertTrue(identity['verified'])
            self.assertTrue(identity['not_inferred_from_title'])
            self.assertIn(str(row['quest_id']), identity['locator'])

    def test_date_only_boundaries_admit_only_interior_days(self):
        for row in self.record['editions']:
            with self.subTest(edition=row['edition_id']):
                period = row['source_period']
                start = date.fromisoformat(period['start_date'])
                end = date.fromisoformat(period['end_date'])
                gate = row['conservative_selection_window']
                begin = datetime.fromisoformat(gate['from_inclusive'])
                expiry = datetime.fromisoformat(gate['until_exclusive'])
                self.assertEqual(start + timedelta(days=1), begin.date())
                self.assertEqual(end, expiry.date())
                self.assertEqual((0, 0, 0), (begin.hour, begin.minute, begin.second))
                self.assertEqual((0, 0, 0), (expiry.hour, expiry.minute, expiry.second))
                self.assertEqual(timedelta(hours=9), begin.utcoffset())
                self.assertEqual(timedelta(hours=9), expiry.utcoffset())
                self.assertLess(begin, expiry)
                self.assertIsNone(period['actual_start_instant'])
                self.assertIsNone(period['actual_end_instant'])

    def test_source_dates_and_api_transcription_are_not_live_observations(self):
        for source in self.record['sources']:
            accessed = date.fromisoformat(source['accessed_on'])
            for field in ('published_on', 'modified_on'):
                if source[field] is not None:
                    self.assertLessEqual(date.fromisoformat(source[field]), accessed)
                else:
                    self.assertTrue(source['date_note'])
        for row in self.record['editions']:
            identity = row['expected_observation']
            self.assertTrue(identity['exactTitle'])
            self.assertTrue(identity['exactDetail'])
            self.assertEqual(2, identity['category'])
            self.assertEqual(2, identity['type'])
            self.assertTrue(identity['runtime_api_observation_required'])
            self.assertFalse(identity['live_api_observation_captured'])
            self.assertFalse(row['whole_rule_certified'])
        self.assertFalse(self.record['live_game_verified'])
        self.assertFalse(self.record['official_source_access']['latest_official_timeline_certified'])

    def test_unresolved_2026_ids_are_never_inferred_from_the_legacy_id(self):
        for comparison in self.record['unresolved_newer_editions']:
            self.assertIsNone(comparison['internal_id'])
            self.assertFalse(comparison['selectable'])
            self.assertTrue(comparison['unresolved'])

    def test_initial_148_comparisons_are_not_full_condition_certification(self):
        self.assertEqual(148, self.record['initial_comparison_count'])
        self.assertEqual(0, self.record['full_condition_certified_count'])
        self.assertFalse(self.record['complete'])
        old = self.record['previous_observation_registry']
        self.assertEqual(old['sha256'], hashlib.sha256((ROOT / old['path']).read_bytes()).hexdigest())


if __name__ == '__main__':
    unittest.main()
