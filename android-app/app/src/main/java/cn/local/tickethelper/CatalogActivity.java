package cn.local.tickethelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.local.tickethelper.catalog.CatalogProject;
import cn.local.tickethelper.catalog.CatalogSession;
import cn.local.tickethelper.catalog.CatalogTier;
import cn.local.tickethelper.catalog.CatalogKeyword;
import cn.local.tickethelper.core.MonitorConfig;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** User-directed selection of public labels from the real Damai UI. Never starts monitoring. */
public final class CatalogActivity extends Activity {
    public static final String EXTRA_APPLIED = "cn.local.tickethelper.CATALOG_APPLIED";
    private static final String DISPLAY_PREFS = "catalog_target_display";
    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int BG = Color.rgb(245, 246, 248), CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(23, 38, 53), MUTED = Color.rgb(88, 101, 117);
    private static final int ACCENT = Color.rgb(23, 107, 91), BORDER = Color.rgb(222, 228, 234);
    private static final int ERROR = Color.rgb(169, 54, 54);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> selectedTierKeys = new LinkedHashSet<>();
    private LinearLayout page, footer;
    private ScrollView contentScroll;
    private EditText searchInput;
    private TextView selectionHint;
    private Button applyButton;
    private CatalogStore.Snapshot shown;
    private String queryDraft = "", tierScope = "";
    private boolean searchExpanded, reviewRequested;
    private String selectedProjectKey = "", selectedSessionKey = "";
    private long choiceRevision = -1, reviewRevision = -1;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            CatalogStore.Snapshot current = CatalogStore.get();
            if (shown == null || shown.revision != current.revision) render(current);
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (state != null) {
            queryDraft = state.getString("queryDraft", "");
            tierScope = state.getString("tierScope", "");
            searchExpanded = state.getBoolean("searchExpanded", false);
            reviewRequested = state.getBoolean("reviewRequested", false);
            selectedProjectKey = state.getString("selectedProjectKey", "");
            selectedSessionKey = state.getString("selectedSessionKey", "");
            choiceRevision = state.getLong("choiceRevision", -1);
            reviewRevision = state.getLong("reviewRevision", -1);
            ArrayList<String> selected = state.getStringArrayList("selectedTierKeys");
            if (selected != null) selectedTierKeys.addAll(selected);
        } else {
            queryDraft = CatalogStore.get().query;
            if (queryDraft.isEmpty()) queryDraft = new AppStore(this).loadConfig().eventKeyword;
        }
        LinearLayout shell = column(); shell.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this); contentScroll = scroll;
        scroll.setBackgroundColor(BG); scroll.setFillViewport(true);
        page = column(); page.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(page);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        footer = column(); footer.setPadding(dp(20), dp(12), dp(20), dp(14));
        footer.setBackgroundColor(CARD); shell.addView(footer);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            // One root owns bottom insets. The keyboard replaces, rather than adds to, navigation space.
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom)); return insets;
        });
        setContentView(shell);
        render(CatalogStore.get());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); render(CatalogStore.get());
    }

    private void render(CatalogStore.Snapshot snapshot) {
        if (searchInput != null) queryDraft = searchInput.getText().toString();
        boolean changedStage = shown == null || !shown.stage.equals(snapshot.stage);
        if (choiceRevision != snapshot.revision) {
            selectedProjectKey = ""; selectedSessionKey = ""; choiceRevision = snapshot.revision;
        }
        if (reviewRequested && (reviewRevision != snapshot.revision || !"TIERS".equals(snapshot.stage))) reviewRequested = false;
        shown = snapshot; page.removeAllViews(); footer.removeAllViews();
        applyButton = null; selectionHint = null; searchInput = null;
        boolean idle = "IDLE".equals(snapshot.stage), busy = "BUSY".equals(snapshot.stage);
        boolean blocked = "BLOCKED".equals(snapshot.stage), searching = idle || searchExpanded;
        page.addView(link(reviewRequested ? "‹ 修改票价" : searching && !idle ? "‹ 返回选择" : "‹ 返回",
                v -> goBack()), spaced(0));
        renderSteps(snapshot, searching);
        String heading = reviewRequested ? "核对你的选择"
                : searching ? "想看谁的演出？" : "PROJECTS".equals(snapshot.stage) ? "选择演出和城市"
                : "SESSIONS".equals(snapshot.stage) ? "选一个方便的时间" : "TIERS".equals(snapshot.stage) ? "哪些票价都可以？"
                : busy ? "去大麦，继续这一步" : "这一步需要处理";
        addText(page, heading, 26, TEXT, true, 18);
        String subheading = reviewRequested ? "核对后保存，再设置有票时怎么做。"
                : searching ? "输入艺人或演出名，也可以加上城市。"
                : "PROJECTS".equals(snapshot.stage) ? "点选一项，再查看这场演出的场次。"
                : "SESSIONS".equals(snapshot.stage) ? "日期和时间来自刚才的大麦页面。"
                : "TIERS".equals(snapshot.stage) ? "可以多选。缺货票价也能关注回流。" : "";
        if (!subheading.isEmpty()) addText(page, subheading, 14, MUTED, false, 7);
        if (!searching && !reviewRequested && snapshot.event != null && !busy && !blocked) {
            LinearLayout chosen = column(); chosen.setPadding(dp(13), dp(12), dp(13), dp(12));
            chosen.setBackground(shape(Color.rgb(234, 243, 240), Color.rgb(234, 243, 240)));
            addText(chosen, snapshot.event.label, 13, TEXT, true, 0);
            if (snapshot.session != null) addText(chosen, sessionDate(snapshot.session), 12, MUTED, false, 4);
            if ("TIERS".equals(snapshot.stage)) chosen.addView(link("修改场次", v -> changeSession()), spaced(4));
            page.addView(chosen, spaced(14));
        }
        if (!busy && !reviewRequested && !searching && !blocked) renderAiHelp(snapshot, false);
        if (searching) renderSearch(snapshot);
        else if (reviewRequested) renderReview(snapshot);
        else if ("PROJECTS".equals(snapshot.stage)) renderProjects(snapshot);
        else if ("SESSIONS".equals(snapshot.stage)) renderSessions(snapshot);
        else if ("TIERS".equals(snapshot.stage)) renderTiers(snapshot);
        else if (busy) renderHandoff(snapshot);
        else renderBlocked(snapshot);

        if (!busy && !reviewRequested && (searching || blocked)) renderAiHelp(snapshot, searching);
        if (!busy && !searching && !reviewRequested) {
            page.addView(link("换个演出", v -> { searchExpanded = true; render(CatalogStore.get()); scrollTop(); }), spaced(16));
            if (!blocked) page.addView(link("大麦已显示别的内容？重新读取", v -> captureCurrent()), spaced(2));
        }
        if (!busy && !reviewRequested) page.addView(link("使用帮助", v -> openSelectionHelp()), spaced(4));
        if (snapshot.readAtMs > 0 && !busy && !searching) {
            addText(page, "大麦页面 · " + Instant.ofEpochMilli(snapshot.readAtMs).atZone(VENUE_ZONE)
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " 读取 · 不代表已锁票", 11, MUTED, false, 14);
        }
        if (changedStage) scrollTop();
    }

    private void scrollTop() { contentScroll.post(() -> contentScroll.scrollTo(0, 0)); }

    private String sessionDate(CatalogSession session) {
        try { return LocalDate.parse(session.date).format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)) + "  " + session.time; }
        catch (RuntimeException ignored) { return session.label; }
    }

    private void renderSteps(CatalogStore.Snapshot snapshot, boolean searching) {
        int step = reviewRequested ? 4 : searching ? 1
                : snapshot.session != null || "TIERS".equals(snapshot.stage) ? 3
                : snapshot.event != null || "SESSIONS".equals(snapshot.stage) ? 2 : 1;
        String[] labels = {"找演出", "选场次", "选票价", "核对"};
        LinearLayout steps = new LinearLayout(this); steps.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            TextView label = text((i + 1 < step ? "✓ " : (i + 1) + " ") + labels[i], 12,
                    i + 1 <= step ? ACCENT : MUTED, i + 1 == step);
            label.setPadding(dp(0), dp(10), dp(0), dp(8));
            label.setContentDescription("第 " + (i + 1) + " 步，" + labels[i] + (i + 1 == step ? "，当前步骤" : ""));
            steps.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        page.addView(steps, spaced(4));
    }

    private void primary(String title, String hint, boolean enabled, View.OnClickListener action) {
        footer.removeAllViews();
        selectionHint = text(hint, 12, MUTED, false);
        selectionHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        footer.addView(selectionHint);
        applyButton = button(title, true, action); applyButton.setEnabled(enabled); applyButton.setAlpha(enabled ? 1f : 0.45f);
        footer.addView(applyButton, spaced(hint.isEmpty() ? 0 : 8));
    }

    private void renderSearch(CatalogStore.Snapshot snapshot) {
        LinearLayout search = card();
        searchInput = new EditText(this);
        searchInput.setSingleLine(true); searchInput.setTextSize(18); searchInput.setTextColor(TEXT);
        searchInput.setHintTextColor(MUTED); searchInput.setHint("例如：谢霆锋 杭州");
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        searchInput.setContentDescription("艺人或演出名，可加城市");
        searchInput.setBackground(shape(BG, BORDER)); searchInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        searchInput.setMinHeight(dp(56)); searchInput.setText(queryDraft); search.addView(searchInput);
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            search(); return true;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                queryDraft = s.toString();
                if (applyButton != null) { boolean valid = queryDraft.trim().length() >= 2; applyButton.setEnabled(valid); applyButton.setAlpha(valid ? 1f : 0.45f); }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        addText(search, "会打开真实大麦。读取完成后，点助手通知回来选。", 13, MUTED, false, 12);
        page.addView(link("已经打开大麦？读取当前页", v -> captureCurrent()), spaced(10));
        primary("在大麦搜索", "", queryDraft.trim().length() >= 2, v -> search());
    }

    private void renderProjects(CatalogStore.Snapshot snapshot) {
        for (CatalogProject project : snapshot.projects) {
            boolean selected = project.key.equals(selectedProjectKey);
            LinearLayout option = selectableCard(project.label, safe(project.city).isEmpty() ? "城市尚未读取" : project.city,
                    selected, v -> { selectedProjectKey = project.key; render(snapshot); });
            option.setContentDescription(project.label + "，" + missing(project.city) + (selected ? "，已选择" : "，点按选择"));
        }
        if (snapshot.projects.isEmpty()) addText(page, "还没有读到演出。可以换个名称搜索，或在大麦展开结果后重新读取。", 14, MUTED, false, 18);
        primary("继续，选场次", selectedProjectKey.isEmpty() ? "先点选一场演出" : "将到大麦读取所选演出的场次",
                !selectedProjectKey.isEmpty(), v -> continueProject(snapshot));
    }

    private void continueProject(CatalogStore.Snapshot snapshot) {
        if (!isCurrent(snapshot)) return;
        for (CatalogProject project : snapshot.projects) if (project.key.equals(selectedProjectKey)) {
            searchExpanded = false; returnToDamai(() -> CatalogController.selectProject(this, project.key)); return;
        }
    }

    private void renderSessions(CatalogStore.Snapshot snapshot) {
        for (CatalogSession session : snapshot.sessions) {
            boolean selected = session.key.equals(selectedSessionKey);
            String date, time;
            try {
                date = LocalDate.parse(session.date).format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA));
                time = session.time + " 开场 · " + LocalDate.parse(session.date).getYear() + "年";
            } catch (RuntimeException invalid) { date = session.label; time = "完整日期和时间尚待读取"; }
            selectableCard(date, time, selected, v -> { selectedSessionKey = session.key; render(snapshot); });
        }
        if (snapshot.sessions.isEmpty()) addText(page, "还没有读到场次。请在大麦展开日期，再重新读取。", 14, MUTED, false, 18);
        primary("继续，选票价", selectedSessionKey.isEmpty() ? "先点选一个日期和时间" : "将到大麦读取这一场的票价",
                !selectedSessionKey.isEmpty(), v -> continueSession(snapshot));
    }

    private void continueSession(CatalogStore.Snapshot snapshot) {
        if (!isCurrent(snapshot)) return;
        for (CatalogSession session : snapshot.sessions) if (session.key.equals(selectedSessionKey)) {
            searchExpanded = false; returnToDamai(() -> CatalogController.selectSession(this, session.key)); return;
        }
    }

    private boolean isCurrent(CatalogStore.Snapshot snapshot) {
        if (CatalogStore.get().revision == snapshot.revision) return true;
        render(CatalogStore.get());
        android.widget.Toast.makeText(this, "页面已更新，请重新选择", android.widget.Toast.LENGTH_SHORT).show();
        return false;
    }

    private LinearLayout selectableCard(String title, String detail, boolean selected, View.OnClickListener listener) {
        LinearLayout option = card();
        option.setBackground(shape(selected ? Color.rgb(232, 245, 240) : CARD, selected ? ACCENT : BORDER));
        addText(option, (selected ? "●  " : "○  ") + title, 17, selected ? ACCENT : TEXT, true, 0);
        if (!detail.isEmpty()) addText(option, detail, 13, MUTED, false, 7);
        option.setSelected(selected); option.setFocusable(true); option.setClickable(true); option.setOnClickListener(listener);
        option.setContentDescription(title + "，" + detail + (selected ? "，已选择" : "，点按选择"));
        return option;
    }

    private void renderTiers(CatalogStore.Snapshot snapshot) {
        String scope = (snapshot.event == null ? "" : snapshot.event.key) + "|"
                + (snapshot.session == null ? "" : snapshot.session.key);
        if (!scope.equals(tierScope)) { selectedTierKeys.clear(); tierScope = scope; }
        LinkedHashSet<String> visible = new LinkedHashSet<>();
        for (CatalogTier tier : snapshot.tiers) visible.add(tier.key);
        selectedTierKeys.retainAll(visible);
        LinearLayout quick = new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(link("全选票价", v -> {
            for (CatalogTier tier : snapshot.tiers) if (tier.price > 0) selectedTierKeys.add(tier.key);
            render(snapshot);
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        quick.addView(link("清空", v -> { selectedTierKeys.clear(); render(snapshot); }), new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(quick, spaced(10));
        LinearLayout gridRow = null;
        for (int index = 0; index < snapshot.tiers.size(); index++) {
            CatalogTier tier = snapshot.tiers.get(index);
            if (index % 2 == 0) { gridRow = new LinearLayout(this); gridRow.setOrientation(LinearLayout.HORIZONTAL); page.addView(gridRow, spaced(10)); }
            CheckBox choice = new CheckBox(this);
            choice.setText(tier.label + "\n" + availabilityLabel(tier.availability));
            choice.setTextColor(TEXT); choice.setTextSize(14); choice.setMinHeight(dp(94));
            choice.setPadding(dp(9), dp(12), dp(9), dp(12));
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            choice.setEnabled(tier.price > 0); choice.setChecked(selectedTierKeys.contains(tier.key));
            styleTier(choice, choice.isChecked());
            choice.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedTierKeys.add(tier.key); else selectedTierKeys.remove(tier.key);
                styleTier(choice, checked); updateApplyState();
            });
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -1, 1f);
            if (index % 2 == 1) cell.leftMargin = dp(10);
            gridRow.addView(choice, cell);
        }
        if (snapshot.tiers.size() % 2 == 1 && gridRow != null) gridRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (snapshot.tiers.isEmpty()) addText(page, "还没有读到票价。请在大麦展开票档后重新读取。", 14, MUTED, false, 16);
        primary("下一步，核对", "", false, v -> confirmSelection()); updateApplyState();
    }

    private void styleTier(CheckBox choice, boolean checked) {
        choice.setBackground(shape(checked ? Color.rgb(232, 245, 240) : CARD, checked ? ACCENT : BORDER));
        choice.setTextColor(checked ? ACCENT : TEXT);
    }

    private void renderReview(CatalogStore.Snapshot snapshot) {
        List<Integer> prices = selectedPrices(snapshot); int maximum = 0;
        for (int price : prices) maximum = Math.max(maximum, price);
        LinearLayout ticket = card();
        addText(ticket, snapshot.event.label, 19, TEXT, true, 0);
        addText(ticket, snapshot.event.city, 13, MUTED, false, 8);
        summaryRow(ticket, "场次", sessionDate(snapshot.session));
        ticket.addView(link("修改场次", v -> changeSession()), spaced(2));
        summaryRow(ticket, "接受票价", pricesText(prices));
        summaryRow(ticket, "张数", "1 张");
        summaryRow(ticket, "实付不超过", "¥ " + maximum);
        addText(ticket, "这些票价任选其一；运行至开场时间停止。", 12, MUTED, false, 14);
        addText(page, "保存后回到首页，由你选择有票时的操作并开始。", 13, MUTED, false, 16);
        primary("保存这场演出", "这里只保存选择，还不会开始或下单", true, v -> saveReviewedSelection());
    }

    private void summaryRow(LinearLayout target, String label, String value) {
        addText(target, label, 12, MUTED, false, 17);
        addText(target, value, 16, TEXT, true, 4);
    }

    private void renderHandoff(CatalogStore.Snapshot snapshot) {
        LinearLayout handoff = card();
        addText(handoff, safe(snapshot.status).isEmpty() ? "请保持大麦在前台，助手正在读取。" : safe(snapshot.status), 16, TEXT, true, 0);
        addText(handoff, "读取完成后，点“票务助手 · 演出选择”通知回来。", 14, MUTED, false, 12);
        page.addView(link("取消这次读取", v -> { CatalogController.cancel(this); render(CatalogStore.get()); }), spaced(12));
        primary("切回大麦", "保持手机解锁和亮屏", true, v -> finishAndRemoveTask());
    }

    private void renderBlocked(CatalogStore.Snapshot snapshot) {
        LinearLayout problem = card();
        addText(problem, safe(snapshot.status).isEmpty() ? "没有读到完整信息，请打开大麦后再试。" : safe(snapshot.status), 15, ERROR, true, 0);
        addText(problem, "之前保存的演出没有改变。", 13, MUTED, false, 10);
        primary("重新读取大麦", "先在大麦处理提示，再打开需要读取的页面", true, v -> captureCurrent());
    }

    private void captureCurrent() {
        searchExpanded = false; reviewRequested = false;
        returnToDamai(() -> CatalogController.captureCurrent(this, queryDraft.trim()));
    }

    private void changeSession() {
        new AlertDialog.Builder(this).setTitle("重新选择场次")
                .setMessage("请保持大麦打开这场演出的场次票价页。继续后切回大麦，读取完成再点助手通知回来选日期。")
                .setNegativeButton("暂不修改", null)
                .setPositiveButton("读取场次", (dialog, which) -> captureCurrent()).show();
    }

    private void renderAiHelp(CatalogStore.Snapshot snapshot, boolean searching) {
        String topic, label, prompt;
        if ("BLOCKED".equals(snapshot.stage) && !searching) {
            topic = "异常分析"; label = "AI 帮我解决这一步";
            prompt = "我在选择演出时遇到了问题，页面提示：" + safe(snapshot.status) + "。请告诉我最可能的原因和下一步该点哪里；不能确定的部分请说明。";
        } else if ("TIERS".equals(snapshot.stage) && !searching) {
            topic = "票档解释"; label = "拿不准票价？让 AI 帮你比较";
            prompt = "请帮我理解刚读到的票价和缺货标记，说明如何按预算选择。不要把可见票价当成有票，也不要编造视野或座位信息。";
        } else if ("SESSIONS".equals(snapshot.stage) && !searching) {
            topic = "场次比较"; label = "AI 帮我比较这些场次";
            prompt = "请比较刚读到的演出场次，把日期、星期和时间列清楚。如果需要我的出行偏好，问一个简单的问题。";
        } else {
            topic = "配置建议"; label = searching ? "还没想好？AI 帮你整理需求" : "AI 帮我比较这些演出";
            prompt = searching ? "请帮我整理想看的演出、城市、日期和预算。先问我一个最必要的问题，不要虚构演出或库存。"
                    : "请根据刚从大麦读到的公开演出结果，帮助我比较城市和项目；不要虚构未读到的场次或库存。";
        }
        final String selectedTopic = topic, selectedPrompt = prompt;
        LinearLayout ai = column(); ai.setPadding(dp(14), dp(10), dp(14), dp(10));
        ai.setBackground(shape(Color.rgb(234, 243, 240), Color.rgb(214, 232, 225)));
        ai.addView(link(label + "  ›", v -> {
            if (!isCurrent(snapshot)) return;
            String promptAtClick = selectedPrompt;
            if (searching) {
                String currentQuery = (searchInput == null ? queryDraft : searchInput.getText().toString()).trim();
                if (!currentQuery.isEmpty()) promptAtClick = "我想搜索：" + currentQuery + "。" + promptAtClick;
            }
            startActivity(new Intent(this, AiAssistantActivity.class)
                    .putExtra("topic", selectedTopic).putExtra("question", promptAtClick).putExtra("origin_label", "选择演出"));
        }));
        page.addView(ai, spaced(18));
    }

    private void openSelectionHelp() {
        try { startActivity(new Intent().setClassName(this, "cn.local.tickethelper.HelpActivity").putExtra("topic", "select-event")); }
        catch (RuntimeException unavailable) { showMessage("选演出的步骤", "找演出 → 选场次 → 选票价 → 核对。读取完成后，点助手通知回来继续选择；保存后再由你开始。"); }
    }

    private void search() {
        String query = (searchInput == null ? queryDraft : searchInput.getText().toString()).trim();
        if (query.length() < 2 || query.length() > 60 || normalize(query).length() < 2) {
            showMessage("请输入演出名", "输入 2 至 60 个字符，可以是演出名、艺人名，也可以加上城市。"); return;
        }
        queryDraft = query; searchExpanded = false; reviewRequested = false;
        returnToDamai(() -> CatalogController.beginSearch(this, query));
    }

    private void returnToDamai(Runnable action) {
        try {
            queryDraft = searchInput == null ? queryDraft : searchInput.getText().toString();
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(page.getWindowToken(), 0);
            action.run();
            if ("BUSY".equals(CatalogStore.get().stage)) finishAndRemoveTask();
            else render(CatalogStore.get());
        } catch (RuntimeException e) {
            CatalogController.cancel(this);
            showMessage("暂时无法读取", "请检查大麦是否已打开、手机是否解锁，以及助手无障碍服务是否连接，再重试。");
        }
    }

    private List<Integer> selectedPrices(CatalogStore.Snapshot snapshot) {
        LinkedHashSet<Integer> prices = new LinkedHashSet<>();
        for (CatalogTier tier : snapshot.tiers) if (selectedTierKeys.contains(tier.key) && tier.price > 0) prices.add(tier.price);
        return new ArrayList<>(prices);
    }

    private String selectionProblem(CatalogStore.Snapshot snapshot) {
        if (!"TIERS".equals(snapshot.stage) || snapshot.event == null) return "请先选好演出和场次，再选择接受的票价。";
        if (safe(snapshot.event.city).isEmpty()) return "还缺少城市。请在大麦展开演出信息，再重新读取。";
        if (snapshot.session == null || !safe(snapshot.session.date).matches("\\d{4}-\\d{2}-\\d{2}")
                || !safe(snapshot.session.time).matches("\\d{2}:\\d{2}")) return "还缺少完整日期或时间。请在大麦展开场次，再重新读取。";
        try {
            if (!deadline(snapshot).isAfter(Instant.now())) return "该场次时间已过，不能作为监控截止时间，请选择未来场次。";
        } catch (RuntimeException e) { return "场次日期或时间无效，请重新读取。"; }
        String query = searchInput == null ? queryDraft.trim() : searchInput.getText().toString().trim();
        if (query.length() < 2 || query.length() > 60 || normalize(query).length() < 2
                || matchedKeyword(snapshot) == null)
            return "搜索内容中未找到可用于识别的演出名称。请包含标题中实际出现的名称（至少 2 个字符），不要只填城市、日期或“演唱会”。";
        if (selectedPrices(snapshot).isEmpty()) return "请勾选至少一个接受的票价。";
        return null;
    }

    private Instant deadline(CatalogStore.Snapshot snapshot) {
        return LocalDate.parse(snapshot.session.date).atTime(LocalTime.parse(snapshot.session.time))
                .atZone(VENUE_ZONE).toInstant();
    }

    private void updateApplyState() {
        if (applyButton == null || selectionHint == null || shown == null) return;
        String problem = selectionProblem(shown);
        applyButton.setEnabled(problem == null); applyButton.setAlpha(problem == null ? 1f : 0.5f);
        selectionHint.setVisibility(View.VISIBLE);
        if (problem != null) selectionHint.setText(problem);
        else {
            List<Integer> prices = selectedPrices(shown); int maximum = 0;
            for (int price : prices) maximum = Math.max(maximum, price);
            selectionHint.setText("已选 " + prices.size() + " 种票价 · 1 张 · 实付不超过 ¥" + maximum);
        }
    }

    private void confirmSelection() {
        CatalogStore.Snapshot snapshot = CatalogStore.get();
        if (shown == null || !isCurrent(shown)) return;
        String problem = selectionProblem(snapshot);
        if (problem != null) { showMessage("还差一步", problem); return; }
        reviewRequested = true; reviewRevision = snapshot.revision; render(snapshot); scrollTop();
    }

    private void saveReviewedSelection() {
        CatalogStore.Snapshot snapshot = CatalogStore.get();
        if (!reviewRequested || reviewRevision != snapshot.revision || !isCurrent(snapshot)) { render(snapshot); return; }
        String problem = selectionProblem(snapshot);
        if (problem != null) { reviewRequested = false; render(snapshot); showMessage("请重新核对", problem); return; }
        List<Integer> prices = selectedPrices(snapshot);
        int maximum = 0; for (int price : prices) maximum = Math.max(maximum, price);
        MonitorConfig old = new AppStore(this).loadConfig();
        MonitorConfig proposed = new MonitorConfig(matchedKeyword(snapshot), snapshot.event.city,
                snapshot.session.date, snapshot.session.time, prices, 1, maximum, deadline(snapshot),
                old.minIntervalMs, old.maxIntervalMs, old.mode);
        if (!proposed.validate().isEmpty()) {
            showMessage("还差一步", String.join("\n", proposed.validate())); return;
        }
        apply(snapshot, proposed);
    }

    private void apply(CatalogStore.Snapshot snapshot, MonitorConfig proposed) {
        if (CatalogStore.get().revision != snapshot.revision || !proposed.deadline.isAfter(Instant.now())) {
            showMessage("页面信息已变化", "请重新核对当前场次和票价后再应用。"); render(CatalogStore.get()); return;
        }
        MonitorService monitor = MonitorService.instance;
        if (monitor != null) monitor.pause("目标已更新；请重新准备后手动开始监控。");
        CatalogController.cancel(this);
        AppStore store = new AppStore(this);
        store.setArmed(false); store.saveConfig(proposed);
        store.setStatus("演出已保存，还没有开始监控。请回首页核对后开始。");
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancel(12);
        getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE).edit().putString("title", snapshot.event.label)
                .putString("target", targetFingerprint(proposed)).putLong("readAtMs", snapshot.readAtMs).apply();
        store.markImportedTargetReviewed();
        returnToHelper(true);
    }

    public static String selectedTitle(Context context, MonitorConfig config) {
        if (new AppStore(context).isImportedTargetPendingReview()) return "";
        SharedPreferences display = context.getSharedPreferences(DISPLAY_PREFS, MODE_PRIVATE);
        return targetFingerprint(config).equals(display.getString("target", ""))
                ? display.getString("title", "") : "";
    }

    private static String targetFingerprint(MonitorConfig c) {
        return c.eventKeyword + "\n" + c.city + "\n" + c.date + "\n" + c.time + "\n" + c.prices + "\n" + c.quantity;
    }

    private void returnToHelper(boolean applied) {
        CatalogController.cancel(this);
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (applied) intent.putExtra(EXTRA_APPLIED, true);
        startActivity(intent); finishAndRemoveTask();
    }

    private void goBack() {
        if (reviewRequested) { reviewRequested = false; render(CatalogStore.get()); scrollTop(); }
        else if (searchExpanded && !"IDLE".equals(CatalogStore.get().stage)) { searchExpanded = false; render(CatalogStore.get()); scrollTop(); }
        else returnToHelper(false);
    }
    @Override public void onBackPressed() { goBack(); }
    @Override protected void onResume() {
        super.onResume(); CatalogController.dismissResultNotification(this);
        handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("queryDraft", searchInput == null ? queryDraft : searchInput.getText().toString());
        state.putBoolean("searchExpanded", searchExpanded);
        state.putBoolean("reviewRequested", reviewRequested); state.putLong("reviewRevision", reviewRevision);
        state.putString("selectedProjectKey", selectedProjectKey); state.putString("selectedSessionKey", selectedSessionKey);
        state.putLong("choiceRevision", choiceRevision);
        state.putString("tierScope", tierScope); state.putStringArrayList("selectedTierKeys", new ArrayList<>(selectedTierKeys));
        super.onSaveInstanceState(state);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(safe(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }
    /** Derive an exact visible name from a natural search, never an artist alias or guessed mapping. */
    private String matchedKeyword(CatalogStore.Snapshot snapshot) {
        if (snapshot.event == null) return null;
        String query = searchInput == null ? queryDraft.trim() : searchInput.getText().toString().trim();
        return CatalogKeyword.fromQuery(snapshot.event.label, query, snapshot.event.city);
    }
    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static String missing(String value) { return safe(value).isEmpty() ? "未读取到" : value; }
    private static String pricesText(List<Integer> prices) { StringBuilder s = new StringBuilder(); for (int p : prices) { if (s.length() > 0) s.append("、"); s.append(p).append(" 元"); } return s.toString(); }
    private static String availabilityLabel(String state) {
        if ("OUT_OF_STOCK".equals(state)) return "读取时缺货";
        if ("IN_STOCK_SIGNAL".equals(state)) return "页面有票信号 · 数量待核实";
        return "库存待核实";
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(16), dp(17), dp(16), dp(17)); v.setBackground(shape(CARD, BORDER)); page.addView(v, spaced(16)); return v; }
    private TextView text(String value, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3), 1f); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private void addText(LinearLayout target, String value, int size, int color, boolean bold, int margin) { target.addView(text(value, size, color, bold), spaced(margin)); }
    private Button button(String title, boolean primary, View.OnClickListener listener) { Button v = new Button(this); v.setText(title); v.setAllCaps(false); v.setTextSize(14); v.setTextColor(primary ? Color.WHITE : TEXT); v.setMinHeight(dp(52)); v.setPadding(dp(10), dp(10), dp(10), dp(10)); v.setBackground(shape(primary ? ACCENT : CARD, primary ? ACCENT : BORDER)); v.setStateListAnimator(null); v.setOnClickListener(listener); return v; }
    private Button link(String title, View.OnClickListener listener) {
        Button v = button(title, false, listener); v.setMinHeight(dp(44)); v.setPadding(dp(0), dp(8), dp(0), dp(8));
        v.setTextColor(ACCENT); v.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        v.setBackgroundColor(Color.TRANSPARENT); return v;
    }
    private GradientDrawable shape(int color, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12)); d.setStroke(dp(1), stroke); return d; }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void showMessage(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show(); }
}
