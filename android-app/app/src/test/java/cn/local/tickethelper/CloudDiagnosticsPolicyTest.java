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
    @Test public void recoveryEventsStayClosedAndDoNotInventAnInventoryState() {
        assertEquals(DiagnosticReport.Code.READ_RETRY, CloudDiagnostics.mappedCode("READ_RECOVERY", "read_recovery_waiting"));
        assertEquals(DiagnosticReport.Code.READ_RECOVERED, CloudDiagnostics.mappedCode("READ_RECOVERY", "read_content_revalidated"));
        assertNull(CloudDiagnostics.mappedState("READ_RECOVERY"));
        assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("READ_RECOVERY", "read_content_revalidated private"));
        assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("READ_RECOVERY", "official_pending_order_verified"));
    }
    @Test public void localSalePhasesAndOneReentryStaySeparateFromInventoryState() {
        String[] phases = {"waiting_for_sale", "opening", "monitoring", "stopped", "reservation_back_to_detail_once"};
        DiagnosticReport.Code[] codes = {DiagnosticReport.Code.SALE_WAITING, DiagnosticReport.Code.SALE_OPENING,
                DiagnosticReport.Code.SALE_MONITORING, DiagnosticReport.Code.SALE_STOPPED, DiagnosticReport.Code.SALE_RESERVATION_REENTRY};
        for (int i = 0; i < phases.length; i++) {
            assertEquals(codes[i], CloudDiagnostics.mappedCode("SALE_TIMING", phases[i]));
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("SALE_TIMING", phases[i] + " private"));
        }
        assertNull(CloudDiagnostics.mappedState("SALE_TIMING"));
        for (String reason : new String[]{null, "OPENING", "official_pending_order_verified", "explicit_available_label", "sk-synthetic-private", "x".repeat(10000)})
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("SALE_TIMING", reason));
    }
    @Test public void dispatchedQuantityInputIsAnInputFactAndDoesNotProveSelectionOrAnOrder() {
        assertEquals(DiagnosticReport.Code.QUANTITY_INCREASE_DISPATCHED, CloudDiagnostics.mappedCode("ACTION", "increase_quantity_dispatched"));
        assertNull(CloudDiagnostics.mappedState("ACTION"));
        for (String reason : new String[]{null, "increase_quantity_dispatched private", "increase_quantity_dispatched\nviewer", "official_pending_order_verified", "select_tier_dispatched"})
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("ACTION", reason));
        assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("UNKNOWN", "increase_quantity_dispatched"));
    }
    @Test public void detailScrollProgressStaysExactWithoutInventingAStablePageOrStock() {
        String[] details = {"scroll_proposed_once", "scroll_command_completed"};
        DiagnosticReport.Code[] codes = {DiagnosticReport.Code.DETAIL_SCROLL_PROPOSED,
                DiagnosticReport.Code.DETAIL_SCROLL_COMMAND_COMPLETED};
        for (int i = 0; i < details.length; i++) {
            assertEquals(codes[i], CloudDiagnostics.mappedCode("DETAIL_SETTLE", details[i]));
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("DETAIL_SETTLE", details[i] + " private"));
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("UNKNOWN", details[i]));
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("ACTION", details[i]));
        }
        assertNull(CloudDiagnostics.mappedState("DETAIL_SETTLE"));
        for (String detail : new String[]{null, "SCROLL_COMMAND_COMPLETED", "scroll_command_completed\nviewer",
                "explicit_available_label", "official_pending_order_verified", "x".repeat(10000)})
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("DETAIL_SETTLE", detail));
    }
    @Test public void detailScrollFailuresAreClosedToActualUnknownOrBlockedOutcomes() {
        String[] reasons = {"detail_scroll_anchor_unverified", "detail_scroll_input_unconfirmed"};
        DiagnosticReport.Code[] codes = {DiagnosticReport.Code.DETAIL_SCROLL_ANCHOR_UNVERIFIED,
                DiagnosticReport.Code.DETAIL_SCROLL_INPUT_UNCONFIRMED};
        for (int i = 0; i < reasons.length; i++) {
            for (String state : new String[]{"UNKNOWN", "BLOCKED"}) {
                assertEquals(codes[i], CloudDiagnostics.mappedCode(state, reasons[i]));
                assertEquals(DiagnosticReport.Code.valueOf(state), CloudDiagnostics.mappedCode(state, reasons[i] + ":private"));
            }
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("SERVICE", reasons[i]));
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("DETAIL_SETTLE", reasons[i]));
            assertEquals(DiagnosticReport.Code.IN_STOCK, CloudDiagnostics.mappedCode("IN_STOCK", reasons[i]));
        }
        assertEquals(DiagnosticReport.State.UNKNOWN, CloudDiagnostics.mappedState("UNKNOWN"));
        assertEquals(DiagnosticReport.State.BLOCKED, CloudDiagnostics.mappedState("BLOCKED"));
    }
    @Test public void quantityFailureReasonsAreExactAndOnlyValidForUnknownOutcomes() {
        String[] reasons = {"quantity_target_unverified", "quantity_transition_page_changed", "quantity_transition_unverified", "quantity_input_unconfirmed"};
        DiagnosticReport.Code[] codes = {DiagnosticReport.Code.QUANTITY_TARGET_UNVERIFIED, DiagnosticReport.Code.QUANTITY_TRANSITION_PAGE_CHANGED,
                DiagnosticReport.Code.QUANTITY_TRANSITION_UNVERIFIED, DiagnosticReport.Code.QUANTITY_INPUT_UNCONFIRMED};
        for (int i = 0; i < reasons.length; i++) {
            assertEquals(codes[i], CloudDiagnostics.mappedCode("UNKNOWN", reasons[i]));
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("UNKNOWN", reasons[i] + ":private"));
            assertEquals(DiagnosticReport.Code.UNKNOWN, CloudDiagnostics.mappedCode("SERVICE", reasons[i]));
            assertEquals(DiagnosticReport.Code.IN_STOCK, CloudDiagnostics.mappedCode("IN_STOCK", reasons[i]));
        }
        assertEquals(DiagnosticReport.State.UNKNOWN, CloudDiagnostics.mappedState("UNKNOWN"));
    }
}
