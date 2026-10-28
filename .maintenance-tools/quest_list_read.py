from pathlib import Path

def new(path, text):
    p=Path(path)
    assert not p.exists(), path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf8')

def edit(path, function):
    p=Path(path);raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n'
    original=raw.decode('utf8').replace('\r\n','\n')
    result=function(original)
    assert result!=original,path
    p.write_bytes(result.replace('\n',ending).encode('utf8'))

def once(text, old, replacement):
    assert text.count(old)==1,(old,text.count(old))
    return text.replace(old,replacement)

new('src/main/java/logbook/plugin/api/QuestListV1.java',r'''package logbook.plugin.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import logbook.core.QuestListReadService;

/**
 * Read-only observed-quest presentation. Copies detach display state from legacy
 * mutable beans; they do not certify atomicity with unsynchronized plugin writers.
 * No expiry calculation, acceptance reset, settings save or file deletion occurs.
 * Cached custom IDs are omitted from this observed list, never removed from storage.
 */
public final class QuestListV1 {
    private QuestListV1() { }
    public static int version() { return 1; }
    public static Listing snapshot() { return QuestListReadService.snapshot(); }
    public static boolean isCustomId(int id) { return id >= 10001 && id <= 10005; }

    public record Listing(List<Quest> quests, List<String> diagnostics) {
        public Listing {
            quests = List.copyOf(quests);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Nullable fields and actual observation time remain nullable, not invented. */
    public record Quest(int no, Integer category, Integer type, Integer state,
            String title, String detail, List<Integer> materials, Integer bonusFlag,
            Integer progressFlag, Integer invalidFlag, Instant observedAt,
            String expire, boolean active) {
        public Quest {
            if (no <= 0) throw new IllegalArgumentException("Positive quest identity required");
            // Some old input lists contain null elements: preserve rather than repair them.
            materials = materials == null ? null : Collections.unmodifiableList(new ArrayList<>(materials));
        }
    }
}
''')

new('src/main/java/logbook/core/QuestListReadService.java',r'''package logbook.core;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestCollection;
import logbook.plugin.api.QuestListV1;
import logbook.plugin.api.QuestListV1.Listing;
import logbook.plugin.api.QuestListV1.Quest;

/** Host adapter only. Never calls AppQuest.toAppQuest, collection.update or Config.store. */
public final class QuestListReadService {
    private QuestListReadService() { }

    public static Listing snapshot() {
        return LogBookCoreServices.readGameState(() -> capture(AppQuestCollection.get().getQuest()));
    }

    static Listing capture(Map<Integer, AppQuest> source) {
        Objects.requireNonNull(source, "quest collection");
        var rows = new ArrayList<Quest>();
        var problems = new ArrayList<String>();
        // Normal sources are ConcurrentSkipListMaps. This copy avoids later membership reads.
        for (var entry : new TreeMap<>(source).entrySet()) {
            int key = entry.getKey();
            if (QuestListV1.isCustomId(key)) continue; // Display projection, NOT state cleanup.
            AppQuest value = entry.getValue();
            try {
                Quest row = copy(value);
                if (row.no() != key) throw new IllegalArgumentException("Map key differs from quest identity");
                rows.add(row);
            } catch (IllegalArgumentException | NullPointerException incomplete) {
                problems.add("Incomplete or inconsistent quest identity: " + key);
            }
        }
        return new Listing(rows, problems);
    }

    /** Compatibility input adapter: identity must be present and consistent. */
    public static Quest copy(AppQuest value) {
        Objects.requireNonNull(value, "quest");
        var raw = Objects.requireNonNull(value.getQuest(), "observed quest");
        Integer id = value.getNo();
        if (id == null || id <= 0 || !id.equals(raw.getNo()))
            throw new IllegalArgumentException("Inconsistent quest identity");
        return new Quest(id, raw.getCategory(), raw.getType(), raw.getState(),
                raw.getTitle(), raw.getDetail(), raw.getGetMaterial(), raw.getBonusFlag(),
                raw.getProgressFlag(), raw.getInvalidFlag(), raw.getObservedAt(),
                value.getExpire(), value.isActive());
    }

    /** Fresh private input for the legacy progress renderer; never creates a live observation. */
    public static AppQuest toLegacy(Quest row) {
        Objects.requireNonNull(row, "quest snapshot");
        var raw = new logbook.bean.QuestList.Quest();
        raw.setNo(row.no()); raw.setCategory(row.category()); raw.setType(row.type());
        raw.setState(row.state()); raw.setTitle(row.title()); raw.setDetail(row.detail());
        raw.setGetMaterial(row.materials() == null ? null : new ArrayList<>(row.materials()));
        raw.setBonusFlag(row.bonusFlag()); raw.setProgressFlag(row.progressFlag());
        raw.setInvalidFlag(row.invalidFlag()); raw.setObservedAt(row.observedAt());
        var result = new AppQuest();
        result.setNo(row.no()); result.setQuest(raw); result.setExpire(row.expire()); result.setActive(row.active());
        return result;
    }
}
''')

