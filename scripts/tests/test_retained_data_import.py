import json
from pathlib import Path
import tempfile
import unittest
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from import_retained_data import import_data

class RetainedImportTest(unittest.TestCase):
    def test_copy_and_refuse_settings_overwrite_and_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=root/'old';source.mkdir()
            (source/'資材ログ.csv').write_text('original\n')
            (source/'sortie-arrivals-v1').mkdir();(source/'sortie-arrivals-v1/history.json').write_text('{}')
            report=import_data(source,root/'new',['資材ログ.csv','sortie-arrivals-v1'])
            self.assertTrue(report['source_unchanged']);self.assertEqual(report['count'],2)
            self.assertEqual((root/'new/資材ログ.csv').read_text(),'original\n')
            self.assertFalse((root/'new/IMPORT_INCOMPLETE').exists())
            with self.assertRaises(FileExistsError):import_data(source,root/'new',['資材ログ.csv'])
            for name in ['config/logbook.bean.AppConfig.json','resources','../escape.csv']:
                with self.assertRaises(ValueError):import_data(source,root/'denied',[name])
            (source/'link.csv').symlink_to(source/'資材ログ.csv')
            with self.assertRaises(ValueError):import_data(source,root/'symlink',['link.csv'])
            self.assertEqual((source/'資材ログ.csv').read_text(),'original\n')
    def test_partial_failure_keeps_source_and_marks_copy_incomplete(self):
        from unittest.mock import patch
        import import_retained_data as module
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);source=root/'old';source.mkdir();(source/'x.csv').write_text('before')
            original=module.regular_bytes;calls=0
            def changing(path):
                nonlocal calls
                calls+=1
                if calls==2:return b'changed'
                return original(path)
            with patch.object(module,'regular_bytes',changing):
                with self.assertRaises(ValueError):import_data(source,root/'new',['x.csv'])
            self.assertTrue((root/'new/IMPORT_INCOMPLETE').exists())
            self.assertEqual((source/'x.csv').read_text(),'before')
