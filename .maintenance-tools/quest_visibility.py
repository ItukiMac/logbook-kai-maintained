from pathlib import Path

def new(path,text):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8')
def edit(path,fn):
    p=Path(path);raw=p.read_bytes();end='\r\n' if b'\r\n' in raw else '\n'
    before=raw.decode().replace('\r\n','\n');after=fn(before);assert before!=after,path
    p.write_bytes(after.replace('\n',end).encode())
def once(s,old,new):
    assert s.count(old)==1,(old,s.count(old));return s.replace(old,new)

new('src/main/java/logbook/plugin/api/QuestVisibilityV1.java',r'''package logbook.plugin.api;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import logbook.core.QuestVisibilityService;

/**
 * Reversible, session-only presentation commands. NEVER deletes a quest, changes
 * its acceptance/history, frees a custom slot, writes Config, or sends a game request.
 * Hidden IDs stay hidden until explicitly restored or this JVM ends, even if the
 * game reports new progress. This is separate from editing/deleting a definition.
 * Capture the plan before asking the user; apply it only after explicit confirmation.
 * The boolean is a caller contract, not authentication of a user's identity.
 */
public final class QuestVisibilityV1 {
    private QuestVisibilityV1() { }
    public static int version() { return 1; }
    public static State snapshot() { return QuestVisibilityService.snapshot(); }
    public static Plan prepareHide(Collection<Integer> ids) { return QuestVisibilityService.prepareHide(ids); }
    public static Plan prepareRestoreAll() { return QuestVisibilityService.prepareRestoreAll(); }
    public static Result apply(Plan plan, boolean confirmed) { return QuestVisibilityService.apply(plan, confirmed); }
    public enum Action { HIDE, RESTORE }
    public enum Outcome { APPLIED, UNCHANGED, CANCELLED, CONFLICT }
    public record State(long revision, Set<Integer> hiddenIds) {
        public State {
            if (revision < 0) throw new IllegalArgumentException("Negative visibility revision");
            hiddenIds = copy(hiddenIds);
        }
    }
    public record Plan(State expected, Action action, Set<Integer> questIds) {
        public Plan {
            Objects.requireNonNull(expected); Objects.requireNonNull(action);
            questIds = copy(questIds);
        }
    }
    public record Result(Outcome outcome, State state) {
        public Result { Objects.requireNonNull(outcome); Objects.requireNonNull(state); }
        public boolean accepted() { return outcome == Outcome.APPLIED || outcome == Outcome.UNCHANGED; }
    }
    public static Set<Integer> copy(Collection<Integer> ids) {
        Objects.requireNonNull(ids);
        if (ids.size() > 10000) throw new IllegalArgumentException("Too many quest IDs");
        var result = new TreeSet<Integer>();
        for (Integer id : ids) {
            if (id == null || id <= 0) throw new IllegalArgumentException("Positive quest IDs required");
            result.add(id);
        }
        return Collections.unmodifiableSet(result);
    }
}
''')
new('src/main/java/logbook/core/QuestVisibilityService.java',r'''package logbook.core;

import java.util.Collection;
import java.util.TreeSet;
import logbook.plugin.api.QuestVisibilityV1;
import logbook.plugin.api.QuestVisibilityV1.*;

/** Owns only an in-memory display policy. No legacy data, filesystem or core lifecycle access. */
public final class QuestVisibilityService {
    private static final Session SESSION = new Session();
    private QuestVisibilityService() { }
    public static State snapshot() { return SESSION.snapshot(); }
    public static Plan prepareHide(Collection<Integer> ids) { return SESSION.prepareHide(ids); }
    public static Plan prepareRestoreAll() { return SESSION.prepareRestoreAll(); }
    public static Result apply(Plan plan, boolean confirmed) { return SESSION.apply(plan, confirmed); }

    /** An isolated owner for pure tests; not a public way to reset the live session. */
    static final class Session {
        private State state = new State(0, java.util.Set.of());
        synchronized State snapshot() { return state; }
        synchronized Plan prepareHide(Collection<Integer> ids) {
            return new Plan(state, Action.HIDE, QuestVisibilityV1.copy(ids));
        }
        synchronized Plan prepareRestoreAll() {
            return new Plan(state, Action.RESTORE, state.hiddenIds());
        }
        synchronized Result apply(Plan plan, boolean confirmed) {
            java.util.Objects.requireNonNull(plan);
            if (!confirmed) return new Result(Outcome.CANCELLED, state);
            if (!state.equals(plan.expected())) return new Result(Outcome.CONFLICT, state);
            var next = new TreeSet<>(state.hiddenIds());
            if (plan.action() == Action.HIDE) next.addAll(plan.questIds());
            else next.removeAll(plan.questIds());
            if (next.equals(state.hiddenIds())) return new Result(Outcome.UNCHANGED, state);
            // Validate/copy before committing either revision or membership.
            State committed = new State(Math.incrementExact(state.revision()), next);
            state = committed;
            return new Result(Outcome.APPLIED, committed);
        }
    }
}
''')
new('src/main/java/logbook/internal/gui/QuestVisibilityUi.java',r'''package logbook.internal.gui;

import java.util.List;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import logbook.plugin.api.QuestListV1.Quest;
import logbook.plugin.api.QuestVisibilityV1;
import logbook.plugin.api.QuestVisibilityV1.Plan;
import logbook.plugin.api.QuestVisibilityV1.State;

/** Confirmation and reversible presentation only; no quest or file mutation. */
final class QuestVisibilityUi {
    static final EventType<Event> CHANGED = new EventType<>(Event.ANY, "QUEST_VISIBILITY_CHANGED_V1");
    private static final String RETAINED = "定義・受諾履歴・キャッシュ・カスタム任務ファイルは削除しません。\n"
            + "この起動中だけ一覧から隠します。再表示またはアプリ再起動で戻ります。\n"
            + "カスタム任務の枠は空きません。";
    private QuestVisibilityUi() { }
    static void hide(Node source, List<Quest> rows) {
        if (rows.isEmpty()) return;
        Plan plan = QuestVisibilityV1.prepareHide(rows.stream().map(Quest::no).toList());
        String targets = rows.stream().map(q -> q.no() + " : " + java.util.Objects.toString(q.title(), "名称未記録"))
                .collect(java.util.stream.Collectors.joining("\n"));
        confirm(source, plan, "この起動中だけ非表示", "対象: " + rows.size() + "件\n" + targets + "\n\n" + RETAINED);
    }
    static HBox banner(State state) {
        Label label = new Label("非表示 " + state.hiddenIds().size() + "件（この起動中のみ・データ保持）");
        label.setWrapText(true);
        Hyperlink restore = new Hyperlink("すべて再表示");
        restore.setId("quest-visibility-restore");
        restore.setOnAction(event -> {
            Plan plan = QuestVisibilityV1.prepareRestoreAll();
            confirm(restore, plan, "一覧へ再表示", "対象ID: " + plan.questIds()
                    + "\n非表示設定だけを取り消します。定義・履歴・ファイルは変更しません。");
        });
        HBox banner = new HBox(6, label, restore);
        banner.setId("quest-visibility-banner");
        return banner;
    }
    private static void confirm(Node source, Plan plan, String operation, String content) {
        ButtonType apply = new ButtonType(operation, ButtonData.OK_DONE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, content, apply, ButtonType.CANCEL);
        dialog.setTitle("任務一覧の表示"); dialog.setHeaderText(operation);
        if (source.getScene() != null) dialog.initOwner(source.getScene().getWindow());
        boolean confirmed = dialog.showAndWait().filter(apply::equals).isPresent();
        var result = QuestVisibilityV1.apply(plan, confirmed);
        if (result.outcome() == QuestVisibilityV1.Outcome.CONFLICT) {
            Alert conflict = new Alert(Alert.AlertType.WARNING,
                    "確認中に非表示状態が変わりました。変更は適用していません。もう一度対象を確認してください。", ButtonType.OK);
            if (source.getScene() != null) conflict.initOwner(source.getScene().getWindow());
            conflict.setTitle("任務一覧の状態変更"); conflict.showAndWait();
        }
        // Also refresh after a conflict, but never silently retry an outdated command.
        if (result.accepted() || result.outcome() == QuestVisibilityV1.Outcome.CONFLICT)
            source.fireEvent(new Event(CHANGED));
    }
}
''')

