import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import maintenance_workbench as w

class WorkbenchTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.repo=self.root/'repo';self.repo.mkdir()
        w.git(self.repo,'-c','init.templateDir=','init','-q')
        (self.repo/'src').mkdir();(self.repo/'scripts').mkdir()
        (self.repo/'src/value.txt').write_text('source before\n')
        (self.repo/'scripts/check.py').write_text("print('RECOVERED_WORKBENCH_OK')\n")
        (self.repo/'.gitignore').write_text('private-runtime/\n')
        w.git(self.repo,'add','--all');w.git(self.repo,'-c','user.name=Fixture','-c','user.email=fixture@localhost','commit','-qm','fixture')
        (self.repo/'private-runtime').mkdir();(self.repo/'private-runtime/secret.txt').write_text('never archive this')
        self.progress={'task':'M4','state':'verification','next':'resume fixed validator','failure_history':['retained']}
        self.tools={'python':'3.12','java':'21.0.13+9-LTS','maven':'3.9.11','git':'2.56.0'}
        self.out=self.root/'checkpoint'
    def capture(self,**kw):
        return w.checkpoint(self.repo,self.out,progress=self.progress,toolchain=self.tools,**kw)
    def state(self):
        return {str(p.relative_to(self.repo)):p.read_bytes() for p in self.repo.rglob('*') if p.is_file()}
    def test_checkpoint_restores_dirty_tracked_source_and_progress_into_new_git_history(self):
        (self.repo/'src/value.txt').write_text('unsaved source preserved\n');before=self.state();r=self.capture()
        restored=w.restore(self.out,r['checkpoint_sha256'],self.root/'recovered',protected=[self.repo],initialize_git=True)
        self.assertEqual(before,self.state());self.assertEqual('unsaved source preserved\n',(self.root/'recovered/src/value.txt').read_text())
        self.assertEqual(self.progress,restored['progress']);self.assertEqual(self.tools,restored['toolchain']);self.assertFalse(restored['origin_history_copied'])
        self.assertEqual(3,len(w.tracked(self.root/'recovered')))
        run=subprocess.run([sys.executable,'scripts/check.py'],cwd=self.root/'recovered',capture_output=True,text=True,check=True)
        self.assertEqual('RECOVERED_WORKBENCH_OK',run.stdout.strip());self.assertFalse(restored['execution_performed'])
    def test_ignored_private_runtime_and_git_credentials_are_never_archived(self):
        before=self.state();r=self.capture();report=w.verify(self.out,r['checkpoint_sha256'])
        self.assertFalse(any('private-runtime' in row['path'] or '.git/' in row['path'] for row in report['files']))
        with ZipFile(self.out/'source.zip') as z:self.assertNotIn('private-runtime/secret.txt',z.namelist())
        self.assertEqual(before,self.state())
    def test_new_source_requires_explicit_inclusion_and_roundtrips(self):
        (self.repo/'scripts/new.py').write_text('new development input')
        r=self.capture(include_new=['scripts/new.py']);report=w.verify(self.out,r['checkpoint_sha256'])
        self.assertIn('scripts/new.py',[row['path'] for row in report['files']])
    def test_non_source_new_file_cannot_be_added(self):
        (self.repo/'secret.txt').write_text('not an input')
        with self.assertRaises(ValueError):self.capture(include_new=['secret.txt'])
        self.assertFalse(self.out.exists())
    def test_input_output_aliases_and_existing_directories_are_refused(self):
        for path in [self.repo,self.repo/'inside',self.root]:
            with self.assertRaises(ValueError):w.checkpoint(self.repo,path,progress={},toolchain=self.tools)
        self.out.mkdir()
        with self.assertRaises(ValueError):self.capture()
    def test_symlink_source_and_parent_are_refused(self):
        alias=self.root/'alias';alias.symlink_to(self.repo,target_is_directory=True)
        with self.assertRaises(ValueError):w.checkpoint(alias,self.out,progress={},toolchain=self.tools)
        (self.repo/'src/value.txt').unlink();(self.repo/'src/value.txt').symlink_to(self.repo/'scripts/check.py')
        with self.assertRaises(ValueError):self.capture()
        self.assertFalse(self.out.exists())
    def test_tampered_manifest_cannot_restore_and_preserves_old_environment(self):
        before=self.state();r=self.capture();(self.out/'workbench.json').write_text('{}');target=self.root/'restore'
        with self.assertRaises(ValueError):w.restore(self.out,r['checkpoint_sha256'],target,protected=[self.repo])
        self.assertFalse(target.exists());self.assertEqual(before,self.state())
    def test_corrupt_archive_is_rejected_before_restored_directory_exists(self):
        r=self.capture();(self.out/'source.zip').write_bytes(b'corrupt');target=self.root/'restore'
        with self.assertRaises(ValueError):w.restore(self.out,r['checkpoint_sha256'],target)
        self.assertFalse(target.exists())
    def forged(self,edit):
        r=self.capture();path=self.out/'workbench.json';report=json.loads(path.read_bytes());edit(report)
        raw=json.dumps(report).encode();path.write_bytes(raw);return hashlib.sha256(raw).hexdigest()
    def test_pinned_but_unsafe_source_names_and_permissions_are_rejected(self):
        for number,name in enumerate(['../escape','/absolute','C:/outside','src/.git/config']):
            with self.subTest(name=name):
                path=self.root/('forged-'+str(number));path.mkdir()
                (path/'source.zip').write_bytes(b'unused')
                report={'format':w.FORMAT,'origin_revision':'a'*40,'paid_provider_calls':0,'production_accessed':False,'publication_allowed':False,'progress':{},'toolchain':self.tools,'files':[{'path':name,'mode':'100644','size':1,'sha256':'a'*64}],'archive_sha256':'a'*64}
                raw=json.dumps(report).encode();(path/'workbench.json').write_bytes(raw)
                with self.assertRaises(ValueError):w.verify(path,hashlib.sha256(raw).hexdigest())
    def test_duplicate_source_inventory_is_refused_even_with_pinned_manifest(self):
        sha=self.forged(lambda r:r['files'].append(dict(r['files'][0])))
        with self.assertRaises(ValueError):w.verify(self.out,sha)
    def test_claimed_paid_or_publication_permissions_are_never_restored(self):
        sha=self.forged(lambda r:r.update(publication_allowed=True))
        with self.assertRaises(ValueError):w.verify(self.out,sha)
    def test_capture_change_retains_partial_artifact_but_no_success_manifest(self):
        real=w.read_source;calls=0
        def changing(root,name):
            nonlocal calls
            calls+=1
            if calls==4:(self.repo/'src/value.txt').write_text('changed after inventory')
            return real(root,name)
        with patch.object(w,'read_source',side_effect=changing):
            with self.assertRaises(ValueError):self.capture()
        self.assertTrue((self.out/'source.zip').exists());self.assertFalse((self.out/'workbench.json').exists())
    def test_failure_restoring_to_protected_source_does_not_touch_source_or_checkpoint(self):
        r=self.capture();before=self.state();archive=(self.out/'source.zip').read_bytes()
        with self.assertRaises(ValueError):w.restore(self.out,r['checkpoint_sha256'],self.repo/'recovered',protected=[self.repo])
        self.assertEqual(before,self.state());self.assertEqual(archive,(self.out/'source.zip').read_bytes())
    def test_executable_mode_survives_new_checkout(self):
        (self.repo/'scripts/check.py').chmod(0o755);w.git(self.repo,'update-index','--chmod=+x','scripts/check.py');r=self.capture();target=self.root/'restored'
        w.restore(self.out,r['checkpoint_sha256'],target,initialize_git=True)
        self.assertEqual('100755',w.tracked(target)['scripts/check.py']);self.assertTrue((target/'scripts/check.py').stat().st_mode & 0o111)
    def test_exact_environment_is_checked_without_executing_checkpoint_commands(self):
        r=self.capture();report=w.verify(self.out,r['checkpoint_sha256'])
        self.assertTrue(w.check_environment(report,dict(self.tools))['compatible'])
        changed=dict(self.tools,java='25.0.5')
        with self.assertRaises(ValueError):w.check_environment(report,changed)
        self.assertFalse(w.check_environment(report,dict(self.tools))['project_code_executed'])
    def test_tool_commands_in_manifest_cannot_replace_version_declarations(self):
        self.tools['java']='java -jar untrusted.jar'
        with self.assertRaises(ValueError):self.capture()
        self.assertFalse(self.out.exists())
    def test_missing_tools_are_not_silently_accepted(self):
        del self.tools['maven']
        with self.assertRaises(ValueError):self.capture()
    def test_real_tool_probe_uses_only_fixed_version_commands_and_a_clean_environment(self):
        runs=[]
        def run(command,**kw):
            runs.append((command,kw));output={'git':'git version 2.56.0','java':'openjdk version "21.0.13"\nOpenJDK Runtime Environment (build 21.0.13+9-LTS)','mvn':'Apache Maven 3.9.11'}[command[0]]
            return subprocess.CompletedProcess(command,0,output)
        with patch.object(w.subprocess,'run',side_effect=run):observed=w.measure_environment()
        self.assertEqual(set(w.TOOLS),set(observed));self.assertEqual([['git','--version'],['java','-version'],['mvn','--version']],[r[0] for r in runs])
        for _,kw in runs:self.assertNotIn('OPENAI_API_KEY',kw['env']);self.assertNotIn('JAVA_TOOL_OPTIONS',kw['env'])
    def test_tracked_files_matching_ignore_rules_are_not_lost_from_new_git_checkout(self):
        (self.repo/'.gitignore').write_text('private-runtime/\nscripts/kept.txt\n')
        (self.repo/'scripts/kept.txt').write_text('already tracked input')
        w.git(self.repo,'add','--force','scripts/kept.txt');r=self.capture();out=self.root/'recovered'
        w.restore(self.out,r['checkpoint_sha256'],out,initialize_git=True)
        self.assertIn('scripts/kept.txt',w.tracked(out));self.assertEqual('already tracked input',(out/'scripts/kept.txt').read_text())
    def test_origin_commit_does_not_pretend_dirty_captured_bytes_were_committed(self):
        (self.repo/'src/value.txt').write_text('different')
        r=self.capture();report=w.verify(self.out,r['checkpoint_sha256'])
        self.assertTrue(report['captured_uncommitted_changes'])
    def test_unstaged_executable_mode_changes_survive_resume(self):
        (self.repo/'scripts/check.py').chmod(0o755)
        self.assertEqual('100644',w.tracked(self.repo)['scripts/check.py'])
        r=self.capture();out=self.root/'resumed'
        w.restore(self.out,r['checkpoint_sha256'],out,initialize_git=True)
        self.assertEqual('100755',w.tracked(out)['scripts/check.py'])
