package local.kancolle.kqs;

import java.time.Instant;
import java.util.*;
import logbook.core.LogBookCoreServices;
import logbook.plugin.api.*;
import logbook.core.quest.QuestLedger;
import logbook.internal.json.JsonMappers;
import tools.jackson.databind.JsonNode;

/** Explicit public projections only: never serialize configuration or HTTP objects. */
public final class InformationExport {
    private InformationExport() { }
    public static Map<String,Object> capture() throws java.io.IOException {
        Map<String,Object> result = LogBookCoreServices.readGameState(() -> {
            Map<String,Object> values = new LinkedHashMap<>();
            values.put("schema", "logbook.information.v1");
            values.put("captured_at", Instant.now().toString());
            values.put("consistency", "Host state captured under writer lock; disk histories and ledger captured separately. Legacy uncooperative writers are outside this boundary.");
            values.put("admiral", OperationsDataV1.admiral());
            values.put("game_state", LogBookCoreServices.gameStateView().snapshot());
            values.put("ships_and_fleets", ShipRosterV1.snapshot());
            values.put("ship_statistics", ShipStatisticsV1.snapshot());
            values.put("equipment", EquipmentDataV1.inventory());
            values.put("aircraft", EquipmentDataV1.aircraft());
            values.put("supplies_repairs_construction", OperationsDataV1.snapshot());
            values.put("airbases", AirBaseStatusV1.snapshot());
            values.put("quests", QuestListV1.snapshot());
            return values;
        });
        try(var scope=LogBookCoreServices.pinDefinitions()) {
            Map<String,String> definitions=new LinkedHashMap<>();
            for(String name:scope.snapshot().resourceNames()) {
                try(var input=scope.snapshot().openResource(name).orElseThrow()) {
                    definitions.put(name,new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            result.put("definition_generation",scope.snapshot().generation());result.put("definitions",definitions);
        }
        java.nio.file.Path materials=java.nio.file.Path.of(logbook.bean.AppConfig.get().getReportPath(),new logbook.internal.log.MaterialLogFormat().fileName());
        result.put("resource_history",java.nio.file.Files.isRegularFile(materials)?Map.of("status","available","csv",java.nio.file.Files.readString(materials,logbook.internal.log.LogWriter.DEFAULT_CHARSET)):Map.of("status","not_recorded"));
        result.put("battle_records", RecordDataV1.battles());
        result.put("expedition_records", RecordDataV1.missions());
        result.put("development_records", RecordDataV1.developments());
        java.nio.file.Path construction=java.nio.file.Path.of(logbook.bean.AppConfig.get().getReportPath(),new logbook.internal.log.CreateshipLogFormat().fileName());
        result.put("construction_history",java.nio.file.Files.isRegularFile(construction)?Map.of("status","available","csv",java.nio.file.Files.readString(construction,logbook.internal.log.LogWriter.DEFAULT_CHARSET)):Map.of("status","not_recorded"));
        result.put("quest_management", logbook.core.quest.QuestManagement.snapshot());
        result.put("coverage", "Public read projections and locally retained histories. Unobserved game data and unavailable historical records are not reconstructed.");
        return Collections.unmodifiableMap(result);
    }
    public static String json(Object value) { return JsonMappers.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(value); }
    /** Lossless leaf/path table for nested domains; JSON remains the round-trip format. */
    public static String csv(Object value) {
        StringBuilder out = new StringBuilder("path,type,value\r\n");
        flatten(JsonMappers.builder().build().valueToTree(value), "", out);
        return out.toString();
    }
    private static void flatten(JsonNode node, String path, StringBuilder out) {
        if (node.isObject() && !node.isEmpty()) {
            for (var item : node.properties()) flatten(item.getValue(), path+"/"+item.getKey().replace("~","~0").replace("/","~1"), out);
        } else if (node.isArray() && !node.isEmpty()) {
            for (int i=0;i<node.size();i++) flatten(node.get(i),path+"/"+i,out);
        } else {
            out.append(cell(path)).append(',').append(cell(node.getNodeType().name())).append(',')
                .append(cell(node.isTextual()?node.asText():node.toString())).append("\r\n");
        }
    }
    private static String cell(String text) {
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0))>=0) text="'"+text;
        return '"'+text.replace("\"","\"\"")+'"';
    }
}
