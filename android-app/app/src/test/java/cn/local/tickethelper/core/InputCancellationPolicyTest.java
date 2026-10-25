package cn.local.tickethelper.core;

import org.junit.Test;
import static cn.local.tickethelper.core.InputCancellationPolicy.Action.*;
import static org.junit.Assert.*;

/** Transport-state regression cases; no Android service, input command or monitor is started. */
public final class InputCancellationPolicyTest {
    @Test public void savingWhileIdlePreservesConnectedInputAcrossRepeatedPauseRequests() {
        for (int pause = 0; pause < 3; pause++)
            assertEquals(KEEP_IDLE_READY, InputCancellationPolicy.decide(false, false, true));
    }

    @Test public void queuedOrDispatchedInputAlwaysRequiresCancellationEvenIfReady() {
        for (boolean ready : new boolean[]{false, true})
            for (boolean alreadyCancelling : new boolean[]{false, true})
                assertEquals(INVALIDATE_AND_CANCEL,
                        InputCancellationPolicy.decide(true, alreadyCancelling, ready));
    }

    @Test public void repeatedPauseKeepsTheOutstandingCancellationAcknowledgement() {
        assertEquals(INVALIDATE_AND_CANCEL, InputCancellationPolicy.decide(true, false, true));
        // The first cancel detached pending, but the remote cancel/ping has not acknowledged yet.
        for (int pause = 0; pause < 3; pause++)
            assertEquals(KEEP_CANCELLING, InputCancellationPolicy.decide(false, true, false));
        // Only the real acknowledgement can restore the idle-ready state.
        assertEquals(KEEP_IDLE_READY, InputCancellationPolicy.decide(false, false, true));
    }

    @Test public void cancellingHasPriorityOverAStaleReadyFlag() {
        assertEquals(KEEP_CANCELLING, InputCancellationPolicy.decide(false, true, true));
    }

    @Test public void connectingOrUnavailableTransportIsNotTreatedAsIdleReady() {
        assertEquals(INVALIDATE_AND_CANCEL, InputCancellationPolicy.decide(false, false, false));
    }

    @Test public void failedCancellationDoesNotUseTheIdleFastPath() {
        assertEquals(KEEP_CANCELLING, InputCancellationPolicy.decide(false, true, false));
        // Failed ack / binder death leaves no connected-ready state to preserve.
        assertEquals(INVALIDATE_AND_CANCEL, InputCancellationPolicy.decide(false, false, false));
    }
}
