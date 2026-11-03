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
import cn.local.tickethelper.core.DetailOcrNavigation;
import cn.local.tickethelper.core.DetailScrollPolicy;
import cn.local.tickethelper.core.InputDispatchPolicy;
import cn.local.tickethelper.core.InputDispatchPolicy.Rejection;
import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.Observation;
import cn.local.tickethelper.core.QuantityTransition;
import cn.local.tickethelper.core.SaleTiming;
import cn.local.tickethelper.core.DetailNavigationRead;
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
    private boolean running, busy, targetSeen, screenReceiverRegistered, confirmationOnly;
    private Decision semanticDetailDecision;
    private long semanticDetailBoundary;
    private int semanticDetailWindow;
    private long generation, windowGeneration, startedAt, lastNavigationAt, nextActionAt, lastCaptureAt, scheduledAt;
    private long lastSessionCompletedElapsed;
    private long lastWindowChangeElapsed;
    private String lastWindowEventSummary = "none";
    private long transitionAt;
    private Decision.Action transitionAction = Decision.Action.NONE;
    private final QuantityTransition quantityTransition = new QuantityTransition();
    private Runnable quantityTimeout;
    private SaleTiming saleTiming;
    private SaleTiming.Phase salePhase;
    private boolean openingWakePending, openingInputPending;
    private OpeningCountdownOverlay openingCountdown;
    private boolean reservationExitAttempted;
    private long detailSettleReadAfter;
    private DetailScrollPolicy.FrameCheck lastDetailScrollFrameCheck;
    private String lastStatus = "";
    private int unknownCount, actionFailures;
    private final cn.local.tickethelper.core.ReadStability readRecovery = new cn.local.tickethelper.core.ReadStability();
    // Reset after a dispatched action, never merely because another OCR frame was accepted.
    private final cn.local.tickethelper.core.ReadStability inputReadRecovery = new cn.local.tickethelper.core.ReadStability();
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
        engine.reset(); transitionAction = Decision.Action.NONE; clearQuantityTransition();
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
        clearConfirmationTest();
        cancelAiPageRead();
        armInternal(null);
    }

    public void armForOrder(LockAuthorization authorization) {
        clearConfirmationTest();
        cancelAiPageRead();
        armInternal(authorization);
    }

    private boolean confirmationTest;
    private java.util.List<ViewerMatcher.RequestedViewer> confirmationTestViewers = java.util.List.of();
    private MonitorConfig confirmationTestConfig;

    /** Only a same-UID debug receiver may request this one fixed, freshly approved live acceptance case.
     * It never changes the saved user's plan and never enables the production automatic-order choice.
     * All normal durable reservation, amount, attendee, live rebinding and one-input gates remain.
     */
    public void armConfirmationTest(LockAuthorization authorization, java.util.List<ViewerMatcher.RequestedViewer> viewers) {
        if (running || !connectionReady || authorization == null || LockAuthorization.viewerBindingsHash(viewers).isEmpty()
                || (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        MonitorConfig c = authorization.config;
        if (c == null || !c.eventKeyword.contains("庾澄庆") || !c.city.equals("杭州") || !c.date.equals("2026-12-19")
                || !c.time.equals("19:00") || !c.prices.equals(java.util.List.of(980)) || c.quantity != 2 || c.maxTotal != 1960
                || c.mode != MonitorConfig.Mode.LOCK_ORDER || viewers.size() != 2
                || !authorization.validFor(c, System.currentTimeMillis())
                || !authorization.viewerBindingHash.equals(LockAuthorization.viewerBindingsHash(viewers))) return;
        confirmationTest = true; confirmationTestConfig = c; confirmationTestViewers = java.util.List.copyOf(viewers);
        cancelAiPageRead(); armInternal(authorization);
    }

    private void clearConfirmationTest() {
        confirmationTest = false; confirmationTestConfig = null; confirmationTestViewers = java.util.List.of();
    }

    private void armInternal(LockAuthorization authorization) {
        if (running) return;
        if (!connectionReady) return;
        if (new ComputerLinkStore(this).remaining() > 0) {
            pause("电脑协作中。请先在电脑页结束协作，再从手机开始监控。"); return;
        }
        closeInPlaceCatalog();
        if (catalog != null) catalog.cancelInternal(false);
        config = confirmationTest ? confirmationTestConfig : store.loadConfig();
        orderAuthorization = null; liveOrderAttempt = null; orderAttempts = null;
        if (!confirmationTest && store.isImportedTargetPendingReview()) {
            pause("换机目标尚未核对，请在助手重新选择演出后开始。"); return;
        }
        confirmationOnly = false;
        if (config.mode == MonitorConfig.Mode.LOCK_ORDER) {
            boolean live = OrderCheckoutAdapter.isLiveVerified();
            boolean confOnly = OrderCheckoutAdapter.isConfirmationOnlyVerified();
            if (!confirmationTest && !live && !confOnly) {
                pause("自动下单的确认页适配尚未真机验收，请先使用到确认页暂停模式。"); return;
            }
            confirmationOnly = !live && confOnly;
            if (!confirmationTest && !OrderCheckoutAdapter.supportsInstalledVersion(this)) {
                pause("当前大麦版本尚未适配，请使用到确认页暂停模式。"); return;
            }
            if (authorization == null || !authorization.validFor(config, System.currentTimeMillis())
                    || (confirmationTest ? confirmationTestViewers : store.getViewerBindings()).size() != config.quantity
                    || !authorization.viewerBindingHash.equals(LockAuthorization.viewerBindingsHash(
                            confirmationTest ? confirmationTestViewers : store.getViewerBindings()))) {
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
        SaleTiming preparedTiming = new SaleTiming(config, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        SaleTiming.Tick initialTiming = preparedTiming.tick(System.currentTimeMillis(), SystemClock.elapsedRealtime());
        if (initialTiming.phase == SaleTiming.Phase.STOPPED) { pause(initialTiming.statusText()); return; }
        if (ocr.isBusy()) { ocr.close(); ocr = new OcrReader(this); }
        generation++; engine.reset(); engine.beginOpeningStrategy(config,System.currentTimeMillis()); engine.allowInitialDetailNavigation(config); busy = false; running = true; targetSeen = false;
        startedAt = SystemClock.elapsedRealtime(); lastNavigationAt = 0; nextActionAt = 0; lastSessionCompletedElapsed = 0;
        saleTiming = preparedTiming; salePhase = null; reservationExitAttempted = false;
        openingWakePending = initialTiming.phase == SaleTiming.Phase.WAITING_FOR_SALE;
        openingInputPending = openingWakePending;
        lastCaptureAt = 0; detailSettleReadAfter = 0; lastDetailScrollFrameCheck = null;
        unknownCount = 0; actionFailures = 0; readRecovery.reset(); inputReadRecovery.reset();
        lastStatus = ""; transitionAction = Decision.Action.NONE; clearQuantityTransition(); scheduledAt = 0;
        getSystemService(NotificationManager.class).cancel(11);
        getSystemService(NotificationManager.class).cancel(12);
        store.setArmed(true); store.appendEvent("ARMED", "user_started", 0);
        status(confirmationTest ? "正在验证一笔已明确批准的杭州测试订单；只提交一次，不付款。"
                : "已启动；请在 20 秒内打开大麦目标演出页。");
        schedule(0);
    }

    public void pause(String reason) { stop(reason, "PAUSED", "user_paused", false); }

    private void stop(String message, String state, String reason, boolean alert) {
        selectionStartGeneration++;
        boolean wasRunning = running;
        cancelAiPageRead();
        if (openingCountdown != null) { openingCountdown.close(); openingCountdown = null; }
        running = false; busy = false; generation++; engine.reset(); handler.removeCallbacks(tick);
        clearQuantityTransition(); transitionAction = Decision.Action.NONE;
        saleTiming = null; salePhase = null;
        detailSettleReadAfter = 0;
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
        clearConfirmationTest();
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
        // Native events wake local observation; they are not server requests or action authority.
        if (!busy) {
            SaleTiming.Tick timing = timingTick();
            schedule(timing != null && timing.phase == SaleTiming.Phase.WAITING_FOR_SALE
                    ? timing.nextWakeMs : SaleTiming.eventReadDelayMs());
        }
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
        SaleTiming.Tick timing = timingTick();
        if (timing == null || timing.phase == SaleTiming.Phase.STOPPED) {
            stop(timing == null ? "监控计时无法核实，任务已暂停。" : timing.statusText(),
                    timing != null && timing.stopReason == SaleTiming.StopReason.DEADLINE_REACHED ? "EXPIRED" : "CONFIG_INVALID",
                    timing != null && timing.stopReason == SaleTiming.StopReason.DEADLINE_REACHED ? "deadline_reached" : "invalid_configuration", true); return;
        }
        if (timing.phase != salePhase) {
            salePhase = timing.phase;
            store.appendEvent("SALE_TIMING", timing.phase.name().toLowerCase(Locale.ROOT), 0);
        }
        if (!timing.mayReadOfficialPage()) {
            if (openingCountdown == null) openingCountdown = new OpeningCountdownOverlay(this);
            openingCountdown.update(config.saleAt, timing.untilSaleMs);
            if (!timing.statusText().equals(lastStatus)) status(timing.statusText());
            schedule(timing.nextWakeMs); return;
        }
        if (openingCountdown != null) { openingCountdown.close(); openingCountdown = null; }
        if (openingWakePending && config.saleAt != null) {
            openingWakePending = false;
            store.appendEvent("OPENING_WAKE", "local_clock_lateness", Math.max(0, System.currentTimeMillis()-config.saleAt.toEpochMilli()));
        }
        long settleRemaining = detailSettleReadAfter - SystemClock.elapsedRealtime();
        if (settleRemaining > 0) { schedule(settleRemaining); return; }
        if (ocr.isBusy()) { schedule(700); return; }
        busy = true;
        semanticDetailDecision = null;
        long token = generation, pageToken = windowGeneration, began = SystemClock.elapsedRealtime();
        long boundaryToken = readBoundaryGeneration;
        int readWindow = currentReadWindow();
        long observedAt = System.currentTimeMillis();
        if (watchdog != null) handler.removeCallbacks(watchdog);
        watchdog = () -> {
            if (running && token == generation && busy)
                stop("界面读取或操作超时，任务已暂停。", "BLOCKED", "operation_timeout", true);
        };
        boolean openingBurst = config.saleAt != null
                && Math.abs(System.currentTimeMillis() - config.saleAt.toEpochMilli()) < 60_000;
        long watchdogMs = openingBurst ? 4000 : (confirmationTest || liveOrderAttempt != null ? 5000 : 8000);
        handler.postDelayed(watchdog, watchdogMs);
        if (confirmationTest || liveOrderAttempt != null) {
            handleOrderCheckout(token, pageToken); return;
        }
        List<UiItem> nodes = readNodes();
        Decision decision = engine.evaluate(config, new Observation(TARGET, nodes, observedAt), lastNavigationAt);
        boolean quantityNeedsReadback = quantityTransition.pending()
                && decision.state != Decision.State.BLOCKED && decision.state != Decision.State.EXPIRED
                && decision.state != Decision.State.CHECKOUT && decision.state != Decision.State.UNRELATED
                && decision.state != Decision.State.CONFIG_INVALID;
        if (decision.state != Decision.State.UNKNOWN && !quantityNeedsReadback) {
            if (valid(token, pageToken)) readRecovery.reset();
            handle(decision, began, token, pageToken, nodes.size(), 0, false); return;
        }
        long captureWait = ocr.captureDelayMs();
        if (captureWait > 0) { busy = false; schedule(captureWait); return; }
        lastCaptureAt = SystemClock.elapsedRealtime();
        ocr.readGuarded(nodes, observedAt, (items, evidence, proof, error) -> {
            if (token != generation) return;
            if (error != null) {
                stop("页面截图或本地识别不可用，请在手机检查后重新开始。", "BLOCKED", error, true); return;
            }
            if (!valid(token, pageToken)) {
                if (trySemanticDetailRead(nodes, items, observedAt, began, token, boundaryToken, readWindow)) return;
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
                                } else {
                                    // Only a failed complete-frame match may request the one-shot
                                    // reversible settling scroll. Already stable detail frames never
                                    // scroll; the old OCR can authorize only this narrow scroll purpose.
                                    if (!matches && tryDetailSettle(nodes, items, observedAt, token,
                                            pageToken, boundaryToken, readWindow)) return;
                                    deferChangedRead(began);
                                }
                            });
                } else deferChangedRead(began);
                return;
            }
            acceptMonitorRead(nodes, items, evidence, observedAt, began, token, pageToken);
        });
    }

    /** Reread the entire screen, but compare navigation semantics rather than video pixels.
     * Both full reads must independently propose only the same reversible footer entry. */
    private boolean trySemanticDetailRead(List<UiItem> nodes, List<UiItem> items, long observedAt,
                                          long began, long token, long boundary, int window) {
        if (!detailReadBoundaryCurrent(token, boundary, window) || quantityTransition.pending()
                || transitionAction != Decision.Action.NONE || liveOrderAttempt != null) {
            store.appendEvent("NAVIGATION_REVIEW", "boundary_" + (boundary == readBoundaryGeneration)
                    + "_window_" + (window == currentReadWindow()) + "_transition_" + transitionAction.name()
                    + "_event_" + lastWindowEventSummary, SystemClock.elapsedRealtime() - began);
            return false;
        }
        List<UiItem> firstItems = mergeReadItems(nodes, items);
        Decision first = engine.evaluate(config, new Observation(TARGET, firstItems, observedAt), lastNavigationAt);
        if (!DetailOcrNavigation.isProposal(first) || DetailScrollPolicy.unsafePage(firstItems)) {
            store.appendEvent("NAVIGATION_REVIEW", "first_read_" + first.reason, 0);
            return false;
        }
        handler.postDelayed(() -> {
            if (token != generation) return;
            if (!detailReadBoundaryCurrent(token, boundary, window)) {
                store.appendEvent("NAVIGATION_REVIEW", "second_boundary_" + lastWindowEventSummary, SystemClock.elapsedRealtime() - began);
                deferChangedRead(began); return;
            }
            List<UiItem> secondNodes = readNodes();
            long secondAt = System.currentTimeMillis();
            ocr.readGuarded(secondNodes, secondAt, (secondItems, evidence, proof, error) -> {
                if (token != generation) return;
                if (error != null || !detailReadBoundaryCurrent(token, boundary, window)) {
                    store.appendEvent("NAVIGATION_REVIEW", "second_read_boundary_" + lastWindowEventSummary, SystemClock.elapsedRealtime() - began);
                    deferChangedRead(began); return;
                }
                List<UiItem> merged = mergeReadItems(secondNodes, secondItems);
                Decision second = engine.evaluate(config, new Observation(TARGET, merged, secondAt), lastNavigationAt);
                if (!DetailNavigationRead.agrees(config, first, firstItems, second, merged, System.currentTimeMillis())
                        || !detailOcrAnchorSafe(second, getRootInActiveWindow())) {
                    store.appendEvent("NAVIGATION_REVIEW", "second_mismatch_" + second.reason, SystemClock.elapsedRealtime() - began);
                    deferChangedRead(began); return;
                }
                semanticDetailDecision = second; semanticDetailBoundary = boundary; semanticDetailWindow = window;
                readRecovery.reset();
                store.appendEvent("READ_RECOVERY", "detail_semantics_revalidated", SystemClock.elapsedRealtime() - began);
                handle(second, began, token, windowGeneration, secondNodes.size(), secondItems.size(), true);
            });
        }, ocr.captureDelayMs());
        return true;
    }

    private boolean detailReadBoundaryCurrent(long token, long boundary, int window) {
        return running && token == generation && boundary == readBoundaryGeneration && window >= 0
                && window == currentReadWindow() && unlocked() && TARGET.equals(foreground()) && timingPermitsRead();
    }

    private boolean semanticDetailCurrent(Decision decision, long token) {
        long age = System.currentTimeMillis() - decision.observedAtMs;
        return decision == semanticDetailDecision && DetailOcrNavigation.isProposal(decision)
                && age >= 0 && age < 3000 && detailReadBoundaryCurrent(token, semanticDetailBoundary, semanticDetailWindow)
                // Both complete semantic reads already veto modal/error content. The final
                // reversible-navigation guard checks the live window/root, not another slow
                // traversal of every video descendant on the 200 ms Binder guard budget.
                && detailOcrAnchorSafe(decision, getRootInActiveWindow());
    }

    private boolean tryDetailSettle(List<UiItem> nodes, List<UiItem> items, long observedAt,
                                    long token, long pageToken, long boundaryToken, int readWindow) {
        if (!running || token != generation || liveOrderAttempt != null || quantityTransition.pending()
                || transitionAction != Decision.Action.NONE || !engine.detailScrollMayPrepare(config)) return false;
        long currentPage = windowGeneration;
        DetailScrollPolicy.FrameCheck check = DetailScrollPolicy.checkFrame(pageToken, currentPage,
                boundaryToken, readBoundaryGeneration, readWindow, currentReadWindow(),
                System.currentTimeMillis() - observedAt, nodes, readNodes());
        if (check != DetailScrollPolicy.FrameCheck.VERIFIED) { logDetailScrollFrame(check); return false; }
        if (!valid(token, currentPage)) return false;
        List<UiItem> merged = mergeReadItems(nodes, items);
        Decision proposal = engine.prepareDetailScroll(config, new Observation(TARGET, merged, observedAt));
        if (proposal == null) return false;
        AccessibilityNodeInfo anchor = getRootInActiveWindow();
        Rect bounds = new Rect();
        if (!detailScrollRootSafe(anchor, null, readWindow)) {
            stop("详情稳定滚动的窗口无法核实，已暂停。", "UNKNOWN", "detail_scroll_anchor_unverified", true); return true;
        }
        anchor.getBoundsInScreen(bounds);
        android.hardware.display.DisplayManager manager = getSystemService(android.hardware.display.DisplayManager.class);
        android.view.Display display = manager == null ? null : manager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        if (display == null || display.getState() != android.view.Display.STATE_ON) {
            stop("详情稳定滚动的屏幕无法核实，已暂停。", "BLOCKED", "detail_scroll_anchor_unverified", true); return true;
        }
        display.getRealMetrics(metrics);
        DetailScrollPolicy.Plan plan = DetailScrollPolicy.forRoot(bounds.left, bounds.top, bounds.right, bounds.bottom,
                metrics.widthPixels, metrics.heightPixels);
        if (plan == null || !valid(token, windowGeneration) || !engine.consumeDetailScroll(proposal, config)) {
            stop("详情稳定滚动的授权或区域无法核实，已暂停。", "UNKNOWN", "detail_scroll_anchor_unverified", true); return true;
        }
        store.appendEvent("DETAIL_SETTLE", "scroll_proposed_once", 0);
        java.util.concurrent.atomic.AtomicReference<DetailScrollPolicy.FrameCheck> guardFrame =
                new java.util.concurrent.atomic.AtomicReference<>(DetailScrollPolicy.FrameCheck.VERIFIED);
        ShizukuInputBridge.DispatchGuard guard = new ShizukuInputBridge.DispatchGuard() {
            private Rejection reason = Rejection.NONE;
            @Override public Rejection rejection() { return reason; }
            @Override public boolean allow() {
                reason = detailScrollRejection(proposal, anchor, bounds, nodes, token, pageToken, boundaryToken, readWindow, guardFrame);
                return reason == Rejection.NONE;
            }
        };
        if (AppStore.INPUT_SHIZUKU.equals(inputMode)) {
            long age = System.currentTimeMillis() - observedAt;
            long deadline = SystemClock.elapsedRealtime() + Math.min(2000, Math.max(1, 3000 - age));
            inputBridge.scrollDetailUp(bounds.left, bounds.top, bounds.right, bounds.bottom, deadline, guard,
                    result -> finishDetailSettle(proposal, token, result.success, result.outcomeUnknown,
                            result.code, result.guardRejection, guardFrame.get()));
        } else {
            if (!guard.allow()) {
                finishDetailSettle(proposal, token, false, false, "gesture_guard_rejected", guard.rejection(), guardFrame.get()); return true;
            }
            Path path = new Path(); path.moveTo(plan.x, plan.startY); path.lineTo(plan.x, plan.endY);
            GestureDescription gesture = new GestureDescription.Builder().setDisplayId(android.view.Display.DEFAULT_DISPLAY)
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, DetailScrollPolicy.DURATION_MS)).build();
            boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription description) {
                    finishDetailSettle(proposal, token, true, false, "gesture_completed", Rejection.NONE, guardFrame.get());
                }
                @Override public void onCancelled(GestureDescription description) {
                    finishDetailSettle(proposal, token, false, true, "gesture_cancelled_outcome_unknown", Rejection.NONE, guardFrame.get());
                }
            }, handler);
            if (!accepted) finishDetailSettle(proposal, token, false, false, "gesture_not_dispatched", Rejection.NONE, guardFrame.get());
        }
        return true;
    }

    private Rejection detailScrollRejection(Decision proposal, AccessibilityNodeInfo anchor, Rect bounds,
                                            List<UiItem> originalNative, long token, long pageToken,
                                            long boundaryToken, int readWindow,
                                            java.util.concurrent.atomic.AtomicReference<DetailScrollPolicy.FrameCheck> guardFrame) {
        if (!running) return Rejection.MONITOR_STOPPED;
        if (token != generation) return Rejection.MONITOR_GENERATION_CHANGED;
        if (!unlocked()) return Rejection.SCREEN_LOCKED;
        if (!TARGET.equals(foreground())) return Rejection.FOREGROUND_CHANGED;
        if (!timingPermitsRead()) return Rejection.TIMING_DISALLOWED;
        if (!engine.detailScrollCurrent(proposal, config)) return Rejection.TARGET_PATH_UNVERIFIED;
        long currentPage = windowGeneration;
        if (boundaryToken != readBoundaryGeneration || readWindow != currentReadWindow()) return Rejection.PAGE_BOUNDARY_CHANGED;
        DetailScrollPolicy.FrameCheck frame = DetailScrollPolicy.checkFrame(pageToken, currentPage, boundaryToken, readBoundaryGeneration,
                readWindow, currentReadWindow(), System.currentTimeMillis() - proposal.observedAtMs,
                originalNative, readNodes());
        guardFrame.set(frame);
        if (frame != DetailScrollPolicy.FrameCheck.VERIFIED) {
            logDetailScrollFrame(frame);
            if (DetailScrollPolicy.retryableNativeChange(frame)) return Rejection.SCROLL_NATIVE_CHANGED;
            if (frame == DetailScrollPolicy.FrameCheck.FRAME_STALE) return Rejection.OBSERVATION_STALE;
            if (frame == DetailScrollPolicy.FrameCheck.FRAME_FROM_FUTURE) return Rejection.OBSERVATION_FROM_FUTURE;
            if (frame == DetailScrollPolicy.FrameCheck.BOUNDARY_CHANGED || frame == DetailScrollPolicy.FrameCheck.WINDOW_CHANGED)
                return Rejection.PAGE_BOUNDARY_CHANGED;
            return Rejection.TARGET_PATH_UNVERIFIED;
        }
        if (!detailScrollRootSafe(anchor, bounds, readWindow)) return Rejection.TARGET_PATH_UNVERIFIED;
        return valid(token, currentPage) && boundaryToken == readBoundaryGeneration ? Rejection.NONE : Rejection.PAGE_BOUNDARY_CHANGED;
    }

    private boolean detailScrollRootSafe(AccessibilityNodeInfo root, Rect expected, int windowId) {
        if (root == null || !root.refresh() || !root.isVisibleToUser() || !root.isEnabled() || root.isPassword()
                || root.getParent() != null || !TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                || (Build.VERSION.SDK_INT >= 34 && root.isAccessibilityDataSensitive()) || root.getWindowId() != windowId) return false;
        android.view.accessibility.AccessibilityWindowInfo window = root.getWindow();
        if (window == null || !window.isActive() || window.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) return false;
        Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
        return !bounds.isEmpty() && (expected == null || expected.equals(bounds));
    }

    private void logDetailScrollFrame(DetailScrollPolicy.FrameCheck frame) {
        if (frame != lastDetailScrollFrameCheck) {
            store.appendEvent("DETAIL_SETTLE_FRAME", frame.code(), 0); lastDetailScrollFrameCheck = frame;
        }
    }

    private void finishDetailSettle(Decision proposal, long token, boolean success, boolean unknown,
                                     String resultCode, Rejection rejection, DetailScrollPolicy.FrameCheck frame) {
        if (!running || token != generation) return;
        if (rejection != Rejection.NONE) store.appendEvent("LOCAL_INPUT_GUARD", rejection.code(), 0);
        if (!success) {
            if (engine.retryUnsentDetailScroll(proposal, config, success, unknown, resultCode, rejection, frame)) {
                // The exact service response proves input never started. Obtain new OCR/native
                // facts within the original permit and read-recovery budget; never replay this work.
                store.appendEvent("DETAIL_SETTLE", "scroll_unsent_reobserve", 0);
                busy = false; lastCaptureAt = 0;
                detailSettleReadAfter = SystemClock.elapsedRealtime() + DetailScrollPolicy.SETTLE_READ_DELAY_MS;
                status("滚动尚未执行，页面信息已变化；正在重新读取，不会沿用旧滚动方案。");
                schedule(DetailScrollPolicy.SETTLE_READ_DELAY_MS); return;
            }
            engine.finishDetailScroll(proposal);
            stop("详情稳定滚动未能确认完成，已暂停；不会重复滚动。", unknown ? "UNKNOWN" : "BLOCKED",
                    "detail_scroll_input_unconfirmed", true); return;
        }
        engine.finishDetailScroll(proposal);
        // No actionDone: preserve BACK's existing interval/cooldown and the fixed permit expiry.
        store.appendEvent("DETAIL_SETTLE", "scroll_command_completed", 0);
        busy = false; readRecovery.reset(); lastCaptureAt = 0;
        detailSettleReadAfter = SystemClock.elapsedRealtime() + DetailScrollPolicy.SETTLE_READ_DELAY_MS;
        status("已尝试一次普通详情滚动，正在重新读取完整项目与入口；库存仍待核实。");
        schedule(DetailScrollPolicy.SETTLE_READ_DELAY_MS);
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
        List<UiItem> merged = mergeReadItems(nodes, items);
        // Preserve the original OCR observation time; visual revalidation never renews input freshness.
        Decision read = engine.evaluate(config, new Observation(TARGET, merged, observedAt, evidence), lastNavigationAt);
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && evidence != null && read.reason.equals("adapter_quantity_or_total_not_verified")) {
            store.appendEvent("SELECTION_CHECK", evidence.quantity == null ? "quantity_unverified"
                    : evidence.totalYuan == null ? "total_unverified"
                    : evidence.confirmTarget == null ? "confirm_unverified"
                    : evidence.quantityIncreaseTarget == null ? "increase_unverified"
                    : "combined_evidence_conflict", 0);
        }
        handle(read, began, token, pageToken, nodes.size(), items.size(), true);
    }

    private static List<UiItem> mergeReadItems(List<UiItem> nodes, List<UiItem> items) {
        return cn.local.tickethelper.core.ObservationMerge.merge(nodes, items);
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
        return running && token == generation && pageToken == windowGeneration && unlocked() && TARGET.equals(foreground())
                && timingPermitsRead();
    }

    private SaleTiming.Tick timingTick() {
        return saleTiming == null ? null : saleTiming.tick(System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }
    private boolean timingPermitsRead() {
        SaleTiming.Tick timing = timingTick(); return timing != null && timing.mayReadOfficialPage();
    }
    private long observationDelay() {
        // During the critical 60s around sale start, scan faster
        if (config.saleAt != null && Math.abs(System.currentTimeMillis() - config.saleAt.toEpochMilli()) < 60_000)
            return 150;
        SaleTiming.Tick timing = timingTick();
        return timing == null ? SaleTiming.LIVE_READ_DELAY_MS : Math.max(1, timing.nextWakeMs);
    }

    /** Short delay during checkout to reduce end-to-end submission latency. */
    private long checkoutDelay() { return 200; }

    private void handle(Decision decision, long began, long token, long pageToken,
                        int nodeCount, int ocrCount, boolean usedOcr) {
        if (token != generation) return;
        if (!valid(token, pageToken)) { busy = false; schedule(700); return; }
        AiContextRepository.recordDecision(decision);
        store.appendObservation(decision.state.name(), decision.reason, SystemClock.elapsedRealtime() - began,
                nodeCount, ocrCount, usedOcr);
        if(transitionAction==Decision.Action.NONE && engine.expandAfterOpeningShortage(config,decision)) {
            busy=false;store.appendEvent("OPENING_FALLBACK","primary_tier_shortage_fallback_enabled",0);
            status("首抢票档已确认缺票，继续核查接受的其他票档。");schedule(1);return;
        }
        if (transitionAction == Decision.Action.INCREASE_QUANTITY) {
            if (decision.state == Decision.State.CHECKOUT) {
                stop("数量调整后进入了其他页面，尚未确认两张，已暂停。", "UNKNOWN", "quantity_transition_page_changed", true); return;
            }
            if (decision.state != Decision.State.BLOCKED && decision.state != Decision.State.EXPIRED
                    && decision.state != Decision.State.UNRELATED && decision.state != Decision.State.CONFIG_INVALID) {
                if (decision.state == Decision.State.OUT_OF_STOCK) {
                    clearQuantityTransition(); transitionAction = Decision.Action.NONE;
                } else {
                    QuantityTransition.Result readback = quantityTransition.inspect(decision.selectionEvidence, SystemClock.elapsedRealtime());
                    if (readback == QuantityTransition.Result.CHANGED || readback == QuantityTransition.Result.TIMED_OUT) {
                        stop("加号已尝试一次，但两张数量与票款未能核实；已暂停，不会重复点击。", "UNKNOWN", "quantity_transition_unverified", true); return;
                    }
                    if (readback != QuantityTransition.Result.VERIFIED || decision.state != Decision.State.TIER_SELECTED) {
                        busy = false; status("正在核对两张数量与票款，不会再次点击加号。"); schedule(observationDelay()); return;
                    }
                    clearQuantityTransition(); transitionAction = Decision.Action.NONE;
                }
            }
        }
        if (transitionAction != Decision.Action.NONE && decision.state != Decision.State.CHECKOUT
                && decision.state != Decision.State.BLOCKED && decision.state != Decision.State.EXPIRED) {
            boolean sessionVerified = decision.selectionEvidence != null
                    && decision.selectionEvidence.observedAtMs == decision.observedAtMs
                    && config.date.equals(decision.selectionEvidence.date) && config.time.equals(decision.selectionEvidence.time);
            boolean stillPending = transitionAction == Decision.Action.SELECT_SESSION ? !sessionVerified
                    : transitionAction == Decision.Action.CONFIRM_TIER
                    || (decision.state != Decision.State.TIER_SELECTED && decision.state != Decision.State.OUT_OF_STOCK
                        && !QuantityTransition.isIncreaseProposal(decision));
            if (stillPending) {
                long transitionTimeout = (transitionAction == Decision.Action.CONFIRM_TIER
                        || transitionAction == Decision.Action.OPEN_TICKETS) ? 3000 : 6000;
                if (SystemClock.elapsedRealtime() - transitionAt >= transitionTimeout) {
                    stop("操作后的页面变化未能核实，已暂停，请手动检查。", "UNKNOWN",
                            "transition_unverified:" + decision.reason, true);
                } else { busy = false; schedule(observationDelay()); }
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
                    if (confirmationOnly) status("已进入确认页（仅确认提交模式），正在读取页面。");
                    handleOrderCheckout(token, pageToken); return;
                }
                stop("已检测到订单相关页面，请核对信息并手动继续；尚未确认生成订单。", "CHECKOUT", decision.reason, true); return;
            case UNRELATED:
                stop("当前页面不匹配，任务暂停。", "UNRELATED", decision.reason, true); return;
            case UNKNOWN:
                if (waitForNormalEntry(decision, token, pageToken)) return;
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
                unknownCount = 0; status(decision.action == Decision.Action.INCREASE_QUANTITY
                        ? "已核实当前一张，正在尝试增加至两张；两张库存仍待核实。"
                        : decision.action == Decision.Action.SELECT_SESSION
                        ? "正在选择已核实的目标场次，随后重新检查库存；尚未发现有票。"
                        : "目标场次已核实，正在试选票档；库存仍待核实。"); break;
            case TIER_SELECTED:
                unknownCount = 0; status("票档、数量及票款已核实，准备进入确认购买页。"); break;
            case OUT_OF_STOCK:
                unknownCount = 0; status("可见目标票档显示缺货，继续按设定间隔检查。"); break;
            default:
                unknownCount = 0; status("已识别目标页面，正在检查票档。"); break;
        }
        if (decision.action == Decision.Action.OPEN_TICKETS && !normalTicketEntry(decision.target)
                && !DetailOcrNavigation.isProposal(decision)) {
            busy = false; status("当前没有核实到正常购票入口，等待页面变化；不会点击缺货登记或想看。");
            schedule(observationDelay()); return;
        }
        boolean mayDispatch = ActionPacing.mayDispatch(decision, System.currentTimeMillis(), SystemClock.elapsedRealtime(), nextActionAt, actionFailures > 0);
        if (mayDispatch) {
            act(decision, token, pageToken); return;
        }
        busy = false;
        long baseDelay = observationDelay();
        if (decision.action == Decision.Action.CONFIRM_TIER
                || decision.action == Decision.Action.OPEN_TICKETS) baseDelay = Math.min(baseDelay, 150);
        long wait = decision.action == Decision.Action.NONE ? baseDelay
                : Math.min(baseDelay, SaleTiming.actionWaitMs(false, SystemClock.elapsedRealtime(), nextActionAt));
        schedule(Math.max(1, wait));
    }

    private boolean waitForNormalEntry(Decision decision, long token, long pageToken) {
        SaleTiming.Tick timing = timingTick();
        if (config.saleAt == null || timing == null || !timing.mayReadOfficialPage()
                || !"reservation_selection_only".equals(decision.reason)) return false;
        unknownCount = 0;
        // A fresh, exact native reservation label permits one ordinary BACK in PREPARE only.
        // Never use this recovery on checkout/confirmation pages or infer an available stock.
        if (!reservationExitAttempted && config.mode == MonitorConfig.Mode.PREPARE
                && transitionAction == Decision.Action.NONE && liveOrderAttempt == null
                && readNodes().stream().filter(i -> i.source == UiItem.Source.NODE
                    && "cn.damai:id/tv_perform_name".equals(i.resourceId) && i.text.equals("预约想看场次")).count() == 1) {
            reservationExitAttempted = true;
            store.appendEvent("SALE_TIMING", "reservation_back_to_detail_once", 0);
            act(new Decision(Decision.State.READY, Decision.Action.BACK, null,
                    "reservation_back_to_detail_once", decision.observedAtMs), token, pageToken);
            return true;
        }
        busy = false; status("已到设定开售时间，当前仍为预约预选页；等待官方正常购票入口，库存未核实。");
        schedule(observationDelay()); return true;
    }
    private static boolean normalTicketEntry(UiItem target) {
        if (target == null) return false;
        String text = target.text.trim();
        return text.equals("立即购买") || text.equals("立即预订") || text.equals("选座购买") || text.equals("购买");
    }

    private boolean orderGrantCurrent() {
        return orderAuthorization != null && orderConfigRevision == store.getConfigRevision()
                && orderAuthorization.validFor(confirmationTest ? config : store.loadConfig(), System.currentTimeMillis())
                && (confirmationTest ? confirmationTestViewers : store.getViewerBindings()).size() == config.quantity
                && orderAuthorization.viewerBindingHash.equals(LockAuthorization.viewerBindingsHash(
                        confirmationTest ? confirmationTestViewers : store.getViewerBindings()));
    }

    private CheckoutObservation readOrderPage() {
        return readOrderPageResult().observation;
    }

    private CheckoutParseResult readOrderPageResult() {
        if (confirmationTest) return OrderCheckoutAdapter.readConfirmationProbe(this, () -> windowGeneration,
                liveOrderAttempt == null ? "" : liveOrderAttempt.attemptId, confirmationTestViewers);
        return OrderCheckoutAdapter.read(this, () -> windowGeneration,
                liveOrderAttempt == null ? "" : liveOrderAttempt.attemptId,
                store.getViewerBindings());
    }

    private void handleOrderCheckout(long token, long pageToken) {
        if (!valid(token, pageToken)) { busy = false; schedule(checkoutDelay()); return; }
        boolean live = OrderCheckoutAdapter.isLiveVerified();
        if ((!confirmationTest && !live && !confirmationOnly) || !orderGrantCurrent() || orderAttempts == null) {
            stop("自动下单适配或本次授权无法核实，已暂停。", "CHECKOUT", "order_adapter_or_grant_unverified", true); return;
        }
        try {
            CheckoutParseResult read = readOrderPageResult();
            store.appendEvent("ORDER_READ", read.reason, 0);
            OrderAttempt attempt = orderAttempts.read();
            if (confirmationTest && attempt == null && !read.observation.viewerSelectionVerified) {
                stop("本次提交测试需要先在大麦明确勾选两位观演人；尚未提交。", "CHECKOUT", "viewer_selection_unverified", true); return;
            }
            if (attempt == null && read.viewerResult != null
                    && read.viewerResult.state == ViewerMatcher.State.SELECT_PROPOSAL) {
                if (viewerSelectionTargets.containsKey(viewerTargetKey(read))) {
                    if (SystemClock.elapsedRealtime() - viewerSelectionTargets.get(viewerTargetKey(read)) < 3000) {
                        busy = false; schedule(checkoutDelay()); return;
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
                    busy = false; status("已发出一次订单提交，正在核实大麦结果；不会重复提交。"); schedule(checkoutDelay()); return;
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
        if ((!confirmationTest && !OrderCheckoutAdapter.isLiveVerified()) || !valid(token, pageToken) || !orderGrantCurrent()
                || liveOrderAttempt == null || orderAttempts == null || !orderInputFresh(proposal, token, pageToken)) return null;
        if (!orderEngine.revalidateForDispatch(config, orderAuthorization, readOrderPage(), proposal,
                liveOrderAttempt, System.currentTimeMillis())) return null;
        if (!orderInputFresh(proposal, token, pageToken)) return null;
        long deadline = SystemClock.elapsedRealtime() + 150;
        AccessibilityNodeInfo node = confirmationTest
                ? OrderCheckoutAdapter.findConfirmationProbeSubmit(this, proposal, () -> windowGeneration, deadline)
                : OrderCheckoutAdapter.findSubmitTarget(this, proposal, () -> windowGeneration, deadline);
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
        if (decision.action == Decision.Action.INCREASE_QUANTITY) {
            AccessibilityNodeInfo plus = target.source == UiItem.Source.NODE
                    ? findTarget(getRootInActiveWindow(), target, 0) : null;
            if (config.quantity != 2 || !QuantityTransition.isIncreaseProposal(decision)
                    || quantityTransition.pending() || !quantityIncreaseStillOne(plus)) {
                stop("数量加号与当前真实控件不匹配，已暂停。", "UNKNOWN", "quantity_target_unverified", true); return;
            }
            if (AppStore.INPUT_SHIZUKU.equals(inputMode)) dispatchVerifiedLocalTap(decision, plus, token, pageToken);
            else dispatchVerifiedGesture(decision, plus, "verified_quantity_gesture", token, pageToken);
            return;
        }
        if (decision.action == Decision.Action.OPEN_TICKETS && !normalTicketEntry(target)
                && !DetailOcrNavigation.isProposal(decision)) {
            stop("正常购票入口未核实，不会点击登记或想看。", "UNKNOWN", "ticket_entry_not_verified", true); return;
        }
        if (DetailOcrNavigation.isProposal(decision)) {
            // This provider exposes no refreshable native footer leaf. The root is only a fresh
            // frame/window anchor; the click position remains this exact same-frame OCR footer.
            AccessibilityNodeInfo anchor = getRootInActiveWindow();
            if (!detailOcrAnchorSafe(decision, anchor)) {
                stop("同项目详情的底部导航与当前窗口无法核实，已暂停。", "UNKNOWN", "detail_ocr_anchor_unverified", true); return;
            }
            store.appendEvent("ACTION_ROUTE", "verified_detail_ocr_navigation", 0);
            if (AppStore.INPUT_SHIZUKU.equals(inputMode)) dispatchVerifiedLocalTap(decision, anchor, token, pageToken);
            else dispatchVerifiedGesture(decision, anchor, "verified_detail_ocr_navigation", token, pageToken);
            return;
        }
        if (decision.action == Decision.Action.SELECT_SESSION) {
            // Session proposals use the same native-bound SKU container route. Never let a date
            // OCR rectangle fall through to the generic coordinate-only gesture fallback.
            AccessibilityNodeInfo cell = target.source == UiItem.Source.OCR
                    ? findSkuCell(getRootInActiveWindow(), target, 0) : null;
            if (cell == null || !sessionCellInRegion(cell)) {
                stop("目标场次与当前原生控件不匹配，已暂停。", "UNKNOWN", "session_target_changed", true); return;
            }
            if (AppStore.INPUT_SHIZUKU.equals(inputMode)) dispatchVerifiedLocalTap(decision, cell, token, pageToken);
            else dispatchVerifiedGesture(decision, cell, "verified_session_gesture", token, pageToken);
            return;
        }
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
                && decision.action != Decision.Action.CONFIRM_TIER && decision.action != Decision.Action.OPEN_TICKETS
                && decision.action != Decision.Action.INCREASE_QUANTITY && decision.action != Decision.Action.SELECT_SESSION) {
            stop("当前动作不支持本地调试输入，已暂停。", "BLOCKED", "local_input_action_rejected", true); return;
        }
        if (node == null || !node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) {
            stop("目标控件已变化，已暂停。", "UNKNOWN", "local_input_target_stale", true); return;
        }
        if (DetailOcrNavigation.isProposal(decision) && !detailOcrAnchorSafe(decision, node)) {
            stop("同项目详情的底部导航与当前窗口无法核实，已暂停。", "UNKNOWN", "detail_ocr_anchor_unverified", true); return;
        }
        if (decision.action == Decision.Action.INCREASE_QUANTITY && !quantityIncreaseStillOne(node)) {
            stop("数量控件已变化，已暂停，不会重复加号。", "UNKNOWN", "quantity_target_unverified", true); return;
        }
        if (decision.action == Decision.Action.SELECT_SESSION && !sessionCellInRegion(node)) {
            stop("场次控件已变化，已暂停。", "UNKNOWN", "session_target_changed", true); return;
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
        store.appendEvent("INPUT_LATENCY", decision.action.name().toLowerCase(Locale.ROOT),
                Math.max(0, System.currentTimeMillis() - decision.observedAtMs));
        long observedAge = System.currentTimeMillis() - decision.observedAtMs;
        if (observedAge < 0 || observedAge >= 3000) { busy = false; schedule(700); return; }
        long dispatchDeadline = SystemClock.elapsedRealtime() + Math.min(2000, 3000 - observedAge);
        int expectedWindowId = node.getWindowId();
        long expectedBoundary = readBoundaryGeneration;
        inputBridge.tap(decision.target.centerX(), decision.target.centerY(),
                dispatchDeadline, new ShizukuInputBridge.DispatchGuard() {
                    private Rejection reason = Rejection.NONE;
                    @Override public Rejection rejection() { return reason; }
                    @Override public boolean allow() {
                        // Keep every original final guard. Reasons distinguish the failure without
                        // interpreting content events as harmless or renewing the OCR timestamp.
                        reason = localTapRejection(decision, node, bounds, expectedWindowId,
                                token, pageToken, expectedBoundary);
                        return reason == Rejection.NONE;
                    }
                }, result -> {
                    if (!running || token != generation) return;
                    store.appendEvent("LOCAL_INPUT_RESULT", result.code, 0);
                    if (!result.success) {
                        if (result.guardRejection != Rejection.NONE)
                            store.appendEvent("LOCAL_INPUT_GUARD", result.guardRejection.code(), 0);
                        if (InputDispatchPolicy.mayReobserve(decision.action, result.success,
                                result.outcomeUnknown, result.code, result.guardRejection)
                                || InputDispatchPolicy.mayReobserveNavigation(decision, result.success,
                                    result.outcomeUnknown, result.code, result.guardRejection)) {
                            // The remote service explicitly returned before starting input. Discard
                            // this complete proposal; scan will obtain native facts/OCR and decide anew.
                            busy = false;
                            if (!inputReadRecovery.retry(SystemClock.elapsedRealtime())) {
                                stop("点击前页面持续变化，新读取仍未稳定；已暂停，尚未执行该点击。",
                                        "UNKNOWN", "input_page_unstable", true); return;
                            }
                            store.appendEvent("READ_RECOVERY", "input_proposal_discarded", inputReadRecovery.attempts());
                            status("点击前页面已更新，正在重新读取；不会沿用旧点击位置。");
                            schedule(inputReadRecovery.delayMs()); return;
                        }
                        stop("本地输入未能确认完成，已暂停，请检查手机；不会重试该点击。",
                                result.outcomeUnknown ? "UNKNOWN" : "BLOCKED", "local_input_" + result.code, true);
                        return;
                    }
                    // A successful shell exit only confirms dispatch; the next observation must verify UI effect.
                    actionDone(decision, true, token);
                });
    }

    private Rejection localTapRejection(Decision decision, AccessibilityNodeInfo node, Rect bounds,
                                        int expectedWindowId, long token, long pageToken, long expectedBoundary) {
        if (!running) return Rejection.MONITOR_STOPPED;
        if (token != generation) return Rejection.MONITOR_GENERATION_CHANGED;
        if (pageToken != windowGeneration && !semanticDetailCurrent(decision, token)) return expectedBoundary == readBoundaryGeneration
                ? Rejection.PAGE_CONTENT_CHANGED : Rejection.PAGE_BOUNDARY_CHANGED;
        if (!unlocked()) return Rejection.SCREEN_LOCKED;
        if (!TARGET.equals(foreground())) return Rejection.FOREGROUND_CHANGED;
        if (!timingPermitsRead()) return Rejection.TIMING_DISALLOWED;
        long age = System.currentTimeMillis() - decision.observedAtMs;
        if (age < 0) return Rejection.OBSERVATION_FROM_FUTURE;
        if (age > 3000) return Rejection.OBSERVATION_STALE;
        if (!node.refresh()) return Rejection.TARGET_REFRESH_FAILED;
        if (!node.isVisibleToUser()) return Rejection.TARGET_HIDDEN;
        if (!node.isEnabled()) return Rejection.TARGET_DISABLED;
        if (node.getWindowId() != expectedWindowId) return Rejection.TARGET_WINDOW_CHANGED;
        Rect currentBounds = new Rect(); node.getBoundsInScreen(currentBounds);
        if (!bounds.equals(currentBounds)) return Rejection.TARGET_BOUNDS_CHANGED;
        android.view.accessibility.AccessibilityWindowInfo window = node.getWindow();
        if (window == null) return Rejection.TARGET_WINDOW_MISSING;
        if (!window.isActive()) return Rejection.TARGET_WINDOW_INACTIVE;
        if (window.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) return Rejection.TARGET_DISPLAY_CHANGED;
        if (decision.action == Decision.Action.INCREASE_QUANTITY && !quantityIncreaseStillOne(node))
            return Rejection.QUANTITY_CHANGED;
        if (decision.action == Decision.Action.SELECT_SESSION && !sessionCellInRegion(node))
            return Rejection.TARGET_PATH_UNVERIFIED;
        if (DetailOcrNavigation.isProposal(decision) && !detailOcrAnchorSafe(decision, node))
            return Rejection.TARGET_PATH_UNVERIFIED;
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 50; depth++, current = current.getParent()) {
            if (current.isPassword() || (Build.VERSION.SDK_INT >= 34 && current.isAccessibilityDataSensitive()))
                return Rejection.SENSITIVE_TARGET;
        }
        return current == null ? Rejection.NONE : Rejection.TARGET_PATH_UNVERIFIED;
    }

    private void dispatchVerifiedGesture(Decision decision, AccessibilityNodeInfo node, String route,
                                         long token, long pageToken) {
        if (!valid(token, pageToken)) { busy = false; schedule(700); return; }
        if (decision.action == Decision.Action.INCREASE_QUANTITY && !quantityIncreaseStillOne(node)) {
            stop("数量控件已变化，已暂停，不会重复加号。", "UNKNOWN", "quantity_target_unverified", true); return;
        }
        if (decision.action == Decision.Action.SELECT_SESSION && !sessionCellInRegion(node)) {
            stop("场次控件已变化，已暂停。", "UNKNOWN", "session_target_changed", true); return;
        }
        if (DetailOcrNavigation.isProposal(decision) && !detailOcrAnchorSafe(decision, node)) {
            stop("同项目详情的底部导航与当前窗口无法核实，已暂停。", "UNKNOWN", "detail_ocr_anchor_unverified", true); return;
        }
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
        if (decision.action == Decision.Action.INCREASE_QUANTITY && (displayId != android.view.Display.DEFAULT_DISPLAY
                || !quantityIncreaseStillOne(node))) {
            stop("数量控件已变化，已暂停，不会重复加号。", "UNKNOWN", "quantity_target_unverified", true); return;
        }
        if (DetailOcrNavigation.isProposal(decision) && !detailOcrAnchorSafe(decision, node)) {
            stop("同项目详情的底部导航与当前窗口无法核实，已暂停。", "UNKNOWN", "detail_ocr_anchor_unverified", true); return;
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
            if (openingInputPending && config.saleAt != null && decision.action != Decision.Action.BACK
                    && System.currentTimeMillis() >= config.saleAt.toEpochMilli()) {
                openingInputPending = false;
                store.appendEvent("OPENING_INPUT", decision.action.name().toLowerCase(Locale.ROOT),
                        System.currentTimeMillis()-config.saleAt.toEpochMilli());
            }
            inputReadRecovery.reset();
            actionFailures = 0;
            engine.recordAction(decision, System.currentTimeMillis());
            store.appendEvent("ACTION", decision.action.name().toLowerCase(Locale.ROOT) + "_dispatched", 0);
            if (decision.action == Decision.Action.SELECT_SESSION) {
                long completed = SystemClock.elapsedRealtime();
                if (lastSessionCompletedElapsed > 0)
                    store.appendEvent("CYCLE_LATENCY", "session_input_to_session_input", completed-lastSessionCompletedElapsed);
                lastSessionCompletedElapsed = completed;
            }
            if (decision.action == Decision.Action.CONFIRM_TIER || decision.action == Decision.Action.SELECT_TIER
                    || decision.action == Decision.Action.PROBE_TIER || decision.action == Decision.Action.INCREASE_QUANTITY
                    || decision.action == Decision.Action.SELECT_SESSION) {
                transitionAction = decision.action; transitionAt = SystemClock.elapsedRealtime();
                if (decision.action == Decision.Action.INCREASE_QUANTITY) {
                    if (!quantityTransition.begin(decision.selectionEvidence, transitionAt)) {
                        stop("数量转换无法核实，已暂停，不会重复加号。", "UNKNOWN", "quantity_transition_unverified", true); return;
                    }
                    quantityTimeout = () -> {
                        if (running && token == generation && quantityTransition.pending())
                            stop("加号后六秒仍未核实两张与票款，已暂停，不会重复加号。", "UNKNOWN", "quantity_transition_unverified", true);
                    };
                    handler.postDelayed(quantityTimeout, QuantityTransition.TIMEOUT_MS);
                }
            }
            if (decision.action == Decision.Action.BACK || (decision.action == Decision.Action.OPEN_TICKETS
                    && !"verified_return_detail_entry".equals(decision.reason)))
                lastNavigationAt = System.currentTimeMillis();
            if (decision.action == Decision.Action.BACK) lastDetailScrollFrameCheck = null;
        } else {
            if (decision.action == Decision.Action.INCREASE_QUANTITY) {
                stop("加号操作未能确认完成，已暂停，不会重复该点击。", "UNKNOWN", "quantity_input_unconfirmed", true); return;
            }
            actionFailures++; store.appendEvent("ACTION_FAILED", "ui_action_not_dispatched", 0);
            if (actionFailures >= 2) {
                stop("界面操作连续失败，任务已暂停，请检查手机。", "BLOCKED", "ui_action_failed", true); return;
            }
        }
        // This paired navigation belongs to BACK's existing refresh round; do not start a second
        // random interval or move the outer refresh anchor forward after entering the selector.
        if (ActionPacing.restartsCooldown(decision, success)) {
            long delay = ThreadLocalRandom.current().nextLong(config.minIntervalMs, config.maxIntervalMs + 1);
            nextActionAt = SystemClock.elapsedRealtime() + delay;
            store.appendEvent("REFRESH_WAIT", "sampled_cooldown_ms_" + delay, 0);
        }
        // Fresh permitted forward steps get prompt readback; trial probes/navigation remain paced.
        boolean freshForward = success && ActionPacing.mayDispatch(decision, System.currentTimeMillis(),
                0, Long.MAX_VALUE, false);
        schedule(freshForward ? SaleTiming.afterForwardInputReadDelayMs() : observationDelay());
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
            String resourceId = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
            // Empty layout wrappers are not evidence. Do not perform parent Binder queries
            // for nodes that will be discarded; still visit every child below.
            if (!text.isEmpty() || evidenceContainer(resourceId) || quantityEvidenceNode(node)) {
                Rect rect = new Rect(); node.getBoundsInScreen(rect);
                AccessibilityNodeInfo container = clickContainer(node, rect);
                boolean selected = node.isSelected() || node.isChecked()
                        || (container != null && (container.isSelected() || container.isChecked()));
                items.add(new UiItem(text, rect.left, rect.top, rect.right, rect.bottom,
                        node.isEnabled(), container != null, selected, UiItem.Source.NODE, resourceId));
            }
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

    private void clearQuantityTransition() {
        quantityTransition.reset();
        if (quantityTimeout != null) handler.removeCallbacks(quantityTimeout);
        quantityTimeout = null;
    }

    /** Exact native path from historical quantity captures; no arbitrary empty clickable is retained. */
    private static boolean quantityEvidenceNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        String id = node.getViewIdResourceName();
        boolean stepperNode = "cn.damai:id/layout_sku_num_ll".equals(id);
        if (!stepperNode && !"cn.damai:id/img_jia".equals(id) && !"cn.damai:id/img_jian".equals(id)) return false;
        AccessibilityNodeInfo stepper = stepperNode ? node : node.getParent();
        if (!nativeShape(stepper, "layout_sku_num_ll", "android.widget.LinearLayout") || stepper.getChildCount() != 3) return false;
        AccessibilityNodeInfo area = stepper.getParent(), bottom = area == null ? null : area.getParent();
        if (!nativeShape(area, "layout_num", "android.widget.LinearLayout")) return false;
        // Damai 9.0.37 on the current vivo has two named bottom-bar wrappers.
        // Accept this exact observed path, never an arbitrary ancestor search.
        if (nativeShape(bottom, "id_sku_bottom_bar_action_content", "android.widget.LinearLayout")) {
            if (!bottom.isEnabled()) return false;
            AccessibilityNodeInfo wrapper = bottom.getParent();
            if (!nativeShape(wrapper, "id_sku_bottom_bar_container", "android.widget.FrameLayout")
                    || !wrapper.isEnabled()) return false;
            bottom = wrapper.getParent();
        }
        if (!nativeShape(bottom, "bottom_layout", "android.widget.LinearLayout")) return false;
        if (!stepper.isEnabled() || !area.isEnabled() || !bottom.isEnabled()) return false;
        AccessibilityNodeInfo minus = stepper.getChild(0), count = stepper.getChild(1), plus = stepper.getChild(2);
        if (!nativeShape(minus, "img_jian", "android.widget.ImageView") || !blankNode(minus)
                || !nativeShape(count, "tv_num", "android.widget.TextView")
                || !nativeShape(plus, "img_jia", "android.widget.ImageView") || !blankNode(plus)) return false;
        Rect parentBounds = new Rect(), bounds = new Rect(); stepper.getBoundsInScreen(parentBounds); node.getBoundsInScreen(bounds);
        return parentBounds.contains(bounds) && !bounds.isEmpty();
    }
    private static boolean quantityIncreaseStillOne(AccessibilityNodeInfo plus) {
        if (plus == null || !plus.refresh() || !"cn.damai:id/img_jia".equals(plus.getViewIdResourceName())
                || !plus.isEnabled() || !plus.isClickable() || !quantityEvidenceNode(plus)) return false;
        AccessibilityNodeInfo stepper = plus.getParent();
        AccessibilityNodeInfo count = stepper.getChild(1), minus = stepper.getChild(0);
        if (count == null || count.getText() == null || !count.getText().toString().replaceAll("\\s+", "").equals("1张")
                || minus == null || minus.isEnabled()) return false;
        AccessibilityNodeInfo ancestor = plus;
        for (int depth=0; ancestor != null && depth<50; depth++, ancestor=ancestor.getParent())
            if (ancestor.isPassword() || (Build.VERSION.SDK_INT>=34 && ancestor.isAccessibilityDataSensitive())) return false;
        return ancestor == null;
    }
    private static boolean nativeShape(AccessibilityNodeInfo node, String id, String type) {
        return node != null && node.isVisibleToUser() && (Build.VERSION.SDK_INT < 34 || !node.isAccessibilityDataSensitive())
                && !node.isPassword() && TARGET.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())
                && ("cn.damai:id/" + id).equals(node.getViewIdResourceName()) && type.contentEquals(node.getClassName() == null ? "" : node.getClassName());
    }
    private static boolean blankNode(AccessibilityNodeInfo node) {
        return (node.getText() == null || node.getText().toString().trim().isEmpty())
                && (node.getContentDescription() == null || node.getContentDescription().toString().trim().isEmpty());
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

    private static boolean sessionCellInRegion(AccessibilityNodeInfo node) {
        if (node == null || !node.refresh() || !node.isVisibleToUser() || !node.isEnabled()) return false;
        AccessibilityNodeInfo ancestor = node;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        for (int depth = 0; ancestor != null && depth < 8; depth++, ancestor = ancestor.getParent()) {
            if (ancestor.isPassword() || (Build.VERSION.SDK_INT >= 34 && ancestor.isAccessibilityDataSensitive())) return false;
            if ("cn.damai:id/project_detail_perform_flowlayout".equals(ancestor.getViewIdResourceName())) {
                Rect area = new Rect(); ancestor.getBoundsInScreen(area);
                return ancestor.isVisibleToUser() && ancestor.isEnabled() && area.contains(bounds);
            }
        }
        return false;
    }

    private boolean detailOcrAnchorSafe(Decision decision, AccessibilityNodeInfo root) {
        if (!DetailOcrNavigation.isProposal(decision) || root == null || !root.refresh()
                || !root.isVisibleToUser() || !root.isEnabled() || root.isPassword()
                || !TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())
                || (Build.VERSION.SDK_INT >= 34 && root.isAccessibilityDataSensitive()) || root.getParent() != null) return false;
        android.view.accessibility.AccessibilityWindowInfo window = root.getWindow();
        if (window == null || !window.isActive() || window.getDisplayId() != android.view.Display.DEFAULT_DISPLAY
                || root.getWindowId() != currentReadWindow()) return false;
        Rect bounds = new Rect(); root.getBoundsInScreen(bounds);
        return DetailOcrNavigation.footerContained(decision, bounds.left, bounds.top, bounds.right, bounds.bottom);
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
