package cn.local.tickethelper.checkout;

import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.UiItem;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class OrderAttemptStoreTest {
    private static final long NOW = Instant.parse("2026-10-25T06:00:00Z").toEpochMilli();
    private static final String NAME = "测试观演人不应存储", DOCUMENT = "76543X";
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void reservesBeforeGrantingOneConsumablePermit() throws Exception {
        OrderAttemptStore store = store();
        assertNull(store.read());
        assertFalse(store.hasUnresolved());
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        assertNotNull(permit);
        assertEquals(OrderAttempt.State.RESERVED, store.read().state);
        assertTrue(store.hasUnresolved());
        assertNull(store.tryReserve(proposal("grant-2")));
        assertTrue(permit.consume());
        assertEquals(OrderAttempt.State.AWAITING_RESULT, store.read().state);
        assertFalse(permit.consume());
    }

    @Test public void concurrentIndependentStoresIssueExactlyOnePermit() throws Exception {
        File directory = temporary.getRoot();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<OrderAttemptStore.SubmitPermit>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                final int index = i;
                outcomes.add(pool.submit(() -> {
                    OrderAttemptStore store = new OrderAttemptStore(directory);
                    start.await();
                    return store.tryReserve(proposal("grant-" + index));
                }));
            }
            start.countDown();
            int permits = 0;
            for (Future<OrderAttemptStore.SubmitPermit> outcome : outcomes) if (outcome.get() != null) permits++;
            assertEquals(1, permits);
            assertTrue(new OrderAttemptStore(directory).hasUnresolved());
        } finally { pool.shutdownNow(); }
    }

    @Test public void concurrentConsumptionSucceedsOnlyOnce() throws Exception {
        OrderAttemptStore.SubmitPermit permit = store().tryReserve(proposal("grant-1"));
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> outcomes = new ArrayList<>();
            for (int i = 0; i < 32; i++) outcomes.add(pool.submit(permit::consume));
            int allowed = 0;
            for (Future<Boolean> result : outcomes) if (result.get()) allowed++;
            assertEquals(1, allowed);
        } finally { pool.shutdownNow(); }
    }

    @Test(timeout = 15_000) public void separateProcessesCannotReserveTheSameJournalTwice() throws Exception {
        String separator = File.pathSeparator;
        String classpath = System.getProperty("java.class.path") + separator
                + new File(OrderAttemptStoreTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + separator
                + new File(OrderAttemptStore.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + separator
                + new File(org.junit.Test.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String java = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
        Path start = temporary.getRoot().toPath().resolve("start-workers");
        List<Process> workers = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) workers.add(new ProcessBuilder(java, "-cp", classpath,
                    ReserveProcess.class.getName(), temporary.getRoot().getAbsolutePath(), start.toString())
                    .redirectErrorStream(true).start());
            Files.createFile(start);
            int winners = 0;
            for (Process worker : workers) {
                assertTrue(worker.waitFor(8, TimeUnit.SECONDS));
                String output = new String(worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                assertEquals(output, 0, worker.exitValue());
                if ("RESERVED".equals(output)) winners++;
                else assertEquals("BLOCKED", output);
            }
            assertEquals(1, winners);
        } finally { for (Process worker : workers) worker.destroyForcibly(); }
    }

    /** Separate JVM entry point exercises the real OS file lock, not just the static JVM lock. */
    public static final class ReserveProcess {
        public static void main(String[] arguments) throws Exception {
            Path start = new File(arguments[1]).toPath();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(start)) {
                if (System.nanoTime() >= deadline) throw new IOException("worker_start_timeout");
                Thread.sleep(5);
            }
            OrderAttemptStore.SubmitPermit permit = new OrderAttemptStore(new File(arguments[0]))
                    .tryReserve(proposal("independent-process-grant"));
            System.out.println(permit == null ? "BLOCKED" : "RESERVED");
        }
    }

    @Test public void reservationFileSyncFailureNeverIssuesPermitOrRemovesBarrier() throws Exception {
        OrderAttemptStore store = new OrderAttemptStore(temporary.getRoot(), failing(1, false), () -> NOW);
        assertThrows(IOException.class, () -> store.tryReserve(proposal("grant-1")));
        assertTrue(Files.exists(journal()));
        OrderAttemptStore reconstructed = store();
        assertNull(reconstructed.tryReserve(proposal("grant-2")));
        assertEquals(OrderAttempt.State.OUTCOME_UNKNOWN, reconstructed.read().state);
    }

    @Test public void reservationDirectorySyncFailureNeverIssuesPermit() throws Exception {
        OrderAttemptStore store = new OrderAttemptStore(temporary.getRoot(), failing(0, true), () -> NOW);
        assertThrows(IOException.class, () -> store.tryReserve(proposal("grant-1")));
        assertNull(store().tryReserve(proposal("grant-2")));
        assertEquals(OrderAttempt.State.OUTCOME_UNKNOWN, store().read().state);
    }

    @Test public void awaitingSyncFailureConsumesCapabilityWithoutAllowingInput() throws Exception {
        OrderAttemptStore store = new OrderAttemptStore(temporary.getRoot(), failing(2, false), () -> NOW);
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        assertThrows(IOException.class, permit::consume);
        assertFalse(permit.consume());
        assertNull(store().tryReserve(proposal("grant-2")));
        assertEquals(OrderAttempt.State.OUTCOME_UNKNOWN, store().read().state);
    }

    @Test public void reconstructedReservedAndAwaitingAreUnknownAndCannotReserveAgain() throws Exception {
        OrderAttemptStore original = store();
        OrderAttemptStore.SubmitPermit permit = original.tryReserve(proposal("grant-1"));
        assertEquals(OrderAttempt.State.OUTCOME_UNKNOWN, store().read().state);
        assertFalse(store().read().submissionAuthorized);
        assertNull(store().tryReserve(proposal("grant-2")));
        assertTrue(permit.consume());
        assertEquals(OrderAttempt.State.OUTCOME_UNKNOWN, store().read().state);
        assertTrue(store().read().submissionAuthorized);
        assertNull(store().tryReserve(proposal("grant-3")));
    }

    @Test public void unknownStateRevokesUnconsumedPermitAndPreservesBarrier() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        store.markUnknown(permit.attempt.attemptId);
        assertFalse(permit.consume());
        assertEquals(OrderAttempt.State.OUTCOME_UNKNOWN, store().read().state);
        assertNull(store().tryReserve(proposal("different-config-grant")));
    }

    @Test public void confirmedPendingPersistsAcrossRestartAndCannotBeDowngraded() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        assertTrue(permit.consume());
        VerifiedPendingOrder pending = pending(permit.attempt.target);
        store.markPending(permit.attempt.attemptId, pending);
        OrderAttempt restored = store().read();
        assertEquals(OrderAttempt.State.CONFIRMED_PENDING, restored.state);
        assertEquals(pending.orderReferenceHash, restored.orderReferenceHash);
        assertEquals(pending.paymentDeadlineMs, restored.paymentDeadlineMs);
        assertEquals(OrderAttempt.State.CONFIRMED_PENDING, store.markUnknown(restored.attemptId).state);
        assertEquals(OrderAttempt.State.CONFIRMED_PENDING, store.markInventoryRejected(restored.attemptId).state);
        assertNull(store().tryReserve(proposal("grant-2")));
    }

    @Test public void explicitInventoryRejectionStillBlocksNewOrders() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        assertTrue(permit.consume());
        store.markInventoryRejected(permit.attempt.attemptId);
        assertEquals(OrderAttempt.State.INVENTORY_REJECTED, store().read().state);
        assertEquals(OrderAttempt.State.INVENTORY_REJECTED, store.markUnknown(permit.attempt.attemptId).state);
        assertThrows(IOException.class, () -> store.markPending(permit.attempt.attemptId, pending(permit.attempt.target)));
        assertNull(store().tryReserve(proposal("grant-2")));
    }

    @Test public void unknownAttemptCanOnlyBecomePendingForItsExactTarget() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        permit.consume();
        store.markUnknown(permit.attempt.attemptId);
        OrderTarget wrong = new OrderTarget("其他演出杭州站", "杭州", "2026-11-15", "19:00", 98000, 1,
                98000, 98000, permit.attempt.target.viewerBindingHash);
        assertThrows(IOException.class, () -> store.markPending(permit.attempt.attemptId, pending(wrong)));
        OrderAttempt restored = store().markPending(permit.attempt.attemptId, pending(permit.attempt.target));
        assertEquals(OrderAttempt.State.CONFIRMED_PENDING, restored.state);
    }

    @Test public void cannotConfirmBeforeDispatchOrMutateAnotherAttempt() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        assertThrows(IOException.class, () -> store.markPending(permit.attempt.attemptId, pending(permit.attempt.target)));
        assertThrows(IOException.class, () -> store.markUnknown("other-attempt"));
        assertEquals(OrderAttempt.State.RESERVED, store.read().state);
    }

    @Test public void partialRecordFailsClosedAndIsNeverDeleted() throws Exception {
        OrderAttemptStore store = store();
        store.tryReserve(proposal("grant-1"));
        Files.write(journal(), new byte[]{1, 2, 3}, StandardOpenOption.APPEND);
        assertThrows(IOException.class, store::read);
        assertThrows(IOException.class, store::hasUnresolved);
        assertNull(store().tryReserve(proposal("grant-2")));
        assertTrue(Files.exists(journal()));
    }

    @Test public void futureSchemaAndChecksumDamageCannotResetBarrier() throws Exception {
        store().tryReserve(proposal("grant-1"));
        byte[] bytes = Files.readAllBytes(journal());
        ByteBuffer.wrap(bytes).putInt(4, 999);
        Files.write(journal(), bytes);
        assertThrows(IOException.class, () -> store().read());
        assertNull(store().tryReserve(proposal("grant-2")));
        ByteBuffer.wrap(bytes).putInt(4, 1);
        bytes[bytes.length - 1] ^= 1;
        Files.write(journal(), bytes);
        assertThrows(IOException.class, () -> store().read());
        assertNull(store().tryReserve(proposal("grant-3")));
    }

    @Test public void journalExcludesViewerAndEphemeralInputData() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        permit.consume();
        store.markPending(permit.attempt.attemptId, pending(permit.attempt.target));
        String disk = new String(Files.readAllBytes(journal()), StandardCharsets.UTF_8);
        assertFalse(disk.contains(NAME));
        assertFalse(disk.contains(DOCUMENT));
        assertFalse(disk.contains("raw-screen-secret"));
        assertFalse(disk.contains("submit-button-resource"));
        assertFalse(disk.contains("raw-order-reference"));
        assertTrue(disk.contains(permit.attempt.target.viewerBindingHash));
    }

    @Test public void incompleteFirstWriteStillBlocksFutureReservation() throws Exception {
        Files.write(journal(), new byte[0], StandardOpenOption.CREATE_NEW);
        assertThrows(IOException.class, () -> store().read());
        assertNull(store().tryReserve(proposal("grant-1")));
    }

    @Test public void rejectsUnexpectedExtraChargesBeforeCreatingAnyPermit() throws Exception {
        assertThrows(IOException.class, () -> store().tryReserve(proposal("grant-extra", 98100)));
        assertFalse(Files.exists(journal()));
    }

    @Test public void unknownBeforeConsumptionCannotBecomePendingEvenAfterRestart() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        store.markUnknown(permit.attempt.attemptId);
        OrderAttempt recovered = store().read();
        assertEquals(OrderAttempt.State.OUTCOME_UNKNOWN, recovered.state);
        assertFalse(recovered.submissionAuthorized);
        assertThrows(IOException.class, () -> store().markPending(recovered.attemptId, pending(recovered.target)));
        assertNull(store().tryReserve(proposal("grant-2")));
    }

    @Test public void unknownAfterConsumptionKeepsDurableProvenanceForReadOnlyReconciliation() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        permit.consume();
        store.markUnknown(permit.attempt.attemptId);
        OrderAttempt recovered = store().read();
        assertTrue(recovered.submissionAuthorized);
        assertEquals(OrderAttempt.State.CONFIRMED_PENDING, store().markPending(recovered.attemptId, pending(recovered.target)).state);
        assertNull(store().tryReserve(proposal("grant-2")));
    }

    @Test public void storeRejectsMissingHistoricOrFutureCreationEvidence() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        permit.consume();
        String reference = CheckoutIdentity.hash("raw-order-reference");
        for (VerifiedPendingOrder insufficient : List.of(
                new VerifiedPendingOrder(permit.attempt.target, reference, NOW + 600_000, "", NOW),
                new VerifiedPendingOrder(permit.attempt.target, reference, NOW + 600_000, "", NOW, NOW - 1, true),
                new VerifiedPendingOrder(permit.attempt.target, reference, NOW + 600_000, "", NOW, NOW + 1, true),
                new VerifiedPendingOrder(permit.attempt.target, reference, NOW + 600_000, "", NOW, NOW, false))) {
            assertThrows(IOException.class, () -> store.markPending(permit.attempt.attemptId, insufficient));
        }
        assertEquals(OrderAttempt.State.AWAITING_RESULT, store.read().state);
        assertNull(store().tryReserve(proposal("grant-2")));
    }

    @Test public void storeRejectsExpiredDeadlineAndStaleOrFarFutureObservation() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        permit.consume();
        String reference = CheckoutIdentity.hash("raw-order-reference");
        for (VerifiedPendingOrder insufficient : List.of(
                new VerifiedPendingOrder(permit.attempt.target, reference, NOW, "", NOW, NOW, true),
                new VerifiedPendingOrder(permit.attempt.target, reference, NOW + 600_000, "", NOW + 1001, NOW, true),
                new VerifiedPendingOrder(permit.attempt.target, reference, NOW + 600_000, "", Long.MAX_VALUE, NOW, true))) {
            assertThrows(IOException.class, () -> store.markPending(permit.attempt.attemptId, insufficient));
        }
        OrderAttemptStore later = new OrderAttemptStore(temporary.getRoot(), failing(0, false), () -> NOW + 4001);
        assertThrows(IOException.class, () -> later.markPending(permit.attempt.attemptId,
                new VerifiedPendingOrder(permit.attempt.target, reference, NOW + 600_000, "", NOW + 1000, NOW, true)));
        assertNull(store().tryReserve(proposal("grant-2")));
    }

    @Test public void legacyUnknownConstructorDoesNotInventConsumption() {
        OrderAttempt old = OrderAttempt.reserved("old-attempt", proposal("grant-1"), NOW);
        OrderAttempt unknown = new OrderAttempt(old.attemptId, old.grantId, old.configFingerprint, old.targetFingerprint,
                old.target, NOW, NOW, OrderAttempt.State.OUTCOME_UNKNOWN, "", null, "");
        assertFalse(unknown.submissionAuthorized);
    }

    @Test public void unconsumedReservationCannotBeRecordedAsAnOrderInventoryRejection() throws Exception {
        OrderAttemptStore store = store();
        OrderAttemptStore.SubmitPermit permit = store.tryReserve(proposal("grant-1"));
        assertThrows(IOException.class, () -> store.markInventoryRejected(permit.attempt.attemptId));
        store.markUnknown(permit.attempt.attemptId);
        assertThrows(IOException.class, () -> store().markInventoryRejected(permit.attempt.attemptId));
        assertFalse(store().read().submissionAuthorized);
        assertNull(store().tryReserve(proposal("grant-2")));
    }

    private OrderAttemptStore store() throws IOException { return new OrderAttemptStore(temporary.getRoot(), failing(0, false), () -> NOW); }
    private Path journal() { return temporary.getRoot().toPath().resolve("order-attempt.journal"); }

    private static VerifiedPendingOrder pending(OrderTarget target) {
        return new VerifiedPendingOrder(target, CheckoutIdentity.hash("raw-order-reference"), NOW + 600_000,
                "剩余支付时间 09:59", NOW + 1000, NOW + 500, true);
    }

    private static VerifiedSubmitProposal proposal(String grantId) {
        return proposal(grantId, 98000);
    }

    private static VerifiedSubmitProposal proposal(String grantId, long payableFen) {
        MonitorConfig config = new MonitorConfig("测试演唱会", "杭州", "2026-11-15", "19:00", List.of(980),
                1, 980, Instant.ofEpochMilli(NOW + 3_600_000), 3000, 5000, MonitorConfig.Mode.LOCK_ORDER);
        String viewerHash = LockAuthorization.viewerBindingHash(NAME, DOCUMENT);
        OrderTarget target = new OrderTarget("测试演唱会-杭州站", "杭州", "2026-11-15", "19:00", 98000, 1,
                98000, payableFen, viewerHash);
        LockAuthorization authorization = new LockAuthorization(grantId, config, target.eventTitle, viewerHash, NOW, NOW + 600_000);
        UiItem submit = new UiItem("raw-screen-secret", 101, 202, 303, 404,
                true, true, false, UiItem.Source.NODE, "submit-button-resource");
        CheckoutObservation observation = new CheckoutObservation.Builder(CheckoutObservation.Kind.CONFIRMATION, NOW)
                .frame("cn.damai", 55, 123, true, true).submit(submit, true).build();
        return new VerifiedSubmitProposal(authorization, target, observation);
    }

    private static OrderAttemptStore.Durability failing(int failureSyncNumber, boolean failDirectory) {
        AtomicInteger count = new AtomicInteger();
        return new OrderAttemptStore.Durability() {
            @Override public void sync(FileChannel channel) throws IOException {
                if (count.incrementAndGet() == failureSyncNumber) throw new IOException("injected_file_sync_failure");
                channel.force(true);
            }
            @Override public void syncDirectory(Path directory) throws IOException {
                if (failDirectory) throw new IOException("injected_directory_sync_failure");
                try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
            }
        };
    }
}
