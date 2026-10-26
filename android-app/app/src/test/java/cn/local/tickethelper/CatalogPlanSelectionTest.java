package cn.local.tickethelper;

import cn.local.tickethelper.catalog.CatalogPlanSelection;
import cn.local.tickethelper.catalog.CatalogProject;
import cn.local.tickethelper.catalog.CatalogSession;
import cn.local.tickethelper.catalog.CatalogTier;
import cn.local.tickethelper.core.MonitorConfig;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CatalogPlanSelectionTest {
    private static final long NOW = Instant.parse("2026-10-27T01:00:00Z").toEpochMilli();
    private static final Set<String> ONE_PRICE = Set.of("tier:1680");

    private CatalogStore.Snapshot snapshot(String query, String city, String date, String time,
                                            List<CatalogTier> tiers, long readAtMs) {
        CatalogProject event = new CatalogProject("project:wenzhou", "温州·谢霆锋演唱会-温州站", city, "2026");
        CatalogSession session = new CatalogSession("session:1031", "2026-10-31 19:00", date, time);
        CatalogStore.update("TIERS", "public prices", query, event, session,
                List.of(), List.of(), tiers, readAtMs);
        return CatalogStore.get();
    }

    private CatalogStore.Snapshot snapshot() {
        return snapshot("谢霆锋", "温州", "2026-10-31", "19:00", List.of(
                new CatalogTier("tier:1380", "1380元", 1380, "OUT_OF_STOCK"),
                new CatalogTier("tier:1680", "1680元", 1680, "OUT_OF_STOCK")), NOW - 1000);
    }

    private MonitorConfig old(MonitorConfig.Mode mode) {
        return new MonitorConfig("旧演出", "杭州", "2026-11-15", "19:00", List.of(980), 1, 980,
                Instant.parse("2026-11-15T11:00:00Z"), 500, 1500, mode);
    }

    @Test public void twoTicketBudgetUsesOneSameTierAndDoesNotClaimInventory() {
        CatalogStore.Snapshot snapshot = snapshot();
        assertNull(CatalogPlanSelection.problem(snapshot, ONE_PRICE, 2, NOW));
        MonitorConfig built = CatalogPlanSelection.buildConfig(snapshot, ONE_PRICE, 2, old(MonitorConfig.Mode.PREPARE), NOW);
        assertEquals(2, built.quantity); assertEquals(3360, built.maxTotal);
        assertEquals(List.of(1680), built.prices); assertEquals("谢霆锋", built.eventKeyword);
        assertEquals("温州", built.city); assertEquals("2026-10-31", built.date);
        assertEquals("19:00", built.time); assertTrue(built.validate().isEmpty());
    }

    @Test public void reminderAndPacingRemainUserPreferences() {
        MonitorConfig built = CatalogPlanSelection.buildConfig(snapshot(), ONE_PRICE, 1, old(MonitorConfig.Mode.ALERT), NOW);
        assertEquals(MonitorConfig.Mode.ALERT, built.mode);
        assertEquals(500, built.minIntervalMs); assertEquals(1500, built.maxIntervalMs);
        assertEquals(1680, built.maxTotal);
    }

    @Test public void changedTargetNeverInheritsFinalSubmissionMode() {
        MonitorConfig built = CatalogPlanSelection.buildConfig(snapshot(), ONE_PRICE, 2, old(MonitorConfig.Mode.LOCK_ORDER), NOW);
        assertEquals(MonitorConfig.Mode.PREPARE, built.mode);
    }

    @Test public void catalogRevisionChangeRejectsEarlierReview() {
        CatalogStore.Snapshot original = snapshot();
        snapshot();
        assertNotNull(CatalogPlanSelection.problem(original, ONE_PRICE, 1, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> CatalogPlanSelection.buildConfig(original, ONE_PRICE, 1, old(MonitorConfig.Mode.PREPARE), NOW));
    }

    @Test public void catalogExpiryIsBoundedAndRejectsFutureTimestamp() {
        CatalogStore.Snapshot snapshot = snapshot();
        assertNull(CatalogPlanSelection.problem(snapshot, ONE_PRICE, 1, snapshot.readAtMs + 300_000));
        assertNotNull(CatalogPlanSelection.problem(snapshot, ONE_PRICE, 1, snapshot.readAtMs + 300_001));
        assertNotNull(CatalogPlanSelection.problem(snapshot, ONE_PRICE, 1, snapshot.readAtMs - 1));
    }

    @Test public void missingReadTimestampAndNonTierStageCannotSave() {
        CatalogStore.Snapshot missing = snapshot("谢霆锋", "温州", "2026-10-31", "19:00",
                List.of(new CatalogTier("tier:1680", "1680元", 1680, "UNKNOWN")), 0);
        assertNotNull(CatalogPlanSelection.problem(missing, ONE_PRICE, 1, NOW));
        CatalogStore.update("BUSY", "reading", "谢霆锋", missing.event, missing.session,
                List.of(), List.of(), missing.tiers, NOW);
        assertNotNull(CatalogPlanSelection.problem(CatalogStore.get(), ONE_PRICE, 1, NOW));
    }

    @Test public void invalidMissingAndPastSessionCannotBecomePlan() {
        for (String[] dateAndTime : List.of(new String[]{"2026-02-30", "19:00"},
                new String[]{"2026-10-31", "24:00"}, new String[]{"2026-10-31", ""},
                new String[]{"2026-10-26", "19:00"})) {
            CatalogStore.Snapshot invalid = snapshot("谢霆锋", "温州", dateAndTime[0], dateAndTime[1],
                    List.of(new CatalogTier("tier:1680", "1680元", 1680, "UNKNOWN")), NOW);
            assertNotNull(CatalogPlanSelection.problem(invalid, ONE_PRICE, 1, NOW));
        }
    }

    @Test public void cityAndNonGenericVisibleKeywordAreRequired() {
        CatalogStore.Snapshot blankCity = snapshot("谢霆锋", "", "2026-10-31", "19:00",
                List.of(new CatalogTier("tier:1680", "1680元", 1680, "UNKNOWN")), NOW);
        assertNotNull(CatalogPlanSelection.problem(blankCity, ONE_PRICE, 1, NOW));
        for (String query : List.of("", "温州", "演唱会", "别的艺人")) {
            CatalogStore.Snapshot wrong = snapshot(query, "温州", "2026-10-31", "19:00",
                    List.of(new CatalogTier("tier:1680", "1680元", 1680, "UNKNOWN")), NOW);
            assertNotNull(CatalogPlanSelection.problem(wrong, ONE_PRICE, 1, NOW));
        }
    }

    @Test public void editedQueryMustStillMatchTheVisibleTitle() {
        CatalogStore.Snapshot snapshot = snapshot();
        assertNotNull(CatalogPlanSelection.problem(snapshot, ONE_PRICE, 1, "其他艺人", NOW));
        assertNull(CatalogPlanSelection.problem(snapshot, ONE_PRICE, 1, "谢霆锋 温州", NOW));
    }

    @Test public void quantityMustBeWithinSupportedRange() {
        CatalogStore.Snapshot snapshot = snapshot();
        assertNotNull(CatalogPlanSelection.problem(snapshot, ONE_PRICE, 0, NOW));
        assertNotNull(CatalogPlanSelection.problem(snapshot, ONE_PRICE, 3, NOW));
    }

    @Test public void emptyUnknownAndPartiallyStalePriceKeysAreRejected() {
        CatalogStore.Snapshot snapshot = snapshot();
        assertNotNull(CatalogPlanSelection.problem(snapshot, Set.of(), 1, NOW));
        assertNotNull(CatalogPlanSelection.problem(snapshot, Set.of("tier:980"), 1, NOW));
        assertNotNull(CatalogPlanSelection.problem(snapshot, Set.of("tier:1680", "tier:980"), 1, NOW));
        assertNotNull(CatalogPlanSelection.problem(snapshot, Set.of(""), 1, NOW));
    }

    @Test public void duplicateKeyAndDuplicatePriceAreAmbiguous() {
        CatalogStore.Snapshot duplicateKey = snapshot("谢霆锋", "温州", "2026-10-31", "19:00", List.of(
                new CatalogTier("tier:1680", "A", 1680, "UNKNOWN"),
                new CatalogTier("tier:1680", "B", 1680, "UNKNOWN")), NOW);
        assertNotNull(CatalogPlanSelection.problem(duplicateKey, ONE_PRICE, 1, NOW));
        CatalogStore.Snapshot duplicatePrice = snapshot("谢霆锋", "温州", "2026-10-31", "19:00", List.of(
                new CatalogTier("a", "A", 1680, "UNKNOWN"),
                new CatalogTier("b", "B", 1680, "UNKNOWN")), NOW);
        assertNotNull(CatalogPlanSelection.problem(duplicatePrice, Set.of("a", "b"), 1, NOW));
    }

    @Test public void selectionRetainsReadOrderAndComputesMaximumTotal() {
        CatalogStore.Snapshot snapshot = snapshot();
        Set<String> reverse = new LinkedHashSet<>(List.of("tier:1680", "tier:1380"));
        MonitorConfig built = CatalogPlanSelection.buildConfig(snapshot, reverse, 2, old(MonitorConfig.Mode.PREPARE), NOW);
        assertEquals(List.of(1380, 1680), built.prices); assertEquals(3360, built.maxTotal);
    }

    @Test public void invalidExistingPacingFailsMonitorConfigValidation() {
        MonitorConfig invalid = new MonitorConfig("旧演出", "杭州", "2026-11-15", "19:00", List.of(980), 1, 980,
                Instant.parse("2026-11-15T11:00:00Z"), 10, 100, MonitorConfig.Mode.PREPARE);
        CatalogStore.Snapshot snapshot = snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> CatalogPlanSelection.buildConfig(snapshot, ONE_PRICE, 1, invalid, NOW));
    }

    @Test public void aggregateBudgetStillMustPassCoreValidation() {
        CatalogStore.Snapshot expensive = snapshot("谢霆锋", "温州", "2026-10-31", "19:00",
                List.of(new CatalogTier("tier:100000", "100000元", 100000, "UNKNOWN")), NOW);
        assertThrows(IllegalArgumentException.class, () -> CatalogPlanSelection.buildConfig(
                expensive, Set.of("tier:100000"), 2, old(MonitorConfig.Mode.PREPARE), NOW));
    }

    @Test public void deadlineUsesVenueZoneAndNotDeviceDefault() {
        assertEquals(Instant.parse("2026-10-31T11:00:00Z"), CatalogPlanSelection.deadline(snapshot()));
    }
}
