from pathlib import Path

def new(path,text):
    p=Path(path);assert not p.exists(),path
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf8')

def edit(path,fn):
    p=Path(path);raw=p.read_bytes();ending='\r\n' if b'\r\n' in raw else '\n'
    old=raw.decode().replace('\r\n','\n');value=fn(old);assert value!=old,path
    p.write_bytes(value.replace('\n',ending).encode())

def once(s,a,b):
    assert s.count(a)==1,(a,s.count(a));return s.replace(a,b)

new('src/main/java/logbook/plugin/api/QuestLocalFilesV1.java',r'''package logbook.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import logbook.core.QuestLocalFilesService;

/**
 * Read-only captured local presentation, not observed game state or editing commands.
 * Contents are reread on capture, including same-size/same-mtime changes; files are
 * never repaired or saved. This is not an atomic snapshot across external writers.
 */
public final class QuestLocalFilesV1 {
    private QuestLocalFilesV1() { }
    public static int version() { return 1; }
    public static Snapshot snapshot(List<Integer> arsenalQuestIds) {
        return QuestLocalFilesService.snapshot(arsenalQuestIds);
    }
    public static Note note(int questId) { return QuestLocalFilesService.note(questId); }
    public enum Status { MISSING, VALID, INVALID, UNREADABLE }
    public record FileInfo(Status status, String sha256, String diagnostic) {
        public FileInfo { Objects.requireNonNull(status); Objects.requireNonNull(diagnostic); }
        public boolean present() { return status != Status.MISSING; }
        public boolean problem() { return status == Status.INVALID || status == Status.UNREADABLE; }
    }
    public record Custom(int questId, String title, FileInfo file) {
        public Custom {
            if (!QuestListV1.isCustomId(questId)) throw new IllegalArgumentException("Custom quest identity required");
            Objects.requireNonNull(title); Objects.requireNonNull(file);
        }
        /** Local custom rows have no game observation timestamp or fabricated expiry. */
        public QuestListV1.Quest display() {
            return new QuestListV1.Quest(questId,2,4,2,title,"カスタム任務"+(questId-10000),
                    List.of(),0,0,0,null,null,true);
        }
    }
    public record Note(int questId, String comment, boolean discard, FileInfo file) {
        public Note {
            if (questId <= 0) throw new IllegalArgumentException("Positive quest identity required");
            Objects.requireNonNull(comment); Objects.requireNonNull(file);
        }
    }
    public record Snapshot(List<Custom> customs, Map<Integer,Note> notes) {
        public Snapshot { customs=List.copyOf(customs); notes=Map.copyOf(notes); }
    }
}
''')
new('src/main/java/logbook/core/QuestLocalFilesService.java',r'''package logbook.core;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import logbook.bean.AppQuestCondition;
import logbook.bean.ArsenalQuestSetting;
import logbook.internal.json.JsonMappers;
import logbook.internal.util.QuestDocumentStore;
import logbook.plugin.api.QuestLocalFilesV1.*;

/** Local read adapter only: reuse bounded/link-refusing read, never Store.save/remove or Config.store. */
public final class QuestLocalFilesService {
    private QuestLocalFilesService() { }
    public static Snapshot snapshot(List<Integer> ids) {
        return capture(Path.of("customquest"),Path.of("arsenalquest"),ids);
    }
    public static Note note(int id) {
        if(id<=0) throw new IllegalArgumentException("Positive quest identity required");
        return note(Path.of("arsenalquest").resolve(id+".json"),id);
    }
    static Snapshot capture(Path custom,Path arsenal,List<Integer> ids) {
        Objects.requireNonNull(custom);Objects.requireNonNull(arsenal);Objects.requireNonNull(ids);
        // Validate the whole request before reading any source.
        var unique=new TreeSet<Integer>();
        for(Integer id:ids) {
            if(id==null||id<=0) throw new IllegalArgumentException("Positive quest identity required");
            unique.add(id);
        }
        var rows=new ArrayList<Custom>();
        for(int id=10001;id<=10005;id++) rows.add(custom(custom.resolve(id+".json"),id));
        var notes=new LinkedHashMap<Integer,Note>();
        for(int id:unique) notes.put(id,note(arsenal.resolve(id+".json"),id));
        return new Snapshot(rows,notes);
    }
    private static FileInfo info(Status status,byte[] raw,String diagnostic) {
        String sha=null;
        if(raw!=null) try {sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        return new FileInfo(status,sha,diagnostic);
    }
    private static Custom custom(Path path,int id) {
        String fallback="カスタム任務"+(id-10000);
        byte[] raw;
        try {raw=QuestDocumentStore.read(path);}
        catch(IOException|SecurityException refused) {
            return new Custom(id,fallback,info(Status.UNREADABLE,null,"任務定義を読み取れません（リンク・形式・サイズ・権限を確認）。元ファイルは保持しています。"));
        }
        if(raw==null) return new Custom(id,fallback,info(Status.MISSING,null,""));
        try {
            // Preserve the former GUI's typed reader and comment support, including unknown-field refusal.
            var mapper=JsonMappers.builder().enable(tools.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS).build();
            var condition=mapper.readValue(raw,AppQuestCondition.class);
            if(condition==null) throw new IllegalArgumentException("Null definition");
            String name=condition.getCustomName();
            return new Custom(id,name==null||name.trim().isEmpty()?fallback:name,info(Status.VALID,raw,""));
        } catch(tools.jackson.core.JacksonException|IllegalArgumentException invalid) {
            return new Custom(id,fallback,info(Status.INVALID,raw,"任務定義を表示用に読み取れません。元ファイルは保持しています。"));
        }
    }
    private static Note note(Path path,int id) {
        byte[] raw;
        try {raw=QuestDocumentStore.read(path);}
        catch(IOException|SecurityException refused) {
            return new Note(id,"",false,info(Status.UNREADABLE,null,"工廠設定を読み取れません（リンク・形式・サイズ・権限を確認）。元ファイルは保持しています。"));
        }
        if(raw==null) return new Note(id,"",false,info(Status.MISSING,null,""));
        try {
            var setting=JsonMappers.builder().build().readValue(raw,ArsenalQuestSetting.class);
            if(setting==null) throw new IllegalArgumentException("Null setting");
            return new Note(id,Objects.toString(setting.getComment(),""),setting.isDiscard(),info(Status.VALID,raw,""));
        } catch(tools.jackson.core.JacksonException|IllegalArgumentException invalid) {
            return new Note(id,"",false,info(Status.INVALID,raw,"工廠設定を表示用に読み取れません。元ファイルは保持しています。"));
        }
    }
}
''')

