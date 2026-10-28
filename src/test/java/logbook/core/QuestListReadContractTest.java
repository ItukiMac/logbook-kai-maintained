package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import logbook.bean.AppQuest;
import logbook.plugin.api.QuestListV1;
import org.junit.jupiter.api.Test;

/** Pure adapters only: no global core init/close, config lookup, writer or fake observation clock. */
class QuestListReadContractTest {
    static AppQuest fixture(int id, boolean active) {
        var q = new logbook.bean.QuestList.Quest();
        q.setNo(id); q.setCategory(2); q.setType(4); q.setState(active ? 2 : 1);
        q.setTitle("Aa"); q.setDetail("first<br>second"); q.setGetMaterial(new ArrayList<>(List.of(1,2,3,4)));
        q.setBonusFlag(7); q.setProgressFlag(2); q.setInvalidFlag(0);
        q.setObservedAt(Instant.parse("2026-10-28T01:00:00Z"));
        var result = new AppQuest(); result.setNo(id); result.setQuest(q); result.setExpire("retained expiry"); result.setActive(active);
        return result;
    }
    @Test void versionAndPublicSignaturesExposeOnlyReadValues() {
        assertEquals(1, QuestListV1.version());
        for (var m : QuestListV1.class.getDeclaredMethods()) {
            assertFalse(m.toGenericString().contains("logbook.bean"));
            assertFalse(m.toGenericString().contains("javafx"));
        }
        assertTrue(QuestListV1.Quest.class.isRecord());
        assertTrue(QuestListV1.Listing.class.isRecord());
    }
    @Test void mutableQuestAndMaterialListAreDetached() {
        var raw=fixture(700001,true); var row=QuestListReadService.copy(raw);
        raw.getQuest().setTitle("changed"); raw.getQuest().getGetMaterial().set(0,99); raw.setActive(false);
        assertEquals("Aa",row.title()); assertEquals(List.of(1,2,3,4),row.materials()); assertTrue(row.active());
        assertThrows(UnsupportedOperationException.class,()->row.materials().clear());
    }
    @Test void listingCopiesInputsAndIsImmutable() {
        var input=new ArrayList<>(List.of(QuestListReadService.copy(fixture(1,true))));
        var notes=new ArrayList<>(List.of("diagnostic"));
        var listing=new QuestListV1.Listing(input,notes); input.clear();notes.clear();
        assertEquals(1,listing.quests().size());assertEquals(List.of("diagnostic"),listing.diagnostics());
        assertThrows(UnsupportedOperationException.class,()->listing.quests().clear());
        assertThrows(UnsupportedOperationException.class,()->listing.diagnostics().clear());
    }
    @Test void expiryAndActualObservationTimeAreCopiedExactly() {
        var raw=fixture(1,true);var row=QuestListReadService.copy(raw);
        assertEquals(raw.getQuest().getObservedAt(),row.observedAt());assertEquals("retained expiry",row.expire());
        assertEquals(row,QuestListReadService.copy(QuestListReadService.toLegacy(row)));
    }
    @Test void restoredDataDoesNotAcquireFreshObservationEvidence() {
        var raw=fixture(1,true);raw.getQuest().setObservedAt(null);raw.setExpire(null);
        var row=QuestListReadService.copy(raw);var back=QuestListReadService.toLegacy(row);
        assertNull(row.observedAt());assertNull(back.getQuest().getObservedAt());assertNull(back.getExpire());
    }
    @Test void legacyProgressInputIsADifferentDeepCopyOnEveryCall() {
        var row=QuestListReadService.copy(fixture(1,true));var first=QuestListReadService.toLegacy(row);var second=QuestListReadService.toLegacy(row);
        first.getQuest().setTitle("other");first.getQuest().getGetMaterial().clear();first.setActive(false);
        assertEquals("Aa",second.getQuest().getTitle());assertEquals(4,second.getQuest().getGetMaterial().size());assertTrue(second.isActive());
        assertEquals(4,row.materials().size());assertNotSame(first.getQuest(),second.getQuest());
    }
    @Test void allQuestFlagsSurviveLegacyRoundTrip() {
        var row=QuestListReadService.copy(fixture(1,true));var copy=QuestListReadService.toLegacy(row).getQuest();
        assertEquals(2,copy.getCategory());assertEquals(4,copy.getType());assertEquals(2,copy.getState());
        assertEquals(7,copy.getBonusFlag());assertEquals(2,copy.getProgressFlag());assertEquals(0,copy.getInvalidFlag());
    }
    @Test void nullFieldsAndNullMaterialElementsAreNotSilentlyRepaired() {
        var raw=fixture(1,false);var q=raw.getQuest();q.setTitle(null);q.setDetail(null);q.setCategory(null);
        q.setProgressFlag(null);q.setGetMaterial(Arrays.asList(1,null,3));
        var row=QuestListReadService.copy(raw);assertNull(row.title());assertNull(row.detail());assertNull(row.category());assertNull(row.progressFlag());
        assertEquals(Arrays.asList(1,null,3),row.materials());
        q.setGetMaterial(null);assertNull(QuestListReadService.copy(raw).materials());
    }
    @Test void capturedOrderIsQuestIdOrderRegardlessOfMapIteration() {
        Map<Integer,AppQuest> values=new HashMap<>();values.put(9,fixture(9,false));values.put(1,fixture(1,true));
        assertEquals(List.of(1,9),QuestListReadService.capture(values).quests().stream().map(QuestListV1.Quest::no).toList());
    }
    @Test void customCacheIsFilteredWithoutMutatingSourceMembershipOrValues() {
        var real=fixture(9000,true);var cached=fixture(10001,true);var source=new HashMap<>(Map.of(9000,real,10001,cached));
        var listing=QuestListReadService.capture(source);
        assertEquals(List.of(9000),listing.quests().stream().map(QuestListV1.Quest::no).toList());
        assertEquals(2,source.size());assertSame(cached,source.get(10001));assertTrue(cached.isActive());
    }
    @Test void customRangeBoundariesAreExact() {
        assertFalse(QuestListV1.isCustomId(10000));assertTrue(QuestListV1.isCustomId(10001));
        assertTrue(QuestListV1.isCustomId(10005));assertFalse(QuestListV1.isCustomId(10006));
    }
    @Test void malformedIdentitiesAreDiagnosedInsteadOfAliased() {
        var wrong=fixture(7,true);wrong.getQuest().setNo(8);
        var source=new HashMap<Integer,AppQuest>();source.put(7,wrong);source.put(10,null);source.put(1,fixture(2,true));
        var listing=QuestListReadService.capture(source);assertTrue(listing.quests().isEmpty());assertEquals(3,listing.diagnostics().size());
        assertEquals(3,source.size());assertEquals(8,wrong.getQuest().getNo());
    }
    @Test void incompleteLegacyInputsAreRejectedWithoutInventingIdentity() {
        assertThrows(NullPointerException.class,()->QuestListReadService.copy(null));
        var raw=fixture(1,true);raw.setNo(null);assertThrows(IllegalArgumentException.class,()->QuestListReadService.copy(raw));
        raw.setNo(1);raw.setQuest(null);assertThrows(NullPointerException.class,()->QuestListReadService.copy(raw));
    }
    @Test void sourceMembershipChangesDoNotChangeAnEarlierListing() {
        var source=new HashMap<>(Map.of(1,fixture(1,true)));var first=QuestListReadService.capture(source);
        source.clear();assertEquals(1,first.quests().size());assertTrue(QuestListReadService.capture(source).quests().isEmpty());
    }
    @Test void contentEqualityDistinguishesTheClassicJavaHashCollision() {
        var raw=fixture(1,true);var a=QuestListReadService.capture(Map.of(1,raw));
        raw.getQuest().setTitle("BB");var b=QuestListReadService.capture(Map.of(1,raw));
        assertEquals("Aa".hashCode(),"BB".hashCode());assertEquals(a.hashCode(),b.hashCode());assertNotEquals(a,b);
    }
    @Test void progressAndAcceptanceChangesAreVisibleWithoutChangingIdentity() {
        var raw=fixture(1,true);var before=QuestListReadService.capture(Map.of(1,raw));raw.setActive(false);raw.getQuest().setProgressFlag(1);
        var after=QuestListReadService.capture(Map.of(1,raw));assertNotEquals(before,after);assertFalse(after.quests().getFirst().active());
        assertEquals(1,after.quests().getFirst().progressFlag());
    }
    @Test void emptyInputProducesStableEmptyValuesRatherThanAGeneratedTimestamp() {
        assertEquals(QuestListReadService.capture(Map.of()),QuestListReadService.capture(Map.of()));
        assertTrue(QuestListReadService.capture(Map.of()).diagnostics().isEmpty());
    }
}
