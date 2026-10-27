package cn.local.tickethelper.sync;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;

/** HTTPS pairing and typed diagnostic upload only. Has no remote-command/gesture API. */
public final class DiagnosticSyncClient {
    private DiagnosticSyncClient() { }
    private static final int TIMEOUT_SECONDS = 20, MAX_REPLY_BYTES = 8192;
    private static final AtomicLong PAIRING_GENERATION = new AtomicLong();
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> { Thread thread = new Thread(task, "ticketpilot-sync-http"); thread.setDaemon(true); return thread; });
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final boolean retryable;
        Failure(String message) { this(message, true); }
        Failure(String message, boolean retryable) { super(message); this.retryable = retryable; }
    }
    /** Fresh token per operation; cancellation never disconnects synchronously on the UI thread. */
    public static final class Cancellation {
        private final Object commitLock = new Object();
        private final AtomicBoolean cancelled = new AtomicBoolean(), started = new AtomicBoolean();
        private volatile Future<?> future;
        private volatile HttpURLConnection connection;
        public boolean isCancelled() { return cancelled.get(); }
        public void cancel() {
            // Serialize only the tiny local credential commit, never the network request.
            // If cancellation wins, a late pairing response cannot save its credential.
            synchronized (commitLock) { if (!cancelled.compareAndSet(false, true)) return; }
            Future<?> active = future; if (active != null) active.cancel(true);
            HttpURLConnection socket = connection; if (socket != null) disconnect(socket);
        }
        void attach(Future<?> active) { future = active; if (isCancelled()) active.cancel(true); }
        void attach(HttpURLConnection active) { connection = active; if (isCancelled()) disconnect(active); }
    }
    public static final class UploadResult {
        public final boolean uploaded, duplicate;
        public final String reportId;
        public final long cursor;
        private UploadResult(boolean uploaded, boolean duplicate, String reportId, long cursor) {
            this.uploaded = uploaded; this.duplicate = duplicate; this.reportId = reportId; this.cursor = cursor;
        }
    }

    /** Blocking bounded call: invoke from a worker, never Android's main thread. */
    public static SyncSecretsStore.Connection pair(SyncEndpoint endpoint, String pairingCode, DiagnosticQueue queue,
                                                   SyncSecretsStore secrets, Cancellation cancellation) throws IOException {
        if (endpoint == null || queue == null || secrets == null || pairingCode == null || !pairingCode.matches("[A-Za-z0-9_-]{22}"))
            throw new Failure("请填写云端地址和有效的一次性配对码。", false);
        Cancellation token = cancellation == null ? new Cancellation() : cancellation;
        long generation = PAIRING_GENERATION.incrementAndGet();
        try {
            JSONObject request = new JSONObject().put("pairingCode", pairingCode).put("installId", queue.installId());
            JSONObject response = post(endpoint.pairUrl(), "", request.toString().getBytes(StandardCharsets.UTF_8), token);
            DiagnosticReport.keys(response, Set.of("schema", "deviceId", "deviceToken", "expiresAt"));
            DiagnosticReport.integer(response, "schema", 1, 1);
            String deviceId = DiagnosticReport.string(response, "deviceId"), deviceToken = DiagnosticReport.string(response, "deviceToken");
            String expires = DiagnosticReport.string(response, "expiresAt");
            if (!DiagnosticReport.uuid(deviceId) || !SyncSecretsStore.validToken(deviceToken) || !expires.endsWith("Z")) throw new Exception();
            Instant now = Instant.now(), expiresAt = Instant.parse(expires);
            if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plusSeconds(TimeUnit.DAYS.toSeconds(100)))) throw new Exception();
            synchronized (token.commitLock) {
                if (token.isCancelled() || generation != PAIRING_GENERATION.get()) throw new Failure("本次配对已取消，连接未保存。", false);
                secrets.savePaired(endpoint, deviceId, deviceToken, expires);
                return secrets.load();
            }
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new Failure("云端配对响应无效，连接未保存。", false); }
    }

    /** One queued report per explicit call. Unknown outcome is retried with the SAME report ID. */
    public static UploadResult uploadFirst(DiagnosticQueue queue, SyncSecretsStore secrets,
                                           Cancellation cancellation) throws IOException {
        if (queue == null || secrets == null) throw new Failure("无法读取云端诊断配置。", false);
        SyncSecretsStore.Connection receiver = secrets.load();
        if (receiver == null || receiver.expired(System.currentTimeMillis())) throw new Failure("请先连接云端；已有连接过期时需重新配对。", false);
        DiagnosticReport report = queue.first();
        if (report == null) return new UploadResult(false, false, "", 0);
        String credential = secrets.readTokenFor(receiver);
        Cancellation token = cancellation == null ? new Cancellation() : cancellation;
        JSONObject response = post(receiver.endpoint.diagnosticsUrl(), credential, report.bytes(), token);
        UploadResult result = parseAcknowledgement(response, report.reportId);
        if (token.isCancelled() || !receiver.sameReceiver(secrets.load())) throw new Failure("云端连接已变化，保留本机诊断待核对。", false);
        if (!queue.acknowledge(report.reportId)) throw new Failure("诊断已被云端接收，本机队列状态需重新读取。", false);
        return result;
    }

    static UploadResult parseAcknowledgement(JSONObject response, String reportId) throws Failure {
        try {
            DiagnosticReport.keys(response, Set.of("schema", "accepted", "duplicate", "cursor", "reportId"));
            DiagnosticReport.integer(response, "schema", 1, 1);
            if (!DiagnosticReport.bool(response, "accepted") || !reportId.equals(DiagnosticReport.string(response, "reportId"))) throw new Exception();
            return new UploadResult(true, DiagnosticReport.bool(response, "duplicate"), reportId,
                    DiagnosticReport.integer(response, "cursor", 1, DiagnosticReport.MAX_SEQUENCE));
        } catch (Exception error) { throw new Failure("云端未确认这份诊断，请核对服务版本；本机队列已保留。", false); }
    }

    private static JSONObject post(String url, String bearer, byte[] body, Cancellation token) throws Failure {
        if (body == null || body.length > DiagnosticReport.MAX_BYTES) throw new Failure("诊断内容超过安全上限，未发送。", false);
        if (!token.started.compareAndSet(false, true) || token.isCancelled()) throw new Failure("本次同步已取消。", false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        Future<JSONObject> future;
        try { future = WORKERS.submit(() -> request(url, bearer, body, token, deadline)); token.attach(future); }
        catch (RuntimeException error) { throw new Failure("上一轮同步尚未结束，请稍后重试。"); }
        try {
            long remaining = deadline - System.nanoTime(); if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) { token.cancel(); throw new Failure("云端同步超时，待发送诊断保留在手机。"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); token.cancel(); throw new Failure("本次同步已取消。", false); }
        catch (CancellationException error) { throw new Failure("本次同步已取消。", false); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Failure) throw (Failure) error.getCause();
            throw new Failure("云端同步未完成，待发送诊断保留在手机。");
        }
    }

    private static JSONObject request(String url, String bearer, byte[] body, Cancellation token, long deadline) throws Failure {
        HttpsURLConnection connection = null;
        try {
            ensureActive(token, deadline);
            connection = (HttpsURLConnection) new URL(url).openConnection(); token.attach(connection);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(Math.min(10000, remainingMs(deadline))); connection.setReadTimeout(remainingMs(deadline));
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Connection", "close"); connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Accept-Encoding", "identity");
            if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            ensureActive(token, deadline);
            try (OutputStream out = connection.getOutputStream()) { ensureActive(token, deadline); out.write(body); }
            ensureActive(token, deadline); int status = connection.getResponseCode(); rejectStatus(status);
            if (connection.getContentLengthLong() > MAX_REPLY_BYTES) throw new Failure("云端响应超出上限，请核对服务；未确认本次同步。", false);
            byte[] response;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while (true) {
                    ensureActive(token, deadline); connection.setReadTimeout(remainingMs(deadline)); count = in.read(buffer);
                    if (count == -1) break; if (out.size() + count > MAX_REPLY_BYTES) throw new Failure("云端响应超出上限，请核对服务；未确认本次同步。", false);
                    out.write(buffer, 0, count);
                }
                response = out.toByteArray();
            }
            ensureActive(token, deadline);
            return new JSONObject(new String(response, StandardCharsets.UTF_8));
        } catch (Failure error) { throw error; }
        catch (SocketTimeoutException error) { throw new Failure("云端同步超时，待发送诊断保留在手机。"); }
        catch (Exception error) {
            if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw new Failure("本次同步已取消。", false);
            throw new Failure("无法连接云端，请检查网络、HTTPS 地址和证书。");
        } finally { token.connection = null; if (connection != null) disconnect(connection); }
    }

    static void rejectStatus(int status) throws Failure {
        if (status >= 200 && status < 300) return;
        if (status >= 300 && status < 400) throw new Failure("云端返回跳转，请核对原地址；授权不会转发。", false);
        if (status == 401 || status == 403) throw new Failure("云端授权失效或配对码无效，请重新配对。", false);
        if (status == 409) throw new Failure("云端诊断序号冲突，请重新配对后核对待上传记录。", false);
        if (status == 413) throw new Failure("诊断超过云端上限，请更新助手后核对待上传记录。", false);
        if (status == 422) throw new Failure("诊断日期或格式无效，请校正时间；过期记录需手动清理。", false);
        if (status == 429) throw new Failure("云端暂时限制频率，稍后再试；诊断保留在手机。");
        if (status >= 400 && status < 500 && status != 408)
            throw new Failure("云端地址或诊断格式不匹配，请核对服务和助手版本。", false);
        throw new Failure("云端暂时无法接收，诊断保留在手机。");
    }
    private static void ensureActive(Cancellation token, long deadline) throws Failure {
        if (token.isCancelled() || Thread.currentThread().isInterrupted()) throw new Failure("本次同步已取消。", false);
        if (System.nanoTime() >= deadline) throw new Failure("云端同步超时，待发送诊断保留在手机。");
    }
    private static int remainingMs(long deadline) {
        return (int) Math.max(1, Math.min(20000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
    }
    private static void disconnect(HttpURLConnection connection) {
        Thread thread = new Thread(connection::disconnect, "ticketpilot-sync-disconnect"); thread.setDaemon(true); thread.start();
    }
}