def main(s):
    s=once(s,'    private java.util.List<Boolean> displayedCustomFiles;',
           '    private logbook.plugin.api.QuestLocalFilesV1.Snapshot displayedLocalQuestFiles;')
    start=s.index('        var customFiles = new java.util.ArrayList<Boolean>(5);')
    end=s.index('        // Full value equality',start)
    s=s[:start]+'''        var localFiles = logbook.plugin.api.QuestLocalFilesV1.snapshot(listing.quests().stream()
                .filter(row -> Integer.valueOf(6).equals(row.category()) || Integer.valueOf(11).equals(row.category()))
                .map(logbook.plugin.api.QuestListV1.Quest::no).toList());
'''+s[end:]
    s=once(s,'!customFiles.equals(this.displayedCustomFiles)','!localFiles.equals(this.displayedLocalQuestFiles)')
    start=s.index('    private void quest()');end=s.index('    private void checkNotifyMission()',start)
    body=s[start:end]
    assert body.count('.map(QuestPane::new)')==2
    body=body.replace('.map(QuestPane::new)','.map(row -> new QuestPane(row, localFiles.notes().get(row.no())))')
    a=body.index('            // 2. カスタム任務を追加');b=body.index('            // 3. 非受領の任務を追加',a)
    body=body[:a]+'''            // Local rows and notes are rendered from the same captured bytes used for comparison.
            for (var custom : localFiles.customs()) {
                if (custom.file().present() && !visibility.hiddenIds().contains(custom.questId())) {
                    var pane = new QuestPane(custom.display(), null);
                    if (custom.file().problem()) pane.showLocalDiagnostic(custom.file().diagnostic());
                    quest.add(pane);
                }
            }

'''+body[b:]
    body=once(body,'this.displayedCustomFiles = java.util.List.copyOf(customFiles);','this.displayedLocalQuestFiles = localFiles;')
    assert 'java.nio.file' not in body and 'readValue' not in body and 'AppQuest.toAppQuest' not in body
    return s[:start]+body+s[end:]
