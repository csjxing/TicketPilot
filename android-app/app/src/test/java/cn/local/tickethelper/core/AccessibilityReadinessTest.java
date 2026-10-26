package cn.local.tickethelper.core;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static cn.local.tickethelper.core.AccessibilityReadiness.State.*;

public class AccessibilityReadinessTest {
    @Test public void failedConfigurationReadRemainsUnknownRegardlessOfOtherSignals() {
        for (boolean globallyEnabled : new boolean[]{false, true})
            for (boolean connectedReady : new boolean[]{false, true})
                assertEquals(UNKNOWN, AccessibilityReadiness.evaluate(null, globallyEnabled, connectedReady));
    }

    @Test public void explicitMissingConfigurationIsOffEvenWithAStaleLocalInstance() {
        assertEquals(OFF, AccessibilityReadiness.evaluate(false, true, true));
        assertEquals(OFF, AccessibilityReadiness.evaluate(false, true, false));
        assertEquals(OFF, AccessibilityReadiness.evaluate(false, false, true));
        assertEquals(OFF, AccessibilityReadiness.evaluate(false, false, false));
    }

    @Test public void authorizedServiceWithoutAConnectionIsNotReportedAsOff() {
        assertEquals(ENABLED_DISCONNECTED, AccessibilityReadiness.evaluate(true, true, false));
    }

    @Test public void configuredComponentWithGlobalAccessibilityOffIsNotReady() {
        assertEquals(ENABLED_DISCONNECTED, AccessibilityReadiness.evaluate(true, false, false));
        assertEquals(ENABLED_DISCONNECTED, AccessibilityReadiness.evaluate(true, false, true));
    }

    @Test public void readinessRequiresAllThreeFacts() {
        assertEquals(READY, AccessibilityReadiness.evaluate(true, true, true));
    }

    @Test public void lossOfConnectionDoesNotBecomeALossOfAuthorization() {
        assertEquals(READY, AccessibilityReadiness.evaluate(true, true, true));
        assertEquals(ENABLED_DISCONNECTED, AccessibilityReadiness.evaluate(true, true, false));
        assertEquals(READY, AccessibilityReadiness.evaluate(true, true, true));
    }

    @Test public void revocationOverridesAnEarlierReadyConnection() {
        assertEquals(READY, AccessibilityReadiness.evaluate(true, true, true));
        assertEquals(OFF, AccessibilityReadiness.evaluate(false, true, true));
    }
}
