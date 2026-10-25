package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.UiItem;
import java.time.Instant;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class OrderLockEngineTest {
    private static final long NOW=Instant.parse("2026-10-25T08:00:00Z").toEpochMilli();
    private static final String TITLE="杭州·测试演唱会-杭州站";
    private static final String VIEWER=LockAuthorization.viewerBindingHash("测试甲","1234");
    private final OrderLockEngine engine=new OrderLockEngine();
    private MonitorConfig config() { return config(MonitorConfig.Mode.LOCK_ORDER,List.of(980,1380),1380); }
    private MonitorConfig config(MonitorConfig.Mode mode,List<Integer> prices,int budget) {
        return new MonitorConfig("测试演唱会","杭州","2026-11-15","19:00",prices,1,budget,
                Instant.ofEpochMilli(NOW+3_600_000),3000,6000,mode);
    }
    private LockAuthorization grant() { return new LockAuthorization("explicit-grant",config(),TITLE,VIEWER,NOW-1000,NOW+600_000); }
    private UiItem submit(String label,UiItem.Source source) {
        return new UiItem(label,600,2300,1100,2450,true,true,false,source,"cn.damai:id/observed_submit");
    }
    private CheckoutObservation.Builder page(CheckoutObservation.Kind kind) {
        return new CheckoutObservation.Builder(kind,NOW).frame("cn.damai",10,40,true,true)
                .target(TITLE,"杭州","2026-11-15","19:00").amounts(98_000L,1,98_000L,98_000L)
                .viewer(VIEWER,true).extras(true).agreements(true).submit(submit("提交订单",UiItem.Source.NODE),true);
    }
    private CheckoutDecision evaluate(CheckoutObservation observation) { return engine.evaluate(config(),grant(),observation,null,NOW); }
    private OrderAttempt attempt() {
        return state(reserved(),OrderAttempt.State.AWAITING_RESULT);
    }
    private OrderAttempt reserved() {
        return OrderAttempt.reserved("attempt-1",evaluate(page(CheckoutObservation.Kind.CONFIRMATION).build()).proposal,NOW-10);
    }
    private OrderAttempt state(OrderAttempt old,OrderAttempt.State state) {
        return new OrderAttempt(old.attemptId,old.grantId,old.configFingerprint,old.targetFingerprint,old.target,
                old.reservedAtMs,NOW,state,"",null,"",state != OrderAttempt.State.RESERVED
                        && (old.submissionAuthorized || state == OrderAttempt.State.AWAITING_RESULT || state == OrderAttempt.State.CONFIRMED_PENDING));
    }
    private CheckoutObservation.Builder result(CheckoutObservation.Kind kind) {
        return page(kind).relatedAttempt("attempt-1").orderCreatedAt(NOW-5,true);
    }
    private void noProposal(CheckoutDecision decision) { assertNull(decision.proposal); }

    @Test public void completeConfirmationProposesReservationButDoesNotClaimOrderCreated() {
        CheckoutDecision decision=evaluate(page(CheckoutObservation.Kind.CONFIRMATION).build());
        assertEquals(CheckoutDecision.State.READY_TO_RESERVE,decision.state);
        assertNotNull(decision.proposal); assertNull(decision.pendingOrder);
        assertEquals(98_000,decision.proposal.target.payableFen);
        assertEquals(grant().configFingerprint,decision.proposal.configFingerprint);
    }
    @Test public void modeAloneNeverAuthorizesSubmissionAndOldGrantDoesNotSurviveConfigChange() {
        var observation=page(CheckoutObservation.Kind.CONFIRMATION).build();
        noProposal(engine.evaluate(config(),null,observation,null,NOW));
        noProposal(engine.evaluate(config(MonitorConfig.Mode.PREPARE,List.of(980,1380),1380),grant(),observation,null,NOW));
        noProposal(engine.evaluate(config(MonitorConfig.Mode.LOCK_ORDER,List.of(980),980),grant(),observation,null,NOW));
        var expired=new LockAuthorization("explicit-grant",config(),TITLE,VIEWER,NOW-1000,NOW);
        noProposal(engine.evaluate(config(),expired,observation,null,NOW));
    }
    @Test public void titleAllowsOnlyKnownCityDecorationsNotSpecialPerformanceSuffix() {
        assertEquals(CheckoutDecision.State.READY_TO_RESERVE,evaluate(page(CheckoutObservation.Kind.CONFIRMATION)
                .target("测试演唱会-杭州站","杭州","2026-11-15","19:00").build()).state);
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION)
                .target(TITLE+"特别场","杭州","2026-11-15","19:00").build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION)
                .target(TITLE,"杭州","2026-11-14","19:00").build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION)
                .target(TITLE,"温州","2026-11-15","19:00").build()));
    }
    @Test public void oneFenExtraMissingAmountsWrongQuantityAndUnknownExtrasReject() {
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).amounts(98_000L,1,98_000L,98_001L).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).amounts(98_000L,1,null,98_000L).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).amounts(98_000L,2,196_000L,196_000L).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).amounts(99_000L,1,99_000L,99_000L).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).extras(false).build()));
    }
    @Test public void viewerMustBeUniqueSelectedExactBindingAndTermsAlreadyConfirmed() {
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION)
                .viewer(LockAuthorization.viewerBindingHash("测试乙","1234"),true).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).agreements(false).build()));
        assertEquals("",LockAuthorization.viewerBindingHash("","1234"));
    }
    @Test public void paymentOcrAndAmbiguousSubmitTargetsNeverProduceProposal() {
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).submit(submit("立即支付",UiItem.Source.NODE),true).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).submit(submit("提交订单",UiItem.Source.OCR),true).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).submit(submit("提交订单",UiItem.Source.NODE),false).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.PAYMENT_OR_PAID).build()));
    }
    @Test public void freshnessAndNativeSameFrameEvidenceAreMandatory() {
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).frame("cn.damai",10,40,false,true).build()));
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).frame("cn.other",10,40,true,true).build()));
        var observation=page(CheckoutObservation.Kind.CONFIRMATION).build();
        noProposal(engine.evaluate(config(),grant(),observation,null,NOW+3001));
    }
    @Test public void anyPersistedAttemptBlocksAnotherProposalEvenAfterGrantOrConfigChanges() {
        var observation=result(CheckoutObservation.Kind.CONFIRMATION).build();
        for (OrderAttempt.State state:OrderAttempt.State.values()) {
            CheckoutDecision decision=engine.evaluate(config(MonitorConfig.Mode.LOCK_ORDER,List.of(980),980),null,
                    observation,state(attempt(),state),NOW);
            noProposal(decision); assertNotEquals(CheckoutDecision.State.READY_TO_RESERVE,decision.state);
        }
    }
    @Test public void inputCompletionWithoutOrderEvidenceNeverMeansSuccessAndTimeoutNeverRetries() {
        var attempt=attempt();
        var waiting=engine.evaluate(config(),grant(),result(CheckoutObservation.Kind.CONFIRMATION).build(),attempt,NOW);
        assertEquals(CheckoutDecision.State.WAIT_RESULT,waiting.state); noProposal(waiting);
        var timedOut=engine.evaluate(config(),grant(),null,attempt,NOW+OrderLockEngine.RESULT_TIMEOUT_MS);
        assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,timedOut.state); noProposal(timedOut);
        var unknown=engine.evaluate(config(),grant(),result(CheckoutObservation.Kind.CONFIRMATION).build(),
                state(attempt,OrderAttempt.State.OUTCOME_UNKNOWN),NOW);
        assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,unknown.state); noProposal(unknown);
    }
    @Test public void officialPendingOrderRequiresIdentityMoneyViewerReferenceAndAttempt() {
        var complete=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"剩余支付时间 04:59").build();
        var decision=engine.evaluate(config(),grant(),complete,attempt(),NOW);
        assertEquals(CheckoutDecision.State.CONFIRMED_PENDING,decision.state); noProposal(decision);
        assertTrue(decision.pendingOrder.orderReferenceHash.matches("[0-9a-f]{64}"));
        assertNotEquals(complete.orderReference,decision.pendingOrder.orderReferenceHash);
        var missing=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("",false,null,"").build();
        assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(config(),grant(),missing,attempt(),NOW).state);
        var wrong=result(CheckoutObservation.Kind.PAYMENT_PENDING).relatedAttempt("other-attempt")
                .pendingOrder("order-example-1",true,NOW+300_000,"").build();
        assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(config(),grant(),wrong,attempt(),NOW).state);
    }
    @Test public void uncertainAttemptMayBeReadOnlyReconciledButNeverResubmitted() {
        var complete=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,null,"").build();
        var decision=engine.evaluate(null,null,complete,state(attempt(),OrderAttempt.State.OUTCOME_UNKNOWN),NOW);
        assertEquals(CheckoutDecision.State.CONFIRMED_PENDING,decision.state); noProposal(decision);
        assertNull(decision.pendingOrder.paymentDeadlineMs); assertEquals("",decision.pendingOrder.paymentDeadlineText);
    }
    @Test public void genericStockLabelIsNotAuthoritativeOrderRejection() {
        var generic=result(CheckoutObservation.Kind.INVENTORY_REJECTED).inventoryRejection(false).build();
        assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(config(),grant(),generic,attempt(),NOW).state);
        var official=result(CheckoutObservation.Kind.INVENTORY_REJECTED).inventoryRejection(true).build();
        assertEquals(CheckoutDecision.State.INVENTORY_REJECTED,engine.evaluate(config(),grant(),official,attempt(),NOW).state);
    }
    @Test public void verificationOrPaymentHandoffAfterSubmissionIsUnknownNotRetryable() {
        for (CheckoutObservation.Kind kind:List.of(CheckoutObservation.Kind.VERIFICATION,CheckoutObservation.Kind.PAYMENT_OR_PAID)) {
            var decision=engine.evaluate(config(),grant(),result(kind).build(),attempt(),NOW);
            assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,decision.state); noProposal(decision);
        }
    }
    @Test public void pendingDeadlineExpiryDoesNotReleaseThePersistedBarrier() {
        var attempt=attempt();
        var confirmed=new OrderAttempt(attempt.attemptId,attempt.grantId,attempt.configFingerprint,attempt.targetFingerprint,
                attempt.target,attempt.reservedAtMs,NOW-1,OrderAttempt.State.CONFIRMED_PENDING,"a".repeat(64),NOW-1,"00:00");
        var decision=engine.evaluate(config(),grant(),page(CheckoutObservation.Kind.CONFIRMATION).build(),confirmed,NOW);
        noProposal(decision); assertEquals(CheckoutDecision.State.MATCHING_PENDING_REVIEW,decision.state);
        assertEquals("persisted_pending_payment_window_expired",decision.reason);
    }
    @Test public void finalGuardRequiresConsumedPersistedPermitAndSameGrantFrameTarget() {
        var observation=page(CheckoutObservation.Kind.CONFIRMATION).build();
        var proposal=evaluate(observation).proposal;
        var reserved=OrderAttempt.reserved("attempt-1",proposal,NOW);
        var awaiting=state(reserved,OrderAttempt.State.AWAITING_RESULT);
        assertTrue(engine.revalidateForDispatch(config(),grant(),observation,proposal,awaiting,NOW));
        assertFalse(engine.revalidateForDispatch(config(),grant(),observation,proposal,reserved,NOW));
        assertFalse(engine.revalidateForDispatch(config(),grant(),observation,proposal,state(reserved,OrderAttempt.State.OUTCOME_UNKNOWN),NOW));
        var differentGrant=new LockAuthorization("new-grant",config(),TITLE,VIEWER,NOW-1000,NOW+600_000);
        assertFalse(engine.revalidateForDispatch(config(),differentGrant,observation,proposal,awaiting,NOW));
        assertFalse(engine.revalidateForDispatch(config(),grant(),page(CheckoutObservation.Kind.CONFIRMATION)
                .frame("cn.damai",10,41,true,true).build(),proposal,awaiting,NOW));
        assertFalse(engine.revalidateForDispatch(config(),grant(),page(CheckoutObservation.Kind.CONFIRMATION)
                .amounts(138_000L,1,138_000L,138_000L).build(),proposal,awaiting,NOW));
        assertFalse(engine.revalidateForDispatch(config(),grant(),observation,proposal,awaiting,NOW+3001));
    }
    @Test public void finalGuardRejectsMovedSubmitEvenWhenOtherFieldsStillMatch() {
        var observation=page(CheckoutObservation.Kind.CONFIRMATION).build();
        var proposal=evaluate(observation).proposal;
        var awaiting=state(OrderAttempt.reserved("attempt-1",proposal,NOW),OrderAttempt.State.AWAITING_RESULT);
        UiItem moved=new UiItem("提交订单",601,2300,1101,2450,true,true,false,UiItem.Source.NODE,"cn.damai:id/observed_submit");
        assertFalse(engine.revalidateForDispatch(config(),grant(),page(CheckoutObservation.Kind.CONFIRMATION)
                .submit(moved,true).build(),proposal,awaiting,NOW));
        assertFalse(engine.revalidateForDispatch(config(),grant(),page(CheckoutObservation.Kind.CONFIRMATION)
                .submit(submit("立即支付",UiItem.Source.NODE),true).build(),proposal,awaiting,NOW));
    }

    @Test public void unconsumedReservationAndItsUnknownRecoveryNeverClaimSubmission() {
        var pending=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"").build();
        for (OrderAttempt record:List.of(reserved(),state(reserved(),OrderAttempt.State.OUTCOME_UNKNOWN))) {
            assertFalse(record.submissionAuthorized);
            var decision=engine.evaluate(null,null,pending,record,NOW);
            assertEquals(CheckoutDecision.State.MATCHING_PENDING_REVIEW,decision.state);
            noProposal(decision);
            assertNotNull(decision.pendingOrder);
            assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,null,record,NOW).state);
            assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,
                    result(CheckoutObservation.Kind.INVENTORY_REJECTED).inventoryRejection(true).build(),record,NOW).state);
        }
    }

    @Test public void matchingOrderWithoutIndependentCreationTimeIsReadOnlyReview() {
        for (CheckoutObservation observation:List.of(
                result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                        .orderCreatedAt(null,false).build(),
                result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                        .orderCreatedAt(NOW-5,false).build())) {
            var decision=engine.evaluate(null,null,observation,attempt(),NOW);
            assertEquals(CheckoutDecision.State.MATCHING_PENDING_REVIEW,decision.state);
            assertNotNull(decision.pendingOrder); noProposal(decision);
        }
    }

    @Test public void creationTimeMustBeExactlyInsideReservationAndObservationInterval() {
        var attempt=attempt();
        for (long exact:List.of(attempt.reservedAtMs,NOW)) {
            var observation=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                    .orderCreatedAt(exact,true).build();
            assertEquals(CheckoutDecision.State.CONFIRMED_PENDING,engine.evaluate(null,null,observation,attempt,NOW).state);
        }
        var historic=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                .orderCreatedAt(attempt.reservedAtMs-1,true).build();
        assertEquals(CheckoutDecision.State.MATCHING_PENDING_REVIEW,engine.evaluate(null,null,historic,attempt,NOW).state);
        for (long impossible:List.of(NOW+1,0L,-1L)) {
            var observation=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                    .orderCreatedAt(impossible,true).build();
            assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,observation,attempt,NOW).state);
        }
    }

    @Test public void pendingOrderWithWrongOrMalformedDateTimeNeverConfirms() {
        for (String[] datetime:List.of(new String[]{"2026-11-14","19:00"},new String[]{"2026-11-15","19:01"},
                new String[]{"2026-02-30","19:00"},new String[]{"2026-11-15","24:00"},new String[]{"","19:00"})) {
            var observation=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                    .target(TITLE,"杭州",datetime[0],datetime[1]).build();
            assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,observation,attempt(),NOW).state);
        }
    }

    @Test public void incompleteReceiptOrUnexpectedPayableAmountNeverConfirms() {
        for (CheckoutObservation observation:List.of(
                result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                        .amounts(98_000L,1,98_000L,98_001L).build(),
                result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                        .amounts(98_000L,1,null,98_000L).build(),
                result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"")
                        .viewer(VIEWER,false).build(),
                result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order\nexample",true,NOW+300_000,"").build(),
                result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",false,NOW+300_000,"").build())) {
            assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,observation,attempt(),NOW).state);
        }
    }

    @Test public void expiredOrExactlyElapsedPaymentWindowRequiresReviewAndNeverReleasesBarrier() {
        for (long deadline:List.of(NOW-1,NOW)) {
            var observation=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,deadline,"00:00").build();
            var decision=engine.evaluate(null,null,observation,attempt(),NOW);
            assertEquals(CheckoutDecision.State.MATCHING_PENDING_REVIEW,decision.state);
            assertEquals("matching_pending_payment_window_expired",decision.reason); noProposal(decision);
        }
    }

    @Test public void timeoutAndRevokedGrantDoNotPreventLaterReadOnlyReconciliation() {
        var unknown=state(attempt(),OrderAttempt.State.OUTCOME_UNKNOWN);
        long later=NOW+60_000;
        var observation=new CheckoutObservation.Builder(CheckoutObservation.Kind.PAYMENT_PENDING,later)
                .frame("cn.damai",10,50,true,true).target(TITLE,"杭州","2026-11-15","19:00")
                .amounts(98_000L,1,98_000L,98_000L).viewer(VIEWER,true).extras(true)
                .relatedAttempt(unknown.attemptId).pendingOrder("order-example-1",true,later+300_000,"")
                .orderCreatedAt(NOW,true).build();
        var decision=engine.evaluate(null,null,observation,unknown,later);
        assertEquals(CheckoutDecision.State.CONFIRMED_PENDING,decision.state); noProposal(decision);
        assertTrue(decision.pendingOrder.orderCreationTimeVerified);
        assertEquals(Long.valueOf(NOW),decision.pendingOrder.orderCreatedAtMs);
    }

    @Test public void persistedPendingReadbackIsHistoryNotANewOrderSuccess() {
        var attempt=attempt();
        var confirmed=new OrderAttempt(attempt.attemptId,attempt.grantId,attempt.configFingerprint,attempt.targetFingerprint,
                attempt.target,attempt.reservedAtMs,NOW,OrderAttempt.State.CONFIRMED_PENDING,"a".repeat(64),NOW+300_000,"");
        var decision=engine.evaluate(null,null,null,confirmed,NOW);
        assertEquals(CheckoutDecision.State.MATCHING_PENDING_REVIEW,decision.state);
        assertEquals("persisted_pending_order_barrier",decision.reason); noProposal(decision);
    }

    @Test public void invalidPersistedAmountsDatetimeAndConsumptionProvenanceFailClosed() {
        var attempt=attempt();
        for (OrderTarget bad:List.of(
                new OrderTarget(TITLE,"杭州","2026-02-30","19:00",98_000,1,98_000,98_000,VIEWER),
                new OrderTarget(TITLE,"杭州","2026-11-15","24:00",98_000,1,98_000,98_000,VIEWER),
                new OrderTarget(TITLE,"杭州","2026-11-15","19:00",98_000,1,98_000,98_001,VIEWER))) {
            var record=new OrderAttempt(attempt.attemptId,attempt.grantId,attempt.configFingerprint,bad.fingerprint(),bad,
                    attempt.reservedAtMs,NOW,OrderAttempt.State.AWAITING_RESULT,"",null,"",true);
            assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,null,record,NOW).state);
        }
        var neverConsumed=new OrderAttempt(attempt.attemptId,attempt.grantId,attempt.configFingerprint,attempt.targetFingerprint,attempt.target,
                attempt.reservedAtMs,NOW,OrderAttempt.State.AWAITING_RESULT,"",null,"",false);
        assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,null,neverConsumed,NOW).state);
    }

    @Test public void backwardsClockOrFarFutureObservationCannotConfirmPending() {
        var observation=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,NOW+300_000,"").build();
        assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,observation,attempt(),NOW-1).state);
        var future=new CheckoutObservation.Builder(CheckoutObservation.Kind.PAYMENT_PENDING,Long.MAX_VALUE)
                .frame("cn.damai",10,40,true,true).build();
        assertNotEquals(CheckoutDecision.State.CONFIRMED_PENDING,engine.evaluate(null,null,future,attempt(),NOW).state);
    }

    @Test public void invalidPaymentDeadlineCannotBeReportedAsAValidPendingOrder() {
        for (long invalid:List.of(0L,-1L,NOW-6)) {
            var observation=result(CheckoutObservation.Kind.PAYMENT_PENDING).pendingOrder("order-example-1",true,invalid,"").build();
            assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,engine.evaluate(null,null,observation,attempt(),NOW).state);
        }
    }

    private ViewerMatcher.Result viewerChoice(String id,boolean selected,UiItem.Source source) {
        UiItem control=new UiItem("",100,400,180,480,true,true,selected,source,id);
        return ViewerMatcher.match("测试甲","1234",List.of(new ViewerMatcher.ViewerRow("row-1","测试甲","身份证尾号1234",
                control,selected,true,false)),true);
    }

    @Test public void viewerCanBeSelectedBeforeTermsWithoutPretendingItIsAlreadySelected() {
        var choice=viewerChoice("cn.damai:id/viewer_checkbox",false,UiItem.Source.NODE);
        var observation=page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).agreements(false).build();
        assertTrue(engine.canSelectViewer(config(),grant(),observation,choice,null,NOW));
        noProposal(evaluate(observation));
        // A choice proposal never satisfies the final submission guard.
        noProposal(evaluate(page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).agreements(true).build()));
    }

    @Test public void viewerSelectionRequiresNoAttemptAndCurrentGrantAndFreshConfirmation() {
        var choice=viewerChoice("cn.damai:id/viewer_checkbox",false,UiItem.Source.NODE);
        var observation=page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).build();
        assertFalse(engine.canSelectViewer(config(),null,observation,choice,null,NOW));
        assertFalse(engine.canSelectViewer(config(),grant(),observation,choice,reserved(),NOW));
        assertFalse(engine.canSelectViewer(config(),grant(),observation,choice,state(attempt(),OrderAttempt.State.OUTCOME_UNKNOWN),NOW));
        assertFalse(engine.canSelectViewer(config(),grant(),observation,choice,null,NOW+3001));
        assertFalse(engine.canSelectViewer(config(MonitorConfig.Mode.LOCK_ORDER,List.of(980),980),grant(),observation,choice,null,NOW));
        assertFalse(engine.canSelectViewer(config(),grant(),page(CheckoutObservation.Kind.PAYMENT_PENDING).viewer(VIEWER,false).build(),choice,null,NOW));
    }

    @Test public void viewerSelectionCannotSkipTargetAmountExtrasOrBindingChecks() {
        var choice=viewerChoice("cn.damai:id/viewer_checkbox",false,UiItem.Source.NODE);
        for (CheckoutObservation observation:List.of(
                page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).target(TITLE,"杭州","2026-11-14","19:00").build(),
                page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).amounts(98_000L,1,98_000L,98_001L).build(),
                page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).amounts(98_000L,1,null,98_000L).build(),
                page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).extras(false).build(),
                page(CheckoutObservation.Kind.CONFIRMATION).viewer(LockAuthorization.viewerBindingHash("测试乙","1234"),false).build(),
                page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,true).build())) {
            assertFalse(engine.canSelectViewer(config(),grant(),observation,choice,null,NOW));
        }
    }

    @Test public void viewerSelectionRequiresAnUnselectedExactNativeControl() {
        var observation=page(CheckoutObservation.Kind.CONFIRMATION).viewer(VIEWER,false).build();
        for (ViewerMatcher.Result choice:List.of(
                viewerChoice("cn.other:id/viewer_checkbox",false,UiItem.Source.NODE),
                viewerChoice("cn.damai:id/",false,UiItem.Source.NODE),
                viewerChoice("",false,UiItem.Source.NODE),
                viewerChoice("cn.damai:id/viewer_checkbox",true,UiItem.Source.NODE),
                viewerChoice("cn.damai:id/viewer_checkbox",false,UiItem.Source.OCR))) {
            assertFalse(engine.canSelectViewer(config(),grant(),observation,choice,null,NOW));
        }
    }
}
