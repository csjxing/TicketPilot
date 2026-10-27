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
import java.util.List;

/** Local fixed-enum history; never accepts a log line, exception body or page text. */
public final class DiagnosticHistory {
    private static final int LIMIT = DiagnosticReport.MAX_EVENTS - 1, MAX_FILE_BYTES = 16384;
    private final File file;
    private final Object writerLock = new Object();
    private final List<DiagnosticReport.Event> events = new ArrayList<>();
    public DiagnosticHistory(File directory) {
        file = new File(directory, "recent-events.json");
        try {
            if (!file.exists() || file.length() > MAX_FILE_BYTES) return;
            JSONObject value = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            DiagnosticReport.keys(value, java.util.Set.of("schema", "events"));
            DiagnosticReport.integer(value, "schema", 1, 1);
            JSONArray rows = value.getJSONArray("events");
            if (rows.length() > LIMIT) return;
            List<DiagnosticReport.Event> restored = new ArrayList<>();
            for (int i=0;i<rows.length();i++) restored.add(DiagnosticReport.Event.parse(rows.getJSONObject(i)));
            events.addAll(restored);
        } catch (Exception ignored) { /* A bad diagnostic copy never prevents local operation. */ }
    }
    public synchronized boolean record(long at, DiagnosticReport.Tag tag, DiagnosticReport.Code code) {
        DiagnosticReport.Event event;
        try { event = new DiagnosticReport.Event(at, tag, code); }
        catch (IllegalArgumentException invalidClock) { return false; }
        if (!events.isEmpty()) {
            DiagnosticReport.Event previous = events.get(events.size()-1);
            if (previous.tag == tag && previous.code == code) return false;
        }
        if (events.size() == LIMIT) events.remove(0);
        events.add(event); return true;
    }
    public synchronized List<DiagnosticReport.Event> snapshot() { return new ArrayList<>(events); }
    public void persist() throws IOException {
        synchronized (writerLock) { persistSnapshot(snapshot()); }
    }
    private void persistSnapshot(List<DiagnosticReport.Event> copy) throws IOException {
        File temporary = new File(file.getParentFile(), file.getName()+".tmp");
        try {
            JSONArray rows = new JSONArray(); for (DiagnosticReport.Event event : copy) rows.put(event.json());
            byte[] bytes = new JSONObject().put("schema",1).put("events",rows).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_FILE_BYTES || (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs())) throw new IOException();
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) { throw new IOException("本机诊断历史未保存。"); }
        finally { if (temporary.exists()) temporary.delete(); }
    }
}
