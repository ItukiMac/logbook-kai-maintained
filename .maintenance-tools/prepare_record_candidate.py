from pathlib import Path
import runpy,json

runpy.run_path('../tooling/.maintenance-tools/apply_record_data.py')
s=Path('../tooling/.maintenance-tools/add_record_tests.py').read_text()
old="lambda s:s.replace('CollectPeriod','CollectUnit')";assert s.count(old)==1
s=s.replace(old,"lambda s:s.replace('collectPeriod','collectUnit').replace('setPeriod(', 'setUnit(')")
exec(compile(s,'add_record_tests_corrected.py','exec'))
p=Path('src/test/java/logbook/core/RecordDataContractTest.java');s=p.read_text();assert s.count('import java.time.*;')==1
p.write_text(s.replace('import java.time.*;','import java.time.LocalDate;\nimport java.time.ZoneId;\nimport java.time.ZonedDateTime;'))
p=Path('scripts/java/RecordUiProbe.java');s=p.read_text();assert s.count('root.applyCss();root.layout();')==1
p.write_text(s.replace('root.applyCss();root.layout();','((TableView) detail).scrollToColumn(exp);root.applyCss();root.layout();'))
runpy.run_path('../tooling/.maintenance-tools/compact_record_views.py')
runpy.run_path('../tooling/.maintenance-tools/package_record_views.py')
p=Path('scripts/tests/test_record_distribution_profile.py');s=p.read_text()
old="        self.rewrite('logbook-core.jar',lambda entries:entries.update({name:b'fallback'}))\n        with self.assertRaisesRegex"
new="""        self.rewrite('logbook-core.jar',lambda entries:entries.update({name:b'fallback'}))
        # Make all hashes/metadata otherwise coherent, so this asserts ownership,
        # not an earlier unrelated rejection by the exact-host guard.
        report=self.manifest();host_hash=report['host']['sha256']
        for module in report['modules']:module['host_sha256']=host_hash
        self.save(report)
        def rebind(entries):
            metadata=json.loads(entries['META-INF/logbook/feature.json'])
            metadata['host_sha256']=host_hash
            entries['META-INF/logbook/feature.json']=json.dumps(metadata).encode()
        for module in report['modules']:self.rewrite(module['path'],rebind)
        with self.assertRaisesRegex"""
assert s.count(old)==1;p.write_text(s.replace(old,new))
paths=sorted(set(json.loads(Path('../evidence/changed-paths.json').read_text())+json.loads(Path('../evidence/packaging-paths.json').read_text())))
Path('../evidence/product-paths.json').write_text(json.dumps(paths,indent=2)+'\n')
print('RECORD_CANDIDATE_READY paths=',len(paths))
