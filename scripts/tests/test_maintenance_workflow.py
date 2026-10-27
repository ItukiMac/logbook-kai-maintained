"""Offline end-to-end candidate workflow. All fixtures, no real approvals/data."""
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from maintenance_workflow import run_gate
from maintenance_plan import plan


class OfflineWorkflowTest(unittest.TestCase):
    def setUp(self):
        t = tempfile.TemporaryDirectory(); self.addCleanup(t.cleanup)
        self.base = Path(t.name)
        self.source = self.base / 'source'; self.source.mkdir()
        self.stage = self.base / 'staging'
        self.paths = []
        for cat in ('quest', 'eventquest', 'kousyou', 'mission', 'map', 'supplemental'):
            p = self.source / f'src/main/resources/logbook/{cat}/1.json'
            p.parent.mkdir(parents=True, exist_ok=True); p.write_text('{}')
            self.paths.append(p)
        subprocess.run(['git', 'init', '-q', str(self.source)], check=True)
        subprocess.run(['git', '-C', str(self.source), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(self.source), '-c', 'user.email=fixture@example.invalid',
                        '-c', 'user.name=Fixture', 'commit', '-qm', 'baseline'], check=True)
        self.now = datetime(2026, 10, 27, tzinfo=timezone.utc)
        self.candidates = [{
            'path': self.paths[0].relative_to(self.source).as_posix(),
            'expected_old_sha256': hashlib.sha256(b'{}').hexdigest(),
            'content': '{"fixture": true}',
            'upper_bound': {'input_tokens': 0, 'output_tokens': 0, 'micro_usd': 0},
            'evidence': [{'url': 'https://wikiwiki.jp/kancolle/test',
                          'checked_at': self.now.isoformat(), 'summary': 'Synthetic fixture, NOT user approval',
                          'evidence_sha256': 'a' * 64}]
        }]
        self.budget = {'candidates': 1, 'input_tokens': 0, 'output_tokens': 0, 'micro_usd': 0}
        identity = plan(self.source, self.candidates, [], self.budget, now=self.now)['entries'][0]['identity']
        self.approval = [{'identity': identity, 'decision': 'approve', 'reviewer': 'CI fixture only'}]

    def test_unapproved_plan_never_writes_source_or_stage(self):
        original = [p.read_bytes() for p in self.paths]
        result = run_gate(self.source, self.candidates, [], self.budget, now=self.now)
        self.assertEqual('requires_approval', result['plan']['entries'][0]['status'])
        self.assertEqual('read_only', result['mode']); self.assertFalse(result['stage_complete'])
        self.assertFalse(result['publication_allowed']); self.assertEqual(0, result['paid_provider_calls'])
        self.assertFalse(self.stage.exists()); self.assertEqual(original, [p.read_bytes() for p in self.paths])

    def test_approval_is_verified_again_and_stage_is_isolated(self):
        original = [p.read_bytes() for p in self.paths]
        result = run_gate(self.source, self.candidates, self.approval, self.budget,
                          output=self.stage, now=self.now)
        self.assertTrue(result['stage_complete']); self.assertFalse(result['publication_allowed'])
        self.assertTrue((self.stage / 'stage.json').exists())
        self.assertEqual(original, [p.read_bytes() for p in self.paths])
        self.assertEqual(b'{}', (self.stage / 'original/quest/1.json').read_bytes())
        self.assertEqual(b'{"fixture": true}', (self.stage / 'candidate/quest/1.json').read_bytes())
        with self.assertRaises(FileExistsError):
            run_gate(self.source, self.candidates, self.approval, self.budget, output=self.stage, now=self.now)

    def test_changed_source_and_approval_do_not_create_output(self):
        self.paths[0].write_text('[]')
        with self.assertRaisesRegex(ValueError, 'approval|Source changed|candidate'):
            run_gate(self.source, self.candidates, self.approval, self.budget, output=self.stage, now=self.now)
        self.assertFalse(self.stage.exists())

    def test_forged_approved_checkpoint_cannot_skip_syntax_validation(self):
        candidate = self.candidates[0].copy(); candidate['content'] = '{broken'
        p = plan(self.source, [candidate], [], self.budget, now=self.now)
        forged = dict(p['entries'][0], status='approved_candidate')
        prior = {'validator': p['validator'], 'entries': [forged]}
        self.assertEqual('error', plan(self.source, [candidate], [], self.budget,
                                       previous=prior, now=self.now)['entries'][0]['status'])
        candidate['content'] = '{"fixture": true}'
        p = plan(self.source, [candidate], [], self.budget, now=self.now)
        forged = dict(p['entries'][0], status='approved_candidate')
        prior = {'validator': p['validator'], 'entries': [forged]}
        result = plan(self.source, [candidate], self.approval, self.budget, previous=prior, now=self.now)
        self.assertFalse(result['entries'][0].get('reused', False))
        self.assertEqual('approved_candidate', result['entries'][0]['status'])
        self.assertEqual(1, result['used']['candidates'])

    def test_budget_and_stale_evidence_rejected_before_creating_output(self):
        expensive = [dict(self.candidates[0], upper_bound=dict(input_tokens=1, output_tokens=0, micro_usd=0))]
        with self.assertRaisesRegex(ValueError, 'candidate failed'):
            run_gate(self.source, expensive, self.approval, self.budget, output=self.stage, now=self.now)
        self.assertFalse(self.stage.exists())
        stale = json.loads(json.dumps(self.candidates))
        stale[0]['evidence'][0]['checked_at'] = '2020-01-01T00:00:00+00:00'
        with self.assertRaisesRegex(ValueError, 'candidate failed'):
            run_gate(self.source, stale, self.approval, self.budget, output=self.stage, now=self.now)
        self.assertFalse(self.stage.exists())

    def test_bad_managed_definition_blocks_even_readonly_plan(self):
        self.paths[3].write_text('{invalid')
        with self.assertRaisesRegex(ValueError, 'Managed JSON validation failed'):
            run_gate(self.source, self.candidates, [], self.budget, now=self.now)

    def test_parent_traversal_stage_is_rejected(self):
        elsewhere = self.base / 'empty'; elsewhere.mkdir()
        bad = elsewhere / '..' / 'source' / 'inside'
        with self.assertRaises(ValueError):
            run_gate(self.source, self.candidates, self.approval, self.budget, output=bad, now=self.now)
        self.assertFalse((self.source / 'inside').exists())


if __name__ == '__main__': unittest.main()
