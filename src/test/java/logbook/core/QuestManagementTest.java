package logbook.core;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import logbook.core.quest.*;
import logbook.core.quest.QuestLedger.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class QuestManagementTest {
    @TempDir Path root;
    @Test void unknownCatalogRowsAreNotInventedCompletedAndLaterContradictionIsVisible() throws Exception {
        var key=new Key(1,"regular","once");var ledger=new QuestLedger(root);var time=Instant.parse("2026-10-31T00:00:00Z");
        var row=QuestManagement.project(ledger.snapshot(),Map.of(key,"test")).rows().get(0);
        assertEquals(State.UNKNOWN,row.effective());
        ledger.append(0,"manual",key,State.CLAIMED,Source.MANUAL,time,"offline completion");
        ledger.append(1,"observed",key,State.AVAILABLE,Source.GAME,time,"");
        row=QuestManagement.project(ledger.snapshot(),Map.of(key,"test")).rows().get(0);
        assertTrue(row.conflict());assertEquals(State.CLAIMED,row.effective());assertEquals(State.AVAILABLE,row.observed());
        ledger.append(2,"correction",key,State.AVAILABLE,Source.MANUAL,time,"correct earlier mistake");
        row=QuestManagement.project(ledger.snapshot(),Map.of(key,"test")).rows().get(0);
        assertFalse(row.conflict());assertEquals(State.AVAILABLE,row.effective());assertEquals(3,ledger.snapshot().history().size());
    }
}
