from pathlib import Path
import re,sys

def new(path,text):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8')

def change(path,fn):
    p=Path(path);raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n'
    old=raw.decode().replace('\r\n','\n');updated=fn(old);assert updated!=old,path
    p.write_bytes(updated.replace('\n',ending).encode())

def once(s,old,new):
    assert s.count(old)==1,(old,s.count(old));return s.replace(old,new)

if sys.argv[1]=='baseline':
    new('src/test/java/logbook/internal/gui/QuestEditorReadBoundaryTest.java',r'''package logbook.internal.gui;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
class QuestEditorReadBoundaryTest {
    @Test void controllerDoesNotReadLiveQuestCollectionOrHostResourceResolver() throws Exception {
        try(var in=QuestConditionEditController.class.getResourceAsStream("QuestConditionEditController.class")) {
            String symbols=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
            for(String type:new String[]{"logbook/bean/AppQuestCollection","logbook/core/LogBookCoreServices","logbook/constants/StatisticsShipTypeGroup"})
                assertFalse(symbols.contains(type),"EDITOR_DIRECT_READ: "+type);
        }
    }
    @Test void formLoadingUsesPublicProjectionNotMutableDomainModel() throws Exception {
        String source=Files.readString(Path.of("src/main/java/logbook/internal/gui/QuestConditionEditController.java"));
        int a=source.indexOf("    private void loadQuestData(");int b=source.indexOf("    private void updateConditionsArea(",a);
        String body=source.substring(a,b);
        assertFalse(body.contains("logbook.bean.AppQuestCondition"),"EDITOR_MUTABLE_FORM_READ");
        assertFalse(body.contains("treeToValue"),"EDITOR_DOMAIN_DECODING");
        assertTrue(body.contains("QuestEditorDataV1.form"));
    }
}
''')
    sys.exit(0)
assert sys.argv[1]=='apply'
new('src/main/java/logbook/plugin/api/QuestEditorDataV1.java',r'''package logbook.plugin.api;

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
''')
new('src/main/java/logbook/core/QuestEditorReadService.java',r'''package logbook.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;
import logbook.bean.AppQuestCondition;
import logbook.constants.StatisticsShipTypeGroup;
import logbook.plugin.api.QuestListV1;
import logbook.plugin.api.QuestEditorDataV1.*;

/** Host-owned editor projection. No collection update, expiry calculation, storage write or GUI dependency. */
public final class QuestEditorReadService {
    private QuestEditorReadService() { }
    public static Selection choices(boolean custom) {
        if(custom)return customs();
        var listing=QuestListV1.snapshot();
        try(var lease=LogBookCoreServices.pinDefinitions()) {
            return select(listing,id->LogBookCoreServices.getQuestResource(id,true)!=null);
        }
    }
    static Selection customs() {
        var result=new ArrayList<Choice>();
        for(int id=10001;id<=10005;id++)result.add(new Choice(id,2,4,"カスタム任務"+(id-10000)));
        return new Selection(result,List.of());
    }
    static Selection select(QuestListV1.Listing listing,IntPredicate bundled) {
        Objects.requireNonNull(listing);Objects.requireNonNull(bundled);
        var eligible=Set.of(2,8,9,10);
        var choices=listing.quests().stream()
                .filter(q->!QuestListV1.isCustomId(q.no())&&q.category()!=null&&eligible.contains(q.category()))
                .sorted(Comparator.comparingInt(QuestListV1.Quest::no))
                .filter(q->!bundled.test(q.no()))
                .map(q->new Choice(q.no(),q.category(),q.type(),q.title())).toList();
        return new Selection(choices,listing.diagnostics());
    }
    public static List<List<String>> shipTypeGroups() {
        return Arrays.stream(StatisticsShipTypeGroup.values()).map(g->List.copyOf(g.getGroup())).toList();
    }
    public static Form form(byte[] capturedDocument) throws IOException {
        Objects.requireNonNull(capturedDocument,"captured document");
        if(capturedDocument.length>2*1024*1024)throw new IOException("Quest document exceeds existing 2MiB bound");
        var tree=QuestDocumentValidation.parse(capturedDocument.clone());
        QuestDocumentValidation.validate(tree);
        try {
            // Same typed projection as the former controller; retain unknown fields in the original tree.
            var mapper=QuestDocumentValidation.mapper().rebuild()
                    .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
            return project(mapper.treeToValue(tree,AppQuestCondition.class));
        } catch(tools.jackson.core.JacksonException|IllegalArgumentException|NullPointerException error) {
            throw new IOException("任務設定を入力欄へ読み込めません。元データは保持してください。",error);
        }
    }
    static Form project(AppQuestCondition value) {
        Objects.requireNonNull(value,"condition");
        var filter=value.getFilter();
        return new Form(value.getResetType(),value.getYearlyResetMonth(),value.getCustomName(),value.getStartDate(),
                filter==null?null:new Filter(filter.getArea(),filter.getDeckIds(),filter.getNoRetreat(),fleet(filter.getFleet())),
                value.getConditions()==null?null:value.getConditions().stream().map(q->new Goal(q.getArea(),q.getCell(),q.getCells(),q.getRank(),q.getCount(),q.isArrival())).toList());
    }
    private static Fleet fleet(AppQuestCondition.FleetCondition value) {
        if(value==null)return null;
        return new Fleet(value.getOperator(),value.getStype(),value.getName(),value.getOrder(),value.getCount(),
                value.getConditions()==null?null:value.getConditions().stream().map(QuestEditorReadService::fleet).toList());
    }
}
''')

