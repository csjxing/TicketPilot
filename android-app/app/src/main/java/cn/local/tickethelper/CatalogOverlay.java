package cn.local.tickethelper;

import android.accessibilityservice.AccessibilityService;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.local.tickethelper.catalog.CatalogOverlayDraft;
import cn.local.tickethelper.catalog.CatalogTier;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Main-thread, user-directed chooser above Damai. This window never clicks or reads the app itself.
 * The owner removes it before a fresh read and closes it when locked or leaving the target app.
 */
public final class CatalogOverlay implements AutoCloseable {
    public interface Actions {
        void captureSelected();
        void save(CatalogStore.Snapshot snapshot, Set<String> tierKeys, int quantity);
        void startSelectionTest();
        void closed();
    }

    private enum Mode { READY, RESULT, FAILURE, SAVED }
    private static final int INK = Color.rgb(24, 33, 58), MUTED = Color.rgb(102, 113, 138);
    private static final int ACCENT = Color.rgb(61, 90, 254), TINT = Color.rgb(238, 241, 255);
    private static final int BORDER = Color.rgb(225, 230, 240), WHITE = Color.WHITE;
    private final AccessibilityService service;
    private final WindowManager windows;
    private final Actions actions;
    private final CatalogOverlayDraft draft;
    private View attached;
    private CatalogStore.Snapshot snapshot;
    private Mode mode = Mode.READY;
    private boolean open, collapsed, reviewing, saving;
    private String message = "";
    private Button primary;
    private TextView hint;

    public CatalogOverlay(AccessibilityService service, int initialQuantity, Actions actions) {
        if (service == null || actions == null) throw new IllegalArgumentException("Overlay owner is required");
        this.service = service; this.actions = actions;
        windows = service.getSystemService(WindowManager.class);
        draft = new CatalogOverlayDraft(initialQuantity);
    }

    /** True throughout an in-place selection, including while removed for a read. */
    public boolean isOpen() { return open; }
    public boolean isVisible() { return attached != null; }

    public void showReady() { showReady("先在大麦选好日期和时间，再点下面的按钮。"); }
    public void showReady(String instruction) {
        open = true; collapsed = false; reviewing = false; saving = false; mode = Mode.READY;
        message = instruction == null ? "" : instruction; snapshot = null; draft.clear(); render();
    }

    public void showResult(CatalogStore.Snapshot result) {
        if (!open) return;
        if (result == null || !"TIERS".equals(result.stage) || result.event == null || result.session == null
                || result.tiers.isEmpty()) {
            showFailure(result == null ? "还没有读到完整场次，请在大麦选好日期和时间。" : result.status);
            return;
        }
        boolean same = snapshot != null && snapshot.revision == result.revision;
        snapshot = result; mode = Mode.RESULT; collapsed = false; saving = false; message = "";
        if (!same) reviewing = false;
        List<String> keys = new ArrayList<>();
        for (CatalogTier tier : result.tiers) if (tier.price > 0) keys.add(tier.key);
        draft.replace(result.revision, result.readAtMs, keys); render();
    }

    public void showFailure(String reason) {
        if (!open) return;
        mode = Mode.FAILURE; collapsed = false; reviewing = false; saving = false;
        snapshot = null; draft.clear();
        message = reason == null || reason.isBlank() ? "请在大麦选好场次，再试一次。" : reason;
        render();
    }

    public void showSaved(String summary) {
        if (!open) return;
        mode = Mode.SAVED; collapsed = false; reviewing = false; saving = false;
        message = summary == null ? "计划已保存，尚未开始。" : summary; render();
    }

    /** Remove the actual window, rather than making it transparent, before any app read/input. */
    public void hideForRead() { removeWindow(); }

    @Override public void close() {
        boolean wasOpen = open;
        open = false; saving = false; snapshot = null; draft.clear(); removeWindow();
        if (wasOpen) actions.closed();
    }

    private void capture() {
        if (!open) return;
        snapshot = null; reviewing = false; saving = false; draft.clear();
        hideForRead(); actions.captureSelected();
    }

