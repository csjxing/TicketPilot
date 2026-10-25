package cn.local.tickethelper;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.core.HomeStep;
import cn.local.tickethelper.checkout.LockAuthorization;
import cn.local.tickethelper.checkout.OrderAttemptStore;
import cn.local.tickethelper.checkout.OrderCheckoutAdapter;
import java.io.OutputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** All controls run locally. The real Damai app remains the only purchase interface. */
public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 248), CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(23, 38, 53), MUTED = Color.rgb(88, 101, 117);
    private static final int ACCENT = Color.rgb(23, 107, 91), BORDER = Color.rgb(222, 228, 234);
    private static final int EXPORT_REQUEST = 41, NOTIFICATION_REQUEST = 42, SHIZUKU_REQUEST = 43;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private AppStore store;
    private TextView stateBadge, statusText, historyText, permissionText, inputStatusText, targetSummary, orderBarrierText, orderCapabilityText, preparedHelp;
    private RadioButton alertMode, prepareMode, lockMode;
    private RadioGroup behaviorModes;
    private Button startButton, directStartButton, shizukuPermissionButton, shizukuConnectButton, advancedTargetButton;
    private LinearLayout advancedTargetFields, viewerFields;
    private EditText viewerName, viewerSuffix;
    private boolean advancedExpanded, restoringFields, activityResumed;
    private boolean unsavedChanges, startAfterDialog;
    private long displayedConfigRevision;
    private LinearLayout taskPage, settingsPage, resourcesPage, shizukuPanel;
    private final List<Button> navigationButtons = new ArrayList<>();
    private ScrollView contentScroll;
    private TextView nextStepText, draftText, accessSummary;
    private Button pauseButton, saveButton;
    private int selectedTab;
    private long uiRevision;
    private AlertDialog orderConfirmation;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { refreshStatus(); handler.postDelayed(this, 1200); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AppStore(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout shell = column(); shell.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this); contentScroll = scroll;
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout pages = column(); pages.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(pages);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        taskPage = column(); settingsPage = column(); resourcesPage = column();
        pages.addView(taskPage); pages.addView(settingsPage); pages.addView(resourcesPage);
        LinearLayout navigation = row(); navigation.setPadding(dp(10), dp(7), dp(10), dp(7));
        navigation.setBackgroundColor(CARD);
        String[] tabs = {"任务", "设置", "使用与维护"};
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            Button item = button(tabs[i], false, v -> showTab(tab));
            navigationButtons.add(item); navigation.addView(item, i == 0 ? weighted() : weightedWithGap());
        }
        shell.addView(navigation);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(shell);
        LinearLayout page = taskPage;

        addText(page, "票务助手", 28, TEXT, true, 0);
        addText(page, "选好演出，再开始关注余票", 14, MUTED, false, 4);
        addText(settingsPage, "设置", 28, TEXT, true, 0);
        addText(settingsPage, "每项都显示当前状态，完成后回到任务页。", 14, MUTED, false, 4);
        addText(resourcesPage, "使用与维护", 28, TEXT, true, 0);
        addText(resourcesPage, "操作说明和开发资料都在手机里，离线可读。", 14, MUTED, false, 4);

        LinearLayout live = card(page, 18);
        stateBadge = text("未启动", 12, ACCENT, true);
        live.addView(stateBadge);
        nextStepText = text("", 20, TEXT, true); live.addView(nextStepText, spaced(10));
        statusText = text("", 13, MUTED, false);
        live.addView(statusText, spaced(10));
        directStartButton = button("选择演出", true, v -> homePrimaryAction());
        live.addView(directStartButton, spaced(14));
        addText(live, "监控时保持屏幕亮着，并停留在大麦。", 12, MUTED, false, 10);

        LinearLayout access = card(settingsPage, 18);
        section(access, "1", "让助手能读取页面、提醒你");
        permissionText = text("", 13, MUTED, false);
        access.addView(permissionText, spaced(12));
        addText(access, "只开启“票务助手 · 界面读取”，不需要开启 TalkBack。", 13, MUTED, false, 8);
        LinearLayout permissionButtons = row();
        permissionButtons.addView(button("设置页面读取", false, v -> showAccessibilityDisclosure()), weighted());
        Button notify = button("允许通知", false, v -> requestNotifications());
        permissionButtons.addView(notify, weightedWithGap());
        access.addView(permissionButtons, spaced(12));
        access.addView(button("看权限设置步骤", false, v -> openGuide("permissions")), spaced(8));
        access.addView(button("打开大麦，确认已登录", false, v -> openDamai()), spaced(8));

        addText(access, "2  /  选择点击方式", 16, TEXT, true, 20);
        RadioGroup inputModes = new RadioGroup(this);
        inputModes.setOrientation(RadioGroup.VERTICAL);
        RadioButton accessibilityInput = radio("标准触控（无障碍）", AppStore.INPUT_ACCESSIBILITY);
        RadioButton shizukuInput = radio("兼容触控（Shizuku）", AppStore.INPUT_SHIZUKU);
        inputModes.addView(accessibilityInput);
        inputModes.addView(shizukuInput);
        inputModes.check(AppStore.INPUT_SHIZUKU.equals(store.getInputMode())
                ? shizukuInput.getId() : accessibilityInput.getId());
        inputModes.setOnCheckedChangeListener((group, checkedId) ->
                changeInputMode(checkedId == shizukuInput.getId()
                        ? AppStore.INPUT_SHIZUKU : AppStore.INPUT_ACCESSIBILITY));
        access.addView(inputModes, spaced(8));
        addText(access, "这台 vivo 的选票测试使用兼容触控通过。换手机后仍需重新测试，切换方式会暂停任务。", 12, MUTED, false, 6);
        inputStatusText = text("", 13, ACCENT, false);
        access.addView(inputStatusText, spaced(10));
        shizukuPanel = column(); access.addView(shizukuPanel, spaced(10));
        shizukuPanel.addView(button("① 打开 Shizuku，确认已启动", false, v -> openShizuku()));
        LinearLayout shizukuButtons = column();
        shizukuPermissionButton = button("② 允许票务助手使用", false, v -> requestShizukuPermission());
        shizukuConnectButton = button("③ 连接点击服务", false, v -> connectShizuku());
        shizukuButtons.addView(shizukuPermissionButton, spaced(8));
        shizukuButtons.addView(shizukuConnectButton, spaced(8));
        shizukuPanel.addView(shizukuButtons);
        addText(shizukuPanel, "完成后回到“任务”。连接成功不会自动开始监控。", 12, MUTED, false, 8);

        LinearLayout config = card(page, 14);
        section(config, "1", "关注哪场演出");
        targetSummary = text("", 14, TEXT, false);
        config.addView(targetSummary, spaced(12));
        config.addView(button("选择 / 更换演出", false, v -> openCatalog()), spaced(14));
        advancedTargetButton = button("手动修改目标 ▾", false, v -> setAdvancedExpanded(!advancedExpanded));
        config.addView(advancedTargetButton, spaced(10));
        advancedTargetFields = column();
        config.addView(advancedTargetFields, spaced(2));
        field(advancedTargetFields, "event", "演出关键词", "填写页面上可见的准确名称", false);
        paired(advancedTargetFields, "city", "城市", "例如：杭州", false, "date", "演出日期", "2026-12-31", false);
        paired(advancedTargetFields, "time", "开演时间", "19:30", false, "quantity", "张数", "当前版本仅支持 1 张", true);
        field(advancedTargetFields, "prices", "接受票价（元）", "例如：980,1380,1680", false);
        field(advancedTargetFields, "maxTotal", "订单实付上限（元，含全部费用）", "填写最高可接受的订单总额", true);
        field(advancedTargetFields, "deadline", "什么时候停止（北京时间）", "点击选择日期与时间", false);
        setupDateTimePickers();
        addText(advancedTargetFields, "修改后点“保存修改”，或直接开始时保存。金额以元为单位。", 12, MUTED, false, 8);
        setAdvancedExpanded(false);

        LinearLayout rhythm = card(settingsPage, 14);
        section(rhythm, "3", "检查节奏");
        rhythm.addView(button("使用常用节奏 · 5–10 秒", false, v -> setRhythm(5, 10)), spaced(10));
        rhythm.addView(button("使用较缓节奏 · 10–20 秒", false, v -> setRhythm(10, 20)), spaced(8));
        paired(rhythm, "minSeconds", "最短间隔（秒）", "5", true,
                "maxSeconds", "最长间隔（秒）", "10", true);
        addText(rhythm, "在区间内动态等待，最短 3 秒。页面加载和识别另计；不会绕过验证码或平台限制。", 12, MUTED, false, 8);

        LinearLayout behavior = card(page, 14);
        section(behavior, "2", "发现有票后做什么");
        behaviorModes = new RadioGroup(this);
        behaviorModes.setOrientation(RadioGroup.VERTICAL);
        alertMode = radio("提醒我，由我继续操作", "ALERT");
        prepareMode = radio("帮我选票，到确认页停下", "PREPARE");
        lockMode = radio("自动锁单 · 当前未开放", "LOCK_ORDER");
        behaviorModes.addView(alertMode);
        behaviorModes.addView(prepareMode);
        behaviorModes.addView(lockMode);
        behavior.addView(behaviorModes, spaced(10));
        lockMode.setEnabled(OrderCheckoutAdapter.isLiveVerified());
        addText(behavior, "到确认页后仍需你提交订单。看到票档或选票成功，都不等于锁票。", 13, MUTED, false, 6);
        behavior.addView(button("了解当前能做什么", false, v -> openGuide("capabilities")), spaced(10));
        orderCapabilityText = text("", 13, ACCENT, true); behavior.addView(orderCapabilityText, spaced(10));
        viewerFields = column(); behavior.addView(viewerFields, spaced(8));
        viewerName = field(viewerFields, "viewerName", "观演人姓名（仅本机核对）", "请按大麦实名名单填写", false);
        viewerName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        viewerName.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        viewerSuffix = field(viewerFields, "viewerSuffix", "证件尾号（选填，2–6 位）", "不填写完整证件号", false);
        // Validate the whole value on save; never silently truncate a full document number into a wrong suffix.
        viewerSuffix.setFilters(new InputFilter[]{new InputFilter.AllCaps()});
        viewerName.setSaveEnabled(false); viewerSuffix.setSaveEnabled(false);
        addText(viewerFields, "先在真实大麦完成实名添加。此处只保存姓名及可选尾号用于核对，不填写或保存完整证件号，不写入通知和检查记录。同名或显示不完整时交由你处理。", 12, MUTED, false, 8);
        orderBarrierText = text("", 13, ACCENT, false); behavior.addView(orderBarrierText, spaced(12));

        draftText = text("", 13, ACCENT, true); page.addView(draftText, spaced(14));
        saveButton = button("保存修改", false, v -> saveOnly()); page.addView(saveButton, spaced(8));
        LinearLayout ready = card(page, 14);
        accessSummary = text("", 13, MUTED, false); ready.addView(accessSummary);
        ready.addView(button("查看设置", false, v -> showTab(1)), spaced(10));
        pauseButton = button("暂停监控", false, v -> pauseMonitoring());
        page.addView(pauseButton, spaced(10));

        settingsPage.addView(button("保存节奏与目标修改", true, v -> saveOnly()), spaced(16));
        settingsPage.addView(button("回到任务", false, v -> showTab(0)), spaced(10));

        LinearLayout guide = card(resourcesPage, 18);
        addText(guide, "随时查，不用重新问一遍", 19, TEXT, true, 0);
        addText(guide, "从第一次使用到异常处理，按关键词搜索。", 14, MUTED, false, 8);
        guide.addView(button("打开离线使用指南", true, v -> openGuide(null)), spaced(14));
        guide.addView(button("遇到问题 · 查看排查步骤", false, v -> openGuide("troubleshooting")), spaced(8));
        LinearLayout local = card(resourcesPage, 14);
        addText(local, "这台手机单独运行", 19, TEXT, true, 0);
        addText(local, "日常读取、判断和点击由手机执行。电脑用于维护；脱离电脑的完整选票流程仍待实测。", 14, MUTED, false, 8);
        local.addView(button("查看单机使用步骤", false, v -> openGuide("move-phone")), spaced(14));
        LinearLayout transfer = card(resourcesPage, 14);
        addText(transfer, "换电脑继续开发", 19, TEXT, true, 0);
        addText(transfer, "把当前工程、知识库和接手说明交给 Codex 等工具。连接的仍是这台 vivo，不需要更换手机。", 14, MUTED, false, 8);
        transfer.addView(button("电脑接手与备份", true, v -> openTransfer()), spaced(14));
        transfer.addView(button("开发进展与后续交接", false, v -> openGuide("developer-handoff")), spaced(8));
        LinearLayout records = card(resourcesPage, 14);
        addText(records, "检查记录", 18, TEXT, true, 0);
        historyText = text("", 13, MUTED, false); records.addView(historyText, spaced(10));
        records.addView(button("导出检查记录", false, v -> exportHistory()), spaced(10));
        LinearLayout alternate = card(resourcesPage, 14);
        addText(alternate, "其他启动方式", 16, TEXT, true, 0);
        startButton = button("通过通知启动（备用）", false, v -> startMonitoring());
        alternate.addView(startButton, spaced(10));
        preparedHelp = text("备用入口仅用于提醒或到确认页暂停：打开目标票档后，下拉通知点“开始监控”。通知正文只打开助手，不授权自动下单。", 12, MUTED, false);
        alternate.addView(preparedHelp, spaced(8));
        addText(resourcesPage, "v0.1.10 体验版 · 独立辅助工具\n日常检查与知识库均在本机运行。", 12, MUTED, false, 20);
        restoreConfig(store.loadConfig());
        boolean applied = getIntent().getBooleanExtra(CatalogActivity.EXTRA_APPLIED, false);
        if (state != null && !applied && state.getLong("draftConfigRevision", -1) == displayedConfigRevision) {
            for (Map.Entry<String, EditText> entry : fields.entrySet()) {
                if (entry.getKey().startsWith("viewer")) continue;
                if (state.containsKey(entry.getKey())) entry.getValue().setText(state.getString(entry.getKey()));
            }
            restoreMode(state.getString("behaviorMode", state.getBoolean("prepareMode", false) ? "PREPARE" : "ALERT"));
            setAdvancedExpanded(state.getBoolean("advancedExpanded", false));
            unsavedChanges = state.getBoolean("unsavedChanges", false);
        }
        getIntent().removeExtra(CatalogActivity.EXTRA_APPLIED);
        installEditInvalidation();
        showTab(applied || getIntent().getBooleanExtra("show_task", false) || state == null
                ? 0 : state.getInt("selectedTab", 0));
        getIntent().removeExtra("show_task");
        refreshStatus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent.getBooleanExtra("show_task", false)) {
            showTab(0); intent.removeExtra("show_task");
        }
        if (intent.getBooleanExtra(CatalogActivity.EXTRA_APPLIED, false)) {
            restoreConfig(store.loadConfig());
            setAdvancedExpanded(false);
            showTab(0);
            intent.removeExtra(CatalogActivity.EXTRA_APPLIED);
        }
        refreshStatus();
    }

    private void showTab(int tab) {
        selectedTab = Math.max(0, Math.min(2, tab));
        taskPage.setVisibility(selectedTab == 0 ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(selectedTab == 1 ? View.VISIBLE : View.GONE);
        resourcesPage.setVisibility(selectedTab == 2 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navigationButtons.size(); i++) {
            Button b = navigationButtons.get(i); boolean active = selectedTab == i;
            b.setSelected(active); b.setTextColor(active ? ACCENT : MUTED);
            b.setBackground(shape(active ? Color.rgb(231, 243, 239) : CARD, active ? ACCENT : CARD, 12));
            b.setContentDescription(b.getText() + (active ? "，当前页面" : ""));
        }
        contentScroll.post(() -> contentScroll.scrollTo(0, 0));
    }

    private void openGuide(String topic) {
        Intent intent = new Intent(this, HelpActivity.class);
        if (topic != null) intent.putExtra("topic", topic);
        startActivity(intent);
    }

    private void openTransfer() {
        // Opening maintenance never saves or discards an unfinished target edit.
        startActivity(new Intent(this, TransferActivity.class));
    }

    private HomeStep homeStep() {
        MonitorConfig config = store.loadConfig();
        boolean running = MonitorService.instance != null && MonitorService.instance.isRunning();
        boolean ready = MonitorService.instance != null && notificationsEnabled()
                && getPackageManager().getLaunchIntentForPackage("cn.damai") != null
                && (!AppStore.INPUT_SHIZUKU.equals(store.getInputMode()) || ShizukuInputBridge.get(this).isReady());
        boolean valid = config.validate().isEmpty() && config.deadline != null && config.deadline.isAfter(Instant.now())
                && !java.time.LocalDate.parse(config.date).isBefore(java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")));
        return HomeStep.resolve(running, !config.eventKeyword.isEmpty() || !value("event").isEmpty(),
                store.isImportedTargetPendingReview(), valid, ready,
                lockMode.isChecked() && !OrderCheckoutAdapter.isLiveVerified(), unsavedChanges);
    }

    private void homePrimaryAction() {
        switch (homeStep()) {
            case RUNNING: pauseMonitoring(); break;
            case SELECT: case REVIEW_IMPORTED: openCatalog(); break;
            case REPAIR_TARGET:
                new AlertDialog.Builder(this).setTitle("目标需要更新")
                        .setMessage("可能是演出或停止时间已过，或目标信息不完整。重新选择可以自动填入日期、票价和停止时间。")
                        .setNegativeButton("手动修改", (d, w) -> { showTab(0); setAdvancedExpanded(true); })
                        .setPositiveButton("重新选择演出", (d, w) -> openCatalog()).show(); break;
            case SETUP: showTab(1); break;
            case CHANGE_MODE:
                new AlertDialog.Builder(this).setTitle("选择当前可用的方式")
                        .setItems(new String[]{"提醒我，由我继续操作", "帮我选票，到确认页停下"},
                                (d, which) -> restoreMode(which == 0 ? "ALERT" : "PREPARE")).show(); break;
            default:
                new AlertDialog.Builder(this).setTitle("先确认大麦停在哪一页")
                        .setMessage("请先在大麦打开本次演出的票档页面。确认开始后，在 20 秒内用最近任务切回那个页面；保持屏幕亮着。")
                        .setNegativeButton("还没打开，去大麦", (d, w) -> openDamai())
                        .setPositiveButton("已打开，开始监控", (d, w) -> startAfterDialog = true).show();
        }
    }

    private void setRhythm(int min, int max) {
        put("minSeconds", String.valueOf(min)); put("maxSeconds", String.valueOf(max));
        toast("已选择 " + min + "–" + max + " 秒，记得保存"); refreshStatus();
    }

    private Instant parseDeadline(String value) {
        if (value.contains("T")) return OffsetDateTime.parse(value).toInstant();
        return java.time.LocalDateTime.parse(value, DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                .withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }

    private void setupDateTimePickers() {
        for (String key : new String[]{"date", "time", "deadline"}) {
            EditText field = fields.get(key); field.setFocusable(false); field.setCursorVisible(false);
            field.setOnClickListener(v -> pickDateTime(key));
        }
        fields.get("quantity").setFocusable(false); fields.get("quantity").setCursorVisible(false);
    }

    private void pickDateTime(String key) {
        java.time.LocalDateTime fallback = java.time.LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(1);
        java.time.LocalDate date = fallback.toLocalDate(); java.time.LocalTime time = fallback.toLocalTime();
        try {
            if ("deadline".equals(key)) {
                java.time.ZonedDateTime saved = parseDeadline(value(key)).atZone(ZoneId.of("Asia/Shanghai"));
                date = saved.toLocalDate(); time = saved.toLocalTime();
            } else {
                if (!value("date").isEmpty()) date = java.time.LocalDate.parse(value("date"));
                if (!value("time").isEmpty()) time = java.time.LocalTime.parse(value("time"));
            }
        } catch (RuntimeException ignored) { /* A blank form begins at the current local time. */ }
        final java.time.LocalTime initialTime = time;
        if ("time".equals(key)) {
            new TimePickerDialog(this, (p, h, m) -> put(key, String.format(java.util.Locale.ROOT, "%02d:%02d", h, m)),
                    time.getHour(), time.getMinute(), true).show();
            return;
        }
        new DatePickerDialog(this, (p, year, month, day) -> {
            java.time.LocalDate selected = java.time.LocalDate.of(year, month + 1, day);
            if ("date".equals(key)) { put(key, selected.toString()); return; }
            new TimePickerDialog(this, (t, h, m) -> put(key, selected + String.format(java.util.Locale.ROOT, " %02d:%02d", h, m)),
                    initialTime.getHour(), initialTime.getMinute(), true).show();
        }, date.getYear(), date.getMonthValue() - 1, date.getDayOfMonth()).show();
    }

    private void setAdvancedExpanded(boolean expanded) {
        advancedExpanded = expanded;
        advancedTargetFields.setVisibility(expanded ? View.VISIBLE : View.GONE);
        advancedTargetButton.setText(expanded ? "收起手动修改 ▴" : "手动修改目标 ▾");
    }

    private void openCatalog() {
        if (MonitorService.instance == null || !notificationsEnabled()
                || (AppStore.INPUT_SHIZUKU.equals(store.getInputMode()) && !ShizukuInputBridge.get(this).isReady())) {
            showTab(1); toast("先完成页面读取、通知和点击方式设置，再选择演出。"); return;
        }
        pauseForInputSettings("正在选择演出；选好后回到任务页开始。");
        refreshStatus();
        try { startActivity(new Intent(this, CatalogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { showMessage("暂时无法打开目标选择", "监控已暂停。请重新打开助手后重试，也可使用高级手动填写。"); }
    }

    private void refreshTargetSummary() {
        MonitorConfig config = store.loadConfig();
        if (config.eventKeyword.isEmpty()) {
            targetSummary.setText("尚未选择演出\n选择后会显示项目、场次、票价与停止时间。"); return;
        }
        String fullTitle = CatalogActivity.selectedTitle(this, config);
        StringBuilder prices = new StringBuilder();
        for (Integer price : config.prices) { if (prices.length() > 0) prices.append(" / "); prices.append(price); }
        String deadline = config.deadline == null ? "未设置" : config.deadline.atZone(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "（北京时间）";
        targetSummary.setText((store.isImportedTargetPendingReview() ? "备份恢复 · 请重新选择核对\n" : "")
                + (fullTitle.isEmpty() ? config.eventKeyword : fullTitle)
                + "\n" + config.city + " · " + config.date + " " + config.time
                + "\n接受票价：" + (prices.length() == 0 ? "未设置" : prices + " 元")
                + "\n" + config.quantity + " 张 · 总额不超过 " + config.maxTotal + " 元"
                + "\n停止时间：" + deadline);
    }

    private void restoreConfig(MonitorConfig config) {
        restoringFields = true;
        put("event", config.eventKeyword); put("city", config.city); put("date", config.date); put("time", config.time);
        put("quantity", String.valueOf(config.quantity));
        StringBuilder prices = new StringBuilder();
        for (Integer price : config.prices) { if (prices.length() > 0) prices.append(','); prices.append(price); }
        put("prices", prices.toString());
        put("maxTotal", config.maxTotal > 0 ? String.valueOf(config.maxTotal) : "");
        put("deadline", config.deadline == null ? "" : config.deadline.atZone(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        put("minSeconds", String.valueOf(config.minIntervalMs / 1000));
        put("maxSeconds", String.valueOf(config.maxIntervalMs / 1000));
        viewerName.setText(store.getViewerDisplayName());
        viewerSuffix.setText(store.getViewerDocumentSuffix());
        restoreMode(config.mode.name());
        restoringFields = false;
        unsavedChanges = false;
        displayedConfigRevision = store.getConfigRevision();
    }

    private MonitorConfig.Mode selectedMode() {
        if (lockMode.isChecked()) return MonitorConfig.Mode.LOCK_ORDER;
        return prepareMode.isChecked() ? MonitorConfig.Mode.PREPARE : MonitorConfig.Mode.ALERT;
    }

    private void restoreMode(String mode) {
        behaviorModes.check("LOCK_ORDER".equals(mode) ? lockMode.getId()
                : "PREPARE".equals(mode) ? prepareMode.getId() : alertMode.getId());
        viewerFields.setVisibility(lockMode.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void installEditInvalidation() {
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { invalidateEditedConfig(); }
            @Override public void afterTextChanged(Editable s) { }
        };
        for (EditText field : fields.values()) field.addTextChangedListener(watcher);
        behaviorModes.setOnCheckedChangeListener((group, id) -> {
            viewerFields.setVisibility(lockMode.isChecked() ? View.VISIBLE : View.GONE);
            invalidateEditedConfig(); refreshStatus();
        });
    }

    private void invalidateEditedConfig() {
        if (restoringFields) return;
        uiRevision++;
        unsavedChanges = true;
        pauseForInputSettings("有修改尚未保存。开始前会先保存并核对。");
    }

    private MonitorConfig readConfig() {
        try {
            LinkedHashSet<Integer> values = new LinkedHashSet<>();
            for (String part : value("prices").split("[,，、\\s]+")) {
                if (!part.isEmpty()) values.add(Integer.parseInt(part));
            }
            Instant deadline = parseDeadline(value("deadline"));
            MonitorConfig config = new MonitorConfig(value("event"), value("city"), value("date"), value("time"),
                    new ArrayList<>(values), Integer.parseInt(value("quantity")), Integer.parseInt(value("maxTotal")), deadline,
                    Math.multiplyExact(Long.parseLong(value("minSeconds")), 1000),
                    Math.multiplyExact(Long.parseLong(value("maxSeconds")), 1000),
                    selectedMode());
            List<String> problems = new ArrayList<>(config.validate());
            if (!deadline.isAfter(Instant.now())) problems.add("停止时间需要晚于当前时间。");
            if (problems.isEmpty() && java.time.LocalDate.parse(config.date)
                    .isBefore(java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")))) problems.add("演出日期已过，请重新选择。");
            if (!problems.isEmpty()) { showTab(0); setAdvancedExpanded(true); showMessage("还有几项需要补全", String.join("\n", problems)); return null; }
            return config;
        } catch (Exception e) {
            showTab(0); setAdvancedExpanded(true);
            showMessage("请补全目标", "推荐先点“选择 / 更换演出”自动填写。手动修改时，票价、金额和间隔使用整数；日期与时间点选即可。");
            return null;
        }
    }

    private void saveOnly() {
        MonitorConfig config = readConfig();
        if (config == null) return;
        if (!persistDraft(config)) return;
        toast("配置已保存");
        refreshStatus();
    }

    private boolean persistDraft(MonitorConfig config) {
        try {
            pauseForInputSettings("正在保存配置，监控保持暂停；本次下单授权已撤销。");
            store.saveViewerBinding(viewerName.getText().toString(), viewerSuffix.getText().toString());
            store.saveConfig(config);
            displayedConfigRevision = store.getConfigRevision(); unsavedChanges = false;
            store.setStatus("修改已保存，尚未开始监控。");
            return true;
        } catch (IllegalArgumentException invalid) {
            showMessage("请检查观演人", "姓名请按大麦实名名单填写；不要填写完整证件号。证件尾号可留空，或填写末 2 至 6 位字母、数字。");
            return false;
        }
    }

    /** A visible Activity action starts in place; the service waits for Damai for 20 seconds. */
    private void startFromActivity() {
        if (!activityResumed || !hasWindowFocus()) return;
        if (store.isImportedTargetPendingReview()) { openCatalog(); return; }
        MonitorService current = MonitorService.instance;
        if (current != null && current.isRunning()) { toast("监控已运行，请先暂停。"); return; }
        MonitorConfig config = readConfig();
        if (config == null) return;
        if (config.mode == MonitorConfig.Mode.LOCK_ORDER && !OrderCheckoutAdapter.isLiveVerified()) {
            showMessage("自动下单尚未开放", "真实确认购买页及待付款页的适配尚未通过真机验收。你可以保存配置，当前请使用“帮我选票，到确认页停下”；不会生成下单授权或自动提交。"); return;
        }
        if (!persistDraft(config)) return;
        MonitorService service = checkedStartService();
        if (service == null) return;
        if (config.mode != MonitorConfig.Mode.LOCK_ORDER) {
            store.appendEvent("START_RECEIVED", "activity_button", 0);
            try { service.arm(); }
            catch (RuntimeException e) { startFailed(service); }
            refreshStatus();
            if (service.isRunning()) toast("已启动，请在 20 秒内切回大麦目标票档页。");
            return;
        }
        if (!orderBarrierClear()) { showMessage("请先核查大麦订单", orderBarrierText.getText().toString()); return; }
        String title = CatalogActivity.selectedTitle(this, config);
        String viewer = store.getViewerDisplayName(), suffix = store.getViewerDocumentSuffix();
        String binding = LockAuthorization.viewerBindingHash(viewer, suffix);
        if (title.isEmpty()) {
            showMessage("需要核实完整项目", "请先通过“选择 / 更换演出”保存真实项目，再使用自动下单。手填关键词不能替代完整项目核对。"); return;
        }
        if (binding.isEmpty()) {
            showMessage("请填写本次观演人", "请按大麦实名名单填写本机观演人姓名。不会沿用历史任务中的实名信息。"); return;
        }
        long revision = store.getConfigRevision(), draftRevision = uiRevision;
        String deadline = config.deadline.atZone(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String message = title + "\n" + config.city + " · " + config.date + " " + config.time
                + "\n接受票面价：" + config.prices + " 元"
                + "\n数量：" + config.quantity + " 张 · 订单实付上限：" + config.maxTotal + " 元"
                + "\n观演人：" + viewer + (suffix.isEmpty() ? "" : " · 证件尾号 " + suffix)
                + "\n本次监控截止：" + deadline + "（北京时间）"
                + "\n\n确认后核实目标和观演人，尝试提交一次订单，看到官方待付款订单后停止；付款由你完成。"
                + "\n不会添加付费项目。提交结果未知时不会重试。暂停或配置改变后本次授权失效。"
                + "\n启动后请在 20 秒内手动切回大麦目标票档页。";
        orderConfirmation = new AlertDialog.Builder(this).setTitle("核对本次自动下单")
                .setMessage(message).setNegativeButton("返回修改", null)
                .setPositiveButton("确认并开始", (dialog, which) -> {
                    if (!OrderCheckoutAdapter.isLiveVerified() || !activityResumed || isFinishing() || isDestroyed()
                            || uiRevision != draftRevision || store.getConfigRevision() != revision
                            || !LockAuthorization.configFingerprint(config).equals(LockAuthorization.configFingerprint(store.loadConfig()))
                            || !binding.equals(LockAuthorization.viewerBindingHash(store.getViewerDisplayName(), store.getViewerDocumentSuffix()))
                            || !title.equals(CatalogActivity.selectedTitle(this, store.loadConfig()))) {
                        showMessage("本次配置已变化", "请重新核对目标与观演人后开始。"); return;
                    }
                    if (!orderBarrierClear()) { showMessage("已阻止再次下单", orderBarrierText.getText().toString()); return; }
                    MonitorService fresh = checkedStartService();
                    if (fresh == null || fresh.isRunning()) return;
                    long now = System.currentTimeMillis();
                    LockAuthorization grant = new LockAuthorization(UUID.randomUUID().toString(), config,
                            title, binding, now, config.deadline.toEpochMilli());
                    if (!grant.validFor(store.loadConfig(), now)) {
                        showMessage("本次目标不能启动", "请重新读取完整项目，确认日期、监控截止时间和观演人配置有效。"); return;
                    }
                    store.appendEvent("START_RECEIVED", "activity_button", 0);
                    try { fresh.armForOrder(grant); }
                    catch (RuntimeException e) { startFailed(fresh); }
                    refreshStatus();
                    if (fresh.isRunning()) toast("已启动，请在 20 秒内切回大麦目标票档页；付款仍由你完成。");
                }).create();
        orderConfirmation.setOnDismissListener(dialog -> orderConfirmation = null);
        orderConfirmation.show();
    }

    private MonitorService checkedStartService() {
        MonitorService service = MonitorService.instance;
        if (service == null) { showMessage("需要开启票务助手服务", "请在连接与权限中开启“票务助手 · 界面读取”，再回来开始。"); return null; }
        if (!notificationsEnabled()) { showMessage("需要允许通知", "请先允许助手通知，以接收暂停和待付款提醒。"); return null; }
        if (AppStore.INPUT_SHIZUKU.equals(store.getInputMode()) && !ShizukuInputBridge.get(this).isReady()) {
            showMessage("本地输入尚未就绪", "请先启动 Shizuku、授权并连接，再重新开始。"); return null;
        }
        return service;
    }

    private void startFailed(MonitorService service) {
        store.setArmed(false);
        try { service.pause("启动未完成，请重新核对目标后开始。"); } catch (RuntimeException ignored) { }
        store.appendEvent("START_FAILED", "arm_runtime_exception", 0);
        store.setStatus("启动未完成，监控已暂停；请重新核对目标后开始。");
    }

    /** Existing or unreadable durable attempts never authorize another submission. */
    private boolean orderBarrierClear() {
        try {
            if (new OrderAttemptStore(getFilesDir()).read() != null) {
                orderBarrierText.setText("已有提交记录，已阻止再次自动下单。请在大麦核查订单；此处不会清除记录或自动重试。");
                return false;
            }
            orderBarrierText.setText(lockMode.isChecked() ? "尚无提交记录。只有核对本次目标并明确开始后才允许尝试一次；付款由你完成。" : "");
            return true;
        } catch (Exception e) {
            orderBarrierText.setText("无法读取订单提交记录，自动下单已阻止。请先在大麦核查订单，记录不会被自动清除。");
            return false;
        }
    }

    private void startMonitoring() {
        if (store.isImportedTargetPendingReview()) { openCatalog(); return; }
        MonitorService service = MonitorService.instance;
        if (service != null && service.isRunning()) {
            toast("监控已运行；修改配置请先保存并暂停。");
            return;
        }
        MonitorConfig config = readConfig();
        if (config == null) return;
        if (config.mode == MonitorConfig.Mode.LOCK_ORDER) {
            showMessage("请使用本次核对入口", "自动下单需在助手内核对当前目标和观演人，再点击“核对并开始自动下单”。准备通知不授权下单。"); return;
        }
        if (!persistDraft(config)) return;
        if (service == null) {
            new AlertDialog.Builder(this).setTitle("需要开启票务助手服务")
                    .setMessage("在系统无障碍设置中向下滑动，找到“已下载的服务”或类似服务列表，进入“票务助手 · 界面读取”。\n\n开启详情页顶部的同名服务开关；下方“票务助手 · 界面读取快捷方式”无需开启，TalkBack 也无需开启。完成后返回这里，点击“通过通知启动（备用）”。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("查看权限说明", (d, w) -> showAccessibilityDisclosure()).show();
            return;
        }
        if (!notificationsEnabled()) {
            new AlertDialog.Builder(this).setTitle("请先允许提醒通知")
                    .setMessage("发现有票、需要你处理或监控暂停时，需要通过手机通知提醒你。允许通知后再点击“通过通知启动（备用）”。")
                    .setNegativeButton("稍后", null)
                    .setPositiveButton("开启通知", (d, w) -> requestNotifications()).show();
            return;
        }
        if (AppStore.INPUT_SHIZUKU.equals(store.getInputMode()) && !ShizukuInputBridge.get(this).isReady()) {
            showMessage("本地调试输入尚未就绪", "请先打开并启动 Shizuku，再点击“允许票务助手使用”和“连接点击服务”。连接就绪后，重新准备监控。你也可以切换回默认无障碍触控。");
            return;
        }
        if (getPackageManager().getLaunchIntentForPackage("cn.damai") == null) {
            showMessage("尚未找到大麦", "请先安装并登录官方大麦 App，再打开目标演出页面。");
            return;
        }
        store.setArmed(false);
        store.setStatus("已准备，尚未启动。请在大麦打开目标票档，再点通知中的“开始监控”。");
        if (!showPreparedNotification()) {
            store.setStatus("准备通知无法显示，请检查通知权限后重试。");
            refreshStatus(); return;
        }
        if (!openDamai()) service.pause("无法打开大麦，请手动打开目标页面后重新准备。");
        refreshStatus();
    }

    private boolean showPreparedNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        manager.createNotificationChannel(new NotificationChannel("monitor_status", "运行状态", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel channel = manager.getNotificationChannel("monitor_status");
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        PendingIntent start = PendingIntent.getBroadcast(this, 12,
                new Intent(this, StartReceiver.class).setAction(StartReceiver.ACTION_START),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getBroadcast(this, 13,
                new Intent(this, StopReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 12,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String message = "在大麦打开目标票档后，下拉通知并点“开始监控”。当前尚未启动；点击通知正文只会打开助手。";
        Notification notification = new Notification.Builder(this, "monitor_status")
                .setSmallIcon(R.drawable.ic_ticket).setContentTitle("票务助手 · 已准备")
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null, "开始监控", start).build())
                .addAction(new Notification.Action.Builder(null, "取消", cancel).build()).build();
        // Remove only this application's old result alert before showing a new preparation.
        manager.cancel(11);
        manager.notify(12, notification);
        return true;
    }

    private void pauseMonitoring() {
        MonitorService service = MonitorService.instance;
        if (service != null) service.pause("已由你手动暂停。");
        store.setArmed(false);
        store.setStatus("已暂停；再次开始需要你手动启动。");
        refreshStatus();
        toast("已暂停");
    }

    private boolean openDamai() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("cn.damai");
        if (launch == null) { showMessage("尚未找到大麦", "请先安装官方大麦 App。"); return false; }
        try { startActivity(launch); return true; }
        catch (Exception e) { toast("无法打开大麦，请在手机上手动打开。"); return false; }
    }

    private void changeInputMode(String mode) {
        if (mode.equals(store.getInputMode())) return;
        pauseForInputSettings("点击方式已修改；请重新准备并手动启动监控。");
        store.setInputMode(mode);
        if (AppStore.INPUT_ACCESSIBILITY.equals(mode)) ShizukuInputBridge.get(this).disconnect();
        refreshStatus();
        toast("点击方式已保存，监控已暂停");
    }

    private void pauseForInputSettings(String reason) {
        MonitorService service = MonitorService.instance;
        if (service != null) service.pause(reason);
        store.setArmed(false);
        store.setStatus(reason);
        ShizukuInputBridge.get(this).cancelPending();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancel(12);
    }

    private void requestShizukuPermission() {
        if (!AppStore.INPUT_SHIZUKU.equals(store.getInputMode())) return;
        pauseForInputSettings("正在设置本地调试输入；监控保持暂停。");
        try { ShizukuInputBridge.get(this).requestPermission(this, SHIZUKU_REQUEST); }
        catch (IllegalStateException e) { showMessage("无法允许票务助手使用", shizukuPermissionGuidance(e.getMessage())); }
        catch (Exception e) { showMessage("无法允许票务助手使用", "请先在手机上安装并启动 Shizuku，然后返回重试。"); }
        refreshStatus();
    }

    /** Only our bridge's fixed guidance is displayed; arbitrary SDK exception text stays private. */
    private String shizukuPermissionGuidance(String reason) {
        if ("请先暂停任务，再在当前页面授权。".equals(reason)
                || "请先在手机启动 Shizuku。".equals(reason)
                || "需要以无线调试方式启动 Shizuku 13 或更新版本。".equals(reason)
                || "请在 Shizuku 的应用管理中手动允许票务助手。".equals(reason)) return reason;
        return "请确认 Shizuku 已启动，返回当前页面后重新请求授权；详细状态见连接与权限。";
    }

    private void connectShizuku() {
        if (!AppStore.INPUT_SHIZUKU.equals(store.getInputMode())) return;
        pauseForInputSettings("正在连接本地调试输入；监控保持暂停。");
        try {
            ShizukuInputBridge.get(this).connect(result -> {
                if (isFinishing() || isDestroyed()) return;
                if (!AppStore.INPUT_SHIZUKU.equals(store.getInputMode())) return;
                boolean connected = result.success && ShizukuInputBridge.get(this).isReady();
                if (!store.isArmed()) store.setStatus(connected
                        ? "本地输入已连接；监控尚未启动。" : "本地输入未连接；监控保持暂停，请查看连接与权限状态。");
                refreshStatus();
                toast(connected ? "本地输入已连接；监控尚未启动。" : "连接未完成，请查看连接状态。");
            });
        } catch (Exception e) {
            store.setStatus("本地输入连接失败；监控保持暂停。");
            showMessage("无法连接点击服务", "请确认 Shizuku 已启动，且已向票务助手授权，再重试连接。");
        }
        refreshStatus();
    }

    private void openShizuku() {
        pauseForInputSettings("已暂停；设置 Shizuku 后请返回重新准备监控。");
        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launch == null) {
            showMessage("尚未找到 Shizuku", "请从 Shizuku 官方网站 shizuku.rikka.app 了解安装与启动方法。此实验方式需要你自行开启并授权本地调试服务。");
        } else {
            try { startActivity(launch); }
            catch (Exception e) { toast("无法打开 Shizuku，请在手机上手动打开。"); }
        }
        refreshStatus();
    }

    private void showAccessibilityDisclosure() {
        new AlertDialog.Builder(this).setTitle("开启票务助手的辅助服务")
                .setMessage("前往系统设置后，向下滑动找到“已下载的服务”或类似服务列表，进入“票务助手 · 界面读取”，开启详情页顶部的同名服务开关，再返回本应用。\n\n下方“票务助手 · 界面读取快捷方式”无需开启，TalkBack 也无需开启。\n\n本服务在你搜索和选择目标、读取当前大麦页面，或手动启动监控后，读取大麦可见页面并执行相应点击、返回和选票。必要时在本机识别屏幕文字；不上传截图，不读取其他应用内容。遇到验证码、锁屏或不支持页面会暂停。\n\n仅提醒及到确认页暂停模式不会最终提交订单。自动下单模式只在你核对本次目标和观演人并明确开始后，尝试提交一次并停在官方待付款页；付款始终由你完成。你可随时关闭服务。")
                .setNegativeButton("取消", null).setPositiveButton("前往系统设置", (d, w) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (Exception e) { toast("请在系统无障碍设置中找到“票务助手 · 界面读取”。"); }
                }).show();
    }

    private boolean notificationsEnabled() {
        android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || !manager.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return false;
        NotificationChannel alerts = manager.getNotificationChannel("monitor_alert");
        return alerts != null && alerts.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_REQUEST) {
            toast(notificationsEnabled() ? "通知已允许，可返回准备监控。" : "通知尚未允许，监控未启动。");
            refreshStatus();
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
        } else {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel("monitor_alert");
            boolean channelOff = channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE;
            Intent intent = new Intent(channelOff ? Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS : Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (channelOff) intent.putExtra(Settings.EXTRA_CHANNEL_ID, "monitor_alert");
            try { startActivity(intent); } catch (Exception e) { toast("请在系统设置中允许票务助手通知。"); }
        }
    }

    private void exportHistory() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, "ticket-helper-history-" + java.time.LocalDate.now() + ".jsonl");
        try { startActivityForResult(intent, EXPORT_REQUEST); }
        catch (Exception e) { toast("系统文件选择器不可用。"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != EXPORT_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try (OutputStream destination = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (destination == null) throw new java.io.IOException("Missing destination");
            store.exportHistory(destination);
            toast("检查记录已导出");
        } catch (Exception e) { showMessage("导出失败", "无法写入所选位置，请换一个保存位置重试。"); }
    }

    private void refreshStatus() {
        if (store == null || stateBadge == null) return;
        refreshTargetSummary();
        MonitorService service = MonitorService.instance;
        boolean running = service != null && service.isRunning();
        stateBadge.setText(running ? "正在监控" : "尚未运行");
        stateBadge.setTextColor(running ? ACCENT : MUTED);
        statusText.setText(service == null && store.isArmed()
                ? "无障碍连接已断开，当前未运行。请重新开启后手动开始。" : store.getStatus());
        historyText.setText(getString(R.string.history_summary, store.historyCount(), store.lastEventSummary()));
        boolean notifications = notificationsEnabled();
        permissionText.setText(getString(R.string.permission_summary, service != null ? "已连接" : "待开启", notifications ? "已允许" : "待允许"));
        boolean shizukuMode = AppStore.INPUT_SHIZUKU.equals(store.getInputMode());
        inputStatusText.setText(shizukuMode ? ShizukuInputBridge.get(this).status()
                : "已选标准触控。若选票没有变化，可查看帮助后改用兼容触控。");
        shizukuPanel.setVisibility(shizukuMode ? View.VISIBLE : View.GONE);
        shizukuPermissionButton.setEnabled(shizukuMode && !running);
        shizukuConnectButton.setEnabled(shizukuMode && !running);
        shizukuPermissionButton.setAlpha(shizukuMode && !running ? 1f : 0.55f);
        shizukuConnectButton.setAlpha(shizukuMode && !running ? 1f : 0.55f);
        startButton.setEnabled(!running);
        startButton.setAlpha(running ? 0.55f : 1f);
        boolean lock = lockMode.isChecked();
        boolean clear = orderBarrierClear();
        orderCapabilityText.setVisibility(lock ? View.VISIBLE : View.GONE);
        orderCapabilityText.setText(OrderCheckoutAdapter.isLiveVerified()
                ? "自动下单需逐次核对当前目标和观演人；仅官方待付款订单确认后才报告成功。"
                : "自动下单未开放：真实确认页和待付款页适配尚未验收。可保存配置，当前使用“帮我选票，到确认页停下”。");
        startButton.setVisibility(lock ? View.GONE : View.VISIBLE);
        preparedHelp.setVisibility(lock ? View.GONE : View.VISIBLE);
        HomeStep step = homeStep(); nextStepText.setText(step.title);
        directStartButton.setText(step.button); directStartButton.setEnabled(true); directStartButton.setAlpha(1f);
        pauseButton.setVisibility(running ? View.VISIBLE : View.GONE);
        draftText.setVisibility(unsavedChanges ? View.VISIBLE : View.GONE);
        draftText.setText("有修改未保存。上方目标摘要仍显示已保存的内容。");
        saveButton.setVisibility(unsavedChanges ? View.VISIBLE : View.GONE);
        boolean damai = getPackageManager().getLaunchIntentForPackage("cn.damai") != null;
        accessSummary.setText("大麦 " + (damai ? "已安装" : "待安装")
                + "  ·  页面读取 " + (service != null ? "已连接" : "待开启")
                + "\n通知 " + (notifications ? "已允许" : "待允许")
                + "  ·  点击服务 " + (!shizukuMode ? "标准触控" : ShizukuInputBridge.get(this).isReady() ? "已连接" : "待连接"));
    }

    @Override protected void onResume() {
        super.onResume(); activityResumed = true;
        if (store != null && viewerName != null && displayedConfigRevision != store.getConfigRevision()) {
            restoreConfig(store.loadConfig());
        }
        handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && startAfterDialog && activityResumed) {
            startAfterDialog = false; handler.post(this::startFromActivity);
        }
    }
    @Override protected void onPause() {
        activityResumed = false; startAfterDialog = false;
        if (orderConfirmation != null) orderConfirmation.dismiss();
        handler.removeCallbacks(refresh); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        for (Map.Entry<String, EditText> entry : fields.entrySet()) {
            if (!entry.getKey().startsWith("viewer")) state.putString(entry.getKey(), entry.getValue().getText().toString());
        }
        state.putString("behaviorMode", selectedMode().name());
        state.putBoolean("advancedExpanded", advancedExpanded);
        state.putBoolean("unsavedChanges", unsavedChanges);
        state.putInt("selectedTab", selectedTab);
        state.putLong("draftConfigRevision", displayedConfigRevision);
        super.onSaveInstanceState(state);
    }

    private void section(LinearLayout target, String index, String title) {
        addText(target, index + "  /  " + title, 16, TEXT, true, 0);
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card(LinearLayout target, int margin) {
        LinearLayout card = column(); card.setPadding(dp(17), dp(18), dp(17), dp(18));
        card.setBackground(shape(CARD, BORDER, 20)); target.addView(card, spaced(margin)); return card;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private void addText(LinearLayout target, String value, int size, int color, boolean bold, int margin) { target.addView(text(value, size, color, bold), spaced(margin)); }
    private EditText field(LinearLayout target, String key, String label, String hint, boolean numeric) {
        addText(target, label, 12, MUTED, true, 15);
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(15); input.setTextColor(TEXT); input.setHintTextColor(Color.rgb(111, 134, 155));
        input.setHint(hint); input.setPadding(dp(12), dp(11), dp(12), dp(11)); input.setMinHeight(dp(50));
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setBackground(shape(BG, BORDER, 10));
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setContentDescription(label);
        fields.put(key, input); target.addView(input, spaced(7)); return input;
    }
    private void paired(LinearLayout target, String key1, String label1, String hint1, boolean number1, String key2, String label2, String hint2, boolean number2) {
        LinearLayout row = row(); LinearLayout left = column(), right = column();
        row.addView(left, weighted()); row.addView(right, weightedWithGap());
        field(left, key1, label1, hint1, number1); field(right, key2, label2, hint2, number2); target.addView(row);
    }
    private RadioButton radio(String label, String tag) {
        RadioButton view = new RadioButton(this); view.setId(View.generateViewId()); view.setText(label); view.setTag(tag);
        view.setTextSize(15); view.setTextColor(TEXT); view.setMinHeight(dp(48));
        view.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT)); return view;
    }
    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button view = new Button(this); view.setText(label); view.setTextSize(14); view.setAllCaps(false);
        view.setTextColor(primary ? Color.WHITE : TEXT); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinHeight(dp(50)); view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setBackground(shape(primary ? ACCENT : CARD, primary ? ACCENT : BORDER, 12));
        view.setStateListAnimator(null); view.setOnClickListener(click); return view;
    }
    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), stroke); return drawable;
    }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private LinearLayout.LayoutParams weightedWithGap() { LinearLayout.LayoutParams p = weighted(); p.leftMargin = dp(10); return p; }
    private void put(String key, String value) { fields.get(key).setText(value); }
    private String value(String key) { return fields.get(key).getText().toString().trim(); }
    private int dp(int size) { return Math.round(getResources().getDisplayMetrics().density * size); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void showMessage(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show(); }
}
