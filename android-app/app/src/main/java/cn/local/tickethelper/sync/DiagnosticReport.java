package cn.local.tickethelper.sync;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed diagnostic schema: no page text, log messages, names, order IDs or settings map. */
public final class DiagnosticReport {
    public static final int MAX_EVENTS = 64, MAX_BYTES = 32768;
    public static final long MAX_SEQUENCE = 9007199254740991L;
    private static final long EARLIEST_MS = 1577836800000L, LATEST_MS = 4102444800000L;
    public enum A11y { OFF, ENABLED_DISCONNECTED, READY, UNKNOWN }
    public enum Mode { OFF, ALERT, PREPARE, LOCK_ORDER }
    public enum State { IDLE, STOPPED, CONFIG_INVALID, EXPIRED, BLOCKED, UNRELATED, UNKNOWN,
        READY, PROBING, OUT_OF_STOCK, IN_STOCK, TIER_SELECTED, CHECKOUT, PENDING_PAYMENT_VERIFIED, NEEDS_REVIEW }
    public enum Tag { SERVICE, MONITOR, CATALOG, INPUT, CHECKOUT, UPDATE, SYNC, USER }
    public enum Code { MANUAL_REPORT, SESSION_STARTED, SESSION_STOPPED, CONNECTED, DISCONNECTED,
        PERMISSION_REQUIRED, FOREGROUND_CHANGED, RESERVATION_ONLY, IN_STOCK, OUT_OF_STOCK,
        TIER_SELECTED, CHECKOUT_LOADED, ORDER_PENDING_VERIFIED, ORDER_OUTCOME_UNKNOWN,
        READ_FAILED, READ_RETRY, READ_RECOVERED, INPUT_FAILED, TIMEOUT, CANCELLED, UPDATE_AVAILABLE, UPDATE_INSTALLED,
        CONFIG_INVALID, EXPIRED, BLOCKED, UNKNOWN }

    public static final class Version {
        public final String name;
        public final int code;
        public Version(String name, int code) {
            if (name == null || !name.matches("[0-9][A-Za-z0-9._+\\-]{0,63}") || code < 0)
                throw new IllegalArgumentException("Invalid diagnostic version");
            this.name = name; this.code = code;
        }
        JSONObject json() throws Exception { return new JSONObject().put("name", name).put("code", code); }
        static Version parse(JSONObject value) throws Exception {
            keys(value, Set.of("name", "code"));
            return new Version(string(value, "name"), (int) integer(value, "code", 0, Integer.MAX_VALUE));
        }
    }

    /** Callers supply typed readiness; this object cannot receive an AppStore or UI tree. */
    public static final class Snapshot {
        public final Version appVersion, damaiVersion;
        public final int androidApi;
        public final A11y a11y;
        public final boolean notifications, shizuku, armed;
        public final Mode mode;
        public final State state;
        public Snapshot(Version appVersion, Version damaiVersion, int androidApi, A11y a11y,
                        boolean notifications, boolean shizuku, boolean armed, Mode mode, State state) {
            if (appVersion == null || androidApi < 30 || androidApi > 99 || a11y == null || mode == null || state == null)
                throw new IllegalArgumentException("Invalid diagnostic snapshot");
            this.appVersion = appVersion; this.damaiVersion = damaiVersion; this.androidApi = androidApi;
            this.a11y = a11y; this.notifications = notifications; this.shizuku = shizuku;
            this.armed = armed; this.mode = mode; this.state = state;
        }
    }

    public static final class Event {
        public final long at;
        public final Tag tag;
        public final Code code;
        public Event(long at, Tag tag, Code code) {
            if (!validTime(at) || tag == null || code == null) throw new IllegalArgumentException("Invalid diagnostic event");
            this.at = at; this.tag = tag; this.code = code;
        }
        JSONObject json() throws Exception { return new JSONObject().put("at", at).put("tag", tag.name()).put("code", code.name()); }
        static Event parse(JSONObject value) throws Exception {
            keys(value, Set.of("at", "tag", "code"));
            return new Event(integer(value, "at", EARLIEST_MS, LATEST_MS),
                    Tag.valueOf(string(value, "tag")), Code.valueOf(string(value, "code")));
        }
    }

    public final String reportId, installId, createdAt;
    public final long sequence;
    public final Snapshot snapshot;
    public final List<Event> events;

