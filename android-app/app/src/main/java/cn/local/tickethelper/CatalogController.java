package cn.local.tickethelper;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;
import cn.local.tickethelper.catalog.CatalogManualEntry;
import cn.local.tickethelper.catalog.CatalogHandoff;
import cn.local.tickethelper.catalog.CatalogParser;
import cn.local.tickethelper.catalog.ReservationSessionParser;
import cn.local.tickethelper.catalog.CatalogProject;
import cn.local.tickethelper.catalog.CatalogSession;
import cn.local.tickethelper.catalog.CatalogTier;
import cn.local.tickethelper.catalog.CatalogSelection;
import cn.local.tickethelper.core.UiItem;
import cn.local.tickethelper.core.ReadStability;
import java.util.ArrayList;
import java.util.List;

/** Deliberate, paused-only public catalog navigation. Never selects a price or confirms an order. */
public final class CatalogController implements AutoCloseable {
    private static final String TARGET = "cn.damai";
    private static final String RESULT_CHANNEL = "catalog_result";
    private static final int RESULT_NOTIFICATION = 31;
    private static final long FRAME_LIFETIME = 3000, CHOICE_LIFETIME = 300_000;
    private final MonitorService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AppStore appStore;
    private final OcrReader reader;
    private long generation;
    private long operationStartedAt;
    private long searchReadyDeadline;
    private long operationRound;
    private String operationKind = "none";
    private CatalogHandoff handoff;
    private boolean active;
    private Runnable timeout;
    private String query = "";
    private CatalogProject event;
    private CatalogSession session;
    private boolean inPlace;

    CatalogController(MonitorService service) {
        this.service = service; this.appStore = new AppStore(service); this.reader = new OcrReader(service);
    }
    public static boolean isActive() {
        MonitorService monitor = MonitorService.instance;
        return monitor != null && monitor.catalogController() != null && monitor.catalogController().active;
    }
    private static void invoke(Context context, java.util.function.Consumer<CatalogController> action) {
        Context app = context.getApplicationContext();
        Runnable work = () -> {
            MonitorService monitor = MonitorService.instance;
            if (monitor == null || monitor.catalogController() == null) {
                CatalogStore.Snapshot old = CatalogStore.get();
                CatalogStore.update("BLOCKED", "请先开启票务助手的无障碍服务，再读取大麦。", old.query,
                        old.event, old.session, List.of(), List.of(), List.of(), 0);
                show(app); return;
            }
            action.accept(monitor.catalogController());
        };
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else new Handler(Looper.getMainLooper()).post(work);
    }
    public static void beginSearch(Context context, String query) { invoke(context, c -> c.search(query)); }
    public static void captureCurrent(Context context) { invoke(context, CatalogController::capture); }
    public static void captureCurrent(Context context, String query) {
        invoke(context, c -> {
            String value = query == null ? "" : query.trim();
            if (value.length() > 60 || value.contains("\n") || value.contains("\r")) {
                c.fail("搜索关键词过长或包含换行，请修改后重试。", "invalid_query"); return;
            }
            c.capture(value);
        });
    }
    public static void selectProject(Context context, String key) { invoke(context, c -> c.project(key)); }
    public static void selectSession(Context context, String key) { invoke(context, c -> c.session(key)); }
    /** One deliberate read of the user's current Damai selection; no launcher navigation or taps. */
    void captureSelectedInPlace() {
        inPlace = true;
        query = ""; event = null; session = null;
        if (!begin("in_place", "正在读取大麦中已选中的场次…")) return;
        CatalogHandoff expected = handoff;
        later(250, () -> awaitDamai(expected, () -> read(frame -> {
            CatalogProject observed = CatalogParser.projectFromPage(frame.items, "");
            if (observed == null) {
                fail("请在大麦打开目标演出的场次票价页，再点使用当前场次。", "in_place_project_unverified"); return;
            }
            event = observed;
            String title = observed.label;
            query = title.length() <= 60 ? title : title.substring(0, 60);
            List<CatalogParser.Entry<CatalogSession>> choices = CatalogParser.sessions(frame.items, event);
            List<CatalogParser.Entry<CatalogSession>> selected;
            if (frame.catalogSelection != null) selected = choices.stream().filter(entry ->
                    entry.value.date.equals(frame.catalogSelection.date) && entry.value.time.equals(frame.catalogSelection.time))
                    .collect(java.util.stream.Collectors.toList());
            else if (!ReservationSessionParser.isReservationPage(frame.items)) selected = choices.stream()
                    .filter(entry -> entry.target.selected).collect(java.util.stream.Collectors.toList());
            else selected = List.of();
            if (selected.size() != 1) {
                fail("先在大麦点选一个日期和时间，确认只有一场选中，再点使用当前场次。", "in_place_selection_unverified"); return;
            }
            session = selected.get(0).value;
            readSelectedTiers(frame);
        })));
    }

    boolean openTargetForPanel() { inPlace = true; return openDamai(); }
    public static void cancel(Context context) {
        Runnable work = () -> {
            MonitorService monitor = MonitorService.instance;
            if (monitor != null && monitor.catalogController() != null) monitor.catalogController().cancelInternal(false);
            CatalogStore.Snapshot state = CatalogStore.get();
            if (state.stage.equals("BUSY")) CatalogStore.update("IDLE", "读取已取消；监控保持暂停。", state.query,
                    state.event, state.session, List.of(), List.of(), List.of(), 0);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) work.run();
        else new Handler(Looper.getMainLooper()).post(work);
    }

