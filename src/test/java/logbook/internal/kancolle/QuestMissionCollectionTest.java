package logbook.internal.kancolle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import logbook.bean.AppQuest;
import logbook.bean.AppQuestCondition;
import logbook.bean.AppQuestDuration;
import logbook.internal.kancolle.MissionLogs.SimpleMissionLog;
import org.junit.jupiter.api.Test;

class QuestMissionCollectionTest {
    private static SimpleMissionLog row(String result, String name, String date) {
        var row = mock(SimpleMissionLog.class);
        when(row.getResult()).thenReturn(result); when(row.getName()).thenReturn(name);
        when(row.getDateString()).thenReturn(date); return row;
    }
    private static QuestCollect collect(List<SimpleMissionLog> rows, String start) {
        var quest = new AppQuest(); quest.setNo(402);
        var condition = new AppQuestCondition(); condition.setType(AppQuestCondition.Type.遠征); condition.setStartDate(start);
        var duration = mock(AppQuestDuration.class); when(duration.getMissionCondition(quest)).thenReturn(Optional.of(rows));
        try (var durations = mockStatic(AppQuestDuration.class)) {
            durations.when(AppQuestDuration::get).thenReturn(duration);
            return QuestCollect.collect(quest, condition);
        }
    }
    @Test void countsSuccessAndGreatSuccessButNotFailureOrUnknownResults() {
        var rows = new ArrayList<SimpleMissionLog>();
        for (String result : Arrays.asList("成功", "大成功", "失敗", "", null, "unknown"))
            rows.add(row(result, "fixture", "2026-10-26 05:00:00"));
        assertEquals(Map.of("fixture", 2L), collect(rows, null).getMissions());
    }
    @Test void startTimestampIsInclusiveAndMalformedOrOlderRowsDoNotCount() {
        var rows = List.of(row("成功", "fixture", "2026-10-26 04:59:59"),
                row("成功", "fixture", "2026-10-26 05:00:00"),
                row("大成功", "fixture", "2026-10-26 05:00:01"), row("成功", "fixture", "invalid"));
        assertEquals(Map.of("fixture", 2L), collect(rows, "2026-10-26 05:00:00").getMissions());
    }
    @Test void independentMissionNamesRemainSeparateAndUnknownRecordsDoNotStopCollection() {
        var rows = List.of(row("", "東京急行", "2026-10-26 05:00:00"),
                row("成功", "東京急行", "2026-10-26 05:00:01"),
                row("大成功", "東京急行(弐)", "2026-10-26 05:00:02"));
        assertEquals(Map.of("東京急行", 1L, "東京急行(弐)", 1L), collect(rows, null).getMissions());
    }
}