edit('src/main/java/logbook/internal/gui/MainController.java',main)

def pane(s):
    s=once(s,'    private final Quest display;','    private final Quest display;\n    private final logbook.plugin.api.QuestLocalFilesV1.Note localNote;')
    s=once(s,'''    public QuestPane(Quest quest) {
        this.display = java.util.Objects.requireNonNull(quest);''','''    public QuestPane(Quest quest) {
        this(quest, Integer.valueOf(6).equals(quest.category()) || Integer.valueOf(11).equals(quest.category())
                ? logbook.plugin.api.QuestLocalFilesV1.note(quest.no()) : null);
    }

    /** Render the captured note; do not reopen its file during control construction. */
    public QuestPane(Quest quest, logbook.plugin.api.QuestLocalFilesV1.Note note) {
        this.display = java.util.Objects.requireNonNull(quest);
        if (note != null && note.questId() != quest.no()) throw new IllegalArgumentException("Note identity mismatch");
        this.localNote = note;''')
    a=s.index('            // 工廠任務の備考を表示');b=s.index('            // カスタム任務の場合、検索リンクを表示しない',a)
    s=s[:a]+'''            // Values and diagnostics come from the same bounded read as the refresh comparison.
            if (this.localNote != null) {
                if (!this.localNote.comment().isEmpty()) {
                    Label commentLabel = new Label(this.localNote.comment());
                    commentLabel.setWrapText(true);
                    commentLabel.setStyle("-fx-text-fill: red;");
                    this.infomation.getChildren().add(commentLabel);
                }
                if (this.localNote.discard()) {
                    this.setStyle("-fx-background-color: #ffe0e0;");
                    this.name.setOpacity(0.5);
                    this.name.setStyle("-fx-text-fill: gray;");
                }
                if (this.localNote.file().problem()) showLocalDiagnostic(this.localNote.file().diagnostic());
            }
'''+s[b:]
    needle='    @FXML\n    void remove(ActionEvent event) {'
    s=once(s,needle,'''    void showLocalDiagnostic(String message) {
        Label warning = new Label(message);
        warning.setWrapText(true);
        warning.getStyleClass().add("quest-local-read-warning");
        this.infomation.getChildren().add(warning);
    }

'''+needle)
    assert 'java.nio.file' not in s and 'readValue' not in s
    return s
edit('src/main/java/logbook/internal/gui/QuestPane.java',pane)

