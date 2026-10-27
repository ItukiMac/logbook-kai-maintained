"""Apply a bounded mechanical migration to an isolated checkout, never runtime data."""
from pathlib import Path
import re,sys
root=Path(sys.argv[1]).resolve()
assert (root/'pom.xml').is_file()

def arguments(text,start):
    depth=0;quoted=None;escape=False
    for i in range(start,len(text)):
        c=text[i]
        if quoted:
            if escape:escape=False
            elif c=='\\':escape=True
            elif c==quoted:quoted=None
        elif c in ('\"',"'"):quoted=c
        elif c=='(':depth+=1
        elif c==')':
            depth-=1
            if depth==0:return i+1
    raise ValueError('Unclosed constructor/option')

def transform(text):
    text=text.replace('com.fasterxml.jackson.databind','tools.jackson.databind').replace('com.fasterxml.jackson.core','tools.jackson.core')
    text=text.replace('import tools.jackson.core.JsonParser.Feature;','')
    text=text.replace('tools.jackson.core.JsonFactory','tools.jackson.core.json.JsonFactory')
    text=text.replace('JsonProcessingException','JacksonException').replace('JsonMappingException','DatabindException')
    text=text.replace('JsonParser.Feature.ALLOW_COMMENTS','tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS')
    text=text.replace('Feature.ALLOW_COMMENTS','tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS')
    text=text.replace('JsonParser.Feature.STRICT_DUPLICATE_DETECTION','tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION')
    text=text.replace('tools.jackson.core.tools.jackson.core.','tools.jackson.core.')
    text=text.replace('JsonParser.Feature.AUTO_CLOSE_SOURCE','tools.jackson.core.StreamReadFeature.AUTO_CLOSE_SOURCE')
    text=text.replace('tools.jackson.databind.json.JsonMapper.builder()','tools.jackson.databind.json.JsonMapper.builderWithJackson2Defaults()')
    # Fold sequential configuration of final fields before constructing immutable mappers.
    pattern=r'(?P<left>(?:this\.)?\b\w+)\s*=\s*new (?:tools\.jackson\.databind\.)?ObjectMapper\(\);\s*(?P=left)\.(?P<option>configure|enable|disable)\((?P<args>[^;]+)\);'
    text=re.sub(pattern,lambda m:m['left']+' = logbook.internal.json.JsonMappers.builder().'+m['option']+'('+m['args']+').build();',text)
    # Known factory form used by feature metadata validation.
    text=re.sub(r'new ObjectMapper\(new JsonFactory\(\)\s*\.enable\(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION\)\)',
                'logbook.internal.json.JsonMappers.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()',text)
    # New mapper constructor, with any fluent configuration applied before build().
    pattern=re.compile(r'new (?:tools\.jackson\.databind\.)?ObjectMapper\(\)')
    pos=0
    while True:
        match=pattern.search(text,pos)
        if not match:break
        end=match.end();options=''
        while True:
            option=re.match(r'\s*\.(enable|disable|configure)\s*\(',text[end:])
            if not option:break
            opening=end+option.end()-1;stop=arguments(text,opening)
            options+='.'+option.group(1)+text[opening:stop];end=stop
        replacement='logbook.internal.json.JsonMappers.builder()'+options+'.build()'
        text=text[:match.start()]+replacement+text[end:];pos=match.start()+len(replacement)
    # Mutating a previously constructed mapper must use an immutable replacement.
    text=re.sub(r'(?m)^(\s*)((?:this\.)?mapper)\.(enable|disable|configure)\(([^;]+)\);',
                lambda m:m[1]+m[2]+' = '+m[2]+'.rebuild().'+m[3]+'('+m[4]+').build();',text)
    text=text.replace('.fieldNames()', '.propertyNames().iterator()').replace('.fields()', '.properties().iterator()')
    return text

changed=[]
paths=list((root/'src/main/java').rglob('*.java'))+list((root/'src/test/java').rglob('*.java'))
paths+=[root/'scripts/java/ArrivalArchiveProbe.java']
for path in paths:
    raw=path.read_bytes();text=raw.decode('utf8')
    if 'com.fasterxml.jackson' not in text:continue
    ending='\r\n' if b'\r\n' in raw else '\n'
    normalized=text.replace('\r\n','\n');new=transform(normalized)
    if new!=normalized:
        path.write_bytes(new.replace('\n',ending).encode());changed.append(str(path.relative_to(root)))
codec=root/'src/main/java/logbook/internal/json/JsonMappers.java';codec.parent.mkdir(exist_ok=True)
codec.write_text('''package logbook.internal.json;

import tools.jackson.databind.json.JsonMapper;

/** Host JSON construction boundary. Retains the existing persisted JSON defaults.
 * Jackson 2 remains a runtime-only compatibility dependency for existing plugins;
 * host code uses immutable Jackson 3 mappers. Protocol-specific strict options
 * are added by the caller and are never disabled by this factory.
 */
public final class JsonMappers {
    private JsonMappers() { }
    public static JsonMapper.Builder builder() {
        return JsonMapper.builderWithJackson2Defaults();
    }
}
''')
pom=root/'pom.xml';text=pom.read_text()
old='<groupId>com.fasterxml.jackson.core</groupId>\n            <artifactId>jackson-databind</artifactId>\n            <version>2.22.4</version>'
assert text.count(old)==1
new=old+'\n            <!-- Only for existing separately compiled KLB/KQS and third-party code. -->\n            <scope>runtime</scope>\n        </dependency>\n        <dependency>\n            <groupId>tools.jackson.core</groupId>\n            <artifactId>jackson-databind</artifactId>\n            <version>3.2.4</version>'
text=text.replace(old,new)
pom.write_text(text)
print('MIGRATION_PATHS',*changed,sep='\n')