def controller(s):
    for line in ('import logbook.constants.StatisticsShipTypeGroup;\n','import logbook.bean.AppQuest;\n','import logbook.bean.AppQuestCollection;\n','import logbook.bean.QuestList.Quest;\n','import logbook.core.LogBookCoreServices;\n'):
        s=once(s,line,'')
    s=once(s,'import logbook.internal.logger.LoggerHolder;', 'import logbook.internal.logger.LoggerHolder;\nimport logbook.plugin.api.QuestEditorDataV1;\nimport logbook.plugin.api.QuestEditorDataV1.Choice;')
    s=re.sub(r'\bAppQuest\b','Choice',s)
    a=s.index('                public String toString(Choice object) {');b=s.index('                @Override\n                public Choice fromString',a)
    s=s[:a]+'''                public String toString(Choice object) {
                    return object == null ? null : object.label();
                }

'''+s[b:]
    s=once(s,'if (n != null && n.getQuest() != null)', 'if (n != null)')
    s=once(s,'        Quest quest = appQuest.getQuest();','        Choice quest = appQuest;')
    s=once(s,'private void loadQuestData(Quest quest)','private void loadQuestData(Choice quest)')
    s=s.replace('appQuest == null || appQuest.getQuest() == null','appQuest == null')
    s=s.replace('appQuest != null && appQuest.getQuest() != null','appQuest != null')
    s=s.replace('selected == null || selected.getQuest() == null ||','selected == null ||')
    s=s.replace('selected.getQuest().getNo() == null || ','')
    for obj in ('appQuest','selected'):
        s=s.replace(obj+'.getQuest().getNo()',obj+'.id()')
        s=s.replace(obj+'.getQuest().getCategory()',obj+'.category()')
    s=s.replace('quest.getNo()','quest.id()')
    s=s.replace('this.getCategoryName(quest.getCategory())','quest.categoryName()')
    a=s.index('            // ResetType (推定)');b=s.index('            this.yearlyResetMonth.setText("");',a)
    s=s[:a]+'''            // Captured metadata supplies defaults; the stored definition still overrides them.
            this.resetType.getSelectionModel().select(quest.defaultResetType());
'''+s[b:]
    s=once(s,'''this.resetType.setItems(FXCollections.observableArrayList(
                    "デイリー", "ウィークリー", "マンスリー", "単発", "クオータリー", "イヤリー"));''','''this.resetType.setItems(FXCollections.observableArrayList(QuestEditorDataV1.resetTypes()));''')
    a=s.index('    private String getCategoryName(');b=s.index('    /**',a)
    s=s[:a]+s[b:]
    s=once(s,'''for (StatisticsShipTypeGroup group : StatisticsShipTypeGroup.values()) {
            sb.append(String.join(", ", group.getGroup())).append("\\n");
        }''','''for (var group : QuestEditorDataV1.shipTypeGroups()) {
            sb.append(String.join(", ", group)).append("\\n");
        }''')
    a=s.index('    private void updateQuestList() {');b=s.index('    private logbook.plugin.api.QuestDocumentsV1.Plan prepareRetirementDocument()',a)
    s=s[:a]+'''    // Isolated FXML tests may supply synthetic choices without altering global collections.
    QuestEditorDataV1.Selection editorChoices(boolean custom) { return QuestEditorDataV1.choices(custom); }

    private void updateQuestList() {
        var selection = this.editorChoices(!this.normalQuestRadio.isSelected());
        this.questSelector.setItems(FXCollections.observableArrayList(selection.choices()));
        this.questSelector.getSelectionModel().clearSelection();
        if (this.removeBtn != null) this.removeBtn.setDisable(true);
        if (this.editNotice != null && !selection.diagnostics().isEmpty())
            this.editNotice.setText(String.join("\\n", selection.diagnostics()));
    }

'''+s[b:]
    a=s.index('    private void loadQuestData(');b=s.index('    private void updateConditionsArea(',a)
    part=s[a:b]
    start=part.index('            tools.jackson.databind.ObjectMapper mapper = QuestEditorDocument.mapper();')
    stop=part.index('            if (condition != null) {',start)
    part=part[:start]+'''            this.originalDocument = QuestEditorDocument.parse(this.loadedBytes);
            var condition = QuestEditorDataV1.form(this.loadedBytes);

'''+part[stop:]
    part=part.replace('logbook.bean.AppQuestCondition.FleetCondition','QuestEditorDataV1.Fleet').replace('logbook.bean.AppQuestCondition.Condition','QuestEditorDataV1.Goal')
    for name in ('ResetType','YearlyResetMonth','CustomName','StartDate','Filter','Conditions','DeckIds','NoRetreat','Area','Fleet','Operator','Stype','Name','Order','Count','Cell','Cells','Rank'):
        part=part.replace('.get'+name+'()', '.'+name[0].lower()+name[1:]+'()')
    part=part.replace('.isArrival()', '.arrival()')
    assert 'logbook.bean.AppQuestCondition' not in part and 'treeToValue' not in part
    s=s[:a]+part+s[b:]
    assert '.getQuest()' not in s and 'AppQuestCollection' not in s and 'LogBookCoreServices' not in s
    assert 'StatisticsShipTypeGroup' not in s and 'QuestDocumentStore' not in s
    return s
change('src/main/java/logbook/internal/gui/QuestConditionEditController.java',controller)

# Preserve all prior assertions; update only the private-control fixture type and ID accessor.
for name in ('ArrivalConditionEditorGuiTest','QuestDocumentCommandsGuiTest'):
    def update(s):
        s=once(s,'import logbook.bean.AppQuest;','import logbook.plugin.api.QuestEditorDataV1.Choice;')
        s=s.replace('ComboBox<AppQuest>','ComboBox<Choice>')
        s=s.replace('.getValue().getNo()', '.getValue().id()')
        assert not re.search(r'\bAppQuest\b',s)
        return s
    change('src/test/java/logbook/internal/gui/'+name+'.java',update)
print('QUEST_EDITOR_READ_APPLIED selector_and_form=true existing_store_and_commands_unchanged=true')
