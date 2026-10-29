package cn.local.tickethelper.core;

import org.junit.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

public final class SaleTimingTest {
    private static final long START=Instant.parse("2026-10-28T04:00:00Z").toEpochMilli(), ELAPSED=70_000;
    private MonitorConfig legacy() {
        return new MonitorConfig("测试演出","杭州","2026-12-19","19:00",List.of(980,1180),2,2360,
                Instant.parse("2026-12-19T11:00:00Z"),5000,10000,MonitorConfig.Mode.PREPARE);
    }
    private MonitorConfig timed(long saleOffsetMs,int minutes) {
        return legacy().withTiming(Instant.ofEpochMilli(START+saleOffsetMs),minutes);
    }
    @Test public void futureSaleDoesNotConsumeTheThirtyMinuteFollowUpWindow() {
        SaleTiming timing=new SaleTiming(timed(3600_000,30),START,ELAPSED);
        SaleTiming.Tick before=timing.tick(START,ELAPSED);
        assertEquals(SaleTiming.Phase.WAITING_FOR_SALE,before.phase);
        assertFalse(before.mayReadOfficialPage());assertEquals(5400_000,before.remainingMs);
        assertEquals(START+5400_000,before.stopAtMs);
        assertEquals(SaleTiming.Phase.MONITORING,timing.tick(START+3600_000+20_000,ELAPSED+3600_000+20_000).phase);
        assertEquals(SaleTiming.Phase.STOPPED,timing.tick(START+5400_000,ELAPSED+5400_000).phase);
    }
    @Test public void oneMillisecondBeforeSaleWaitsAndTheBoundaryReadsImmediately() {
        SaleTiming timing=new SaleTiming(timed(1000,30),START,ELAPSED);
        SaleTiming.Tick before=timing.tick(START+999,ELAPSED+999);
        assertEquals(SaleTiming.Phase.WAITING_FOR_SALE,before.phase);assertEquals(1,before.nextWakeMs);
        SaleTiming.Tick open=timing.tick(START+1000,ELAPSED+1000);
        assertEquals(SaleTiming.Phase.OPENING,open.phase);assertTrue(open.mayReadOfficialPage());
        assertEquals(SaleTiming.OPENING_READ_DELAY_MS,open.nextWakeMs);
        assertEquals(1800_000,open.remainingMs);
    }
    @Test public void aSaleAlreadyInThePastStartsANewExplicitMonitoringWindow() {
        SaleTiming timing=new SaleTiming(timed(-3600_000,30),START,ELAPSED);
        SaleTiming.Tick tick=timing.tick(START,ELAPSED);
        assertEquals(SaleTiming.Phase.MONITORING,tick.phase);assertTrue(tick.mayReadOfficialPage());
        assertEquals(START+1800_000,tick.stopAtMs);assertEquals(1800_000,tick.remainingMs);
    }
    @Test public void unspecifiedSaleIsNotInferredFromThePerformanceDate() {
        SaleTiming timing=new SaleTiming(legacy().withTiming(null,30),START,ELAPSED);
        SaleTiming.Tick tick=timing.tick(START,ELAPSED);
        assertEquals(SaleTiming.Phase.MONITORING,tick.phase);assertEquals(0,tick.untilSaleMs);
        assertEquals(1800_000,tick.remainingMs);
    }
    @Test public void earliestUserDeadlineAndPerformanceStartAlwaysWin() {
        MonitorConfig userEarly=new MonitorConfig("测试演出","杭州","2026-12-19","19:00",List.of(980),2,2360,
                Instant.ofEpochMilli(START+10_000),500,1500,MonitorConfig.Mode.PREPARE,null,30);
        assertEquals(START+10_000,SaleTiming.effectiveStopAtMs(userEarly,START));
        MonitorConfig tooLate=new MonitorConfig("测试演出","杭州","2026-10-28","12:20",List.of(980),2,2360,
                Instant.ofEpochMilli(START+7200_000),500,1500,MonitorConfig.Mode.PREPARE,null,30);
        assertEquals(START+1200_000,SaleTiming.effectiveStopAtMs(tooLate,START));
        SaleTiming tick=new SaleTiming(tooLate,START,ELAPSED);
        assertEquals(SaleTiming.StopReason.DEADLINE_REACHED,tick.tick(START+1200_000,ELAPSED+1200_000).stopReason);
    }
    @Test public void movingTheWallClockBackCannotExtendTheWatch() {
        SaleTiming timing=new SaleTiming(legacy().withTiming(null,30),START,ELAPSED);
        SaleTiming.Tick tick=timing.tick(START-3600_000,ELAPSED+1800_000);
        assertEquals(SaleTiming.Phase.STOPPED,tick.phase);assertEquals(SaleTiming.StopReason.DEADLINE_REACHED,tick.stopReason);
        assertEquals(SaleTiming.StopReason.INVALID_CONFIGURATION,timing.tick(START,ELAPSED-1).stopReason);
    }
    @Test public void legacySchedulesKeepTheirDeadlineAndSavedActionIntervals() {
        MonitorConfig config=legacy();assertNull(config.saleAt);assertEquals(0,config.followUpMinutes);assertFalse(config.timingSpecified);
        assertEquals(config.deadline.toEpochMilli(),SaleTiming.effectiveStopAtMs(config,START));
        MonitorConfig timed=config.withTiming(Instant.ofEpochMilli(START+3600_000),30);
        assertEquals(5000,timed.minIntervalMs);assertEquals(10000,timed.maxIntervalMs);
        assertEquals(config.mode,timed.mode);assertEquals(config.maxTotal,timed.maxTotal);assertEquals(config.prices,timed.prices);
    }
    @Test public void invalidOpeningDatesAndWindowBoundsNeverStartAWatch() {
        MonitorConfig afterPerformance=legacy().withTiming(legacy().eventStartsAt(),30);
        assertFalse(afterPerformance.validate().isEmpty());
        assertEquals(SaleTiming.StopReason.INVALID_CONFIGURATION,new SaleTiming(afterPerformance,START,ELAPSED).tick(START,ELAPSED).stopReason);
        assertFalse(legacy().withTiming(null,-1).validate().isEmpty());assertFalse(legacy().withTiming(null,1441).validate().isEmpty());
        assertTrue(legacy().withTiming(null,1440).validate().isEmpty());
        MonitorConfig deadlineBeforeOpening=new MonitorConfig("测试演出","杭州","2026-12-19","19:00",List.of(980),2,2360,
                Instant.ofEpochMilli(START+1000),500,1500,MonitorConfig.Mode.PREPARE,Instant.ofEpochMilli(START+2000),30);
        assertFalse(deadlineBeforeOpening.validate().isEmpty());
    }
    @Test public void legacySameSessionEditsRetainTimingButNewTargetsAndExplicitClearDoNot() {
        MonitorConfig old=timed(3600_000,30),same=legacy().resolveLegacyTiming(old);
        assertEquals(old.saleAt,same.saleAt);assertEquals(30,same.followUpMinutes);
        MonitorConfig different=new MonitorConfig("别的演出","杭州","2026-12-19","19:00",List.of(980),2,2360,
                old.deadline,5000,10000,MonitorConfig.Mode.PREPARE).resolveLegacyTiming(old);
        assertNull(different.saleAt);assertEquals(0,different.followUpMinutes);
        MonitorConfig cleared=legacy().withTiming(null,0).resolveLegacyTiming(old);
        assertNull(cleared.saleAt);assertEquals(0,cleared.followUpMinutes);
    }
    @Test public void finalHundredMillisecondsUseShortBoundedWakeups() {
        assertEquals(10, SaleTiming.waitWakeMs(100));
        assertEquals(10, SaleTiming.waitWakeMs(11));
        assertEquals(7, SaleTiming.waitWakeMs(7));
        assertEquals(50, SaleTiming.waitWakeMs(101));
        SaleTiming timing=new SaleTiming(timed(1007,30),START,ELAPSED);
        assertFalse(timing.tick(START+1006,ELAPSED+1006).mayReadOfficialPage());
        assertTrue(timing.tick(START+1007,ELAPSED+1007).mayReadOfficialPage());
    }
    @Test public void wakeupsAreBoundedAndNeverOvershootAnOpeningBoundary() {
        for(long remain:new long[]{1,49,50,51,999,1000,1001,4999,5000,5001,60000,60001,86_400_000}) {
            long delay=SaleTiming.waitWakeMs(remain);assertTrue(delay>0);assertTrue(delay<=remain);assertTrue(delay<=1000);
        }
        assertEquals(0,SaleTiming.waitWakeMs(0));assertEquals(80,SaleTiming.eventReadDelayMs());
        assertEquals(100,SaleTiming.afterForwardInputReadDelayMs());
        assertEquals(0,SaleTiming.actionWaitMs(true,1000,6000));assertEquals(5000,SaleTiming.actionWaitMs(false,1000,6000));
        assertEquals(1,SaleTiming.actionWaitMs(false,6000,1000));
    }
}
