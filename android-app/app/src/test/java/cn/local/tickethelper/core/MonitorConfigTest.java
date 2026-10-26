package cn.local.tickethelper.core;

import org.junit.Test;
import java.time.Instant;
import java.util.Arrays;
import static org.junit.Assert.*;

public class MonitorConfigTest {
    private MonitorConfig config(String date, String time, int quantity, int budget, long min,long max) {
        return new MonitorConfig("测试演出","杭州",date,time,Arrays.asList(980,1380),quantity,budget,
                Instant.parse("2026-10-23T11:30:00Z"),min,max,MonitorConfig.Mode.ALERT);
    }
    @Test public void acceptsCompleteConfiguration() { assertTrue(config("2026-10-23","19:30",1,1680,3000,6000).validate().isEmpty()); }
    @Test public void acceptsFastAndLegacyIntervalsWithoutRounding() {
        assertTrue(config("2026-10-23","19:30",1,1680,500,1500).validate().isEmpty());
        assertTrue(config("2026-10-23","19:30",1,1680,501,600000).validate().isEmpty());
        assertTrue(config("2026-10-23","19:30",1,1680,5000,10000).validate().isEmpty());
    }
    @Test public void rejectsInvalidCalendarDate() { assertFalse(config("2026-02-30","19:30",1,1680,3000,6000).validate().isEmpty()); }
    @Test public void rejectsInvalidTime() { assertFalse(config("2026-10-23","25:30",1,1680,3000,6000).validate().isEmpty()); }
    @Test public void acceptsTwoTicketsWithSufficientTotalBudget() { assertTrue(config("2026-10-23","19:30",2,3360,3000,6000).validate().isEmpty()); }
    @Test public void rejectsZeroNegativeAndMoreThanTwoTickets() {
        for (int quantity : new int[]{-1,0,3,Integer.MAX_VALUE})
            assertFalse(config("2026-10-23","19:30",quantity,100000,3000,6000).validate().isEmpty());
    }
    @Test public void twoTicketsRequireBudgetForTheHighestAcceptedTier() {
        assertFalse(config("2026-10-23","19:30",2,2759,3000,6000).validate().isEmpty());
        assertTrue(config("2026-10-23","19:30",2,2760,3000,6000).validate().isEmpty());
    }
    @Test public void rejectsOverBudgetTiers() { assertFalse(config("2026-10-23","19:30",1,1000,3000,6000).validate().isEmpty()); }
    @Test public void rejectsSubMinimumReversedOrOverMaximumIntervals() {
        assertFalse(config("2026-10-23","19:30",1,1680,499,6000).validate().isEmpty());
        assertFalse(config("2026-10-23","19:30",1,1680,7000,6000).validate().isEmpty());
        assertFalse(config("2026-10-23","19:30",1,1680,500,600001).validate().isEmpty());
    }
}
