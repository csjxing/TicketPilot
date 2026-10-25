package cn.local.tickethelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One explicitly requested, bounded test against this application's own probe button only. */
final class TouchDiagnostics {
    interface Host {
        boolean isReady();
        void prepareProbe(String marker);
        JSONObject probeCounts();
        void stageFinished(JSONObject result);
        void finished(JSONObject result);
    }
    private static final String OWN_PACKAGE = "cn.local.tickethelper";
    private static final String[] STAGES = {"native_click", "gesture_70ms", "gesture_200ms"};
    private final MonitorService service;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    // A node action against our own Activity must not block its UI thread while waiting for it.
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final JSONObject report = new JSONObject();
    private final JSONArray results = new JSONArray();
    private final String runToken = UUID.randomUUID().toString();
    private volatile boolean active;
    private volatile Stage current;
    private int index;

    private static final class Stage {
        final int index;
        final String name, marker;
        final JSONObject result = new JSONObject();
        Stage(int index, String marker) {
            this.index = index; this.name = STAGES[index]; this.marker = marker;
            put(result, "stage", name);
            put(result, "started_at_ms", System.currentTimeMillis());
        }
    }

    TouchDiagnostics(MonitorService service, Host host) { this.service = service; this.host = host; }

    void start() {
        if (active) return;
        active = true;
        put(report, "schema", 1);
        put(report, "scope", "own_app_only");
        put(report, "sdk", Build.VERSION.SDK_INT);
        put(report, "started_at_ms", System.currentTimeMillis());
        put(report, "stages", results);
        next();
    }

    void cancel(String reason) {
        if (!active) return;
        Stage stage = current;
        if (stage != null) {
            put(stage.result, "status", reason);
            put(stage.result, "counts", host.probeCounts());
            put(stage.result, "passed", false);
            results.put(stage.result);
        }
        finish(reason);
    }

    private boolean ready() {
        return active && service != null && MonitorService.instance == service
                && !service.isRunning() && host.isReady();
    }

    private void next() {
        if (!ready()) { finish("own_app_not_ready_or_monitor_running"); return; }
        if (index >= STAGES.length) { finish("completed"); return; }
        Stage stage = new Stage(index, "ticket_helper_probe_" + runToken + "_" + index);
        current = stage;
        host.prepareProbe(stage.marker);
        main.postDelayed(() -> {
            if (isCurrent(stage)) cancel("stage_timeout");
        }, 3000);
        // Let the marker/content-description update reach the accessibility tree first.
        main.postDelayed(() -> {
            if (!isCurrent(stage)) return;
            if (!ready()) { cancel("own_app_not_ready_or_monitor_running"); return; }
            worker.execute(() -> dispatch(stage));
        }, 250);
    }

    private boolean isCurrent(Stage stage) { return active && current == stage; }

