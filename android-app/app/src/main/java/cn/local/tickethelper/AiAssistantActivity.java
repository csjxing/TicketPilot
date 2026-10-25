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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.local.tickethelper.ai.AiContextRepository;
import cn.local.tickethelper.ai.AiHttpClient;
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
    private LinearLayout page, settingsPanel, advancedSettingsPanel, learningPanel, resultCard;
    private ScrollView scroll;
    private EditText question, baseUrl, model, apiKey, outputLimit, dailyLimit, note;
    private CheckBox enabled, legacy;
    private TextView settingsSummary, keyStatus, usageStatus, requestStatus, resultText, resultMeta, captureStatus, notesText;
    private Button settingsToggle, previewButton, testButton, cancelButton, saveSettingsButton, clearSettingsButton, saveNoteButton;
    private String topic = TOPICS[0];
    private boolean restoring, settingsDirty, saving, sending, preparing, loadingSettings, foreground, destroyed;
    private AiSettings savedSettings = AiSettings.defaults();
    private boolean savedHasKey;
    private int usedToday;
    private long requestGeneration, draftConfigRevision = -1;
    private AiHttpClient.Cancellation cancellation;
    private Future<?> requestFuture;
    private AlertDialog previewDialog;
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
        build();
        restoreSettings();
        installListeners();
        refreshNotes(); refreshCapture(); reloadSettings(true);
    }

    private void build() {
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        page = column(); page.setPadding(dp(18), dp(12), dp(18), dp(30)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll);
        page.addView(button("‹ 返回票务助手", false, v -> finish()), spaced(0));
        page.addView(text("AI 辅助", 28, TEXT, true), spaced(18));
        page.addView(text("把难理解的配置和页面，整理成下一步建议。", 15, MUTED, false), spaced(6));
        page.addView(text("仅在你确认发送后调用模型。普通监控继续用本地规则；AI 不点击、不启动监控，也不提交订单。", 13, MUTED, false), spaced(10));

        LinearLayout connection = card(page, 18);
        settingsSummary = text("AI 尚未配置", 17, TEXT, true); connection.addView(settingsSummary);
        usageStatus = text("", 12, MUTED, false); connection.addView(usageStatus, spaced(7));
        settingsToggle = button("设置模型与密钥", false, v -> toggleSettings()); connection.addView(settingsToggle, spaced(12));
        settingsPanel = column(); settingsPanel.setVisibility(View.GONE); connection.addView(settingsPanel, spaced(10));
        enabled = check("启用 AI 辅助（默认关闭）"); settingsPanel.addView(enabled); settingsControls.add(enabled);
        baseUrl = field(settingsPanel, "接口地址", "https://你的服务商地址/v1", false, 2048);
        model = field(settingsPanel, "模型名称", "填写服务商提供的模型 ID", false, 200);
        apiKey = field(settingsPanel, "API Key", "粘贴新密钥；留空保留已保存密钥", false, 8192);
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
        settingsPanel.addView(text("密钥由本机 Keystore 加密保存，不进入备份、源码包或检查日志。更换接口地址后需要重新填写密钥。", 12, MUTED, false), spaced(8));
        settingsPanel.addView(button("用量与接口兼容设置 ▾", false, v -> advancedSettingsPanel.setVisibility(advancedSettingsPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), spaced(10));
        advancedSettingsPanel = column(); advancedSettingsPanel.setVisibility(View.GONE); settingsPanel.addView(advancedSettingsPanel);
        outputLimit = field(advancedSettingsPanel, "每次最大输出 Token", "1024（128–4096）", true, 9);
        dailyLimit = field(advancedSettingsPanel, "每天最多发送次数", "20（1–100）", true, 9);
        settingsControls.add(outputLimit); settingsControls.add(dailyLimit);
        legacy = check("兼容旧接口：使用 max_tokens"); advancedSettingsPanel.addView(legacy, spaced(8)); settingsControls.add(legacy);
        advancedSettingsPanel.addView(text("默认使用 max_completion_tokens。若服务商仅支持 max_tokens，再开启此项。调用可能产生费用；失败和取消前已发送的请求也计入次数，不自动重试。", 12, MUTED, false), spaced(5));
        saveSettingsButton = button("保存模型设置", true, v -> saveSettings()); settingsPanel.addView(saveSettingsButton, spaced(12));
        testButton = button("测试连接", false, v -> previewTest()); settingsPanel.addView(testButton, spaced(8));
        clearSettingsButton = button("关闭 AI 并清除密钥", false, v -> confirmClear()); settingsPanel.addView(clearSettingsButton, spaced(8));

        LinearLayout ask = card(page, 14);
        ask.addView(text("想让 AI 帮你看什么？", 19, TEXT, true));
        HorizontalScrollView topicScroll = new HorizontalScrollView(this); topicScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout topicRow = new LinearLayout(this); topicRow.setOrientation(LinearLayout.HORIZONTAL); topicScroll.addView(topicRow);
        for (String value : TOPICS) {
            Button item = button(value, value.equals(topic), v -> selectTopic(value));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.rightMargin = dp(8); topicRow.addView(item, p); topics.add(item);
        }
        ask.addView(topicScroll, spaced(12));
        question = field(ask, "你的问题（选填）", "例如：两个场次有什么区别？为什么票档状态仍未知？", false, 2000);
        question.setSingleLine(false); question.setMinLines(2); question.setMaxLines(6);
        question.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        question.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); updateQuestionHint();
        captureStatus = text("", 12, MUTED, false); ask.addView(captureStatus, spaced(10));
        ask.addView(button("读取大麦当前页", false, v -> requestCapture()), spaced(10));
        ask.addView(text("只读取一次公开页面摘要。没有刚读取的页面时，也可使用已保存目标或最近的场次列表；发送前会显示来源和时间。", 12, MUTED, false), spaced(6));
        previewButton = button("预览发送内容", true, v -> previewQuestion()); ask.addView(previewButton, spaced(14));
        requestStatus = text("尚未发送。先预览，再决定是否调用模型。", 13, MUTED, false); ask.addView(requestStatus, spaced(10));
        cancelButton = button("停止等待", false, v -> cancelRequest("已停止等待；已发出的请求可能仍产生费用。", true));
        cancelButton.setVisibility(View.GONE); ask.addView(cancelButton, spaced(8));

        LinearLayout result = card(page, 14); resultCard = result; result.setVisibility(View.GONE);
        result.addView(text("分析建议", 19, TEXT, true));
        result.addView(text("模型可能判断错误。页面信号不代表锁票；价格、库存和订单仍以大麦实际页面为准。", 12, MUTED, false), spaced(7));
        resultText = text("发送后，分析会显示在这里。", 15, TEXT, false); resultText.setTextIsSelectable(true); resultText.setSaveEnabled(false); result.addView(resultText, spaced(12));
        resultMeta = text("", 12, MUTED, false); result.addView(resultMeta, spaced(8));
        applyDraftButton = button("使用这份草稿，去大麦核对", true, v -> reviewDraft());
        applyDraftButton.setVisibility(View.GONE); result.addView(applyDraftButton, spaced(12));
        result.addView(button("把这条建议整理成笔记", false, v -> useAnswerAsNote()), spaced(12));

        LinearLayout learning = card(page, 14);
        learning.addView(text("本机学习笔记", 19, TEXT, true));
        learning.addView(text("核对后再积累经验，后续分析可参考。", 13, MUTED, false), spaced(7));
        learning.addView(button("查看和整理学习笔记 ▾", false, v -> learningPanel.setVisibility(learningPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), spaced(10));
        learningPanel = column(); learningPanel.setVisibility(View.GONE); learning.addView(learningPanel);
        learningPanel.addView(text("这是未验证笔记，不会自动变成规则，也不会改变点击或下单逻辑。", 12, MUTED, false), spaced(10));
        note = field(learningPanel, "确认要保留的经验", "先核对、删去私人信息，再保存（最多 2000 字）", false, 2000);
        note.setSingleLine(false); note.setMinLines(3); note.setMaxLines(8);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        saveNoteButton = button("保存为未验证笔记", false, v -> saveNote()); learningPanel.addView(saveNoteButton, spaced(10));
        notesText = text("", 13, MUTED, false); notesText.setTextIsSelectable(true); notesText.setSaveEnabled(false); learningPanel.addView(notesText, spaced(12));
        learningPanel.addView(button("清空本机学习笔记", false, v -> confirmClearNotes()), spaced(10));
    }

    private void installListeners() {
        TextWatcher settingsWatcher = watcher(() -> {
            if (restoring) return;
            settingsDirty = true; invalidatePreview(); cancelRequest("设置已修改，请先保存后重新预览。", true); refreshSettingsSummary();
        });
        for (EditText input : new EditText[]{baseUrl, model, apiKey, outputLimit, dailyLimit}) input.addTextChangedListener(settingsWatcher);
        enabled.setOnCheckedChangeListener((button, value) -> settingsWatcher.onTextChanged("", 0, 0, 0));
        legacy.setOnCheckedChangeListener((button, value) -> settingsWatcher.onTextChanged("", 0, 0, 0));
        question.addTextChangedListener(watcher(() -> {
            if (!restoring) { invalidatePreview(); cancelRequest("问题已修改，请重新预览。", true); }
        }));
    }

    private void restoreSettings() {
        restoring = true;
        AiSettings settings = savedSettings;
        enabled.setChecked(settings.enabled); baseUrl.setText(settings.baseUrl); model.setText(settings.model);
        outputLimit.setText(Integer.toString(settings.maxOutputTokens)); dailyLimit.setText(Integer.toString(settings.dailyCallLimit));
        legacy.setChecked(settings.legacyTokenLimit); apiKey.setText(""); settingsDirty = false;
        restoring = false; refreshSettingsSummary(); updateControls();
    }

    private void toggleSettings() {
        boolean visible = settingsPanel.getVisibility() != View.VISIBLE;
        settingsPanel.setVisibility(visible ? View.VISIBLE : View.GONE);
        settingsToggle.setText(visible ? "收起模型设置" : "设置模型与密钥");
    }

    private void refreshSettingsSummary() {
        AiSettings current = savedSettings;
        settingsSummary.setText(loadingSettings ? "正在读取本机 AI 设置…" : settingsDirty ? "设置有修改，尚未保存" : current.enabled && savedHasKey ? "AI 已配置 · 按需调用" : "AI 未开启");
        keyStatus.setText(savedHasKey ? "密钥已加密保存；页面不会显示原密钥。" : "尚未保存密钥。");
        usageStatus.setText("今天已尝试 " + usedToday + " / " + current.dailyCallLimit + " 次 · "
                + (current.model.isEmpty() ? "未选择模型" : current.model));
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
            });
        });
    }

    private AiSettings readSettings() {
        int tokens, limit;
        try { tokens = Integer.parseInt(outputLimit.getText().toString().trim()); limit = Integer.parseInt(dailyLimit.getText().toString().trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("请填写整数形式的输出上限和每天发送次数。"); }
        AiSettings settings = new AiSettings(enabled.isChecked(), baseUrl.getText().toString().trim(), model.getText().toString().trim(), tokens, limit, legacy.isChecked());
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
                if (saved) { acceptProviderState(metadata, true); requestStatus.setText("设置已保存。不会自动发送或启动监控。"); }
                else { settingsDirty = true; updateControls(); requestStatus.setText(failure == null ? "未能保存。请核对接口、模型和新密钥；若更换了地址，需要重新填写密钥。" : failure); }
                refreshSettingsSummary();
            });
        });
    }

    private AiSettings configured() {
        if (saving || sending || preparing || loadingSettings) { toast("请先结束当前操作。"); return null; }
        if (settingsDirty) { message("先保存设置", "模型设置有修改，请先保存，再预览将要发送的内容。"); return null; }
        AiSettings settings = savedSettings;
        if (!settings.enabled || !settings.validate().isEmpty() || !savedHasKey) {
            if (settingsPanel.getVisibility() != View.VISIBLE) toggleSettings();
            message("先配置 AI", "填写 HTTPS 接口地址、模型名称和 API Key，开启 AI 后保存。密钥不需要发给任何聊天助手。"); return null;
        }
        return settings;
    }

    private void previewQuestion() {
        AiSettings settings = configured(); if (settings == null) return;
        final String selectedTopic = topic, userQuestion = question.getText().toString();
        final long generation = ++requestGeneration;
        preparing = true; requestStatus.setText("正在本机整理发送预览，尚未调用模型…"); updateControls();
        requestFuture = worker.submit(() -> {
            AiContextRepository.Preview prepared = null;
            try { prepared = context.preview(selectedTopic, userQuestion); } catch (Exception ignored) { }
            final AiContextRepository.Preview preview = prepared;
            handler.post(() -> {
                if (destroyed || !foreground || generation != requestGeneration) return;
                preparing = false; requestFuture = null; updateControls();
                if (preview == null) { requestStatus.setText("未能准备摘要，本次没有发送。请重新读取公开页面或核对目标。"); return; }
                requestStatus.setText("预览已准备，确认后才调用模型。");
                showPreview(settings, preview.systemPrompt, preview.userPrompt, preview.description, false);
            });
        });
    }

    private void previewTest() {
        AiSettings settings = configured(); if (settings == null) return;
        showPreview(settings, "你是连接测试助手。仅回复：连接正常。", "这是由用户主动发起的一次连接测试，请仅回复：连接正常。",
                "只发送下面的固定测试文本，不发送目标、页面摘要或学习笔记。测试将计入今天的发送次数，可能产生少量费用。", true);
    }

    private void showPreview(AiSettings settings, String systemPrompt, String userPrompt, String description, boolean test) {
        invalidatePreview();
        LinearLayout body = column(); body.setPadding(dp(20), dp(8), dp(20), dp(12));
        String host;
        try { host = new URI(settings.endpoint()).getHost(); } catch (Exception e) { host = "已配置接口"; }
        body.addView(text("发送到：" + host + "\n模型：" + settings.model, 14, TEXT, true));
        body.addView(text(description, 13, MUTED, false), spaced(10));
        body.addView(text("请确认摘要中没有你不愿发送的私人信息。输入内容会交给你配置的服务商；本次只发送一次。", 13, MUTED, false), spaced(10));
        body.addView(text("系统说明（实际发送）", 12, MUTED, true), spaced(14));
        TextView system = text(systemPrompt, 12, MUTED, false); system.setTextIsSelectable(true); system.setSaveEnabled(false); body.addView(system, spaced(5));
        body.addView(text("问题与上下文（实际发送）", 12, MUTED, true), spaced(14));
        TextView payload = text(userPrompt, 13, TEXT, false); payload.setTextIsSelectable(true); payload.setSaveEnabled(false); body.addView(payload, spaced(5));
        ScrollView previewScroll = new ScrollView(this); previewScroll.addView(body);
        final long generation = requestGeneration;
        previewDialog = new AlertDialog.Builder(this).setTitle(test ? "确认测试连接" : "确认发送给 AI")
                .setView(previewScroll).setNegativeButton("返回修改", null)
                .setPositiveButton(test ? "发送测试" : "确认发送", (dialog, which) -> {
                    previewDialog = null;
                    if (foreground && !destroyed && generation == requestGeneration && !settingsDirty && !saving && !sending && !preparing && !loadingSettings)
                        send(settings, systemPrompt, userPrompt, test);
                }).create();
        previewDialog.setOnDismissListener(dialog -> previewDialog = null); previewDialog.show();
        previewDialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void send(AiSettings settings, String systemPrompt, String userPrompt, boolean test) {
        if (!test) { suggestedDraft = null; applyDraftButton.setVisibility(View.GONE); }
        sending = true; cancellation = new AiHttpClient.Cancellation();
        AiHttpClient.Cancellation currentCancellation = cancellation;
        long generation = ++requestGeneration;
        requestStatus.setText(test ? "正在测试连接……" : "正在分析；你可以停止等待。"); updateControls();
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
                if (completed == null) { requestStatus.setText(error == null ? "本次调用已结束，没有可显示结果。" : error); return; }
                if (test) { requestStatus.setText("接口已返回有效文本；这只验证连接，不代表票务能力已验收。"); }
                else {
                    answer = completed.text; requestStatus.setText("分析已返回。请核对建议；不会自动执行。");
                    try {
                        AppStore current = new AppStore(this); draftConfigRevision = current.getConfigRevision();
                        suggestedDraft = TOPICS[0].equals(topic) ? AiDraft.fromResponse(answer, current.loadConfig()) : null;
                    } catch (Exception ignored) { suggestedDraft = null; draftConfigRevision = -1; }
                    String readable = suggestedDraft == null ? answer : answer.trim().startsWith("{") ? suggestedDraft.summary
                            : answer.replaceFirst("(?is)```json\\s*.*?```", "").trim();
                    resultText.setText(readable.isEmpty() && suggestedDraft != null ? suggestedDraft.summary : readable);
                    resultCard.setVisibility(View.VISIBLE);
                    applyDraftButton.setVisibility(suggestedDraft == null ? View.GONE : View.VISIBLE);
                }
                resultMeta.setText((test ? "连接测试 · " : "") + String.format(java.util.Locale.ROOT, "%.1f 秒", completed.elapsedMs / 1000.0)
                        + " · 输入 Token " + tokenCount(completed.promptTokens) + " · 输出 Token " + tokenCount(completed.completionTokens));
            });
        });
    }

    private String tokenCount(long count) { return count < 0 ? "服务商未提供" : Long.toString(count); }

    private void cancelRequest(String reason, boolean show) {
        requestGeneration++;
        if (cancellation != null) cancellation.cancel();
        if (requestFuture != null) requestFuture.cancel(true);
        boolean wasSending = sending || preparing;
        sending = false; preparing = false; cancellation = null; requestFuture = null;
        if (!destroyed && previewButton != null) {
            updateControls();
            if (show && (wasSending || settingsDirty)) requestStatus.setText(reason);
        }
    }

    private void invalidatePreview() {
        if (previewDialog != null) { previewDialog.dismiss(); previewDialog = null; }
    }

    private void selectTopic(String value) {
        if (topic.equals(value)) return;
        topic = value; updateQuestionHint(); invalidatePreview(); cancelRequest("分析主题已更换，请重新预览。", true);
        for (int i = 0; i < topics.size(); i++) {
            boolean selected = TOPICS[i].equals(topic); Button item = topics.get(i);
            item.setTextColor(selected ? Color.WHITE : TEXT); item.setBackground(background(selected ? ACCENT : CARD, selected ? ACCENT : BORDER));
        }
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
                .setMessage("开始后，请在 25 秒内通过最近任务切回已经打开的大麦页面。助手只读取公开标签，不点击、不发送模型请求。\n\n稍后回到这里，先预览摘要，再决定是否发送。确认购买、实名和支付页不会作为公开页面全文上传。")
                .setNegativeButton("取消", null).setPositiveButton("开始读取", (dialog, which) -> {
                    MonitorService service = MonitorService.instance;
                    if (service == null || !service.requestAiPageRead()) {
                        message("页面读取尚未就绪", "请先在助手设置中连接“票务助手 · 界面读取”，再试一次。"); return;
                    }
                    invalidatePreview();
                    requestStatus.setText("请在 25 秒内切回大麦，随后回到这里查看摘要。"); refreshCapture();
                }).show();
    }

    private void refreshCapture() {
        try { captureStatus.setText(context.describeCapture()); }
        catch (Exception e) { captureStatus.setText("尚无可用页面摘要，可先读取一次大麦当前页。"); }
    }

    private void useAnswerAsNote() {
        if (answer.isEmpty()) { toast("先完成一次分析，再整理成笔记。"); return; }
        learningPanel.setVisibility(View.VISIBLE);
        String readable = resultText.getText().toString();
        note.setText(readable.length() > 2000 ? readable.substring(0, 2000) : readable);
        note.requestFocus(); scroll.smoothScrollTo(0, ((View) note.getParent()).getTop());
        toast("请核对并删去私人信息，再保存。");
    }

    private void saveNote() {
        String value = note.getText().toString().trim();
        if (value.isEmpty()) { toast("先填写要保留的经验。"); return; }
        new AlertDialog.Builder(this).setTitle("保存为未验证笔记")
                .setMessage("这段笔记只保存在本机，后续分析的发送预览中会列出。它不会成为点击规则或购票授权。确认已删除私人信息后保存。")
                .setNegativeButton("返回编辑", null).setPositiveButton("确认保存", (dialog, which) -> {
                    try { context.saveNote(value); note.setText(""); refreshNotes(); invalidatePreview(); toast("已保存为未验证笔记"); }
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
                        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
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
                    try { context.clearNotes(); refreshNotes(); invalidatePreview(); toast("笔记已清空"); }
                    catch (Exception e) { message("未能清空", "存储暂时不可用，请稍后重试。"); }
                }).show();
    }

    private void confirmClear() {
        if (saving) return;
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
                            if (cleared) { acceptProviderState(metadata, true); requestStatus.setText("AI 已关闭，连接配置与密钥已清除。"); }
                            else { requestStatus.setText("未能清除，请稍后重试。"); updateControls(); }
                        });
                    });
                }).show();
    }

    private void updateControls() {
        boolean ready = !saving && !sending && !preparing && !loadingSettings;
        previewButton.setEnabled(ready); testButton.setEnabled(ready);
        saveSettingsButton.setEnabled(!saving && !loadingSettings); clearSettingsButton.setEnabled(!saving && !loadingSettings);
        settingsToggle.setEnabled(!saving); saveNoteButton.setEnabled(ready); applyDraftButton.setEnabled(ready);
        for (View control : settingsControls) control.setEnabled(!saving && !loadingSettings);
        cancelButton.setVisibility(sending || preparing ? View.VISIBLE : View.GONE);
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
    @Override protected void onSaveInstanceState(Bundle state) { state.putString("topic", topic); super.onSaveInstanceState(state); }

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
