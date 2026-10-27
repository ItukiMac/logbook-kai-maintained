from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from scripts.check_nonmint_gates import validate


ROOT = Path(__file__).resolve().parents[2]
LEDGER = json.loads((ROOT / "docs/maintenance/non-mint-completion-gates.json").read_text(encoding="utf8"))


class GateLedgerTest(unittest.TestCase):
    def test_all_remaining_tasks_explicit(self):
        report = validate(deepcopy(LEDGER), ROOT)
        self.assertEqual(19, report["tasks"])
        self.assertEqual(15, report["mint_independent_open"])
        self.assertEqual(3, report["native_mint_open"])
        self.assertEqual(1, report["manual_approval"])
        self.assertFalse(report["release_ready"])
        self.assertFalse(report["auto_publish_authorized"])

    def test_cannot_call_unverified_work_done(self):
        doc = deepcopy(LEDGER)
        doc["tasks"][0]["verified_complete"] = True
        with self.assertRaises(ValueError):
            validate(doc, ROOT)

    def test_cannot_drop_remaining_task(self):
        doc = deepcopy(LEDGER)
        doc["tasks"].pop()
        with self.assertRaises(ValueError):
            validate(doc, ROOT)

    def test_cannot_misclassify_mint(self):
        doc = deepcopy(LEDGER)
        doc["tasks"][15]["mint_independent"] = True
        with self.assertRaises(ValueError):
            validate(doc, ROOT)

    def test_missing_evidence_fails_closed(self):
        doc = deepcopy(LEDGER)
        doc["tasks"][0]["evidence"] = "docs/maintenance/not-real.md"
        with self.assertRaises(ValueError):
            validate(doc, ROOT)

    def test_duplicate_task_fails_closed(self):
        doc = deepcopy(LEDGER)
        doc["tasks"][0]["key"] = doc["tasks"][1]["key"]
        with self.assertRaises(ValueError):
            validate(doc, ROOT)


if __name__ == "__main__":
    unittest.main()
