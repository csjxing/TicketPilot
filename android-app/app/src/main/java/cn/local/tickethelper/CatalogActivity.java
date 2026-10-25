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
    private LinearLayout page;
    private EditText searchInput;
    private TextView selectionHint;
    private Button applyButton;
    private CatalogStore.Snapshot shown;
    private String queryDraft = "", tierScope = "";
    private boolean searchExpanded;
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
        if (state != null) {
            queryDraft = state.getString("queryDraft", "");
            tierScope = state.getString("tierScope", "");
            searchExpanded = state.getBoolean("searchExpanded", false);
            ArrayList<String> selected = state.getStringArrayList("selectedTierKeys");
            if (selected != null) selectedTierKeys.addAll(selected);
        } else {
            queryDraft = CatalogStore.get().query;
            if (queryDraft.isEmpty()) queryDraft = new AppStore(this).loadConfig().eventKeyword;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        page = column(); page.setPadding(dp(20), dp(22), dp(20), dp(28));
        scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll);
        render(CatalogStore.get());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); render(CatalogStore.get());
    }

    private void render(CatalogStore.Snapshot snapshot) {
        if (searchInput != null) queryDraft = searchInput.getText().toString();
        shown = snapshot; page.removeAllViews(); applyButton = null; selectionHint = null; searchInput = null;
        page.addView(button("‹ 返回任务", false, v -> returnToHelper(false)), spaced(0));
        addText(page, "选演出和场次", 28, TEXT, true, 18);
        addText(page, "点选实际读到的选项，不用手填日期和票价。", 14, MUTED, false, 6);
        renderSteps(snapshot);

        boolean idle = "IDLE".equals(snapshot.stage);
        boolean busy = "BUSY".equals(snapshot.stage);
        boolean blocked = "BLOCKED".equals(snapshot.stage);
        if (!idle) {
            LinearLayout status = card();
            TextView heading = text(stageLabel(snapshot.stage), 17, blocked ? ERROR : ACCENT, true);
            heading.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            status.addView(heading);
            addText(status, nextStep(snapshot), 14, TEXT, false, 8);
            if ((busy || blocked) && !safe(snapshot.status).isEmpty())
                addText(status, safe(snapshot.status), 13, MUTED, false, 10);
            if (snapshot.event != null) {
                addText(status, snapshot.event.label, 16, TEXT, true, 14);
                addText(status, missing(snapshot.event.city), 13, MUTED, false, 4);
            }
            if (snapshot.session != null) addText(status, snapshot.session.label, 14, TEXT, false, 7);
            if (snapshot.readAtMs > 0 && !blocked) addText(status, "本次页面读取时间 " + Instant.ofEpochMilli(snapshot.readAtMs)
                    .atZone(VENUE_ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")), 12, MUTED, false, 10);
        }

        if ("PROJECTS".equals(snapshot.stage)) renderProjects(snapshot);
        else if ("SESSIONS".equals(snapshot.stage)) renderSessions(snapshot);
        else if ("TIERS".equals(snapshot.stage)) renderTiers(snapshot);
        else if (busy) {
            LinearLayout handoff = card();
            addText(handoff, "接下来在大麦完成", 16, TEXT, true, 0);
            addText(handoff, "保持手机解锁，切回大麦等待读取。若详情页出现“立即预订”，请点一次打开场次。", 14, TEXT, false, 8);
            addText(handoff, "读完后，下拉通知并点“票务助手 · 演出选择”返回。没有通知时，回助手重新打开本页查看结果。", 13, MUTED, false, 10);
            handoff.addView(button("收起此页，手动切回大麦", true, v -> finishAndRemoveTask()), spaced(14));
            handoff.addView(button("取消这次读取", false, v -> { CatalogController.cancel(this); render(CatalogStore.get()); }), spaced(8));
        }

        if (!busy) renderSearch(snapshot, idle || blocked || searchExpanded);
        addText(page, "保存演出不会开始监控，也不会创建订单。", 12, MUTED, false, 20);
        if (!busy && !idle) page.addView(button("让 AI 帮我比较这些选项", false, v -> {
            startActivity(new Intent(this, AiAssistantActivity.class).putExtra("topic",
                    "TIERS".equals(snapshot.stage) ? "票档解释" : "场次比较"));
        }), spaced(10));
        page.addView(button("不清楚怎么选？", false, v -> openSelectionHelp()), spaced(10));
    }

    private void renderSteps(CatalogStore.Snapshot snapshot) {
        int step = "IDLE".equals(snapshot.stage) ? 1
                : snapshot.session != null || "TIERS".equals(snapshot.stage) ? 3
                : snapshot.event != null || "SESSIONS".equals(snapshot.stage) ? 2 : 1;
        String[] labels = {"演出", "场次", "票价", "保存"};
        LinearLayout steps = new LinearLayout(this); steps.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            TextView label = text((i + 1) + "  " + labels[i], 13, i + 1 == step ? ACCENT : MUTED, i + 1 == step);
            label.setPadding(dp(0), dp(12), dp(0), dp(8));
            label.setContentDescription("第 " + (i + 1) + " 步，" + labels[i] + (i + 1 == step ? "，当前步骤" : ""));
            steps.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        page.addView(steps, spaced(8));
    }

    private String nextStep(CatalogStore.Snapshot snapshot) {
        switch (snapshot.stage) {
            case "PROJECTS": return snapshot.projects.isEmpty() ? "暂时没有读到演出，可修改搜索或读取大麦当前页。"
                    : "请选择演出和城市。接下来会到大麦读取场次。";
            case "SESSIONS": return "请选择日期和时间。接下来会读取这场的票价。";
            case "TIERS": return "勾选你能接受的票价，再核对并保存。可以多选。";
            case "BUSY": return "请切回大麦，完成后通过结果通知回来。";
            case "BLOCKED": return "本次读取已暂停，尚未保存新目标。处理提示后可重新读取。";
            default: return "先输入演出名或艺人名，也可以读取已打开的大麦页面。";
        }
    }

    private void renderSearch(CatalogStore.Snapshot snapshot, boolean expanded) {
        LinearLayout search = card();
        if (!"IDLE".equals(snapshot.stage) && !"BLOCKED".equals(snapshot.stage)) {
            search.addView(button(expanded ? "收起搜索与重读 ▴" : "换个演出 / 重新读取 ▾", false, v -> {
                searchExpanded = !expanded; render(CatalogStore.get());
            }));
        }
        LinearLayout form = column(); search.addView(form, spaced(expanded ? 10 : 0));
        form.setVisibility(expanded ? View.VISIBLE : View.GONE);
        addText(form, "演出名或艺人", 15, TEXT, true, 0);
        searchInput = new EditText(this);
        searchInput.setSingleLine(true); searchInput.setTextSize(17); searchInput.setTextColor(TEXT);
        searchInput.setHintTextColor(MUTED); searchInput.setHint("例如：谢霆锋 杭州");
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        searchInput.setContentDescription("演出名或艺人，可加城市");
        searchInput.setBackground(shape(BG, BORDER)); searchInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        searchInput.setMinHeight(dp(52)); searchInput.setText(queryDraft); form.addView(searchInput, spaced(10));
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            search(); return true;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { queryDraft = s.toString(); updateApplyState(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        form.addView(button("去大麦搜索", true, v -> search()), spaced(12));
        addText(form, "搜索会打开大麦。读完后点“票务助手 · 演出选择”通知，回到这里继续选。", 13, MUTED, false, 9);
        form.addView(button("我已打开演出，读取当前页", false,
                v -> returnToDamai(() -> CatalogController.captureCurrent(this, queryDraft.trim()))), spaced(16));
        addText(form, "选这个入口后，请在 20 秒内从最近任务切回大麦。不会重新打开大麦首页。", 12, MUTED, false, 8);
        if ("BLOCKED".equals(snapshot.stage)) addText(form,
                "也可返回任务页完成权限设置，或使用手动填写。原来的已保存目标不会因读取失败被覆盖。", 12, MUTED, false, 12);
    }

    private void openSelectionHelp() {
        try { startActivity(new Intent().setClassName(this, "cn.local.tickethelper.HelpActivity").putExtra("topic", "select-event")); }
        catch (RuntimeException unavailable) { showMessage("选演出的步骤", "搜索演出 → 选择城市与场次 → 勾选接受票价 → 核对保存。每次读取结束，点“票务助手 · 演出选择”通知返回；保存后仍需在任务页主动开始。"); }
    }

    private void renderProjects(CatalogStore.Snapshot snapshot) {
        addText(page, "当前读到 " + snapshot.projects.size() + " 个演出", 17, TEXT, true, 20);
        for (CatalogProject project : snapshot.projects) {
            LinearLayout row = card();
            addText(row, project.label, 16, TEXT, true, 0);
            addText(row, "城市：" + missing(project.city), 12, MUTED, false, 6);
            row.addView(button("选这场演出，查看日期", true,
                    v -> returnToDamai(() -> CatalogController.selectProject(this, project.key))), spaced(12));
        }
        if (snapshot.projects.isEmpty()) addText(page, "当前可见页面没有可选择项目，请重新搜索或读取。", 13, MUTED, false, 12);
    }

    private void renderSessions(CatalogStore.Snapshot snapshot) {
        addText(page, "哪一天方便？", 17, TEXT, true, 20);
        for (CatalogSession session : snapshot.sessions) {
            LinearLayout row = card();
            String dateLabel = session.label;
            try { dateLabel = LocalDate.parse(session.date).format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) + "  " + session.time; }
            catch (RuntimeException ignored) { }
            addText(row, dateLabel, 18, TEXT, true, 0);
            row.addView(button("选这场", true,
                    v -> returnToDamai(() -> CatalogController.selectSession(this, session.key))), spaced(12));
        }
        if (snapshot.sessions.isEmpty()) addText(page, "未读到场次。请在大麦展开日期后重新读取，不会按当前年份补全。", 13, MUTED, false, 12);
    }

    private void renderTiers(CatalogStore.Snapshot snapshot) {
        String scope = (snapshot.event == null ? "" : snapshot.event.key) + "|"
                + (snapshot.session == null ? "" : snapshot.session.key);
        if (!scope.equals(tierScope)) { selectedTierKeys.clear(); tierScope = scope; }
        LinkedHashSet<String> visible = new LinkedHashSet<>();
        for (CatalogTier tier : snapshot.tiers) visible.add(tier.key);
        selectedTierKeys.retainAll(visible);
        LinearLayout choices = card();
        addText(choices, "哪些票价可以接受？", 18, TEXT, true, 0);
        addText(choices, "可以多选；缺货票档也能作为监控目标。选中这里只会保存偏好。", 12, MUTED, false, 7);
        LinearLayout quick = new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(button("全选", false, v -> {
            for (CatalogTier tier : snapshot.tiers) if (tier.price > 0) selectedTierKeys.add(tier.key);
            render(snapshot);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        quick.addView(button("清空", false, v -> { selectedTierKeys.clear(); render(snapshot); }), new LinearLayout.LayoutParams(0, -2, 1));
        choices.addView(quick, spaced(10));
        for (CatalogTier tier : snapshot.tiers) {
            CheckBox choice = new CheckBox(this);
            choice.setText(tier.label + "\n" + availabilityLabel(tier.availability));
            choice.setTextColor(TEXT); choice.setTextSize(16); choice.setMinHeight(dp(72));
            choice.setPadding(dp(8), dp(10), dp(8), dp(10));
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            choice.setEnabled(tier.price > 0); choice.setChecked(selectedTierKeys.contains(tier.key));
            choice.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selectedTierKeys.add(tier.key); else selectedTierKeys.remove(tier.key);
                updateApplyState();
            });
            choices.addView(choice, spaced(7));
        }
        if (snapshot.tiers.isEmpty()) addText(choices, "未读取到有效票价，请返回大麦显示完整票档后重试。", 13, MUTED, false, 12);
        selectionHint = text("", 13, MUTED, false); choices.addView(selectionHint, spaced(12));
        applyButton = button("下一步：核对并保存", true, v -> confirmSelection());
        choices.addView(applyButton, spaced(12));
        updateApplyState();
    }

    private void search() {
        String query = (searchInput == null ? queryDraft : searchInput.getText().toString()).trim();
        if (query.length() < 2 || query.length() > 60 || normalize(query).length() < 2) {
            showMessage("请输入演出名", "输入 2 至 60 个字符，可以是演出名、艺人名，也可以加上城市。"); return;
        }
        queryDraft = query;
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
        if (problem != null) selectionHint.setText(problem);
        else {
            List<Integer> prices = selectedPrices(shown); int maximum = 0;
            for (int price : prices) maximum = Math.max(maximum, price);
            selectionHint.setText("已选 " + prices.size() + " 个票价 · 购买 1 张\n最高实付 " + maximum + " 元\n监控会在 " + shown.session.date + " "
                    + shown.session.time + "（北京时间）停止。");
        }
    }

    private void confirmSelection() {
        CatalogStore.Snapshot snapshot = CatalogStore.get();
        if (shown == null || snapshot.revision != shown.revision) { render(snapshot); return; }
        String problem = selectionProblem(snapshot);
        if (problem != null) { showMessage("还不能保存", problem); return; }
        List<Integer> prices = selectedPrices(snapshot);
        int maximum = 0; for (int price : prices) maximum = Math.max(maximum, price);
        MonitorConfig old = new AppStore(this).loadConfig();
        MonitorConfig proposed = new MonitorConfig(matchedKeyword(snapshot), snapshot.event.city,
                snapshot.session.date, snapshot.session.time, prices, 1, maximum, deadline(snapshot),
                old.minIntervalMs, old.maxIntervalMs, old.mode);
        if (!proposed.validate().isEmpty()) {
            showMessage("配置还需检查", String.join("\n", proposed.validate()) + "\n可返回助手，在高级手动填写中修正。"); return;
        }
        String summary = snapshot.event.label + "\n" + snapshot.event.city + " · " + snapshot.session.date + " "
                + snapshot.session.time + "\n接受票面价：" + pricesText(prices) + "\n数量：1 张 · 订单实付上限：" + maximum
                + " 元\n停止时间：" + snapshot.session.date + " " + snapshot.session.time + "（北京时间）\n\n保存后回到任务页。监控保持暂停，由你决定何时开始；不会创建订单。";
        new AlertDialog.Builder(this).setTitle("第 4 步：核对并保存").setMessage(summary)
                .setNegativeButton("返回修改", null).setPositiveButton("保存到任务", (dialog, which) -> apply(snapshot, proposed)).show();
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
        store.setStatus("演出已保存，还没有开始监控。请在任务页核对后开始。");
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

    @Override public void onBackPressed() { returnToHelper(false); }
    @Override protected void onResume() {
        super.onResume(); CatalogController.dismissResultNotification(this);
        handler.removeCallbacks(refresh); handler.post(refresh);
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("queryDraft", searchInput == null ? queryDraft : searchInput.getText().toString());
        state.putBoolean("searchExpanded", searchExpanded);
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
        if ("OUT_OF_STOCK".equals(state)) return "当前显示缺货，可监控回流";
        if ("IN_STOCK_SIGNAL".equals(state)) return "页面有票信号，数量仍需核实";
        return "能读到票价，库存尚未确认";
    }
    private static String stageLabel(String stage) {
        switch (stage) {
            case "BUSY": return "正在读取大麦";
            case "PROJECTS": return "第 1 步 · 选演出";
            case "SESSIONS": return "第 2 步 · 选场次";
            case "TIERS": return "第 3 步 · 选票价";
            case "BLOCKED": return "读取暂停 · 需要你处理";
            default: return "选择新的目标";
        }
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(16), dp(17), dp(16), dp(17)); v.setBackground(shape(CARD, BORDER)); page.addView(v, spaced(16)); return v; }
    private TextView text(String value, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3), 1f); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private void addText(LinearLayout target, String value, int size, int color, boolean bold, int margin) { target.addView(text(value, size, color, bold), spaced(margin)); }
    private Button button(String title, boolean primary, View.OnClickListener listener) { Button v = new Button(this); v.setText(title); v.setAllCaps(false); v.setTextSize(14); v.setTextColor(primary ? Color.WHITE : TEXT); v.setMinHeight(dp(52)); v.setPadding(dp(10), dp(10), dp(10), dp(10)); v.setBackground(shape(primary ? ACCENT : CARD, primary ? ACCENT : BORDER)); v.setStateListAnimator(null); v.setOnClickListener(listener); return v; }
    private GradientDrawable shape(int color, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(12)); d.setStroke(dp(1), stroke); return d; }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void showMessage(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show(); }
}
