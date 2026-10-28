package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.MonitorConfig;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic authorization facts only; no claim of a production checkout or real attendee. */
public final class LockAuthorizationTimingTest {
    private static final long NOW=Instant.parse("2026-10-28T04:00:00Z").toEpochMilli();
    private MonitorConfig legacy() {
        return new MonitorConfig("合成演唱会","杭州","2026-12-19","19:00",List.of(980),2,1960,
                Instant.ofEpochMilli(NOW+7200_000),500,1500,MonitorConfig.Mode.LOCK_ORDER);
    }
    private LockAuthorization grant(MonitorConfig config) {
        String viewers=LockAuthorization.viewerBindingsHash(List.of(
                new ViewerMatcher.RequestedViewer("测试甲","1234"),
                new ViewerMatcher.RequestedViewer("测试乙","5678")));
        return new LockAuthorization("synthetic-timing-grant",config,"杭州·合成演唱会-杭州站",viewers,NOW,NOW+7200_000);
    }
    @Test public void changingOnlyTheOpeningTimeInvalidatesAnEarlierGrant() {
        MonitorConfig original=legacy().withTiming(Instant.ofEpochMilli(NOW+3600_000),30);
        LockAuthorization allowed=grant(original);
        assertTrue(allowed.validFor(original,NOW));
        MonitorConfig changed=original.withTiming(Instant.ofEpochMilli(NOW+3660_000),30);
        assertTrue(changed.validate().isEmpty());
        assertNotEquals(allowed.configFingerprint,LockAuthorization.configFingerprint(changed));
        assertFalse(allowed.validFor(changed,NOW));
    }
    @Test public void changingOnlyTheFollowUpWindowInvalidatesAnEarlierGrant() {
        MonitorConfig original=legacy().withTiming(null,30);LockAuthorization allowed=grant(original);
        assertTrue(allowed.validFor(original,NOW));
        MonitorConfig changed=original.withTiming(null,60);
        assertTrue(changed.validate().isEmpty());assertFalse(allowed.validFor(changed,NOW));
        assertFalse(allowed.validFor(original.withTiming(null,0),NOW));
    }
    @Test public void addingOrRemovingAScheduleInvalidatesAnEarlierGrant() {
        MonitorConfig legacy=legacy();LockAuthorization allowed=grant(legacy);
        assertTrue(allowed.validFor(legacy,NOW));
        assertFalse(allowed.validFor(legacy.withTiming(null,30),NOW));
        assertFalse(allowed.validFor(legacy.withTiming(Instant.ofEpochMilli(NOW+3600_000),0),NOW));
        MonitorConfig scheduled=legacy.withTiming(Instant.ofEpochMilli(NOW+3600_000),30);
        assertFalse(grant(scheduled).validFor(legacy,NOW));
    }
    @Test public void equivalentLegacySemanticsIgnoreTheConstructorMarkerAndKeepTheOldDigest() {
        MonitorConfig old=legacy(),explicit=old.withTiming(null,0);
        assertFalse(old.timingSpecified);assertTrue(explicit.timingSpecified);
        String oldFacts=CheckoutIdentity.part(old.eventKeyword)+CheckoutIdentity.part(old.city)
                +CheckoutIdentity.part(old.date)+CheckoutIdentity.part(old.time)+CheckoutIdentity.part(old.prices.toString())
                +"|"+old.quantity+"|"+old.maxTotal+"|"+old.deadline+"|"+old.minIntervalMs+"|"+old.maxIntervalMs+"|"+old.mode;
        assertEquals(CheckoutIdentity.hash(oldFacts),LockAuthorization.configFingerprint(old));
        assertEquals(LockAuthorization.configFingerprint(old),LockAuthorization.configFingerprint(explicit));
        assertTrue(grant(old).validFor(explicit,NOW));
    }
}
