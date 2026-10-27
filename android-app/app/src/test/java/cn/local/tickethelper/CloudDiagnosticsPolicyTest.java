package cn.local.tickethelper;

import cn.local.tickethelper.sync.DiagnosticReport;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CloudDiagnosticsPolicyTest {
    @Test public void retriesBackOffFromThirtySecondsToFifteenMinutes() {
        assertEquals(0, CloudDiagnostics.backoffMillis(0));
        assertEquals(30000, CloudDiagnostics.backoffMillis(1));
        assertEquals(60000, CloudDiagnostics.backoffMillis(2));
        assertEquals(120000, CloudDiagnostics.backoffMillis(3));
        assertEquals(900000, CloudDiagnostics.backoffMillis(6));
        assertEquals(900000, CloudDiagnostics.backoffMillis(Integer.MAX_VALUE));
    }
    @Test public void schedulingDoesNotDelayAnAlreadyDueAttempt() {
        assertEquals(0, CloudDiagnostics.delayUntil(15000, 16000));
        assertEquals(0, CloudDiagnostics.delayUntil(15000, 15000));
        assertEquals(1000, CloudDiagnostics.delayUntil(15000, 14000));
    }
    @Test public void onlyExactVerifiedPendingEventMapsToVerifiedState() {
        assertEquals(DiagnosticReport.State.PENDING_PAYMENT_VERIFIED, CloudDiagnostics.mappedState("ORDER_PENDING_PAYMENT"));
        assertEquals(DiagnosticReport.State.CHECKOUT, CloudDiagnostics.mappedState("CHECKOUT"));
        assertEquals(DiagnosticReport.State.NEEDS_REVIEW, CloudDiagnostics.mappedState("ORDER_REVIEW"));
        assertEquals(DiagnosticReport.State.NEEDS_REVIEW, CloudDiagnostics.mappedState("ORDER_UNKNOWN"));
        assertNull(CloudDiagnostics.mappedState("ORDER_PENDING"));
        assertNull(CloudDiagnostics.mappedState("official_pending_order_verified"));
    }
    @Test public void arbitraryInputNeverBecomesDiagnosticContent() {
        assertNull(CloudDiagnostics.mappedState("private user input"));
        assertNull(CloudDiagnostics.mappedState("OUT_OF_STOCK:raw_page"));
        assertNull(CloudDiagnostics.mappedState(null));
        assertNull(CloudDiagnostics.mappedState("ACTION_REQUEST"));
        assertNull(CloudDiagnostics.mappedState("SERVICE"));
        assertEquals(DiagnosticReport.State.OUT_OF_STOCK, CloudDiagnostics.mappedState("OUT_OF_STOCK"));
    }
    @Test public void knownReasonsBecomeFixedCodesWithoutChangingState() {
        assertEquals(DiagnosticReport.Code.RESERVATION_ONLY, CloudDiagnostics.mappedCode("UNKNOWN", "reservation_selection_only"));
        assertEquals(DiagnosticReport.Code.READ_FAILED, CloudDiagnostics.mappedCode("UNKNOWN", "page_changed_during_read"));
        assertEquals(DiagnosticReport.Code.FOREGROUND_CHANGED, CloudDiagnostics.mappedCode("UNKNOWN", "foreground_changed"));
        assertEquals(DiagnosticReport.Code.CONNECTED, CloudDiagnostics.mappedCode("SERVICE", "connected_ready"));
        assertEquals(DiagnosticReport.Code.READ_FAILED, CloudDiagnostics.mappedCode("SERVICE", "initialization_failed:IllegalStateException"));
        assertEquals(DiagnosticReport.State.UNKNOWN, CloudDiagnostics.mappedState("UNKNOWN"));
        assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("UNKNOWN", "official_pending_order_verified"));
        assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("UNKNOWN", "explicit_available_label"));
    }
    @Test public void privateAndUnknownReasonTextCannotEnterFixedDiagnosticCodes() {
        for (String reason : new String[]{"示例观演人", "sk-fixture-not-a-real-key", "unrecognized_reason",
                "page_changed_during_read:private", "foreground_changed user@example.test", "initialization_failed:示例观演人",
                "initialization_failed:sk-fixture-not-a-real-key", "initialization_failed:IllegalStateException private", null}) {
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("UNKNOWN", reason));
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("SERVICE", reason));
        }
        assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("unrecognized_state", "reservation_selection_only"));
        assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("UNKNOWN", "connected_ready"));
        assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("UNKNOWN", "initialization_failed:IllegalStateException"));
    }
}
