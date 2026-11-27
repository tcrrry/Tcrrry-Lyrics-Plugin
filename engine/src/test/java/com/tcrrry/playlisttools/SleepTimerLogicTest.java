package com.tcrrry.playlisttools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.tcrrry.lyrics.engine.ui.HorizontalMinuteDialView;
import org.junit.Test;

public class SleepTimerLogicTest {
    @Test public void parseCustomMinutesValidatesBoundsAndWhitespace() {
        assertEquals(1, HardenedSleepTimer.parseCustomMinutes("1"));
        assertEquals(25, HardenedSleepTimer.parseCustomMinutes(" 25 "));
        assertEquals(1440, HardenedSleepTimer.parseCustomMinutes("1440"));
        assertEquals(-1, HardenedSleepTimer.parseCustomMinutes("0"));
        assertEquals(-1, HardenedSleepTimer.parseCustomMinutes("-5"));
        assertEquals(-1, HardenedSleepTimer.parseCustomMinutes("1441"));
        assertEquals(-1, HardenedSleepTimer.parseCustomMinutes("abc"));
        assertEquals(-1, HardenedSleepTimer.parseCustomMinutes(""));
        assertEquals(-1, HardenedSleepTimer.parseCustomMinutes(null));
    }

    @Test public void formatRemainingFormatsMinutesAndHours() {
        assertEquals("00:00", HardenedSleepTimer.formatRemaining(0L));
        assertEquals("00:00", HardenedSleepTimer.formatRemaining(-500L));
        assertEquals("00:59", HardenedSleepTimer.formatRemaining(59_000L));
        assertEquals("15:00", HardenedSleepTimer.formatRemaining(900_000L));
        assertEquals("1:05:30", HardenedSleepTimer.formatRemaining(3_930_000L));
    }

    @Test public void computeRemainingAndTriggerDetectDeadline() {
        assertEquals(0L, HardenedSleepTimer.computeRemainingMs(0L, 1000L));
        assertEquals(5000L, HardenedSleepTimer.computeRemainingMs(15_000L, 10_000L));
        assertEquals(0L, HardenedSleepTimer.computeRemainingMs(15_000L, 16_000L));

        assertFalse(HardenedSleepTimer.shouldTriggerStop(0L, 10_000L));
        assertFalse(HardenedSleepTimer.shouldTriggerStop(15_000L, 14_999L));
        assertTrue(HardenedSleepTimer.shouldTriggerStop(15_000L, 15_000L));
        assertTrue(HardenedSleepTimer.shouldTriggerStop(15_000L, 15_500L));
    }

    @Test public void pinnedRecentLabelFormatsActiveAndIdleStates() {
        assertEquals(
                "最近自定义：35 分钟后",
                HardenedSleepTimer.formatRecentPinnedLabel(35, false, 0L));
        assertEquals(
                "最近自定义：35 分钟（剩余 12:34）",
                HardenedSleepTimer.formatRecentPinnedLabel(35, true, 754_000L));
    }

    @Test public void horizontalDialHelpersClampFormatAndMapOffsets() {
        assertEquals(1, HorizontalMinuteDialView.Companion.clampMinutes(0));
        assertEquals(45, HorizontalMinuteDialView.Companion.clampMinutes(45));
        assertEquals(240, HorizontalMinuteDialView.Companion.clampMinutes(999));

        assertEquals("", HorizontalMinuteDialView.Companion.formatDurationDetail(25));
        assertEquals("1 小时", HorizontalMinuteDialView.Companion.formatDurationDetail(60));
        assertEquals("1 小时 35 分钟", HorizontalMinuteDialView.Companion.formatDurationDetail(95));

        assertEquals(1, HorizontalMinuteDialView.Companion.offsetToMinutes(0f, 14f));
        assertEquals(21, HorizontalMinuteDialView.Companion.offsetToMinutes(20 * 14f, 14f));
    }
}
