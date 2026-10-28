package cn.local.tickethelper.sync;

import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public final class DiagnosticReportTest {
    static final long NOW = Instant.parse("2026-10-27T02:00:00Z").toEpochMilli();
    static DiagnosticReport.Snapshot snapshot(DiagnosticReport.State state) {
        return new DiagnosticReport.Snapshot(new DiagnosticReport.Version("0.2.4-preview", 20),
                new DiagnosticReport.Version("9.0.37", 90037), 36, DiagnosticReport.A11y.READY,
                true, true, false, DiagnosticReport.Mode.PREPARE, state);
    }
    static DiagnosticReport report() {
        return new DiagnosticReport(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, NOW,
                snapshot(DiagnosticReport.State.IDLE), List.of(new DiagnosticReport.Event(NOW, DiagnosticReport.Tag.USER, DiagnosticReport.Code.MANUAL_REPORT)));
    }
    @Test public void strictRoundTripKeepsOnlyTypedFacts() {
        DiagnosticReport report = report(), roundTrip = DiagnosticReport.parse(report.toJson());
        assertEquals(report.reportId, roundTrip.reportId); assertEquals(report.installId, roundTrip.installId);
        assertEquals(report.contentKey(), roundTrip.contentKey()); assertEquals(report.createdAt, roundTrip.createdAt);
        assertTrue(report.bytes().length < 32768); assertEquals(11, report.toJson().length());
        assertEquals(DiagnosticReport.Code.MANUAL_REPORT, roundTrip.events.get(0).code);
    }
    @Test public void extraFieldsAreRejectedAtEveryNestedBoundary() throws Exception {
        for (String location : List.of("root", "appVersion", "damaiVersion", "readiness", "runtime", "event")) {
            JSONObject json = report().toJson();
            JSONObject target = location.equals("root") ? json : location.equals("event")
                    ? json.getJSONArray("events").getJSONObject(0) : json.getJSONObject(location);
            target.put("raw_text", "private fixture text");
            try { DiagnosticReport.parse(json); fail(location); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void numericStringsAndBooleanStringsCannotBypassSchema() throws Exception {
        for (String field : List.of("schema", "sequence", "androidApi")) {
            JSONObject json = report().toJson(); json.put(field, "1");
            try { DiagnosticReport.parse(json); fail(field); } catch (IllegalArgumentException expected) { }
        }
        JSONObject json = report().toJson(); json.getJSONObject("runtime").put("armed", "false");
        try { DiagnosticReport.parse(json); fail(); } catch (IllegalArgumentException expected) { }
        json = report().toJson(); json.put("sequence", 1.25);
        try { DiagnosticReport.parse(json); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void unknownEnumsAndUnstructuredVersionNamesAreRejected() throws Exception {
        JSONObject json = report().toJson(); json.getJSONArray("events").getJSONObject(0).put("code", "ANY_FREE_FORM_TEXT");
        try { DiagnosticReport.parse(json); fail(); } catch (IllegalArgumentException expected) { }
        json = report().toJson(); json.getJSONObject("runtime").put("state", "raw checkout text");
        try { DiagnosticReport.parse(json); fail(); } catch (IllegalArgumentException expected) { }
        for (String value : List.of("private fixture", "user@example.com", "9.0.37\nprivate", "", "9".repeat(65))) {
            try { new DiagnosticReport.Version(value, 2); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void missingDamaiIsExplicitNullWithoutSyntheticVersion() {
        DiagnosticReport.Snapshot known = snapshot(DiagnosticReport.State.STOPPED);
        DiagnosticReport.Snapshot missing = new DiagnosticReport.Snapshot(known.appVersion, null, 36, DiagnosticReport.A11y.UNKNOWN,
                false, false, false, DiagnosticReport.Mode.OFF, DiagnosticReport.State.UNKNOWN);
        DiagnosticReport report = new DiagnosticReport(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 2, NOW, missing, List.of());
        assertTrue(report.toJson().isNull("damaiVersion")); assertNull(DiagnosticReport.parse(report.toJson()).snapshot.damaiVersion);
    }
    @Test public void timestampAndIdentityBoundsFailClosed() throws Exception {
        JSONObject json = report().toJson(); json.put("installId", "device-serial");
        try { DiagnosticReport.parse(json); fail(); } catch (IllegalArgumentException expected) { }
        json = report().toJson(); json.put("createdAt", "2026-10-27T10:00:00+08:00");
        try { DiagnosticReport.parse(json); fail(); } catch (IllegalArgumentException expected) { }
        json = report().toJson(); json.put("sequence", DiagnosticReport.MAX_SEQUENCE + 1);
        try { DiagnosticReport.parse(json); fail(); } catch (IllegalArgumentException expected) { }
        try { new DiagnosticReport.Event(1, DiagnosticReport.Tag.USER, DiagnosticReport.Code.MANUAL_REPORT); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void eventListIsBoundedImmutableAndDedupIgnoresOnlyClock() {
        List<DiagnosticReport.Event> events = new ArrayList<>();
        for (int i = 0; i < 65; i++) events.add(new DiagnosticReport.Event(NOW + i, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.UNKNOWN));
        try { new DiagnosticReport(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, NOW,
                snapshot(DiagnosticReport.State.UNKNOWN), events); fail(); } catch (IllegalArgumentException expected) { }
        DiagnosticReport a = report(), b = new DiagnosticReport(UUID.randomUUID().toString(), a.installId, 2, NOW + 1,
                a.snapshot, List.of(new DiagnosticReport.Event(NOW + 1, DiagnosticReport.Tag.USER, DiagnosticReport.Code.MANUAL_REPORT)));
        assertEquals(a.contentKey(), b.contentKey());
        try { a.events.add(new DiagnosticReport.Event(NOW, DiagnosticReport.Tag.USER, DiagnosticReport.Code.UNKNOWN)); fail(); }
        catch (UnsupportedOperationException expected) { }
    }
    @Test public void timingQuantityAndDetailCodesRoundTripWithoutAddingFieldsOrChangingInventoryState() throws Exception {
        DiagnosticReport old = report();
        List<DiagnosticReport.Event> events = new ArrayList<>();
        for (DiagnosticReport.Code code : new DiagnosticReport.Code[]{DiagnosticReport.Code.SALE_WAITING,
                DiagnosticReport.Code.SALE_OPENING, DiagnosticReport.Code.SALE_MONITORING, DiagnosticReport.Code.SALE_STOPPED,
                DiagnosticReport.Code.SALE_RESERVATION_REENTRY, DiagnosticReport.Code.QUANTITY_INCREASE_DISPATCHED,
                DiagnosticReport.Code.QUANTITY_TARGET_UNVERIFIED, DiagnosticReport.Code.QUANTITY_TRANSITION_PAGE_CHANGED,
                DiagnosticReport.Code.QUANTITY_TRANSITION_UNVERIFIED, DiagnosticReport.Code.QUANTITY_INPUT_UNCONFIRMED,
                DiagnosticReport.Code.DETAIL_SCROLL_PROPOSED, DiagnosticReport.Code.DETAIL_SCROLL_COMMAND_COMPLETED,
                DiagnosticReport.Code.DETAIL_SCROLL_ANCHOR_UNVERIFIED, DiagnosticReport.Code.DETAIL_SCROLL_INPUT_UNCONFIRMED})
            events.add(new DiagnosticReport.Event(NOW, code.name().startsWith("SALE_") ? DiagnosticReport.Tag.MONITOR : DiagnosticReport.Tag.INPUT, code));
        DiagnosticReport expanded = new DiagnosticReport(old.reportId, old.installId, old.sequence, NOW,
                snapshot(DiagnosticReport.State.UNKNOWN), events);
        JSONObject json = expanded.toJson();
        assertEquals(1, json.getInt("schema")); assertEquals(11, json.length());
        DiagnosticReport restored = DiagnosticReport.parse(json);
        assertEquals(DiagnosticReport.State.UNKNOWN, restored.snapshot.state); assertEquals(events.size(), restored.events.size());
        for (int i = 0; i < events.size(); i++) {
            assertEquals(events.get(i).code, restored.events.get(i).code);
            assertEquals(3, json.getJSONArray("events").getJSONObject(i).length());
        }
        assertEquals(DiagnosticReport.Code.MANUAL_REPORT, DiagnosticReport.parse(old.toJson()).events.get(0).code);
        json.getJSONArray("events").getJSONObject(0).put("phase", "private text");
        try { DiagnosticReport.parse(json); fail(); } catch (IllegalArgumentException expected) { }
    }
}