def pane(s):
    s=once(s,'import logbook.bean.AppQuestCollection;\n','')
    start=s.index('    @FXML\n    void remove(ActionEvent event)')
    stop=s.index('    @FXML\n    void search(ActionEvent event)',start)
    s=s[:start]+'''    @FXML
    void remove(ActionEvent event) {
        QuestVisibilityUi.hide(this, java.util.List.of(this.display));
    }

    @FXML
    void removeAll(ActionEvent event) {
        // Capture only the displayed rows. Do not clear the underlying quest collection.
        var parent = getParent();
        var rows = parent == null ? java.util.List.of(this.display)
                : parent.getChildrenUnmodifiable().stream().filter(QuestPane.class::isInstance)
                    .map(QuestPane.class::cast).map(row -> row.display).toList();
        QuestVisibilityUi.hide(this, rows);
    }

'''+s[stop:]
    s=once(s,'new MenuItem("全て除去")','new MenuItem("表示中の任務をすべて非表示（この起動中）")')
    assert 'deleteIfExists' not in s and 'AppQuestCollection' not in s
    return s
edit('src/main/java/logbook/internal/gui/QuestPane.java',pane)

def main(s):
    s=once(s,'    private boolean displayedQuestVisibility;', '''    private boolean displayedQuestVisibility;
    private logbook.plugin.api.QuestVisibilityV1.State displayedHiddenQuests;
    private boolean questVisibilityListenerInstalled;''')
    s=once(s,'''    private void quest() {
        // Capture once''','''    private void quest() {
        if (!this.questVisibilityListenerInstalled) {
            this.questbox.addEventHandler(QuestVisibilityUi.CHANGED, event -> { this.quest(); event.consume(); });
            this.questVisibilityListenerInstalled = true;
        }
        var visibility = logbook.plugin.api.QuestVisibilityV1.snapshot();
        // Capture once''')
    s=once(s,'                || show != this.displayedQuestVisibility) {','                || show != this.displayedQuestVisibility || !visibility.equals(this.displayedHiddenQuests)) {')
    s=once(s,'''            quest.clear();
            
            // 1. 受領中''','''            quest.clear();
            if (!visibility.hiddenIds().isEmpty()) quest.add(QuestVisibilityUi.banner(visibility));

            // 1. 受領中''')
    assert s.count('            listing.quests().stream()')==2
    s=s.replace('            listing.quests().stream()','            listing.quests().stream().filter(row -> !visibility.hiddenIds().contains(row.no()))')
    start=s.index('    private void quest()');end=s.index('    private void checkNotifyMission()',start)
    section=s[start:end]
    section=once(section,'                if (java.nio.file.Files.exists(file)) {','                if (java.nio.file.Files.exists(file) && !visibility.hiddenIds().contains(no)) {')
    section=once(section,'            this.displayedQuestVisibility = show;','            this.displayedQuestVisibility = show;\n            this.displayedHiddenQuests = visibility;')
    return s[:start]+section+s[end:]
