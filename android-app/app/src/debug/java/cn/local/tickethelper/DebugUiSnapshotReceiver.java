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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Explicit, debug-only native structure capture. The nonexported manifest receiver enforces the
 * same-UID boundary. No input, navigation, screenshot, security-setting or network operation exists.
 */
public final class DebugUiSnapshotReceiver extends BroadcastReceiver {
    public static final String ACTION = "cn.local.tickethelper.DEBUG_NATIVE_STRUCTURE";
    private static final String DAMAI = "cn.damai";
    private static final int MAX_NODES = 1600, MAX_DEPTH = 60, MAX_BYTES = 1500000;
    private static final long CAPTURE_MS = 8000, WATCHDOG_MS = 9000;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final Object REPORT_LOCK = new Object();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        DebugSnapshotPolicy.CaptureScope scope = DebugSnapshotPolicy.captureScope(intent.getStringExtra("scope"));
        if (scope == null) return;
        PendingResult pending = goAsync();
        if (!IN_FLIGHT.compareAndSet(false, true)) { pending.finish(); return; }
        new Probe(context.getApplicationContext(), pending, scope).start();
    }

    private static final class Probe {
        final Context context;
        final PendingResult pending;
        final DebugSnapshotPolicy.CaptureScope scope;
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean done = new AtomicBoolean();
        final MonitorService service = MonitorService.instance;
        final long startedAt = System.currentTimeMillis(), began = SystemClock.elapsedRealtime();
        final JSONObject report = new JSONObject();
        final Runnable watchdog = () -> finish("capture_timeout", null);

        Probe(Context context, PendingResult pending, DebugSnapshotPolicy.CaptureScope scope) {
            this.context = context; this.pending = pending; this.scope = scope;
            put(report, "schema", 1); put(report, "scope", detailEntry() ? "debug_read_only_detail_entry" : "debug_read_only_native_structure");
            put(report, "tree_scope", detailEntry() ? "detail_entry_ancestor_chains_only" : "damai_with_textless_invisible_system_leaf_placeholders");
            put(report, "capture_budget_ms", detailEntry() ? 2000 : CAPTURE_MS);
            put(report, "requested_at_ms", startedAt); put(report, "package", DAMAI);
            put(report, "status", "not_captured"); put(report, "capture_complete", false);
        }
        void start() {
            main.postDelayed(watchdog, detailEntry() ? 2000 : WATCHDOG_MS);
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
                if (detailEntry()) { runDetailEntry(root); return; }
                int windowId = root.getWindowId();
                Capture first = new Capture(windowId);
                first.collect(root, -1, 0, -1, false);
                recordCapture("first", first);
                if (!first.complete || expired()) { finish("incomplete_or_expired", null); return; }
                if (!ready() || armed()) { finish("service_or_screen_changed", null); return; }
                AccessibilityNodeInfo next = service.getRootInActiveWindow();
                if (!validRoot(next) || !root.equals(next) || next.getWindowId() != windowId) { finish("root_changed", null); return; }
                Capture second = new Capture(windowId);
                second.collect(next, -1, 0, -1, false);
                recordCapture("second", second);
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
        boolean detailEntry() { return scope == DebugSnapshotPolicy.CaptureScope.DETAIL_ENTRY; }
        boolean expired() { return done.get() || SystemClock.elapsedRealtime() - began >= (detailEntry() ? 2000 : CAPTURE_MS); }
        boolean validRoot(AccessibilityNodeInfo root) {
            if (root == null || !root.refresh() || !root.isVisibleToUser()
                    || !DAMAI.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return false;
            AccessibilityWindowInfo window = root.getWindow();
            return window != null && window.isActive() && root.getWindowId() >= 0
                    && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                    && window.getDisplayId() == Display.DEFAULT_DISPLAY;
        }
        void runDetailEntry(AccessibilityNodeInfo root) {
            int windowId = root.getWindowId();
            synchronized (REPORT_LOCK) {
                put(report, "lookup_query", DebugSnapshotPolicy.DETAIL_ENTRY_LABEL);
                put(report, "chain_direction", "candidate_to_root"); put(report, "full_tree_capture", false);
            }
            DetailCapture first = new DetailCapture(root, windowId); first.lookup(); recordDetail("first", first);
            if (!first.complete || expired()) { finish("detail_entry_not_captured", null); return; }
            if (!ready() || armed()) { finish("service_or_screen_changed", null); return; }
            AccessibilityNodeInfo next = service.getRootInActiveWindow();
            if (!validRoot(next) || !root.equals(next) || next.getWindowId() != windowId) { finish("root_changed", null); return; }
            DetailCapture second = new DetailCapture(next, windowId); second.lookup(); recordDetail("second", second);
            if (!second.complete || expired()) { finish("detail_entry_not_captured", null); return; }
            AccessibilityNodeInfo after = service.getRootInActiveWindow();
            if (!ready() || armed() || !validRoot(after) || !root.equals(after) || after.getWindowId() != windowId
                    || expired() || first.candidateCount != second.candidateCount
                    || !first.nodes.toString().equals(second.nodes.toString())) { finish("stale_structure", null); return; }
            synchronized (REPORT_LOCK) {
                put(report, "window_id", windowId); put(report, "display_id", Display.DEFAULT_DISPLAY);
                put(report, "captured_at_ms", System.currentTimeMillis()); put(report, "node_count", second.nodes.length());
                put(report, "matched_candidate_count", second.exactCount);
            }
            finish("captured", second.nodes);
        }
        final class DetailCapture {
            final AccessibilityNodeInfo root;
            final int windowId;
            final JSONArray nodes = new JSONArray();
            int candidateCount, exactCount;
            boolean complete;
            DebugSnapshotPolicy.DetailFailure failure = DebugSnapshotPolicy.DetailFailure.NONE;
            int failureAtLevel = -1, currentLevel = -1;
            JSONObject failureMetadata;
            final JSONArray failureChain = new JSONArray();
            final List<JSONObject> attemptedChain = new ArrayList<>();
            DetailBeforeRefresh currentBefore;
            JSONObject currentAttempt;
            DebugSnapshotPolicy.DetailTraceEnd failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.NONE;
            DetailCapture(AccessibilityNodeInfo root, int windowId) { this.root = root; this.windowId = windowId; }
            void lookup() {
                if (expired()) { reject(DebugSnapshotPolicy.DetailFailure.CAPTURE_EXPIRED); return; }
                final List<AccessibilityNodeInfo> candidates;
                try { candidates = root.findAccessibilityNodeInfosByText(DebugSnapshotPolicy.DETAIL_ENTRY_LABEL); }
                catch (Exception failed) { reject(DebugSnapshotPolicy.DetailFailure.LOOKUP_FAILED); return; }
                candidateCount = candidates == null ? 0 : Math.min(candidates.size(), DebugSnapshotPolicy.MAX_DETAIL_CANDIDATES + 1);
                if (candidateCount > DebugSnapshotPolicy.MAX_DETAIL_CANDIDATES) { reject(DebugSnapshotPolicy.DetailFailure.CANDIDATE_LIMIT); return; }
                if (candidateCount == 0) { reject(DebugSnapshotPolicy.DetailFailure.ENTRY_NOT_FOUND); return; }
                for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                    if (expired()) { reject(DebugSnapshotPolicy.DetailFailure.CAPTURE_EXPIRED); return; }
                    AccessibilityNodeInfo candidate = candidates.get(candidateIndex), node = candidate;
                    List<JSONObject> chain = new ArrayList<>();
                    attemptedChain.clear(); currentBefore = null; currentAttempt = null;
                    boolean reachedRoot = false;
                    try {
                        for (int level = 0; level < DebugSnapshotPolicy.MAX_DETAIL_CHAIN; level++) {
                            currentLevel = level;
                            currentBefore = null; currentAttempt = null;
                            if (expired()) { reject(DebugSnapshotPolicy.DetailFailure.CAPTURE_EXPIRED); return; }
                            if (node == null) { rejectWithChain(DebugSnapshotPolicy.DetailFailure.CHAIN_NULL, candidate, null, level, DebugSnapshotPolicy.DetailRefresh.NOT_ATTEMPTED); return; }
                            // A provider may clear the original object on refresh(false). Preserve
                            // the copy, metadata and parent before invoking that destructive operation.
                            currentBefore = new DetailBeforeRefresh(node, level);
                            final boolean refreshed;
                            try { refreshed = node.refresh(); }
                            catch (Exception failed) { rejectWithChain(DebugSnapshotPolicy.DetailFailure.NODE_ACCESS_FAILED, candidate, node, level, DebugSnapshotPolicy.DetailRefresh.THREW); return; }
                            currentAttempt = attemptedMetadata(currentBefore, node,
                                    refreshed ? DebugSnapshotPolicy.DetailRefresh.SUCCEEDED : DebugSnapshotPolicy.DetailRefresh.RETURNED_FALSE);
                            attemptedChain.add(currentAttempt);
                            if (!refreshed) { rejectWithChain(DebugSnapshotPolicy.DetailFailure.REFRESH_FAILED, candidate, node, level, DebugSnapshotPolicy.DetailRefresh.RETURNED_FALSE); return; }
                            if (node.getWindowId() != windowId) { rejectWithChain(DebugSnapshotPolicy.DetailFailure.WINDOW_MISMATCH, candidate, node, level, DebugSnapshotPolicy.DetailRefresh.SUCCEEDED); return; }
                            if (DebugSnapshotPolicy.packageKind(node.getPackageName()) != DebugSnapshotPolicy.PackageKind.DAMAI) {
                                rejectWithChain(DebugSnapshotPolicy.DetailFailure.PACKAGE_MISMATCH, candidate, node, level, DebugSnapshotPolicy.DetailRefresh.SUCCEEDED); return;
                            }
                            if (node.isPassword() || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) {
                                rejectWithChain(DebugSnapshotPolicy.DetailFailure.PROTECTED_CHAIN, candidate, node, level, DebugSnapshotPolicy.DetailRefresh.SUCCEEDED); return;
                            }
                            reachedRoot = root.equals(node);
                            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                            JSONObject item = new JSONObject();
                            put(item, "candidate_index", candidateIndex); put(item, "chain_index", level);
                            put(item, "parent_chain_index", reachedRoot ? -1 : level + 1);
                            put(item, "id", DebugSnapshotPolicy.resourceId(node.getViewIdResourceName()));
                            put(item, "class", DebugSnapshotPolicy.className(node.getClassName()));
                            put(item, "bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom));
                            put(item, "visible", node.isVisibleToUser()); put(item, "enabled", node.isEnabled());
                            put(item, "clickable", node.isClickable()); put(item, "window_id", windowId);
                            // No ancestor text or description is accessed, even for a public-looking ID.
                            put(item, "label", level == 0 ? DebugSnapshotPolicy.DETAIL_ENTRY_LABEL : ""); chain.add(item);
                            if (reachedRoot) break;
                            node = node.getParent();
                        }
                        if (!reachedRoot) { reject(DebugSnapshotPolicy.DetailFailure.CHAIN_LIMIT); return; }
                        if (!DebugSnapshotPolicy.mayReadDetailLabel(true, true, true, false, reachedRoot, chain.size(), expired())) {
                            reject(DebugSnapshotPolicy.DetailFailure.CAPTURE_EXPIRED); return;
                        }
                        // Only now, after checking every ancestor, inspect these two candidate values.
                        DebugSnapshotPolicy.DetailLabelSource source = DebugSnapshotPolicy.detailLabelSource(candidate.getText(), candidate.getContentDescription());
                        if (source == DebugSnapshotPolicy.DetailLabelSource.NONE) continue;
                        put(chain.get(0), "label_source", source.name());
                        for (JSONObject item : chain) nodes.put(item);
                        exactCount++;
                    } catch (Exception failed) { rejectWithChain(DebugSnapshotPolicy.DetailFailure.NODE_ACCESS_FAILED, candidate, node, currentLevel, DebugSnapshotPolicy.DetailRefresh.NOT_ATTEMPTED); return; }
                }
                if (expired()) { reject(DebugSnapshotPolicy.DetailFailure.CAPTURE_EXPIRED); return; }
                if (exactCount == 0) { reject(DebugSnapshotPolicy.DetailFailure.ENTRY_NOT_FOUND); return; }
                complete = true;
            }
            void reject(DebugSnapshotPolicy.DetailFailure reason) { complete = false; failure = reason; }
            void rejectWithChain(DebugSnapshotPolicy.DetailFailure reason, AccessibilityNodeInfo candidate,
                    AccessibilityNodeInfo failedNode, int level, DebugSnapshotPolicy.DetailRefresh refresh) {
                reject(reason); failureAtLevel = DebugSnapshotPolicy.detailChainLevel(level);
                if (expired()) return;
                if (currentBefore == null) currentBefore = new DetailBeforeRefresh(failedNode, level);
                if (currentAttempt == null) {
                    currentAttempt = attemptedMetadata(currentBefore, failedNode, refresh);
                    attemptedChain.add(currentAttempt);
                }
                failureMetadata = currentAttempt;
                for (JSONObject attempted : attemptedChain) failureChain.put(attempted);
                if (currentBefore.matchesRoot) { failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.ROOT_REACHED; return; }
                if (currentBefore.parent == null) {
                    failureChainEnd = currentBefore.parentAccessFailed ? DebugSnapshotPolicy.DetailTraceEnd.PARENT_ACCESS_FAILED : DebugSnapshotPolicy.DetailTraceEnd.PARENT_NULL;
                    return;
                }
                traceFailureParents(currentBefore.parent, level + 1);
            }
            /** Failed refresh is never an input permit. This trace does not read ANY text/description. */
            void traceFailureParents(AccessibilityNodeInfo parent, int firstLevel) {
                AccessibilityNodeInfo node = parent;
                for (int level = firstLevel; level < DebugSnapshotPolicy.MAX_DETAIL_CHAIN; level++) {
                    if (expired()) { failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.CAPTURE_EXPIRED; return; }
                    if (!ready() || armed()) { failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.SERVICE_CHANGED; return; }
                    if (node == null) { failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.PARENT_NULL; return; }
                    DetailBeforeRefresh before = new DetailBeforeRefresh(node, level);
                    if (expired()) { failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.CAPTURE_EXPIRED; return; }
                    DebugSnapshotPolicy.DetailRefresh refresh;
                    try { refresh = node.refresh() ? DebugSnapshotPolicy.DetailRefresh.SUCCEEDED : DebugSnapshotPolicy.DetailRefresh.RETURNED_FALSE; }
                    catch (Exception failed) { refresh = DebugSnapshotPolicy.DetailRefresh.THREW; }
                    if (expired()) { failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.CAPTURE_EXPIRED; return; }
                    failureChain.put(attemptedMetadata(before, node, refresh));
                    if (before.matchesRoot) { failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.ROOT_REACHED; return; }
                    if (before.parent == null) {
                        failureChainEnd = before.parentAccessFailed ? DebugSnapshotPolicy.DetailTraceEnd.PARENT_ACCESS_FAILED : DebugSnapshotPolicy.DetailTraceEnd.PARENT_NULL;
                        return;
                    }
                    node = before.parent;
                }
                failureChainEnd = DebugSnapshotPolicy.DetailTraceEnd.CHAIN_LIMIT;
            }
            final class DetailBeforeRefresh {
                final AccessibilityNodeInfo copy, parent;
                final JSONObject metadata;
                final boolean parentAccessFailed, matchesRoot;
                final int level;
                @SuppressWarnings("deprecation")
                DetailBeforeRefresh(AccessibilityNodeInfo node, int level) {
                    this.level = level;
                    AccessibilityNodeInfo copied = null, savedParent = null;
                    boolean parentFailed = false, rootMatch = false;
                    if (node != null) {
                        try { copied = AccessibilityNodeInfo.obtain(node); } catch (Exception failed) { }
                        AccessibilityNodeInfo source = copied == null ? node : copied;
                        metadata = unverifiedMetadata(source, level, DebugSnapshotPolicy.DetailRefresh.NOT_ATTEMPTED);
                        try { rootMatch = root.equals(source); } catch (Exception ignored) { }
                        try { savedParent = source.getParent(); } catch (Exception failed) { parentFailed = true; }
                    } else metadata = unverifiedMetadata(null, level, DebugSnapshotPolicy.DetailRefresh.NOT_ATTEMPTED);
                    copy = copied; parent = savedParent; parentAccessFailed = parentFailed; matchesRoot = rootMatch;
                }
            }
            JSONObject attemptedMetadata(DetailBeforeRefresh before, AccessibilityNodeInfo after, DebugSnapshotPolicy.DetailRefresh refresh) {
                JSONObject result;
                try { result = new JSONObject(before.metadata.toString()); }
                catch (Exception ignored) { result = new JSONObject(); }
                put(result, "metadata_unverified", true); put(result, "authorizes_action", false);
                put(result, "metadata_source", before.copy == null ? "BEFORE_REFRESH_NODE" : "BEFORE_REFRESH_COPY");
                put(result, "refresh_state", refresh.name());
                put(result, "raw_refresh_result", refresh.rawResult() == null ? JSONObject.NULL : refresh.rawResult());
                put(result, "before_refresh_metadata", before.metadata);
                put(result, "after_refresh_metadata", unverifiedMetadata(after, before.level, refresh));
                put(result, "before_copy_available", before.copy != null); put(result, "parent_before_available", before.parent != null);
                put(result, "parent_before_access_failed", before.parentAccessFailed);
                return result;
            }
            JSONObject unverifiedMetadata(AccessibilityNodeInfo node, int level, DebugSnapshotPolicy.DetailRefresh refresh) {
                JSONObject item = new JSONObject();
                put(item, "chain_index", DebugSnapshotPolicy.detailChainLevel(level));
                put(item, "metadata_unverified", true); put(item, "authorizes_action", false);
                put(item, "refresh_state", refresh.name());
                put(item, "raw_refresh_result", refresh.rawResult() == null ? JSONObject.NULL : refresh.rawResult());
                put(item, "id", ""); put(item, "class", ""); put(item, "bounds", JSONObject.NULL);
                put(item, "visible", JSONObject.NULL); put(item, "enabled", JSONObject.NULL); put(item, "clickable", JSONObject.NULL);
                put(item, "password", JSONObject.NULL); put(item, "nodeSensitive", JSONObject.NULL);
                put(item, "window_id", JSONObject.NULL); put(item, "package_category", DebugSnapshotPolicy.PackageKind.EMPTY.name());
                if (node == null) return item;
                try { put(item, "id", DebugSnapshotPolicy.resourceId(node.getViewIdResourceName())); } catch (Exception ignored) { }
                try { put(item, "class", DebugSnapshotPolicy.className(node.getClassName())); } catch (Exception ignored) { }
                try {
                    Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
                    put(item, "bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom));
                } catch (Exception ignored) { }
                try { put(item, "visible", node.isVisibleToUser()); } catch (Exception ignored) { }
                try { put(item, "enabled", node.isEnabled()); } catch (Exception ignored) { }
                try { put(item, "clickable", node.isClickable()); } catch (Exception ignored) { }
                try { put(item, "password", node.isPassword()); } catch (Exception ignored) { }
                try { put(item, "nodeSensitive", Build.VERSION.SDK_INT >= 34 ? node.isAccessibilityDataSensitive() : JSONObject.NULL); } catch (Exception ignored) { }
                try { put(item, "window_id", node.getWindowId()); } catch (Exception ignored) { }
                try { put(item, "package_category", DebugSnapshotPolicy.packageKind(node.getPackageName()).name()); } catch (Exception ignored) { }
                put(item, "matches_expected_root", root.equals(node));
                return item;
            }
        }
        void recordDetail(String pass, DetailCapture capture) {
            synchronized (REPORT_LOCK) {
                if (done.get()) return;
                put(report, pass + "_candidate_count", capture.candidateCount);
                put(report, pass + "_matched_count", capture.exactCount); put(report, pass + "_node_count", capture.nodes.length());
                put(report, pass + "_complete", capture.complete); put(report, pass + "_failure", capture.failure.name());
                if (capture.failureMetadata != null) {
                    put(report, pass + "_failure_at_level", capture.failureAtLevel);
                    put(report, pass + "_failure_metadata", capture.failureMetadata);
                    put(report, "failure_chain", capture.failureChain); put(report, "failure_chain_end", capture.failureChainEnd.name());
                    put(report, "failure_chain_scope", "unverified_read_only_metadata"); put(report, "failure_chain_authorizes_action", false);
                }
            }
        }
        final class Capture {
            final JSONArray nodes = new JSONArray();
            final int windowId;
            boolean complete = true;
            int excludedSystemLeaves, maximumDepth;
            DebugSnapshotPolicy.FailureMetadata failure;
            Capture(int windowId) { this.windowId = windowId; }
            void collect(AccessibilityNodeInfo node, int parent, int depth, int childIndex, boolean protectedParent) {
                if (!complete) return;
                DebugSnapshotPolicy.PackageKind kind = DebugSnapshotPolicy.PackageKind.EMPTY;
                Boolean visible = null;
                maximumDepth = Math.max(maximumDepth, depth);
                if (node == null) { fail(DebugSnapshotPolicy.FailureReason.NULL_NODE, parent, depth, childIndex, kind, visible); return; }
                if (depth > MAX_DEPTH) { fail(DebugSnapshotPolicy.FailureReason.DEPTH_LIMIT, parent, depth, childIndex, kind, visible); return; }
                if (nodes.length() >= MAX_NODES) { fail(DebugSnapshotPolicy.FailureReason.NODE_LIMIT, parent, depth, childIndex, kind, visible); return; }
                if (expired()) { fail(DebugSnapshotPolicy.FailureReason.CAPTURE_EXPIRED, parent, depth, childIndex, kind, visible); return; }
                try {
                if (!node.refresh()) { fail(DebugSnapshotPolicy.FailureReason.REFRESH_FAILED, parent, depth, childIndex, kind, visible); return; }
                kind = DebugSnapshotPolicy.packageKind(node.getPackageName()); visible = node.isVisibleToUser();
                if (node.getWindowId() != windowId) { fail(DebugSnapshotPolicy.FailureReason.WINDOW_MISMATCH, parent, depth, childIndex, kind, visible); return; }
                int count = node.getChildCount();
                if (count < 0) { fail(DebugSnapshotPolicy.FailureReason.CHILD_COUNT_INVALID, parent, depth, childIndex, kind, visible); return; }
                boolean excluded = DebugSnapshotPolicy.mayExcludeSystemLeaf(kind, true, true, visible, count);
                if (kind != DebugSnapshotPolicy.PackageKind.DAMAI && !excluded) {
                    fail(DebugSnapshotPolicy.FailureReason.PACKAGE_MISMATCH, parent, depth, childIndex, kind, visible); return;
                }
                if (excluded) excludedSystemLeaves++;
                boolean password = node.isPassword();
                boolean nodeSensitive = password || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive());
                boolean sensitive = protectedParent || nodeSensitive || excluded;
                String rawId = excluded ? null : node.getViewIdResourceName();
                String id = DebugSnapshotPolicy.resourceId(rawId);
                Rect rect = new Rect(); node.getBoundsInScreen(rect);
                JSONObject item = new JSONObject(); int index = nodes.length();
                put(item, "index", index); put(item, "parent", parent); put(item, "depth", depth);
                put(item, "child_index", childIndex); put(item, "child_count", count);
                put(item, "package_category", kind.name()); put(item, "scope_excluded", excluded);
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
                if (count > MAX_NODES - nodes.length()) {
                    fail(DebugSnapshotPolicy.FailureReason.CHILD_COUNT_LIMIT, index, depth + 1, -1, kind, visible); return;
                }
                for (int i = 0; complete && i < count; i++) {
                    if (expired()) { fail(DebugSnapshotPolicy.FailureReason.CAPTURE_EXPIRED, index, depth + 1, i, kind, visible); return; }
                    AccessibilityNodeInfo child;
                    try { child = node.getChild(i); }
                    catch (Exception inaccessible) {
                        fail(DebugSnapshotPolicy.FailureReason.CHILD_ACCESS_FAILED, index, depth + 1, i,
                                DebugSnapshotPolicy.PackageKind.EMPTY, null); return;
                    }
                    collect(child, index, depth + 1, i, sensitive);
                }
                } catch (Exception inaccessible) {
                    fail(DebugSnapshotPolicy.FailureReason.NODE_ACCESS_FAILED, parent, depth, childIndex, kind, visible);
                }
            }
            void fail(DebugSnapshotPolicy.FailureReason reason, int parent, int depth, int childIndex,
                    DebugSnapshotPolicy.PackageKind kind, Boolean visible) {
                complete = false;
                if (failure != null) return;
                String parentId = "";
                if (parent >= 0 && parent < nodes.length()) {
                    JSONObject value = nodes.optJSONObject(parent); if (value != null) parentId = value.optString("id", "");
                }
                failure = new DebugSnapshotPolicy.FailureMetadata(reason, parent, depth, childIndex, parentId, kind, visible);
            }
        }
        void recordCapture(String pass, Capture capture) {
            synchronized (REPORT_LOCK) {
                if (done.get()) return;
                put(report, pass + "_node_count", capture.nodes.length());
                put(report, pass + "_elapsed_ms", SystemClock.elapsedRealtime() - began);
                put(report, pass + "_complete", capture.complete);
                put(report, pass + "_maximum_depth", capture.maximumDepth);
                put(report, pass + "_excluded_system_leaves", capture.excludedSystemLeaves);
                DebugSnapshotPolicy.FailureMetadata failed = capture.failure;
                if (failed == null) return;
                JSONObject metadata = new JSONObject();
                put(metadata, "reason", failed.reason.name()); put(metadata, "parent_index", failed.parentIndex);
                put(metadata, "depth", failed.depth); put(metadata, "child_index", failed.childIndex);
                put(metadata, "parent_id", failed.parentId); put(metadata, "package_category", failed.packageKind.name());
                put(metadata, "visible", failed.visible == null ? JSONObject.NULL : failed.visible);
                put(report, pass + "_failure", metadata);
            }
        }
        void finish(String status, JSONArray nodes) {
            if (!done.compareAndSet(false, true)) return;
            main.removeCallbacks(watchdog);
            try {
                synchronized (REPORT_LOCK) {
                    put(report, "status", status); put(report, "finished_at_ms", System.currentTimeMillis());
                    put(report, "elapsed_ms", SystemClock.elapsedRealtime() - began);
                    put(report, "capture_complete", nodes != null && "captured".equals(status));
                    if (nodes != null && "captured".equals(status)) put(report, "nodes", nodes);
                    byte[] data = report.toString(2).getBytes(StandardCharsets.UTF_8);
                    if (data.length > MAX_BYTES) {
                        report.remove("nodes"); put(report, "status", "report_size_limit"); put(report, "capture_complete", false);
                        data = report.toString(2).getBytes(StandardCharsets.UTF_8);
                    }
                    AtomicFile output = new AtomicFile(new File(context.getFilesDir(), detailEntry() ? "debug-detail-entry.json" : "debug-native-structure.json"));
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