    private void render() {
        if (!open) return;
        removeWindow(); primary = null; hint = null;
        LinearLayout panel = column(); panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        panel.setBackground(shape(WHITE, BORDER, 18)); panel.setElevation(dp(12));
        panel.setFilterTouchesWhenObscured(true);
        if (collapsed) {
            LinearLayout pill = row();
            Button expand = link("票务助手 · 展开", v -> { collapsed = false; render(); });
            pill.addView(expand, new LinearLayout.LayoutParams(0, -2, 1));
            pill.addView(closeButton()); panel.addView(pill); attach(panel, false); return;
        }
        LinearLayout heading = row();
        TextView title = text("TicketPilot · 当前场次", 15, INK, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(link("收起", v -> { collapsed = true; render(); })); heading.addView(closeButton());
        panel.addView(heading);
        boolean full = mode == Mode.RESULT;
        LinearLayout content = column();
        if (full) {
            ScrollView scroll = new ScrollView(service); scroll.setFillViewport(false); scroll.addView(content);
            panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            if (reviewing) renderReview(content); else renderChoices(content);
        } else {
            add(content, mode == Mode.SAVED ? "计划已保存" : mode == Mode.FAILURE ? "需要你处理一下" : "留在大麦，直接选场次", 18, INK, true, 8);
            add(content, message, 13, MUTED, false, 7); panel.addView(content);
        }
        if (mode == Mode.RESULT) {
            hint = text("", 12, MUTED, false); panel.addView(hint, spacing(8));
            primary = button(reviewing ? "确认保存计划" : "核对这份计划", true, v -> {
                if (reviewing) save(); else review();
            }); panel.addView(primary, spacing(8));
            updateHint();
        } else if (mode == Mode.SAVED) {
            add(panel, "本次试选进入确认购买页后暂停；只有大麦确认待付款订单，才算锁票。", 12, MUTED, false, 9);
            primary = button("开始选票测试（到确认页暂停）", true, v -> {
                if (primary != null) primary.setEnabled(false);
                actions.startSelectionTest();
            });
            panel.addView(primary, spacing(12));
            panel.addView(link("先保存，稍后开始", v -> { collapsed = true; render(); }), spacing(2));
            panel.addView(link("重新读取场次", v -> capture()), spacing(2));
        } else {
            panel.addView(button(mode == Mode.FAILURE ? "处理好了，重新读取" : "使用当前场次", true, v -> capture()), spacing(12));
        }
        attach(panel, full);
    }

    private void renderChoices(LinearLayout target) {
        add(target, snapshot.event.label, 15, INK, true, 8);
        add(target, sessionLabel(), 14, ACCENT, true, 6);
        target.addView(link("改场次：在大麦选好后点此读取", v -> capture()), spacing(2));
        add(target, "接受哪些票价？可多选，每次使用其中一档。", 13, MUTED, false, 8);
        LinearLayout grid = null;
        for (int i = 0; i < snapshot.tiers.size(); i++) {
            CatalogTier tier = snapshot.tiers.get(i);
            if (i % 2 == 0) { grid = row(); target.addView(grid, spacing(8)); }
            CheckBox choice = new CheckBox(service);
            choice.setText(String.format(Locale.CHINA, "¥ %d\n%s", tier.price, stockLabel(tier.availability)));
            choice.setTextSize(14); choice.setMinHeight(dp(66)); choice.setPadding(dp(5), dp(8), dp(5), dp(8));
            choice.setButtonTintList(ColorStateList.valueOf(ACCENT));
            choice.setChecked(draft.contains(tier.key)); choice.setEnabled(tier.price > 0); styleChoice(choice);
            choice.setOnCheckedChangeListener((view, checked) -> {
                draft.select(tier.key, checked); styleChoice(choice); updateHint();
            });
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 == 1) cell.leftMargin = dp(8);
            grid.addView(choice, cell);
        }
        if (snapshot.tiers.size() % 2 == 1 && grid != null) grid.addView(new View(service), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout quantity = row(); quantity.addView(text("数量", 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        for (int value = 1; value <= 2; value++) {
            final int count = value;
            Button amount = button(value + " 张", draft.quantity() == value, v -> {
                draft.setQuantity(count); render();
            });
            LinearLayout.LayoutParams chip = new LinearLayout.LayoutParams(dp(72), dp(42)); chip.leftMargin = dp(8);
            quantity.addView(amount, chip);
        }
        target.addView(quantity, spacing(14));
        add(target, "状态来自本次读取；可见票价不代表已有所需库存。", 11, MUTED, false, 9);
    }

    private void renderReview(LinearLayout target) {
        add(target, "核对后保存", 20, INK, true, 8);
        add(target, snapshot.event.label, 15, INK, true, 12);
        add(target, sessionLabel(), 14, ACCENT, true, 8);
        add(target, priceLabel() + " · " + draft.quantity() + " 张", 16, INK, true, 12);
        add(target, "票款上限 ¥ " + maximumTotal(), 16, INK, true, 8);
        add(target, "保存后尚未开始。观演人和后续购票动作需另行核对。", 13, MUTED, false, 14);
        target.addView(link("返回修改票价或数量", v -> { reviewing = false; draft.edit(); render(); }), spacing(12));
        target.addView(link("演出或场次不对，重新读取", v -> capture()), spacing(2));
    }

    private void review() {
        if (!current() || !draft.beginReview(System.currentTimeMillis(), CatalogStore.get().revision)) {
            updateHint(); return;
        }
        reviewing = true; render();
    }

    private void save() {
        if (saving) return;
        if (!current() || !draft.canSave(System.currentTimeMillis(), CatalogStore.get().revision)) {
            showFailure("这份读取结果已变化或过期，请重新读取后核对。"); return;
        }
        saving = true;
        if (primary != null) primary.setEnabled(false);
        if (hint != null) hint.setText("正在保存计划…");
        try { actions.save(snapshot, draft.keys(), draft.quantity()); }
        catch (IllegalArgumentException invalid) { showFailure(invalid.getMessage()); }
    }

    private boolean current() {
        return snapshot != null && CatalogStore.get().revision == snapshot.revision
                && "TIERS".equals(CatalogStore.get().stage)
                && draft.fresh(System.currentTimeMillis(), snapshot.revision);
    }

    private void updateHint() {
        if (primary == null || hint == null || snapshot == null) return;
        boolean fresh = current(), chosen = !draft.keys().isEmpty();
        hint.setText(!fresh ? "读取结果已变化或过期，请重新读取。" : !chosen ? "先勾选接受的票价。"
                : "已选 " + draft.keys().size() + " 档 · " + draft.quantity() + " 张 · 票款最多 ¥ " + maximumTotal());
        primary.setEnabled(!saving && fresh && chosen && (!reviewing || draft.canSave(System.currentTimeMillis(), snapshot.revision)));
    }

    private String priceLabel() {
        StringBuilder label = new StringBuilder();
        for (CatalogTier tier : snapshot.tiers) if (draft.contains(tier.key)) {
            if (label.length() > 0) label.append(" / "); label.append("¥ ").append(tier.price);
        }
        return label.toString();
    }
    private long maximumTotal() {
        int maximum = 0;
        for (CatalogTier tier : snapshot.tiers) if (draft.contains(tier.key)) maximum = Math.max(maximum, tier.price);
        return (long) maximum * draft.quantity();
    }
    private String sessionLabel() {
        try { return snapshot.event.city + " · " + LocalDate.parse(snapshot.session.date)
                .format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)) + " " + snapshot.session.time; }
        catch (RuntimeException invalid) { return snapshot.session.date + " " + snapshot.session.time; }
    }
    private static String stockLabel(String state) {
        return "OUT_OF_STOCK".equals(state) ? "读取时缺货" : "IN_STOCK_SIGNAL".equals(state) ? "有票信号，仍需核实" : "库存待核实";
    }
    private void styleChoice(CheckBox choice) {
        choice.setTextColor(choice.isChecked() ? ACCENT : INK);
        choice.setBackground(shape(choice.isChecked() ? TINT : WHITE, choice.isChecked() ? ACCENT : BORDER, 10));
    }
    private Button closeButton() {
        Button close = link("×", v -> close()); close.setContentDescription("关闭悬浮助手"); close.setTextSize(22);
        close.setMinWidth(dp(36)); close.setMinimumWidth(dp(36)); return close;
    }
    private void attach(View view, boolean expandedResult) {
        Rect screen = windows.getCurrentWindowMetrics().getBounds();
        int width = Math.max(1, Math.min(screen.width() - dp(20), dp(480)));
        int height = expandedResult ? Math.max(1, (int) (screen.height() * .55f)) : WindowManager.LayoutParams.WRAP_CONTENT;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; params.y = dp(10);
        params.setTitle("TicketPilot · 悬浮选票");
        try { windows.addView(view, params); attached = view; }
        catch (RuntimeException unavailable) {
            open = false; snapshot = null; draft.clear(); attached = null;
            actions.closed();
            Toast.makeText(service, "悬浮面板未能显示，请重新打开助手检查无障碍服务。", Toast.LENGTH_LONG).show();
        }
    }
    private void removeWindow() {
        View prior = attached; attached = null;
        if (prior != null) {
            try { windows.removeViewImmediate(prior); } catch (IllegalArgumentException detached) { /* Already removed by the system. */ }
        }
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(service); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(service); layout.setOrientation(LinearLayout.HORIZONTAL); layout.setGravity(Gravity.CENTER_VERTICAL); return layout; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(service); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(2), 1f); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private void add(LinearLayout parent, String value, int size, int color, boolean bold, int margin) {
        parent.addView(text(value, size, color, bold), spacing(margin));
    }
    private Button button(String title, boolean filled, View.OnClickListener listener) {
        Button view = new Button(service); view.setText(title); view.setAllCaps(false); view.setTextSize(14);
        view.setTextColor(filled ? WHITE : ACCENT); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setMinHeight(dp(46)); view.setMinimumHeight(dp(46)); view.setPadding(dp(8), dp(6), dp(8), dp(6));
        view.setBackground(shape(filled ? ACCENT : TINT, filled ? ACCENT : TINT, 10));
        view.setStateListAnimator(null); view.setOnClickListener(listener); return view;
    }
    private Button link(String title, View.OnClickListener listener) {
        Button view = button(title, false, listener); view.setTextSize(12); view.setBackgroundColor(Color.TRANSPARENT);
        view.setMinHeight(dp(38)); view.setMinimumHeight(dp(38)); view.setMinWidth(0); view.setMinimumWidth(0); return view;
    }
    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius)); shape.setStroke(dp(1), stroke); return shape;
    }
    private LinearLayout.LayoutParams spacing(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private int dp(int value) { return Math.round(service.getResources().getDisplayMetrics().density * value); }
}
