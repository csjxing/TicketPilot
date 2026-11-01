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
import android.view.Gravity;
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
import cn.local.tickethelper.catalog.CatalogGuidance;
import cn.local.tickethelper.catalog.CatalogPlanSelection;
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
    public static final String EXTRA_SEARCH_QUERY = "search_query";
    private static final String DISPLAY_PREFS = "catalog_target_display";
    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int BG = Color.rgb(247, 248, 252), CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(24, 33, 58), MUTED = Color.rgb(102, 113, 138);
    private static final int ACCENT = Color.rgb(61, 90, 254), TINT = Color.rgb(238, 241, 255), BORDER = Color.rgb(228, 232, 242);
    private static final int ERROR = Color.rgb(169, 54, 54);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> selectedTierKeys = new LinkedHashSet<>();
    private LinearLayout page, footer, progressTrack;
    private TextView headerStep;
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
        if (state == null) readIncomingSearch(getIntent());
        LinearLayout shell = column(); shell.setBackgroundColor(BG);
        LinearLayout header = row(); header.setPadding(dp(14), dp(4), dp(20), dp(4));
        Button back = link("‹", v -> goBack()); back.setTextSize(28); back.setContentDescription("返回上一步");
        header.addView(back, new LinearLayout.LayoutParams(dp(42), dp(48)));
        TextView headerTitle = text("添加购票计划", 16, TEXT, true);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, -2, 1));
        headerStep = text("1 / 4", 13, MUTED, false); header.addView(headerStep);
        shell.addView(header);
        progressTrack = row(); progressTrack.setPadding(dp(20), dp(0), dp(20), dp(0)); shell.addView(progressTrack);
        ScrollView scroll = new ScrollView(this); contentScroll = scroll;
        scroll.setBackgroundColor(BG); scroll.setFillViewport(true);
        page = column(); page.setPadding(dp(22), dp(4), dp(22), dp(24));
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
        super.onNewIntent(intent); setIntent(intent);
        if (searchInput != null) queryDraft = searchInput.getText().toString();
        searchInput = null; readIncomingSearch(intent); render(CatalogStore.get());
    }

    private void readIncomingSearch(Intent intent) {
        if (!intent.hasExtra(EXTRA_SEARCH_QUERY)) return;
        queryDraft = safe(intent.getStringExtra(EXTRA_SEARCH_QUERY));
        intent.removeExtra(EXTRA_SEARCH_QUERY);
        CatalogController.cancel(this);
        searchExpanded = true; reviewRequested = false;
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
        boolean blocked = "BLOCKED".equals(snapshot.stage), searching = !busy && (idle || searchExpanded);
        renderSteps(snapshot, searching);
        String heading = reviewRequested ? "这张计划，确认一下"
                : searching ? "想去谁的现场？" : "PROJECTS".equals(snapshot.stage) ? "选对演出和城市"
                : "SESSIONS".equals(snapshot.stage) ? "哪一天方便去？" : "TIERS".equals(snapshot.stage) ? "这些票价，你接受哪些？"
                : busy ? CatalogGuidance.busy(snapshot.status).title : "先解决这一步";
        addText(page, heading, 27, TEXT, true, 22);
        String subheading = reviewRequested ? "保存后返回计划页，核对条件再开始。"
                : searching ? "输入艺人或演出名，去大麦读取真实结果。"
                : "PROJECTS".equals(snapshot.stage) ? "点选一项，下一步读取这场演出的日期。"
                : "SESSIONS".equals(snapshot.stage) ? "点一个日期和时间，下一步选票价。"
                : "TIERS".equals(snapshot.stage) ? "可以多选；每次只购买其中一档、1 张。" : "";
        if (!subheading.isEmpty()) addText(page, subheading, 14, MUTED, false, 9);
        if (!searching && !reviewRequested && snapshot.event != null && !busy && !blocked) renderSelectedStrip(snapshot);
        if (searching) renderSearch(snapshot);
        else if (reviewRequested) renderReview(snapshot);
        else if ("PROJECTS".equals(snapshot.stage)) renderProjects(snapshot);
        else if ("SESSIONS".equals(snapshot.stage)) renderSessions(snapshot);
        else if ("TIERS".equals(snapshot.stage)) renderTiers(snapshot);
        else if (busy) renderHandoff(snapshot);
        else renderBlocked(snapshot);

        if (!busy && !searching && !reviewRequested) {
            LinearLayout utilities = row();
            utilities.addView(link("重新读取", v -> captureCurrent()), new LinearLayout.LayoutParams(0, -2, 1));
            Button change = link("换个演出", v -> editEvent()); change.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            utilities.addView(change, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(utilities, spaced(12));
        }
        if (snapshot.readAtMs > 0 && !busy && !searching) addText(page, "来自大麦 · " + Instant.ofEpochMilli(snapshot.readAtMs).atZone(VENUE_ZONE)
                .format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " 读取，库存仍需运行时核实", 11, MUTED, false, 10);
        if (changedStage) scrollTop();
    }

    private void renderSelectedStrip(CatalogStore.Snapshot snapshot) {
        LinearLayout strip = column(); strip.setPadding(dp(13), dp(12), dp(13), dp(12)); strip.setBackground(shape(TINT, TINT));
        addText(strip, snapshot.event.label, 13, TEXT, true, 0);
        if (snapshot.session != null) {
            addText(strip, sessionDate(snapshot.session), 12, MUTED, false, 5);
            strip.addView(link("修改场次  ›", v -> changeSession()), spaced(1));
        }
        page.addView(strip, spaced(18));
    }

    private void editEvent() {
        searchExpanded = true; reviewRequested = false; render(CatalogStore.get()); scrollTop();
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
        headerStep.setText(step + " / 4"); progressTrack.removeAllViews();
        String[] labels = {"演出", "场次", "票价", "核对"};
        for (int i = 0; i < labels.length; i++) {
            LinearLayout segment = column();
            TextView title = text((i + 1 < step ? "✓ " : "") + labels[i], 11, i + 1 <= step ? ACCENT : MUTED, i + 1 == step);
            title.setGravity(Gravity.CENTER); segment.addView(title);
            View bar = new View(this); bar.setBackground(shape(i + 1 <= step ? ACCENT : BORDER, i + 1 <= step ? ACCENT : BORDER));
            LinearLayout.LayoutParams barLayout = new LinearLayout.LayoutParams(-1, dp(3)); barLayout.topMargin = dp(7); segment.addView(bar, barLayout);
            segment.setContentDescription("第 " + (i + 1) + " 步，" + labels[i] + (i + 1 == step ? "，当前步骤" : ""));
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(0, -2, 1); if (i > 0) item.leftMargin = dp(7); progressTrack.addView(segment, item);
        }
    }

    private void primary(String title, String hint, boolean enabled, View.OnClickListener action) {
        footer.removeAllViews();
        selectionHint = text(hint, 12, TEXT, false);
        selectionHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        footer.addView(selectionHint);
        applyButton = button(title, true, action); applyButton.setEnabled(enabled); applyButton.setAlpha(enabled ? 1f : 0.45f);
        footer.addView(applyButton, spaced(hint.isEmpty() ? 0 : 8));
    }

    private void renderSearch(CatalogStore.Snapshot snapshot) {
        searchInput = new EditText(this);
        searchInput.setSingleLine(true); searchInput.setTextSize(18); searchInput.setTextColor(TEXT);
        searchInput.setHintTextColor(MUTED); searchInput.setHint("艺人 / 演出名称，可加城市");
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        searchInput.setContentDescription("演出搜索，输入艺人或演出名，可加城市");
        searchInput.setBackground(shape(CARD, BORDER)); searchInput.setPadding(dp(16), dp(15), dp(16), dp(15));
        searchInput.setMinHeight(dp(58)); searchInput.setText(queryDraft); page.addView(searchInput, spaced(25));
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
        addText(page, "例如：谢霆锋 杭州", 12, MUTED, false, 9);
        if (!"IDLE".equals(snapshot.stage) && !"BLOCKED".equals(snapshot.stage)) {
            page.addView(link("继续刚才的选择  ›", v -> { searchExpanded = false; render(CatalogStore.get()); scrollTop(); }), spaced(7));
        }
        LinearLayout reading = column(); reading.setPadding(dp(0), dp(24), dp(0), dp(12));
        addText(reading, "已经在大麦看中一场？", 15, TEXT, true, 0);
        reading.addView(link("直接读取当前页面  →", v -> captureCurrent()), spaced(4)); page.addView(reading);
        primary("去大麦搜索", "读完后，点「票务助手 · 演出选择」通知回来", queryDraft.trim().length() >= 2, v -> search());
    }

    private void renderProjects(CatalogStore.Snapshot snapshot) {
        LinearLayout results = surface();
        for (int i = 0; i < snapshot.projects.size(); i++) {
            CatalogProject project = snapshot.projects.get(i);
            if (i > 0) divider(results);
            boolean selected = project.key.equals(selectedProjectKey);
            LinearLayout option = selectableRow(project.label, safe(project.city).isEmpty() ? "城市尚未读取" : project.city,
                    selected, v -> { selectedProjectKey = project.key; render(snapshot); });
            results.addView(option);
        }
        if (snapshot.projects.isEmpty()) emptyState("还没有找到可选演出", "换一个艺人或演出名，或在大麦展开结果后重新读取。");
        primary("继续选场次  →", selectedProjectKey.isEmpty() ? "先点选一场演出" : "下一步：到大麦读取该演出的日期",
                !selectedProjectKey.isEmpty(), v -> continueProject(snapshot));
    }

    private void continueProject(CatalogStore.Snapshot snapshot) {
        if (!isCurrent(snapshot)) return;
        for (CatalogProject project : snapshot.projects) if (project.key.equals(selectedProjectKey)) {
            searchExpanded = false; returnToDamai(() -> CatalogController.selectProject(this, project.key)); return;
        }
    }

    private void renderSessions(CatalogStore.Snapshot snapshot) {
        LinearLayout results = surface();
        for (int i = 0; i < snapshot.sessions.size(); i++) {
            CatalogSession session = snapshot.sessions.get(i); if (i > 0) divider(results);
            boolean selected = session.key.equals(selectedSessionKey);
            LinearLayout option = row(); option.setPadding(dp(13), dp(16), dp(13), dp(16));
            option.setBackgroundColor(selected ? TINT : CARD);
            String stamp = "日期", title = session.label, subtitle = "完整日期和时间尚待读取";
            try {
                LocalDate date = LocalDate.parse(session.date);
                stamp = date.getMonthValue() + "月\n" + date.getDayOfMonth();
                title = date.format(DateTimeFormatter.ofPattern("EEEE", Locale.CHINA)) + "  " + session.time;
                subtitle = date.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)) + " · 以演出当地时间为准";
            } catch (RuntimeException ignored) { }
            TextView calendar = text(stamp, 16, selected ? ACCENT : TEXT, true); calendar.setGravity(Gravity.CENTER);
            calendar.setPadding(dp(6), dp(6), dp(6), dp(6)); calendar.setBackground(shape(selected ? CARD : BG, selected ? CARD : BG));
            option.addView(calendar, new LinearLayout.LayoutParams(dp(58), -2));
            LinearLayout words = column(); addText(words, title, 17, TEXT, true, 0); addText(words, subtitle, 12, MUTED, false, 5);
            LinearLayout.LayoutParams wordsLayout = new LinearLayout.LayoutParams(0, -2, 1); wordsLayout.leftMargin = dp(13); option.addView(words, wordsLayout);
            TextView check = text(selected ? "●" : "○", 20, selected ? ACCENT : BORDER, true); option.addView(check);
            option.setSelected(selected); option.setFocusable(true); option.setClickable(true);
            option.setContentDescription(sessionDate(session) + (selected ? "，已选择" : "，点按选择"));
            option.setOnClickListener(v -> { selectedSessionKey = session.key; render(snapshot); }); results.addView(option);
        }
        if (snapshot.sessions.isEmpty()) emptyState("暂时没有读到日期", "在大麦打开这场演出的场次页，再点重新读取。");
        primary("继续选票价  →", selectedSessionKey.isEmpty() ? "先点选一个日期和时间" : "下一步：读取这一场可选择的票价",
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

    private LinearLayout selectableRow(String title, String detail, boolean selected, View.OnClickListener listener) {
        LinearLayout option = row(); option.setPadding(dp(15), dp(18), dp(15), dp(18));
        option.setBackgroundColor(selected ? TINT : CARD);
        LinearLayout words = column(); addText(words, title, 17, TEXT, true, 0);
        if (!detail.isEmpty()) {
            TextView city = text(detail, 11, selected ? ACCENT : MUTED, true);
            city.setPadding(dp(8), dp(4), dp(8), dp(4)); city.setBackground(shape(selected ? CARD : BG, selected ? CARD : BG));
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, -2); badge.topMargin = dp(8); words.addView(city, badge);
        }
        option.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView check = text(selected ? "●" : "○", 21, selected ? ACCENT : BORDER, true); option.addView(check);
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
            choice.setText("¥ " + tier.price + "\n" + availabilityLabel(tier.availability));
            choice.setTextColor(TEXT); choice.setTextSize(14); choice.setMinHeight(dp(108));
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
        primary("核对这份计划  →", "", false, v -> confirmSelection()); updateApplyState();
    }

    private void styleTier(CheckBox choice, boolean checked) {
        choice.setBackground(shape(checked ? TINT : CARD, checked ? ACCENT : BORDER));
        choice.setTextColor(checked ? ACCENT : TEXT);
    }

    private void renderReview(CatalogStore.Snapshot snapshot) {
        List<Integer> prices = selectedPrices(snapshot); int maximum = 0;
        for (int price : prices) maximum = Math.max(maximum, price);
        LinearLayout ticket = column(); ticket.setBackground(shape(CARD, CARD)); page.addView(ticket, spaced(24));
        LinearLayout ticketTop = column(); ticketTop.setPadding(dp(19), dp(20), dp(19), dp(20)); ticketTop.setBackground(shape(TEXT, TEXT));
        addText(ticketTop, "TICKET PLAN  /  购票计划", 10, Color.rgb(173, 187, 220), true, 0);
        addText(ticketTop, snapshot.event.label, 20, Color.WHITE, true, 13);
        addText(ticketTop, snapshot.event.city, 13, Color.rgb(207, 216, 237), false, 8); ticket.addView(ticketTop);
        LinearLayout facts = column(); facts.setPadding(dp(18), dp(8), dp(18), dp(18)); ticket.addView(facts);
        summaryRow(facts, "场次", sessionDate(snapshot.session), "改场次", v -> changeSession());
        divider(facts); summaryRow(facts, "接受票价", pricesText(prices), "改票价", v -> { reviewRequested = false; render(CatalogStore.get()); scrollTop(); });
        divider(facts); summaryRow(facts, "数量", "1 张 · 其中一档", "", null);
        divider(facts); summaryRow(facts, "实付上限", "¥ " + maximum, "", null);
        page.addView(link("演出选错了？重新选择", v -> editEvent()), spaced(9));
        addText(page, "下一步：回到计划页，选择有票后的动作，再开始。", 14, TEXT, false, 14);
        addText(page, "保存不会提交订单。只有大麦确认待付款订单，才算锁票。", 12, MUTED, false, 8);
        primary("保存到计划  →", "所选票价任选其一，至本场开演时停止", true, v -> saveReviewedSelection());
    }

    private void summaryRow(LinearLayout target, String label, String value, String action, View.OnClickListener listener) {
        LinearLayout row = row(); row.setPadding(dp(0), dp(15), dp(0), dp(15));
        LinearLayout words = column(); addText(words, label, 11, MUTED, false, 0); addText(words, value, 16, TEXT, true, 5);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (!action.isEmpty()) { Button edit = link(action, listener); edit.setTextSize(12); row.addView(edit, new LinearLayout.LayoutParams(dp(66), -2)); }
        target.addView(row);
    }

    private void renderHandoff(CatalogStore.Snapshot snapshot) {
        CatalogGuidance guide = CatalogGuidance.busy(snapshot.status);
        LinearLayout instruction = column(); instruction.setPadding(dp(18), dp(20), dp(18), dp(20)); instruction.setBackground(shape(TEXT, TEXT));
        addText(instruction, "现在请这样做", 11, Color.rgb(173, 187, 220), true, 0);
        addText(instruction, guide.instruction, 20, Color.WHITE, true, 12);
        addText(instruction, guide.after, 14, Color.rgb(207, 216, 237), false, 14); page.addView(instruction, spaced(24));
        addText(page, "返回方式", 12, MUTED, true, 24);
        addText(page, "看到「票务助手 · 演出选择」通知后，点通知回这里继续。", 15, TEXT, false, 7);
        page.addView(link("查看当前读取状态", v -> showMessage("当前进度", safe(snapshot.status))), spaced(10));
        page.addView(link("停止这次读取", v -> { CatalogController.cancel(this); render(CatalogStore.get()); }), spaced(6));
        primary(guide.button, "收起后用最近任务切回大麦，保持解锁和亮屏", true, v -> finishAndRemoveTask());
    }

    private void renderBlocked(CatalogStore.Snapshot snapshot) {
        LinearLayout instruction = column(); instruction.setPadding(dp(18), dp(18), dp(18), dp(18));
        instruction.setBackground(shape(Color.rgb(255, 243, 235), Color.rgb(255, 243, 235)));
        addText(instruction, "这次读取已停止", 12, ERROR, true, 0);
        addText(instruction, safe(snapshot.status).isEmpty() ? "没有读到完整信息，请在大麦打开演出详情或票档页。" : safe(snapshot.status), 16, TEXT, true, 9);
        addText(instruction, "之前保存的计划没有改变。", 12, MUTED, false, 12); page.addView(instruction, spaced(24));
        boolean setup = safe(snapshot.status).contains("无障碍") || safe(snapshot.status).contains("通知");
        primary(setup ? "去完成手机准备  →" : "处理好了，重新读取  →",
                setup ? "完成必要设置后再回来读取" : "先在大麦处理页面提示，然后再试一次", true,
                v -> { if (setup) returnToSetup(); else captureCurrent(); });
    }

    private void returnToSetup() {
        CatalogController.cancel(this);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("route", "setup")); finishAndRemoveTask();
    }

    private void captureCurrent() {
        searchExpanded = false; reviewRequested = false;
        returnToDamai(() -> CatalogController.captureCurrent(this, queryDraft.trim()));
    }

    private void changeSession() {
        new AlertDialog.Builder(this).setTitle("重新选择场次")
                .setMessage("下一步会重新读取大麦中的日期。请在大麦保持这场演出的场次页打开；读取后点助手通知回来选日期。当前已保存的计划不会改变。")
                .setNegativeButton("暂不修改", null)
                .setPositiveButton("重新读取场次", (dialog, which) -> captureCurrent()).show();
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
            if (applyButton != null) applyButton.setEnabled(false);
            // The controller requests normal Damai foreground navigation before this
            // catalog task is removed; closing this task alone cannot restore Damai.
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
        String query = searchInput == null ? queryDraft.trim() : searchInput.getText().toString().trim();
        return CatalogPlanSelection.problem(snapshot, selectedTierKeys, 1, query, System.currentTimeMillis());
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
        String query = searchInput == null ? queryDraft.trim() : searchInput.getText().toString().trim();
        try {
            CatalogPlanSaver.save(this, snapshot, selectedTierKeys, 1, query);
            returnToHelper(true);
        } catch (IllegalArgumentException invalid) {
            reviewRequested = false; render(CatalogStore.get());
            showMessage("请重新核对", invalid.getMessage());
        }
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
        store.setStatus("计划已保存。下一步选择有票后的动作，再由你开始。");
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
        CatalogStore.Snapshot current = CatalogStore.get();
        if (reviewRequested) { reviewRequested = false; render(current); scrollTop(); }
        else if (searchExpanded) returnToHelper(false);
        else if ("TIERS".equals(current.stage)) changeSession();
        else if ("PROJECTS".equals(current.stage) || "SESSIONS".equals(current.stage)) editEvent();
        else if ("BUSY".equals(current.stage)) new AlertDialog.Builder(this).setTitle("停止这次读取？")
                .setMessage("返回计划页会停止当前读取，已保存的演出不变。")
                .setNegativeButton("继续等待", null).setPositiveButton("停止并返回", (d, w) -> returnToHelper(false)).show();
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
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout surface() { LinearLayout v = column(); v.setBackground(shape(CARD, CARD)); page.addView(v, spaced(22)); return v; }
    private void divider(LinearLayout target) { View line = new View(this); line.setBackgroundColor(BORDER); target.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private void emptyState(String title, String detail) { addText(page, title, 17, TEXT, true, 22); addText(page, detail, 14, MUTED, false, 8); }
    private TextView text(String value, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3), 1f); if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return v; }
    private void addText(LinearLayout target, String value, int size, int color, boolean bold, int margin) { target.addView(text(value, size, color, bold), spaced(margin)); }
    private Button button(String title, boolean primary, View.OnClickListener listener) { Button v = new Button(this); v.setText(title); v.setAllCaps(false); v.setTextSize(15); v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); v.setTextColor(primary ? Color.WHITE : TEXT); v.setMinHeight(dp(54)); v.setPadding(dp(10), dp(10), dp(10), dp(10)); v.setBackground(shape(primary ? ACCENT : CARD, primary ? ACCENT : BORDER)); v.setStateListAnimator(null); v.setOnClickListener(listener); return v; }
    private Button link(String title, View.OnClickListener listener) {
        Button v = button(title, false, listener); v.setMinHeight(dp(44)); v.setPadding(dp(0), dp(8), dp(0), dp(8));
        v.setTextColor(ACCENT); v.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        v.setBackgroundColor(Color.TRANSPARENT); return v;
    }
    private GradientDrawable shape(int color, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(10)); d.setStroke(dp(1), stroke); return d; }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private void showMessage(String title, String message) { new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show(); }
}
