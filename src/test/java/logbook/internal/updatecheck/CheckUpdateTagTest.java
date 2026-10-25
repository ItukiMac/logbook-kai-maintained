package logbook.internal.updatecheck;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CheckUpdateTagTest {
    @Test
    void applicationTagsAreAccepted() {
        assertTrue(CheckUpdate.TAG_REGIX.matcher("v25.0.8").matches());
        assertTrue(CheckUpdate.TAG_REGIX.matcher("v25.0").matches());
    }

    @Test
    void dataOnlyReleasesCanNeverTriggerApplicationUpdate() {
        assertFalse(CheckUpdate.TAG_REGIX.matcher("data-v2026.10.25").matches());
        assertFalse(CheckUpdate.TAG_REGIX.matcher("data-v26.0.9").matches());
        assertFalse(CheckUpdate.TAG_REGIX.matcher("other-v999.0.0").matches());
        assertFalse(CheckUpdate.TAG_REGIX.matcher("v25.0.8-rc1").matches());
        assertFalse(CheckUpdate.TAG_REGIX.matcher("v25").matches());
    }
}
