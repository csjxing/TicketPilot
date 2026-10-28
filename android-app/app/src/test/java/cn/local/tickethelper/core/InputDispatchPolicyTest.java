package cn.local.tickethelper.core;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InputDispatchPolicyTest {
    @Test public void onlyCertainlyUnsentTrialOrSelectionCanObtainNewObservation() {
        for (InputDispatchPolicy.Rejection reason : InputDispatchPolicy.Rejection.values()) {
            for (Decision.Action action : Decision.Action.values()) {
                boolean expected = (reason == InputDispatchPolicy.Rejection.PAGE_CONTENT_CHANGED
                        || reason == InputDispatchPolicy.Rejection.PAGE_BOUNDARY_CHANGED)
                        && (action == Decision.Action.PROBE_TIER || action == Decision.Action.SELECT_TIER
                            || action == Decision.Action.SELECT_SESSION);
                assertEquals(action + "/" + reason, expected, InputDispatchPolicy.mayReobserve(
                        action, false, false, "dispatch_guard_rejected", reason));
            }
        }
    }

    @Test public void unknownOrCompletedOutcomeNeverRetriesEvenWithPageChangeReason() {
        for (Decision.Action action : Decision.Action.values()) {
            assertFalse(InputDispatchPolicy.mayReobserve(action, false, true,
                    "dispatch_guard_rejected", InputDispatchPolicy.Rejection.PAGE_CONTENT_CHANGED));
            assertFalse(InputDispatchPolicy.mayReobserve(action, true, false,
                    "dispatch_guard_rejected", InputDispatchPolicy.Rejection.PAGE_CONTENT_CHANGED));
            assertFalse(InputDispatchPolicy.mayReobserve(action, false, false,
                    "client_timeout_outcome_unknown", InputDispatchPolicy.Rejection.PAGE_CONTENT_CHANGED));
            assertFalse(InputDispatchPolicy.mayReobserve(action, false, false,
                    null, InputDispatchPolicy.Rejection.PAGE_CONTENT_CHANGED));
        }
    }

    @Test public void reasonCodesAreClosedAsciiIdentifiers() {
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (InputDispatchPolicy.Rejection reason : InputDispatchPolicy.Rejection.values()) {
            assertTrue(reason.code().matches("[a-z_]{1,64}"));
            assertTrue(codes.add(reason.code()));
        }
    }
}
