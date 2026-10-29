package logbook.core;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import logbook.bean.AppQuestCondition;
import logbook.constants.StatisticsShipTypeGroup;
import logbook.plugin.api.QuestListV1;
import logbook.plugin.api.QuestEditorDataV1;
import logbook.plugin.api.QuestEditorDataV1.*;
import org.junit.jupiter.api.Test;

/** Pure synthetic values. No config lookup, filesystem writes or global core lifecycle. */
class QuestEditorReadTest {
    static QuestListV1.Quest row(int id,Integer category,Integer type,String title) {
        return new QuestListV1.Quest(id,category,type,2,title,"detail",List.of(),0,0,0,null,"saved expiry",true);
    }
    static Selection select(List<QuestListV1.Quest> rows,java.util.function.IntPredicate bundled) {
        return QuestEditorReadService.select(new QuestListV1.Listing(rows,List.of()),bundled);
    }
    static Form parse(String text)throws Exception {return QuestEditorDataV1.form(text.getBytes(StandardCharsets.UTF_8));}
    @Test void publicContractUsesNoBeansJsonJavafxOrMutableDates() {
        assertEquals(1,QuestEditorDataV1.version());
        for(var type:List.of(QuestEditorDataV1.class,Selection.class,Choice.class,Form.class,Filter.class,Fleet.class,Goal.class))
            for(var method:type.getDeclaredMethods()) {
                String s=method.toGenericString();for(String forbidden:List.of("logbook.bean","tools.jackson","javafx","java.util.Date"))assertFalse(s.contains(forbidden),s);
            }
    }
    @Test void normalSelectionPreservesFourSupportedCategories() {
        var rows=new ArrayList<QuestListV1.Quest>();for(int i=1;i<=11;i++)rows.add(row(i,i,4,"q"+i));
        assertEquals(List.of(2,8,9,10),select(rows,id->false).choices().stream().map(Choice::id).toList());
    }
    @Test void bundledAvailabilityPredicateRetainsOriginalExclusionPolicy() {
        var choices=select(List.of(row(20,2,1,"yes"),row(21,8,2,"bundled")),id->id==21);
        assertEquals(List.of(20),choices.choices().stream().map(Choice::id).toList());
    }
    @Test void onlyEligibleOrdinaryIdsReachResourceLookup() {
        var called=new ArrayList<Integer>();
        select(List.of(row(10001,2,4,"cached"),row(3,null,4,"unknown"),row(8,3,4,"practice"),row(9,2,4,"eligible")),id->{called.add(id);return false;});
        assertEquals(List.of(9),called);
    }
    @Test void orderIsNumericAndIndependentOfSourceIteration() {
        assertEquals(List.of(2,10,90),select(List.of(row(90,2,4,"a"),row(10,8,4,"b"),row(2,9,4,"c")),id->false).choices().stream().map(Choice::id).toList());
    }
    @Test void customSlotsAreFiveStableChoicesWithoutRuntimeInitialization() {
        var choices=QuestEditorDataV1.choices(true).choices();
        assertEquals(List.of(10001,10002,10003,10004,10005),choices.stream().map(Choice::id).toList());
        assertEquals("カスタム任務5",choices.getLast().title());assertTrue(choices.stream().allMatch(Choice::custom));
        assertEquals(QuestEditorDataV1.choices(true),QuestEditorDataV1.choices(true));
    }
    @Test void choicesContainNoObservationOrExpiryFields() {
        assertEquals(List.of("id","category","type","title"),Arrays.stream(Choice.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
    }
    @Test void collectionInputsAreCopiedAndUnmodifiable() {
        var choices=new ArrayList<>(List.of(new Choice(1,2,4,"name")));var notes=new ArrayList<>(List.of("kept"));
        var s=new Selection(choices,notes);choices.clear();notes.clear();assertEquals(1,s.choices().size());assertEquals(List.of("kept"),s.diagnostics());
        assertThrows(UnsupportedOperationException.class,()->s.choices().clear());assertThrows(UnsupportedOperationException.class,()->s.diagnostics().clear());
    }
    @Test void diagnosticMessagesArePreserved() {
        var s=QuestEditorReadService.select(new QuestListV1.Listing(List.of(),List.of("identity mismatch")),id->false);
        assertEquals(List.of("identity mismatch"),s.diagnostics());
    }
    @Test void missingTitlesHaveDisplayOnlyFallback() {
        var c=new Choice(7,2,null,null);assertNull(c.title());assertEquals("7: 任務 7",c.label());assertEquals("単発",c.defaultResetType());
    }
    @Test void positiveIdentityAndExactCustomRange() {
        assertThrows(IllegalArgumentException.class,()->new Choice(0,2,4,""));assertThrows(IllegalArgumentException.class,()->new Choice(-1,2,4,""));
        assertFalse(new Choice(10000,2,4,"").custom());assertTrue(new Choice(10005,2,4,"").custom());assertFalse(new Choice(10006,2,4,"").custom());
    }
    @Test void editorDefaultResetVocabularyIsPreserved() {
        assertEquals(List.of("デイリー","ウィークリー","マンスリー","単発","クオータリー","単発"),
                java.util.stream.IntStream.rangeClosed(1,6).mapToObj(t->new Choice(t,2,t,"").defaultResetType()).toList());
        assertEquals(List.of("デイリー","ウィークリー","マンスリー","単発","クオータリー","イヤリー"),QuestEditorDataV1.resetTypes());
    }
    @Test void originalCategoryLabelsAndUnknownFallbackArePreserved() {
        assertEquals("遠征",new Choice(1,4,4,"").categoryName());assertEquals("出撃(4)",new Choice(2,10,4,"").categoryName());
        assertEquals("その他",new Choice(3,null,4,"").categoryName());assertEquals("その他",new Choice(4,11,4,"").categoryName());
    }
    @Test void shipTypeGroupsAreDetachedImmutableCopiesInOriginalOrder() {
        var groups=QuestEditorDataV1.shipTypeGroups();assertEquals(Arrays.stream(StatisticsShipTypeGroup.values()).map(StatisticsShipTypeGroup::getGroup).toList(),groups);
        assertThrows(UnsupportedOperationException.class,()->groups.clear());assertThrows(UnsupportedOperationException.class,()->groups.getFirst().clear());
    }
    @Test void commentsUnknownFieldsAndOriginalBytesArePreservedAtRead()throws Exception {
        byte[] input="/*keep*/{\"customName\":\"A\",\"future\":{\"x\":1},\"conditions\":[]}".getBytes(StandardCharsets.UTF_8);byte[] before=input.clone();
        var f=QuestEditorDataV1.form(input);assertEquals("A",f.customName());assertArrayEquals(before,input);assertEquals(List.of(),f.conditions());
    }
    @Test void timingFieldsAreCopiedWithoutRecalculation()throws Exception {
        var f=parse("{\"resetType\":\"イヤリー\",\"yearlyResetMonth\":6,\"startDate\":\"unchanged\"}");
        assertEquals("イヤリー",f.resetType());assertEquals(6,f.yearlyResetMonth());assertEquals("unchanged",f.startDate());
    }
    @Test void nullAndAbsentValuesKeepOriginalTypedReaderSemantics()throws Exception {
        var f=parse("{\"conditions\":null}");assertNull(f.conditions());assertNull(f.filter());assertNull(f.resetType());assertNull(f.customName());
        assertEquals(List.of(),parse("{}").conditions());
    }
    @Test void filterValuesAndLegacyIterationOrderAreRetained()throws Exception {
        String json="{\"filter\":{\"area\":[\"7-5\",\"1-6\"],\"deckIds\":[3,1],\"noRetreat\":true}}";
        var f=parse(json).filter();
        var legacy=QuestDocumentValidation.mapper().readValue(json,AppQuestCondition.class).getFilter();
        assertEquals(Set.of("7-5","1-6"),f.area());assertEquals(Set.of(3,1),f.deckIds());assertTrue(f.noRetreat());
        assertEquals(new ArrayList<>(legacy.getArea()),new ArrayList<>(f.area()));
        assertEquals(new ArrayList<>(legacy.getDeckIds()),new ArrayList<>(f.deckIds()));
        assertThrows(UnsupportedOperationException.class,()->f.area().clear());
    }
    @Test void nestedFleetConditionsAreImmutableAndKeepNamesOrderAndOperators()throws Exception {
        var f=parse("{\"filter\":{\"fleet\":{\"operator\":\"OR\",\"conditions\":[{\"stype\":[\"駆逐艦\",\"軽巡洋艦\"],\"operator\":\"GE\",\"count\":2},{\"name\":[\"赤城\"],\"order\":2}]}}}").filter().fleet();
        assertEquals("OR",f.operator());assertEquals(2,f.conditions().getFirst().count());assertEquals(2,f.conditions().get(1).order());
        assertEquals(List.of("駆逐艦","軽巡洋艦"),new ArrayList<>(f.conditions().getFirst().stype()));
        assertThrows(UnsupportedOperationException.class,()->f.conditions().clear());assertThrows(UnsupportedOperationException.class,()->f.conditions().get(1).name().clear());
    }
    @Test void goalFieldsRemainIndependentInsteadOfCollapsingBranches()throws Exception {
        var f=parse("{\"conditions\":[{\"area\":[\"7-5\"],\"cell\":\"Q\",\"rank\":[\"S\",\"A\"],\"count\":3},{\"area\":[\"1-6\"],\"arrival\":true,\"count\":2}]}");
        assertEquals("Q",f.conditions().getFirst().cell());assertEquals(3,f.conditions().getFirst().count());assertFalse(f.conditions().getFirst().arrival());
        assertEquals(Set.of("S","A"),f.conditions().getFirst().rank());assertTrue(f.conditions().get(1).arrival());assertNull(f.conditions().get(1).rank());
    }
    @Test void publicProjectionConstructorsCopyAllCollectionInputs() {
        var areas=new LinkedHashSet<>(List.of("1-1"));var goals=new ArrayList<>(List.of(new Goal(areas,null,null,null,1,false)));
        var f=new Form(null,null,null,null,new Filter(areas,null,null,null),goals);areas.clear();goals.clear();
        assertEquals(Set.of("1-1"),f.filter().area());assertEquals(1,f.conditions().size());assertEquals(Set.of("1-1"),f.conditions().getFirst().area());
        assertThrows(UnsupportedOperationException.class,()->f.conditions().clear());
    }
    @Test void projectionIsDetachedFromMutableHostBean() {
        var bean=new AppQuestCondition();bean.setCustomName("before");var filter=new AppQuestCondition.FilterCondition();filter.setArea(new LinkedHashSet<>(List.of("1-1")));bean.setFilter(filter);
        var projected=QuestEditorReadService.project(bean);bean.setCustomName("after");filter.getArea().clear();
        assertEquals("before",projected.customName());assertEquals(Set.of("1-1"),projected.filter().area());
    }
    @Test void duplicateAndTrailingJsonAreRejected() {
        for(String text:List.of("{\"customName\":\"a\",\"customName\":\"b\"}","{} {}"))assertThrows(IOException.class,()->parse(text));
    }
    @Test void nonObjectAndUnmappableTypedValuesAreRejected() {
        for(String text:List.of("null","[]","{\"conditions\":\"bad\"}","{\"filter\":[]} "))assertThrows(IOException.class,()->parse(text));
    }
    @Test void existingArrivalDeckAndRetreatValidationIsNotWeakened() {
        for(String text:List.of("{\"conditions\":[{\"arrival\":true,\"area\":[\"1-1\"],\"count\":1}]}","{\"filter\":{\"deckIds\":[1,1]}}","{\"filter\":{\"noRetreat\":\"yes\"}}"))assertThrows(IOException.class,()->parse(text));
    }
    @Test void oversizedCapturedDocumentIsRefusedBeforeParsing() {
        assertThrows(IOException.class,()->QuestEditorDataV1.form(new byte[2*1024*1024+1]));
        assertThrows(NullPointerException.class,()->QuestEditorDataV1.form(null));
    }
}