    public DiagnosticReport(String reportId, String installId, long sequence, long createdAtMs,
                            Snapshot snapshot, List<Event> events) {
        if (!uuid(reportId) || !uuid(installId) || sequence <= 0 || sequence > MAX_SEQUENCE || !validTime(createdAtMs)
                || snapshot == null || events == null || events.size() > MAX_EVENTS || events.stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("Invalid diagnostic report");
        this.reportId = reportId; this.installId = installId; this.sequence = sequence;
        this.createdAt = Instant.ofEpochMilli(createdAtMs).toString(); this.snapshot = snapshot;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
    }

    public JSONObject toJson() {
        try {
            JSONArray items = new JSONArray(); for (Event event : events) items.put(event.json());
            return new JSONObject().put("schema", 1).put("reportId", reportId).put("installId", installId)
                    .put("sequence", sequence).put("createdAt", createdAt).put("appVersion", snapshot.appVersion.json())
                    .put("damaiVersion", snapshot.damaiVersion == null ? JSONObject.NULL : snapshot.damaiVersion.json())
                    .put("androidApi", snapshot.androidApi)
                    .put("readiness", new JSONObject().put("a11y", snapshot.a11y.name())
                            .put("notifications", snapshot.notifications).put("shizuku", snapshot.shizuku))
                    .put("runtime", new JSONObject().put("armed", snapshot.armed).put("mode", snapshot.mode.name()).put("state", snapshot.state.name()))
                    .put("events", items);
        } catch (Exception error) { throw new IllegalStateException("Unable to encode diagnostic report"); }
    }

    public byte[] bytes() {
        byte[] data = toJson().toString().getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("Diagnostic report too large");
        return data;
    }

    /** Defends imported/persisted records too: extra fields cannot hide private text. */
    public static DiagnosticReport parse(JSONObject value) {
        try {
            keys(value, Set.of("schema", "reportId", "installId", "sequence", "createdAt", "appVersion", "damaiVersion",
                    "androidApi", "readiness", "runtime", "events"));
            integer(value, "schema", 1, 1);
            JSONObject ready = value.getJSONObject("readiness"), runtime = value.getJSONObject("runtime");
            keys(ready, Set.of("a11y", "notifications", "shizuku"));
            keys(runtime, Set.of("armed", "mode", "state"));
            Snapshot snap = new Snapshot(Version.parse(value.getJSONObject("appVersion")),
                    value.isNull("damaiVersion") ? null : Version.parse(value.getJSONObject("damaiVersion")),
                    (int) integer(value, "androidApi", 30, 99), A11y.valueOf(string(ready, "a11y")),
                    bool(ready, "notifications"), bool(ready, "shizuku"), bool(runtime, "armed"),
                    Mode.valueOf(string(runtime, "mode")), State.valueOf(string(runtime, "state")));
            JSONArray items = value.getJSONArray("events");
            if (items.length() > MAX_EVENTS) throw new IllegalArgumentException();
            List<Event> events = new ArrayList<>(); for (int i = 0; i < items.length(); i++) events.add(Event.parse(items.getJSONObject(i)));
            String at = string(value, "createdAt"); if (!at.endsWith("Z")) throw new IllegalArgumentException();
            DiagnosticReport result = new DiagnosticReport(string(value, "reportId"), string(value, "installId"),
                    integer(value, "sequence", 1, MAX_SEQUENCE), Instant.parse(at).toEpochMilli(), snap, events);
            result.bytes(); return result;
        } catch (Exception error) { throw new IllegalArgumentException("Invalid diagnostic report"); }
    }

    /** Deduplication ignores capture clock/IDs, but retains readiness, state and typed event order. */
    String contentKey() {
        // JSON object iteration order is not contractual: compare a canonical list of facts.
        StringBuilder key = new StringBuilder().append(snapshot.appVersion.name).append('/').append(snapshot.appVersion.code)
                .append('|').append(snapshot.damaiVersion == null ? "" : snapshot.damaiVersion.name + '/' + snapshot.damaiVersion.code)
                .append('|').append(snapshot.androidApi).append('|').append(snapshot.a11y).append('|').append(snapshot.notifications)
                .append('|').append(snapshot.shizuku).append('|').append(snapshot.armed).append('|').append(snapshot.mode).append('|').append(snapshot.state);
        for (Event event : events) key.append('|').append(event.tag).append(':').append(event.code);
        return key.toString();
    }

    static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).version() == 4 && UUID.fromString(value).variant() == 2
                && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException error) { return false; }
    }
    private static boolean validTime(long at) { return at >= EARLIEST_MS && at <= LATEST_MS; }
    static void keys(JSONObject value, Set<String> expected) {
        if (value == null || value.length() != expected.size()) throw new IllegalArgumentException();
        Iterator<String> iterator = value.keys(); while (iterator.hasNext()) if (!expected.contains(iterator.next())) throw new IllegalArgumentException();
    }
    static String string(JSONObject value, String key) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof String)) throw new IllegalArgumentException(); return (String) raw;
    }
    static boolean bool(JSONObject value, String key) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof Boolean)) throw new IllegalArgumentException(); return (Boolean) raw;
    }
    static long integer(JSONObject value, String key, long min, long max) throws Exception {
        Object raw = value.get(key); if (!(raw instanceof Integer) && !(raw instanceof Long)) throw new IllegalArgumentException();
        long number = ((Number) raw).longValue(); if (number < min || number > max) throw new IllegalArgumentException(); return number;
    }
}
