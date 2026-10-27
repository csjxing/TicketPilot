package cn.local.tickethelper.relay;

import cn.local.tickethelper.sync.DiagnosticReport;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Durable, single diagnostic transfer. Unknown outcomes reuse the same server idempotency keys. */
final class RelayTransfer {
    private static final Object LOCK = new Object();
    private final File file;
    static final class Job {
        RelayEndpoint endpoint; String binding, requestId, itemId = "", fileId = "", completedAt = "", etag = "";
        DiagnosticReport report;
        byte[] bytes() { return report.bytes(); }
        String filename() { return "ticketpilot-diagnostic-" + report.reportId + ".json"; }
        String summary() { return "TicketPilot 诊断（仅固定状态码，无原始页面/实名/密钥/订单正文）\n"
                + "App " + report.snapshot.appVersion.name + " / code" + report.snapshot.appVersion.code
                + "\n状态 " + report.snapshot.state.name() + "；无障碍 " + report.snapshot.a11y.name()
                + "\n记录 " + report.reportId + "\nSHA-256 " + hash(bytes()); }
        String title() { return "TicketPilot 诊断 · " + report.snapshot.appVersion.name; }
        boolean complete() { return !completedAt.isEmpty(); }
    }
    RelayTransfer(File directory) { if (directory == null) throw new IllegalArgumentException(); file = new File(directory, "relay-diagnostic-transfer.json"); }
    Job read() throws IOException {
        synchronized (LOCK) {
            if (!file.exists()) return null;
            try {
                if (file.length() > 131072) throw new IOException();
                JSONObject value = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                Object schemaValue = value.get("schema");
                if (!(schemaValue instanceof Integer)) throw new IOException();
                int schema = (Integer) schemaValue;
                if (!(schema == 1 && value.length() == 8 || schema == 2 && value.length() == 9)) throw new IOException();
                Job job = new Job(); job.endpoint = new RelayEndpoint(value.getString("origin")); job.binding = value.getString("binding");
                job.requestId = value.getString("requestId"); job.itemId = value.getString("itemId"); job.fileId = value.getString("fileId");
                job.completedAt = value.getString("completedAt"); job.report = DiagnosticReport.parse(value.getJSONObject("report"));
                if (schema == 2) job.etag = value.getString("etag");
                if (!uuid(job.binding) || !uuid(job.requestId) || (!job.itemId.isEmpty() && !validId(job.itemId))
                        || (!job.fileId.isEmpty() && (!validId(job.fileId) || job.itemId.isEmpty()))
                        || (!job.etag.isEmpty() && job.fileId.isEmpty())
                        || (job.complete() && (job.fileId.isEmpty() || !job.completedAt.endsWith("Z")))) throw new IOException();
                if (!job.etag.isEmpty()) RelayClient.etag(job.etag);
                if (job.complete()) Instant.parse(job.completedAt);
                return job;
            } catch (Exception error) { throw new IOException("上次中转记录无法读取，请先核查文件中转站；没有另建或删除记录。"); }
        }
    }
    Job prepare(RelaySecretsStore.Connection connection, DiagnosticReport report) throws IOException {
        synchronized (LOCK) {
            Job previous = read();
            if (previous != null && !previous.complete()) return previous;
            if (previous != null && previous.binding.equals(connection.binding) && previous.report.reportId.equals(report.reportId)) return previous;
            Job job = new Job(); job.endpoint = connection.endpoint; job.binding = connection.binding;
            job.requestId = UUID.randomUUID().toString(); job.report = DiagnosticReport.parse(report.toJson()); save(job); return job;
        }
    }
    void save(Job job) throws IOException {
        synchronized (LOCK) {
            File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
            try {
                byte[] bytes = new JSONObject().put("schema", 2).put("origin", job.endpoint.origin).put("binding", job.binding)
                        .put("requestId", job.requestId).put("itemId", job.itemId).put("fileId", job.fileId)
                        .put("completedAt", job.completedAt).put("etag", job.etag).put("report", job.report.toJson()).toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 131072 || (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())) throw new IOException();
                try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                if (!Arrays.equals(bytes, Files.readAllBytes(file.toPath()))) throw new IOException();
            } catch (Exception error) { throw new IOException("中转进度未保存，已停止网络操作；请稍后核查。"); }
            finally { if (temporary.exists()) temporary.delete(); }
        }
    }
    void discard() throws IOException {
        synchronized (LOCK) {
            try { Files.deleteIfExists(file.toPath()); }
            catch (Exception error) { throw new IOException("本机待发送诊断尚未清除。"); }
        }
    }
    Job upload(Job job, RelayClient client, RelayClient.Cancellation token) throws IOException {
        synchronized (LOCK) {
            if (job.complete()) return job;
            token.check(); client.verifyManage(token); // Recheck the scope after key revocation or changes.
            try {
                if (job.itemId.isEmpty()) {
                    JSONObject created = client.create(job.summary(), job.title(), "tp-diag-" + job.requestId, token);
                    job.itemId = RelayEndpoint.segment(created.getString("id")); save(job);
                }
                if (job.fileId.isEmpty()) {
                    JSONObject session = client.reserve(job.itemId, job.filename(), job.bytes().length, "tp-file-" + job.requestId, token);
                    job.fileId = RelayEndpoint.segment(session.getString("fileId")); save(job);
                }
                if (!job.etag.isEmpty()) {
                    // A prior merge may have succeeded while its DB ready update/reply was lost.
                    // The multipart upload may already be gone, so do not query parts or send PUT
                    // again. The server's idempotent complete performs HEAD recovery of that object.
                    token.check(); client.complete(job.itemId, job.fileId, job.etag, token);
                } else {
                    JSONObject existing = client.parts(job.itemId, job.fileId, token);
                    if (!"ready".equals(existing.optString("status"))) {
                        if (existing.getInt("partSize") != 8388608 || existing.getInt("partCount") != 1) throw new IOException("上传会话不匹配，保留本次记录待核查。");
                        JSONArray uploaded = existing.getJSONArray("parts");
                        if (uploaded.length() > 1) throw new IOException("上传分片不匹配，保留本次记录待核查。");
                        String etag;
                        if (uploaded.length() == 1) {
                            if (uploaded.getJSONObject(0).getInt("partNumber") != 1) throw new IOException("上传分片不匹配，保留本次记录待核查。");
                            etag = RelayClient.etag(uploaded.getJSONObject(0).getString("etag"));
                        } else etag = client.put(client.sign(job.itemId, job.fileId, token), job.bytes(), token);
                        // Persist the exact completion inputs before the consequential request.
                        job.etag = etag; save(job);
                        token.check(); client.complete(job.itemId, job.fileId, etag, token);
                    }
                }
                verifyReady(job, client.detail(job.itemId, token));
                token.check(); job.completedAt = Instant.now().toString(); save(job); return job;
            } catch (IOException error) { throw error; }
            catch (Exception error) { throw new IOException("无法核实中转结果，保留同一记录；稍后继续上传会先读取进度。"); }
        }
    }
    static void verifyPreview(Job job, String reportId, String sha256) throws IOException {
        if (job == null || reportId == null || sha256 == null || !reportId.equals(job.report.reportId)
                || !sha256.matches("[a-f0-9]{64}") || !sha256.equals(hash(job.bytes())))
            throw new IOException("待发送诊断已变化，请重新预览后再发送；没有上传新的内容。");
    }
    static void verifyReady(Job job, JSONObject value) throws IOException {
        try {
            if (!job.itemId.equals(value.getString("id")) || !job.summary().equals(value.getString("text"))) throw new IOException();
            JSONArray files = value.getJSONArray("files"); if (files.length() > 20) throw new IOException();
            int matches = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                if (job.fileId.equals(entry.getString("id"))) {
                    if (!"ready".equals(entry.getString("status")) || !job.filename().equals(entry.getString("filename"))
                            || !"application/json".equals(entry.getString("contentType")) || entry.getLong("size") != job.bytes().length) throw new IOException();
                    matches++;
                }
            }
            if (matches != 1) throw new IOException();
        } catch (Exception error) { throw new IOException("中转站尚未确认诊断附件就绪，请保留同一记录并稍后核实。"); }
    }
    static String hash(byte[] bytes) {
        try { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{1,128}"); }
    private static boolean uuid(String id) { try { return id != null && UUID.fromString(id).toString().equals(id) && UUID.fromString(id).version() == 4; } catch (Exception e) { return false; } }
}
