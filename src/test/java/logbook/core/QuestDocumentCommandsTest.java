package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import logbook.plugin.api.QuestDocumentsV1;
import logbook.plugin.api.QuestDocumentsV1.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** New disposable directories only. No global Config, core lifecycle, game traffic or private data. */
class QuestDocumentCommandsTest {
    @TempDir Path root;
    static final byte[] A = bytes("/*preserve*/{\"customName\":\"a\",\"unknown\":{\"x\":1},\"conditions\":[]}");
    static final byte[] B = bytes("/*preserve*/{\"customName\":\"b\",\"unknown\":{\"x\":1},\"conditions\":[]}");
    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    Path file(int id) { return root.resolve(id+".json"); }
    Session open(int id) throws IOException { return QuestDocumentsV1.at(root).open(id); }
    Session original() throws IOException { Files.write(file(10001),A,StandardOpenOption.CREATE_NEW);return open(10001); }
    Map<String,String> state() throws IOException {
        var values=new TreeMap<String,String>();
        try(var files=Files.list(root)) {
            for(Path p:files.filter(Files::isRegularFile).toList()) values.put(p.getFileName().toString(),Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        }
        return values;
    }
    @Test void publicContractsExposeNoBeansJsonEngineOrJavafx() {
        assertEquals(1,QuestDocumentsV1.version());
        for(Class<?> type:List.of(QuestDocumentsV1.class,Session.class,Plan.class,Document.class))
            for(var method:type.getDeclaredMethods()) {
                String value=method.toGenericString();assertFalse(value.contains("logbook.bean"));assertFalse(value.contains("javafx"));assertFalse(value.contains("tools.jackson"));
            }
    }
    @Test void absentOpenDoesNotCreateDirectoryOrInventBytes() throws Exception {
        Path missing=root.resolve("new-directory");var session=QuestDocumentsV1.at(missing).open(905);
        assertFalse(session.document().present());assertNull(session.document().bytes());assertNull(session.document().sha256());assertFalse(Files.exists(missing));
    }
    @Test void invalidIdentityAndTraversalAreRejectedBeforeFilesystemAccess() {
        assertThrows(IllegalArgumentException.class,()->open(0));assertThrows(IllegalArgumentException.class,()->open(-1));
        assertThrows(IllegalArgumentException.class,()->QuestDocumentsV1.at(root.resolve("../outside")));
        assertThrows(IllegalArgumentException.class,()->QuestDocumentsV1.at(Path.of("")));
    }
    @Test void bytesAreCopiedAtConstructionAndOnEveryRead() throws Exception {
        var input=A.clone();var d=new Document(1,"label",input);input[0]=0;byte[] output=d.bytes();output[1]=0;
        assertArrayEquals(A,d.bytes());assertEquals(64,d.sha256().length());assertFalse(d.toString().contains("customName"));
        assertEquals(d,new Document(1,"label",A));assertEquals(d.hashCode(),new Document(1,"label",A).hashCode());
    }
    @Test void replacementIsCapturedBeforeConfirmation() throws Exception {
        var s=original();var replacement=B.clone();Plan plan=s.prepareSave(replacement);replacement[0]=0;
        assertEquals(Operation.SAVE,plan.operation());assertEquals(10001,plan.questId());assertTrue(plan.existing());
        assertEquals(s.document().sha256(),plan.expectedSha256());assertNotEquals(plan.expectedSha256(),plan.replacementSha256());
        s.apply(plan,true);assertArrayEquals(B,Files.readAllBytes(file(10001)));
    }
    @Test void unchangedSaveKeepsCommentsAndDoesNotCreateArtifacts() throws Exception {
        var s=original();var before=state();var r=s.apply(s.prepareSave(A),true);
        assertEquals(Outcome.UNCHANGED,r.outcome());assertNull(r.retained());assertEquals(before,state());assertArrayEquals(A,s.document().bytes());
    }
    @Test void changedSaveRetainsVerifiedOriginalAndUnknownFields() throws Exception {
        var s=original();var r=s.apply(s.prepareSave(B),true);
        assertEquals(Outcome.APPLIED,r.outcome());assertArrayEquals(A,Files.readAllBytes(r.retained()));
        assertArrayEquals(B,Files.readAllBytes(file(10001)));assertArrayEquals(B,s.document().bytes());
        assertEquals(1,QuestDocumentValidation.parse(s.document().bytes()).path("unknown").path("x").asInt());
    }
    @Test void newSaveUsesExistingCreateNewActivationAndKeepsPreparedBytes() throws Exception {
        var s=open(905);var r=s.apply(s.prepareSave(A),true);assertEquals(Outcome.APPLIED,r.outcome());assertNull(r.retained());
        assertArrayEquals(A,Files.readAllBytes(file(905)));assertTrue(state().keySet().stream().anyMatch(n->n.startsWith("905.json.pending-")));
    }
    @Test void cancelledSaveDoesNotInspectOrOverwriteAnExternalEdit() throws Exception {
        var s=original();var plan=s.prepareSave(B);Files.writeString(file(10001),"external change");var before=state();
        assertEquals(Outcome.CANCELLED,s.apply(plan,false).outcome());assertEquals(before,state());assertArrayEquals(A,s.document().bytes());
    }
    @Test void cancelledRetirementKeepsAllBytesAndSession() throws Exception {
        var s=original();var before=state();var d=s.document();
        assertEquals(Outcome.CANCELLED,s.apply(s.prepareRetirement(),false).outcome());assertEquals(before,state());assertSame(d,s.document());
    }
    @Test void externalEditRejectsSaveBeforeCreatingBackups() throws Exception {
        var s=original();var plan=s.prepareSave(B);Files.writeString(file(10001),"changed externally");var before=state();
        assertThrows(IOException.class,()->s.apply(plan,true));assertEquals(before,state());assertArrayEquals(A,s.document().bytes());
    }
    @Test void newlyAppearingFileIsNeverReplaced() throws Exception {
        var s=open(10001);var plan=s.prepareSave(B);Files.write(file(10001),A,StandardOpenOption.CREATE_NEW);var before=state();
        assertThrows(IOException.class,()->s.apply(plan,true));assertEquals(before,state());assertFalse(s.document().present());
    }
    @Test void externallyRetiredSourceIsNotRecreatedByAStalePlan() throws Exception {
        var s=original();var plan=s.prepareSave(B);Files.move(file(10001),root.resolve("external-retained.json"));
        assertThrows(IOException.class,()->s.apply(plan,true));assertFalse(Files.exists(file(10001)));assertArrayEquals(A,Files.readAllBytes(root.resolve("external-retained.json")));
    }
    @Test void sameIdentityDifferentSessionCannotApplyThePlan() throws Exception {
        var s=original();var other=open(10001);var before=state();
        assertThrows(IOException.class,()->other.apply(s.prepareSave(B),true));assertEquals(before,state());
    }
    @Test void successfulChangeInvalidatesOlderPreparedActions() throws Exception {
        var s=original();var old=s.prepareRetirement();s.apply(s.prepareSave(B),true);var before=state();
        assertThrows(IOException.class,()->s.apply(old,true));assertEquals(before,state());
    }
    @Test void retirementRetainsBothOriginalAndVerifiedCopyWithoutDeletingData() throws Exception {
        var s=original();var r=s.apply(s.prepareRetirement(),true);assertEquals(Outcome.APPLIED,r.outcome());
        assertFalse(Files.exists(file(10001)));assertFalse(s.document().present());assertArrayEquals(A,Files.readAllBytes(r.retained()));
        var files=state();assertEquals(2,files.size());assertTrue(files.keySet().stream().anyMatch(n->n.startsWith("10001.json.before-remove-")));
        assertTrue(files.values().stream().allMatch(v->v.equals(Base64.getEncoder().encodeToString(A))));
    }
    @Test void absentRetirementIsANoOpWithoutDirectories() throws Exception {
        var s=QuestDocumentsV1.at(root.resolve("absent")).open(10005);var r=s.apply(s.prepareRetirement(),true);
        assertEquals(Outcome.UNCHANGED,r.outcome());assertNull(r.retained());assertFalse(Files.exists(root.resolve("absent")));
    }
    @Test void noncustomRetirementIsRejectedWhileOrdinaryDefinitionSaveRemainsSupported() throws Exception {
        var s=open(905);assertThrows(IOException.class,s::prepareRetirement);s.apply(s.prepareSave(A),true);assertArrayEquals(A,Files.readAllBytes(file(905)));
        assertThrows(IOException.class,open(10006)::prepareRetirement);
    }
    @Test void malformedOriginalMayBeExplicitlyRetiredButBytesRemainUntouched() throws Exception {
        byte[] malformed=bytes("{ broken original with private-looking unknown fields");Files.write(file(10001),malformed,StandardOpenOption.CREATE_NEW);
        var s=open(10001);var r=s.apply(s.prepareRetirement(),true);assertArrayEquals(malformed,Files.readAllBytes(r.retained()));assertEquals(2,state().size());
    }
    @Test void invalidReplacementUsesExactlyTheExistingEditorRules() throws Exception {
        var s=original();var before=state();
        for(String invalid:List.of("{broken","[]","{} {}","{\"a\":1,\"a\":2}",
                "{\"filter\":{\"deckIds\":[1,1]}}","{\"filter\":{\"noRetreat\":2}}",
                "{\"conditions\":[{\"arrival\":true,\"area\":[\"1-6\"],\"count\":0}]}"))
            assertThrows(IOException.class,()->s.prepareSave(bytes(invalid)),invalid);
        assertEquals(before,state());
    }
    @Test void oversizeOrNullReplacementCannotChangeSource() throws Exception {
        var s=original();var before=state();assertThrows(IOException.class,()->s.prepareSave(null));
        assertThrows(IOException.class,()->s.prepareSave(new byte[QuestDocumentsV1.MAX_BYTES+1]));assertEquals(before,state());
    }
    @Test void symlinkedFileIsRejectedAndTargetIsUnchanged() throws Exception {
        Path external=root.resolve("retained.txt");Files.write(external,A,StandardOpenOption.CREATE_NEW);Files.createSymbolicLink(file(10001),external);
        assertThrows(IOException.class,()->open(10001));assertArrayEquals(A,Files.readAllBytes(external));
    }
    @Test void symlinkedDirectoryIsRejectedWithoutFollowingIt() throws Exception {
        Path real=Files.createDirectory(root.resolve("real"));Files.write(real.resolve("10001.json"),A,StandardOpenOption.CREATE_NEW);
        Path link=root.resolve("link");Files.createSymbolicLink(link,real);
        assertThrows(IOException.class,()->QuestDocumentsV1.at(link).open(10001));assertArrayEquals(A,Files.readAllBytes(real.resolve("10001.json")));
    }
    @Test void linkReplacementAfterPreparationIsRejected() throws Exception {
        var s=original();var plan=s.prepareSave(B);Path retained=root.resolve("original-retained.json");Files.move(file(10001),retained);Files.createSymbolicLink(file(10001),retained);
        assertThrows(IOException.class,()->s.apply(plan,true));assertArrayEquals(A,Files.readAllBytes(retained));assertArrayEquals(A,s.document().bytes());
    }
    @Test void concurrentSeparateSessionsDoNotBothOverwriteTheSameOriginal() throws Exception {
        var first=original();var second=open(10001);var p=first.prepareSave(B);var q=second.prepareSave(bytes("{\"customName\":\"c\"}"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();try{first.apply(p,true);return true;}catch(IOException conflict){return false;}});
            var b=pool.submit(()->{start.await();try{second.apply(q,true);return true;}catch(IOException conflict){return false;}});
            start.countDown();assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        }
        assertEquals(2,state().size());assertTrue(state().values().contains(Base64.getEncoder().encodeToString(A)));
    }
    @Test void twoApprovedDirectoriesStayBoundToTheirOwnCapturedTarget() throws Exception {
        var a=original();Path another=Files.createDirectory(root.resolve("another"));Files.write(another.resolve("10001.json"),A,StandardOpenOption.CREATE_NEW);
        var b=QuestDocumentsV1.at(another).open(10001);assertThrows(IOException.class,()->b.apply(a.prepareRetirement(),true));
        assertArrayEquals(A,Files.readAllBytes(file(10001)));assertArrayEquals(A,Files.readAllBytes(another.resolve("10001.json")));
    }
    @Test void readFailureDoesNotFallBackToAFreshEmptyDocument() throws Exception {
        Files.createDirectory(file(10001));assertThrows(IOException.class,()->open(10001));assertTrue(Files.isDirectory(file(10001)));
    }
    @Test void oldSnapshotStaysDetachedAfterSaveAndRetirement() throws Exception {
        var s=original();Document before=s.document();s.apply(s.prepareSave(B),true);s.apply(s.prepareRetirement(),true);
        assertArrayEquals(A,before.bytes());assertTrue(before.present());assertFalse(s.document().present());
    }
}