new('src/test/java/logbook/core/QuestLocalFilesReadTest.java',r'''package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import logbook.plugin.api.QuestLocalFilesV1;
import logbook.plugin.api.QuestLocalFilesV1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Only new synthetic roots. No global core lifecycle, default data or write command invocation. */
class QuestLocalFilesReadTest {
    @TempDir Path root;
    Path custom() {return root.resolve("customquest");}
    Path arsenal() {return root.resolve("arsenalquest");}
    Snapshot read(Integer...ids){return QuestLocalFilesService.capture(custom(),arsenal(),List.of(ids));}
    Path write(Path dir,int id,String text)throws Exception{Files.createDirectories(dir);return Files.writeString(dir.resolve(id+".json"),text,StandardOpenOption.CREATE_NEW);}
    Custom first(Snapshot s){return s.customs().getFirst();}
    @Test void contractHasNoMutableBeansJsonOrJavafx() {
        assertEquals(1,QuestLocalFilesV1.version());
        for(var type:List.of(QuestLocalFilesV1.class,Snapshot.class,Custom.class,Note.class,FileInfo.class))
            for(var m:type.getDeclaredMethods()) {
                var signature=m.toGenericString();assertFalse(signature.contains("logbook.bean"));assertFalse(signature.contains("tools.jackson"));assertFalse(signature.contains("javafx"));
            }
    }
    @Test void missingDirectoriesRemainMissingAndFiveSlotsAreStable() {
        var s=read(900001);assertEquals(5,s.customs().size());assertFalse(Files.exists(custom()));assertFalse(Files.exists(arsenal()));
        assertTrue(s.customs().stream().allMatch(c->c.file().status()==Status.MISSING));assertEquals(Status.MISSING,s.notes().get(900001).file().status());assertEquals(s,read(900001));
    }
    @Test void commentsAndCustomTitleAreReadWithoutChangingBytes()throws Exception {
        var p=write(custom(),10001,"/*kept*/{\"customName\":\"named\",\"conditions\":[]}");var bytes=Files.readAllBytes(p);
        var c=first(read());assertEquals("named",c.title());assertEquals(Status.VALID,c.file().status());assertEquals(64,c.file().sha256().length());assertArrayEquals(bytes,Files.readAllBytes(p));
    }
    @Test void sameSizeAndSameTimestampCannotHideAChangedName()throws Exception {
        var p=write(custom(),10001,"{\"customName\":\"Aa\"}");var before=read();var time=Files.getLastModifiedTime(p);long size=Files.size(p);
        Files.writeString(p,"{\"customName\":\"BB\"}");Files.setLastModifiedTime(p,time);
        var after=read();assertEquals(size,Files.size(p));assertEquals(time,Files.getLastModifiedTime(p));assertNotEquals(before,after);assertEquals("BB",first(after).title());assertEquals("Aa",first(before).title());
    }
    @Test void unchangedBytesReuseEqualValuesWithoutGeneratedClock()throws Exception {
        write(custom(),10001,"{\"customName\":\"name\"}");assertEquals(read(),read());assertNull(first(read()).display().observedAt());assertNull(first(read()).display().expire());
    }
    @Test void conditionsOnlyChangeIsAlsoDetected()throws Exception {
        var p=write(custom(),10001,"{\"customName\":\"same\",\"conditions\":[]}");var before=read();
        Files.writeString(p,"{\"customName\":\"same\",\"conditions\":[{\"area\":[\"1-1\"],\"count\":1}]}");
        assertNotEquals(before,read());assertEquals(first(before).title(),first(read()).title());
    }
    @Test void blankNameKeepsExistingFallback()throws Exception {
        write(custom(),10001,"{\"customName\":\"  \"}");var c=first(read());assertEquals("カスタム任務1",c.title());assertEquals(Status.VALID,c.file().status());
    }
    @Test void malformedFileRemainsPresentButDiagnosedAndUnchanged()throws Exception {
        var p=write(custom(),10001,"{broken raw original");var c=first(read());assertTrue(c.file().present());assertEquals(Status.INVALID,c.file().status());assertFalse(c.file().diagnostic().isBlank());assertEquals("{broken raw original",Files.readString(p));
    }
    @Test void unknownFieldStillUsesTypedReaderFailureInsteadOfRelaxingParser()throws Exception {
        write(custom(),10001,"{\"customName\":\"not accepted\",\"unknownFutureField\":1}");assertEquals(Status.INVALID,first(read()).file().status());assertEquals("カスタム任務1",first(read()).title());
    }
    @Test void repairedFileIsRecognizedWithoutWritingAnyRepair()throws Exception {
        var p=write(custom(),10001,"{");assertEquals(Status.INVALID,first(read()).file().status());Files.writeString(p,"{\"customName\":\"fixed\"}");assertEquals("fixed",first(read()).title());assertEquals(Status.VALID,first(read()).file().status());
    }
    @Test void nullJsonIsNotInventedAsAValidDefinition()throws Exception {
        write(custom(),10001,"null");assertEquals(Status.INVALID,first(read()).file().status());
    }
    @Test void missingInvalidAndUnreadableAreDifferentStates()throws Exception {
        var missing=first(read());write(custom(),10001,"{");var invalid=first(read());
        Files.move(custom().resolve("10001.json"),root.resolve("retained.json"));Files.createDirectory(custom().resolve("10001.json"));var refused=first(read());
        assertEquals(Status.MISSING,missing.file().status());assertEquals(Status.INVALID,invalid.file().status());assertEquals(Status.UNREADABLE,refused.file().status());assertEquals("{",Files.readString(root.resolve("retained.json")));
    }
    @Test void symlinkedCustomIsRefusedWithoutReadingItsTarget()throws Exception {
        var target=write(root,7,"{\"customName\":\"outside\"}");Files.createDirectories(custom());Files.createSymbolicLink(custom().resolve("10001.json"),target);
        assertEquals(Status.UNREADABLE,first(read()).file().status());assertEquals("{\"customName\":\"outside\"}",Files.readString(target));assertNull(first(read()).file().sha256());
    }
    @Test void symlinkedAncestorIsRefused()throws Exception {
        Path real=Files.createDirectory(root.resolve("real"));write(real,10001,"{}");Files.createSymbolicLink(custom(),real);
        assertEquals(Status.UNREADABLE,first(read()).file().status());assertEquals("{}",Files.readString(real.resolve("10001.json")));
    }
    @Test void oversizedSourceIsRefusedAndPreserved()throws Exception {
        Files.createDirectories(custom());var p=custom().resolve("10001.json");var bytes=new byte[2*1024*1024+1];Files.write(p,bytes,StandardOpenOption.CREATE_NEW);
        assertEquals(Status.UNREADABLE,first(read()).file().status());assertArrayEquals(bytes,Files.readAllBytes(p));
    }
    @Test void onlyExactCustomSlotsAreRead()throws Exception {
        write(custom(),10000,"{}");write(custom(),10006,"{}");write(custom(),10005,"{\"customName\":\"five\"}");
        var s=read();assertEquals(5,s.customs().size());assertEquals(List.of(10001,10002,10003,10004,10005),s.customs().stream().map(Custom::questId).toList());assertEquals("five",s.customs().getLast().title());
    }
    @Test void noteValuesAreCapturedAndSourceRemainsIntact()throws Exception {
        var p=write(arsenal(),900001,"{\"comment\":\"before\",\"discard\":true}");var note=read(900001).notes().get(900001);
        Files.writeString(p,"{\"comment\":\"after\",\"discard\":false}");assertEquals("before",note.comment());assertTrue(note.discard());var newer=read(900001).notes().get(900001);assertEquals("after",newer.comment());assertFalse(newer.discard());
    }
    @Test void noteNullCommentIsDisplayOnlyEmptyAndNotARewrite()throws Exception {
        var p=write(arsenal(),900001,"{\"comment\":null}");assertEquals("",read(900001).notes().get(900001).comment());assertEquals("{\"comment\":null}",Files.readString(p));
    }
    @Test void invalidNoteDoesNotReuseStaleDiscardOrComment()throws Exception {
        write(arsenal(),900001,"{broken}");var n=read(900001).notes().get(900001);assertEquals(Status.INVALID,n.file().status());assertEquals("",n.comment());assertFalse(n.discard());
    }
    @Test void originalArsenalReaderStillRefusesCommentsAndUnknownFields()throws Exception {
        var p=write(arsenal(),900001,"/*not accepted here*/{\"comment\":\"x\"}");assertEquals(Status.INVALID,read(900001).notes().get(900001).file().status());
        Files.writeString(p,"{\"comment\":\"x\",\"unknown\":1}");assertEquals(Status.INVALID,read(900001).notes().get(900001).file().status());
    }
    @Test void onlyRequestedNotesAreReadAndDeduplicated()throws Exception {
        write(arsenal(),900002,"{");var s=read(900001,900001);assertEquals(Set.of(900001),s.notes().keySet());assertEquals(Status.MISSING,s.notes().get(900001).file().status());
    }
    @Test void snapshotsDefensivelyCopyCollections() {
        var list=new ArrayList<Custom>();var map=new HashMap<Integer,Note>();var snapshot=new Snapshot(list,map);list.add(new Custom(10001,"x",new FileInfo(Status.MISSING,null,"")));
        assertTrue(snapshot.customs().isEmpty());assertThrows(UnsupportedOperationException.class,()->snapshot.customs().clear());assertThrows(UnsupportedOperationException.class,()->snapshot.notes().clear());
    }
    @Test void invalidNoteIdsAreRejectedBeforeDirectoryCreation() {
        assertThrows(IllegalArgumentException.class,()->read(0));assertThrows(IllegalArgumentException.class,()->read(-1));assertFalse(Files.exists(custom()));assertFalse(Files.exists(arsenal()));
    }
    @Test void displayRowsRetainCustomDefaultsWithoutNewObservationEvidence()throws Exception {
        write(custom(),10001,"{}");var row=first(read()).display();assertEquals(10001,row.no());assertTrue(row.active());assertEquals(2,row.state());assertEquals(4,row.type());assertTrue(row.materials().isEmpty());assertNull(row.observedAt());assertNull(row.expire());
    }
}
''')
new('src/test/java/logbook/internal/gui/QuestLocalFilesBoundaryTest.java',r'''package logbook.internal.gui;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
class QuestLocalFilesBoundaryTest {
    @Test void listRendererDoesNotOpenParseOrGenerateLocalQuestObservations()throws Exception {
        var source=Files.readString(Path.of("src/main/java/logbook/internal/gui/MainController.java"));
        var body=source.substring(source.indexOf("    private void quest()"),source.indexOf("    private void checkNotifyMission()"));
        assertFalse(body.contains("java.nio.file"));assertFalse(body.contains("readValue"));assertFalse(body.contains("AppQuest.toAppQuest"));assertTrue(body.contains("QuestLocalFilesV1.snapshot"));
    }
    @Test void paneRendersCapturedNotesWithoutFilesystemOrJsonDependencies()throws Exception {
        try(var input=QuestPane.class.getResourceAsStream("QuestPane.class")) {
            var symbols=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(symbols.contains("java/nio/file/Files"));assertFalse(symbols.contains("tools/jackson"));assertFalse(symbols.contains("ArsenalQuestSetting"));assertTrue(symbols.contains("QuestLocalFilesV1"));
        }
    }
}
''')
new('docs/maintenance/quest-local-read-20261029.md','''# 任務のローカル表示資料・内容変更検知 — 2026-10-29

基点: PR68統合2f20fe3926e621d1cf1e7c13a2ec05088966a208。
QuestLocalFilesV1はカスタム5枠と、必要な工廠備考だけを読取り専用の不変値として返す。
MainControllerは同じ取得結果を比較と描画に用い、QuestPaneは取得済み備考を受け取る。
同一パス・同一サイズ・同一更新時刻でも内容ハッシュが変われば次の一覧更新で反映する。
別ファイル群を原子的に読む保証や、外部writerに対するロックを提供するものではない。

既存QuestDocumentStore.readの2MiB上限・リンク拒否を再利用し、save/removeは呼ばない。
Storeと既存の編集コマンド、定義の達成条件判定、保存形式は変更しない。
旧GUIと同じ型付きパーサーを使い、カスタム側だけコメントを許可する。未知フィールドの拒否は維持する。
MISSING/VALID/INVALID/UNREADABLEを区別し、不正な入力も原本のまま保持して診断を表示する。
カスタム行はローカル表示であり、新たなゲーム観測日時や期限を生成しない。

試験は新規合成ディレクトリと隔離CIのみ。基点で同じGUI変更検知試験が失敗すること、
修正版で同サイズ/同時刻の任務名・条件変更、工廠備考と薄色表示、破損・修復、退避/再出現、
不変の設定・受諾キャッシュ、取得後変更からの分離、無変更時再利用を確認する。
最終試験件数・対象head・成果物はPRの実測を参照する。未実行を成功として扱わない。

未完了: 任務編集Selector/フォームの全読取分離、任務UIの機能JAR化、工廠編集の書込契約、
全writer同期、残るP1/P3、全148任務意味照合、未知形式移行、Issue有効化待ち、Mint実機/本人データ/公開。
内容変更検知は定期的な一覧更新時であり、OSファイル監視や無制限のバックグラウンド監視ではない。
サイズ上限は読取ごとで、全ファイルを一つの時刻へ固定する保証はない。

Mint/OS/現用JAR/auto.sh/本人の設定・履歴・plugins・backupsは未アクセス。
原本削除・forcepush・Release/タグ公開・有料AI/API・KQS別repo変更はしない。
''')
print('QUEST_LOCAL_READ_APPLIED no_store_changes=true no_save_format_changes=true')
