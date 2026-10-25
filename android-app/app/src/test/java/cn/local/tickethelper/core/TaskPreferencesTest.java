package cn.local.tickethelper.core;

import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

public class TaskPreferencesTest {
    @Test public void emptyTargetCanChangePreferencesButRemainsInvalidForStart() {
        MonitorConfig blank = new MonitorConfig("", "", "", "", List.of(), 1, 0, null, 5000, 10000, MonitorConfig.Mode.ALERT);
        MonitorConfig paced = TaskPreferences.withPace(blank, 500, 1500);
        MonitorConfig changed = TaskPreferences.withMode(paced, MonitorConfig.Mode.PREPARE);
        assertEquals(500, changed.minIntervalMs); assertEquals(1500, changed.maxIntervalMs);
        assertEquals(MonitorConfig.Mode.PREPARE, changed.mode); assertFalse(changed.validate().isEmpty());
        assertEquals("", changed.eventKeyword); assertNull(changed.deadline); assertEquals(5000, blank.minIntervalMs);
    }

    @Test public void preferencesPreserveSelectedEventAndBudget() {
        MonitorConfig base = new MonitorConfig("测试演出", "杭州", "2026-12-31", "19:30", List.of(980, 1380), 1, 1380,
                Instant.parse("2026-12-31T11:30:00Z"), 5000, 10000, MonitorConfig.Mode.PREPARE);
        MonitorConfig changed = TaskPreferences.withPace(base, 800, 1200);
        assertEquals(base.eventKeyword, changed.eventKeyword); assertEquals(base.city, changed.city);
        assertEquals(base.date, changed.date); assertEquals(base.time, changed.time);
        assertEquals(base.prices, changed.prices); assertEquals(base.quantity, changed.quantity);
        assertEquals(base.maxTotal, changed.maxTotal); assertEquals(base.deadline, changed.deadline);
        assertEquals(base.mode, changed.mode);
    }

    @Test public void invalidIntervalsCannotBeSaved() {
        MonitorConfig blank = new MonitorConfig("", "", "", "", List.of(), 1, 0, null, 5000, 10000, MonitorConfig.Mode.ALERT);
        for (long[] interval : new long[][]{{499, 1000}, {1000, 999}, {1000, 600001}}) {
            try { TaskPreferences.withPace(blank, interval[0], interval[1]); fail("invalid interval accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
