package cn.local.tickethelper;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit, debug-only native structure capture. The nonexported manifest receiver enforces the
 * same-UID boundary. No input, navigation, screenshot, security-setting or network operation exists.
 */
public final class DebugUiSnapshotReceiver extends BroadcastReceiver {
    public static final String ACTION = "cn.local.tickethelper.DEBUG_NATIVE_STRUCTURE";
    private static final String DAMAI = "cn.damai";
    private static final int MAX_NODES = 1200, MAX_DEPTH = 45, MAX_BYTES = 1500000;
    private static final long CAPTURE_MS = 2500, WATCHDOG_MS = 4000;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final Object REPORT_LOCK = new Object();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        PendingResult pending = goAsync();
        if (!IN_FLIGHT.compareAndSet(false, true)) { pending.finish(); return; }
        new Probe(context.getApplicationContext(), pending).start();
    }

    private static final class Probe {
        final Context context;
        final PendingResult pending;
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean done = new AtomicBoolean();
        final MonitorService service = MonitorService.instance;
        final long startedAt = System.currentTimeMillis(), began = SystemClock.elapsedRealtime();
        final JSONObject report = new JSONObject();
        final Runnable watchdog = () -> finish("capture_timeout", null);

        Probe(Context context, PendingResult pending) {
            this.context = context; this.pending = pending;
            put(report, "schema", 1); put(report, "scope", "debug_read_only_native_structure");
            put(report, "requested_at_ms", startedAt); put(report, "package", DAMAI);
            put(report, "status", "not_captured");
        }
        void start() {
            main.postDelayed(watchdog, WATCHDOG_MS);
            Thread worker = new Thread(this::run, "ticketpilot-debug-native-structure");
            worker.setDaemon(true); worker.start();
        }
        void run() {
            try {
                if (!ready()) { finish("service_or_screen_not_ready", null); return; }
                if (armed()) { finish("monitor_armed", null); return; }
                PackageInfo installed = context.getPackageManager().getPackageInfo(DAMAI, 0);
                String version = installed.versionName;
                if (version == null || !version.matches("[0-9][A-Za-z0-9._+\\-]{0,63}")) {
                    finish("version_unavailable", null); return;
                }
                synchronized (REPORT_LOCK) {
                    put(report, "damai_version", version); put(report, "damai_version_code", installed.getLongVersionCode());
                }
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (!validRoot(root)) { finish("foreground_or_window_unverified", null); return; }
                int windowId = root.getWindowId();
                Capture first = new Capture(windowId);
                first.collect(root, -1, 0, false);
                if (!first.complete || expired()) { finish("incomplete_or_expired", null); return; }
                if (!ready() || armed()) { finish("service_or_screen_changed", null); return; }
                AccessibilityNodeInfo next = service.getRootInActiveWindow();
                if (!validRoot(next) || !root.equals(next) || next.getWindowId() != windowId) { finish("root_changed", null); return; }
                Capture second = new Capture(windowId);
                second.collect(next, -1, 0, false);
                AccessibilityNodeInfo after = service.getRootInActiveWindow();
                if (!second.complete || expired()) { finish("incomplete_or_expired", null); return; }
                if (!ready() || armed() || !validRoot(after)
                        || !root.equals(after) || after.getWindowId() != windowId
                        || !first.nodes.toString().equals(second.nodes.toString())) {
                    finish("stale_structure", null); return;
                }
                synchronized (REPORT_LOCK) {
                    put(report, "window_id", windowId); put(report, "display_id", next.getWindow().getDisplayId());
                    put(report, "captured_at_ms", System.currentTimeMillis());
                    put(report, "node_count", second.nodes.length());
                }
                finish("captured", second.nodes);
            } catch (Exception failure) { finish("capture_failed", null); }
            finally {
                // A watchdog can finish a blocked Binder request, but no second capture starts
                // until this worker actually exits. Never accumulate background capture threads.
                IN_FLIGHT.set(false);
            }
        }
        boolean ready() {
            PowerManager power = context.getSystemService(PowerManager.class);
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            return !done.get() && MonitorService.instance == service && service != null
                    && service.isConnectionReady() && !service.isRunning()
                    && power != null && power.isInteractive() && keyguard != null && !keyguard.isKeyguardLocked();
        }
        boolean armed() { return context.getSharedPreferences("ticket_helper", Context.MODE_PRIVATE).getBoolean("armed", false); }
        boolean expired() { return done.get() || SystemClock.elapsedRealtime() - began > CAPTURE_MS; }
        boolean validRoot(AccessibilityNodeInfo root) {
            if (root == null || !root.refresh() || !root.isVisibleToUser()
                    || !DAMAI.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
            AccessibilityWindowInfo window = root.getWindow();
            return window != null && window.isActive() && root.getWindowId() >= 0
                    && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                    && window.getDisplayId() == Display.DEFAULT_DISPLAY;
        }
        final class Capture {
            final JSONArray nodes = new JSONArray();
            final int windowId;
            boolean complete = true;
            Capture(int windowId) { this.windowId = windowId; }
            void collect(AccessibilityNodeInfo node, int parent, int depth, boolean protectedParent) {
                if (!complete) return;
                if (node == null || depth > MAX_DEPTH || nodes.length() >= MAX_NODES || expired()
                        || !node.refresh() || node.getWindowId() != windowId
                        || !DAMAI.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                    complete = false; return;
                }
                boolean password = node.isPassword();
                boolean nodeSensitive = password || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive());
                boolean sensitive = protectedParent || nodeSensitive;
                String rawId = node.getViewIdResourceName();
                String id = DebugSnapshotPolicy.resourceId(rawId);
                Rect rect = new Rect(); node.getBoundsInScreen(rect);
                JSONObject item = new JSONObject(); int index = nodes.length();
                put(item, "index", index); put(item, "parent", parent); put(item, "depth", depth);
                put(item, "id", id); put(item, "class", DebugSnapshotPolicy.className(node.getClassName()));
                put(item, "bounds", new JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom));
                put(item, "window_id", windowId); put(item, "visible", node.isVisibleToUser());
                put(item, "enabled", node.isEnabled()); put(item, "clickable", node.isClickable());
                put(item, "selected", node.isSelected()); put(item, "checked", node.isChecked());
                put(item, "checkable", node.isCheckable()); put(item, "password", password);
                put(item, "nodeSensitive", nodeSensitive); put(item, "sensitive", sensitive);
                put(item, "textRead", !sensitive);
                if (sensitive) {
                    // Presence cannot be checked without reading a protected value. Report unknown.
                    put(item, "textPresent", JSONObject.NULL); put(item, "descriptionPresent", JSONObject.NULL);
                    put(item, "text", ""); put(item, "description", ""); put(item, "redacted", true);
                } else {
                    CharSequence text = node.getText(), description = node.getContentDescription();
                    String publicText = DebugSnapshotPolicy.publicText(text, rawId);
                    String publicDescription = DebugSnapshotPolicy.publicText(description, "");
                    boolean textPresent = text != null && text.length() > 0;
                    boolean descriptionPresent = description != null && description.length() > 0;
                    put(item, "textPresent", textPresent); put(item, "descriptionPresent", descriptionPresent);
                    put(item, "text", publicText); put(item, "description", publicDescription);
                    put(item, "redacted", (textPresent && publicText.isEmpty()) || (descriptionPresent && publicDescription.isEmpty()));
                }
                nodes.put(item);
                int count = node.getChildCount();
                if (count > MAX_NODES - nodes.length()) { complete = false; return; }
                for (int i = 0; complete && i < count; i++) {
                    if (expired()) { complete = false; return; }
                    collect(node.getChild(i), index, depth + 1, sensitive);
                }
            }
        }
        void finish(String status, JSONArray nodes) {
            if (!done.compareAndSet(false, true)) return;
            main.removeCallbacks(watchdog);
            try {
                synchronized (REPORT_LOCK) {
                    put(report, "status", status); put(report, "finished_at_ms", System.currentTimeMillis());
                    put(report, "elapsed_ms", SystemClock.elapsedRealtime() - began);
                    if (nodes != null && "captured".equals(status)) put(report, "nodes", nodes);
                    byte[] data = report.toString(2).getBytes(StandardCharsets.UTF_8);
                    if (data.length > MAX_BYTES) {
                        report.remove("nodes"); put(report, "status", "report_size_limit");
                        data = report.toString(2).getBytes(StandardCharsets.UTF_8);
                    }
                    AtomicFile output = new AtomicFile(new File(context.getFilesDir(), "debug-native-structure.json"));
                    FileOutputStream stream = null;
                    try {
                        stream = output.startWrite(); stream.write(data); stream.getFD().sync(); output.finishWrite(stream);
                    } catch (Exception failure) { if (stream != null) output.failWrite(stream); }
                }
            } catch (Exception ignored) { /* No exception text, source page content or identity is logged. */ }
            finally { pending.finish(); }
        }
    }
    private static void put(JSONObject value, String key, Object item) {
        try { value.put(key, item); } catch (Exception ignored) { }
    }
}
