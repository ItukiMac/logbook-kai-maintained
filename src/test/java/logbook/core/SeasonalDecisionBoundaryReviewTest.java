package logbook.core;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import logbook.bean.AppQuestCondition;
import logbook.internal.kancolle.QuestCollect;
import org.junit.jupiter.api.Test;

/** A selected edition must still be valid when the actual predicate returns. */
class SeasonalDecisionBoundaryReviewTest {
    @Test void expiryDuringActualCountEvaluationCannotPublishAConfirmedResult() throws Exception {
        var clock = new SeasonalQuestRuleServiceTest.MutableClock(SeasonalQuestRuleServiceTest.NOW);
        var resolution = SeasonalQuestRuleServiceTest.service(clock, SeasonalQuestRuleServiceTest.edition())
                .resolve(840, "eventquest", SeasonalQuestRuleServiceTest.BASE,
                        SeasonalQuestRuleServiceTest.observation(clock.now));
        AppQuestCondition condition = SeasonalQuestRuleServiceTest.JSON.readValue(
                resolution.definition(), AppQuestCondition.class);
        condition.setSeasonalSelection(resolution.selection());
        assertTrue(condition.isRuleUsable());

        var count = new QuestCollect.Count();
        count.getBoss().setS(1);
        QuestCollect collect = mock(QuestCollect.class);
        when(collect.getArea()).thenAnswer(ignored -> {
            // Cross the edition boundary while the real count predicate runs,
            // after the outer availability check but before its result returns.
            clock.now = SeasonalQuestRuleServiceTest.UNTIL;
            return Map.of("1-1", count);
        });

        assertFalse(condition.test(collect), "An expired rule must not publish a completed result");
        assertNull(condition.getResult(), "Expired means unconfirmed rather than ordinary failure");
        assertNull(condition.getConditions().getFirst().getResult());
        assertTrue(condition.getConditions().getFirst().getCurrent().contains("未確定"));
    }
}
