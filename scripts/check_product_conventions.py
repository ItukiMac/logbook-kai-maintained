#!/usr/bin/env python3
"""Check canonical product/module names and the IDE/CI formatter contract."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]

def check():
    errors=[]
    settings=dict(line.split('=',1) for line in (ROOT/'.settings/org.eclipse.jdt.core.prefs').read_text().splitlines()
                  if line.startswith('org.eclipse.jdt.core.formatter.'))
    profile={x.attrib['id']:x.attrib['value'] for x in ET.parse(ROOT/'config-quality/eclipse-java.xml').findall('.//setting')}
    if profile != settings:errors.append('IDE and Maven formatter profiles differ')
    for p in (ROOT/'src/main/java').rglob('*.java'):
        text=p.read_text()
        if re.search(r'\b(local\.kancolle\.(?:kqs|bridge)|KqsLogbookPlugin|KancolleBridgeStartUp|KlbFeatures|KqsFeatures)\b',text):
            errors.append('Obsolete internal identifier: '+str(p.relative_to(ROOT)))
        package=re.search(r'^package ([\w.]+);',text,re.M)
        if package and p.parent.relative_to(ROOT/'src/main/java').as_posix()!=package[1].replace('.','/'):
            errors.append('Package/path mismatch: '+str(p))
    ns={'m':'http://maven.apache.org/POM/4.0.0'};pom=ET.parse(ROOT/'pom.xml')
    if pom.findtext('m:artifactId',namespaces=ns)!='logbook-kai2':errors.append('Noncanonical artifactId')
    for folder in ['docs/user','docs/developer']:
        for p in (ROOT/folder).glob('*.md'):
            for target in re.findall(r'\]\(([^)]+)\)',p.read_text()):
                if '://' not in target and not target.startswith('#') and not (p.parent/target.split('#')[0]).exists():
                    errors.append('Broken documentation link: '+str(p)+' -> '+target)
    if errors:raise SystemExit('\n'.join(errors))
    print('PRODUCT_CONVENTIONS_OK formatter=shared names=canonical docs=linked')

if __name__=='__main__':check()
