package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import logbook.internal.kancolle.Mapping;
import logbook.internal.kancolle.Ships;
import logbook.bean.AppQuestCondition;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GameDefinitionRuntimeTest {
    @TempDir Path root;
    String previous;
    @BeforeEach void start() throws Exception {
        previous=System.getProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);
        GameDefinitionRuntime.stopForTest();GameDefinitionRuntime.initialize();
    }
    @AfterEach void stop(){GameDefinitionRuntime.stopForTest();if(previous==null)System.clearProperty(ExternalQuestDefinitions.ROOT_PROPERTY);else System.setProperty(ExternalQuestDefinitions.ROOT_PROPERTY,previous);}
    private Path stage(String id, Map<String,String> files) throws Exception {
        Path generation=root.resolve(id);Path base=generation.resolve("generations").resolve(id.repeat(64));
        Files.createDirectories(base.resolve("definitions"));
        List<Map<String,Object>> records=new ArrayList<>();
        for(var entry:files.entrySet()) {
            byte[] bytes=entry.getValue().getBytes(StandardCharsets.UTF_8);
            Path file=base.resolve("definitions/"+entry.getKey());Files.createDirectories(file.getParent());Files.write(file,bytes);
            records.add(Map.of("path","definitions/"+entry.getKey(),"bytes",bytes.length,"sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
        }
        var mapper=new ObjectMapper();mapper.writeValue(base.resolve("manifest.json").toFile(),Map.of("format",DefinitionPackInstaller.FORMAT,"files",records));
        mapper.writeValue(generation.resolve("active.json").toFile(),Map.of("schema",DefinitionPackInstaller.POINTER_FORMAT,"generation",id.repeat(64)));
        return generation;
    }
    private Map<String,String> data(String cell,int item){return Map.of("map/mapping.json","{\"1-1-1\":\""+cell+"\"}","supplemental/ships.json","[{\"id\":1,\"min_taisen\":7,\"item\":["+item+"]}]","quest/1045.json","{\"resetType\":\"単発\",\"conditions\":[{\"count\":1}]}");}
    @Test void entireBundledSetHasTypedConsumersAndIndexMatchesSource() throws Exception {
        assertTrue(GameDefinitionRuntime.view().snapshot().resourceNames().size() >= 152);
        assertNotNull(GameDefinitionRuntime.mapping());assertFalse(GameDefinitionRuntime.mapping().isEmpty());
        var paths=new TreeSet<String>();for(String group:List.of("quest","eventquest","mission","map","kousyou","supplemental"))try(var stream=Files.walk(Path.of("src/main/resources/logbook",group))){stream.filter(p->p.toString().endsWith(".json")).map(p->Path.of("src/main/resources").relativize(p).toString().replace('\\','/')).forEach(paths::add);}
        assertEquals(paths,GameDefinitionRuntime.view().snapshot().resourceNames());
    }
    @Test void actualMappingShipAndQuestConsumersShareOneLeaseDuringReload() throws Exception {
        GameDefinitionRuntime.reload(stage("a",data("A",101)));
        var before=LogBookCoreServices.getQuestResource(1045,true);
        try(var lease=LogBookCoreServices.pinDefinitions()) {
            var first=AppQuestCondition.loadFromResource(1045);assertNotNull(first);
            assertEquals("A",Mapping.getCell("1-1-1"));assertEquals(List.of(101),Ships.shipItems(1).orElseThrow());
            GameDefinitionRuntime.reload(stage("b",data("B",102)));
            assertEquals("A",Mapping.getCell("1-1-1"));assertEquals(List.of(101),Ships.shipItems(1).orElseThrow());
            assertSame(lease.snapshot(),first.getDefinitionSnapshot());
        }
        assertEquals("B",Mapping.getCell("1-1-1"));assertEquals(List.of(102),Ships.shipItems(1).orElseThrow());
        try(var stream=before.openStream()){assertTrue(new String(stream.readAllBytes(),StandardCharsets.UTF_8).contains("単発"));}
    }
    @Test void semanticallyUnsafeCandidateCannotPublishOrNotify() throws Exception {
        var old=GameDefinitionRuntime.view().snapshot();AtomicInteger events=new AtomicInteger();
        try(var subscription=GameDefinitionRuntime.view().subscribe(e->events.incrementAndGet())) {
            Path invalid=stage("a",Map.of("quest/1.json","{\"conditions\":[],\"resetType\":\"単発\"}"));
            assertThrows(IllegalArgumentException.class,()->GameDefinitionRuntime.reload(invalid));
            assertSame(old,GameDefinitionRuntime.view().snapshot());assertEquals(0,events.get());
        }
    }
    @Test void missingResourcesNeverFallBackToBundledCaches() throws Exception {
        GameDefinitionRuntime.reload(stage("a",Map.of("map/mapping.json","{}")));
        assertEquals("1",Mapping.getCell("1-1-1"));assertTrue(Ships.shipItems(1).isEmpty());
        assertNull(AppQuestCondition.loadFromResource(1045));assertNull(LogBookCoreServices.getResource("logbook/supplemental/ships.json"));
    }
    @Test void nestedLeasesRequireLifoAndTheCreatingThread() throws Exception {
        var outer=LogBookCoreServices.pinDefinitions();var inner=LogBookCoreServices.pinDefinitions();
        assertThrows(IllegalStateException.class,outer::close);
        try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){pool.submit(()->assertThrows(IllegalStateException.class,inner::close)).get();}
        inner.close();inner.close();outer.close();
    }
    @Test void unsafeMissionAndFleetOperandsAreRejectedBeforePublication() {
        for(String bad:List.of("{\"type\":\"invalid\"}","{\"type\":\"艦隊\",\"count_type\":\"装備\",\"value\":1}","{\"operator\":\"EQ\",\"conditions\":[{\"type\":\"艦娘\"}]}")) {
            assertThrows(IllegalArgumentException.class,()->PreparedGameDefinitions.prepare(new logbook.plugin.api.GameDefinitionSnapshot("bad-mission",Map.of("logbook/mission/1/01.json",bad.getBytes(StandardCharsets.UTF_8)))));
        }
        String bad="{\"conditions\":[{\"count\":1}],\"filter\":{\"fleet\":{\"count\":1,\"operator\":\"AND\",\"name\":[\"x\"]}}}";
        assertThrows(IllegalArgumentException.class,()->PreparedGameDefinitions.prepare(new logbook.plugin.api.GameDefinitionSnapshot("bad-fleet",Map.of("logbook/quest/1.json",bad.getBytes(StandardCharsets.UTF_8)))));
    }
    @Test void malformedTypedSupplementalAndOperatorsAreRejected() throws Exception {
        for(String bad:List.of("{\"operator\":\"BAD\",\"conditions\":[{}]}","{\"conditions\":[{\"order\":0}]}")){
            assertThrows(IllegalArgumentException.class,()->PreparedGameDefinitions.prepare(new logbook.plugin.api.GameDefinitionSnapshot("test",Map.of("logbook/quest/1.json",bad.getBytes(StandardCharsets.UTF_8)))));
        }
    }
}
