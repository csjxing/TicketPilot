package cn.local.tickethelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import cn.local.tickethelper.core.ActionPacing;
import cn.local.tickethelper.core.Decision;
import cn.local.tickethelper.core.DecisionEngine;
import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.Observation;
import cn.local.tickethelper.core.UiItem;
import cn.local.tickethelper.ai.AiContextRepository;
import cn.local.tickethelper.ai.AiPageSummary;
import cn.local.tickethelper.catalog.CatalogHandoff;
import cn.local.tickethelper.checkout.CheckoutDecision;
import cn.local.tickethelper.checkout.CheckoutObservation;
import cn.local.tickethelper.checkout.LockAuthorization;
import cn.local.tickethelper.checkout.OrderAttempt;
import cn.local.tickethelper.checkout.OrderAttemptStore;
import cn.local.tickethelper.checkout.OrderCheckoutAdapter;
import cn.local.tickethelper.checkout.OrderLockEngine;
import cn.local.tickethelper.checkout.CheckoutNativeTarget;
import cn.local.tickethelper.checkout.CheckoutParseResult;
import cn.local.tickethelper.checkout.ViewerMatcher;
import cn.local.tickethelper.checkout.VerifiedSubmitProposal;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** User-armed local UI monitor. Final order input is separate from retryable SKU input; no payment actions. */
public final class MonitorService extends AccessibilityService {
    public static MonitorService instance;
    private volatile boolean connectionReady;
    private static final String TARGET = "cn.damai";
    private static final String ACTIVE = "monitor_status", ALERT = "monitor_alert";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DecisionEngine engine = new DecisionEngine();
    private final OrderLockEngine orderEngine = new OrderLockEngine();
    private LockAuthorization orderAuthorization;
    private OrderAttemptStore orderAttempts;
    private OrderAttempt liveOrderAttempt;
    private long orderConfigRevision;
    private final java.util.Map<String, Long> viewerSelectionTargets = new java.util.HashMap<>();
    private AppStore store;
    private OcrReader ocr;
    private OcrReader aiPageReader;
    private long aiReadToken;
    private Runnable aiReadWait;
    private MonitorConfig config;
    private ShizukuInputBridge inputBridge;
    private CatalogController catalog;
    private CatalogOverlay catalogOverlay;
    private long panelGeneration;
    private long selectionStartGeneration;
    private Runnable panelWatch;
    private String inputMode = AppStore.INPUT_ACCESSIBILITY;
    private boolean running, busy, targetSeen, screenReceiverRegistered;
    private long generation, windowGeneration, startedAt, lastNavigationAt, nextActionAt, lastCaptureAt, scheduledAt;
    private long lastWindowChangeElapsed;
    private String lastWindowEventSummary = "none";
    private long transitionAt;
    private Decision.Action transitionAction = Decision.Action.NONE;
    private String lastStatus = "";
    private int unknownCount, actionFailures;
    private final cn.local.tickethelper.core.ReadStability readRecovery = new cn.local.tickethelper.core.ReadStability();
    private long readBoundaryGeneration, lastReadBoundaryElapsed;
    private final Runnable tick = () -> { scheduledAt = 0; scan(); };
    private Runnable watchdog;
    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && running)
                stop("手机已熄屏，任务已暂停。", "BLOCKED", "screen_locked", true);
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connectionReady = false;
        if (instance == this) instance = null;
        try {
        cancelAiPageRead();
        boolean wasRunning = running;
        invalidateOrderAuthorization();
        // Android may reconnect the same Service object. Invalidate callbacks before replacing readers.
        generation++; running = false; busy = false; targetSeen = false;
        if (inputBridge != null) inputBridge.cancelPending();
        handler.removeCallbacks(tick); scheduledAt = 0;
        if (watchdog != null) { handler.removeCallbacks(watchdog); watchdog = null; }
        engine.reset(); transitionAction = Decision.Action.NONE;
        releaseConnectionResources();
        store = new AppStore(this); ocr = new OcrReader(this);
        inputBridge = ShizukuInputBridge.get(this);
        catalog = new CatalogController(this);
        // Reconnection never resumes a task without a new deliberate user start.
        if (wasRunning || store.isArmed()) {
            store.recordMonitorStop("连接已恢复，前次任务已暂停；请核对后重新开始。", "BLOCKED", false, true);
        } else {
            store.setArmed(false);
            if (store.getMonitorStopState() == cn.local.tickethelper.core.MonitorStopState.NONE)
                store.setStatus("无障碍已连接；请设置目标并主动开始。");
        }
        if (wasRunning) store.appendEvent("PAUSED", "service_reconnected", 0);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.cancel(10);
        notifications.cancel(12);
        notifications.createNotificationChannel(new NotificationChannel(ACTIVE, "运行状态", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel alert = new NotificationChannel(ALERT, "有票及需要处理", NotificationManager.IMPORTANCE_HIGH);
        alert.enableVibration(true); notifications.createNotificationChannel(alert);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(stopReceiver, filter);
        screenReceiverRegistered = true;
        connectionReady = true; instance = this;
        store.appendEvent("SERVICE", "connected_ready", 0);
        } catch (RuntimeException failed) {
            connectionReady = false;
            if (instance == this) instance = null;
            releaseConnectionResources();
            if (store != null) {
                store.recordMonitorStop("无障碍已开启，但服务初始化失败。请在系统中关闭再开启本服务。", "BLOCKED", false, false);
                store.appendEvent("SERVICE", "initialization_failed:" + failed.getClass().getSimpleName(), 0);
            }
        }
    }

    public boolean isConnectionReady() { return connectionReady; }
    public boolean isRunning() { return running; }
    /** One explicit read only. No network, navigation, clicks or model invocation. */
    public boolean requestAiPageRead() {
        if (store == null) return false;
        pause("正在读取公开页面摘要，监控保持暂停。");
        CatalogController.cancel(this);
        cancelAiPageRead();
        AiContextRepository.captureWaiting();
        long token = ++aiReadToken, until = SystemClock.elapsedRealtime() + 25_000;
        aiReadWait = () -> readAiPageWhenReady(token, until);
        handler.post(aiReadWait);
        return true;
    }
    private void cancelAiPageRead() {
        aiReadToken++;
        if (aiReadWait != null) { handler.removeCallbacks(aiReadWait); aiReadWait = null; }
        if (aiPageReader != null) { aiPageReader.close(); aiPageReader = null; }
    }
    private void readAiPageWhenReady(long token, long until) {
        if (token != aiReadToken) return;
        if (!unlocked() || running || SystemClock.elapsedRealtime() >= until) {
            AiContextRepository.captureFailed("本次页面读取已结束：请解锁后重新读取，并在25秒内切回大麦。");
            cancelAiPageRead(); return;
        }
        if (!TARGET.equals(foreground())) { handler.postDelayed(aiReadWait, 600); return; }
        List<UiItem> nodes = readNodes();
        long at = System.currentTimeMillis(), pageToken = windowGeneration;
        Observation initial = new Observation(TARGET, nodes, at);
        String summary = AiPageSummary.summarize(initial);
        if (summary.startsWith("页面类型：账号") || summary.startsWith("页面类型：系统")) {
            AiContextRepository.recordPage(initial); cancelAiPageRead(); return;
        }
        aiPageReader = new OcrReader(this);
        aiPageReader.read(nodes, at, (items, evidence, error) -> {
            if (token != aiReadToken) return;
            if (!unlocked() || running || !TARGET.equals(foreground()) || pageToken != windowGeneration
                    || SystemClock.elapsedRealtime() >= until) {
                AiContextRepository.captureFailed("页面在读取时发生变化，未保存摘要；请停留在目标页重读。");
            } else if (error != null) {
                // Node-only public projection is still useful, but never fill missing visual labels.
                AiContextRepository.recordPage(initial);
            } else {
                List<UiItem> merged = new ArrayList<>(nodes); merged.addAll(items);
                AiContextRepository.recordPage(new Observation(TARGET, merged, at, evidence));
            }
            cancelAiPageRead();
        });
        aiReadWait = () -> {
            if (token == aiReadToken) {
                AiContextRepository.captureFailed("页面读取超时，库存仍未知。请回助手重新尝试。"); cancelAiPageRead();
            }
        };
        handler.postDelayed(aiReadWait, Math.max(1, until - SystemClock.elapsedRealtime()));
    }
    CatalogController catalogController() { return catalog; }
    List<UiItem> catalogReadNodes() { return readNodes(); }
    long catalogWindowGeneration() { return windowGeneration; }
    long catalogReadBoundaryGeneration() { return readBoundaryGeneration; }
    long catalogLastReadBoundaryElapsed() { return lastReadBoundaryElapsed; }
    int catalogCurrentReadWindow() { return currentReadWindow(); }
    /** Main-thread timing/diagnostics only; these never replace the existing frame generation gate. */
    long catalogLastWindowChangeElapsed() { return lastWindowChangeElapsed; }
    String catalogLastWindowEventSummary() { return lastWindowEventSummary; }

    /** User-selected in-place workflow. Opening the panel never starts monitoring. */
    public void openInPlaceCatalog() {
        closeInPlaceCatalog();
        pause("正在大麦中选择场次，监控保持暂停。");
        if (catalog == null || !unlocked()) return;
        catalog.cancelInternal(false);
        CatalogController.dismissResultNotification(this);
        catalogOverlay = new CatalogOverlay(this, store.loadConfig().quantity, new CatalogOverlay.Actions() {
            @Override public void captureSelected() {
                if (catalogOverlay != null && catalogOverlay.isOpen() && catalog != null)
                    catalog.captureSelectedInPlace();
            }
            @Override public void save(CatalogStore.Snapshot snapshot, java.util.Set<String> keys, int quantity) {
                withPanelOnDamai(() -> {
                    try {
                        MonitorConfig saved = CatalogPlanSaver.save(MonitorService.this, snapshot, keys, quantity);
                        catalogOverlay.showSaved(saved.city + " · " + saved.date + " " + saved.time + "\n"
                                + saved.prices + " 元 · " + saved.quantity + " 张 · 票款上限 ¥" + saved.maxTotal
                                + "\n计划已保存。当前选票测试会在确认购买页暂停。");
                        store.appendEvent("CATALOG_PANEL", "plan_saved", 0);
                    } catch (IllegalArgumentException invalid) {
                        catalogOverlay.showFailure(invalid.getMessage());
                    }
                });
            }
            @Override public void startSelectionTest() { withPanelOnDamai(MonitorService.this::startInPlaceSelectionTest); }
            @Override public void closed() {
                if (catalog != null) catalog.cancelInternal(false);
                if (panelWatch != null) handler.removeCallbacks(panelWatch);
            }
        });
        long token = ++panelGeneration;
        if (!catalog.openTargetForPanel()) { closeInPlaceCatalog(); return; }
        awaitPanelForeground(token, SystemClock.elapsedRealtime() + 20_000);
    }

    private void awaitPanelForeground(long token, long deadline) {
        if (token != panelGeneration || catalogOverlay == null) return;
        if (!unlocked() || running || SystemClock.elapsedRealtime() >= deadline) {
            closeInPlaceCatalog(); return;
        }
        if (!TARGET.equals(foreground())) {
            handler.postDelayed(() -> awaitPanelForeground(token, deadline), 200); return;
        }
        catalogOverlay.showReady();
        store.appendEvent("CATALOG_PANEL", "opened", 0);
        panelWatch = new Runnable() {
            @Override public void run() {
                if (token != panelGeneration || catalogOverlay == null || !catalogOverlay.isOpen()) return;
                if (!unlocked() || running || !TARGET.equals(foreground())) { closeInPlaceCatalog(); return; }
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(panelWatch, 500);
    }

    private void withPanelOnDamai(Runnable action) {
        CatalogOverlay expected = catalogOverlay;
        if (expected == null || !expected.isOpen()) return;
        expected.hideForRead();
        CatalogHandoff handoff = new CatalogHandoff(SystemClock.elapsedRealtime(), 10_000, 500);
        awaitPanelAction(expected, panelGeneration, handoff, action);
    }

    private void awaitPanelAction(CatalogOverlay expected, long token, CatalogHandoff handoff, Runnable action) {
        if (token != panelGeneration || catalogOverlay != expected || !expected.isOpen()) return;
        long now = SystemClock.elapsedRealtime();
        boolean settled = TARGET.equals(foreground()) && now - lastWindowChangeElapsed >= 300;
        switch (handoff.observe(now, settled, unlocked(), running || store.isArmed())) {
            case WAITING: handler.postDelayed(() -> awaitPanelAction(expected, token, handoff, action), 200); break;
            case READY: action.run(); break;
            case TIMED_OUT: expected.showFailure("请在大麦保持原场次页面，再重新读取核对。"); break;
            default: closeInPlaceCatalog(); break;
        }
    }

    void deliverInPlaceCatalog(CatalogStore.Snapshot snapshot) {
        if (catalogOverlay == null || !catalogOverlay.isOpen()) return;
        if (!unlocked() || !TARGET.equals(foreground())) { closeInPlaceCatalog(); return; }
        if ("TIERS".equals(snapshot.stage)) catalogOverlay.showResult(snapshot);
        else catalogOverlay.showFailure(snapshot.status);
        store.appendEvent("CATALOG_PANEL", "result_shown", 0);
    }

    private void startInPlaceSelectionTest() {
        if (catalogOverlay == null || !catalogOverlay.isOpen() || !unlocked() || !TARGET.equals(foreground())) return;
        MonitorConfig saved = store.loadConfig();
        MonitorConfig test = new MonitorConfig(saved.eventKeyword, saved.city, saved.date, saved.time, saved.prices,
                saved.quantity, saved.maxTotal, saved.deadline, saved.minIntervalMs, saved.maxIntervalMs, MonitorConfig.Mode.PREPARE);
        if (!test.validate().isEmpty() || store.isImportedTargetPendingReview()) {
            catalogOverlay.showFailure("请先读取并核对保存当前场次。"); return;
        }
        closeInPlaceCatalog();
        store.saveConfig(test);
        // Overlay removal emits a real window event. Start from a new observation after it settles.
        long token = ++selectionStartGeneration;
        long revision = store.getConfigRevision();
        handler.postDelayed(() -> {
            if (token == selectionStartGeneration && revision == store.getConfigRevision()
                    && store.loadConfig().mode == MonitorConfig.Mode.PREPARE
                    && unlocked() && TARGET.equals(foreground()) && !running && !store.isArmed()) arm();
        }, 700);
    }

    private void closeInPlaceCatalog() {
        panelGeneration++;
        if (panelWatch != null) { handler.removeCallbacks(panelWatch); panelWatch = null; }
        CatalogOverlay previous = catalogOverlay; catalogOverlay = null;
        if (previous != null) previous.close();
    }

    public void arm() {
        cancelAiPageRead();
        armInternal(null);
    }

    public void armForOrder(LockAuthorization authorization) {
        cancelAiPageRead();
        armInternal(authorization);
    }

    private void armInternal(LockAuthorization authorization) {
        if (running) return;
        if (!connectionReady) return;
        closeInPlaceCatalog();
        if (catalog != null) catalog.cancelInternal(false);
        config = store.loadConfig();
        orderAuthorization = null; liveOrderAttempt = null; orderAttempts = null;
        if (store.isImportedTargetPendingReview()) {
            pause("换机目标尚未核对，请在助手重新选择演出后开始。"); return;
        }
        if (config.mode == MonitorConfig.Mode.LOCK_ORDER) {
            if (!OrderCheckoutAdapter.isLiveVerified()) {
                pause("自动下单的确认页及待付款页适配尚未真机验收，请先使用到确认页暂停模式。"); return;
            }
            if (!OrderCheckoutAdapter.supportsInstalledVersion(this)) {
                pause("当前大麦版本的订单页面尚未适配，请使用到确认页暂停模式。"); return;
            }
            if (authorization == null || !authorization.validFor(config, System.currentTimeMillis())
                    || store.getViewerBindings().size() != config.quantity
                    || !authorization.viewerBindingHash.equals(LockAuthorization.viewerBindingsHash(store.getViewerBindings()))) {
                pause("请在助手中重新核对本次演出、预算及观演人，再主动开始自动下单。"); return;
            }
            try {
                orderAttempts = new OrderAttemptStore(getFilesDir());
                if (orderAttempts.hasUnresolved()) {
                    pause("存在先前订单提交记录，请先到大麦核查订单；不会再次自动提交。"); return;
                }
            } catch (IOException | RuntimeException error) {
                pause("订单提交记录无法核实，自动下单暂停。"); return;
            }
            orderAuthorization = authorization;
            orderConfigRevision = store.getConfigRevision();
            viewerSelectionTargets.clear();
        }
        inputMode = store.getInputMode();
        if (AppStore.INPUT_SHIZUKU.equals(inputMode) && !inputBridge.isReady()) {
            pause("手机本地调试输入尚未连接，请回助手授权并连接后再开始。"); return;
        }
        List<String> errors = config.validate();
        if (!errors.isEmpty()) { pause("请修正目标配置后再开始。"); return; }
        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            pause("请先允许通知，以接收有票及异常提醒。"); return;
        }
        NotificationChannel alerts = getSystemService(NotificationManager.class).getNotificationChannel(ALERT);
        if (alerts == null || alerts.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            pause("请在通知设置开启“有票及需要处理”类别。"); return;
        }
        if (config.deadline.toEpochMilli() <= System.currentTimeMillis()) { pause("截止时间已过，请设置新任务。"); return; }
        if (ocr.isBusy()) { ocr.close(); ocr = new OcrReader(this); }
        generation++; engine.reset(); busy = false; running = true; targetSeen = false;
        startedAt = SystemClock.elapsedRealtime(); lastNavigationAt = 0; nextActionAt = 0;
        lastCaptureAt = 0; unknownCount = 0; actionFailures = 0; readRecovery.reset();
        lastStatus = ""; transitionAction = Decision.Action.NONE; scheduledAt = 0;
        getSystemService(NotificationManager.class).cancel(11);
        getSystemService(NotificationManager.class).cancel(12);
        store.setArmed(true); store.appendEvent("ARMED", "user_started", 0);
        status("已启动；请在 20 秒内打开大麦目标演出页。");
        schedule(600);
    }

    public void pause(String reason) { stop(reason, "PAUSED", "user_paused", false); }

    private void stop(String message, String state, String reason, boolean alert) {
        selectionStartGeneration++;
        boolean wasRunning = running;
        cancelAiPageRead();
        running = false; busy = false; generation++; engine.reset(); handler.removeCallbacks(tick);
        if (inputBridge != null) inputBridge.cancelPending();
        invalidateOrderAuthorization();
        scheduledAt = 0;
        if (watchdog != null) { handler.removeCallbacks(watchdog); watchdog = null; }
        if (store == null) return;
        store.recordMonitorStop(message, state, alert, wasRunning); store.appendEvent(state, reason, 0);
        getSystemService(NotificationManager.class).cancel(10);
        getSystemService(NotificationManager.class).cancel(12);
        if (alert) notifyUser(message);
    }

    private void invalidateOrderAuthorization() {
        orderAuthorization = null;
        if (liveOrderAttempt != null && orderAttempts != null) {
            try { orderAttempts.markUnknown(liveOrderAttempt.attemptId); }
            catch (IOException | RuntimeException ignored) {
                // The reservation already exists. Never remove or replace it on a write failure.
            }
        }
        liveOrderAttempt = null;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        // Catalog reads use the same fresh-page counter while normal monitoring remains paused.
        if (!running) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    || TARGET.equals(pkg)) recordWindowChange(type, pkg);
            return;
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            recordWindowChange(type, pkg);
            String active = foreground();
            if (targetSeen && !active.isEmpty() && !TARGET.equals(active)) {
                stop("已切换应用或打开系统界面，任务暂停。", "UNRELATED", "foreground_changed", true); return;
            }
        } else if (TARGET.equals(pkg)) recordWindowChange(type, pkg);
        if (!TARGET.equals(pkg)) return;
        // Events may shorten observation latency; actions still respect the configured interval.
        if (!busy) schedule(500);
    }

    /** Strict input generation still includes every old event. Read recovery distinguishes content-only noise. */
    private void recordWindowChange(int type, String pkg) {
        windowGeneration++;
        lastWindowChangeElapsed = SystemClock.elapsedRealtime();
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || !TARGET.equals(pkg)) {
            readBoundaryGeneration++;
            lastReadBoundaryElapsed = lastWindowChangeElapsed;
        }
        String source = TARGET.equals(pkg) ? "target" : getPackageName().equals(pkg) ? "helper"
                : "android".equals(pkg) || "com.android.systemui".equals(pkg) || "com.android.settings".equals(pkg)
                ? "system" : "other";
        String event;
        switch (type) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: event = "window_state_changed"; break;
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED: event = "windows_changed"; break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: event = "window_content_changed"; break;
            case AccessibilityEvent.TYPE_VIEW_SELECTED: event = "view_selected"; break;
            case AccessibilityEvent.TYPE_VIEW_CLICKED: event = "view_clicked"; break;
            default: event = "other"; break;
        }
        lastWindowEventSummary = source + ":" + event;
    }

    public boolean isWaitingForDamai() { return running && !targetSeen; }

    public int waitingForDamaiSeconds() {
        return isWaitingForDamai() ? (int) Math.max(0, (20_000L - (SystemClock.elapsedRealtime() - startedAt) + 999) / 1000) : 0;
    }

    private boolean unlocked() {
        return getSystemService(PowerManager.class).isInteractive()
                && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    private String foreground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        return root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    private void schedule(long delay) {
        long at = SystemClock.elapsedRealtime() + Math.max(0, delay);
        if (scheduledAt > 0 && scheduledAt <= at) return;
        handler.removeCallbacks(tick);
        if (running) { scheduledAt = at; handler.postDelayed(tick, Math.max(0, delay)); }
    }

    private void scan() {
        if (!running || busy) return;
        if (AppStore.INPUT_SHIZUKU.equals(inputMode) && !inputBridge.isReady()) {
            stop("手机本地调试输入已断开，任务暂停，请重新连接后主动开始。", "BLOCKED", "local_input_unavailable", true); return;
        }
        if (System.currentTimeMillis() >= config.deadline.toEpochMilli()) {
            stop("已到截止时间，任务停止。", "EXPIRED", "deadline_reached", true); return;
        }
        if (!unlocked()) { stop("手机已锁屏或熄屏，任务已暂停。", "BLOCKED", "screen_locked", true); return; }
        String pkg = foreground();
        if (!TARGET.equals(pkg)) {
            if (!targetSeen && SystemClock.elapsedRealtime() - startedAt < 20_000) { schedule(600); return; }
            stop("当前不在大麦页面，任务已暂停。", "UNRELATED", "foreground_changed", true); return;
        }
        targetSeen = true;
        if (config.mode == MonitorConfig.Mode.LOCK_ORDER && !orderGrantCurrent()) {
            stop("自动下单授权或目标设置已变化，请回助手重新核对。", "PAUSED", "order_grant_invalidated", true); return;
        }
        if (ocr.isBusy()) { schedule(700); return; }
        busy = true;
        long token = generation, pageToken = windowGeneration, began = SystemClock.elapsedRealtime();
        long boundaryToken = readBoundaryGeneration;
        int readWindow = currentReadWindow();
        long observedAt = System.currentTimeMillis();
        if (watchdog != null) handler.removeCallbacks(watchdog);
        watchdog = () -> {
            if (running && token == generation && busy)
                stop("界面读取或操作超时，任务已暂停。", "BLOCKED", "operation_timeout", true);
        };
        handler.postDelayed(watchdog, 8000);
        if (liveOrderAttempt != null) {
            handleOrderCheckout(token, pageToken); return;
        }
        List<UiItem> nodes = readNodes();
        Decision decision = engine.evaluate(config, new Observation(TARGET, nodes, observedAt), lastNavigationAt);
        if (decision.state != Decision.State.UNKNOWN) {
            if (valid(token, pageToken)) readRecovery.reset();
            handle(decision, began, token, pageToken, nodes.size(), 0, false); return;
        }
        long sinceCapture = SystemClock.elapsedRealtime() - lastCaptureAt;
        if (sinceCapture < 700) { busy = false; schedule(700 - sinceCapture); return; }
        lastCaptureAt = SystemClock.elapsedRealtime();
        ocr.readGuarded(nodes, observedAt, (items, evidence, proof, error) -> {
            if (token != generation) return;
            if (error != null) {
                stop("页面截图或本地识别不可用，请在手机检查后重新开始。", "BLOCKED", error, true); return;
            }
            if (!valid(token, pageToken)) {
                long currentPage = windowGeneration;
                if (running && unlocked() && TARGET.equals(foreground())
                        && cn.local.tickethelper.core.ReadStability.mayRecheck(pageToken, currentPage,
                        boundaryToken, readBoundaryGeneration, readWindow, currentReadWindow(),
                        SystemClock.elapsedRealtime() - began)
                        && cn.local.tickethelper.core.ReadStability.sameNative(nodes, readNodes())) {
                    // A content notification alone is not a changed page. Recheck the complete original
                    // crop, including new dialogs/selection colours, before rebasing the strict token.
                    ocr.verifyFrame(proof, () -> valid(token, currentPage) && boundaryToken == readBoundaryGeneration
                                    && currentReadWindow() == readWindow
                                    && SystemClock.elapsedRealtime() - began < cn.local.tickethelper.core.ReadStability.MAX_FRAME_AGE_MS,
                            matches -> {
                                if (token != generation) return;
                                if (matches && valid(token, currentPage) && boundaryToken == readBoundaryGeneration
                                        && currentReadWindow() == readWindow
                                        && SystemClock.elapsedRealtime() - began < cn.local.tickethelper.core.ReadStability.MAX_FRAME_AGE_MS
                                        && cn.local.tickethelper.core.ReadStability.sameNative(nodes, readNodes())) {
                                    store.appendEvent("READ_RECOVERY", "read_content_revalidated", SystemClock.elapsedRealtime() - began);
                                    acceptMonitorRead(nodes, items, evidence, observedAt, began, token, currentPage);
                                } else deferChangedRead(began);
                            });
                } else deferChangedRead(began);
                return;
            }
            acceptMonitorRead(nodes, items, evidence, observedAt, began, token, pageToken);
        });
    }

    private int currentReadWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return -1;
        android.view.accessibility.AccessibilityWindowInfo window = root.getWindow();
        return window != null && window.isActive() && window.getDisplayId() == android.view.Display.DEFAULT_DISPLAY
                ? root.getWindowId() : -1;
    }

    private void acceptMonitorRead(List<UiItem> nodes, List<UiItem> items, Observation.VerifiedSelection evidence,
                                   long observedAt, long began, long token, long pageToken) {
        if (!valid(token, pageToken)) { deferChangedRead(began); return; }
        readRecovery.reset();
        List<UiItem> merged = new ArrayList<>(nodes);
        for (UiItem text : items) {
            boolean duplicate = nodes.stream().anyMatch(n -> n.text.equals(text.text)
                    && Math.abs(n.centerY() - text.centerY()) < 30 && Math.abs(n.centerX() - text.centerX()) < 50);
            if (!duplicate) merged.add(text);
        }
        // Preserve the original OCR observation time; visual revalidation never renews input freshness.
        Decision read = engine.evaluate(config, new Observation(TARGET, merged, observedAt, evidence), lastNavigationAt);
        handle(read, began, token, pageToken, nodes.size(), items.size(), true);
    }

    private void deferChangedRead(long began) {
        if (!running) return;
        busy = false;
        store.appendEvent("UNKNOWN", "page_changed_during_read", SystemClock.elapsedRealtime() - began);
        if (!readRecovery.retry(SystemClock.elapsedRealtime())) {
            stop("页面信息持续变化，重读仍未核实。请停留在目标票档页后重新开始。", "UNKNOWN", "unstable_page", true);
            return;
        }
        status("页面正在更新，正在重新读取（" + readRecovery.attempts() + "/"
                + cn.local.tickethelper.core.ReadStability.MAX_ATTEMPTS + "），尚未判断有票或缺货。");
        store.appendEvent("READ_RECOVERY", "read_recovery_waiting", readRecovery.attempts());
        schedule(readRecovery.delayMs());
    }

    private boolean valid(long token, long pageToken) {
        return running && token == generation && pageToken == windowGeneration && unlocked() && TARGET.equals(foreground());
    }

    private void handle(Decision decision, long began, long token, long pageToken,
                        int nodeCount, int ocrCount, boolean usedOcr) {
        if (token != generation) return;
        if (!valid(token, pageToken)) { busy = false; schedule(700); return; }
        AiContextRepository.recordDecision(decision);
        store.appendObservation(decision.state.name(), decision.reason, SystemClock.elapsedRealtime() - began,
                nodeCount, ocrCount, usedOcr);
        if (transitionAction != Decision.Action.NONE && decision.state != Decision.State.CHECKOUT
                && decision.state != Decision.State.BLOCKED && decision.state != Decision.State.EXPIRED) {
            boolean stillPending = transitionAction == Decision.Action.CONFIRM_TIER
                    || (decision.state != Decision.State.TIER_SELECTED && decision.state != Decision.State.OUT_OF_STOCK);
            if (stillPending) {
                if (SystemClock.elapsedRealtime() - transitionAt >= 6000) {
                    stop("操作后的页面变化未能核实，已暂停，请手动检查。", "UNKNOWN",
                            "transition_unverified:" + decision.reason, true);
                } else { busy = false; schedule(700); }
                return;
            }
            transitionAction = Decision.Action.NONE;
        }
        switch (decision.state) {
            case BLOCKED:
                stop("checkout_queue_busy".equals(decision.reason)
                        ? "大麦提示排队拥挤，任务已暂停，尚未提交订单；请稍后在手机检查。"
                        : "检测到验证、登录或网络异常，请在手机处理后重新开始。",
                        "BLOCKED", decision.reason, true); return;
            case CONFIG_INVALID: case EXPIRED:
                stop("目标配置已失效或到达截止时间，任务停止。", decision.state.name(), decision.reason, true); return;
            case CHECKOUT:
                if (config.mode == MonitorConfig.Mode.LOCK_ORDER) {
                    handleOrderCheckout(token, pageToken); return;
                }
                stop("已检测到订单相关页面，请核对信息并手动继续；尚未确认生成订单。", "CHECKOUT", decision.reason, true); return;
            case UNRELATED:
                stop("当前页面不匹配，任务暂停。", "UNRELATED", decision.reason, true); return;
            case UNKNOWN:
                unknownCount++;
                status("页面状态待核实（" + unknownCount + "/3），不会据此判断缺货。");
                if (unknownCount >= 3) {
                    stop("连续 3 次无法确认目标页面或库存，请在手机检查后重新开始。", "UNKNOWN", decision.reason, true); return;
                }
                break;
            case IN_STOCK:
                unknownCount = 0;
                if (config.mode == MonitorConfig.Mode.ALERT || decision.action == Decision.Action.NONE) {
                    stop("发现目标票档的有票信号，请立即核实；尚未锁票或下单。", "IN_STOCK", decision.reason, true); return;
                }
                status("发现目标票档信号，正在核实选择。"); break;
            case PROBING:
                unknownCount = 0; status("目标场次已核实，正在试选票档；库存仍待核实。"); break;
            case TIER_SELECTED:
                unknownCount = 0; status("票档、数量及票款已核实，准备进入确认购买页。"); break;
            case OUT_OF_STOCK:
                unknownCount = 0; status("可见目标票档显示缺货，继续按设定间隔检查。"); break;
            default:
                unknownCount = 0; status("已识别目标页面，正在检查票档。"); break;
        }
        if (ActionPacing.mayDispatch(decision, System.currentTimeMillis(), SystemClock.elapsedRealtime(), nextActionAt, actionFailures > 0)) {
            act(decision, token, pageToken); return;
        }
        busy = false;
        schedule(Math.max(MonitorConfig.MIN_INTERVAL_MS, Math.min(config.minIntervalMs, nextActionAt - SystemClock.elapsedRealtime())));
    }

    private boolean orderGrantCurrent() {
        return orderAuthorization != null && orderConfigRevision == store.getConfigRevision()
                && orderAuthorization.validFor(store.loadConfig(), System.currentTimeMillis())
                && store.getViewerBindings().size() == config.quantity
                && orderAuthorization.viewerBindingHash.equals(LockAuthorization.viewerBindingsHash(store.getViewerBindings()));
    }

    private CheckoutObservation readOrderPage() {
        return readOrderPageResult().observation;
    }

    private CheckoutParseResult readOrderPageResult() {
        return OrderCheckoutAdapter.read(this, () -> windowGeneration,
                liveOrderAttempt == null ? "" : liveOrderAttempt.attemptId,
                store.getViewerBindings());
    }

    private void handleOrderCheckout(long token, long pageToken) {
        if (!valid(token, pageToken)) { busy = false; schedule(700); return; }
        if (!OrderCheckoutAdapter.isLiveVerified() || !orderGrantCurrent() || orderAttempts == null) {
            stop("自动下单适配或本次授权无法核实，已暂停。", "CHECKOUT", "order_adapter_or_grant_unverified", true); return;
        }
        try {
            CheckoutParseResult read = readOrderPageResult();
            store.appendEvent("ORDER_READ", read.reason, 0);
            OrderAttempt attempt = orderAttempts.read();
            if (attempt == null && read.viewerResult != null
                    && read.viewerResult.state == ViewerMatcher.State.SELECT_PROPOSAL) {
                if (viewerSelectionTargets.containsKey(viewerTargetKey(read))) {
                    if (SystemClock.elapsedRealtime() - viewerSelectionTargets.get(viewerTargetKey(read)) < 6000) {
                        busy = false; schedule(700); return;
                    }
                    stop("观演人点击后的选中状态无法确认，已暂停；尚未提交订单。",
                            "CHECKOUT", "viewer_selection_not_verified", true); return;
                }
                if (viewerSelectionTargets.size() >= config.quantity) {
                    stop("观演人选中状态仍无法核实，已暂停；尚未提交订单。",
                            "CHECKOUT", "viewer_selection_limit", true); return;
                }
                if (orderEngine.canSelectViewer(config, orderAuthorization, read.observation, read.viewerResult, attempt,
                        System.currentTimeMillis())) {
                    selectViewerOnce(read, token, pageToken); return;
                }
            }
            CheckoutDecision decision = orderEngine.evaluate(config, orderAuthorization, read.observation,
                    attempt, System.currentTimeMillis());
            store.appendEvent("ORDER_STATE", decision.reason, 0);
            switch (decision.state) {
                case READY_TO_RESERVE:
                    submitOrderOnce(decision.proposal, token, pageToken); return;
                case WAIT_RESULT:
                    busy = false; status("已发出一次订单提交，正在核实大麦结果；不会重复提交。"); schedule(700); return;
                case CONFIRMED_PENDING:
                    if (liveOrderAttempt == null || decision.pendingOrder == null
                            || !"official_pending_order_verified".equals(decision.reason)) {
                        stop("存在历史订单提交记录，请到大麦核查当前订单状态。", "ORDER_REVIEW", "historical_order_barrier", true); return;
                    }
                    orderAttempts.markPending(liveOrderAttempt.attemptId, decision.pendingOrder);
                    String deadline = decision.pendingOrder.paymentDeadlineText.isEmpty()
                            ? "付款期限请以大麦订单页为准。" : "付款期限：" + decision.pendingOrder.paymentDeadlineText + "。";
                    stop("大麦已确认待付款订单，请在当前订单页或大麦“我的订单”付款。" + deadline,
                            "ORDER_PENDING_PAYMENT", "official_pending_order_verified", true); return;
                case MATCHING_PENDING_REVIEW:
                    stop("大麦显示匹配的待付款订单，但尚未证实是本次创建，请到“我的订单”核查状态和期限；不会再次下单。",
                            "ORDER_REVIEW", decision.reason, true); return;
                case INVENTORY_REJECTED:
                    if (liveOrderAttempt != null) orderAttempts.markInventoryRejected(liveOrderAttempt.attemptId);
                    stop("大麦返回库存不足；本次未确认锁票，自动提交已停止。", "ORDER_REJECTED", decision.reason, true); return;
                case OUTCOME_UNKNOWN:
                    stop("订单提交结果无法确认，请立即到大麦“我的订单”核查；不会重复下单。",
                            "ORDER_UNKNOWN", decision.reason, true); return;
                default:
                    stop("确认购买页的场次、观演人或金额未能完整核实，已暂停，尚未提交订单。",
                            "CHECKOUT", decision.reason, true);
            }
        } catch (IOException | RuntimeException error) {
            stop("订单记录或页面核验失败，已暂停；请在大麦核查订单。", "ORDER_UNKNOWN", "order_state_unavailable", true);
        }
    }

    /** Select one uniquely matched, currently unchecked attendee; always reread before submitting. */
    private void selectViewerOnce(CheckoutParseResult read, long token, long pageToken) {
        if (viewerSelectionTargets.size() >= config.quantity || viewerSelectionTargets.containsKey(viewerTargetKey(read))
                || !viewerReadFresh(read, token, pageToken)) {
            stop("观演人页面已变化，已暂停，尚未提交订单。", "CHECKOUT", "viewer_page_changed", true); return;
        }
        viewerSelectionTargets.put(viewerTargetKey(read), SystemClock.elapsedRealtime());
        UiItem target = read.viewerResult.selectionTarget;
        store.appendEvent("VIEWER_SELECTION_REQUEST", "single_native_checkbox", 0);
        if (AppStore.INPUT_SHIZUKU.equals(inputMode)) {
            long age = System.currentTimeMillis() - read.observation.observedAtMs;
            long deadline = SystemClock.elapsedRealtime() + Math.min(2000, Math.max(0, 3000 - age));
            inputBridge.tapOrder(target.centerX(), target.centerY(), deadline,
                    () -> viewerDispatchNode(read, token, pageToken) != null, result -> {
                        if (!running || token != generation) return;
                        if (!result.success) {
                            stop("观演人点击结果无法确认，请在大麦核查选择；尚未提交订单。",
                                    "CHECKOUT", "viewer_input_unconfirmed", true); return;
                        }
                        busy = false; schedule(700);
                    });
        } else {
            AccessibilityNodeInfo node = viewerDispatchNode(read, token, pageToken);
            boolean sent = node != null && viewerReadFresh(read, token, pageToken)
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!sent) {
                stop("观演人点击结果无法确认，请在大麦核查选择；尚未提交订单。",
                        "CHECKOUT", "viewer_input_unconfirmed", true); return;
            }
            busy = false; schedule(700);
        }
    }

    private boolean viewerReadFresh(CheckoutParseResult read, long token, long pageToken) {
        if (read == null || read.viewerResult == null || read.viewerResult.selectionTarget == null
                || !read.viewerResult.selectionIdentityHash.matches("[0-9a-f]{64}")
                || !valid(token, pageToken) || !orderGrantCurrent()) return false;
        long age = System.currentTimeMillis() - read.observation.observedAtMs;
        return age >= 0 && age < 3000;
    }

    private AccessibilityNodeInfo viewerDispatchNode(CheckoutParseResult initial, long token, long pageToken) {
        if (!OrderCheckoutAdapter.isLiveVerified() || !viewerReadFresh(initial, token, pageToken)
                || orderAttempts == null || liveOrderAttempt != null) return null;
        try {
            OrderAttempt attempt = orderAttempts.read();
            CheckoutParseResult current = readOrderPageResult();
            if (current.viewerResult == null || current.observation.windowId != initial.observation.windowId
                    || current.observation.frameGeneration != initial.observation.frameGeneration
                    || !orderEngine.canSelectViewer(config, orderAuthorization, current.observation, current.viewerResult,
                            attempt, System.currentTimeMillis())
                    || !current.viewerResult.bindingHash.equals(initial.viewerResult.bindingHash)
                    || !current.viewerResult.selectionIdentityHash.equals(initial.viewerResult.selectionIdentityHash)
                    || !sameNativeControl(initial.viewerResult.selectionTarget, current.viewerResult.selectionTarget)
                    || !viewerReadFresh(initial, token, pageToken)) return null;
            long deadline = SystemClock.elapsedRealtime() + 80;
            AccessibilityNodeInfo node = CheckoutNativeTarget.find(this, current.viewerResult.selectionTarget,
                    current.observation.windowId, deadline,
                    candidate -> OrderCheckoutAdapter.viewerControlMatches(this, candidate,
                            proposedViewer(current.viewerResult.selectionIdentityHash), deadline));
            return node != null && viewerReadFresh(initial, token, pageToken) ? node : null;
        } catch (IOException | RuntimeException unavailable) { return null; }
    }

    private static boolean sameNativeControl(UiItem a, UiItem b) {
        return a != null && b != null && a.source == UiItem.Source.NODE && b.source == UiItem.Source.NODE
                && a.resourceId.equals(b.resourceId) && a.text.equals(b.text)
                && a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }

    private static String viewerTargetKey(CheckoutParseResult read) {
        return read == null || read.viewerResult == null ? "" : read.viewerResult.selectionIdentityHash;
    }

    private java.util.List<ViewerMatcher.RequestedViewer> proposedViewer(String identityHash) {
        if (identityHash == null || !identityHash.matches("[0-9a-f]{64}")) return java.util.List.of();
        java.util.List<ViewerMatcher.RequestedViewer> matches = store.getViewerBindings().stream().filter(viewer ->
                identityHash.equals(LockAuthorization.viewerBindingHash(viewer.displayName, viewer.documentSuffix)))
                .collect(java.util.stream.Collectors.toList());
        return matches.size() == 1 ? matches : java.util.List.of();
    }

    /** Exactly one submit input, isolated from SKU retries and gesture fallbacks. */
    private void submitOrderOnce(VerifiedSubmitProposal proposal, long token, long pageToken) throws IOException {
        if (proposal == null || !valid(token, pageToken) || !orderGrantCurrent()) {
            stop("提交前页面或授权已变化，已暂停。", "ORDER_REVIEW", "pre_reserve_changed", true); return;
        }
        OrderAttemptStore.SubmitPermit permit = orderAttempts.tryReserve(proposal);
        if (permit == null) {
            stop("已有订单提交记录，请到大麦核查；不会再次提交。", "ORDER_REVIEW", "durable_order_barrier", true); return;
        }
        liveOrderAttempt = permit.attempt;
        // Both reservation and consumed state must be durably written before any input is sent.
        if (!permit.consume()) {
            stop("本次提交许可无法核实，已暂停。", "ORDER_UNKNOWN", "order_permit_not_consumed", true); return;
        }
        liveOrderAttempt = orderAttempts.read();
        if (!orderDispatchAllowed(proposal, token, pageToken)) {
            stop("提交前最终核验未通过，已暂停，请手动核查。", "ORDER_UNKNOWN", "order_final_guard_failed", true); return;
        }
        UiItem target = proposal.submitTarget;
        store.appendEvent("ORDER_SUBMIT_REQUEST", "single_native_submit", 0);
        if (AppStore.INPUT_SHIZUKU.equals(inputMode)) {
            long age = System.currentTimeMillis() - proposal.observedAtMs;
            long dispatchDeadline = SystemClock.elapsedRealtime() + Math.min(2000, Math.max(0, 3000 - age));
            inputBridge.tapOrder(target.centerX(), target.centerY(), dispatchDeadline,
                    () -> orderDispatchAllowed(proposal, token, pageToken), result -> {
                        if (!running || token != generation) return;
                        store.appendEvent("ORDER_INPUT_RESULT", result.code, 0);
                        if (!result.success) {
                            stop("订单点击结果无法确认，请到大麦“我的订单”核查；不会重试。",
                                    "ORDER_UNKNOWN", "order_input_unconfirmed", true); return;
                        }
                        busy = false; schedule(700);
                    });
        } else {
            // A false return is not permission to retry. Read back the official result only.
            AccessibilityNodeInfo node = orderDispatchNode(proposal, token, pageToken);
            boolean sent = node != null && orderInputFresh(proposal, token, pageToken)
                    && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            store.appendEvent("ORDER_INPUT_RESULT", sent ? "native_dispatched" : "native_unconfirmed", 0);
            if (!sent) {
                stop("订单点击结果无法确认，请到大麦“我的订单”核查；不会重试。",
                        "ORDER_UNKNOWN", "order_input_unconfirmed", true); return;
            }
            busy = false; schedule(700);
        }
    }

    private boolean orderDispatchAllowed(VerifiedSubmitProposal proposal, long token, long pageToken) {
        return orderDispatchNode(proposal, token, pageToken) != null;
    }

    private boolean orderInputFresh(VerifiedSubmitProposal proposal, long token, long pageToken) {
        if (!valid(token, pageToken) || !orderGrantCurrent()) return false;
        long age = System.currentTimeMillis() - proposal.observedAtMs;
        return age >= 0 && age < 3000;
    }

    private AccessibilityNodeInfo orderDispatchNode(VerifiedSubmitProposal proposal, long token, long pageToken) {
        if (!OrderCheckoutAdapter.isLiveVerified() || !valid(token, pageToken) || !orderGrantCurrent()
                || liveOrderAttempt == null || orderAttempts == null || !orderInputFresh(proposal, token, pageToken)) return null;
        if (!orderEngine.revalidateForDispatch(config, orderAuthorization, readOrderPage(), proposal,
                liveOrderAttempt, System.currentTimeMillis())) return null;
        if (!orderInputFresh(proposal, token, pageToken)) return null;
        AccessibilityNodeInfo node = OrderCheckoutAdapter.findSubmitTarget(this, proposal, () -> windowGeneration,
                SystemClock.elapsedRealtime() + 80);
        return node != null && orderInputFresh(proposal, token, pageToken) ? node : null;
    }

    private void act(Decision decision, long token, long pageToken) {
        if (token != generation) return;
        if (!valid(token, pageToken)) { busy = false; schedule(700); return; }
        // A short observation lifetime limits clicks against a page which has since changed.
        if (decision.observedAtMs <= 0 || System.currentTimeMillis() - decision.observedAtMs > 3000) {
            busy = false; schedule(700); return;
        }
        if (decision.action == Decision.Action.BACK) {
            actionDone(decision, performGlobalAction(GLOBAL_ACTION_BACK), token); return;
        }
        UiItem target = decision.target;
        if (target == null || !target.hasBounds()) { actionDone(decision, false, token); return; }
        store.appendEvent("ACTION_REQUEST", decision.action.name().toLowerCase(Locale.ROOT)
                + ":" + target.source.name().toLowerCase(Locale.ROOT) + ":"
                + target.left + "," + target.top + "," + target.right + "," + target.bottom, 0);
        if (target.source == UiItem.Source.NODE) {
            AccessibilityNodeInfo found = findTarget(getRootInActiveWindow(), target, 0);
            if (AppStore.INPUT_SHIZUKU.equals(inputMode)) {
                dispatchVerifiedLocalTap(decision, found, token, pageToken); return;
            }
            if (found != null && "cn.damai:id/btn_buy_view".equals(target.resourceId)
                    && decision.action == Decision.Action.CONFIRM_TIER) {
                dispatchVerifiedGesture(decision, found, "verified_confirm_gesture", token, pageToken);
                return;
            }
            store.appendEvent("ACTION_ROUTE", found == null ? "native_target_missing" : "native_node_click", 0);
            boolean success = found != null && found.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            actionDone(decision, success, token); return;
        }
        // The SKU adapter keeps OCR semantics separate from node identity. Its exact cell bounds
        // can still resolve to the current real container and that container's clickable parent.
        AccessibilityNodeInfo skuCell = findSkuCell(getRootInActiveWindow(), target, 0);
        if (skuCell != null) {
            if (AppStore.INPUT_SHIZUKU.equals(inputMode)) {
                dispatchVerifiedLocalTap(decision, skuCell, token, pageToken); return;
            }
            // A clickable container can report ACTION_CLICK success without invoking its touch
            // listener. Use one ordinary touch on the freshly matched visible SKU, then verify.
            dispatchVerifiedGesture(decision, skuCell, "verified_sku_gesture", token, pageToken);
            return;
        }
        if (decision.action == Decision.Action.PROBE_TIER || decision.action == Decision.Action.SELECT_TIER) {
            stop("票档与当前页面控件不匹配，已暂停，请检查页面适配。", "UNKNOWN", "sku_target_changed", true);
            return;
        }
        if (AppStore.INPUT_SHIZUKU.equals(inputMode)) {
            stop("当前操作缺少可核实的页面控件，已暂停。", "UNKNOWN", "local_input_target_missing", true); return;
        }
        dispatchVerifiedGesture(decision, null, "ocr_gesture", token, pageToken);
    }

    private void dispatchVerifiedLocalTap(Decision decision, AccessibilityNodeInfo node,
                                         long token, long pageToken) {
        if (!valid(token, pageToken)) { busy = false; schedule(700); return; }
        // This backend executes only the existing bounded proposals. It adds no order/payment action.
        if (decision.action != Decision.Action.PROBE_TIER && decision.action != Decision.Action.SELECT_TIER
                && decision.action != Decision.Action.CONFIRM_TIER && decision.action != Decision.Action.OPEN_TICKETS) {
            stop("当前动作不支持本地调试输入，已暂停。", "BLOCKED", "local_input_action_rejected", true); return;
        }
        if (node == null || !node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) {
            stop("目标控件已变化，已暂停。", "UNKNOWN", "local_input_target_stale", true); return;
        }
        AccessibilityNodeInfo ancestor = node;
        for (int depth = 0; ancestor != null && depth < 50; depth++, ancestor = ancestor.getParent()) {
            if (Build.VERSION.SDK_INT >= 34 && ancestor.isAccessibilityDataSensitive()) {
                stop("目标控件标记为受保护，已暂停，请手动操作。", "BLOCKED", "sensitive_target", true); return;
            }
        }
        android.view.accessibility.AccessibilityWindowInfo window = node.getWindow();
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if (window == null || !window.isActive() || window.getDisplayId() != android.view.Display.DEFAULT_DISPLAY
                || !bounds.contains(decision.target.centerX(), decision.target.centerY())) {
            stop("目标窗口无法核实，已暂停。", "UNKNOWN", "local_input_window_changed", true); return;
        }
        if (!inputBridge.isReady()) {
            stop("手机本地调试输入未就绪，已暂停。", "BLOCKED", "local_input_unavailable", true); return;
        }
        if (!valid(token, pageToken) || System.currentTimeMillis() - decision.observedAtMs > 3000) {
            busy = false; schedule(700); return;
        }
        store.appendEvent("ACTION_ROUTE", "local_debug_tap:display=0", 0);
        long observedAge = System.currentTimeMillis() - decision.observedAtMs;
        if (observedAge < 0 || observedAge >= 3000) { busy = false; schedule(700); return; }
        long dispatchDeadline = SystemClock.elapsedRealtime() + Math.min(2000, 3000 - observedAge);
        int expectedWindowId = node.getWindowId();
        inputBridge.tap(decision.target.centerX(), decision.target.centerY(),
                dispatchDeadline, () -> {
                    // Revalidate on our main thread after the remote system checks, immediately before input.
                    long age = System.currentTimeMillis() - decision.observedAtMs;
                    if (!valid(token, pageToken) || age < 0 || age > 3000 || !node.refresh()
                            || !node.isVisibleToUser() || !node.isEnabled() || node.getWindowId() != expectedWindowId)
                        return false;
                    Rect currentBounds = new Rect(); node.getBoundsInScreen(currentBounds);
                    android.view.accessibility.AccessibilityWindowInfo currentWindow = node.getWindow();
                    if (!bounds.equals(currentBounds) || currentWindow == null || !currentWindow.isActive()
                            || currentWindow.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) return false;
                    AccessibilityNodeInfo current = node;
                    for (int depth = 0; current != null && depth < 50; depth++, current = current.getParent()) {
                        if (Build.VERSION.SDK_INT >= 34 && current.isAccessibilityDataSensitive()) return false;
                    }
                    return current == null;
                }, result -> {
                    if (!running || token != generation) return;
                    store.appendEvent("LOCAL_INPUT_RESULT", result.code, 0);
                    if (!result.success) {
                        stop("本地输入未能确认完成，已暂停，请检查手机；不会重试该点击。",
                                result.outcomeUnknown ? "UNKNOWN" : "BLOCKED", "local_input_" + result.code, true);
                        return;
                    }
                    // A successful shell exit only confirms dispatch; the next observation must verify UI effect.
                    actionDone(decision, true, token);
                });
    }

    private void dispatchVerifiedGesture(Decision decision, AccessibilityNodeInfo node, String route,
                                         long token, long pageToken) {
        if (!valid(token, pageToken)) { busy = false; schedule(700); return; }
        int displayId = android.view.Display.DEFAULT_DISPLAY;
        if (node != null) {
            if (!node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) {
                actionDone(decision, false, token); return;
            }
            if (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive()) {
                stop("目标控件标记为受保护，已暂停，请手动操作。", "BLOCKED", "sensitive_target", true); return;
            }
            android.view.accessibility.AccessibilityWindowInfo window = node.getWindow();
            if (window == null || !window.isActive()) { actionDone(decision, false, token); return; }
            displayId = window.getDisplayId();
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            if (!bounds.contains(decision.target.centerX(), decision.target.centerY())) {
                actionDone(decision, false, token); return;
            }
        }
        if (!valid(token, pageToken) || System.currentTimeMillis() - decision.observedAtMs > 3000) {
            busy = false; schedule(700); return;
        }
        store.appendEvent("ACTION_ROUTE", route + ":display=" + displayId, 0);
        UiItem target = decision.target;
        // OCR can only tap a currently visible bounded label in the verified target window.
        Path path = new Path(); path.moveTo(target.centerX(), target.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .setDisplayId(displayId)
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70)).build();
        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                if (!running || token != generation) return;
                store.appendEvent("GESTURE_CALLBACK", "completed", 0);
                actionDone(decision, true, token);
            }
            @Override public void onCancelled(GestureDescription description) {
                if (!running || token != generation) return;
                store.appendEvent("GESTURE_CALLBACK", "cancelled", 0);
                actionDone(decision, false, token);
            }
        }, handler);
        store.appendEvent("GESTURE_DISPATCH", dispatched ? "accepted" : "rejected", 0);
        if (!dispatched) actionDone(decision, false, token);
    }

    private void actionDone(Decision decision, boolean success, long token) {
        if (!running || token != generation) return;
        busy = false;
        if (success) {
            actionFailures = 0;
            engine.recordAction(decision, System.currentTimeMillis());
            store.appendEvent("ACTION", decision.action.name().toLowerCase(Locale.ROOT) + "_dispatched", 0);
            if (decision.action == Decision.Action.CONFIRM_TIER || decision.action == Decision.Action.SELECT_TIER
                    || decision.action == Decision.Action.PROBE_TIER) {
                transitionAction = decision.action; transitionAt = SystemClock.elapsedRealtime();
            }
            if (decision.action == Decision.Action.BACK || decision.action == Decision.Action.OPEN_TICKETS)
                lastNavigationAt = System.currentTimeMillis();
        } else {
            actionFailures++; store.appendEvent("ACTION_FAILED", "ui_action_not_dispatched", 0);
            if (actionFailures >= 2) {
                stop("界面操作连续失败，任务已暂停，请检查手机。", "BLOCKED", "ui_action_failed", true); return;
            }
        }
        long delay = ThreadLocalRandom.current().nextLong(config.minIntervalMs, config.maxIntervalMs + 1);
        nextActionAt = SystemClock.elapsedRealtime() + delay;
        // Observe the result promptly without dispatching another action until its interval is due.
        schedule(800);
    }

    private List<UiItem> readNodes() {
        List<UiItem> result = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName()))
            collect(root, result, 0);
        return result;
    }

    private void collect(AccessibilityNodeInfo node, List<UiItem> items, int depth) {
        if (node == null || depth > 50 || items.size() >= 500) return;
        if (node.isPassword() || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) return;
        if (node.isVisibleToUser()) {
            String text = node.getText() == null ? "" : node.getText().toString().trim();
            if (text.isEmpty() && node.getContentDescription() != null) text = node.getContentDescription().toString().trim();
            Rect rect = new Rect(); node.getBoundsInScreen(rect);
            AccessibilityNodeInfo container = clickContainer(node, rect);
            boolean selected = node.isSelected() || node.isChecked()
                    || (container != null && (container.isSelected() || container.isChecked()));
            String resourceId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
            if (!text.isEmpty() || evidenceContainer(resourceId)) items.add(new UiItem(text, rect.left, rect.top, rect.right, rect.bottom,
                    node.isEnabled(), container != null, selected, UiItem.Source.NODE, resourceId));
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), items, depth + 1);
    }

    private static boolean evidenceContainer(String id) {
        return id.equals("cn.damai:id/ll_perform_item")
                // Reservation SKU separates performance identity and sale-time text
                // into empty native containers; their bounds are needed for scoped OCR.
                || id.equals("cn.damai:id/item_flowlayout")
                || id.equals("cn.damai:id/layout_time")
                || id.equals("cn.damai:id/error_layout")
                || id.equals("cn.damai:id/project_detail_perform_flowlayout")
                || id.equals("cn.damai:id/project_detail_perform_price_flowlayout")
                || id.equals("cn.damai:id/bottom_layout")
                || id.equals("cn.damai:id/layout_num")
                || id.equals("cn.damai:id/btn_buy_view")
                || id.equals("cn.damai:id/contentLayout")
                || id.equals("cn.damai:id/tour_project_container")
                || id.equals("cn.damai:id/tour_recyclerView")
                || id.equals("cn.damai:id/ll_city_tour")
                || id.equals("cn.damai:id/ll_search_item")
                || id.equals("cn.damai:id/search_v2_container")
                || id.equals("cn.damai:id/search_v2_word_suggest_layout")
                || id.equals("cn.damai:id/search_v2_suggest_recycler")
                || id.equals("cn.damai:id/pioneer_homepage_header_search_btn")
                || id.equals("cn.damai:id/homepage_header_search_layout");
    }

    private AccessibilityNodeInfo findTarget(AccessibilityNodeInfo node, UiItem target, int depth) {
        if (node == null || depth > 50) return null;
        if (node.isPassword() || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive())) return null;
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        if (text.isEmpty() && node.getContentDescription() != null) text = node.getContentDescription().toString().trim();
        Rect r = new Rect(); node.getBoundsInScreen(r);
        boolean identityMatches = target.resourceId.isEmpty() ? text.equals(target.text)
                : target.resourceId.equals(node.getViewIdResourceName()) && text.equals(target.text);
        if (identityMatches && Math.abs(r.centerX() - target.centerX()) <= 4 && Math.abs(r.centerY() - target.centerY()) <= 4
                && node.isVisibleToUser() && node.isEnabled()) return clickContainer(node, r);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = findTarget(node.getChild(i), target, depth + 1);
            if (child != null) return child;
        }
        return null;
    }

    private AccessibilityNodeInfo findSkuCell(AccessibilityNodeInfo node, UiItem target, int depth) {
        if (node == null || depth > 50) return null;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        if ("cn.damai:id/ll_perform_item".equals(node.getViewIdResourceName())
                && bounds.left == target.left && bounds.top == target.top
                && bounds.right == target.right && bounds.bottom == target.bottom
                && node.isVisibleToUser() && node.isEnabled()) return clickContainer(node, bounds);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findSkuCell(node.getChild(i), target, depth + 1);
            if (match != null) return match;
        }
        return null;
    }

    /** Text often sits inside a clickable tile; never climb into a screen-sized container. */
    private AccessibilityNodeInfo clickContainer(AccessibilityNodeInfo node, Rect label) {
        AccessibilityNodeInfo current = node;
        for (int level = 0; level < 3 && current != null; level++, current = current.getParent()) {
            Rect bounds = new Rect(); current.getBoundsInScreen(bounds);
            if (!bounds.contains(label) || bounds.width() > Math.max(label.width() * 3, 80)
                    || bounds.height() > Math.max(label.height() * 4, 80)) return null;
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) return current;
        }
        return null;
    }

    private void status(String message) {
        store.setStatus(message);
        if (message.equals(lastStatus)) return;
        lastStatus = message;
        Notification.Builder notification = baseNotification(ACTIVE, message).setOngoing(true).setOnlyAlertOnce(true);
        Intent stop = new Intent(this, StopReceiver.class);
        PendingIntent stopIntent = PendingIntent.getBroadcast(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        notification.addAction(new Notification.Action.Builder(null, "暂停", stopIntent).build());
        getSystemService(NotificationManager.class).notify(10, notification.build());
    }
    private Notification.Builder baseNotification(String channel, String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_ticket).setContentTitle("票务助手")
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true);
    }
    private void notifyUser(String message) {
        getSystemService(NotificationManager.class).notify(11, baseNotification(ALERT, message).build());
    }

    @Override public void onInterrupt() {
        selectionStartGeneration++;
        closeInPlaceCatalog();
        // Further system interruption callbacks must not repeatedly alert an already paused task.
        if (running) stop("无障碍服务已中断，请重新检查权限。", "BLOCKED", "service_interrupted", true);
        if (catalog != null) catalog.cancelInternal(true);
    }

    private void releaseConnectionResources() {
        connectionReady = false;
        selectionStartGeneration++;
        closeInPlaceCatalog();
        CatalogController previous = catalog; catalog = null;
        if (previous != null) {
            try { previous.close(); } catch (RuntimeException ignored) { /* Continue releasing remaining readers. */ }
        }
        if (screenReceiverRegistered) {
            try { unregisterReceiver(stopReceiver); } catch (IllegalArgumentException ignored) { }
            screenReceiverRegistered = false;
        }
        if (ocr != null) {
            try { ocr.close(); } catch (RuntimeException ignored) { }
            ocr = null;
        }
    }

    @Override public void onDestroy() {
        connectionReady = false;
        cancelAiPageRead();
        pause("服务已断开，任务停止；重新连接后需手动开始。");
        releaseConnectionResources();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public boolean onUnbind(Intent intent) {
        connectionReady = false;
        if (instance == this) instance = null;
        cancelAiPageRead();
        pause("无障碍连接已中断，任务已暂停；重新连接后需主动开始。");
        releaseConnectionResources();
        return super.onUnbind(intent);
    }
}
