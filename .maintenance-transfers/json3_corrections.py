from pathlib import Path
import re,sys
root=Path(sys.argv[1])
def edit(name,fn):
 p=root/name;raw=p.read_bytes();end='\r\n' if b'\r\n' in raw else '\n';text=raw.decode().replace('\r\n','\n');new=fn(text)
 assert new!=text,name
 p.write_bytes(new.replace('\n',end).encode())
edit('src/main/java/logbook/core/SeasonalQuestRuleService.java',lambda s:s.replace('catch (IOException | RuntimeException invalid) { return result(Status.DEFINITION_CHANGED', 'catch (RuntimeException invalid) { return result(Status.DEFINITION_CHANGED'))
edit('src/main/java/logbook/core/FeatureRegistry.java',lambda s:re.sub(r'\.enable\(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION\)\.build\(\)\s*\.enable\(DeserializationFeature.FAIL_ON_TRAILING_TOKENS\)', '.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)\n                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()',s))
edit('src/main/java/logbook/core/PreparedGameDefinitions.java',lambda s:s.replace('node.elements()', 'node.values().iterator()'))
edit('src/main/java/logbook/internal/gui/MissionCheck.java',lambda s:s.replace('private ObjectMapper mapper', 'private tools.jackson.databind.json.JsonMapper mapper'))
def form(s):
 s=re.sub(r'mapper\.setSerializationInclusion\(([^;]+)\);',lambda m:'mapper = mapper.rebuild().changeDefaultPropertyInclusion(value -> com.fasterxml.jackson.annotation.JsonInclude.Value.construct('+m[1]+', '+m[1]+')).build();',s)
 s=re.sub(r'mapper\.addMixIn\(([^;]+)\);',r'mapper = mapper.rebuild().addMixIn(\1).build();',s)
 return s
edit('src/main/java/logbook/internal/gui/QuestConditionEditController.java',form)
edit('src/main/java/logbook/internal/gui/ArsenalQuestConditionEditController.java',lambda s:s.replace('catch (IOException e)', 'catch (tools.jackson.core.JacksonException e)'))
def mapping(s):
 s=s.replace('JsonParseException','JacksonException')
 old='Map<String, Map<String, List<String>>> json = mapper.readValue(new URL(SOURCE_URL), Map.class);'
 assert s.count(old)==1
 return s.replace(old,'Map<String, Map<String, List<String>>> json;\n        try (var input = new URL(SOURCE_URL).openStream()) {\n            json = mapper.readValue(input, Map.class);\n        }')
edit('src/test/java/logbook/map/MappingGenerator.java',mapping)
for name,directory in [('LegacyJsonCompatibilityTest.java','json'),('Jackson3JournalCompatibilityTest.java','kancolle')]:
 source=Path(__file__).parent/name;target=root/'src/test/java/logbook/internal'/directory/name
 assert not target.exists()
 target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source.read_bytes())
