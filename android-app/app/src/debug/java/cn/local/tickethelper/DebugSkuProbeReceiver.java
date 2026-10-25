package cn.local.tickethelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.MagnificationConfig;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityWindowInfo;
import cn.local.tickethelper.core.MonitorConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debug-only, same-UID controlled comparison with the production OCR/action chain.
 * The operator must freshly verify the unselected 980-yuan cell on the known test page.
 * These test-specific bounds and externally verified semantics are NOT an unattended adapter.
 * A normal public gesture is dispatched once; no protection flags or settings are changed.
 */
public final class DebugSkuProbeReceiver extends BroadcastReceiver {
    public static final String ACTION = "cn.local.tickethelper.DEBUG_SKU_TOUCH";
    private static final String DAMAI = "cn.damai";
    private static final String ID = DAMAI + ":id/";
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final Object REPORT_LOCK = new Object();
    // The only manually verified cell in this diagnostic. Never accept arbitrary target points.
    private static final Rect TEST_CELL = new Rect(455, 1550, 804, 1718);
    private static final Rect TEST_PARENT = new Rect(423, 1550, 804, 1750);
    // Confirmed by the actual SKU page dump; this is a back control, never a buy/submit button.
    private static final String BACK_ID = ID + "title_back_btn";
    private static final Rect TEST_BACK = new Rect(74, 144, 158, 298);

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        if (!IN_FLIGHT.compareAndSet(false, true)) { pending.finish(); return; }
        Rect requested = new Rect(intent.getIntExtra("left", -1), intent.getIntExtra("top", -1),
                intent.getIntExtra("right", -1), intent.getIntExtra("bottom", -1));
        String mode = intent.getStringExtra("mode");
        int durationMs = intent.getIntExtra("duration_ms", 70);
        new Probe(app, pending, mode == null ? "sku" : mode, requested, durationMs).start();
    }

    private static final class Probe {
        private final Context context;
        private final PendingResult pending;
        private final Rect requested;
        private final String mode;
        private final boolean backControl;
        private final boolean parentClick;
        private final int durationMs;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final AtomicBoolean done = new AtomicBoolean();
        private final JSONObject report = new JSONObject();
        private final long began = SystemClock.elapsedRealtime();
        private final MonitorService service = MonitorService.instance;

        Probe(Context context, PendingResult pending, String mode, Rect requested, int durationMs) {
            this.context = context; this.pending = pending; this.mode = mode;
            this.backControl = "back_control".equals(mode);
            this.parentClick = "sku_parent_click".equals(mode);
            this.durationMs = backControl ? 70 : parentClick ? 0 : durationMs;
            this.requested = backControl ? new Rect(TEST_BACK) : parentClick ? new Rect(TEST_CELL) : requested;
            put("schema", 1); put("scope", "one_debug_test_sku_only");
            put("mode", mode);
            put("started_at_ms", System.currentTimeMillis());
            put("action", parentClick ? "accessibility_action_click" : "gesture");
            if (!parentClick) put("duration_ms", this.durationMs);
            put("requested_bounds", boundsJson(this.requested));
            put("callback", "not_dispatched");
        }

        void start() {
            main.postDelayed(() -> finish("timeout"), 3000);
            new Thread(this::run, "ticket-helper-debug-sku-probe").start();
        }

        private void run() {
            try {
                if (!"sku".equals(mode) && !backControl && !parentClick) { finish("rejected_mode"); return; }
                if ("sku".equals(mode) && durationMs != 70 && durationMs != 200) {
                    finish("rejected_duration"); return;
                }
                if (!backControl && !TEST_CELL.equals(requested)) { finish("rejected_test_bounds"); return; }
                if (!serviceReady()) { finish("rejected_service_or_screen_state"); return; }
                AppStore store = new AppStore(context);
                MonitorConfig config = store.loadConfig();
                if (store.isArmed() || !testConfig(config)) { finish("rejected_test_configuration"); return; }
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (!damaiRoot(root)) { finish("rejected_foreground"); return; }
                List<AccessibilityNodeInfo> cells = new ArrayList<>(), dates = new ArrayList<>(), prices = new ArrayList<>(), backs = new ArrayList<>();
                collect(root, cells, dates, prices, backs, 0, new int[]{0});
                put("matching_cells", cells.size()); put("date_regions", dates.size()); put("price_regions", prices.size());
                put("back_nodes", backs.size());
                if (cells.size() != 1 || dates.size() != 1 || prices.size() != 1 || (backControl && backs.size() != 1)) {
                    finish("rejected_page_structure"); return;
                }
                AccessibilityNodeInfo cell = cells.get(0);
                Rect dateBounds = bounds(dates.get(0)), priceBounds = bounds(prices.get(0));
                if (!visible(dates.get(0)) || !visible(prices.get(0)) || !priceBounds.contains(TEST_CELL)
                        || dateBounds.isEmpty() || dateBounds.bottom > priceBounds.top) {
                    finish("rejected_page_regions"); return;
                }
                if (!cell.refresh() || !visible(cell) || !TEST_CELL.equals(bounds(cell))) {
                    finish("rejected_stale_cell"); return;
                }
                AccessibilityNodeInfo target = backControl ? backs.get(0)
                        : parentClick ? skuParent(cell, priceBounds) : cell;
                if (target == null) { finish("rejected_sku_parent"); return; }
                Rect targetBounds = parentClick ? TEST_PARENT : requested;
                if (!target.refresh() || !visible(target) || !targetBounds.equals(bounds(target))
                        || (backControl && (!target.isClickable() || !BACK_ID.equals(target.getViewIdResourceName())))) {
                    finish("rejected_stale_target"); return;
                }
                if (parentClick && (!target.isClickable() || !"android.widget.FrameLayout".contentEquals(target.getClassName())
                        || target.getWindowId() != cell.getWindowId())) {
                    finish("rejected_sku_parent_identity"); return;
                }
                if (sensitive(cell) || sensitive(target) || sensitive(dates.get(0)) || sensitive(prices.get(0))) {
                    finish("rejected_sensitive_target"); return;
                }
                AccessibilityWindowInfo window = target.getWindow();
                if (window == null || !window.isActive() || target.getWindowId() != root.getWindowId()) {
                    finish("rejected_window"); return;
                }
                int displayId = window.getDisplayId();
                put("window_id", target.getWindowId()); put("display_id", displayId);
                put("node_class", String.valueOf(target.getClassName()));
                put("node_id", target.getViewIdResourceName()); put("node_bounds", boundsJson(bounds(target)));
                put("target_bounds", boundsJson(bounds(target)));
                put("inner_node", nodeMetadata(cell));
                if (parentClick) put("parent_node", nodeMetadata(target));
                put("sensitive_flag_supported", Build.VERSION.SDK_INT >= 34);
                if (Build.VERSION.SDK_INT >= 34) put("accessibility_data_sensitive", target.isAccessibilityDataSensitive());
                captureEnvironment("before");
                // Recheck immediately before input, after any potentially blocking tree traversal.
                AccessibilityNodeInfo freshRoot = service.getRootInActiveWindow();
                if (done.get() || SystemClock.elapsedRealtime() - began > 2500 || !serviceReady()
                        || !damaiRoot(freshRoot) || freshRoot.getWindowId() != target.getWindowId()
                        || !cell.refresh() || !visible(cell) || !TEST_CELL.equals(bounds(cell)) || sensitive(cell)
                        || !target.refresh() || !visible(target) || !targetBounds.equals(bounds(target)) || sensitive(target)) {
                    finish("rejected_pre_dispatch_state"); return;
                }
                if (parentClick) {
                    AccessibilityNodeInfo currentParent = skuParent(cell, priceBounds);
                    if (currentParent == null || !currentParent.equals(target) || !target.isClickable()
                            || target.getWindowId() != cell.getWindowId() || done.get()
                            || SystemClock.elapsedRealtime() - began > 2500 || !serviceReady()) {
                        finish("rejected_pre_dispatch_parent"); return;
                    }
                    put("callback", "not_applicable");
                    boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    put("api_return", accepted);
                    recordAfterAndFinish(accepted ? "native_action_returned_ui_unverified" : "native_action_rejected");
                    return;
                }
                Path path = new Path(); path.moveTo(requested.centerX(), requested.centerY());
                GestureDescription gesture = new GestureDescription.Builder().setDisplayId(displayId)
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
                put("callback", "pending");
                boolean accepted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription gesture) {
                        if (done.get()) return;
                        put("callback", "completed");
                        // Completion is input-dispatch evidence, never proof of ticket selection.
                        recordAfterAndFinish("gesture_completed_ui_unverified");
                    }
                    @Override public void onCancelled(GestureDescription gesture) {
                        if (done.get()) return;
                        put("callback", "cancelled");
                        recordAfterAndFinish("gesture_cancelled");
                    }
                }, main);
                put("api_return", accepted);
                if (!accepted) { put("callback", "not_dispatched"); recordAfterAndFinish("dispatch_rejected"); }
            } catch (RuntimeException failure) { put("exception", failure.getClass().getSimpleName()); finish("exception"); }
        }

        private boolean serviceReady() {
            return !done.get() && service != null && MonitorService.instance == service && !service.isRunning()
                    && context.getSystemService(PowerManager.class).isInteractive()
                    && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
        }

        private boolean testConfig(MonitorConfig config) {
            return config.validate().isEmpty() && config.mode == MonitorConfig.Mode.PREPARE && config.quantity == 1
                    && "谢霆锋".equals(config.eventKeyword) && "杭州".equals(config.city)
                    && "2026-11-15".equals(config.date) && "19:00".equals(config.time)
                    && config.prices.size() == 1 && config.prices.get(0) == 980 && config.maxTotal == 980
                    && config.deadline.toEpochMilli() > System.currentTimeMillis();
        }

        private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> cells,
                             List<AccessibilityNodeInfo> dates, List<AccessibilityNodeInfo> prices,
                             List<AccessibilityNodeInfo> backs, int depth, int[] seen) {
            if (node == null || depth > 50 || ++seen[0] > 1000) return;
            String id = node.getViewIdResourceName();
            if ((ID + "ll_perform_item").equals(id) && TEST_CELL.equals(bounds(node))) cells.add(node);
            else if ((ID + "project_detail_perform_flowlayout").equals(id)) dates.add(node);
            else if ((ID + "project_detail_perform_price_flowlayout").equals(id)) prices.add(node);
            else if (BACK_ID.equals(id)) backs.add(node);
            for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), cells, dates, prices, backs, depth + 1, seen);
        }

        private void recordAfterAndFinish(String status) {
            main.postDelayed(() -> new Thread(() -> {
                if (done.get()) return;
                captureEnvironment("after");
                finish(status);
            }, "ticket-helper-debug-probe-result").start(), 100);
        }

        /** Window/package metadata only: no titles, text, screenshots, or account data. */
        @SuppressWarnings("deprecation")
        private void captureEnvironment(String phase) {
            JSONObject environment = new JSONObject();
            TouchDiagnostics.put(environment, "captured_at_ms", System.currentTimeMillis());
            try {
                AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
                TouchDiagnostics.put(environment, "touch_exploration", manager.isTouchExplorationEnabled());
            } catch (RuntimeException error) { TouchDiagnostics.put(environment, "touch_exploration_error", error.getClass().getSimpleName()); }
            try {
                // This public no-argument controller queries the default display only.
                TouchDiagnostics.put(environment, "magnification_display_id", android.view.Display.DEFAULT_DISPLAY);
                AccessibilityService.MagnificationController controller = service.getMagnificationController();
                if (Build.VERSION.SDK_INT >= 33) {
                    MagnificationConfig config = controller.getMagnificationConfig();
                    if (config == null) TouchDiagnostics.put(environment, "magnification_status", "unavailable");
                    else {
                        float scale = config.getScale();
                        if (Float.isFinite(scale)) TouchDiagnostics.put(environment, "magnification_scale", scale);
                        else TouchDiagnostics.put(environment, "magnification_status", "non_finite_scale");
                        TouchDiagnostics.put(environment, "magnification_mode", config.getMode());
                        if (Build.VERSION.SDK_INT >= 34) TouchDiagnostics.put(environment, "magnification_activated", config.isActivated());
                    }
                } else {
                    float scale = controller.getScale();
                    if (Float.isFinite(scale)) TouchDiagnostics.put(environment, "magnification_scale", scale);
                    else TouchDiagnostics.put(environment, "magnification_status", "non_finite_scale");
                }
            } catch (RuntimeException error) { TouchDiagnostics.put(environment, "magnification_error", error.getClass().getSimpleName()); }
            JSONArray windows = new JSONArray();
            try {
                List<AccessibilityWindowInfo> current = service.getWindows();
                TouchDiagnostics.put(environment, "window_count", current.size());
                TouchDiagnostics.put(environment, "window_list_truncated", current.size() > 16);
                for (int i = 0; i < current.size() && i < 16; i++) {
                    AccessibilityWindowInfo window = current.get(i);
                    JSONObject item = new JSONObject();
                    TouchDiagnostics.put(item, "id", window.getId()); TouchDiagnostics.put(item, "type", window.getType());
                    TouchDiagnostics.put(item, "layer", window.getLayer()); TouchDiagnostics.put(item, "display_id", window.getDisplayId());
                    TouchDiagnostics.put(item, "active", window.isActive()); TouchDiagnostics.put(item, "focused", window.isFocused());
                    Rect bounds = new Rect(); window.getBoundsInScreen(bounds); TouchDiagnostics.put(item, "bounds", boundsJson(bounds));
                    AccessibilityNodeInfo windowRoot = window.getRoot();
                    TouchDiagnostics.put(item, "package", windowRoot == null || windowRoot.getPackageName() == null
                            ? "" : windowRoot.getPackageName().toString());
                    windows.put(item);
                }
            } catch (RuntimeException error) { TouchDiagnostics.put(environment, "windows_error", error.getClass().getSimpleName()); }
            TouchDiagnostics.put(environment, "windows", windows);
            put("environment_" + phase, environment);
        }

        private void finish(String status) {
            if (!done.compareAndSet(false, true)) return;
            main.removeCallbacksAndMessages(null);
            put("status", status); put("finished_at_ms", System.currentTimeMillis());
            put("elapsed_ms", SystemClock.elapsedRealtime() - began);
            try {
                synchronized (REPORT_LOCK) {
                    AtomicFile output = new AtomicFile(new File(context.getFilesDir(), "debug-sku-probe.json"));
                    FileOutputStream stream = null;
                    try {
                        stream = output.startWrite();
                        synchronized (report) { stream.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
                        output.finishWrite(stream);
                    } catch (Exception failure) { if (stream != null) output.failWrite(stream); }
                }
            } finally { IN_FLIGHT.set(false); pending.finish(); }
        }

        private void put(String key, Object value) {
            synchronized (report) { TouchDiagnostics.put(report, key, value); }
        }
    }

    private static boolean damaiRoot(AccessibilityNodeInfo root) {
        return root != null && DAMAI.contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
    }
    private static boolean visible(AccessibilityNodeInfo node) { return node.isVisibleToUser() && node.isEnabled(); }
    private static AccessibilityNodeInfo skuParent(AccessibilityNodeInfo cell, Rect priceBounds) {
        AccessibilityNodeInfo current = cell.getParent();
        for (int level = 0; level < 3 && current != null; level++, current = current.getParent()) {
            Rect currentBounds = bounds(current);
            if (!priceBounds.contains(currentBounds) || !currentBounds.contains(TEST_CELL)) return null;
            if (visible(current) && current.isClickable()) {
                return "android.widget.FrameLayout".contentEquals(current.getClassName())
                        && TEST_PARENT.equals(currentBounds) && current.getWindowId() == cell.getWindowId() ? current : null;
            }
        }
        return null;
    }
    private static JSONObject nodeMetadata(AccessibilityNodeInfo node) {
        JSONObject result = new JSONObject();
        TouchDiagnostics.put(result, "class", String.valueOf(node.getClassName()));
        TouchDiagnostics.put(result, "id", node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName());
        TouchDiagnostics.put(result, "bounds", boundsJson(bounds(node)));
        TouchDiagnostics.put(result, "window_id", node.getWindowId());
        TouchDiagnostics.put(result, "clickable", node.isClickable());
        TouchDiagnostics.put(result, "visible", node.isVisibleToUser());
        TouchDiagnostics.put(result, "enabled", node.isEnabled());
        if (Build.VERSION.SDK_INT >= 34) TouchDiagnostics.put(result, "accessibility_data_sensitive", node.isAccessibilityDataSensitive());
        return result;
    }
    private static Rect bounds(AccessibilityNodeInfo node) { Rect result = new Rect(); node.getBoundsInScreen(result); return result; }
    private static JSONArray boundsJson(Rect bounds) { return new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom); }
    private static boolean sensitive(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < 34) return false;
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth < 50 && current != null; depth++, current = current.getParent())
            if (current.isAccessibilityDataSensitive()) return true;
        return false;
    }
}
