package cn.local.tickethelper.sync;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

public final class DiagnosticHistoryTest {
    private static final long NOW = Instant.parse("2026-10-28T02:00:00Z").toEpochMilli();
    private static File directory() throws Exception { return Files.createTempDirectory("ticketpilot-history-test").toFile(); }
    private static File file(File dir) { return new File(dir, "recent-events.json"); }
    private static JSONObject event() throws Exception { return new JSONObject().put("at", NOW).put("tag", "MONITOR").put("code", "READ_FAILED"); }
    private static void saved(File dir, JSONObject value) throws Exception { Files.write(file(dir).toPath(), value.toString().getBytes(StandardCharsets.UTF_8)); }

    @Test public void fixedEventsPersistAndRestoreWithoutPrivateFields() throws Exception {
        File dir = directory(); DiagnosticHistory history = new DiagnosticHistory(dir);
        assertTrue(history.record(NOW, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_FAILED));
        assertTrue(history.record(NOW + 1, DiagnosticReport.Tag.INPUT, DiagnosticReport.Code.INPUT_FAILED)); history.persist();
        List<DiagnosticReport.Event> restored = new DiagnosticHistory(dir).snapshot(); assertEquals(2, restored.size());
        assertEquals(NOW, restored.get(0).at); assertEquals(DiagnosticReport.Code.READ_FAILED, restored.get(0).code);
        assertEquals(DiagnosticReport.Tag.INPUT, restored.get(1).tag);
        JSONObject data = new JSONObject(new String(Files.readAllBytes(file(dir).toPath()), StandardCharsets.UTF_8)); assertEquals(2, data.length());
        assertEquals(3, data.getJSONArray("events").getJSONObject(0).length());
        String raw = data.toString();
        for (String forbidden : List.of("rawPage", "rawLog", "cookie", "Authorization", "deviceSerial", "apiKey", "viewerName", "orderId")) assertFalse(raw.contains(forbidden));
    }
    @Test public void historyKeepsNewest63AndLeavesRoomForManualReportEvent() throws Exception {
        File dir = directory(); DiagnosticHistory history = new DiagnosticHistory(dir);
        for (int i = 0; i < 500; i++) assertTrue(history.record(NOW + i, DiagnosticReport.Tag.MONITOR,
                i % 2 == 0 ? DiagnosticReport.Code.READ_FAILED : DiagnosticReport.Code.TIMEOUT));
        List<DiagnosticReport.Event> rows = history.snapshot(); assertEquals(DiagnosticReport.MAX_EVENTS - 1, rows.size());
        assertEquals(NOW + 437, rows.get(0).at); assertEquals(NOW + 499, rows.get(rows.size() - 1).at);
        history.persist(); assertTrue(file(dir).length() <= 16384); assertEquals(63, new DiagnosticHistory(dir).snapshot().size());
    }
    @Test public void repeatedSameEventIsDeduplicatedAcrossRestartButMeaningfulTransitionsRemain() throws Exception {
        File dir = directory(); DiagnosticHistory history = new DiagnosticHistory(dir);
        assertTrue(history.record(NOW, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_FAILED));
        assertFalse(history.record(NOW + 1, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_FAILED)); history.persist();
        history = new DiagnosticHistory(dir);
        assertFalse(history.record(NOW + 2, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_FAILED));
        assertTrue(history.record(NOW + 3, DiagnosticReport.Tag.CATALOG, DiagnosticReport.Code.READ_FAILED));
        assertTrue(history.record(NOW + 4, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.CONNECTED)); assertEquals(3, history.snapshot().size());
    }
    @Test public void invalidClockOrNullEnumsDoNotEnterHistory() throws Exception {
        DiagnosticHistory history = new DiagnosticHistory(directory());
        assertFalse(history.record(0, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_FAILED));
        assertFalse(history.record(Long.MAX_VALUE, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_FAILED));
        assertFalse(history.record(NOW, null, DiagnosticReport.Code.READ_FAILED));
        assertFalse(history.record(NOW, DiagnosticReport.Tag.MONITOR, null)); assertTrue(history.snapshot().isEmpty());
    }
    @Test public void snapshotDoesNotExposeMutableInternalList() throws Exception {
        DiagnosticHistory history = new DiagnosticHistory(directory()); history.record(NOW, DiagnosticReport.Tag.USER, DiagnosticReport.Code.MANUAL_REPORT);
        List<DiagnosticReport.Event> copy = history.snapshot(); copy.clear(); assertEquals(1, history.snapshot().size());
    }
    @Test public void malformedOrPrivateNestedFieldsRejectEntireRestoreWithoutDeletingEvidence() throws Exception {
        for (String field : List.of("rawPage", "code", "at")) {
            File dir = directory(); JSONObject changed = event();
            if (field.equals("rawPage")) changed.put(field, "synthetic private content");
            else if (field.equals("code")) changed.put(field, "ARBITRARY_PAGE_TEXT");
            else changed.put(field, Long.toString(NOW));
            saved(dir, new JSONObject().put("schema", 1).put("events", new JSONArray().put(event()).put(changed)));
            assertTrue(new DiagnosticHistory(dir).snapshot().isEmpty()); assertTrue(file(dir).exists());
        }
    }
    @Test public void oversizedOrTooManyEventsAreIgnoredWithoutPartialRestore() throws Exception {
        File dir = directory(); Files.write(file(dir).toPath(), "x".repeat(16385).getBytes(StandardCharsets.UTF_8));
        assertTrue(new DiagnosticHistory(dir).snapshot().isEmpty()); assertEquals(16385, file(dir).length());
        JSONArray tooMany = new JSONArray(); for (int i = 0; i < 64; i++) tooMany.put(event());
        saved(dir, new JSONObject().put("schema", 1).put("events", tooMany)); assertTrue(new DiagnosticHistory(dir).snapshot().isEmpty());
        saved(dir, new JSONObject().put("schema", 1).put("events", new JSONArray().put(event())).put("settings", "not permitted"));
        assertTrue(new DiagnosticHistory(dir).snapshot().isEmpty());
    }
    @Test public void atomicPersistenceNeverLeavesATemporaryFileAfterSuccess() throws Exception {
        File dir = directory(); DiagnosticHistory history = new DiagnosticHistory(dir);
        for (int i = 0; i < 20; i++) {
            history.record(NOW + i, DiagnosticReport.Tag.SERVICE, i % 2 == 0 ? DiagnosticReport.Code.CONNECTED : DiagnosticReport.Code.DISCONNECTED);
            history.persist(); assertEquals(i + 1, new DiagnosticHistory(dir).snapshot().size());
        }
        assertFalse(new File(dir, "recent-events.json.tmp").exists());
    }
}
