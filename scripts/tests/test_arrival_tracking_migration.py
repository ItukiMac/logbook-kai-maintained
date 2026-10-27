"""B+D storage and B+E retained-stage integration, on synthetic copies only."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import runtime_managed_import as migration
import runtime_snapshot as snapshots
import test_runtime_managed_import as fixtures


class ArrivalTrackingMigrationTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.RuntimeManagedImportTest()
        self.fixture.setUp(); self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.source
        self.published, _ = self.fixture.add_arrival()
        self.record = json.loads(self.published.read_text())
        self.tracking = 'sortie-arrivals-v1/tracking-v1'
        self.fleet = 'sortie-arrivals-v1/fleet-evidence-v1/2025-05/'
        self.checkpoint = dict(format='logbook-port-arrival-tracking-v1', sequence=1,
                previousDigest='', kind='BOUNDARY', observedAt=self.record['arrivedAt'],
                payload=None, reason='SYNTHETIC_TEST_BOUNDARY')
        self.write_checkpoint()
        self.fixture.write(self.tracking + '/.lock', b'')
        self.evidence = dict(format='logbook-port-arrival-fleet-v1', arrival=self.record,
                members=[dict(slot=1, instanceId=11, masterId=101, retreated=False),
                         dict(slot=2, instanceId=12, masterId=102, retreated=False)])
        self.fleet_path = self.fixture.write(self.fleet + self.record['sortieId'] + '.json',
                                             json.dumps(self.evidence).encode())
        self.pending = self.fixture.write(self.fleet + '.' + self.record['sortieId']
                + '-33333333-3333-4333-8333-333333333333.pending', self.fleet_path.read_bytes())

    def write_checkpoint(self):
        unsigned = {k:v for k,v in self.checkpoint.items() if k != 'digest'}
        raw = json.dumps(unsigned, ensure_ascii=False, separators=(',', ':')).encode()
        self.checkpoint['digest'] = hashlib.sha256(raw).hexdigest()
        self.path = self.fixture.write(self.tracking + '/2025-05-10/00000001.json',
                                       json.dumps(self.checkpoint).encode())

    def validate(self, profile='arrival-tracking-v1'):
        return migration.validate_runtime(self.root, profile, limits=self.fixture.limits)

    def test_explicit_profile_reads_chain_and_retained_pending_fleet(self):
        before = snapshots.inventory(self.root, self.fixture.limits)
        result = self.validate()
        self.assertEqual(1, result['tracking_checkpoints'])
        self.assertEqual(1, result['fleet_evidence_records'])
        self.assertEqual(1, result['retained_pending_fleet_evidence'])
        self.assertFalse(result['capture_continuity_proven'])
        self.assertFalse(result['launch_safe'])
        self.assertEqual(before, snapshots.inventory(self.root, self.fixture.limits))

    def test_legacy_profile_refuses_auxiliary_formats_without_dropping_them(self):
        for profile in ('legacy-config-v1', 'arrival-journal-v1'):
            with self.assertRaises(ValueError): self.validate(profile)
        self.assertTrue(self.path.exists()); self.assertTrue(self.pending.exists())

    def test_partial_checkpoint_blocks_import_but_retains_all_input(self):
        self.path.write_bytes(b'{interrupted')
        self.fixture.capture()
        with self.assertRaises(ValueError): self.fixture.perform_import('arrival-tracking-v1')
        self.assertEqual(b'{interrupted', self.path.read_bytes())
        self.assertFalse(self.fixture.out.exists())

    def test_hash_mismatch_is_not_repaired(self):
        self.checkpoint['reason'] = 'modified without checksum'
        self.path.write_text(json.dumps(self.checkpoint))
        with self.assertRaisesRegex(ValueError, 'checksum'): self.validate()

    def test_missing_sequence_and_unknown_layout_are_rejected(self):
        self.fixture.write(self.tracking + '/2025-05-10/00000003.json', self.path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'sequence'): self.validate()

    def test_unknown_auxiliary_version_is_not_assumed_compatible(self):
        self.fixture.write('sortie-arrivals-v1/tracking-v2/record.json', b'{}')
        with self.assertRaises(ValueError): self.validate()

    def test_fleet_must_match_credited_arrival(self):
        self.evidence['arrival']['deckId'] = 2
        self.fleet_path.write_text(json.dumps(self.evidence))
        self.pending.write_text(json.dumps(self.evidence))
        with self.assertRaisesRegex(ValueError, 'credited arrival'): self.validate()

    def test_conflicting_pending_evidence_is_not_silently_ignored(self):
        self.evidence['members'][0]['instanceId'] = 21
        self.pending.write_text(json.dumps(self.evidence))
        with self.assertRaisesRegex(ValueError, 'Conflicting fleet evidence'): self.validate()

    def test_fleet_position_and_retreat_types_are_strict(self):
        for field, value in [('slot', True), ('retreated', 1), ('masterId', 999)]:
            bad = deepcopy(self.evidence); bad['members'][0][field] = value
            self.fleet_path.write_text(json.dumps(bad))
            with self.assertRaises(ValueError): self.validate()

    def test_unsupported_or_missing_route_is_not_validated_as_a_boundary(self):
        self.checkpoint['kind'] = 'START'; self.write_checkpoint()
        with self.assertRaisesRegex(ValueError, 'Missing tracked route'): self.validate()

    def test_empty_tracking_day_fails_without_removing_it(self):
        folder = self.root / self.tracking / '2025-05-11'; folder.mkdir()
        with self.assertRaisesRegex(ValueError, 'Empty'): self.validate()
        self.assertTrue(folder.exists())

    def test_import_and_whole_restore_keep_all_d_records_and_failed_e_stage_bytes(self):
        self.fixture.capture(); self.fixture.perform_import('arrival-tracking-v1')
        store = self.fixture.out
        pending = store / 'versions' / ('pending-' + 'a' * 32)
        pending.mkdir(); (pending / 'partial.jar').write_bytes(b'interrupted feature stage')
        before = snapshots.inventory(store, self.fixture.limits)
        copy = self.fixture.base / 'whole-tracking-store'
        snapshot = snapshots.snapshot(store, copy, stopped=True, limits=self.fixture.limits)
        result = migration.restore_store(copy, self.fixture.base / 'restored-tracking-store',
                snapshot['manifest_sha256'], profile='arrival-tracking-v1', confirm_restore=True,
                limits=self.fixture.limits)
        recovered = Path(result['restored_store'])
        self.assertEqual(before, snapshots.inventory(recovered, self.fixture.limits))
        self.assertEqual(self.fixture.original_inventory, snapshots.inventory(self.root, self.fixture.limits))
        self.assertFalse(result['launch_safe'])
        self.assertEqual(b'interrupted feature stage',
                         (recovered / 'versions' / pending.name / 'partial.jar').read_bytes())


if __name__ == '__main__': unittest.main()
