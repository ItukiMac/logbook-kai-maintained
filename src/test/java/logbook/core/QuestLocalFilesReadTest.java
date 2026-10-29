package logbook.core;

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
