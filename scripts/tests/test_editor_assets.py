import hashlib
import json
from pathlib import Path
import re
import unittest

class EditorAssetsTest(unittest.TestCase):
    def test_local_assets_match_fixed_upstream_manifest_and_license(self):
        root=Path(__file__).resolve().parents[2]
        report=json.loads((root/'docs/maintenance/editor-assets-20261030.json').read_text())
        self.assertEqual('1.45.2',report['version']);self.assertEqual('BSD-3-Clause',report['license'])
        base=root/'src/main/resources/logbook/gui/ace'
        self.assertEqual({p.relative_to(base).as_posix() for p in base.rglob('*') if p.is_file()},{r['path'] for r in report['files']})
        for row in report['files']:
            self.assertEqual(row['sha256'],hashlib.sha256((base/row['path']).read_bytes()).hexdigest(),row['path'])
        self.assertIn('Copyright',(base/'LICENSE').read_text())
    def test_editor_loads_only_existing_local_script_resources(self):
        root=Path(__file__).resolve().parents[2]/'src/main/resources/logbook/gui'
        html=(root/'text_editor_pane.html').read_text()
        sources=re.findall(r'<script\s+src="([^"]+)"',html)
        self.assertGreaterEqual(len(sources),6)
        for name in sources:
            self.assertTrue(name.startswith('ace/'));self.assertNotIn(':',name);self.assertNotIn('..',name)
            self.assertTrue((root/name).is_file())
