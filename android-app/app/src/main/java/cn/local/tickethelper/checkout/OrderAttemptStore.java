package cn.local.tickethelper.checkout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

/**
 * Permanent, local submission barrier. No pause, configuration change, upgrade or restart clears it.
 * The append-only journal contains structured public target facts and opaque hashes, never screen
 * text, viewer names, identity documents, input coordinates, or a serialized proposal.
 * A corrupt/unreadable journal throws: callers must treat that as blocked, never as no attempt.
 */
public final class OrderAttemptStore {
    // This stable filename must not change with APK/schema versions: an old barrier still blocks.
    private static final String JOURNAL = "order-attempt.journal";
    private static final int MAGIC = 0x544F5244, VERSION = 1, MAX_RECORD = 16_384;
    private static final long MAX_JOURNAL = 1_048_576;
    private static final Object PROCESS_LOCK = new Object();
    private final Path directory, journal, lockFile;
    private final Durability durability;
    private final LongSupplier clock;
    private String liveAttemptId;

    /** Use Context.getFilesDir(); never use external/shared storage. */
    public OrderAttemptStore(File privateFilesDir) throws IOException {
        this(privateFilesDir, new Durability() {
            @Override public void sync(FileChannel channel) throws IOException { channel.force(true); }
            @Override public void syncDirectory(Path directory) throws IOException {
                try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                    channel.force(true);
                }
            }
        }, System::currentTimeMillis);
    }

    // Injectable fsync boundary, not a replaceable in-memory store: tests use real files/locks.
    interface Durability {
        void sync(FileChannel channel) throws IOException;
        void syncDirectory(Path directory) throws IOException;
    }

    OrderAttemptStore(File privateFilesDir, Durability durability, LongSupplier clock) throws IOException {
        if (privateFilesDir == null || !privateFilesDir.isDirectory()) throw new IOException("private_directory_unavailable");
        this.directory = privateFilesDir.toPath().toRealPath();
        this.journal = directory.resolve(JOURNAL);
        this.lockFile = directory.resolve("order-attempt.lock");
        this.durability = durability;
        this.clock = clock;
    }

    /** Null means a barrier already exists. An I/O exception never carries a submission permit. */
    public SubmitPermit tryReserve(VerifiedSubmitProposal proposal) throws IOException {
        if (proposal == null) throw new IllegalArgumentException("missing_proposal");
        return locked(() -> {
            if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return null;
            OrderAttempt attempt = OrderAttempt.reserved(UUID.randomUUID().toString(), proposal, clock.getAsLong());
            validate(attempt);
            byte[] record = encode(attempt);
            // CREATE_NEW is also an independent cross-process guard. Never delete on any failure.
            try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                write(channel, ByteBuffer.allocate(8).putInt(MAGIC).putInt(VERSION).array());
                write(channel, record);
                durability.sync(channel);
            }
            durability.syncDirectory(directory);
            liveAttemptId = attempt.attemptId;
            return new SubmitPermit(attempt);
        });
    }

    /** A reconstructed RESERVED/AWAITING_RESULT attempt is unknown, never resumable. */
    public OrderAttempt read() throws IOException {
        return locked(() -> {
            OrderAttempt attempt = readDisk();
            if (attempt != null && !attempt.attemptId.equals(liveAttemptId)
                    && (attempt.state == OrderAttempt.State.RESERVED || attempt.state == OrderAttempt.State.AWAITING_RESULT)) {
                return state(attempt, OrderAttempt.State.OUTCOME_UNKNOWN, attempt.updatedAtMs, "", null, "");
            }
            return attempt;
        });
    }

    /** Read failures must also block; callers must not replace an exception with false. */
    public boolean hasUnresolved() throws IOException { return read() != null; }

    /** Single-use capability: durably writes AWAITING_RESULT before granting one input dispatch. */
    public final class SubmitPermit {
        public final OrderAttempt attempt;
        private final AtomicBoolean consumed = new AtomicBoolean();
        private SubmitPermit(OrderAttempt attempt) { this.attempt = attempt; }
        public boolean consume() throws IOException {
            if (!consumed.compareAndSet(false, true)) return false;
            return locked(() -> {
                OrderAttempt current = require(attempt.attemptId);
                if (current.state != OrderAttempt.State.RESERVED || !current.attemptId.equals(liveAttemptId)) return false;
                append(state(current, OrderAttempt.State.AWAITING_RESULT, now(current), "", null, ""));
                return true;
            });
        }
    }

    public OrderAttempt markPending(String attemptId, VerifiedPendingOrder pending) throws IOException {
        if (pending == null || pending.target == null) throw new IllegalArgumentException("missing_pending_evidence");
        return locked(() -> {
            OrderAttempt current = require(attemptId);
            if (!current.targetFingerprint.equals(pending.target.fingerprint())) throw new IOException("pending_target_mismatch");
            if (current.state == OrderAttempt.State.CONFIRMED_PENDING) return current;
            if (!current.submissionAuthorized || current.state == OrderAttempt.State.RESERVED || current.state == OrderAttempt.State.INVENTORY_REJECTED)
                throw new IOException("pending_transition_rejected");
            long observedNow = clock.getAsLong();
            if (!pending.orderCreationTimeVerified || pending.orderCreatedAtMs == null
                    || pending.orderCreatedAtMs < current.reservedAtMs || pending.orderCreatedAtMs > pending.observedAtMs
                    || observedNow <= 0 || pending.observedAtMs <= 0
                    || (pending.observedAtMs > observedNow ? pending.observedAtMs - observedNow > 1000 : observedNow - pending.observedAtMs > 3000)
                    || (pending.paymentDeadlineMs != null && (pending.paymentDeadlineMs <= observedNow
                    || pending.paymentDeadlineMs <= pending.orderCreatedAtMs)))
                throw new IOException("pending_creation_or_freshness_unverified");
            OrderAttempt next = state(current, OrderAttempt.State.CONFIRMED_PENDING, now(current),
                    pending.orderReferenceHash, pending.paymentDeadlineMs, pending.paymentDeadlineText);
            append(next);
            return next;
        });
    }

    public OrderAttempt markUnknown(String attemptId) throws IOException {
        return markOutcome(attemptId, OrderAttempt.State.OUTCOME_UNKNOWN);
    }

    public OrderAttempt markInventoryRejected(String attemptId) throws IOException {
        return markOutcome(attemptId, OrderAttempt.State.INVENTORY_REJECTED);
    }

    private OrderAttempt markOutcome(String attemptId, OrderAttempt.State outcome) throws IOException {
        return locked(() -> {
            OrderAttempt current = require(attemptId);
            if (current.state == outcome || current.state == OrderAttempt.State.CONFIRMED_PENDING
                    || current.state == OrderAttempt.State.INVENTORY_REJECTED) return current;
            if (outcome == OrderAttempt.State.INVENTORY_REJECTED && !current.submissionAuthorized)
                throw new IOException("rejection_without_submission_authorization");
            OrderAttempt next = state(current, outcome, now(current), "", null, "");
            append(next);
            return next;
        });
    }

    private OrderAttempt require(String attemptId) throws IOException {
        OrderAttempt current = readDisk();
        if (current == null || attemptId == null || !attemptId.equals(current.attemptId)) throw new IOException("attempt_not_found");
        return current;
    }

    private long now(OrderAttempt current) { return Math.max(current.updatedAtMs, clock.getAsLong()); }

    private static OrderAttempt state(OrderAttempt old, OrderAttempt.State state, long now,
                                      String reference, Long deadline, String deadlineText) {
        return new OrderAttempt(old.attemptId, old.grantId, old.configFingerprint, old.targetFingerprint,
                old.target, old.reservedAtMs, now, state, reference, deadline, deadlineText,
                old.submissionAuthorized || state == OrderAttempt.State.AWAITING_RESULT);
    }

    private void append(OrderAttempt attempt) throws IOException {
        validate(attempt);
        byte[] bytes = encode(attempt);
        if (Files.size(journal) + bytes.length > MAX_JOURNAL) throw new IOException("attempt_journal_full");
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            write(channel, bytes);
            durability.sync(channel);
        }
    }

    private OrderAttempt readDisk() throws IOException {
        if (!Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS) || Files.size(journal) > MAX_JOURNAL)
            throw new IOException("invalid_attempt_journal");
        byte[] bytes = Files.readAllBytes(journal);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("unsupported_attempt_journal");
            OrderAttempt first = null, latest = null;
            boolean submissionAuthorized = false;
            while (input.available() > 0) {
                int length = input.readInt();
                if (length <= 0 || length > MAX_RECORD || length > input.available() - 8) throw new IOException("incomplete_attempt_record");
                byte[] payload = new byte[length];
                input.readFully(payload);
                long expected = input.readLong();
                CRC32 crc = new CRC32(); crc.update(payload);
                if (crc.getValue() != expected) throw new IOException("attempt_checksum_mismatch");
                OrderAttempt next = decode(payload);
                // v1 already records every durable transition. Recover consumption provenance
                // from that history, including when the last state is UNKNOWN after a restart.
                submissionAuthorized |= next.state == OrderAttempt.State.AWAITING_RESULT;
                next = new OrderAttempt(next.attemptId,next.grantId,next.configFingerprint,next.targetFingerprint,next.target,
                        next.reservedAtMs,next.updatedAtMs,next.state,next.orderReferenceHash,next.paymentDeadlineMs,
                        next.paymentDeadlineText,submissionAuthorized);
                validate(next);
                if (first == null) {
                    if (next.state != OrderAttempt.State.RESERVED) throw new IOException("missing_reservation");
                    first = next;
                } else if (!sameReservation(first, next) || next.updatedAtMs < latest.updatedAtMs
                        || !validTransition(latest.state, next.state)) throw new IOException("invalid_attempt_transition");
                latest = next;
            }
            if (latest == null) throw new IOException("empty_attempt_journal");
            return latest;
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IOException("unreadable_attempt_journal", exception);
        }
    }

    private static boolean sameReservation(OrderAttempt a, OrderAttempt b) {
        return a.attemptId.equals(b.attemptId) && a.grantId.equals(b.grantId)
                && a.configFingerprint.equals(b.configFingerprint) && a.targetFingerprint.equals(b.targetFingerprint)
                && a.reservedAtMs == b.reservedAtMs;
    }

    private static boolean validTransition(OrderAttempt.State from, OrderAttempt.State to) {
        if (from == OrderAttempt.State.CONFIRMED_PENDING || from == OrderAttempt.State.INVENTORY_REJECTED) return false;
        if (to == OrderAttempt.State.AWAITING_RESULT) return from == OrderAttempt.State.RESERVED;
        if (to == OrderAttempt.State.CONFIRMED_PENDING) return from == OrderAttempt.State.AWAITING_RESULT || from == OrderAttempt.State.OUTCOME_UNKNOWN;
        return to == OrderAttempt.State.OUTCOME_UNKNOWN || to == OrderAttempt.State.INVENTORY_REJECTED;
    }

    private static void validate(OrderAttempt attempt) throws IOException {
        try {
            OrderTarget target = attempt.target;
            if (!attempt.attemptId.matches("[a-zA-Z0-9_.:-]{1,128}") || !attempt.grantId.matches("[a-zA-Z0-9_.:-]{1,128}")
                    || !hash(attempt.configFingerprint) || !hash(attempt.targetFingerprint) || target == null
                    || target.eventTitle.isEmpty() || target.eventTitle.length() > 300 || target.city.isEmpty() || target.city.length() > 40
                    || !hash(target.viewerBindingHash) || target.quantity != 1 || target.unitPriceFen <= 0
                    || target.unitPriceFen > 10_000_000
                    || target.ticketSubtotalFen != target.unitPriceFen || target.payableFen != target.ticketSubtotalFen
                    || !target.fingerprint().equals(attempt.targetFingerprint)
                    || attempt.reservedAtMs <= 0 || attempt.updatedAtMs < attempt.reservedAtMs || attempt.state == null)
                throw new IOException("invalid_attempt_fields");
            if (!target.date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !LocalDate.parse(target.date).toString().equals(target.date)
                    || !target.time.matches("[0-2][0-9]:[0-5][0-9]"))
                throw new IOException("invalid_target_datetime");
            LocalTime.parse(target.time);
            if ((attempt.state == OrderAttempt.State.RESERVED && attempt.submissionAuthorized)
                    || ((attempt.state == OrderAttempt.State.AWAITING_RESULT || attempt.state == OrderAttempt.State.CONFIRMED_PENDING
                    || attempt.state == OrderAttempt.State.INVENTORY_REJECTED)
                    && !attempt.submissionAuthorized)) throw new IOException("invalid_submission_provenance");
            if (attempt.state == OrderAttempt.State.CONFIRMED_PENDING) {
                if (!hash(attempt.orderReferenceHash) || (attempt.paymentDeadlineMs != null && attempt.paymentDeadlineMs <= 0))
                    throw new IOException("invalid_pending_evidence");
            } else if (!attempt.orderReferenceHash.isEmpty() || attempt.paymentDeadlineMs != null || !attempt.paymentDeadlineText.isEmpty()) {
                throw new IOException("unexpected_pending_fields");
            }
        } catch (RuntimeException exception) { throw new IOException("invalid_attempt_fields", exception); }
    }

    private static boolean hash(String value) { return value != null && value.matches("[a-f0-9]{64}"); }

    private static byte[] encode(OrderAttempt attempt) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(payloadBytes)) {
            OrderTarget target = attempt.target;
            data.writeUTF(attempt.attemptId); data.writeUTF(attempt.grantId);
            data.writeUTF(attempt.configFingerprint); data.writeUTF(attempt.targetFingerprint);
            data.writeUTF(target.eventTitle); data.writeUTF(target.city); data.writeUTF(target.date); data.writeUTF(target.time);
            data.writeLong(target.unitPriceFen); data.writeInt(target.quantity);
            data.writeLong(target.ticketSubtotalFen); data.writeLong(target.payableFen); data.writeUTF(target.viewerBindingHash);
            data.writeLong(attempt.reservedAtMs); data.writeLong(attempt.updatedAtMs); data.writeUTF(attempt.state.name());
            data.writeUTF(attempt.orderReferenceHash); data.writeBoolean(attempt.paymentDeadlineMs != null);
            if (attempt.paymentDeadlineMs != null) data.writeLong(attempt.paymentDeadlineMs);
            data.writeUTF(attempt.paymentDeadlineText);
        }
        byte[] payload = payloadBytes.toByteArray();
        if (payload.length > MAX_RECORD) throw new IOException("attempt_record_too_large");
        CRC32 crc = new CRC32(); crc.update(payload);
        ByteBuffer record = ByteBuffer.allocate(4 + payload.length + 8);
        return record.putInt(payload.length).put(payload).putLong(crc.getValue()).array();
    }

    private static OrderAttempt decode(byte[] bytes) throws IOException {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String id = data.readUTF(), grant = data.readUTF(), config = data.readUTF(), targetHash = data.readUTF();
            OrderTarget target = new OrderTarget(data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF(),
                    data.readLong(), data.readInt(), data.readLong(), data.readLong(), data.readUTF());
            long reserved = data.readLong(), updated = data.readLong();
            OrderAttempt.State state = OrderAttempt.State.valueOf(data.readUTF());
            String reference = data.readUTF();
            Long deadline = data.readBoolean() ? data.readLong() : null;
            OrderAttempt result = new OrderAttempt(id, grant, config, targetHash, target, reserved, updated,
                    state, reference, deadline, data.readUTF());
            if (data.available() != 0) throw new IOException("unexpected_attempt_payload");
            return result;
        }
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private interface Operation<T> { T run() throws IOException; }
    private <T> T locked(Operation<T> operation) throws IOException {
        synchronized (PROCESS_LOCK) {
            if (Files.isSymbolicLink(lockFile)) throw new IOException("invalid_attempt_lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.run();
            }
        }
    }
}
