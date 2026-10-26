package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.UiItem;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Pure and private-journal fixtures; these do not register a production app profile. */
public final class TwoTicketCheckoutTest {
    private static final long NOW = Instant.parse("2026-10-27T06:00:00Z").toEpochMilli();
    private static final String TITLE = "温州·合成测试演唱会-温州站";
    private static final List<ViewerMatcher.RequestedViewer> PEOPLE = List.of(
            new ViewerMatcher.RequestedViewer("合成测试甲", "1234"), new ViewerMatcher.RequestedViewer("合成测试乙", "5678"));
    private final OrderLockEngine engine = new OrderLockEngine();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private MonitorConfig config(int quantity, int budget) {
        return new MonitorConfig("合成测试演唱会", "温州", "2026-10-31", "19:00", List.of(1680), quantity, budget,
                Instant.ofEpochMilli(NOW + 3_600_000), 500, 1500, MonitorConfig.Mode.LOCK_ORDER);
    }
    private String binding(int quantity) { return LockAuthorization.viewerBindingsHash(PEOPLE.subList(0, quantity)); }
    private LockAuthorization grant(int quantity, int budget) {
        return new LockAuthorization("synthetic-explicit-grant", config(quantity, budget), TITLE, binding(quantity), NOW - 1000, NOW + 600_000);
    }
    private CheckoutObservation.Builder page(int quantity, CheckoutObservation.Kind kind) {
        return new CheckoutObservation.Builder(kind, NOW).frame("cn.damai", 7, 12, true, true)
                .target(TITLE, "温州", "2026-10-31", "19:00").amounts(168_000L, quantity, 168_000L * quantity, 168_000L * quantity)
                .viewer(binding(quantity), true).extras(true).agreements(true)
                .submit(new UiItem("提交订单", 600, 2300, 1100, 2450, true, true, false,
                        UiItem.Source.NODE, "cn.damai:id/synthetic_submit"), true);
    }
    private CheckoutDecision evaluate(CheckoutObservation observation) {
        return engine.evaluate(config(2, 3360), grant(2, 3360), observation, null, NOW);
    }
    private VerifiedSubmitProposal proposal(int quantity) {
        CheckoutDecision result = engine.evaluate(config(quantity, 1680 * quantity), grant(quantity, 1680 * quantity),
                page(quantity, CheckoutObservation.Kind.CONFIRMATION).build(), null, NOW);
        assertEquals(CheckoutDecision.State.READY_TO_RESERVE, result.state);
        return result.proposal;
    }

    @Test public void twoTicketsRequireA3360TotalAndTheExplicitTwoPersonGrant() {
        CheckoutDecision result = evaluate(page(2, CheckoutObservation.Kind.CONFIRMATION).build());
        assertEquals(CheckoutDecision.State.READY_TO_RESERVE, result.state); assertNotNull(result.proposal);
        assertEquals(2, result.proposal.target.quantity); assertEquals(336_000, result.proposal.target.ticketSubtotalFen);
        assertEquals(336_000, result.proposal.target.payableFen); assertNull(result.pendingOrder);
        assertNull(evaluate(page(2, CheckoutObservation.Kind.CONFIRMATION).viewer(binding(1), true).build()).proposal);
        assertNull(evaluate(page(2, CheckoutObservation.Kind.CONFIRMATION).viewer(binding(2), false).build()).proposal);
    }
    @Test public void wrongQuantitySingleTicketTotalAdditionalFeeAndOverBudgetCannotReserve() {
        for (CheckoutObservation observation : List.of(
                page(1, CheckoutObservation.Kind.CONFIRMATION).build(),
                page(2, CheckoutObservation.Kind.CONFIRMATION).amounts(168_000L, 2, 168_000L, 168_000L).build(),
                page(2, CheckoutObservation.Kind.CONFIRMATION).amounts(168_000L, 2, 336_000L, 336_001L).build(),
                page(2, CheckoutObservation.Kind.CONFIRMATION).amounts(168_000L, 3, 504_000L, 504_000L).build()))
            assertNull(evaluate(observation).proposal);
        assertNull(engine.evaluate(config(2, 3359), grant(2, 3359), page(2, CheckoutObservation.Kind.CONFIRMATION).build(), null, NOW).proposal);
    }
    @Test public void twoTicketPendingMustMatchTheReservedPartyAmountAndOfficialCreationTime() {
        OrderAttempt reserved = OrderAttempt.reserved("two-ticket-attempt", proposal(2), NOW - 10);
        OrderAttempt sent = new OrderAttempt(reserved.attemptId, reserved.grantId, reserved.configFingerprint, reserved.targetFingerprint,
                reserved.target, reserved.reservedAtMs, NOW - 5, OrderAttempt.State.AWAITING_RESULT, "", null, "", true);
        CheckoutObservation pending = page(2, CheckoutObservation.Kind.PAYMENT_PENDING).relatedAttempt(sent.attemptId)
                .pendingOrder("SYNTHETIC-ORDER-2", true, NOW + 300_000, "剩余支付时间 05:00").orderCreatedAt(NOW - 5, true).build();
        CheckoutDecision result = engine.evaluate(config(2, 3360), grant(2, 3360), pending, sent, NOW);
        assertEquals(CheckoutDecision.State.CONFIRMED_PENDING, result.state); assertNotNull(result.pendingOrder);
        assertEquals(2, result.pendingOrder.target.quantity); assertNull(result.proposal);
        CheckoutObservation wrong = page(1, CheckoutObservation.Kind.PAYMENT_PENDING).relatedAttempt(sent.attemptId)
                .pendingOrder("SYNTHETIC-ORDER-1", true, NOW + 300_000, "").orderCreatedAt(NOW - 5, true).build();
        assertEquals(CheckoutDecision.State.OUTCOME_UNKNOWN,
                engine.evaluate(config(2, 3360), grant(2, 3360), wrong, sent, NOW).state);
    }
    @Test public void oneAndTwoTicketJournalsUseTheSameFormatAndPreservePermanentBarriers() throws Exception {
        for (int quantity : new int[]{1, 2}) {
            File folder = temporary.newFolder("quantity-" + quantity);
            OrderAttemptStore store = new OrderAttemptStore(folder);
            OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal(quantity));
            assertNotNull(permit); assertTrue(permit.consume());
            byte[] before = Files.readAllBytes(folder.toPath().resolve("order-attempt.journal"));
            assertEquals(1, ByteBuffer.wrap(before).getInt(4));
            OrderAttemptStore restored = new OrderAttemptStore(folder);
            OrderAttempt record = restored.read();
            assertEquals(quantity, record.target.quantity); assertEquals(168_000L * quantity, record.target.ticketSubtotalFen);
            assertEquals(OrderAttempt.State.OUTCOME_UNKNOWN, record.state); assertTrue(record.submissionAuthorized);
            assertNull(restored.tryReserve(proposal(quantity == 1 ? 2 : 1)));
            assertArrayEquals(before, Files.readAllBytes(folder.toPath().resolve("order-attempt.journal")));
            String raw = new String(before, StandardCharsets.UTF_8);
            for (ViewerMatcher.RequestedViewer person : PEOPLE) assertFalse(raw.contains(person.displayName));
        }
    }
}
