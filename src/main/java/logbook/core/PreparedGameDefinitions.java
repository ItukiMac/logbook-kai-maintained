package logbook.core;

import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import logbook.bean.AppQuestCondition;
import logbook.bean.MissionCondition;
import logbook.plugin.api.GameDefinitionSnapshot;

/** Immutable, eagerly validated consumer data. Mutable predicate beans are never shared. */
final class PreparedGameDefinitions {
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record ShipInfo(int id, int minTaisen, int minSakuteki, int minKaihi, List<Integer> items) {}
    final Map<String,String> mapping;
    final Map<Integer,ShipInfo> ships;
    final Map<String,List<String>> arsenalDays;
    private PreparedGameDefinitions(Map<String,String> mapping, Map<Integer,ShipInfo> ships, Map<String,List<String>> days) {
        this.mapping = Collections.unmodifiableMap(new LinkedHashMap<>(mapping));
        this.ships = Map.copyOf(ships); this.arsenalDays = Map.copyOf(days);
    }
    static PreparedGameDefinitions prepare(GameDefinitionSnapshot snapshot) {
        Map<String,String> mapping = new LinkedHashMap<>();
        Map<Integer,ShipInfo> ships = new HashMap<>();
        Map<String,List<String>> days = new LinkedHashMap<>();
        for (String name : snapshot.resourceNames()) {
            try (var input = snapshot.openResource(name).orElseThrow()) {
                JsonNode node = JSON.readTree(input);
                require(node != null, "empty JSON");
                if (name.startsWith("logbook/quest/") || name.startsWith("logbook/eventquest/")) {
                    require(node.isObject(), "quest must be an object");
                    JSON.treeToValue(node, AppQuestCondition.class);
                    require(node.path("conditions").isArray() && !node.path("conditions").isEmpty(), "quest conditions required");
                    if (node.has("yearlyResetMonth")) require(node.get("yearlyResetMonth").isIntegralNumber()
                        && node.get("yearlyResetMonth").asInt() >= 1 && node.get("yearlyResetMonth").asInt() <= 12, "yearly month");
                    quest(node); predicates(node);
                } else if (name.startsWith("logbook/mission/")) {
                    require(node.isObject(), "mission must be an object");
                    JSON.treeToValue(node, MissionCondition.class); mission(node); predicates(node);
                } else if (name.equals("logbook/map/mapping.json")) {
                    require(node.isObject(), "mapping must be an object");
                    var fields = node.fields();
                    while (fields.hasNext()) {
                        var field = fields.next();
                        require(field.getKey().matches("[0-9]+-[0-9]+-[0-9]+") && field.getValue().isTextual(), "mapping entry");
                        mapping.put(field.getKey(), field.getValue().asText());
                    }
                } else if (name.equals("logbook/supplemental/ships.json")) {
                    JsonNode entries = node.isArray() ? node : node.path("ships");
                    require(entries.isArray(), "ships must be an array");
                    for (JsonNode ship : entries) {
                        int id = integer(ship,"id",false);
                        require(id > 0 && !ships.containsKey(id), "duplicate/invalid ship ID");
                        List<Integer> items = null;
                        if (ship.hasNonNull("item")) {
                            require(ship.get("item").isArray(), "ship items");
                            List<Integer> values = new ArrayList<>();
                            for (JsonNode item : ship.get("item")) {require(item.isIntegralNumber() && item.canConvertToInt(),"item ID"); values.add(item.intValue());}
                            items = List.copyOf(values);
                        }
                        ships.put(id,new ShipInfo(id,integer(ship,"min_taisen",true),integer(ship,"min_sakuteki",true),integer(ship,"min_kaihi",true),items));
                    }
                } else if (name.equals("logbook/supplemental/equiptypes.json")) {
                    require(node.path("categories").isArray(), "equip categories");
                    for (JsonNode category : node.path("categories")) {
                        require(category.path("name").isTextual() && category.path("types").isArray(), "equip category");
                        for(JsonNode type: category.path("types")) require(type.isIntegralNumber() && type.canConvertToInt(),"equip type");
                    }
                } else if (name.equals("logbook/kousyou/kousyou.json")) {
                    require(node.isArray(), "arsenal must be an array");
                    for (JsonNode item : node) {
                        require(item.path("SlotItem").isTextual() && item.path("AllAvailableDays").isArray(), "arsenal entry");
                        List<String> values = new ArrayList<>();
                        for (JsonNode day : item.path("AllAvailableDays")) {
                            require(day.isTextual() && Set.of("日","月","火","水","木","金","土").contains(day.asText()),"arsenal day");
                            values.add(day.asText());
                        }
                        days.put(item.path("SlotItem").asText(), List.copyOf(values));
                    }
                } else throw new IllegalArgumentException("Unregistered typed consumer: " + name);
            } catch(IOException | RuntimeException e) {throw new IllegalArgumentException("Invalid consumer definition: " + name, e);}
        }
        return new PreparedGameDefinitions(mapping,ships,days);
    }
    private static final Set<String> LOGICAL=Set.of("AND","OR","NAND","NOR");
    private static final Set<String> NUMERIC=Set.of("EQ","NE","GE","GT","LE","LT");
    private static void quest(JsonNode node) {
        if(node.hasNonNull("resetType")) require(Set.of("単発","デイリー","ウィークリー","マンスリー","クオータリー","イヤリー").contains(node.path("resetType").asText()),"quest reset type");
        if(node.hasNonNull("startDate")) java.time.LocalDateTime.parse(node.get("startDate").asText(),java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT));
        for(JsonNode condition:node.path("conditions")) {
            require(condition.isObject() && integer(condition,"count",false)>0,"positive quest target");
            if(condition.hasNonNull("rank")) for(JsonNode rank:condition.path("rank")) require(Set.of("S","A","B","C","D","E").contains(rank.asText()),"quest rank");
        }
        JsonNode fleet=node.path("filter").path("fleet");
        if(!fleet.isMissingNode() && !fleet.isNull()) fleet(fleet);
    }
    private static void fleet(JsonNode node) {
        require(node.isObject(),"fleet condition");
        if(node.hasNonNull("count")) {
            require(integer(node,"count",false)>=0 && NUMERIC.contains(node.path("operator").asText()),"numeric fleet operator");
        } else if(node.hasNonNull("operator")) {
            require(LOGICAL.contains(node.path("operator").asText()) && node.path("conditions").isArray() && !node.path("conditions").isEmpty(),"logical fleet children");
            for(JsonNode child:node.path("conditions")) fleet(child);
            return;
        }
        require((node.path("stype").isArray() && !node.path("stype").isEmpty()) || (node.path("name").isArray() && !node.path("name").isEmpty()),"fleet match operand");
    }
    private static void mission(JsonNode node) {
        require(node.isObject(),"mission predicate");
        if(node.hasNonNull("type")) {
            String type=node.path("type").asText();
            require(Set.of("艦娘","艦隊").contains(type),"mission predicate type");
            if(type.equals("艦隊")) {
                String count=node.path("count_type").asText();
                require(Set.of("レベル","火力","対潜","対空","索敵","装備","キラキラ","旗艦レベル＋キラキラ").contains(count),"mission count type");
                if(!count.equals("旗艦レベル＋キラキラ")) require(integer(node,"value",false)>=0,"mission value");
                if(count.equals("装備")) require(node.path("item").isTextual() && !node.path("item").asText().isBlank(),"mission equipment operand");
            }
        } else {
            require(LOGICAL.contains(node.path("operator").asText()) && node.path("conditions").isArray() && !node.path("conditions").isEmpty(),"mission logical predicate");
            for(JsonNode child:node.path("conditions")) mission(child);
        }
        if(node.hasNonNull("greatSuccessCondition")) mission(node.get("greatSuccessCondition"));
    }
    private static int integer(JsonNode parent,String name,boolean optional) {
        if (!parent.has(name) && optional) return 0;
        JsonNode value=parent.path(name);
        require(value.isIntegralNumber() && value.canConvertToInt(), "integer " + name);
        return value.intValue();
    }
    private static void predicates(JsonNode node) {
        if(node.isArray()) {for(JsonNode child:node) predicates(child);return;}
        if(!node.isObject()) return;
        if(node.hasNonNull("operator")) require(Set.of("AND","OR","NAND","NOR","EQ","NE","GE","GT","LE","LT").contains(node.path("operator").asText()),"unknown operator");
        if(node.hasNonNull("order")) require(node.get("order").isIntegralNumber() && node.get("order").asInt()>0,"positive order");
        if(node.hasNonNull("conditions")) require(node.get("conditions").isArray() && !node.get("conditions").isEmpty(),"nonempty conditions");
        var values=node.elements();while(values.hasNext()) predicates(values.next());
    }
    private static void require(boolean valid,String reason) {if(!valid) throw new IllegalArgumentException(reason);}
}