    private boolean begin(String kind, String message) {
        if (service.isRunning() || appStore.isArmed()) { fail("请先暂停监控，再搜索和选择演出。", "monitor_running"); return false; }
        cancelInternal(false);
        if (!unlocked()) { fail("手机已锁屏，请解锁后重新读取。", "screen_locked"); return false; }
        dismissResultNotification(service);
        active = true;
        operationStartedAt = SystemClock.elapsedRealtime();
        operationKind = kind; operationRound++;
        handoff = new CatalogHandoff(operationStartedAt, 20_000, 500);
        appStore.appendEvent("CATALOG", "operation_started:" + operationKind + ":round=" + operationRound, 0);
        CatalogStore.update("BUSY", message, query, event, session, List.of(), List.of(), List.of(), 0);
        long token = generation;
        timeout = () -> { if (validToken(token)) fail("读取超时，已停止操作；请检查大麦后手动重试。", "catalog_timeout"); };
        handler.postDelayed(timeout, 20_000);
        return true;
    }
    private void search(String text) {
        inPlace = false;
        String value = text == null ? "" : text.trim();
        if (value.length() < 2 || value.length() > 60 || value.contains("\n") || value.contains("\r")) {
            fail("请输入 2 至 60 个字符的演出名或艺人名。", "invalid_query"); return;
        }
        query = value; event = null; session = null;
        if (!begin("search", "正在切回大麦，准备搜索…")) return;
        // Damai's normal launch screen can occupy several seconds. Observe it without
        // dismissing ads or clicking unknown UI, within the overall 20-second budget.
        searchReadyDeadline = SystemClock.elapsedRealtime() + 10_000;
        openAndAwaitDamai(() -> findSearch(0));
    }
    private void capture() {
        capture(CatalogStore.get().query);
    }
    private void capture(String value) {
        inPlace = false;
        query = value; event = null; session = null;
        if (!begin("capture", "正在切回大麦，准备读取当前页面…")) return;
        openAndAwaitDamai(() -> read(frame -> {
            CatalogProject found = CatalogParser.projectFromPage(frame.items, query);
            if (found != null) { event = found; readProjectPage(frame); return; }
            List<CatalogParser.Entry<CatalogProject>> projects = CatalogParser.projects(frame.items, query);
            if (!projects.isEmpty()) { offerProjects(projects); return; }
            fail("当前页面无法确定演出。请在大麦打开项目详情或票档页，再点读取当前页。", "public_project_unverified");
        }));
    }
    private void openAndAwaitDamai(Runnable action) {
        // Normal launcher navigation may restore a different page on an OEM device. This
        // handoff establishes foreground only; the next fresh read still verifies identity.
        CatalogHandoff expected = handoff;
        if (!openDamai()) return;
        later(250, () -> awaitDamai(expected, action));
    }
    private void awaitDamai(CatalogHandoff expected, Runnable action) {
        if (!active || expected == null || handoff != expected) return;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        boolean foreground = root != null && TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
        long now = SystemClock.elapsedRealtime();
        // Returning apps can still finish notification/task-window transitions after
        // their root becomes visible. Keep the original budget while waiting for quiet.
        // Content notifications/advertising must not restart the foreground handoff forever.
        // READY only permits a fresh read, whose semantic/visual and input guards remain below.
        boolean settled = foreground && now - service.catalogLastReadBoundaryElapsed() >= 300;
        switch (expected.observe(now, settled, unlocked(), service.isRunning() || appStore.isArmed())) {
            case WAITING: later(200, () -> awaitDamai(expected, action)); return;
            case READY:
                appStore.appendEvent("CATALOG", "foreground_ready:" + operationKind + ":round=" + operationRound, 0);
                CatalogStore.update("BUSY", "已进入大麦，正在核实当前页面…", query, event, session,
                        List.of(), List.of(), List.of(), 0);
                action.run(); return;
            case TIMED_OUT: fail("等待大麦页面超时，已停止操作。请在大麦打开原页面后重新读取。", "catalog_timeout"); return;
            case STATE_CHANGED: fail("手机已锁屏或监控已启动，读取已停止。", "capture_state_changed"); return;
            default: return;
        }
    }
    private boolean openDamai() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(TARGET);
        android.content.pm.ResolveInfo resolved = service.getPackageManager().resolveActivity(query, 0);
        if (resolved == null || resolved.activityInfo == null || !resolved.activityInfo.enabled
                || !resolved.activityInfo.exported || !TARGET.equals(resolved.activityInfo.packageName)) {
            fail("没有找到大麦 App，请先安装并打开。", "target_unavailable"); return false;
        }
        // Match a normal launcher icon: NEW_TASK alone can add the entry Activity
        // above an existing search/SKU screen. Do not use REORDER_TO_FRONT or CLEAR_TOP.
        Intent launch = Intent.makeMainActivity(new android.content.ComponentName(
                resolved.activityInfo.packageName, resolved.activityInfo.name));
        try { service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); }
        catch (RuntimeException denied) { fail("未能打开大麦，请手动打开后再读取。", "open_target_failed"); return false; }
        return true;
    }

    private void findSearch(int backs) {
        findSearch(backs, 0);
    }
    private void findSearch(int backs, int readAttempt) {
        if (!safePublicPage()) return;
        AccessibilityNodeInfo editor = findSearchEditor(service.getRootInActiveWindow(), 0);
        if (editor != null) { setSearch(editor); return; }
        List<UiItem> nativeItems = service.catalogReadNodes();
        if (nativeSignature(nativeItems) == null && CatalogParser.projectFromPage(nativeItems, query) == null
                && SystemClock.elapsedRealtime() < searchReadyDeadline) {
            // A launch ad/animation has no known public navigation control. Waiting needs no OCR.
            appStore.appendEvent("CATALOG", "search_wait_native:" + readAttempt + ":nodes=" + nativeItems.size(), 0);
            later(650, () -> findSearch(backs, readAttempt + 1)); return;
        }
        read(frame -> {
            long exactSearchLabels = frame.items.stream().filter(item -> item.text.trim().equals("搜索")).count();
            long nodeCount = frame.items.stream().filter(item -> item.source == UiItem.Source.NODE).count();
            long searchResourceNodes = frame.items.stream().filter(item -> item.source == UiItem.Source.NODE
                    && item.resourceId.toLowerCase(java.util.Locale.ROOT).contains("search")).count();
            appStore.appendEvent("CATALOG", "search_read:" + readAttempt + ":nodes=" + nodeCount
                    + ":ocr=" + (frame.items.size() - nodeCount) + ":labels=" + exactSearchLabels + ":ids=" + searchResourceNodes, 0);
            UiItem nativeSearch = CatalogParser.searchEntry(frame.items);
            boolean hasKnownSearch = frame.items.stream().anyMatch(item -> item.source == UiItem.Source.NODE
                    && (item.resourceId.equals("cn.damai:id/pioneer_homepage_header_search_btn")
                    || item.resourceId.equals("cn.damai:id/homepage_header_search_layout")));
            UiItem search = nativeSearch == null && !hasKnownSearch
                    ? uniqueLabel(frame.items, List.of("搜索", "搜索演出、艺人或场馆", "搜索明星、演出、场馆")) : nativeSearch;
            if (search != null) { tap(frame, search, () -> later(700, () -> {
                AccessibilityNodeInfo field = findSearchEditor(service.getRootInActiveWindow(), 0);
                if (field == null) fail("大麦搜索框尚未识别，请在大麦完成搜索，再点读取当前页。", "search_editor_missing");
                else setSearch(field);
            })); return; }
            // Only leave a recognized public project/SKU page. Never navigate blindly from account/payment screens.
            if (backs < 2 && CatalogParser.projectFromPage(frame.items, "") != null) {
                if (!safe() || !service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                    fail("未能返回大麦搜索入口，请手动打开搜索。", "search_back_failed"); return;
                }
                later(650, () -> findSearch(backs + 1)); return;
            }
            if (SystemClock.elapsedRealtime() < searchReadyDeadline) {
                later(Math.min(650, searchReadyDeadline - SystemClock.elapsedRealtime()), () -> findSearch(backs, readAttempt + 1)); return;
            }
            fail("尚未识别大麦搜索入口。请在大麦手动搜索，再点读取当前页继续。", "search_entry_missing");
        });
    }
    private void setSearch(AccessibilityNodeInfo editor) {
        if (!editorSafe(editor)) {
            fail("当前搜索框已变化，请重新搜索。", "search_editor_changed"); return;
        }
        Bundle text = new Bundle(); text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
        if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) {
            fail("大麦未接受搜索文字，请在大麦输入后点读取当前页。", "search_text_rejected"); return;
        }
        later(350, () -> {
            if (!safePublicPage()) return;
            AccessibilityNodeInfo current = findSearchEditor(service.getRootInActiveWindow(), 0);
            if (current == null || !editorSafe(current) || current.getText() == null || !current.getText().toString().trim().equals(query)) {
                fail("搜索文字未能核实，请在大麦检查后重试。", "search_text_unverified"); return;
            }
            // Damai may consume IME_ENTER without leaving its suggestion page. Prefer the
            // exact visible query suggestion when available; its tap receives a fresh guard.
            if (CatalogParser.searchSuggestion(service.catalogReadNodes(), query) != null) {
                readSearchResults(); return;
            }
            // IME search is a normal public UI action; no shell text/keyboard injection or private URL.
            if (editorSafe(current) && current.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                later(1100, this::readSearchResults); return;
            }
            read(frame -> {
                UiItem submit = uniqueLabel(frame.items, List.of("搜索"));
                if (submit == null) { fail("搜索按钮无法核实，请在大麦点搜索后读取当前页。", "search_submit_missing"); return; }
                tap(frame, submit, () -> later(1100, this::readSearchResults));
            });
        });
    }
    private void readSearchResults() {
        readSearchResults(false);
    }
    private void readSearchResults(boolean suggestionSubmitted) {
        read(frame -> {
            if (CatalogParser.hasSearchSuggestions(frame.items)) {
                CatalogParser.SearchSuggestion suggestion = CatalogParser.searchSuggestion(frame.items, query);
                if (suggestionSubmitted || suggestion == null) {
                    fail("搜索仍停在联想页，未能核实唯一的同名搜索选项。请在大麦完成搜索后读取当前页。",
                            suggestionSubmitted ? "search_suggestion_unconfirmed" : "search_suggestion_unverified"); return;
                }
                appStore.appendEvent("CATALOG", "search_suggestion_exact", 0);
                tap(frame, suggestion.target, () -> later(1100, () -> readSearchResults(true))); return;
            }
            List<CatalogParser.Entry<CatalogProject>> projects = CatalogParser.projects(frame.items, query);
            if (projects.isEmpty()) {
                fail("当前可见搜索结果未识别到明确项目。可在大麦滚动或打开目标项目后读取当前页。", "visible_results_empty"); return;
            }
            offerProjects(projects);
        });
    }
    private void offerProjects(List<CatalogParser.Entry<CatalogProject>> entries) {
        finish("PROJECTS", "已读取当前可见的 " + entries.size() + " 个项目；可在大麦滚动后重新读取。",
                entries.stream().map(entry -> entry.value).collect(java.util.stream.Collectors.toList()), List.of(), List.of());
    }
    private void project(String key) {
        inPlace = false;
        CatalogStore.Snapshot previous = CatalogStore.get();
        if (!recent(previous) || !previous.stage.equals("PROJECTS")) { fail("选择结果已过期，请重新搜索。", "stale_project_choice"); return; }
        CatalogProject selected = previous.projects.stream().filter(value -> value.key.equals(key)).findFirst().orElse(null);
        if (selected == null) { fail("未找到这个项目，请重新读取。", "unknown_project_choice"); return; }
        query = previous.query; event = selected; session = null;
        if (!begin("project", "正在切回大麦，准备打开所选项目…")) return;
        openAndAwaitDamai(() -> read(frame -> {
            List<CatalogParser.Entry<CatalogProject>> matches = CatalogParser.projects(frame.items, query).stream()
                    .filter(entry -> entry.value.key.equals(key)).collect(java.util.stream.Collectors.toList());
            if (matches.size() != 1) { fail("大麦返回后的页面没有显示所选项目。请在大麦重新搜索或打开该演出，再读取当前页。", "project_choice_changed"); return; }
            tap(frame, matches.get(0).target, () -> later(1000, () -> read(this::readProjectPage)));
        }));
    }
    private void readProjectPage(Frame frame) {
        CatalogProject observed = CatalogParser.projectFromPage(frame.items, "");
        if (observed == null || event == null || !sameProject(event, observed)) {
            fail("打开后的演出名称无法与所选项目核对，请手动检查大麦。", "project_destination_unverified"); return;
        }
        event = new CatalogProject(event.key, event.label, event.city.isEmpty() ? observed.city : event.city,
                observed.yearHint.isEmpty() ? event.yearHint : observed.yearHint);
        List<CatalogParser.Entry<CatalogSession>> sessions = CatalogParser.sessions(frame.items, event);
        if (!sessions.isEmpty()) { offerSessions(sessions); return; }
        UiItem entry = uniqueLabel(frame.items.stream().filter(item -> item.source == UiItem.Source.NODE)
                .collect(java.util.stream.Collectors.toList()), List.of("立即预订", "立即购买", "选座购买", "选座购票"));
        if (entry == null) {
            boolean reservation = frame.items.stream().anyMatch(item -> item.text.trim().equals("预约抢票"));
            waitForManualEntry(reservation ? "预约抢票" : "页面底部的选票按钮"); return;
        }
        tap(frame, entry, () -> later(950, () -> read(next -> {
            CatalogProject destination = CatalogParser.projectFromPage(next.items, "");
            if (destination == null || !sameProject(event, destination)) {
                fail("场次页演出名称无法核对，已停止操作。", "sessions_project_unverified"); return;
            }
            event = new CatalogProject(event.key, event.label, event.city.isEmpty() ? destination.city : event.city,
                    destination.yearHint.isEmpty() ? event.yearHint : destination.yearHint);
            List<CatalogParser.Entry<CatalogSession>> choices = CatalogParser.sessions(next.items, event);
            if (choices.isEmpty()) { fail("尚未读取到明确场次，请在大麦检查页面后重新读取。", "sessions_unavailable"); return; }
            offerSessions(choices);
        })));
    }
    private void waitForManualEntry(String entryLabel) {
        if (!safePublicPage()) return;
        long now = SystemClock.elapsedRealtime();
        long deadline = CatalogManualEntry.deadline(operationStartedAt, now);
        if (deadline < 0) {
            fail("页面准备耗时较长。请在大麦点" + entryLabel + "打开场次页，再回助手点“读取当前页”继续。", "manual_entry_budget_short"); return;
        }
        if (timeout != null) handler.removeCallbacks(timeout);
        long token = generation;
        timeout = () -> { if (validToken(token)) manualEntryTimeout(); };
        handler.postDelayed(timeout, deadline - now);
        CatalogStore.update("BUSY", "请在大麦点一次" + entryLabel + "打开场次页；助手会继续读取。读取完成后再点结果通知返回。",
                query, event, session, List.of(), List.of(), List.of(), 0);
        appStore.appendEvent("CATALOG", "manual_sessions_entry", 0);
        dismissResultNotification(service);
        Toast.makeText(service, "请在大麦点" + entryLabel + "，助手会继续读取场次", Toast.LENGTH_LONG).show();
        showManualEntryNotice(deadline - now, entryLabel);
        later(500, () -> awaitManualEntry(deadline));
    }
    private void awaitManualEntry(long deadline) {
        if (!safe()) return;
        if (SystemClock.elapsedRealtime() >= deadline) { manualEntryTimeout(); return; }
        // This loop only observes public native UI. It never repeats the project tap or
        // uses an OCR location to click a control absent from the accessibility tree.
        CatalogManualEntry.State state = CatalogManualEntry.inspect(service.catalogReadNodes(), event);
        if (state == CatalogManualEntry.State.RESTRICTED) {
            fail("当前为登录、验证、订单或异常页面，等待已停止；请先在大麦处理。", "manual_entry_restricted"); return;
        }
        if (state == CatalogManualEntry.State.WRONG_PROJECT) {
            fail("当前演出已变化，等待已停止；请重新选择项目。", "manual_entry_project_changed"); return;
        }
        if (state == CatalogManualEntry.State.READY) {
            dismissResultNotification(service);
            CatalogStore.update("BUSY", "已检测到所选演出的场次页，正在核实场次…", query, event, session,
                    List.of(), List.of(), List.of(), 0);
            appStore.appendEvent("CATALOG", "manual_sessions_detected", 0);
            // A native region alone is not session evidence. Re-enter the unchanged
            // full-frame OCR and same-project checks before offering any sessions.
            later(650, () -> read(frame -> {
                if (SystemClock.elapsedRealtime() >= deadline) { manualEntryTimeout(); return; }
                CatalogProject current = CatalogParser.projectFromPage(frame.items, "");
                if (current == null || !sameProject(event, current)) {
                    fail("场次页演出无法与所选项目核对，请重新读取。", "manual_sessions_unverified"); return;
                }
                List<CatalogParser.Entry<CatalogSession>> choices = CatalogParser.sessions(frame.items, current);
                if (choices.isEmpty()) {
                    fail("场次页已打开，但未读取到明确场次。请在当前大麦页检查后，回助手点“读取当前页”继续。",
                            "manual_sessions_unavailable"); return;
                }
                event = new CatalogProject(event.key, event.label, event.city,
                        current.yearHint.isEmpty() ? event.yearHint : current.yearHint);
                offerSessions(choices);
            }));
            return;
        }
        later(500, () -> awaitManualEntry(deadline));
    }
    private void manualEntryTimeout() {
        fail("等待场次页或读取超时，已停止操作。请在当前大麦页打开场次，再回助手点“读取当前页”继续。", "manual_entry_timeout");
    }
    private void showManualEntryNotice(long remaining, String entryLabel) {
        try {
            NotificationManager manager = service.getSystemService(NotificationManager.class);
            if (!manager.areNotificationsEnabled()) return;
            manager.createNotificationChannel(new NotificationChannel(RESULT_CHANNEL, "演出选择结果", NotificationManager.IMPORTANCE_LOW));
            // No PendingIntent here: tapping an instruction must not steal focus from
            // Damai while waiting. The completed result supplies its own return action.
            manager.notify(RESULT_NOTIFICATION, new Notification.Builder(service, RESULT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_ticket).setContentTitle("票务助手 · 请打开场次页")
                    .setContentText("请在大麦点" + entryLabel + "；读取完成后再点结果通知返回助手。")
                    .setStyle(new Notification.BigTextStyle().bigText("请在大麦点一次" + entryLabel + "打开场次页。助手会继续读取；读取完成后再点结果通知返回助手。"))
                    .setOnlyAlertOnce(true).setTimeoutAfter(remaining).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        } catch (RuntimeException unavailable) { appStore.appendEvent("CATALOG", "manual_notice_unavailable", 0); }
    }
    private void offerSessions(List<CatalogParser.Entry<CatalogSession>> entries) {
        boolean incomplete = entries.stream().anyMatch(entry -> entry.value.date.isEmpty() || entry.value.time.isEmpty());
        finish("SESSIONS", incomplete ? "已读取场次；部分场次未显示完整年月日和时间，无法直接应用。"
                        : "请选择一个场次。助手只读取该场可见票档，不选择票价或下单。",
                List.of(), entries.stream().map(entry -> entry.value).collect(java.util.stream.Collectors.toList()), List.of());
    }
    private void session(String key) {
        inPlace = false;
        CatalogStore.Snapshot previous = CatalogStore.get();
        if (!recent(previous) || !previous.stage.equals("SESSIONS")) { fail("场次列表已过期，请重新读取。", "stale_session_choice"); return; }
        CatalogSession selected = previous.sessions.stream().filter(value -> value.key.equals(key)).findFirst().orElse(null);
        if (selected == null || previous.event == null) { fail("未找到这个场次，请重新读取。", "unknown_session_choice"); return; }
        query = previous.query; event = previous.event; session = selected;
        if (!begin("session", "正在切回大麦，准备读取所选场次票档…")) return;
        openAndAwaitDamai(() -> read(frame -> {
            CatalogProject observed = CatalogParser.projectFromPage(frame.items, "");
            if (observed == null || !sameProject(event, observed)) { fail("大麦返回后的页面无法核实所选演出。请重新打开该演出的场次页，再读取当前页。", "session_project_changed"); return; }
            List<CatalogParser.Entry<CatalogSession>> matches = CatalogParser.sessions(frame.items, event).stream()
                    .filter(entry -> entry.value.key.equals(key)).collect(java.util.stream.Collectors.toList());
            if (matches.size() != 1) { fail("大麦返回后未显示所选场次。请展开原演出的场次页，再读取当前页。", "session_choice_changed"); return; }
            // A reservation cell is a toggle. Re-tapping the same, freshly verified
            // selection would deselect it, so read its tiers without another input.
            if (frame.catalogSelection != null && selected.date.equals(frame.catalogSelection.date)
                    && selected.time.equals(frame.catalogSelection.time)) {
                readSelectedTiers(frame); return;
            }
            tap(frame, matches.get(0).target, () -> later(900, () -> read(this::readSelectedTiers)));
        }));
    }
    private void readSelectedTiers(Frame frame) {
        CatalogProject observed = CatalogParser.projectFromPage(frame.items, "");
        if (observed == null || !sameProject(event, observed)) { fail("票档页演出名称无法核对，已停止操作。", "tier_project_changed"); return; }
        // Selection requires same-frame native/visual adapter evidence, not just a successful tap callback.
        boolean selected = frame.catalogSelection != null && session != null && !session.date.isEmpty() && !session.time.isEmpty()
                && session.date.equals(frame.catalogSelection.date) && session.time.equals(frame.catalogSelection.time);
        if (!selected && !ReservationSessionParser.isReservationPage(frame.items)) {
            List<CatalogParser.Entry<CatalogSession>> matches = CatalogParser.sessions(frame.items, event).stream()
                    .filter(entry -> session != null && entry.value.key.equals(session.key) && entry.target.selected).collect(java.util.stream.Collectors.toList());
            selected = matches.size() == 1;
        }
        if (!selected) { fail("所选场次没有显示可核实的选中状态，无法将票档归属于它。请在大麦确认后重读。", "session_selection_unverified"); return; }
        List<CatalogParser.Entry<CatalogTier>> tiers = CatalogParser.tiers(frame.items);
        if (tiers.isEmpty()) { fail("该场次未读到可明确识别的单人票价，请在大麦检查票档。", "tiers_unavailable"); return; }
        finish("TIERS", "已读取当前可见票档。显示票价不代表有票；请选择你愿意接受的票价，再确认应用。",
                List.of(), List.of(), tiers.stream().map(entry -> entry.value).collect(java.util.stream.Collectors.toList()));
    }

    private interface FrameCallback { void accept(Frame frame); }
    private static final class Frame {
        final List<UiItem> items;
        final long capturedAt, pageGeneration;
        final cn.local.tickethelper.core.Observation.VerifiedSelection evidence;
        final cn.local.tickethelper.catalog.CatalogSelection catalogSelection;
        final int nativeWindowId;
        final String nativeSignature;
        Frame(List<UiItem> items, long capturedAt, long pageGeneration,
              cn.local.tickethelper.core.Observation.VerifiedSelection evidence, cn.local.tickethelper.catalog.CatalogSelection catalogSelection,
              int nativeWindowId, String nativeSignature) {
            this.items = items; this.capturedAt = capturedAt; this.pageGeneration = pageGeneration;
            this.evidence = evidence; this.catalogSelection = catalogSelection;
            this.nativeWindowId = nativeWindowId; this.nativeSignature = nativeSignature;
        }
    }
    private void read(FrameCallback callback) { read(callback, 0); }
    private void read(FrameCallback callback, int attempt) {
        if (!safe()) return;
        List<UiItem> nodes = service.catalogReadNodes();
        if (!CatalogParser.blockedReason(nodes).isEmpty()) { fail("当前为登录、验证、订单或异常页面，请先在大麦处理。", "restricted_page"); return; }
        long token = generation, page = service.catalogWindowGeneration(), captured = SystemClock.elapsedRealtime();
        long boundary = service.catalogReadBoundaryGeneration();
        int windowId = service.catalogCurrentReadWindow();
        String signature = nativeSignature(nodes);
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (signature != null && root != null) {
            // Fixed controls can remain valid while unrelated homepage carousels emit content changes.
            // The last input guard re-reads their exact semantic identity, bounds and active window.
            appStore.appendEvent("CATALOG", "read_native:nodes=" + nodes.size(), 0);
            callback.accept(new Frame(nodes, captured, page, null, null, root.getWindowId(), signature));
            return;
        }
        if (reader.isBusy()) { fail("上次页面读取尚未结束，请稍后重试。", "reader_busy"); return; }
        reader.readCatalogGuarded(nodes, System.currentTimeMillis(), (texts, evidence, catalogSelection, proof, error) -> {
            if (!validToken(token)) return;
            if (!safe()) return;
            if (error != null) { fail("大麦当前画面无法读取，请检查是否出现验证或受保护页面。", "reader_unavailable"); return; }
            if (page != service.catalogWindowGeneration() || SystemClock.elapsedRealtime() - captured > FRAME_LIFETIME) {
                long currentPage = service.catalogWindowGeneration();
                if (ReadStability.mayRecheck(page, currentPage, boundary, service.catalogReadBoundaryGeneration(),
                        windowId, service.catalogCurrentReadWindow(), SystemClock.elapsedRealtime() - captured)
                        && ReadStability.sameNative(nodes, service.catalogReadNodes())) {
                    reader.verifyFrame(proof, () -> validToken(token) && unlocked() && !service.isRunning() && !appStore.isArmed()
                                    && currentPage == service.catalogWindowGeneration() && windowId == service.catalogCurrentReadWindow()
                                    && boundary == service.catalogReadBoundaryGeneration()
                                    && SystemClock.elapsedRealtime() - captured < FRAME_LIFETIME,
                            matches -> {
                                if (!validToken(token) || !safe()) return;
                                if (matches && currentPage == service.catalogWindowGeneration()
                                        && boundary == service.catalogReadBoundaryGeneration()
                                        && windowId == service.catalogCurrentReadWindow()
                                        && SystemClock.elapsedRealtime() - captured < FRAME_LIFETIME
                                        && ReadStability.sameNative(nodes, service.catalogReadNodes())) {
                                    appStore.appendEvent("READ_RECOVERY", "read_content_revalidated", SystemClock.elapsedRealtime() - captured);
                                    deliverOcrFrame(callback, nodes, texts, evidence, catalogSelection, captured, currentPage);
                                } else retryCatalogRead(callback, attempt);
                            });
                } else retryCatalogRead(callback, attempt);
                return;
            }
            deliverOcrFrame(callback, nodes, texts, evidence, catalogSelection, captured, page);
        });
    }

    private void retryCatalogRead(FrameCallback callback, int attempt) {
        if (!safe()) return;
        if (attempt >= 3 || SystemClock.elapsedRealtime() - operationStartedAt >= 15_000) {
            fail("页面信息仍在变化，未读取成功。请停留在目标场次页，再点“使用当前场次”。", "frame_changed"); return;
        }
        CatalogStore.update("BUSY", "页面正在更新，正在重新核对（" + (attempt + 1) + "/3）…", query, event, session,
                List.of(), List.of(), List.of(), 0);
        appStore.appendEvent("READ_RECOVERY", "read_recovery_waiting", attempt + 1);
        later(550 + attempt * 200L, () -> read(callback, attempt + 1));
    }

    private void deliverOcrFrame(FrameCallback callback, List<UiItem> nodes, List<UiItem> texts,
                                 cn.local.tickethelper.core.Observation.VerifiedSelection evidence,
                                 CatalogSelection catalogSelection, long captured, long page) {
        if (!safe()) return;
        if (page != service.catalogWindowGeneration() || SystemClock.elapsedRealtime() - captured >= FRAME_LIFETIME) return;
        List<UiItem> all = new ArrayList<>(nodes);
        for (UiItem text : texts) {
            boolean duplicate = nodes.stream().anyMatch(node -> node.text.equals(text.text)
                    && Math.abs(node.centerY() - text.centerY()) < 30 && Math.abs(node.centerX() - text.centerX()) < 50);
            if (!duplicate) all.add(text);
        }
        if (!CatalogParser.blockedReason(all).isEmpty()) { fail("当前为登录、验证、订单或异常页面，请先在大麦处理。", "restricted_page"); return; }
        // captured and typed selection evidence keep their original times after visual validation.
        callback.accept(new Frame(all, captured, page, evidence, catalogSelection, -1, null));
    }

    /** One bounded tap, then a new read must verify its effect. No stored coordinates and no retry. */
    private void tap(Frame frame, UiItem target, Runnable after) {
        if (frame.nativeSignature != null && frame.nativeSignature.startsWith("detail_read_only:")) {
            fail("当前详情仅完成身份读取，请在大麦手动打开场次页。", "read_only_frame_input_rejected"); return;
        }
        if (!frameSafe(frame) || !target.hasBounds() || !target.enabled) { fail("页面已变化，已取消这次点击，请重新读取。", "tap_stale"); return; }
        AccessibilityNodeInfo node = findNode(service.getRootInActiveWindow(), target, 0);
        if (node == null || !node.refresh() || sensitive(node)) { fail("当前控件无法核实，已取消点击。", "tap_node_unverified"); return; }
        Rect expected = new Rect(); node.getBoundsInScreen(expected);
        int windowId = node.getWindowId();
        if (!expected.contains(target.centerX(), target.centerY())) { fail("当前控件位置已变化，已取消点击。", "tap_bounds_changed"); return; }
        long token = generation;
        GuardAudit guardAudit = new GuardAudit();
        ShizukuInputBridge.DispatchGuard guard = () -> {
            guardAudit.ran = true;
            long began = SystemClock.elapsedRealtime(), stage = began;
            try {
                if (!validToken(token)) return guardAudit.reject("generation_changed");
                if (!frameSafe(frame)) {
                    guardAudit.frameMs = SystemClock.elapsedRealtime() - stage;
                    return guardAudit.reject("frame_or_foreground_changed");
                }
                guardAudit.frameMs = SystemClock.elapsedRealtime() - stage; stage = SystemClock.elapsedRealtime();
                if (!node.refresh()) return guardAudit.reject("node_refresh_failed");
                if (!node.isVisibleToUser() || !node.isEnabled()) return guardAudit.reject("node_inactive");
                if (node.getWindowId() != windowId) return guardAudit.reject("window_id_changed");
                if (sensitive(node)) return guardAudit.reject("sensitive_ancestor");
                if (!target.resourceId.isEmpty() && !target.resourceId.equals(node.getViewIdResourceName()))
                    return guardAudit.reject("node_id_changed");
                Rect actual = new Rect(); node.getBoundsInScreen(actual);
                AccessibilityWindowInfo window = node.getWindow();
                if (!expected.equals(actual)) return guardAudit.reject("bounds_changed");
                if (window == null || !window.isActive() || window.getDisplayId() != Display.DEFAULT_DISPLAY)
                    return guardAudit.reject("active_display_changed");
                guardAudit.nodeMs = SystemClock.elapsedRealtime() - stage; stage = SystemClock.elapsedRealtime();
                List<UiItem> currentItems = service.catalogReadNodes();
                guardAudit.readMs = SystemClock.elapsedRealtime() - stage; stage = SystemClock.elapsedRealtime();
                if (!CatalogParser.blockedReason(currentItems).isEmpty()) return guardAudit.reject("restricted_page");
                if (frame.nativeSignature != null && !frame.nativeSignature.equals(nativeSignature(currentItems)))
                    return guardAudit.reject("native_semantics_changed");
                guardAudit.signatureMs = SystemClock.elapsedRealtime() - stage;
                guardAudit.reason = "allowed";
                return true;
            } catch (RuntimeException failure) {
                return guardAudit.reject("guard_exception");
            } finally { guardAudit.totalMs = SystemClock.elapsedRealtime() - began; }
        };
        if (AppStore.INPUT_SHIZUKU.equals(appStore.getInputMode())) {
            ShizukuInputBridge bridge = ShizukuInputBridge.get(service);
            if (!bridge.isReady()) { fail("本地调试输入未连接，请在助手连接后重试。", "input_unavailable"); return; }
            long remaining = FRAME_LIFETIME - (SystemClock.elapsedRealtime() - frame.capturedAt);
            bridge.tapCatalog(target.centerX(), target.centerY(), SystemClock.elapsedRealtime() + Math.min(1800, remaining), guard, result -> {
                appStore.appendEvent("CATALOG_INPUT", result.code, 0);
                recordGuard(guardAudit);
                if (!validToken(token)) return;
                if (!result.success) { fail("大麦点击未能确认完成，已停止；请检查手机后重新读取。", "tap_unconfirmed"); return; }
                after.run();
            });
            return;
        }
        boolean permitted = guard.allow(); recordGuard(guardAudit);
        if (!permitted) { fail("点击前页面发生变化，已停止操作。", "tap_guard_rejected"); return; }
        // Use ACTION_CLICK first for standard search/detail widgets, then verify. No fallback if accepted.
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { after.run(); return; }
        Path path = new Path(); path.moveTo(target.centerX(), target.centerY());
        GestureDescription gesture = new GestureDescription.Builder().setDisplayId(Display.DEFAULT_DISPLAY)
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 70)).build();
        boolean accepted = service.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) { if (validToken(token)) after.run(); }
            @Override public void onCancelled(GestureDescription description) {
                if (validToken(token)) fail("大麦点击已取消，请重新读取。", "gesture_cancelled");
            }
        }, handler);
        if (!accepted) fail("当前无法点击大麦，请检查输入方式后重试。", "gesture_rejected");
    }
    private static final class GuardAudit {
        boolean ran;
        String reason = "not_run";
        long totalMs, frameMs, nodeMs, readMs, signatureMs;
        boolean reject(String code) { reason = code; return false; }
    }
    private void recordGuard(GuardAudit audit) {
        appStore.appendEvent("CATALOG_GUARD", audit.reason + ":frame=" + audit.frameMs + ":node=" + audit.nodeMs
                + ":read=" + audit.readMs + ":semantic=" + audit.signatureMs, audit.totalMs);
    }

    private boolean frameSafe(Frame frame) {
        if (!safe()) return false;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        boolean samePage = frame.nativeSignature == null ? service.catalogWindowGeneration() == frame.pageGeneration
                : root != null && root.getWindowId() == frame.nativeWindowId;
        long age = SystemClock.elapsedRealtime() - frame.capturedAt;
        if (!samePage) appStore.appendEvent("CATALOG_FRAME", "page_changed:" + service.catalogLastWindowEventSummary()
                + ":delta=" + (service.catalogWindowGeneration() - frame.pageGeneration), age);
        else if (age < 0 || age >= FRAME_LIFETIME) appStore.appendEvent("CATALOG_FRAME", "frame_expired", age);
        return samePage && age >= 0 && age < FRAME_LIFETIME;
    }
    /** A bounded native-only contract; SKU/session reads always retain the full same-frame OCR guard. */
    private String nativeSignature(List<UiItem> nodes) {
        if (!CatalogParser.blockedReason(nodes).isEmpty()) return null;
        if (nodes.stream().anyMatch(item -> item.resourceId.equals("cn.damai:id/project_detail_perform_flowlayout")
                || item.resourceId.equals("cn.damai:id/project_detail_perform_price_flowlayout"))) return null;
        // An overlay may leave homepage controls visible behind it. Never let those
        // controls weaken the query-and-target contract for a suggestion action.
        if (CatalogParser.hasSearchSuggestions(nodes)) {
            CatalogParser.SearchSuggestion suggestion = CatalogParser.searchSuggestion(nodes, query);
            return suggestion == null ? null : "suggestion:" + query + '|' + identity(suggestion.scope)
                    + '|' + identity(suggestion.list) + '|' + identity(suggestion.editor) + '|' + identity(suggestion.target);
        }
        UiItem search = CatalogParser.searchEntry(nodes);
        if (search != null) return "home:" + identity(search);
        boolean resultPage = nodes.stream().anyMatch(item -> item.resourceId.equals("cn.damai:id/search_v2_container")
                || item.resourceId.equals("cn.damai:id/header_search_v2_input"));
        List<CatalogParser.Entry<CatalogProject>> projects = resultPage ? CatalogParser.projects(nodes, query) : List.of();
        if (!projects.isEmpty()) {
            StringBuilder signature = new StringBuilder("projects:");
            for (CatalogParser.Entry<CatalogProject> project : projects) signature.append(project.value.key).append('|').append(identity(project.target)).append(';');
            return signature.toString();
        }
        CatalogProject project = CatalogParser.projectFromPage(nodes, query);
        UiItem entry = uniqueLabel(nodes, List.of("立即预订", "立即购买", "选座购买", "选座购票"));
        if (project != null && entry != null && entry.source == UiItem.Source.NODE)
            return "detail:" + project.key + '|' + project.city + '|' + identity(entry);
        if (project != null && nodes.stream().anyMatch(item -> item.source == UiItem.Source.NODE && item.hasBounds()
                && (item.resourceId.equals("cn.damai:id/info_v2_title_tv1") || item.resourceId.equals("cn.damai:id/info_v2_title_tv2"))))
            return "detail_read_only:" + project.key + '|' + project.city;
        return null;
    }
    private static String identity(UiItem item) {
        return item.resourceId + ':' + item.text + ':' + item.left + ',' + item.top + ',' + item.right + ',' + item.bottom;
    }
    private boolean safe() {
        if (!active) return false;
        if (service.isRunning() || appStore.isArmed()) { fail("监控已启动，演出选择操作已停止。", "monitor_started"); return false; }
        if (!unlocked()) { fail("手机已锁屏，演出选择操作已停止。", "screen_locked"); return false; }
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || !TARGET.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
            fail("当前不在大麦，演出选择操作已停止；请回大麦后重新读取。", "foreground_changed"); return false;
        }
        return true;
    }
    private boolean safePublicPage() {
        if (!safe()) return false;
        if (!CatalogParser.blockedReason(service.catalogReadNodes()).isEmpty()) {
            fail("当前为登录、验证、订单或异常页面，请先在大麦处理。", "restricted_page"); return false;
        }
        return true;
    }
    private boolean editorSafe(AccessibilityNodeInfo editor) {
        if (!safePublicPage() || editor == null || !editor.refresh() || !isSearchEditor(editor)
                || sensitive(editor) || !TARGET.contentEquals(editor.getPackageName() == null ? "" : editor.getPackageName())) return false;
        AccessibilityWindowInfo window = editor.getWindow();
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root != null && root.getWindowId() == editor.getWindowId()
                && window != null && window.isActive() && window.getDisplayId() == Display.DEFAULT_DISPLAY;
    }
    private boolean unlocked() {
        return service.getSystemService(PowerManager.class).isInteractive()
                && !service.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private boolean validToken(long token) { return active && token == generation; }
    private void later(long delay, Runnable action) {
        long token = generation;
        handler.postDelayed(() -> { if (validToken(token)) action.run(); }, delay);
    }
    private void finish(String stage, String status, List<CatalogProject> projects, List<CatalogSession> sessions, List<CatalogTier> tiers) {
        active = false; generation++;
        if (handoff != null) { handoff.cancel(); handoff = null; }
        if (timeout != null) { handler.removeCallbacks(timeout); timeout = null; }
        CatalogStore.update(stage, status, query, event, session, projects, sessions, tiers, System.currentTimeMillis());
        appStore.appendEvent("CATALOG", "public_" + stage.toLowerCase(java.util.Locale.ROOT), 0);
        deliverResult();
    }
    private void fail(String reason, String code) {
        if (active) appStore.appendEvent("CATALOG", "operation_failed:" + operationKind + ":round=" + operationRound + ":reason=" + code, 0);
        cancelInternal(false);
        CatalogStore.update("BLOCKED", reason, query, event, session, List.of(), List.of(), List.of(), 0);
        appStore.appendEvent("CATALOG", code, 0);
        deliverResult();
    }
    private void deliverResult() {
        if (inPlace) {
            dismissResultNotification(service);
            service.deliverInPlaceCatalog(CatalogStore.get());
        } else show(service);
    }
    void cancelInternal(boolean announce) {
        boolean wasActive = active;
        active = false; generation++;
        if (handoff != null) { handoff.cancel(); handoff = null; }
        handler.removeCallbacksAndMessages(null); timeout = null;
        if (wasActive) ShizukuInputBridge.get(service).cancelPending();
        if (wasActive && !announce) CatalogStore.update("IDLE", "读取已取消；监控保持暂停。", query, event, session,
                List.of(), List.of(), List.of(), 0);
        if (announce && wasActive) {
            CatalogStore.update("BLOCKED", "页面选择已取消，请重新读取。", query, event, session, List.of(), List.of(), List.of(), 0);
            deliverResult();
        }
    }
    @Override public void close() { cancelInternal(false); reader.close(); }
    public static void dismissResultNotification(Context context) {
        context.getSystemService(NotificationManager.class).cancel(RESULT_NOTIFICATION);
    }
    private static void show(Context context) {
        Intent intent = new Intent().setClassName(context.getPackageName(), context.getPackageName() + ".CatalogActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        AppStore audit = new AppStore(context);
        // Android/OEM background launch policy can decline startActivity without throwing.
        // A user-tapped, direct Activity PendingIntent remains the explicit supported return path.
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager.areNotificationsEnabled()) {
                manager.createNotificationChannel(new NotificationChannel(RESULT_CHANNEL, "演出选择结果", NotificationManager.IMPORTANCE_LOW));
                PendingIntent open = PendingIntent.getActivity(context, RESULT_NOTIFICATION, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                CatalogStore.Snapshot snapshot = CatalogStore.get();
                String message = snapshot.stage.equals("BLOCKED") ? "读取需要处理；点此回助手查看。" : "读取已完成；点此继续选择演出和场次。";
                Notification notice = new Notification.Builder(context, RESULT_CHANNEL).setSmallIcon(R.drawable.ic_ticket)
                        .setContentTitle("票务助手 · 演出选择").setContentText(message).setContentIntent(open)
                        .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
                manager.notify(RESULT_NOTIFICATION, notice);
            }
        } catch (RuntimeException unavailable) { audit.appendEvent("CATALOG", "return_notification_unavailable", 0); }
        try {
            context.startActivity(intent);
            audit.appendEvent("CATALOG", "return_activity_requested", 0);
        } catch (RuntimeException denied) { audit.appendEvent("CATALOG", "return_activity_denied", 0); }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            MonitorService monitor = MonitorService.instance;
            AccessibilityNodeInfo root = monitor == null ? null : monitor.getRootInActiveWindow();
            boolean helperVisible = root != null && context.getPackageName().contentEquals(root.getPackageName() == null ? "" : root.getPackageName());
            audit.appendEvent("CATALOG", helperVisible ? "return_helper_visible" : "return_waiting_user", 0);
        }, 800);
    }
    private static boolean recent(CatalogStore.Snapshot snapshot) {
        long age = System.currentTimeMillis() - snapshot.readAtMs;
        return snapshot.readAtMs > 0 && age >= 0 && age <= CHOICE_LIFETIME;
    }
    private static boolean sameProject(CatalogProject expected, CatalogProject current) {
        return CatalogParser.sameProject(expected, current);
    }
    private static UiItem uniqueLabel(List<UiItem> items, List<String> labels) {
        List<UiItem> found = new ArrayList<>();
        for (UiItem item : items) {
            if (!item.hasBounds() || !item.enabled || !labels.contains(item.text.trim())) continue;
            boolean duplicate = found.stream().anyMatch(prior -> prior.text.equals(item.text)
                    && Math.abs(prior.centerX() - item.centerX()) < 50 && Math.abs(prior.centerY() - item.centerY()) < 30);
            if (!duplicate) found.add(item);
        }
        return found.size() == 1 ? found.get(0) : null;
    }
    private static boolean isSearchEditor(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser() || !node.isEnabled() || !node.isEditable()) return false;
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName().toLowerCase(java.util.Locale.ROOT);
        String hint = node.getHintText() == null ? "" : node.getHintText().toString();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        return !node.isPassword() && (id.contains("search") || hint.contains("搜索") || desc.contains("搜索"));
    }
    private static AccessibilityNodeInfo findSearchEditor(AccessibilityNodeInfo node, int depth) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collectSearchEditors(node, depth, found);
        return found.size() == 1 ? found.get(0) : null;
    }
    private static void collectSearchEditors(AccessibilityNodeInfo node, int depth, List<AccessibilityNodeInfo> found) {
        if (node == null || depth > 45 || found.size() > 1) return;
        if (isSearchEditor(node)) found.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            collectSearchEditors(node.getChild(i), depth + 1, found);
        }
    }
    private static boolean sensitive(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 50; depth++, current = current.getParent()) {
            if (current.isPassword() || (Build.VERSION.SDK_INT >= 34 && current.isAccessibilityDataSensitive())) return true;
        }
        return current != null;
    }
    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo node, UiItem target, int depth) {
        if (node == null || depth > 45) return null;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        String text = node.getText() == null ? "" : node.getText().toString().trim();
        if (text.isEmpty() && node.getContentDescription() != null) text = node.getContentDescription().toString().trim();
        boolean idMatches = !target.resourceId.isEmpty() && target.resourceId.equals(node.getViewIdResourceName());
        boolean exactBounds = bounds.left == target.left && bounds.top == target.top
                && bounds.right == target.right && bounds.bottom == target.bottom;
        boolean labelMatches = text.equals(target.text) && Math.abs(bounds.centerX() - target.centerX()) < 8
                && Math.abs(bounds.centerY() - target.centerY()) < 8;
        boolean identityMatches = target.resourceId.isEmpty() ? labelMatches : idMatches && exactBounds;
        if (node.isVisibleToUser() && node.isEnabled() && identityMatches) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNode(node.getChild(i), target, depth + 1);
            if (found != null) return found;
        }
        // A bounded OCR label may use its small enclosing native touch container; never a screen-sized root.
        if (target.source == UiItem.Source.OCR && target.resourceId.isEmpty() && node.isVisibleToUser() && node.isEnabled()
                && bounds.contains(target.left, target.top, target.right, target.bottom)
                && bounds.width() <= Math.max(target.right - target.left, 1) * 3
                && bounds.height() <= Math.max(target.bottom - target.top, 1) * 4
                && bounds.width() < 1400 && bounds.height() < 700 && node.isClickable()) return node;
        return null;
    }
}
