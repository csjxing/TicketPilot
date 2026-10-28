package cn.local.tickethelper;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.MonitorStopState;
import cn.local.tickethelper.sync.DiagnosticCollector;
import cn.local.tickethelper.sync.DiagnosticQueue;
import cn.local.tickethelper.sync.DiagnosticReport;
import cn.local.tickethelper.sync.DiagnosticHistory;
import cn.local.tickethelper.sync.DiagnosticSyncClient;
import cn.local.tickethelper.sync.SyncEndpoint;
import cn.local.tickethelper.sync.SyncSecretsStore;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Optional diagnostics coordinator. Receives a fixed state name, never an audit detail, UI tree,
 * screenshot, account, order ID or credential. Uploads have no route to a phone command.
 */
public final class CloudDiagnostics {
    // Singleton receives only getApplicationContext(), never an Activity or Service context.
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static CloudDiagnostics singleton;
    public static synchronized CloudDiagnostics get(Context context) {
        if (singleton == null) singleton = new CloudDiagnostics(context.getApplicationContext());
        return singleton;
    }
    private static final long COALESCE_MS = 15000, MAX_BACKOFF_MS = 15 * 60 * 1000;
    private final Context context;
    private final DiagnosticQueue queue;
    private final DiagnosticHistory history;
    private final String sharingInstallId;
    private final boolean sharingIdentityReady;
    private long sharingSequence;
    private boolean historyWriteScheduled;
    private final SyncSecretsStore secrets;
    private final SharedPreferences metadata;
    private final Object stateLock = new Object(), controls = new Object(), network = new Object();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ticketpilot-cloud-sync"); thread.setDaemon(true); return thread;
    });
    private final Set<DiagnosticSyncClient.Cancellation> active = new HashSet<>();
    private final List<DiagnosticReport.Event> pendingEvents = new ArrayList<>();
    private volatile boolean autoEnabled;
    private volatile String currentStatus = "云端连接尚未读取";
    private volatile long lastSuccessAtMs, lastCursor;
    private volatile String lastReportId;
    private volatile DiagnosticReport.State observedState = DiagnosticReport.State.IDLE;
    private long generation, lastAttemptElapsed = -1, retryAfterElapsed;
    private int failures;
    private String lastIncoming = "";
    private boolean dirty, autoRunning;
    private ScheduledFuture<?> scheduled;

    private CloudDiagnostics(Context context) {
        this.context = context;
        queue = new DiagnosticQueue(new File(context.getNoBackupFilesDir(), "cloud-diagnostics"));
        secrets = new SyncSecretsStore(context);
        metadata = context.getSharedPreferences("cloud_diagnostic_status", Context.MODE_PRIVATE);
        history = new DiagnosticHistory(new File(context.getNoBackupFilesDir(), "local-diagnostics"));
        String storedSharingId = ""; boolean identityReady = false;
        try {
            storedSharingId = metadata.getString("sharing_install_id", "");
            sharingSequence = metadata.getLong("sharing_sequence", 0);
            if (storedSharingId.isEmpty() && sharingSequence == 0) {
                storedSharingId = java.util.UUID.randomUUID().toString();
                identityReady = metadata.edit().putString("sharing_install_id",storedSharingId).commit();
            } else {
                java.util.UUID identity = java.util.UUID.fromString(storedSharingId);
                identityReady = identity.version()==4 && identity.variant()==2 && identity.toString().equals(storedSharingId)
                        && sharingSequence>=0 && sharingSequence<=DiagnosticReport.MAX_SEQUENCE;
            }
        } catch (RuntimeException unreadableIdentity) { identityReady = false; }
        sharingInstallId = storedSharingId;
        sharingIdentityReady = identityReady;
        lastSuccessAtMs = metadata.getLong("last_success_at_ms", 0);
        lastCursor = metadata.getLong("last_cursor", 0);
        lastReportId = metadata.getString("last_report_id", "");
        // Read the opt-in once after process startup. An opted-out polling hook never reads disk.
        worker.execute(this::restoreConnection);
    }

    /** Local reads/blocking manual actions belong on the caller's background executor. */
    public SyncSecretsStore.Connection connection() throws IOException { return secrets.load(); }
    public int queueCount() throws IOException { return queue.size(); }
    public String status() { return currentStatus; }
    public boolean autoUploadEnabled() { return autoEnabled; }
    public long lastSuccessAtMs() { return lastSuccessAtMs; }
    public long lastCursor() { return lastCursor; }
    public String lastReportId() { return lastReportId; }

    public DiagnosticReport captureManual() throws IOException {
        long now = System.currentTimeMillis();
        List<DiagnosticReport.Event> recent = history.snapshot();
        recent.add(new DiagnosticReport.Event(now, DiagnosticReport.Tag.USER, DiagnosticReport.Code.MANUAL_REPORT));
        return queue.enqueue(snapshot(observedState), recent, now);
    }

    /** Relay/export capture does not enqueue records for an unrelated private diagnostics server. */
    public DiagnosticReport captureForSharing() throws IOException {
        synchronized (stateLock) {
            if (!sharingIdentityReady) throw new IOException("本机诊断身份未能保存或读取，请重启应用后再试；购票资料未改变。");
            if (sharingSequence >= DiagnosticReport.MAX_SEQUENCE) throw new IOException("诊断序号已满。");
            long sequence = sharingSequence + 1;
            if (!metadata.edit().putLong("sharing_sequence",sequence).commit()) throw new IOException("诊断序号未保存。");
            sharingSequence = sequence;
            long now = System.currentTimeMillis();
            List<DiagnosticReport.Event> recent = history.snapshot();
            try {
                recent.add(new DiagnosticReport.Event(now,DiagnosticReport.Tag.USER,DiagnosticReport.Code.MANUAL_REPORT));
                return new DiagnosticReport(java.util.UUID.randomUUID().toString(),sharingInstallId,sequence,now,snapshot(observedState),recent);
            }
            catch (RuntimeException invalid) { throw new IOException("无法生成诊断，请检查手机日期和版本。"); }
        }
    }

    public SyncSecretsStore.Connection pair(String url, String code) throws IOException {
        SyncEndpoint endpoint = new SyncEndpoint(url);
        final long expected;
        synchronized (controls) {
            synchronized (stateLock) { invalidateLocked(); expected = generation; currentStatus = "正在连接云端"; }
            // Keep an earlier receiver only as a disabled local record until new pairing succeeds.
            if (secrets.load() != null) secrets.setAutoUpload(false);
        }
        synchronized (network) {
            DiagnosticSyncClient.Cancellation token = register(expected, false);
            try {
                SyncSecretsStore.Connection result = DiagnosticSyncClient.pair(endpoint,
                        code == null ? "" : code.trim(), queue, secrets, token);
                synchronized (stateLock) {
                    if (expected != generation || token.isCancelled()) throw cancelled();
                    currentStatus = "已连接云端；自动上传未开启";
                }
                return result;
            } catch (IOException error) { failureStatus(expected, "云端连接未完成，请核对地址和配对码"); throw error; }
            finally { unregister(token); }
        }
    }

    /** Captures current facts, then drains at most four reports; a failed request keeps its ID. */
    public int uploadManual() throws IOException {
        final long expected;
        synchronized (stateLock) { expected = generation; currentStatus = "正在上传诊断"; }
        try {
            captureManual();
            int count = drain(expected, false);
            synchronized (stateLock) {
                if (expected == generation) {
                    failures = 0; retryAfterElapsed = 0;
                    currentStatus = count == 0 ? "没有待上传诊断" : "云端已接收 " + count + " 份诊断";
                }
            }
            return count;
        } catch (IOException error) {
            if (nonRetryable(error)) pauseForAction(expected, (DiagnosticSyncClient.Failure) error);
            else failureStatus(expected, "诊断上传未完成，未确认的记录保留在手机");
            throw error;
        }
    }

    public void setAutoUpload(boolean enabled) throws IOException {
        synchronized (controls) {
            final long expected;
            synchronized (stateLock) { invalidateLocked(); expected = generation; }
            SyncSecretsStore.Connection connection = secrets.load();
            if (connection == null || (enabled && connection.expired(System.currentTimeMillis()))) {
                currentStatus = "请先重新连接云端";
                if (enabled) throw new IOException("请先连接有效云端，再开启自动上传。");
                return;
            }
            secrets.setAutoUpload(enabled);
            synchronized (stateLock) {
                if (generation != expected) return;
                autoEnabled = enabled;
                currentStatus = enabled ? "自动上传已开启；仅同步安全诊断" : "自动上传已关闭";
                if (enabled) {
                    dirty = true;
                    addEventLocked(DiagnosticReport.Tag.SYNC, DiagnosticReport.Code.CONNECTED);
                    scheduleLocked(0, expected);
                }
            }
        }
    }

    /** Cancels requests before erasing the local credential; never deletes the order journal. */
    public void disconnect() throws IOException {
        synchronized (controls) {
            synchronized (stateLock) { invalidateLocked(); currentStatus = "正在断开云端"; }
            secrets.clear(); currentStatus = "已断开云端；本机诊断保留";
        }
    }

    /** Explicit user recovery only: no cloud deletion, identity/sequence reset or order changes. */
    public void clearPendingReports() throws IOException {
        synchronized (controls) {
            synchronized (stateLock) { invalidateLocked(); currentStatus = "正在清理待上传诊断"; }
            if (secrets.load() != null) secrets.setAutoUpload(false);
            queue.clearPending();
            currentStatus = "待上传诊断已清理；连接和序号保留，自动上传已关闭";
        }
    }

    /** May run after each audit write. Opt-out does no disk/network IO or executor submission. */
    public void onState(String name) { onState(name, ""); }

    /** The detail is reduced immediately to an enum; its original text is never retained. */
    public void onState(String name, String detail) {
        Mapped mapped = mapWithReason(name, detail);
        if (mapped == null) return;
        if (mapped.state != null) observedState = mapped.state;
        if (history.record(System.currentTimeMillis(), mapped.tag, mapped.code)) {
            synchronized (stateLock) {
                if (!historyWriteScheduled) {
                    historyWriteScheduled = true;
                    worker.schedule(() -> {
                        synchronized (stateLock) { historyWriteScheduled = false; }
                        try { history.persist(); } catch (IOException ignored) { /* No network or monitor side effect. */ }
                    }, 1000, TimeUnit.MILLISECONDS);
                }
            }
        }
        if (!autoEnabled) return;
        synchronized (stateLock) {
            if (!autoEnabled || mapped.key.equals(lastIncoming)) return;
            lastIncoming = mapped.key; dirty = true;
            addEventLocked(mapped.tag, mapped.code);
            if (!autoRunning) scheduleLocked(nextDelay(SystemClock.elapsedRealtime()), generation);
        }
    }

    private void restoreConnection() {
        try {
            SyncSecretsStore.Connection saved = secrets.load();
            synchronized (stateLock) {
                if (generation != 0) return;
                if (saved == null) { currentStatus = "尚未连接云端"; return; }
                if (saved.expired(System.currentTimeMillis())) { currentStatus = "云端授权已过期，请重新连接"; return; }
                autoEnabled = saved.autoUpload;
                currentStatus = autoEnabled ? "自动上传已开启" : "已连接云端；自动上传未开启";
                if (autoEnabled) { dirty = true; scheduleLocked(0, generation); }
            }
        } catch (IOException error) { failureStatus(0, "云端连接无法读取，请重新连接"); }
    }

    private void scheduleLocked(long delayMs, long expected) {
        if (!autoEnabled || autoRunning || scheduled != null || expected != generation) return;
        scheduled = worker.schedule(() -> automatic(expected), Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }
    private void automatic(long expected) {
        List<DiagnosticReport.Event> events;
        DiagnosticReport.State state;
        boolean capture;
        synchronized (stateLock) {
            // A cancelled old task must not erase the handle of a newer generation's job.
            if (expected != generation) return;
            scheduled = null;
            if (!autoEnabled) return;
            autoRunning = true; lastAttemptElapsed = SystemClock.elapsedRealtime();
            capture = dirty; dirty = false; events = new ArrayList<>(pendingEvents); pendingEvents.clear(); state = observedState;
        }
        boolean retry = false, more = false, captured = !capture;
        try {
            if (capture) {
                synchronized (stateLock) { if (expected != generation || !autoEnabled) return; }
                queue.enqueue(snapshot(state), events, System.currentTimeMillis()); captured = true;
            }
            int uploaded = drain(expected, true);
            more = queue.size() > 0;
            synchronized (stateLock) {
                if (expected == generation) {
                    failures = 0; retryAfterElapsed = 0;
                    currentStatus = uploaded == 0 ? "自动上传已开启；等待状态变化" : "云端已接收最新诊断";
                }
            }
        } catch (IOException | RuntimeException error) {
            if (nonRetryable(error)) {
                pauseForAction(expected, (DiagnosticSyncClient.Failure) error);
                return;
            }
            synchronized (stateLock) {
                if (expected == generation && autoEnabled) {
                    failures = Math.min(16, failures + 1); retryAfterElapsed = SystemClock.elapsedRealtime() + backoffMillis(failures);
                    currentStatus = "同步暂未完成，诊断保留，稍后自动重试"; retry = true;
                    if (!captured) {
                        dirty = true;
                        for (DiagnosticReport.Event event : events) {
                            if (pendingEvents.size() >= DiagnosticReport.MAX_EVENTS) break;
                            pendingEvents.add(event);
                        }
                    }
                }
            }
        } finally {
            synchronized (stateLock) {
                autoRunning = false;
                if (expected == generation && autoEnabled && (dirty || retry || more))
                    scheduleLocked(nextDelay(SystemClock.elapsedRealtime()), expected);
                // A control action may have enabled a new generation while this one was cancelling.
                else if (expected != generation && autoEnabled && dirty)
                    scheduleLocked(nextDelay(SystemClock.elapsedRealtime()), generation);
            }
        }
    }

    private int drain(long expected, boolean automatic) throws IOException {
        synchronized (network) {
            int count = 0;
            for (int i = 0; i < 4; i++) {
                DiagnosticSyncClient.Cancellation token = register(expected, automatic);
                try {
                    DiagnosticSyncClient.UploadResult result = DiagnosticSyncClient.uploadFirst(queue, secrets, token);
                    if (!result.uploaded) break;
                    synchronized (stateLock) { if (expected != generation || token.isCancelled()) throw cancelled(); }
                    success(result); count++;
                } finally { unregister(token); }
            }
            return count;
        }
    }
    private DiagnosticSyncClient.Cancellation register(long expected, boolean automatic) throws IOException {
        synchronized (stateLock) {
            if (expected != generation || (automatic && !autoEnabled)) throw cancelled();
            DiagnosticSyncClient.Cancellation token = new DiagnosticSyncClient.Cancellation(); active.add(token); return token;
        }
    }
    private void unregister(DiagnosticSyncClient.Cancellation token) { synchronized (stateLock) { active.remove(token); } }
    private void invalidateLocked() {
        generation++; autoEnabled = false; dirty = false; lastIncoming = ""; pendingEvents.clear(); failures = 0; retryAfterElapsed = 0;
        if (scheduled != null) { scheduled.cancel(false); scheduled = null; }
        for (DiagnosticSyncClient.Cancellation token : active) token.cancel();
    }
    private void addEventLocked(DiagnosticReport.Tag tag, DiagnosticReport.Code code) {
        try {
            DiagnosticReport.Event event = new DiagnosticReport.Event(System.currentTimeMillis(), tag, code);
            if (pendingEvents.size() >= DiagnosticReport.MAX_EVENTS) pendingEvents.remove(0);
            pendingEvents.add(event);
        } catch (IllegalArgumentException badClock) {
            // Capture will report a clock failure; do not invent an event time or disrupt polling.
            currentStatus = "手机日期异常，请先校正时间再同步";
        }
    }
    private long nextDelay(long now) {
        return Math.max(delayUntil(lastAttemptElapsed < 0 ? now : lastAttemptElapsed + COALESCE_MS, now), delayUntil(retryAfterElapsed, now));
    }
    private void success(DiagnosticSyncClient.UploadResult result) {
        lastSuccessAtMs = System.currentTimeMillis(); lastCursor = result.cursor; lastReportId = result.reportId;
        metadata.edit().putLong("last_success_at_ms", lastSuccessAtMs).putLong("last_cursor", lastCursor)
                .putString("last_report_id", lastReportId).apply();
    }
    private void failureStatus(long expected, String safeMessage) {
        synchronized (stateLock) { if (expected == generation) currentStatus = safeMessage; }
    }
    private static boolean nonRetryable(Exception error) {
        return error instanceof DiagnosticSyncClient.Failure && !((DiagnosticSyncClient.Failure) error).retryable;
    }
    private void pauseForAction(long expected, DiagnosticSyncClient.Failure error) {
        synchronized (controls) {
            synchronized (stateLock) {
                if (expected != generation) return;
                invalidateLocked(); currentStatus = "自动上传已暂停：" + error.getMessage();
            }
            try { if (secrets.load() != null) secrets.setAutoUpload(false); }
            catch (IOException saveFailed) { currentStatus = "本次自动上传已停止，但设置未保存；请重新连接云端。"; }
        }
    }
    private DiagnosticReport.Snapshot snapshot(DiagnosticReport.State requestedState) {
        AppStore store = new AppStore(context);
        MonitorConfig config = store.loadConfig();
        DiagnosticReport.Mode mode;
        try { mode = DiagnosticReport.Mode.valueOf(config.mode.name()); }
        catch (RuntimeException error) { mode = DiagnosticReport.Mode.OFF; }
        DiagnosticReport.State state = requestedState;
        if (state == DiagnosticReport.State.IDLE && store.getMonitorStopState() != MonitorStopState.NONE)
            state = store.getMonitorStopState() == MonitorStopState.PAUSED ? DiagnosticReport.State.STOPPED : DiagnosticReport.State.NEEDS_REVIEW;
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        boolean allowed = notifications != null && notifications.areNotificationsEnabled();
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            allowed = false;
        NotificationChannel channel = notifications == null ? null : notifications.getNotificationChannel("monitor_alert");
        allowed &= channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        return DiagnosticCollector.capture(context, DiagnosticReport.A11y.valueOf(AccessibilityAccess.state(context).name()),
                allowed, ShizukuInputBridge.get(context).isReady(), store.isArmed(), mode, state);
    }

    static long backoffMillis(int failures) {
        if (failures <= 0) return 0;
        return Math.min(MAX_BACKOFF_MS, 30000L << Math.min(5, failures - 1));
    }
    static long delayUntil(long target, long now) { return target <= now ? 0 : target - now; }
    static DiagnosticReport.State mappedState(String name) { Mapped value = map(name); return value == null ? null : value.state; }
    static DiagnosticReport.Code mappedCode(String name, String detail) {
        Mapped value = mapWithReason(name, detail); return value == null ? DiagnosticReport.Code.UNKNOWN : value.code;
    }
    private static IOException cancelled() { return new IOException("本次云端同步已取消，尚未确认的记录保留在手机。"); }
    private static final class Mapped {
        final DiagnosticReport.State state;
        final DiagnosticReport.Tag tag;
        final DiagnosticReport.Code code;
        final String key;
        Mapped(DiagnosticReport.State state, DiagnosticReport.Tag tag, DiagnosticReport.Code code) {
            this.state = state; this.tag = tag; this.code = code; key = String.valueOf(state) + '|' + tag.name() + '|' + code.name();
        }
    }
    private static Mapped mapWithReason(String name, String detail) {
        // Timing is a local scheduler fact, never evidence of official sale or inventory.
        if ("SALE_TIMING".equals(name)) {
            DiagnosticReport.Code code;
            if (detail == null || detail.length() > 64) return null;
            switch (detail) {
                case "waiting_for_sale": code = DiagnosticReport.Code.SALE_WAITING; break;
                case "opening": code = DiagnosticReport.Code.SALE_OPENING; break;
                case "monitoring": code = DiagnosticReport.Code.SALE_MONITORING; break;
                case "stopped": code = DiagnosticReport.Code.SALE_STOPPED; break;
                case "reservation_back_to_detail_once": code = DiagnosticReport.Code.SALE_RESERVATION_REENTRY; break;
                default: return null;
            }
            return new Mapped(null, DiagnosticReport.Tag.MONITOR, code);
        }
        // A local scroll proposal/completion establishes neither a settled page nor stock/order facts.
        if ("DETAIL_SETTLE".equals(name)) {
            if ("scroll_proposed_once".equals(detail))
                return new Mapped(null, DiagnosticReport.Tag.INPUT, DiagnosticReport.Code.DETAIL_SCROLL_PROPOSED);
            if ("scroll_command_completed".equals(detail))
                return new Mapped(null, DiagnosticReport.Tag.INPUT, DiagnosticReport.Code.DETAIL_SCROLL_COMMAND_COMPLETED);
            return null;
        }
        // A dispatch callback does not establish selected quantity or an accepted order.
        if ("ACTION".equals(name)) {
            return "increase_quantity_dispatched".equals(detail)
                    ? new Mapped(null, DiagnosticReport.Tag.INPUT, DiagnosticReport.Code.QUANTITY_INCREASE_DISPATCHED) : null;
        }
        if ("READ_RECOVERY".equals(name)) {
            if ("read_recovery_waiting".equals(detail)) return new Mapped(null, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_RETRY);
            if ("read_content_revalidated".equals(detail)) return new Mapped(null, DiagnosticReport.Tag.MONITOR, DiagnosticReport.Code.READ_RECOVERED);
            return null;
        }
        Mapped base = map(name);
        if (base == null) return null;
        DiagnosticReport.Code reason = reasonCode(name, detail);
        return reason == null ? base : new Mapped(base.state, base.tag, reason);
    }
    /** Exact local reason IDs only; never parse a model/page/error message as a reason. */
    private static DiagnosticReport.Code reasonCode(String state, String detail) {
        if (detail == null || detail.isEmpty() || detail.length() > 128) return null;
        if ("SERVICE".equals(state)) {
            if ("connected_ready".equals(detail)) return DiagnosticReport.Code.CONNECTED;
            // Service records the exception's class only. Reduce this known prefix to READ_FAILED,
            // without retaining even that class name; arbitrary suffixes are not accepted.
            if (detail.startsWith("initialization_failed:")
                    && detail.substring("initialization_failed:".length()).matches("[A-Za-z][A-Za-z0-9$]{0,79}"))
                return DiagnosticReport.Code.READ_FAILED;
        }
        switch (detail) {
            case "detail_scroll_anchor_unverified": return "UNKNOWN".equals(state) || "BLOCKED".equals(state)
                    ? DiagnosticReport.Code.DETAIL_SCROLL_ANCHOR_UNVERIFIED : null;
            case "detail_scroll_input_unconfirmed": return "UNKNOWN".equals(state) || "BLOCKED".equals(state)
                    ? DiagnosticReport.Code.DETAIL_SCROLL_INPUT_UNCONFIRMED : null;
            case "quantity_target_unverified": return "UNKNOWN".equals(state) ? DiagnosticReport.Code.QUANTITY_TARGET_UNVERIFIED : null;
            case "quantity_transition_page_changed": return "UNKNOWN".equals(state) ? DiagnosticReport.Code.QUANTITY_TRANSITION_PAGE_CHANGED : null;
            case "quantity_transition_unverified": return "UNKNOWN".equals(state) ? DiagnosticReport.Code.QUANTITY_TRANSITION_UNVERIFIED : null;
            case "quantity_input_unconfirmed": return "UNKNOWN".equals(state) ? DiagnosticReport.Code.QUANTITY_INPUT_UNCONFIRMED : null;
            case "reservation_selection_only": return DiagnosticReport.Code.RESERVATION_ONLY;
            case "page_changed_during_read": case "unstable_page": case "empty_page": case "missing_observation":
            case "native_error_page": case "stale_or_future_observation": case "target_prices_not_readable":
            case "selected_session_not_verified": case "ticket_entry_not_verified":
            case "adapter_confirm_button_not_verified": case "adapter_price_selection_not_verified":
            case "adapter_quantity_or_total_not_verified": case "adapter_session_evidence_mismatch":
            case "viewer_selection_not_verified": case "viewer_page_changed":
                return DiagnosticReport.Code.READ_FAILED;
            case "foreground_changed": case "other_application": return DiagnosticReport.Code.FOREGROUND_CHANGED;
            case "operation_timeout": case "local_input_timeout": return DiagnosticReport.Code.TIMEOUT;
            case "local_input_unavailable": case "service_reconnected": case "service_interrupted":
                return DiagnosticReport.Code.DISCONNECTED;
            case "accessibility_unavailable": return DiagnosticReport.Code.PERMISSION_REQUIRED;
            case "ui_action_failed": case "ui_action_not_dispatched": case "viewer_input_unconfirmed":
                return DiagnosticReport.Code.INPUT_FAILED;
            case "screen_locked": case "verification_login_or_network": case "sensitive_target":
                return DiagnosticReport.Code.BLOCKED;
            case "deadline_reached": case "event_date_passed": return DiagnosticReport.Code.EXPIRED;
            case "invalid_configuration": return DiagnosticReport.Code.CONFIG_INVALID;
            case "user_started": return "ARMED".equals(state) ? DiagnosticReport.Code.SESSION_STARTED : null;
            case "user_paused": return "PAUSED".equals(state) ? DiagnosticReport.Code.SESSION_STOPPED : null;
            case "explicit_available_label": return "IN_STOCK".equals(state) ? DiagnosticReport.Code.IN_STOCK : null;
            case "all_target_tiers_explicitly_unavailable": return "OUT_OF_STOCK".equals(state) ? DiagnosticReport.Code.OUT_OF_STOCK : null;
            case "official_pending_order_verified": return "ORDER_PENDING_PAYMENT".equals(state) ? DiagnosticReport.Code.ORDER_PENDING_VERIFIED : null;
            case "order_input_unconfirmed": case "order_state_unavailable": case "order_final_guard_failed":
            case "order_permit_not_consumed": return "ORDER_UNKNOWN".equals(state) ? DiagnosticReport.Code.ORDER_OUTCOME_UNKNOWN : null;
            default: return null;
        }
    }
    private static Mapped map(String name) {
        if (name == null) return null;
        DiagnosticReport.State state;
        DiagnosticReport.Tag tag = DiagnosticReport.Tag.MONITOR;
        DiagnosticReport.Code code;
        switch (name) {
            case "CONFIG_INVALID": state = DiagnosticReport.State.CONFIG_INVALID; code = DiagnosticReport.Code.CONFIG_INVALID; break;
            case "EXPIRED": state = DiagnosticReport.State.EXPIRED; code = DiagnosticReport.Code.EXPIRED; break;
            case "BLOCKED": state = DiagnosticReport.State.BLOCKED; code = DiagnosticReport.Code.BLOCKED; break;
            case "UNRELATED": state = DiagnosticReport.State.UNRELATED; code = DiagnosticReport.Code.FOREGROUND_CHANGED; break;
            case "UNKNOWN": state = DiagnosticReport.State.UNKNOWN; code = DiagnosticReport.Code.UNKNOWN; break;
            case "READY": state = DiagnosticReport.State.READY; code = DiagnosticReport.Code.UNKNOWN; break;
            case "PROBING": state = DiagnosticReport.State.PROBING; code = DiagnosticReport.Code.UNKNOWN; break;
            case "OUT_OF_STOCK": state = DiagnosticReport.State.OUT_OF_STOCK; code = DiagnosticReport.Code.OUT_OF_STOCK; break;
            case "IN_STOCK": state = DiagnosticReport.State.IN_STOCK; code = DiagnosticReport.Code.IN_STOCK; break;
            case "TIER_SELECTED": state = DiagnosticReport.State.TIER_SELECTED; code = DiagnosticReport.Code.TIER_SELECTED; break;
            case "CHECKOUT": state = DiagnosticReport.State.CHECKOUT; tag = DiagnosticReport.Tag.CHECKOUT; code = DiagnosticReport.Code.UNKNOWN; break;
            case "ARMED": state = DiagnosticReport.State.PROBING; code = DiagnosticReport.Code.SESSION_STARTED; break;
            case "PAUSED": state = DiagnosticReport.State.STOPPED; code = DiagnosticReport.Code.SESSION_STOPPED; break;
            case "ORDER_PENDING_PAYMENT": state = DiagnosticReport.State.PENDING_PAYMENT_VERIFIED; tag = DiagnosticReport.Tag.CHECKOUT; code = DiagnosticReport.Code.ORDER_PENDING_VERIFIED; break;
            case "ORDER_UNKNOWN": state = DiagnosticReport.State.NEEDS_REVIEW; tag = DiagnosticReport.Tag.CHECKOUT; code = DiagnosticReport.Code.ORDER_OUTCOME_UNKNOWN; break;
            case "ORDER_REVIEW": case "ORDER_REJECTED": state = DiagnosticReport.State.NEEDS_REVIEW; tag = DiagnosticReport.Tag.CHECKOUT; code = DiagnosticReport.Code.UNKNOWN; break;
            case "START_FAILED": case "START_REJECTED": state = DiagnosticReport.State.BLOCKED; code = DiagnosticReport.Code.UNKNOWN; break;
            case "ACTION_FAILED": state = DiagnosticReport.State.NEEDS_REVIEW; tag = DiagnosticReport.Tag.INPUT; code = DiagnosticReport.Code.INPUT_FAILED; break;
            case "SERVICE": state = null; tag = DiagnosticReport.Tag.SERVICE; code = DiagnosticReport.Code.UNKNOWN; break;
            case "CATALOG": case "CATALOG_PANEL": state = null; tag = DiagnosticReport.Tag.CATALOG; code = DiagnosticReport.Code.UNKNOWN; break;
            default: return null;
        }
        return new Mapped(state, tag, code);
    }
}
