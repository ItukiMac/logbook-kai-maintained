package logbook.plugin.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import logbook.core.QuestEditorReadService;

/**
 * Immutable editor read values, not game observations or write commands.
 * Form is a projection of supported controls, NOT a replacement document:
 * callers must retain the original bytes for unknown-field-preserving saves.
 */
public final class QuestEditorDataV1 {
    private QuestEditorDataV1() { }
    public static int version() { return 1; }
    public static Selection choices(boolean custom) { return QuestEditorReadService.choices(custom); }
    public static Form form(byte[] capturedDocument) throws IOException { return QuestEditorReadService.form(capturedDocument); }
    public static List<List<String>> shipTypeGroups() { return QuestEditorReadService.shipTypeGroups(); }
    public static List<String> resetTypes() { return List.of("デイリー","ウィークリー","マンスリー","単発","クオータリー","イヤリー"); }

    public record Choice(int id,Integer category,Integer type,String title) {
        public Choice { if(id<=0)throw new IllegalArgumentException("Positive quest identity required"); }
        public boolean custom() { return QuestListV1.isCustomId(id); }
        public String label() { return id+": "+Objects.toString(title,"任務 "+id); }
        public String categoryName() {
            return switch(category==null?0:category) {
                case 1 -> "編成"; case 2 -> "出撃"; case 3 -> "演習"; case 4 -> "遠征";
                case 5 -> "補給/入渠"; case 6 -> "工廠"; case 7 -> "改装";
                case 8 -> "出撃(2)"; case 9 -> "出撃(3)"; case 10 -> "出撃(4)";
                default -> "その他";
            };
        }
        /** Existing editor defaults, not definition-aware reset or expiry calculation. */
        public String defaultResetType() {
            return switch(type==null?4:type) {
                case 1 -> "デイリー";case 2 -> "ウィークリー";case 3 -> "マンスリー";
                case 5 -> "クオータリー";default -> "単発";
            };
        }
    }
    public record Selection(List<Choice> choices,List<String> diagnostics) {
        public Selection { choices=List.copyOf(choices);diagnostics=List.copyOf(diagnostics); }
    }
    public record Form(String resetType,Integer yearlyResetMonth,String customName,String startDate,
            Filter filter,List<Goal> conditions) {
        public Form { conditions=copyList(conditions); }
    }
    public record Filter(Set<String> area,Set<Integer> deckIds,Boolean noRetreat,Fleet fleet) {
        public Filter { area=copySet(area);deckIds=copySet(deckIds); }
    }
    public record Fleet(String operator,Set<String> stype,Set<String> name,Integer order,Integer count,List<Fleet> conditions) {
        public Fleet { stype=copySet(stype);name=copySet(name);conditions=copyList(conditions); }
    }
    public record Goal(Set<String> area,String cell,Set<String> cells,Set<String> rank,Integer count,boolean arrival) {
        public Goal { area=copySet(area);cells=copySet(cells);rank=copySet(rank); }
    }
    private static <T> List<T> copyList(List<T> values) {
        return values==null?null:Collections.unmodifiableList(new ArrayList<>(values));
    }
    private static <T> Set<T> copySet(Set<T> values) {
        return values==null?null:Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }
}
