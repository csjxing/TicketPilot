package cn.local.tickethelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.local.tickethelper.ai.AiContextRepository;
import cn.local.tickethelper.ai.AiHttpClient;
import cn.local.tickethelper.ai.AiRequestReview;
import cn.local.tickethelper.ai.AiFollowUpContext;
import cn.local.tickethelper.ai.AiDraft;
import cn.local.tickethelper.ai.AiSecretsStore;
import cn.local.tickethelper.ai.AiSettings;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Explicit, advisory-only model requests. Model text never becomes an executable action. */
public final class AiAssistantActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 248), CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(23, 38, 53), MUTED = Color.rgb(88, 101, 117);
    private static final int ACCENT = Color.rgb(23, 107, 91), BORDER = Color.rgb(222, 228, 231);
    private static final String[] TOPICS = {"配置建议", "场次比较", "票档解释", "异常分析"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> topics = new ArrayList<>();
    private final List<View> settingsControls = new ArrayList<>();
    private AiSecretsStore secrets;
    private AiContextRepository context;
    private LinearLayout page, askPage, settingsPage, notesPage, settingsPanel, advancedSettingsPanel, learningPanel, resultCard;
    private LinearLayout composerCard, reviewPanel, reviewDetails, testReviewPanel;
    private ScrollView scroll;
    private EditText question, baseUrl, model, apiKey, outputLimit, dailyLimit, inputLimit, timeoutLimit, note;
    private CheckBox enabled, legacy;
    private TextView settingsSummary, keyStatus, usageStatus, requestStatus, resultText, resultMeta, captureStatus, notesText, compactModelStatus, settingsFeedback;
    private TextView journeyTitle, journeyHint, reviewSummary, reviewPayload, reviewSystem, resultTitle, followUpHint;
    private Button settingsToggle, previewButton, testButton, cancelButton, settingsCancelButton, saveSettingsButton, clearSettingsButton, saveNoteButton;
    private Button reviewDetailsButton, resultReturnButton;
    private String topic = TOPICS[0], screen = "ask";
    private boolean restoring, settingsDirty, saving, sending, preparing, loadingSettings, foreground, destroyed, settingsOnlyEntry;
    private AiSettings savedSettings = AiSettings.defaults();
    private boolean savedHasKey;
    private int usedToday;
    private long requestGeneration, draftConfigRevision = -1;
    private AiHttpClient.Cancellation cancellation;
    private Future<?> requestFuture;
    private AiRequestReview reviewedRequest;
    private AiFollowUpContext lastExchange, followUp;
    private String originLabel = "购票";
    private final Runnable preparePreview = this::prepareQuestionPreview;
    private String answer = "";
    private AiDraft suggestedDraft;
    private Button applyDraftButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // All entry paths, including Catalog's separate task, must stop device actions first.
        AppStore monitorState = new AppStore(this);
        MonitorService monitor = MonitorService.instance;
        if (monitor != null && (monitor.isRunning() || monitorState.isArmed()))
            monitor.pause("已打开 AI 辅助，监控保持暂停。");
        monitorState.setArmed(false);
        getSystemService(android.app.NotificationManager.class).cancel(12);
        // Credentials and manually entered questions must not enter screenshots, recents or view state.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        secrets = new AiSecretsStore(this); context = new AiContextRepository(this);
        String requested = state != null ? state.getString("topic", TOPICS[0]) : getIntent().getStringExtra("topic");
        for (String candidate : TOPICS) if (candidate.equals(requested)) topic = candidate;
        String origin = getIntent().getStringExtra("origin_label");
        if (origin != null && !origin.trim().isEmpty()) originLabel = origin.replaceAll("[\\r\\n]", " ").trim();
        if (originLabel.length() > 24) originLabel = originLabel.substring(0, 24);
        settingsOnlyEntry = "settings".equals(getIntent().getStringExtra("screen"));
        build();
        String initialQuestion = state == null ? getIntent().getStringExtra("question") : null;
        question.setText(initialQuestion == null || initialQuestion.trim().isEmpty() ? suggestedQuestion() : initialQuestion);
        restoreSettings();
        installListeners();
        refreshNotes(); refreshCapture(); reloadSettings(true);
        showScreen(state != null ? state.getString("screen", "ask") : getIntent().getStringExtra("screen"));
    }

    private void build() {
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        page = column(); page.setPadding(dp(18), dp(12), dp(18), dp(30)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll);
        askPage = column(); settingsPage = column(); notesPage = column();
        page.addView(askPage); page.addView(settingsPage); page.addView(notesPage);

        LinearLayout topBar = new LinearLayout(this); topBar.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("‹ 返回" + originLabel, false, v -> leaveAssistant());
        back.setBackgroundColor(Color.TRANSPARENT); back.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        topBar.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        settingsToggle = button("模型", false, v -> showScreen("settings"));
        settingsToggle.setBackgroundColor(Color.TRANSPARENT); topBar.addView(settingsToggle, new LinearLayout.LayoutParams(-2, -2));
        askPage.addView(topBar);
        askPage.addView(text("AI 购票助手", 12, ACCENT, true), spaced(16));
        journeyTitle = text("", 27, TEXT, true); askPage.addView(journeyTitle, spaced(5));
        journeyHint = text("", 14, MUTED, false); askPage.addView(journeyHint, spaced(7));

        // Replies occupy the first useful screen, instead of appearing underneath settings.
        LinearLayout result = card(askPage, 18); resultCard = result; result.setVisibility(View.GONE);
        resultTitle = text("给你的建议", 18, TEXT, true); result.addView(resultTitle);
        resultText = text("", 16, TEXT, false); resultText.setTextIsSelectable(true); resultText.setSaveEnabled(false); result.addView(resultText, spaced(12));
        applyDraftButton = button("核对并使用这份计划", true, v -> reviewDraft());
        applyDraftButton.setVisibility(View.GONE); result.addView(applyDraftButton, spaced(16));
        resultReturnButton = button("返回" + originLabel, true, v -> leaveAssistant());
        result.addView(resultReturnButton, spaced(12));
        result.addView(button("补充偏好 / 继续追问", false, v -> beginFollowUp()), spaced(8));
        result.addView(button("开始新问题", false, v -> beginNewQuestion()), spaced(6));
        LinearLayout resultTools = column(); resultTools.setVisibility(View.GONE);
        result.addView(button("更多 · 记录建议与调用详情", false, v -> resultTools.setVisibility(resultTools.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), spaced(8));
        resultMeta = text("", 12, MUTED, false); resultTools.addView(resultMeta, spaced(8));
        resultTools.addView(button("记下有用的建议", false, v -> useAnswerAsNote()), spaced(8));
        result.addView(resultTools);
        result.addView(text("AI 提供参考；余票与订单以大麦为准。", 12, MUTED, false), spaced(10));

        LinearLayout ask = card(askPage, 18); composerCard = ask;
        followUpHint = text("", 12, ACCENT, false); followUpHint.setVisibility(View.GONE); ask.addView(followUpHint);
        question = field(ask, "你想怎么选？", "补充你的偏好即可", false, 64000);
        question.setSingleLine(false); question.setMinLines(2); question.setMaxLines(6);
        question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        question.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); updateQuestionHint();
        Button suggestion = button("帮我梳理这一步", false, v -> question.setText(suggestedQuestion()));
        ask.addView(suggestion, spaced(10));
        reviewPanel = column(); ask.addView(reviewPanel, spaced(14));
        reviewSummary = text("正在整理可参考的信息…", 12, MUTED, false); reviewPanel.addView(reviewSummary);
        reviewDetailsButton = button("查看本次发送内容 ▾", false, v -> {
            boolean expanded = reviewDetails.getVisibility() != View.VISIBLE;
            reviewDetails.setVisibility(expanded ? View.VISIBLE : View.GONE);
            reviewDetailsButton.setText(expanded ? "收起发送内容 ▴" : "查看本次发送内容 ▾");
        });
        reviewDetailsButton.setVisibility(View.GONE);
        reviewDetailsButton.setMinHeight(dp(36)); reviewDetailsButton.setTextSize(12); reviewDetailsButton.setBackgroundColor(Color.TRANSPARENT);
        reviewDetailsButton.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); reviewPanel.addView(reviewDetailsButton, spaced(5));
        reviewDetails = column(); reviewDetails.setVisibility(View.GONE);
        reviewDetails.addView(text("问题与公开参考信息 · 实际发送正文", 12, MUTED, true), spaced(8));
        reviewPayload = text("", 13, TEXT, false); reviewPayload.setTextIsSelectable(true); reviewDetails.addView(reviewPayload, spaced(6));
        LinearLayout systemDetails = column(); systemDetails.setVisibility(View.GONE);
        reviewDetails.addView(button("查看给模型的工作说明 ▾", false, v -> systemDetails.setVisibility(systemDetails.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), spaced(8));
        reviewSystem = text("", 12, MUTED, false); reviewSystem.setTextIsSelectable(true); systemDetails.addView(reviewSystem, spaced(6));
        reviewDetails.addView(systemDetails); reviewPanel.addView(reviewDetails);
        previewButton = button("发送给 AI", true, v -> sendReviewedQuestion()); ask.addView(previewButton, spaced(14));
        requestStatus = text("本地整理信息，点击发送后才调用模型。", 12, MUTED, false); ask.addView(requestStatus, spaced(8));
        cancelButton = button("停止等待", false, v -> stopWaiting("已停止等待；已发出的请求可能仍产生费用。"));
        cancelButton.setVisibility(View.GONE); ask.addView(cancelButton, spaced(8));

        LinearLayout tools = card(askPage, 14);
        Button refreshContext = button("页面不一样？更新大麦信息", false, v -> requestCapture()); tools.addView(refreshContext);
        captureStatus = text("", 12, MUTED, false); tools.addView(captureStatus, spaced(6));
        LinearLayout moreTools = column(); moreTools.setVisibility(View.GONE);
        tools.addView(button("其他问题与经验笔记 ▾", false, v -> moreTools.setVisibility(moreTools.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), spaced(8));
        String[] topicLabels = {"帮我找演出", "帮我选场次", "帮我选票价", "为什么停下了"};
        for (int i = 0; i < TOPICS.length; i++) {
            final String value = TOPICS[i];
            Button item = button(topicLabels[i], false, v -> selectTopic(value));
            moreTools.addView(item, spaced(6)); topics.add(item);
        }
        moreTools.addView(button("我的经验笔记", false, v -> showScreen("notes")), spaced(8));
        compactModelStatus = text("正在读取模型设置…", 12, MUTED, false); moreTools.addView(compactModelStatus, spaced(8));
        tools.addView(moreTools);
        updateJourney();

        settingsPage.addView(button(settingsOnlyEntry ? "‹ 返回" + originLabel : "‹ 返回 AI 助手", false, v -> {
            if (settingsOnlyEntry) leaveAssistant(); else showScreen("ask");
        }));
        settingsPage.addView(text("模型设置", 28, TEXT, true), spaced(18));
        settingsPage.addView(text("使用你自己的接口、模型和密钥。", 14, MUTED, false), spaced(6));
        LinearLayout connection = card(settingsPage, 18);
        settingsSummary = text("正在读取设置…", 17, TEXT, true); connection.addView(settingsSummary);
        usageStatus = text("", 12, MUTED, false); connection.addView(usageStatus, spaced(6));
        settingsPanel = column(); connection.addView(settingsPanel, spaced(10));
        enabled = check("使用 AI 辅助"); settingsPanel.addView(enabled); settingsControls.add(enabled);
        baseUrl = field(settingsPanel, "接口地址", "https://你的服务商地址/v1", false, 2048);
        model = field(settingsPanel, "模型名称", "填写服务商提供的模型 ID", false, 200);
        apiKey = field(settingsPanel, "API Key", "留空保留当前密钥", false, 8192);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        apiKey.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | EditorInfo.IME_ACTION_DONE);
        apiKey.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) { redactMenu(menu); return true; }
            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { redactMenu(menu); return true; }
            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId(); return id == android.R.id.copy || id == android.R.id.cut || id == android.R.id.shareText;
            }
            @Override public void onDestroyActionMode(ActionMode mode) { }
        });
        keyStatus = text("", 12, MUTED, false); settingsPanel.addView(keyStatus, spaced(6));
        saveSettingsButton = button("保存模型设置", true, v -> saveSettings()); settingsPanel.addView(saveSettingsButton, spaced(14));
        settingsFeedback = text("", 13, ACCENT, false); settingsPanel.addView(settingsFeedback, spaced(8));
        testButton = button("测试连接", false, v -> previewTest()); settingsPanel.addView(testButton, spaced(8));
        testReviewPanel = column(); testReviewPanel.setVisibility(View.GONE); settingsPanel.addView(testReviewPanel, spaced(10));
        settingsCancelButton = button("停止等待", false, v -> stopWaiting("已停止等待；已发出的请求可能仍产生费用。"));
        settingsCancelButton.setVisibility(View.GONE); settingsPanel.addView(settingsCancelButton, spaced(8));

        LinearLayout capacity = card(settingsPage, 14);
        capacity.addView(text("用量由你决定", 18, TEXT, true));
        capacity.addView(text("可提高上限，也可让 App 不限制次数、由模型决定输出。服务商自己的限额与计费仍适用。", 13, MUTED, false), spaced(7));
        Button unlimited = button("每天不限次数", false, v -> {
            dailyLimit.setText("0"); advancedSettingsPanel.setVisibility(View.VISIBLE); toast("已设为不限次数，保存后生效。");
        });
        Button providerOutput = button("输出由模型决定", false, v -> {
            outputLimit.setText("0"); advancedSettingsPanel.setVisibility(View.VISIBLE); toast("已使用模型默认输出长度，保存后生效。");
        });
        capacity.addView(unlimited, spaced(12)); capacity.addView(providerOutput, spaced(8)); settingsControls.add(unlimited); settingsControls.add(providerOutput);
        capacity.addView(button("自定义额度与连接参数 ▾", false, v -> advancedSettingsPanel.setVisibility(advancedSettingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), spaced(8));
        advancedSettingsPanel = column(); advancedSettingsPanel.setVisibility(View.GONE); capacity.addView(advancedSettingsPanel);
        dailyLimit = field(advancedSettingsPanel, "每天最多发送次数", "0 = 不限制；可填 1–1000000", true, 9);
        outputLimit = field(advancedSettingsPanel, "每次最大输出 Token", "0 = 模型默认；可填 128–131072", true, 9);
        advancedSettingsPanel.addView(text("Token 是模型计算文字量的单位，不能直接等同于字数。", 12, MUTED, false), spaced(5));
        inputLimit = field(advancedSettingsPanel, "每次最多发送字符", "2000–262144，含问题和公开摘要", true, 9);
        advancedSettingsPanel.addView(text("包括问题和参考资料；单条问题最多 64000 字符。超过设置时会提示，不会悄悄截断后发送。", 12, MUTED, false), spaced(5));
        timeoutLimit = field(advancedSettingsPanel, "等待回复多久（秒）", "15–600", true, 9);
        settingsControls.add(outputLimit); settingsControls.add(dailyLimit); settingsControls.add(inputLimit); settingsControls.add(timeoutLimit);
        legacy = check("旧接口兼容：使用 max_tokens"); advancedSettingsPanel.addView(legacy, spaced(8)); settingsControls.add(legacy);
        advancedSettingsPanel.addView(text("仅当服务商要求 max_tokens 时开启。发出的请求即计入尝试次数；失败不会自动重试。", 12, MUTED, false), spaced(5));
        Button saveCapacity = button("保存额度与参数", true, v -> saveSettings());
        advancedSettingsPanel.addView(saveCapacity, spaced(12)); settingsControls.add(saveCapacity);

        LinearLayout privacy = card(settingsPage, 14);
        privacy.addView(button("密钥与发送内容说明", false, v -> message("密钥与发送内容", "密钥在本机加密保存，不进入备份或开发资料。更换接口地址时需要重新填写密钥。\n\n每次发送前会展示实际内容和服务商。普通监控不调用模型；模型建议不会直接执行点击或提交订单。")));
        clearSettingsButton = button("关闭 AI 并清除密钥", false, v -> confirmClear()); privacy.addView(clearSettingsButton, spaced(8));

        notesPage.addView(button("‹ 返回 AI 助手", false, v -> showScreen("ask")));
        notesPage.addView(text("我的经验笔记", 28, TEXT, true), spaced(18));
        notesPage.addView(text("记下你核对过的经验，供下次提问参考。", 14, MUTED, false), spaced(6));
        learningPanel = card(notesPage, 18);
        note = field(learningPanel, "想记住什么", "核对建议并删去私人信息，最多 2000 字", false, 2000);
        note.setSingleLine(false); note.setMinLines(3); note.setMaxLines(8);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        saveNoteButton = button("保存笔记", true, v -> saveNote()); learningPanel.addView(saveNoteButton, spaced(12));
        learningPanel.addView(text("笔记会在发送前供你检查，不会自动改变购票规则。", 12, MUTED, false), spaced(7));
        notesText = text("", 14, TEXT, false); notesText.setTextIsSelectable(true); notesText.setSaveEnabled(false); notesPage.addView(notesText, spaced(18));
        notesPage.addView(button("清空本机学习笔记", false, v -> confirmClearNotes()), spaced(14));
    }

    private void showScreen(String value) {
        String next = "settings".equals(value) ? "settings" : "notes".equals(value) ? "notes" : "ask";
        if (!screen.equals(next) && (sending || preparing)) {
            invalidatePreview(); stopWaiting("已离开本次分析，停止等待回复；已发出的请求可能仍产生费用。");
        }
        screen = next;
        askPage.setVisibility("ask".equals(screen) ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility("settings".equals(screen) ? View.VISIBLE : View.GONE);
        notesPage.setVisibility("notes".equals(screen) ? View.VISIBLE : View.GONE);
        View focused = getCurrentFocus();
        if (focused != null) {
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        scroll.scrollTo(0, 0);
        if ("ask".equals(screen)) schedulePreview(); else invalidatePreview();
    }

    private void leaveAssistant() {
        if (!settingsDirty) { finish(); return; }
        new AlertDialog.Builder(this).setTitle("模型设置还没保存")
                .setMessage("返回后，这次未保存的修改会丢失。已经保存的配置不会改变。")
                .setNegativeButton("继续编辑", (dialog, which) -> showScreen("settings"))
                .setPositiveButton("放弃修改并返回", (dialog, which) -> finish()).show();
    }

    @Override public void onBackPressed() {
        if ("settings".equals(screen) && settingsOnlyEntry) leaveAssistant();
        else if (!"ask".equals(screen)) showScreen("ask"); else leaveAssistant();
    }

    private void installListeners() {
        TextWatcher settingsWatcher = watcher(() -> {
            if (restoring) return;
            settingsDirty = true; settingsFeedback.setText("修改尚未保存。"); invalidatePreview(); cancelRequest("设置已修改，请先保存后重新预览。", true); refreshSettingsSummary();
        });
        for (EditText input : new EditText[]{baseUrl, model, apiKey, outputLimit, dailyLimit, inputLimit, timeoutLimit}) input.addTextChangedListener(settingsWatcher);
        enabled.setOnCheckedChangeListener((button, value) -> settingsWatcher.onTextChanged("", 0, 0, 0));
        legacy.setOnCheckedChangeListener((button, value) -> settingsWatcher.onTextChanged("", 0, 0, 0));
        question.addTextChangedListener(watcher(() -> {
            if (!restoring) { invalidatePreview(); cancelRequest("问题已修改，正在更新参考信息。", true);
                resultTitle.setText("上次的建议"); schedulePreview(); }
        }));
    }

    private void restoreSettings() {
        restoring = true;
        AiSettings settings = savedSettings;
        enabled.setChecked(settings.enabled); baseUrl.setText(settings.baseUrl); model.setText(settings.model);
        outputLimit.setText(Integer.toString(settings.maxOutputTokens)); dailyLimit.setText(Integer.toString(settings.dailyCallLimit));
        inputLimit.setText(Integer.toString(settings.maxInputChars)); timeoutLimit.setText(Integer.toString(settings.timeoutSeconds));
        legacy.setChecked(settings.legacyTokenLimit); apiKey.setText(""); settingsDirty = false;
        restoring = false; refreshSettingsSummary(); updateControls();
    }

    private void refreshSettingsSummary() {
        AiSettings current = savedSettings;
        String state = loadingSettings ? "正在读取设置…" : settingsDirty ? "有修改，保存后生效" : current.enabled && savedHasKey ? "模型已配置" : "AI 尚未开启";
        settingsSummary.setText(state);
        compactModelStatus.setText(settingsDirty ? "模型设置待保存" : current.enabled && savedHasKey ? "当前模型 · " + current.model : "先添加自己的模型，即可使用助手");
        keyStatus.setText(savedHasKey ? "已有加密保存的密钥。留空即可继续使用。" : "尚未保存密钥。");
        usageStatus.setText(usedToday < 0 ? "今天的用量记录暂不可读，已暂停发送。" : "今天已尝试 " + usedToday + " 次 · "
                + (current.dailyCallLimit == 0 ? "App 不限次数" : "每日上限 " + current.dailyCallLimit + " 次"));
    }

    private static final class ProviderState {
        final AiSettings settings; final boolean hasKey; final int used;
        ProviderState(AiSettings settings, boolean hasKey, int used) { this.settings = settings; this.hasKey = hasKey; this.used = used; }
    }

    /** Keystore operations stay on the worker; only nonsecret configuration is cached in the UI. */
    private ProviderState readProviderState() {
        return new ProviderState(secrets.loadSettings(), secrets.hasKey(), secrets.dailyUsed());
    }
    private void acceptProviderState(ProviderState state, boolean restoreForm) {
        savedSettings = state.settings; savedHasKey = state.hasKey; usedToday = state.used;
        if (restoreForm) restoreSettings(); else refreshSettingsSummary();
        updateControls();
    }
    private void reloadSettings(boolean restoreForm) {
        if (destroyed || loadingSettings || saving || sending || preparing) return;
        loadingSettings = true; refreshSettingsSummary(); updateControls();
        worker.execute(() -> {
            ProviderState state = readProviderState();
            handler.post(() -> {
                if (destroyed) return;
                loadingSettings = false;
                acceptProviderState(state, restoreForm && !settingsDirty);
                if ("ask".equals(screen)) schedulePreview();
            });
        });
    }

    private AiSettings readSettings() {
        int tokens, limit, chars, seconds;
        try {
            tokens = Integer.parseInt(outputLimit.getText().toString().trim()); limit = Integer.parseInt(dailyLimit.getText().toString().trim());
            chars = Integer.parseInt(inputLimit.getText().toString().trim()); seconds = Integer.parseInt(timeoutLimit.getText().toString().trim());
        } catch (NumberFormatException e) { throw new IllegalArgumentException("额度和等待时间请填写整数。次数填 0 表示不限，输出填 0 表示使用模型默认。"); }
        AiSettings settings = new AiSettings(enabled.isChecked(), baseUrl.getText().toString().trim(), model.getText().toString().trim(), tokens, limit, legacy.isChecked(), chars, seconds);
        List<String> errors = settings.validate();
        if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
        return settings;
    }

    private void saveSettings() {
        if (saving || loadingSettings) return;
        final AiSettings settings;
        try { settings = readSettings(); }
        catch (IllegalArgumentException e) { message("请检查设置", e.getMessage()); return; }
        cancelRequest("正在保存模型设置。", true); invalidatePreview();
        String enteredKey = apiKey.getText().toString().trim();
        restoring = true; apiKey.setText(""); restoring = false;
        saving = true; updateControls();
        worker.execute(() -> {
            boolean success; String reason = null;
            try { secrets.saveSettings(settings, enteredKey); success = true; }
            catch (java.io.IOException e) { success = false; reason = e.getMessage(); }
            catch (Exception e) { success = false; }
            final boolean saved = success; final String failure = reason;
            ProviderState metadata = readProviderState();
            handler.post(() -> {
                if (destroyed) return;
                saving = false;
                if (saved) { acceptProviderState(metadata, true); settingsFeedback.setText("已保存，可以返回 AI 助手。"); showRequestStatus("模型设置已保存。"); toast("模型设置已保存"); }
                else { settingsDirty = true; updateControls(); String error = failure == null ? "未能保存。请核对接口、模型和新密钥。" : failure; settingsFeedback.setText(error); showRequestStatus(error); }
                refreshSettingsSummary();
            });
        });
    }

    private AiSettings configured() {
        if (saving || sending || preparing || loadingSettings) { toast("请先结束当前操作。"); return null; }
        if (settingsDirty) { showScreen("settings"); settingsFeedback.setText("先保存刚才的修改，再继续提问。"); return null; }
        if (usedToday < 0) { message("用量记录暂不可读", "本次不会发送。请稍后重新打开助手；不需要清除你的模型配置。"); return null; }
        AiSettings settings = savedSettings;
        if (!settings.enabled || !settings.validate().isEmpty() || !savedHasKey) {
            showScreen("settings");
            settingsFeedback.setText("第一次使用：填写接口、模型和密钥，开启 AI 并保存。"); return null;
        }
        return settings;
    }

    /** Preparing this card reads only local, curated context; it never starts a network request. */
    private void schedulePreview() {
        handler.removeCallbacks(preparePreview);
        if (destroyed || !"ask".equals(screen) || sending || saving || loadingSettings || settingsDirty) return;
        handler.postDelayed(preparePreview, 350);
    }

    private void prepareQuestionPreview() {
        if (destroyed || !foreground || !"ask".equals(screen) || sending || saving || loadingSettings || settingsDirty) return;
        if (!savedSettings.enabled || !savedHasKey || !savedSettings.validate().isEmpty()) {
            invalidatePreview(); reviewSummary.setText("先连接你自己的模型，之后每次只需点发送。");
            showRequestStatus("点击下方按钮设置模型，当前不会发送。"); updateControls(); return;
        }
        if (question.getText().toString().trim().isEmpty()) {
            invalidatePreview(); reviewSummary.setText("说说你的偏好，或点“帮我梳理这一步”。"); updateControls(); return;
        }
        final AiSettings settings = savedSettings;
        final String selectedTopic = topic, userQuestion = question.getText().toString();
        final AiFollowUpContext prior = followUp;
        // Cancel obsolete local work before capturing a new generation.
        if (preparing) cancelRequest("", false);
        invalidatePreview();
        final long generation = ++requestGeneration;
        preparing = true; reviewSummary.setText("正在本机整理相关信息…"); updateControls();
        requestFuture = worker.submit(() -> {
            AiContextRepository.Preview prepared = null;
            String failure = null;
            try { prepared = context.preview(selectedTopic, prior == null ? userQuestion : prior.compose(userQuestion), settings); }
            catch (IllegalArgumentException e) { failure = e.getMessage(); }
            catch (Exception ignored) { }
            final AiContextRepository.Preview preview = prepared; final String previewFailure = failure;
            handler.post(() -> {
                if (destroyed || !foreground || generation != requestGeneration) return;
                preparing = false; requestFuture = null;
                if (preview == null) {
                    reviewSummary.setText(previewFailure == null ? "参考信息暂时没准备好，点击下方按钮重试。" : previewFailure);
                    showRequestStatus("尚未发送任何内容。"); updateControls(); return;
                }
                reviewedRequest = new AiRequestReview(settings, preview.systemPrompt, preview.userPrompt, generation, false);
                reviewSummary.setText("将发送给 " + providerHost(settings) + " · " + settings.model
                        + "\n参考：" + referenceSummary(preview.userPrompt) + "。可以展开核对。"
                        + (prior == null ? "" : "\n" + prior.notice()));
                reviewPayload.setText(preview.userPrompt); reviewSystem.setText(preview.systemPrompt);
                reviewDetailsButton.setVisibility(View.VISIBLE);
                showRequestStatus("点击发送后，调用一次你配置的模型。"); updateControls();
            });
        });
    }

    private void sendReviewedQuestion() {
        AiSettings settings = configured(); if (settings == null) return;
        AiRequestReview request = reviewedRequest;
        if (request == null || !request.matches(settings, requestGeneration, false)) {
            prepareQuestionPreview(); return;
        }
        if (question.getText().toString().trim().isEmpty()) { question.requestFocus(); return; }
        send(request.settings, request.systemPrompt, request.userPrompt, false);
    }

    private void previewTest() {
        AiSettings settings = configured(); if (settings == null) return;
        invalidatePreview();
        final String system = "你是连接测试助手。仅回复：连接正常。";
        final String prompt = "这是由用户主动发起的一次连接测试，请仅回复：连接正常。";
        reviewedRequest = new AiRequestReview(settings, system, prompt, requestGeneration, true);
        testReviewPanel.removeAllViews(); testReviewPanel.setVisibility(View.VISIBLE);
        testReviewPanel.addView(text("发送到 " + providerHost(settings) + " · " + settings.model, 13, TEXT, true));
        testReviewPanel.addView(text("只发送固定测试文字，不带演出或页面资料；计一次调用。", 12, MUTED, false), spaced(6));
        testReviewPanel.addView(text("系统：" + system + "\n消息：" + prompt, 12, MUTED, false), spaced(8));
        testReviewPanel.addView(button("发送这条测试", true, v -> {
            AiRequestReview request = reviewedRequest;
            if (request != null && request.matches(savedSettings, requestGeneration, true) && foreground && !destroyed
                    && !settingsDirty && !saving && !sending && !preparing && !loadingSettings)
                send(request.settings, request.systemPrompt, request.userPrompt, true);
        }), spaced(10));
        scrollTo(testReviewPanel);
    }

    private String providerHost(AiSettings settings) {
        try { return new URI(settings.endpoint()).getHost(); } catch (Exception e) { return "已配置接口"; }
    }

    private String referenceSummary(String payload) {
        List<String> references = new ArrayList<>(); references.add("你的问题与当前偏好");
        if (payload.contains("历史目录读取时间：")) references.add("已读取的演出目录");
        if (payload.contains("历史页面摘要（")) references.add("带时间的页面摘要");
        if (payload.contains("[用户确认的未验证笔记")) references.add("本机经验笔记");
        return String.join("、", references);
    }

    private void send(AiSettings settings, String systemPrompt, String userPrompt, boolean test) {
        handler.removeCallbacks(preparePreview);
        reviewedRequest = null;
        if (test) testReviewPanel.setVisibility(View.GONE);
        if (!test) { suggestedDraft = null; applyDraftButton.setVisibility(View.GONE); resultCard.setVisibility(View.GONE); }
        sending = true; cancellation = new AiHttpClient.Cancellation();
        AiHttpClient.Cancellation currentCancellation = cancellation;
        long generation = ++requestGeneration;
        showRequestStatus(test ? "正在测试连接……" : "正在分析；你可以停止等待。"); updateControls();
        requestFuture = worker.submit(() -> {
            AiHttpClient.Result result = null; String failure = null;
            try {
                String key = secrets.readKeyFor(settings);
                if (currentCancellation.isCancelled()) return;
                secrets.reserveCall(settings.dailyCallLimit);
                result = AiHttpClient.complete(settings, key, systemPrompt, userPrompt, currentCancellation);
            } catch (AiHttpClient.Failure e) { failure = e.getMessage(); }
            catch (java.io.IOException e) { failure = e.getMessage(); }
            catch (Exception e) { failure = "本次未能调用。请检查密钥、当天次数上限和网络连接；不会自动重试。"; }
            AiHttpClient.Result completed = result; String error = failure;
            ProviderState metadata = readProviderState();
            handler.post(() -> {
                if (destroyed || !foreground || generation != requestGeneration) return;
                sending = false; cancellation = null; requestFuture = null; acceptProviderState(metadata, false);
                if (completed == null) { String failureText = error == null ? "本次调用已结束，没有可显示结果。" : error; showRequestStatus(failureText); if (test) settingsFeedback.setText(failureText); return; }
                if (test) { showRequestStatus("模型连接正常。"); settingsFeedback.setText("连接正常，已收到模型回复。"); }
                else {
                    answer = completed.text;
                    lastExchange = new AiFollowUpContext(userPrompt, completed.text);
                    showRequestStatus("分析已返回。请核对建议；不会自动执行。");
                    try {
                        AppStore current = new AppStore(this); draftConfigRevision = current.getConfigRevision();
                        suggestedDraft = TOPICS[0].equals(topic) ? AiDraft.fromResponse(answer, current.loadConfig()) : null;
                    } catch (Exception ignored) { suggestedDraft = null; draftConfigRevision = -1; }
                    String readable = suggestedDraft == null ? answer : answer.trim().startsWith("{") ? suggestedDraft.summary
                            : answer.replaceFirst("(?is)```json\\s*.*?```", "").trim();
                    resultText.setText(readable.isEmpty() && suggestedDraft != null ? suggestedDraft.summary : readable);
                    resultCard.setVisibility(View.VISIBLE);
                    resultTitle.setText("给你的建议");
                    applyDraftButton.setVisibility(suggestedDraft == null ? View.GONE : View.VISIBLE);
                    resultReturnButton.setVisibility(suggestedDraft == null ? View.VISIBLE : View.GONE);
                    composerCard.setVisibility(View.GONE);
                    scrollTo(resultCard);
                }
                resultMeta.setText((test ? "连接测试 · " : "") + String.format(java.util.Locale.ROOT, "%.1f 秒", completed.elapsedMs / 1000.0)
                        + " · 输入 Token " + tokenCount(completed.promptTokens) + " · 输出 Token " + tokenCount(completed.completionTokens));
                if (test) settingsFeedback.setText("连接正常。" + resultMeta.getText());
            });
        });
    }

    private String tokenCount(long count) { return count < 0 ? "服务商未提供" : Long.toString(count); }

    private void showRequestStatus(String value) {
        requestStatus.setText(value);
        settingsFeedback.setText(value);
    }

    private void stopWaiting(String reason) {
        cancelRequest(reason, true);
        reloadSettings(false); // A cancelled request may already have reserved its daily attempt.
    }

    private void cancelRequest(String reason, boolean show) {
        requestGeneration++;
        if (cancellation != null) cancellation.cancel();
        if (requestFuture != null) requestFuture.cancel(true);
        boolean wasSending = sending || preparing;
        reviewedRequest = null;
        sending = false; preparing = false; cancellation = null; requestFuture = null;
        if (!destroyed && previewButton != null) {
            updateControls();
            if (show && (wasSending || settingsDirty)) showRequestStatus(reason);
        }
    }

    private void invalidatePreview() {
        handler.removeCallbacks(preparePreview);
        reviewedRequest = null;
        if (reviewDetails != null) {
            reviewDetails.setVisibility(View.GONE); reviewDetailsButton.setText("查看本次发送内容 ▾");
            reviewDetailsButton.setVisibility(View.GONE); reviewPayload.setText(""); reviewSystem.setText("");
        }
        if (testReviewPanel != null) { testReviewPanel.removeAllViews(); testReviewPanel.setVisibility(View.GONE); }
    }

    private void beginFollowUp() {
        if (lastExchange == null || sending || preparing) return;
        invalidatePreview(); cancelRequest("", false);
        followUp = lastExchange;
        followUpHint.setText(followUp.notice()); followUpHint.setVisibility(View.VISIBLE);
        composerCard.setVisibility(View.VISIBLE); resultTitle.setText("上一轮的建议");
        question.setText(""); question.setHint("直接回答上一条问题，或补充你的偏好");
        scrollTo(composerCard); question.requestFocus();
    }

    private void beginNewQuestion() {
        if (sending || preparing) return;
        invalidatePreview(); cancelRequest("", false);
        followUp = null; lastExchange = null; suggestedDraft = null; answer = "";
        followUpHint.setVisibility(View.GONE); resultCard.setVisibility(View.GONE);
        applyDraftButton.setVisibility(View.GONE); composerCard.setVisibility(View.VISIBLE);
        question.setText(""); updateQuestionHint(); scrollTo(composerCard); question.requestFocus();
    }

    private void selectTopic(String value) {
        followUp = null; lastExchange = null; followUpHint.setVisibility(View.GONE);
        topic = value; updateQuestionHint(); invalidatePreview(); cancelRequest("已切换问题。", true);
        updateJourney(); resultCard.setVisibility(View.GONE); composerCard.setVisibility(View.VISIBLE);
        question.setText(suggestedQuestion()); schedulePreview(); scroll.scrollTo(0, 0);
    }

    private void updateJourney() {
        String title, hint;
        switch (topic) {
            case "场次比较": title = "这几场，怎么选？"; hint = "我会结合已读取的场次，帮你比较时间和偏好。"; break;
            case "票档解释": title = "选一档合适的票"; hint = "说出预算或座位偏好，我帮你看懂当前票档。"; break;
            case "异常分析": title = "一起看看卡在哪"; hint = "结合最近一次状态，解释原因并给出下一步。"; break;
            default: title = "想看什么，说一句就好"; hint = "告诉我演出、城市或预算，先帮你整理选择。"; break;
        }
        journeyTitle.setText(title); journeyHint.setText(hint);
    }

    private String suggestedQuestion() {
        switch (topic) {
            case "场次比较": return "帮我比较已读取的场次，说明时间差异，并告诉我下一步怎么选。";
            case "票档解释": return "帮我比较当前票档和预算，解释哪些信息还不确定，再给出选择建议。";
            case "异常分析": return "根据最近一次状态，告诉我为什么停下，以及现在该点哪里或检查什么。";
            default: return "帮我梳理当前想看的演出和购票偏好；信息不足时先问我一个最必要的问题。";
        }
    }

    private void scrollTo(View target) {
        View focused = getCurrentFocus();
        if (focused != null) {
            android.view.inputmethod.InputMethodManager keyboard = getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            focused.clearFocus();
        }
        scroll.post(() -> {
            android.graphics.Rect bounds = new android.graphics.Rect(); target.getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(target, bounds); scroll.smoothScrollTo(0, Math.max(0, bounds.top - dp(12)));
        });
    }

    private void updateQuestionHint() {
        if (question == null) return;
        switch (topic) {
            case "场次比较": question.setHint("例如：我只能周末去，已读到的场次怎么选？"); break;
            case "票档解释": question.setHint("例如：预算 1500 元，这些票档和缺货标记怎么理解？"); break;
            case "异常分析": question.setHint("例如：选票没有变化，下一步该检查什么？"); break;
            default: question.setHint("例如：想看杭州的演唱会，一张，预算 1500 元，优先周末。"); break;
        }
    }

    private void requestCapture() {
        if (saving || sending || preparing || loadingSettings) { toast("请先结束当前操作。"); return; }
        new AlertDialog.Builder(this).setTitle("读取一次大麦页面")
                .setMessage("先在大麦打开要看的页面。点开始后，在 25 秒内切回大麦，随后回到这里。\n\n这一步只读取公开信息，不点击、不发送给模型。")
                .setNegativeButton("取消", null).setPositiveButton("开始读取", (dialog, which) -> {
                    MonitorService service = MonitorService.instance;
                    if (service == null || !service.requestAiPageRead()) {
                        message("页面读取尚未就绪", "请先在助手设置中连接“票务助手 · 界面读取”，再试一次。"); return;
                    }
                    invalidatePreview();
                    showRequestStatus("请在 25 秒内切回大麦，随后回到这里查看摘要。"); refreshCapture();
                }).show();
    }

    private void refreshCapture() {
        try { captureStatus.setText(context.describeCapture()); }
        catch (Exception e) { captureStatus.setText("尚无可用页面摘要，可先读取一次大麦当前页。"); }
    }

    private void useAnswerAsNote() {
        if (answer.isEmpty()) { toast("先完成一次分析，再整理成笔记。"); return; }
        showScreen("notes");
        String readable = resultText.getText().toString();
        note.setText(readable.length() > 2000 ? readable.substring(0, 2000) : readable);
        note.requestFocus();
        toast("请核对并删去私人信息，再保存。");
    }

    private void saveNote() {
        String value = note.getText().toString().trim();
        if (value.isEmpty()) { toast("先填写要保留的经验。"); return; }
        new AlertDialog.Builder(this).setTitle("保存为未验证笔记")
                .setMessage("这段笔记只保存在本机，后续分析的发送预览中会列出。它不会成为点击规则或购票授权。确认已删除私人信息后保存。")
                .setNegativeButton("返回编辑", null).setPositiveButton("确认保存", (dialog, which) -> {
                    try { context.saveNote(value); note.setText(""); refreshNotes(); invalidatePreview(); schedulePreview(); toast("已保存为未验证笔记"); }
                    catch (Exception e) { message("未能保存", "笔记可能超出限制或存储不可用，请缩短内容后重试。"); }
                }).show();
    }

    private void reviewDraft() {
        if (suggestedDraft == null || saving || sending || preparing || loadingSettings) return;
        final AiDraft draft = suggestedDraft;
        final long revision = draftConfigRevision;
        if (new AppStore(this).getConfigRevision() != revision) {
            suggestedDraft = null; applyDraftButton.setVisibility(View.GONE);
            message("目标已变化", "请根据当前目标重新分析，再使用新的草稿。"); return;
        }
        new AlertDialog.Builder(this).setTitle("核对 AI 整理的草稿")
                .setMessage(draft.summary + "\n\n这里只保存待核对草稿，不会开始监控。接下来仍需从真实大麦选择演出和场次，确认日期、票档与金额。")
                .setNegativeButton("返回修改", null).setPositiveButton("保存草稿并去核对", (dialog, which) -> {
                    try {
                        if (new AppStore(this).getConfigRevision() != revision) {
                            message("目标已变化", "这份草稿尚未保存，请重新分析并核对当前目标。"); return;
                        }
                        context.applyDraft(draft);
                        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                .putExtra("show_task", true));
                        finish();
                    } catch (Exception e) { message("草稿尚未保存", "目标可能已变化或字段不完整，请重新分析并核对后再试。"); }
                }).show();
    }

    private void refreshNotes() {
        try { String value = context.listNotes(); notesText.setText(value == null || value.trim().isEmpty() ? "还没有学习笔记。" : value); }
        catch (Exception e) { notesText.setText("暂时无法读取笔记。"); }
    }

    private void confirmClearNotes() {
        new AlertDialog.Builder(this).setTitle("清空本机学习笔记？")
                .setMessage("只删除 AI 学习笔记，演出目标和订单防重复记录不变。")
                .setNegativeButton("取消", null).setPositiveButton("清空笔记", (dialog, which) -> {
                    try { context.clearNotes(); refreshNotes(); invalidatePreview(); schedulePreview(); toast("笔记已清空"); }
                    catch (Exception e) { message("未能清空", "存储暂时不可用，请稍后重试。"); }
                }).show();
    }

    private void confirmClear() {
        if (saving || loadingSettings) return;
        new AlertDialog.Builder(this).setTitle("关闭 AI 并清除密钥？")
                .setMessage("停止当前请求并删除 AI 连接配置。不会删除演出目标、学习笔记或订单防重复记录。")
                .setNegativeButton("取消", null).setPositiveButton("清除", (dialog, which) -> {
                    cancelRequest("正在清除 AI 配置。", true); invalidatePreview(); saving = true; updateControls();
                    worker.execute(() -> {
                        boolean success;
                        try { secrets.clear(); success = true; } catch (Exception e) { success = false; }
                        final boolean cleared = success;
                        ProviderState metadata = readProviderState();
                        handler.post(() -> {
                            if (destroyed) return; saving = false;
                            if (cleared) { acceptProviderState(metadata, true); showRequestStatus("AI 已关闭，连接配置与密钥已清除。"); settingsFeedback.setText("AI 已关闭，密钥已清除。"); }
                            else { showRequestStatus("未能清除，请稍后重试。"); settingsFeedback.setText("未能清除，请稍后重试。"); updateControls(); }
                        });
                    });
                }).show();
    }

    private void updateControls() {
        boolean ready = !saving && !sending && !preparing && !loadingSettings;
        boolean configured = savedSettings.enabled && savedHasKey && savedSettings.validate().isEmpty();
        previewButton.setText(sending ? "正在分析…" : preparing || loadingSettings ? "正在整理…"
                : !configured ? "连接我的模型" : reviewedRequest == null ? "重新整理参考信息" : "发送给 AI");
        previewButton.setEnabled(ready && usedToday >= 0 && (!configured || !question.getText().toString().trim().isEmpty()));
        testButton.setEnabled(ready && usedToday >= 0);
        saveSettingsButton.setEnabled(!saving && !loadingSettings); clearSettingsButton.setEnabled(!saving && !loadingSettings);
        settingsToggle.setEnabled(!saving); saveNoteButton.setEnabled(ready); applyDraftButton.setEnabled(ready);
        for (View control : settingsControls) control.setEnabled(!saving && !loadingSettings);
        cancelButton.setVisibility(sending || preparing ? View.VISIBLE : View.GONE);
        settingsCancelButton.setVisibility(sending || preparing ? View.VISIBLE : View.GONE);
    }

    @Override protected void onStart() { super.onStart(); foreground = true; if (context != null) { refreshCapture(); reloadSettings(false); } }
    @Override protected void onStop() {
        foreground = false; invalidatePreview(); cancelRequest("已切到后台，停止等待模型回复；不会自动重试。", true);
        restoring = true; if (apiKey != null) apiKey.setText(""); restoring = false;
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true; invalidatePreview(); cancelRequest("", false); handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("topic", topic); state.putString("screen", screen); super.onSaveInstanceState(state); }

    private void redactMenu(Menu menu) {
        for (int index = menu.size() - 1; index >= 0; index--) {
            int id = menu.getItem(index).getItemId();
            if (id != android.R.id.paste && id != android.R.id.pasteAsPlainText && id != android.R.id.selectAll) menu.removeItem(id);
        }
    }
    private TextWatcher watcher(Runnable changed) { return new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence value, int start, int before, int count) { changed.run(); }
        @Override public void afterTextChanged(Editable value) { }
    }; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card(LinearLayout parent, int top) { LinearLayout view = column(); view.setPadding(dp(16), dp(17), dp(16), dp(17)); view.setBackground(background(CARD, BORDER)); parent.addView(view, spaced(top)); return view; }
    private TextView text(String value, int size, int color, boolean bold) { TextView view = new TextView(this); view.setText(value); view.setTextColor(color); view.setTextSize(size); view.setLineSpacing(dp(3), 1); view.setSaveEnabled(false); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private EditText field(LinearLayout parent, String label, String hint, boolean numeric, int length) {
        parent.addView(text(label, 12, MUTED, true), spaced(13));
        EditText input = new EditText(this); input.setTextColor(TEXT); input.setTextSize(15); input.setHintTextColor(MUTED); input.setHint(hint);
        input.setSingleLine(true); input.setMinHeight(dp(50)); input.setPadding(dp(12), dp(10), dp(12), dp(10)); input.setBackground(background(BG, BORDER));
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)}); input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); input.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO);
        input.setContentDescription(label); parent.addView(input, spaced(6));
        if (parent == settingsPanel) settingsControls.add(input);
        return input;
    }
    private CheckBox check(String value) { CheckBox view = new CheckBox(this); view.setText(value); view.setTextColor(TEXT); view.setTextSize(14); view.setMinHeight(dp(48)); view.setSaveEnabled(false); view.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT)); return view; }
    private Button button(String value, boolean primary, View.OnClickListener click) { Button view = new Button(this); view.setText(value); view.setAllCaps(false); view.setTextSize(14); view.setTextColor(primary ? Color.WHITE : TEXT); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setMinHeight(dp(50)); view.setPadding(dp(10), dp(10), dp(10), dp(10)); view.setBackground(background(primary ? ACCENT : CARD, primary ? ACCENT : BORDER)); view.setStateListAnimator(null); view.setOnClickListener(click); return view; }
    private GradientDrawable background(int color, int border) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(12)); drawable.setStroke(dp(1), border); return drawable; }
    private LinearLayout.LayoutParams spaced(int margin) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(margin); return params; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }
    private void message(String title, String value) { new AlertDialog.Builder(this).setTitle(title).setMessage(value).setPositiveButton("知道了", null).show(); }
}
