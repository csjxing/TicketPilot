package cn.local.tickethelper.sync;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Private, bounded, durable outbox. Only a matching receiver acknowledgement removes a report. */
public final class DiagnosticQueue {
    private static final Object LOCK = new Object();
    public static final int MAX_REPORTS = 32;
    private static final int MAX_FILE_BYTES = 1100000;
    private final File file;
    private static final class Record {
        String installId = UUID.randomUUID().toString();
        long next = 1, dropped = 0;
        List<DiagnosticReport> reports = new ArrayList<>();
    }
    public DiagnosticQueue(File privateDirectory) {
        if (privateDirectory == null) throw new IllegalArgumentException("Private directory required");
        file = new File(privateDirectory, "diagnostic-outbox.json");
    }
    public String installId() throws IOException {
        synchronized (LOCK) { Record record = read(); if (!file.exists()) write(record); return record.installId; }
    }
    public int size() throws IOException { synchronized (LOCK) { return read().reports.size(); } }
    public long droppedCount() throws IOException { synchronized (LOCK) { return read().dropped; } }
    public DiagnosticReport first() throws IOException {
        synchronized (LOCK) { Record record = read(); return record.reports.isEmpty() ? null : record.reports.get(0); }
    }
    public DiagnosticReport enqueue(DiagnosticReport.Snapshot snapshot, List<DiagnosticReport.Event> events, long nowMs) throws IOException {
        synchronized (LOCK) {
            Record record = read();
            if (record.next > DiagnosticReport.MAX_SEQUENCE) throw new IOException("诊断序号已满，请联系维护者。");
            DiagnosticReport report;
            try { report = new DiagnosticReport(UUID.randomUUID().toString(), record.installId, record.next, nowMs, snapshot, events); report.bytes(); }
            catch (RuntimeException error) { throw new IOException("无法生成安全诊断，请校正手机日期和应用版本。"); }
            if (!record.reports.isEmpty()) {
                DiagnosticReport last = record.reports.get(record.reports.size() - 1);
                if (last.contentKey().equals(report.contentKey())) return last;
            }
            record.next++;
            if (record.reports.size() >= MAX_REPORTS) { record.reports.remove(0); record.dropped++; }
            record.reports.add(report); write(record); return report;
        }
    }
    public boolean acknowledge(String reportId) throws IOException {
        synchronized (LOCK) {
            Record record = read();
            if (record.reports.isEmpty() || !record.reports.get(0).reportId.equals(reportId)) return false;
            record.reports.remove(0); write(record); return true;
        }
    }
    /** Explicit local clear retains installation identity and monotonically increasing sequence. */
    public void clearPending() throws IOException {
        synchronized (LOCK) { Record record = read(); record.reports.clear(); write(record); }
    }
    private Record read() throws IOException {
        if (!file.exists()) return new Record();
        try {
            if (file.length() > MAX_FILE_BYTES) throw new IOException();
            JSONObject value = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            DiagnosticReport.keys(value, Set.of("schema", "installId", "next", "dropped", "reports"));
            DiagnosticReport.integer(value, "schema", 1, 1);
            Record record = new Record(); record.installId = DiagnosticReport.string(value, "installId");
            if (!DiagnosticReport.uuid(record.installId)) throw new IOException();
            record.next = DiagnosticReport.integer(value, "next", 1, DiagnosticReport.MAX_SEQUENCE + 1);
            record.dropped = DiagnosticReport.integer(value, "dropped", 0, Long.MAX_VALUE);
            JSONArray reports = value.getJSONArray("reports"); if (reports.length() > MAX_REPORTS) throw new IOException();
            long previous = 0;
            for (int i = 0; i < reports.length(); i++) {
                DiagnosticReport report = DiagnosticReport.parse(reports.getJSONObject(i));
                if (!report.installId.equals(record.installId) || report.sequence <= previous || report.sequence >= record.next) throw new IOException();
                previous = report.sequence; record.reports.add(report);
            }
            return record;
        } catch (Exception error) { throw new IOException("本机诊断队列无法读取，未发送或清除任何记录。"); }
    }
    private void write(Record record) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            JSONArray reports = new JSONArray(); for (DiagnosticReport report : record.reports) reports.put(report.toJson());
            byte[] data = new JSONObject().put("schema", 1).put("installId", record.installId).put("next", record.next)
                    .put("dropped", record.dropped).put("reports", reports).toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_FILE_BYTES || (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())) throw new IOException();
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(data); out.getFD().sync(); }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!Arrays.equals(Files.readAllBytes(file.toPath()), data)) throw new IOException();
        } catch (Exception error) { throw new IOException("诊断记录未保存，请稍后重试。"); }
        finally { if (temporary.exists()) temporary.delete(); }
    }
}