    private void dispatch(Stage stage) {
        try {
            if (!isCurrent(stage) || !ready()) { reject(stage, "own_app_not_ready_or_monitor_running"); return; }
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null || !OWN_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                reject(stage, "own_app_not_foreground"); return;
            }
            List<AccessibilityNodeInfo> matches = new ArrayList<>();
            find(root, stage.marker, matches, 0, new int[]{0});
            if (matches.size() != 1) { reject(stage, "probe_not_unique"); return; }
            AccessibilityNodeInfo node = matches.get(0);
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!node.isVisibleToUser() || !node.isEnabled() || !node.isClickable() || bounds.isEmpty()) {
                reject(stage, "probe_not_actionable"); return;
            }
            AccessibilityWindowInfo window = node.getWindow();
            if (window == null || !window.isActive()) { reject(stage, "probe_window_not_active"); return; }
            JSONObject identity = new JSONObject();
            put(identity, "package", OWN_PACKAGE);
            put(identity, "class", String.valueOf(node.getClassName()));
            put(identity, "view_id", node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName());
            put(identity, "window_id", node.getWindowId());
            put(identity, "display_id", window.getDisplayId());
            put(identity, "bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom));
            put(identity, "unique_marker_matches", matches.size());
            put(identity, "sensitive_flag_supported", Build.VERSION.SDK_INT >= 34);
            if (Build.VERSION.SDK_INT >= 34) put(identity, "accessibility_data_sensitive", node.isAccessibilityDataSensitive());
            main.post(() -> { if (isCurrent(stage)) put(stage.result, "node", identity); });
            // Sensitive views are a stop condition. This test never changes any protection flag.
            if (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive()) {
                reject(stage, "probe_sensitive"); return;
            }
            if (!isCurrent(stage) || !ready()) { reject(stage, "own_app_not_ready_or_monitor_running"); return; }
            if (stage.index == 0) {
                boolean accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                main.post(() -> {
                    if (!isCurrent(stage)) return;
                    put(stage.result, "api_return", accepted);
                    put(stage.result, "callback", "not_applicable");
                    settle(stage, accepted ? "action_returned" : "api_rejected");
                });
            } else {
                Path path = new Path(); path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
                int duration = stage.index == 1 ? 70 : 200;
                GestureDescription gesture = new GestureDescription.Builder().setDisplayId(window.getDisplayId())
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
                boolean accepted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gesture) {
                        if (!isCurrent(stage)) return;
                        put(stage.result, "callback", "completed"); settle(stage, "gesture_completed");
                    }
                    @Override public void onCancelled(GestureDescription gesture) {
                        if (!isCurrent(stage)) return;
                        put(stage.result, "callback", "cancelled"); settle(stage, "gesture_cancelled");
                    }
                }, main);
                main.post(() -> {
                    if (!isCurrent(stage)) return;
                    put(stage.result, "api_return", accepted);
                    put(stage.result, "duration_ms", duration);
                    if (!accepted) { put(stage.result, "callback", "not_dispatched"); settle(stage, "api_rejected"); }
                });
            }
        } catch (RuntimeException failure) { reject(stage, "exception_" + failure.getClass().getSimpleName()); }
    }

    private void reject(Stage stage, String reason) {
        main.post(() -> { if (isCurrent(stage)) cancel(reason); });
    }

    private void settle(Stage stage, String status) {
        main.postDelayed(() -> {
            if (!isCurrent(stage)) return;
            if (!ready()) { cancel("own_app_not_ready_or_monitor_running"); return; }
            JSONObject counts = host.probeCounts();
            boolean passed = stage.result.optBoolean("api_return", false) && counts.optInt("clicks") == 1
                    && (stage.index == 0 ? counts.optInt("down") == 0 && counts.optInt("up") == 0
                    : ("completed".equals(stage.result.optString("callback"))
                    && counts.optInt("down") == 1 && counts.optInt("up") == 1));
            put(stage.result, "status", status);
            put(stage.result, "counts", counts);
            put(stage.result, "passed", passed);
            put(stage.result, "finished_at_ms", System.currentTimeMillis());
            results.put(stage.result);
            current = null;
            host.stageFinished(stage.result);
            index++;
            main.postDelayed(this::next, 350);
        }, 250);
    }

    private void finish(String status) {
        if (!active) return;
        active = false; current = null;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        put(report, "status", status);
        put(report, "finished_at_ms", System.currentTimeMillis());
        boolean passed = results.length() == 3;
        for (int i = 0; i < results.length(); i++) passed &= results.optJSONObject(i).optBoolean("passed", false);
        put(report, "passed", passed);
        host.finished(report);
    }

    private static void find(AccessibilityNodeInfo node, String marker, List<AccessibilityNodeInfo> matches, int depth, int[] seen) {
        if (node == null || depth > 40 || ++seen[0] > 1000) return;
        if (marker.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) matches.add(node);
        for (int i = 0; i < node.getChildCount(); i++) find(node.getChild(i), marker, matches, depth + 1, seen);
    }

    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
