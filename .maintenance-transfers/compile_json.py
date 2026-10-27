"""Compile isolated candidate; adapt only javac-proven unthrown IOException catches.
No test assertions, user data, branch refs or repository settings are changed here.
Every intermediate log and correction is retained for human review.
"""
from pathlib import Path
import json,re,subprocess,sys
root=Path(sys.argv[1]).resolve();evidence=root.parent/'evidence'
corrections=[]
pattern=re.compile(r'\[ERROR\] (.+?\.java):\[(\d+),\d+\] exception java\.io\.IOException is never thrown in body of corresponding try statement')
for attempt in range(1,13):
    with (evidence/f'compile-{attempt}.log').open('w') as log:
        result=subprocess.run(['mvn','-B','-ntp','-DskipTests','package'],cwd=root,stdout=log,stderr=subprocess.STDOUT)
    output=(evidence/f'compile-{attempt}.log').read_text()
    for line in output.splitlines():
        if '[ERROR]' in line or 'BUILD SUCCESS' in line: print(line)
    if result.returncode==0:
        (evidence/'compiler-corrections.json').write_text(json.dumps(corrections,indent=2)+'\n')
        print('COMPILE_SUCCESS attempts='+str(attempt));sys.exit(0)
    matches=set(pattern.findall(output))
    if not matches:sys.exit(result.returncode)
    for name,number in sorted(matches):
        path=Path(name).resolve();relative=path.relative_to(root)
        assert relative.parts[:2] in (('src','main'),('src','test'))
        raw=path.read_bytes();lines=raw.decode().splitlines(keepends=True);index=int(number)-1
        old=lines[index]
        assert 'catch (' in old and 'IOException' in old,(name,number,old)
        if 'RuntimeException' in old:
            new=old.replace('IOException | ', '').replace('IOException|', '')
        else:new=old.replace('IOException','tools.jackson.core.JacksonException')
        assert new!=old
        lines[index]=new;path.write_bytes(''.join(lines).encode())
        corrections.append({'file':str(relative),'line':int(number),'before':old,'after':new,'reason':'javac confirmed only parser unchecked exceptions remain'})
    (evidence/'compiler-corrections.json').write_text(json.dumps(corrections,indent=2)+'\n')
raise SystemExit('Exceeded bounded checked-catch migration attempts')
