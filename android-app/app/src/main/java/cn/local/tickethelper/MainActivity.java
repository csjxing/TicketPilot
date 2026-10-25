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
import cn.local.tickethelper.core.IntervalSeconds;
import cn.local.tickethelper.core.TaskPreferences;
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
    private final Map<String, LinearLayout> expandablePanels = new LinkedHashMap<>();
    private final Map<String, Button> expandableButtons = new LinkedHashMap<>();
    private TextView modeSummary, homeHint;
    private TextView targetDetails, priceSummary, journeyText, aiHelpTitle;
    private Button quickModeButton, quickRhythmButton, contextAiButton;
    private LinearLayout actionDock, aiHelpCard;
    private final List<Button> navigationButtons = new ArrayList<>();
    private ScrollView contentScroll;
    private TextView nextStepText, draftText, accessSummary;
    private Button pauseButton, saveButton, changeTargetButton;
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
        actionDock = column(); actionDock.setPadding(dp(18), dp(10), dp(18), dp(10));
        actionDock.setBackgroundColor(CARD); shell.addView(actionDock);
        LinearLayout navigation = row(); navigation.setPadding(dp(10), dp(7), dp(10), dp(7));
        navigation.setBackgroundColor(CARD);
        String[] tabs = {"抢票", "我的"};
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

        addText(page, "TicketPilot", 28, TEXT, true, 0);
        addText(page, "选好想看的，下一步交给助手", 14, MUTED, false, 4);
        journeyText = text("① 选演出   →   ② 定条件   →   ③ 开始", 12, ACCENT, true);
        page.addView(journeyText, spaced(16));
        addText(settingsPage, "我的", 28, TEXT, true, 0);
        addText(settingsPage, "手机准备、AI 和使用帮助", 14, MUTED, false, 4);
        resourcesPage.addView(button("‹ 返回我的", false, v -> showTab(1)));
        addText(resourcesPage, "帮助与记录", 28, TEXT, true, 16);
        addText(resourcesPage, "需要时再看，不影响日常选票", 14, MUTED, false, 4);

        LinearLayout live = card(page, 18);
        stateBadge = text("还未开始", 12, ACCENT, true); live.addView(stateBadge);
        targetSummary = text("", 21, TEXT, true); targetSummary.setMaxLines(3);
        targetSummary.setEllipsize(android.text.TextUtils.TruncateAt.END); live.addView(targetSummary, spaced(12));
        targetDetails = text("", 14, MUTED, false); live.addView(targetDetails, spaced(8));
        priceSummary = text("", 20, ACCENT, true); live.addView(priceSummary, spaced(12));
        modeSummary = text("", 12, MUTED, false); live.addView(modeSummary, spaced(6));
        changeTargetButton = button("更换演出、场次或票价  ›", false, v -> openCatalog());
        live.addView(changeTargetButton, spaced(12));

        LinearLayout choices = card(page, 12);
        addText(choices, "按你的方式", 15, TEXT, true, 0);
        quickModeButton = button("有票后怎么做  ›", false, v -> showModeSheet());
        quickModeButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        choices.addView(quickModeButton, spaced(10));
        quickRhythmButton = button("检查速度  ›", false, v -> showRhythmSheet());
        quickRhythmButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        choices.addView(quickRhythmButton, spaced(8));

        LinearLayout next = card(page, 12);
        page.removeView(next); page.addView(next, page.indexOfChild(live) + 1);
        nextStepText = text("", 16, TEXT, true); next.addView(nextStepText);
        homeHint = text("", 13, MUTED, false); next.addView(homeHint, spaced(6));
        directStartButton = button("选择演出", true, v -> homePrimaryAction());
        actionDock.addView(directStartButton);
        pauseButton = button("暂停", true, v -> pauseMonitoring()); actionDock.addView(pauseButton);
        draftText = text("", 12, ACCENT, true); actionDock.addView(draftText, spaced(6));

        aiHelpCard = next;
        aiHelpTitle = text("AI 帮你少想一步", 12, ACCENT, true); aiHelpCard.addView(aiHelpTitle, spaced(12));
        contextAiButton = button("说说你想看什么", false, v -> openContextAi());
        aiHelpCard.addView(contextAiButton, spaced(9));
        LinearLayout details = expandable(page, "运行详情", "连接状态和最近的处理结果", false);
        statusText = text("", 13, MUTED, false); details.addView(statusText);
        accessSummary = text("", 13, MUTED, false); details.addView(accessSummary, spaced(10));
        details.addView(button("打开大麦", false, v -> openDamai()), spaced(10));
        details.addView(button("检查手机设置", false, v -> openSetup()), spaced(10));

        LinearLayout access = expandable(settingsPage, "手机准备", "读取页面、通知与点击服务", false);
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

        addText(access, "点击方式", 16, TEXT, true, 20);
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
        addText(shizukuPanel, "完成后回到“购票”，再点开始。", 12, MUTED, false, 8);

        LinearLayout moreSettings = expandable(settingsPage, "更多设置", "手动填写、检查参数和购票方式", false);
        LinearLayout manual = card(moreSettings, 0);
        addText(manual, "手动修改演出", 17, TEXT, true, 0);
        addText(manual, "通常只需在购票页点选演出和票价。", 12, MUTED, false, 6);
        advancedTargetButton = button("手动修改目标 ▾", false, v -> setAdvancedExpanded(!advancedExpanded));
        manual.addView(advancedTargetButton, spaced(10));
        advancedTargetFields = column();
        manual.addView(advancedTargetFields, spaced(2));
        field(advancedTargetFields, "event", "演出关键词", "填写页面上可见的准确名称", false);
        paired(advancedTargetFields, "city", "城市", "例如：杭州", false, "date", "演出日期", "2026-12-31", false);
        paired(advancedTargetFields, "time", "开演时间", "19:30", false, "quantity", "张数", "当前版本仅支持 1 张", true);
        field(advancedTargetFields, "prices", "接受票价（元）", "例如：980,1380,1680", false);
        field(advancedTargetFields, "maxTotal", "订单实付上限（元，含全部费用）", "填写最高可接受的订单总额", true);
        field(advancedTargetFields, "deadline", "什么时候停止（北京时间）", "点击选择日期与时间", false);
        setupDateTimePickers();
        addText(advancedTargetFields, "修改后点“保存设置”，或开始时核对保存。", 12, MUTED, false, 8);
        setAdvancedExpanded(false);

        LinearLayout rhythm = expandable(moreSettings, "检查频率", "在设定的时间区间内自动检查", false);
        rhythm.addView(button("快速 · 0.5–1.5 秒", false, v -> setRhythm("0.5", "1.5")), spaced(10));
        rhythm.addView(button("常用 · 1–3 秒", false, v -> setRhythm("1", "3")), spaced(8));
        rhythm.addView(button("稳健 · 5–10 秒", false, v -> setRhythm("5", "10")), spaced(8));
        paired(rhythm, "minSeconds", "最短间隔（秒）", "0.5", true,
                "maxSeconds", "最长间隔（秒）", "1.5", true);
        fields.get("minSeconds").setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        fields.get("maxSeconds").setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        addText(rhythm, "每次操作后，在这个区间随机等待。最快可设 0.5 秒；读取、识别和页面加载还需时间，实际操作不会固定每 0.5 秒发生。", 12, MUTED, false, 8);
        addText(rhythm, "普通检查不调用模型。更快可能增加手机负担，遇到验证或异常仍会暂停。", 12, MUTED, false, 8);

        LinearLayout behavior = expandable(moreSettings, "有票后怎么做", "只提醒，或帮你选票到确认页", false);
        behaviorModes = new RadioGroup(this);
        behaviorModes.setOrientation(RadioGroup.VERTICAL);
        alertMode = radio("提醒我，由我继续操作", "ALERT");
        prepareMode = radio("帮我选票，到确认页停下", "PREPARE");
        lockMode = radio(OrderCheckoutAdapter.isLiveVerified() ? "提交订单，等待我付款"
                : "提交订单，等待我付款（适配未完成）", "LOCK_ORDER");
        behaviorModes.addView(alertMode);
        behaviorModes.addView(prepareMode);
        behaviorModes.addView(lockMode);
        behavior.addView(behaviorModes, spaced(10));
        lockMode.setEnabled(OrderCheckoutAdapter.isLiveVerified());
        lockMode.setVisibility(View.VISIBLE);
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

        saveButton = button("保存设置", true, v -> saveOnly());

        LinearLayout ai = card(settingsPage, 14);
        addText(ai, "AI 模型", 18, TEXT, true, 0);
        addText(ai, "接口、密钥和用量由你管理。", 13, MUTED, false, 8);
        ai.addView(button("管理模型", false, v -> openAiSettings()), spaced(12));
        settingsPage.removeView(ai); settingsPage.addView(ai, 2);

        settingsPage.addView(saveButton, spaced(16));
        settingsPage.addView(button("帮助与记录  ›", false, v -> showTab(2)), spaced(14));
        settingsPage.addView(button("回到抢票", false, v -> showTab(0)), spaced(10));

        LinearLayout guide = card(resourcesPage, 18);
        addText(guide, "随时查，不用重新问一遍", 19, TEXT, true, 0);
        addText(guide, "从第一次使用到异常处理，按关键词搜索。", 14, MUTED, false, 8);
        guide.addView(button("打开离线使用指南", true, v -> openGuide(null)), spaced(14));
        guide.addView(button("遇到问题 · 查看排查步骤", false, v -> openGuide("troubleshooting")), spaced(8));
        LinearLayout local = expandable(resourcesPage, "手机单独使用", "不带电脑时如何准备", false);
        addText(local, "日常读取、判断和点击由手机执行。电脑用于维护；脱离电脑的完整选票流程仍待实测。", 14, MUTED, false, 8);
        local.addView(button("查看单机使用步骤", false, v -> openGuide("move-phone")), spaced(14));
        LinearLayout transfer = expandable(resourcesPage, "电脑接手与备份", "源代码、说明与开发进度", false);
        addText(transfer, "把当前工程、知识库和接手说明交给 Codex 等工具。连接的仍是这台 vivo，不需要更换手机。", 14, MUTED, false, 8);
        transfer.addView(button("电脑接手与备份", true, v -> openTransfer()), spaced(14));
        transfer.addView(button("开发进展与后续交接", false, v -> openGuide("developer-handoff")), spaced(8));
        LinearLayout records = expandable(resourcesPage, "检查记录", "查看结果，或导出排查问题", false);
        historyText = text("", 13, MUTED, false); records.addView(historyText, spaced(10));
        records.addView(button("导出检查记录", false, v -> exportHistory()), spaced(10));
        LinearLayout alternate = expandable(resourcesPage, "通知启动", "备用入口，通常无需使用", false);
        startButton = button("通过通知启动（备用）", false, v -> startMonitoring());
        alternate.addView(startButton, spaced(10));
        preparedHelp = text("备用入口仅用于提醒或到确认页暂停：打开目标票档后，下拉通知点“开始监控”。通知正文只打开助手，不授权自动下单。", 12, MUTED, false);
        alternate.addView(preparedHelp, spaced(8));
        addText(resourcesPage, "TicketPilot 体验版 · 日常检查在手机本地运行", 12, MUTED, false, 20);
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
        if (state != null) for (String key : expandablePanels.keySet())
            setPanelExpanded(key, state.getBoolean("panel:" + key, false));
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
        actionDock.setVisibility(selectedTab == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navigationButtons.size(); i++) {
            Button b = navigationButtons.get(i); boolean active = (selectedTab == 2 ? 1 : selectedTab) == i;
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
                        .setNegativeButton("手动修改", (d, w) -> { showTab(1); setAdvancedExpanded(true); })
                        .setPositiveButton("重新选择演出", (d, w) -> openCatalog()).show(); break;
            case SETUP: openSetup(); break;
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

    private void setRhythm(String min, String max) {
        put("minSeconds", min); put("maxSeconds", max);
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
        if (expanded) setPanelExpanded("更多设置", true);
        advancedTargetFields.setVisibility(expanded ? View.VISIBLE : View.GONE);
        advancedTargetButton.setText(expanded ? "收起手动修改 ▴" : "手动修改目标 ▾");
    }

    private void openCatalog() {
        if (MonitorService.instance == null || !notificationsEnabled()
                || (AppStore.INPUT_SHIZUKU.equals(store.getInputMode()) && !ShizukuInputBridge.get(this).isReady())) {
            openSetup(); toast("先完成手机准备，就能从大麦选择演出。"); return;
        }
        pauseForInputSettings("正在选择演出；选好后回到任务页开始。");
        refreshStatus();
        try { startActivity(new Intent(this, CatalogActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { showMessage("暂时无法打开目标选择", "监控已暂停。请重新打开助手后重试，也可使用高级手动填写。"); }
    }

    private void refreshTargetSummary() {
        MonitorConfig config = store.loadConfig();
        if (config.eventKeyword.isEmpty()) {
            targetSummary.setText("今天想看谁的演出？");
            targetDetails.setText("从大麦选演出、场次和票价，自动带回这里。");
            priceSummary.setVisibility(View.GONE); return;
        }
        String fullTitle = CatalogActivity.selectedTitle(this, config);
        StringBuilder prices = new StringBuilder();
        for (Integer price : config.prices) { if (prices.length() > 0) prices.append(" / "); prices.append(price); }
        String date = config.date;
        try { date = java.time.LocalDate.parse(config.date).format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", java.util.Locale.CHINA)); }
        catch (RuntimeException ignored) { }
        targetSummary.setText(fullTitle.isEmpty() ? config.eventKeyword : fullTitle);
        targetDetails.setText(config.city + " · " + date + " " + config.time);
        priceSummary.setVisibility(View.VISIBLE);
        priceSummary.setText(prices.length() == 0 ? "还没选票价" : "¥" + prices + "  ·  " + config.quantity + " 张");
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
        put("minSeconds", IntervalSeconds.format(config.minIntervalMs));
        put("maxSeconds", IntervalSeconds.format(config.maxIntervalMs));
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
        boolean firstEdit = !unsavedChanges;
        unsavedChanges = true;
        if (firstEdit) pauseForInputSettings("有修改尚未保存。开始前会先保存并核对。");
    }

    private MonitorConfig readConfig() {
        final long minInterval, maxInterval;
        try {
            minInterval = IntervalSeconds.parse(value("minSeconds"));
            maxInterval = IntervalSeconds.parse(value("maxSeconds"));
            if (maxInterval < minInterval) throw new IllegalArgumentException("最长间隔不能小于最短间隔。");
        } catch (IllegalArgumentException invalid) {
            showTab(1); setPanelExpanded("检查频率", true);
            showMessage("请检查操作间隔", invalid.getMessage()); return null;
        }
        try {
            LinkedHashSet<Integer> values = new LinkedHashSet<>();
            for (String part : value("prices").split("[,，、\\s]+")) {
                if (!part.isEmpty()) values.add(Integer.parseInt(part));
            }
            Instant deadline = parseDeadline(value("deadline"));
            MonitorConfig config = new MonitorConfig(value("event"), value("city"), value("date"), value("time"),
                    new ArrayList<>(values), Integer.parseInt(value("quantity")), Integer.parseInt(value("maxTotal")), deadline,
                    minInterval, maxInterval,
                    selectedMode());
            List<String> problems = new ArrayList<>(config.validate());
            if (!deadline.isAfter(Instant.now())) problems.add("停止时间需要晚于当前时间。");
            if (problems.isEmpty() && java.time.LocalDate.parse(config.date)
                    .isBefore(java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")))) problems.add("演出日期已过，请重新选择。");
            if (!problems.isEmpty()) { showTab(1); setAdvancedExpanded(true); showMessage("还有几项需要补全", String.join("\n", problems)); return null; }
            return config;
        } catch (Exception e) {
            showTab(1); setAdvancedExpanded(true);
            showMessage("请补全目标", "推荐先点“选择 / 更换演出”自动填写。手动修改时，票价和金额使用整数；间隔可填 0.5–600 秒，最多精确到 0.001 秒。日期与时间点选即可。");
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

    private void openAiAssistant() {
        pauseForInputSettings("正在使用 AI 辅助；监控保持暂停，返回后需主动开始。");
        startActivity(new Intent(this, AiAssistantActivity.class));
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
        String currentStatus = service == null && store.isArmed()
                ? "无障碍连接已断开，当前未运行。请重新开启后手动开始。" : store.getStatus();
        // This is bounded, user-facing service text, never the raw decision code or node dump.
        if (currentStatus == null) currentStatus = "";
        if (currentStatus.length() > 240) currentStatus = currentStatus.substring(0, 240);
        boolean paused = !running && isPausedStatus(currentStatus);
        boolean waitingForDamai = running && service.isWaitingForDamai();
        stateBadge.setText(waitingForDamai ? "等待切回大麦" : running ? "正在检查页面" : paused ? "已暂停" : "还未开始");
        stateBadge.setTextColor(running || paused ? ACCENT : MUTED);
        statusText.setText(currentStatus);
        historyText.setText(getString(R.string.history_summary, store.historyCount(), store.lastEventSummary()));
        boolean notifications = notificationsEnabled();
        boolean damai = getPackageManager().getLaunchIntentForPackage("cn.damai") != null;
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
        orderCapabilityText.setVisibility(lock || !OrderCheckoutAdapter.isLiveVerified() ? View.VISIBLE : View.GONE);
        orderCapabilityText.setText(OrderCheckoutAdapter.isLiveVerified()
                ? "自动下单需逐次核对当前目标和观演人；仅官方待付款订单确认后才报告成功。"
                : "提交订单功能正在适配，当前不可选。现在可选票到确认页，最终提交订单和付款由你完成。");
        startButton.setVisibility(lock ? View.GONE : View.VISIBLE);
        preparedHelp.setVisibility(lock ? View.GONE : View.VISIBLE);
        HomeStep step = homeStep();
        nextStepText.setText(waitingForDamai ? "现在切回大麦" : paused && step == HomeStep.READY ? "需要你处理一下" : homeTitle(step));
        boolean clickReady = !shizukuMode || ShizukuInputBridge.get(this).isReady();
        if (step == HomeStep.SETUP) {
            nextStepText.setText(service == null ? "先允许读取大麦页面" : !notifications ? "先开启提醒通知"
                    : !damai ? "先安装官方大麦" : !clickReady ? "连接手机点击服务" : "检查手机准备情况");
            java.util.List<String> missing = new java.util.ArrayList<>();
            if (service == null) missing.add("页面读取未连接");
            if (!notifications) missing.add("通知未允许");
            if (!damai) missing.add("未安装大麦");
            if (!clickReady) missing.add("点击服务未连接");
            homeHint.setText(String.join(" · ", missing) + "。点下方按钮，一步步完成。");
            homeHint.setOnClickListener(null);
        } else {
            homeHint.setText(waitingForDamai ? "请在 " + service.waitingForDamaiSeconds() + " 秒内用最近任务切回目标票档页，保持屏幕亮着。"
                    : !currentStatus.isEmpty() ? currentStatus : homeHint(step));
            final String visibleStatus = currentStatus;
            homeHint.setOnClickListener(!running && !currentStatus.isEmpty()
                    ? v -> showMessage("当前状态", visibleStatus) : null);
        }
        homeHint.setMaxLines(4); homeHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        MonitorConfig saved = store.loadConfig();
        modeSummary.setText(saved.maxTotal > 0 ? "实付上限 ¥" + saved.maxTotal + " · 付款由你完成" : "");
        modeSummary.setVisibility(saved.maxTotal > 0 ? View.VISIBLE : View.GONE);
        quickModeButton.setText("有票后    " + modeLabel(saved.mode) + "  ›");
        quickRhythmButton.setText("检查速度    " + IntervalSeconds.format(saved.minIntervalMs) + "–"
                + IntervalSeconds.format(saved.maxIntervalMs) + " 秒  ›");
        journeyText.setText(saved.eventKeyword.isEmpty() ? "① 选演出   →   ② 定条件   →   ③ 开始"
                : running ? "✓ 已选演出   →   ✓ 已定条件   →   ● 运行中" : "✓ 已选演出   →   ② 定条件   →   ③ 开始");
        aiHelpTitle.setText(paused ? "卡住了？AI 帮你看原因" : saved.eventKeyword.isEmpty() ? "还没想好怎么选？" : "AI 帮你核对这份计划");
        contextAiButton.setText(running ? "暂停并让 AI 分析" : paused ? "分析这次暂停" : saved.eventKeyword.isEmpty() ? "一句话整理购票需求" : "帮我看看场次和预算");
        changeTargetButton.setVisibility(saved.eventKeyword.isEmpty() ? View.GONE : View.VISIBLE);
        directStartButton.setText(step.button); directStartButton.setEnabled(true); directStartButton.setAlpha(1f);
        directStartButton.setVisibility(running ? View.GONE : View.VISIBLE);
        pauseButton.setVisibility(running ? View.VISIBLE : View.GONE);
        draftText.setVisibility(unsavedChanges ? View.VISIBLE : View.GONE);
        draftText.setText("有修改未保存，开始前会核对。");
        saveButton.setVisibility(unsavedChanges ? View.VISIBLE : View.GONE);
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
        for (String key : expandablePanels.keySet()) state.putBoolean("panel:" + key, expandablePanels.get(key).getVisibility() == View.VISIBLE);
        super.onSaveInstanceState(state);
    }

    private void openSetup() {
        LinearLayout content = sheetContent();
        boolean pageReady = MonitorService.instance != null;
        boolean alertsReady = notificationsEnabled();
        boolean damaiReady = getPackageManager().getLaunchIntentForPackage("cn.damai") != null;
        boolean shizuku = AppStore.INPUT_SHIZUKU.equals(store.getInputMode());
        boolean clickReady = shizuku ? ShizukuInputBridge.get(this).isReady() : pageReady;
        addText(content, "按下方提示完成一次设置，之后直接选票。", 14, MUTED, false, 0);
        if (pageReady) addText(content, "✓ 已能读取大麦页面", 14, ACCENT, true, 14);
        else content.addView(button("1. 允许读取大麦页面", true, v -> showAccessibilityDisclosure()), spaced(14));
        if (alertsReady) addText(content, "✓ 已允许消息提醒", 14, ACCENT, true, 12);
        else content.addView(button("允许有票和异常提醒", false, v -> requestNotifications()), spaced(12));
        if (!damaiReady) addText(content, "请先在手机安装官方大麦。", 14, TEXT, false, 12);
        if (clickReady) addText(content, "✓ 点击服务已就绪", 14, ACCENT, true, 12);
        else if (shizuku) content.addView(button("连接点击服务", false, null), spaced(12));
        content.addView(button("AI 帮我看设置问题", false, v -> openAiContext("异常分析", "手机准备",
                "请解释当前手机准备还缺哪些条件，并用我能直接照做的短步骤说明。没有现场证据的状态请说未知。")), spaced(14));
        AlertDialog dialog = showSheet("准备好这台手机", content, "完成，返回", null);
        // A settings screen must remain reachable, not hidden behind this sheet on return.
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (!(child instanceof Button)) continue;
            String label = ((Button) child).getText().toString();
            if ("连接点击服务".equals(label)) child.setOnClickListener(v -> {
                dialog.dismiss(); showTab(1); setPanelExpanded("手机准备", true);
            });
            else if ("1. 允许读取大麦页面".equals(label)) child.setOnClickListener(v -> {
                dialog.dismiss(); showAccessibilityDisclosure();
            });
            else if ("允许有票和异常提醒".equals(label)) child.setOnClickListener(v -> {
                dialog.dismiss(); requestNotifications();
            });
            else if ("AI 帮我看设置问题".equals(label)) child.setOnClickListener(v -> {
                dialog.dismiss(); openAiContext("异常分析", "手机准备", "请解释当前手机准备还缺哪些条件，并用我能直接照做的短步骤说明。没有现场证据的状态请说未知。");
            });
        }
    }

    @Override public void onBackPressed() {
        if (selectedTab != 0) showTab(selectedTab == 2 ? 1 : 0); else super.onBackPressed();
    }

    private void openAiSettings() {
        pauseForInputSettings("正在设置 AI；监控保持暂停。");
        startActivity(new Intent(this, AiAssistantActivity.class).putExtra("screen", "settings").putExtra("origin_label", "我的"));
    }

    private boolean isPausedStatus(String status) {
        return status.contains("暂停") || status.contains("停止") || status.contains("断开")
                || status.contains("重新开始")
                || status.contains("已中断") || status.startsWith("已检测到订单相关页面")
                || status.startsWith("发现目标票档的有票信号") || status.startsWith("大麦已确认待付款订单")
                || status.startsWith("大麦显示匹配的待付款订单") || status.startsWith("存在历史订单提交记录")
                || status.startsWith("订单提交结果无法确认");
    }

    private String homeTitle(HomeStep step) {
        switch (step) {
            case RUNNING: return "正在检查，发现变化会提醒你";
            case SELECT: return "先选好演出和场次";
            case REVIEW_IMPORTED: return "这份草稿还需要核对";
            case REPAIR_TARGET: return "请更新演出信息";
            case SETUP: return "还差一步：准备好手机";
            case CHANGE_MODE: return "请选择一种购票方式";
            case SAVE_AND_START: return "保存修改后就能开始";
            default: return "准备好了";
        }
    }

    private String homeHint(HomeStep step) {
        switch (step) {
            case RUNNING: return "切回大麦，保持屏幕亮着。你可以随时暂停。";
            case SELECT: return "选中票价后，回来点开始。";
            case REVIEW_IMPORTED: return "从大麦重新点选场次和票价，再开始。";
            case REPAIR_TARGET: return "日期已过或信息不完整，重新选择即可补齐。";
            case SETUP: return "允许读取页面、接收提醒，并连接点击服务。";
            case CHANGE_MODE: return "当前支持提醒，或选票到确认页后由你继续。";
            default: return "先在大麦打开目标票档页，再开始；订单提交和付款仍由你完成。";
        }
    }

    private LinearLayout expandable(LinearLayout target, String title, String subtitle, boolean expanded) {
        LinearLayout box = card(target, 14);
        Button header = button(title + "  ▾", false, v -> setPanelExpanded(title,
                expandablePanels.get(title).getVisibility() != View.VISIBLE));
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(Color.TRANSPARENT); box.addView(header);
        if (!subtitle.isEmpty()) addText(box, subtitle, 12, MUTED, false, 2);
        LinearLayout body = column(); box.addView(body, spaced(12));
        expandablePanels.put(title, body); expandableButtons.put(title, header);
        setPanelExpanded(title, expanded); return body;
    }

    private void setPanelExpanded(String title, boolean expanded) {
        if (expanded && ("检查频率".equals(title) || "有票后怎么做".equals(title))) setPanelExpanded("更多设置", true);
        LinearLayout body = expandablePanels.get(title); Button header = expandableButtons.get(title);
        if (body == null || header == null) return;
        body.setVisibility(expanded ? View.VISIBLE : View.GONE);
        header.setText(title + (expanded ? "  ▴" : "  ▾"));
        header.setContentDescription(title + (expanded ? "，已展开" : "，已收起"));
    }

    private String modeLabel(MonitorConfig.Mode mode) {
        return mode == MonitorConfig.Mode.ALERT ? "提醒我" : mode == MonitorConfig.Mode.PREPARE ? "选票到确认页" : "提交订单，等我付款";
    }

    private boolean canEditQuickPreference() {
        if (unsavedChanges) {
            new AlertDialog.Builder(this).setTitle("先处理手动修改")
                    .setMessage("你还有未保存的修改。先保存这些内容，再调整这里的选项。")
                    .setNegativeButton("返回", null).setPositiveButton("继续编辑", (d, w) -> {
                        showTab(1); setAdvancedExpanded(true);
                    }).show(); return false;
        }
        pauseForInputSettings("正在调整购票选项，监控保持暂停。");
        return true;
    }

    private void saveQuickPreference(MonitorConfig config) {
        pauseForInputSettings("设置已更新，请核对后重新开始。");
        store.saveConfig(config); restoreConfig(config);
        store.setStatus("已保存，下次开始时使用新设置。");
        refreshStatus(); toast("已保存");
    }

    private LinearLayout sheetContent() {
        LinearLayout content = column(); content.setPadding(dp(20), dp(10), dp(20), dp(12)); return content;
    }

    private AlertDialog showSheet(String title, LinearLayout content, String close, String save) {
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton(close, null);
        if (save != null) builder.setPositiveButton(save, null);
        AlertDialog dialog = builder.create(); dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(shape(CARD, CARD, 22));
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().setLayout(-1, -2);
        }
        return dialog;
    }

    private void showModeSheet() {
        if (!canEditQuickPreference()) return;
        long revision = store.getConfigRevision();
        MonitorConfig previous = store.loadConfig();
        LinearLayout content = sheetContent();
        RadioGroup options = new RadioGroup(this); options.setOrientation(RadioGroup.VERTICAL);
        RadioButton alert = radio("只提醒我\n发现有票后，由我去大麦继续", "ALERT");
        RadioButton prepare = radio("帮我选票\n选到确认购买页，由我提交和付款", "PREPARE");
        RadioButton lock = radio(OrderCheckoutAdapter.isLiveVerified() ? "帮我锁票\n提交一次订单，等待我付款" : "帮我锁票 · 暂不可用\n真实订单流程尚未适配完成", "LOCK_ORDER");
        lock.setEnabled(OrderCheckoutAdapter.isLiveVerified());
        options.addView(alert); options.addView(prepare); options.addView(lock);
        options.check(previous.mode == MonitorConfig.Mode.ALERT ? alert.getId()
                : previous.mode == MonitorConfig.Mode.PREPARE ? prepare.getId() : lock.getId());
        content.addView(options);
        addText(content, "进入确认购买页还没有锁票；以大麦待付款订单为准。", 12, MUTED, false, 10);
        AlertDialog dialog = showSheet("有票后怎么做", content, "取消", "保存选择");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (revision != store.getConfigRevision()) { dialog.dismiss(); refreshStatus(); toast("设置已更新，请重新选择"); return; }
            MonitorConfig.Mode mode = options.getCheckedRadioButtonId() == alert.getId() ? MonitorConfig.Mode.ALERT
                    : options.getCheckedRadioButtonId() == prepare.getId() ? MonitorConfig.Mode.PREPARE : MonitorConfig.Mode.LOCK_ORDER;
            if (mode == MonitorConfig.Mode.LOCK_ORDER && !OrderCheckoutAdapter.isLiveVerified()) { toast("自动锁票尚未完成，请选择其他方式"); return; }
            saveQuickPreference(TaskPreferences.withMode(previous, mode)); dialog.dismiss();
        });
    }

    private void showRhythmSheet() {
        if (!canEditQuickPreference()) return;
        MonitorConfig previous = store.loadConfig(); long revision = store.getConfigRevision();
        LinearLayout content = sheetContent();
        RadioGroup options = new RadioGroup(this); options.setOrientation(RadioGroup.VERTICAL);
        RadioButton fast = radio("快速  ·  0.5–1.5 秒", "fast");
        RadioButton normal = radio("常用  ·  1–3 秒", "normal");
        RadioButton gentle = radio("稳健  ·  5–10 秒", "gentle");
        RadioButton custom = radio("自定义", "custom");
        options.addView(fast); options.addView(normal); options.addView(gentle); options.addView(custom); content.addView(options);
        LinearLayout customInputs = row();
        EditText min = new EditText(this), max = new EditText(this);
        for (EditText input : new EditText[]{min, max}) {
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setTextSize(16); input.setSingleLine(); input.setMinHeight(dp(48)); input.setSaveEnabled(false);
        }
        min.setHint("最短秒数"); min.setContentDescription("最短间隔秒数"); min.setText(IntervalSeconds.format(previous.minIntervalMs));
        max.setHint("最长秒数"); max.setContentDescription("最长间隔秒数"); max.setText(IntervalSeconds.format(previous.maxIntervalMs));
        customInputs.addView(min, weighted()); customInputs.addView(max, weightedWithGap()); content.addView(customInputs, spaced(6));
        int initial = previous.minIntervalMs == 500 && previous.maxIntervalMs == 1500 ? fast.getId()
                : previous.minIntervalMs == 1000 && previous.maxIntervalMs == 3000 ? normal.getId()
                : previous.minIntervalMs == 5000 && previous.maxIntervalMs == 10000 ? gentle.getId() : custom.getId();
        options.check(initial); customInputs.setVisibility(initial == custom.getId() ? View.VISIBLE : View.GONE);
        options.setOnCheckedChangeListener((g, id) -> customInputs.setVisibility(id == custom.getId() ? View.VISIBLE : View.GONE));
        TextView validation = text("", 13, Color.rgb(168, 61, 46), false); content.addView(validation, spaced(6));
        addText(content, "这是操作间的等待；页面加载另需时间，不等于库存更新频率。", 12, MUTED, false, 8);
        Button explain = button("AI 帮我理解怎么选", false, null); content.addView(explain, spaced(12));
        AlertDialog dialog = showSheet("检查速度", content, "取消", "保存速度");
        explain.setOnClickListener(v -> { dialog.dismiss(); openAiContext("配置建议", "检查速度",
                "请解释当前 " + IntervalSeconds.format(previous.minIntervalMs) + "–" + IntervalSeconds.format(previous.maxIntervalMs)
                + " 秒操作间隔，并帮助我在快速、常用和稳健中选择。区分界面操作等待与服务器库存刷新，不承诺抢票成功，也不要自动修改设置。"); });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (revision != store.getConfigRevision()) { dialog.dismiss(); refreshStatus(); toast("设置已更新，请重新选择"); return; }
            int id = options.getCheckedRadioButtonId();
            try {
                long lower = id == fast.getId() ? 500 : id == normal.getId() ? 1000 : id == gentle.getId() ? 5000 : IntervalSeconds.parse(min.getText().toString());
                long upper = id == fast.getId() ? 1500 : id == normal.getId() ? 3000 : id == gentle.getId() ? 10000 : IntervalSeconds.parse(max.getText().toString());
                saveQuickPreference(TaskPreferences.withPace(previous, lower, upper)); dialog.dismiss();
            } catch (IllegalArgumentException invalid) { validation.setText("请输入 0.5–600 秒，最长值不能小于最短值。"); }
        });
    }

    private void openContextAi() {
        String prior = store.getStatus();
        boolean trouble = isPausedStatus(prior == null ? "" : prior) || homeStep() == HomeStep.SETUP;
        String topic = trouble ? "异常分析" : "配置建议";
        String question = trouble ? "请根据最近的公开页面摘要分析这次暂停或准备问题，用一两步告诉我接下来怎么做；若摘要不足，请直接说明。"
                : store.loadConfig().eventKeyword.isEmpty() ? "帮我整理想看的演出和购票条件。先问我最重要的一个问题，不要替我猜场次或票价。"
                : "请帮我核对当前演出、场次、票价和预算是否清楚，告诉我开始前还缺什么。不要把历史库存当成现在有票。";
        openAiContext(topic, trouble ? "本次暂停与准备" : "购票计划", question);
    }

    private void openAiContext(String topic, String origin, String question) {
        cn.local.tickethelper.ai.AiContextRepository.recordTaskIssue(store.getStatus());
        pauseForInputSettings("正在使用 AI 辅助，监控保持暂停。");
        startActivity(new Intent(this, AiAssistantActivity.class).putExtra("topic", topic)
                .putExtra("origin_label", origin).putExtra("question", question));
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
