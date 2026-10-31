"""Actual B import + D readback + E managed launch/update + new-copy recovery.

All files are newly generated synthetic fixtures. Never reads a user's runtime.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import feature_bundle_manager as manager
import runtime_managed_import as migration
import runtime_snapshot as snapshots
from build_feature_distribution import build
import test_feature_version_integration as feature_fixture

ROOT=Path(__file__).resolve().parents[2]
JAR=ROOT/'target/logbook-kai2.jar'
PROFILE='arrival-tracking-v1'


@unittest.skipUnless(JAR.is_file() and shutil.which('javac') and shutil.which('xvfb-run'),
                     'Requires current packaged JAR and Xvfb; mandatory in full integration CI')
class ParallelRuntimeIntegrationTest(unittest.TestCase):
    def test_import_tracking_launch_independent_update_and_new_copy_recovery(self):
        base=Path(tempfile.mkdtemp(prefix='logbook-parallel-integration-'))
        helper=feature_fixture.FeatureVersionIntegrationTest()
        logs=[]; evidence={'fixture_root':str(base),'production_accessed':False}
        try:
            classes=base/'probe-classes';classes.mkdir()
            helper.run_checked(['javac','-proc:none','--release','21','-cp',str(JAR),'-d',str(classes),
                                str(ROOT/'scripts/java/ArrivalArchiveProbe.java')],cwd=base,logs=logs)
            def probe(mode,runtime):
                result=helper.run_checked(['java','-cp',str(JAR)+os.pathsep+str(classes),
                         'logbook.internal.kancolle.ArrivalArchiveProbe',mode,str(runtime)],cwd=runtime,logs=logs)
                self.assertIn('ARRIVAL_ARCHIVE_OK records=1 checkpoints=4 retired_slots=2',result.stdout)
            original=base/'original';original.mkdir()
            number=helper.install_smoke_hook(original)
            configpath=original/'config/logbook.bean.AppConfig.json'
            config=json.loads(configpath.read_text())
            for field in snapshots.PATH_FIELDS:
                if field=='reportPath': config[field]='.'
                elif field=='pluginsDir': config[field]='plugins'
                elif field in ('defaultNotifySound','ffmpegPath'): config[field]=''
                else:
                    path='portable/'+field;(original/path).mkdir(parents=True)
                    config[field]=path
            configpath.write_text(json.dumps(config))
            probe('seed',original)
            limits=snapshots.Limits(reserve=0)
            source_before=snapshots.inventory(original,limits)
            sourcehash=feature_fixture.digest(JAR)
            pristine=base/'pristine';build(JAR,pristine,'a'*40)
            aid=helper.variant(pristine,base/'A','1.0.0',1,2,logs)
            bid=helper.variant(pristine,base/'B','1.1.0',1,3,logs)
            saved=snapshots.snapshot(original,base/'runtime-copy',stopped=True,limits=limits)
            store=base/'managed'
            imported=migration.import_snapshot(base/'runtime-copy',store,saved['manifest_sha256'],
                    profile=PROFILE,bundles=[(base/'A',aid)],selected_bundle_sha256=aid,
                    confirm_import=True,limits=limits)
            self.assertFalse(imported['launch_safe'])
            self.assertEqual(4,imported['schema_review']['tracking_checkpoints'])
            helper.launch(store,'combined-A','1.0.0',10,number,logs)
            probe('read',store/'runtime')
            # An interrupted feature copy is retained but never registered as a release.
            pending=store/'versions'/('pending-'+'b'*32);pending.mkdir()
            (pending/'partial.jar').write_bytes(b'retained failed update evidence')
            old=snapshots.snapshot(store,base/'whole-A',stopped=True,limits=limits)
            before=snapshots.inventory(store,limits)
            updated=manager.stage_feature(store,base/'B',bid,'calculators',aid)
            manager.activate(store,updated['version'],aid)
            helper.launch(store,'combined-B','1.1.0',15,number,logs)
            probe('read',store/'runtime')
            recovered=migration.restore_store(base/'whole-A',base/'restored-A',old['manifest_sha256'],
                    profile=PROFILE,confirm_restore=True,protected_roots=(original,store),limits=limits)
            target=Path(recovered['restored_store'])
            self.assertEqual(before,snapshots.inventory(target,limits))
            self.assertEqual(aid,manager.selector(target)['current'])
            helper.launch(target,'combined-A-restored','1.0.0',10,number,logs)
            probe('read',target/'runtime')
            self.assertEqual(source_before,snapshots.inventory(original,limits))
            self.assertEqual(sourcehash,feature_fixture.digest(JAR))
            self.assertEqual(b'retained failed update evidence',(target/'versions'/pending.name/'partial.jar').read_bytes())
            self.assertEqual(updated['version'],manager.selector(store)['current'])
            evidence.update(result='passed',calculations=[10,15,10],actual_launcher_runs=3,
                    tracking_checkpoints=4,arrival_records=1,retired_original_slot=2,
                    original_unchanged=True,whole_store_restored_to_new_copy=True,
                    pending_stage_preserved=True,live_mint_tested=False)
            print('PARALLEL_BDE_INTEGRATION_OK import_launch_update_newcopy_restore=true calculations=10,15,10 tracking=4 original_unchanged=true')
        except BaseException as error:
            evidence.update(result='failed',failure=repr(error));raise
        finally:
            dest=ROOT/'target/r3-verification'
            if not dest.is_dir(): dest=ROOT/'target'
            with tempfile.NamedTemporaryFile('w',dir=dest,prefix='parallel-integration-',suffix='.log',delete=False) as f:
                f.write('\n\n'.join(logs));evidence['log']=f.name
            with tempfile.NamedTemporaryFile('w',dir=dest,prefix='parallel-integration-',suffix='.json',delete=False) as f:
                json.dump(evidence,f,indent=2);print('PARALLEL_INTEGRATION_EVIDENCE='+f.name)


if __name__=='__main__':unittest.main()
