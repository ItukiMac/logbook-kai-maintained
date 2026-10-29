package logbook.core;

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