edit('src/main/java/logbook/internal/gui/MainController.java',main)

def fxml(s):
    s=once(s,'text="除去"','text="非表示"');s=once(s,'layoutX="32.0"','layoutX="48.0"');s=once(s,'layoutX="64.0"','layoutX="80.0"');return s
edit('src/main/resources/logbook/gui/quest.fxml',fxml)

new('src/test/java/logbook/core/QuestVisibilityCommandTest.java',r'''package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import logbook.plugin.api.QuestVisibilityV1;
import logbook.plugin.api.QuestVisibilityV1.*;
import org.junit.jupiter.api.Test;

/** Isolated sessions only: no live singleton, Config, filesystem or core init/close. */
class QuestVisibilityCommandTest {
    @Test void versionAndApiHaveNoLegacyBeanOrFilesystemParameters() {
        assertEquals(1,QuestVisibilityV1.version());
        for(var method:QuestVisibilityV1.class.getDeclaredMethods()) {
            assertFalse(method.toGenericString().contains("logbook.bean"));
            assertFalse(method.toGenericString().contains("java.nio.file"));
            assertFalse(method.toGenericString().contains("javafx"));
        }
    }
    @Test void freshSessionHasNoHiddenIds() {var s=new QuestVisibilityService.Session();assertEquals(new State(0,Set.of()),s.snapshot());}
    @Test void snapshotIsSortedAndDetached() {
        var input=new HashSet<>(Set.of(3,1));var state=new State(2,input);input.clear();
        assertEquals(List.of(1,3),List.copyOf(state.hiddenIds()));assertThrows(UnsupportedOperationException.class,()->state.hiddenIds().clear());
    }
    @Test void planCopiesItsTargets() {var ids=new HashSet<>(Set.of(1));var plan=new Plan(new State(0,Set.of()),Action.HIDE,ids);ids.clear();assertEquals(Set.of(1),plan.questIds());}
    @Test void invalidIdsAreRejectedBeforeChangingState() {
        var s=new QuestVisibilityService.Session();
        for(var ids:List.of(Arrays.asList((Integer)null),List.of(0),List.of(-1)))assertThrows(IllegalArgumentException.class,()->s.prepareHide(ids));
        assertEquals(0,s.snapshot().revision());
    }
    @Test void negativeRevisionAndNullActionAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new State(-1,Set.of()));
        assertThrows(NullPointerException.class,()->new Plan(new State(0,Set.of()),null,Set.of(1)));
    }
    @Test void cancelDoesNotChangeState() {
        var s=new QuestVisibilityService.Session();var before=s.snapshot();var r=s.apply(s.prepareHide(List.of(1)),false);
        assertEquals(Outcome.CANCELLED,r.outcome());assertSame(before,s.snapshot());assertFalse(r.accepted());
    }
    @Test void confirmedSingleHideIsReversible() {
        var s=new QuestVisibilityService.Session();var r=s.apply(s.prepareHide(List.of(10001)),true);
        assertTrue(r.accepted());assertEquals(Set.of(10001),r.state().hiddenIds());
        assertEquals(Outcome.APPLIED,s.apply(s.prepareRestoreAll(),true).outcome());assertTrue(s.snapshot().hiddenIds().isEmpty());
    }
    @Test void batchTargetsAreAppliedTogetherWithoutChangingInput() {
        var s=new QuestVisibilityService.Session();var ids=new ArrayList<>(List.of(3,1,3));var p=s.prepareHide(ids);
        var r=s.apply(p,true);assertEquals(Set.of(1,3),r.state().hiddenIds());assertEquals(List.of(3,1,3),ids);
    }
    @Test void stalePlanRejectsTheWholeBatch() {
        var s=new QuestVisibilityService.Session();var stale=s.prepareHide(List.of(1,2));
        s.apply(s.prepareHide(List.of(3)),true);var r=s.apply(stale,true);
        assertEquals(Outcome.CONFLICT,r.outcome());assertEquals(Set.of(3),s.snapshot().hiddenIds());
    }
    @Test void repeatedOldPlanCannotApplyTwice() {
        var s=new QuestVisibilityService.Session();var plan=s.prepareHide(List.of(1));s.apply(plan,true);
        assertEquals(Outcome.CONFLICT,s.apply(plan,true).outcome());assertEquals(1,s.snapshot().revision());
    }
    @Test void unchangedRequestDoesNotAdvanceRevision() {
        var s=new QuestVisibilityService.Session();s.apply(s.prepareHide(List.of(1)),true);var before=s.snapshot();
        assertEquals(Outcome.UNCHANGED,s.apply(s.prepareHide(List.of(1)),true).outcome());assertSame(before,s.snapshot());
    }
    @Test void cancellingStalePlanDoesNotConflictOrModifyAnything() {
        var s=new QuestVisibilityService.Session();var p=s.prepareHide(List.of(1));s.apply(s.prepareHide(List.of(2)),true);
        assertEquals(Outcome.CANCELLED,s.apply(p,false).outcome());assertEquals(Set.of(2),s.snapshot().hiddenIds());
    }
    @Test void restoreCannotUndoANewerHideWithoutReconfirmation() {
        var s=new QuestVisibilityService.Session();s.apply(s.prepareHide(List.of(1)),true);var old=s.prepareRestoreAll();
        s.apply(s.prepareHide(List.of(2)),true);assertEquals(Outcome.CONFLICT,s.apply(old,true).outcome());assertEquals(Set.of(1,2),s.snapshot().hiddenIds());
    }
    @Test void revisionPreventsAnAbaReuseAfterRestore() {
        var s=new QuestVisibilityService.Session();var initial=s.prepareHide(List.of(4));s.apply(initial,true);s.apply(s.prepareRestoreAll(),true);
        assertEquals(Outcome.CONFLICT,s.apply(initial,true).outcome());assertEquals(2,s.snapshot().revision());
    }
    @Test void oldSnapshotDoesNotChangeWithSession() {
        var s=new QuestVisibilityService.Session();var before=s.snapshot();s.apply(s.prepareHide(List.of(1)),true);assertTrue(before.hiddenIds().isEmpty());
    }
    @Test void emptyCommandsAreNoOps() {
        var s=new QuestVisibilityService.Session();assertEquals(Outcome.UNCHANGED,s.apply(s.prepareRestoreAll(),true).outcome());
        assertEquals(Outcome.UNCHANGED,s.apply(s.prepareHide(List.of()),true).outcome());assertEquals(0,s.snapshot().revision());
    }
    @Test void resetIsOnlyANewSessionNotAStateFile() {
        var s=new QuestVisibilityService.Session();s.apply(s.prepareHide(List.of(1)),true);
        assertTrue(new QuestVisibilityService.Session().snapshot().hiddenIds().isEmpty());assertEquals(Set.of(1),s.snapshot().hiddenIds());
    }
    @Test void oversizedPlanFailsClosed() {
        var s=new QuestVisibilityService.Session();var ids=java.util.stream.IntStream.rangeClosed(1,10001).boxed().toList();
        assertThrows(IllegalArgumentException.class,()->s.prepareHide(ids));assertTrue(s.snapshot().hiddenIds().isEmpty());
    }
    @Test void oversizedUnionDoesNotPartiallyCommit() {
        var s=new QuestVisibilityService.Session();s.apply(s.prepareHide(java.util.stream.IntStream.rangeClosed(1,10000).boxed().toList()),true);
        var before=s.snapshot();assertThrows(IllegalArgumentException.class,()->s.apply(s.prepareHide(List.of(10001)),true));assertSame(before,s.snapshot());
    }
    @Test void concurrentConfirmedPlansHaveExactlyOneWinner() throws Exception {
        var s=new QuestVisibilityService.Session();var first=s.prepareHide(List.of(1));var second=s.prepareHide(List.of(2));
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=executor.submit(()->{start.await();return s.apply(first,true).outcome();});
            var b=executor.submit(()->{start.await();return s.apply(second,true).outcome();});start.countDown();
            assertEquals(Set.of(Outcome.APPLIED,Outcome.CONFLICT),Set.of(a.get(3,TimeUnit.SECONDS),b.get(3,TimeUnit.SECONDS)));
        }
        assertEquals(1,s.snapshot().hiddenIds().size());assertEquals(1,s.snapshot().revision());
    }
}
''')
new('src/test/java/logbook/internal/gui/QuestVisibilityBoundaryTest.java',r'''package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class QuestVisibilityBoundaryTest {
    @Test void questPaneCannotDeleteFilesOrMutateTheQuestCollection() throws Exception {
        try(var in=QuestPane.class.getResourceAsStream("QuestPane.class")) {
            assertNotNull(in);String symbols=new String(in.readAllBytes(),StandardCharsets.ISO_8859_1);
            assertFalse(symbols.contains("deleteIfExists"));assertFalse(symbols.contains("logbook/bean/AppQuestCollection"));
            assertTrue(symbols.contains("logbook/internal/gui/QuestVisibilityUi"));
        }
    }
    @Test void commandApiIsPresentAndFxmlNoLongerPromisesDeletion() throws Exception {
        try(var in=getClass().getClassLoader().getResourceAsStream("logbook/gui/quest.fxml")) {
            assertNotNull(in);var fxml=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(fxml.contains("text=\"非表示\""));assertFalse(fxml.contains("text=\"除去\""));
        }
    }
}
''')
print('QUEST_VISIBILITY_APPLIED no_storage_format_change=true no_quest_deletion=true session_only=true')