def main_controller(text):
    text=once(text,'    private long questHashCode;', '''    private logbook.plugin.api.QuestListV1.Listing displayedQuests;
    private java.util.List<Boolean> displayedCustomFiles;
    private boolean displayedQuestVisibility;''')
    start=text.index('        // コレクション内の不完全なカスタム任務データの残骸を完全にクリア')
    end=text.index('            this.questPane.setVisible(show);',start)
    text=text[:start]+'''        // Capture once for both refresh comparison and rendering; drawing must not mutate saved quests.
        var listing = logbook.plugin.api.QuestListV1.snapshot();
        boolean show = AppConfig.get().isShowQuest();
        var customFiles = new java.util.ArrayList<Boolean>(5);
        for (int no = 10001; no <= 10005; no++) {
            customFiles.add(java.nio.file.Files.exists(java.nio.file.Paths.get("./customquest/" + no + ".json")));
        }
        // Full value equality avoids silent missed refreshes from colliding Java hash codes.
        if (!listing.equals(this.displayedQuests) || !customFiles.equals(this.displayedCustomFiles)
                || show != this.displayedQuestVisibility) {
''' + text[end:]
    text=once(text,'''            questMap.values()
                    .stream()
                    .filter(aq -> aq.getNo() < 10001 || aq.getNo() > 10005)
                    .filter(AppQuest::isActive)''','''            listing.quests().stream()
                    .filter(logbook.plugin.api.QuestListV1.Quest::active)''')
    text=once(text,'''            questMap.values()
                    .stream()
                    .filter(aq -> aq.getNo() < 10001 || aq.getNo() > 10005)
                    .filter(aq -> !aq.isActive())''','''            listing.quests().stream()
                    .filter(aq -> !aq.active())''')
    text=once(text,'            this.questHashCode = newHashCode;', '''            this.displayedQuests = listing;
            this.displayedCustomFiles = java.util.List.copyOf(customFiles);
            this.displayedQuestVisibility = show;''')
    body=text[text.index('    private void quest()'):text.index('    private void checkNotifyMission()')]
    assert 'removeIf' not in body and '.store()' not in body and 'questMap' not in body
    return text
edit('src/main/java/logbook/internal/gui/MainController.java',main_controller)

def quest_pane(text):
    text=once(text,'import logbook.bean.QuestList.Quest;', 'import logbook.plugin.api.QuestListV1.Quest;\nimport logbook.core.QuestListReadService;')
    text=once(text,'    private AppQuest quest;','    private final Quest display;')
    text=once(text,'''    public QuestPane(AppQuest quest) {
        this.quest = quest;''','''    public QuestPane(AppQuest quest) {
        this(QuestListReadService.copy(quest));
    }

    /** Display an already captured immutable row; retain the legacy constructor above. */
    public QuestPane(Quest quest) {
        this.display = java.util.Objects.requireNonNull(quest);''')
    text=text.replace('this.quest.getNo()', 'this.display.no()')
    text=once(text,'            Quest quest = this.quest.getQuest();','            Quest quest = this.display;')
    text=once(text,'switch (quest.getCategory())','switch (quest.category() == null ? 0 : quest.category())')
    text=once(text,'!this.quest.isActive()', '!this.display.active()')
    text=once(text,'switch (quest.getProgressFlag())','switch (quest.progressFlag() == null ? 0 : quest.progressFlag())')
    text=once(text,'quest.getState() == 3','Integer.valueOf(3).equals(quest.state())')
    text=once(text,'            this.name.setText(quest.getTitle());','            this.name.setText(java.util.Objects.toString(quest.title(), "任務 " + quest.no()));')
    text=once(text,'            this.detail.setText(quest.getDetail().replaceAll("<br>", ""));','            this.detail.setText(java.util.Objects.toString(quest.detail(), "").replace("<br>", ""));')
    text=once(text,'int category = quest.getCategory();','int category = quest.category() == null ? 0 : quest.category();')
    text=text.replace('quest.getNo()', 'quest.no()')
    text=text.replace('this.quest.getQuest().getTitle()', 'java.util.Objects.toString(this.display.title(), "任務 " + this.display.no())')
    text=once(text,'((QuestProgress) c).setQuest(this.quest);', '((QuestProgress) c).setQuest(QuestListReadService.toLegacy(this.display));')
    assert 'this.quest' not in text
    # Explicit existing user commands remain distinct; read-only rendering must not call them.
    assert 'Files.deleteIfExists(file)' in text and 'void removeAll(ActionEvent event)' in text
    return text
edit('src/main/java/logbook/internal/gui/QuestPane.java',quest_pane)

new('src/test/java/logbook/core/QuestListReadContractTest.java',r'''package logbook.core;

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
    @Test void_restoredDataDoesNotAcquireFreshObservationEvidence() {
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
'''.replace('void_restoredData','void restoredData'))
print('QUEST_READ_BOUNDARY_APPLIED api=true displayed_cache_never_deleted=true persisted_schema_unchanged=true')
