package cn.local.tickethelper;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.local.tickethelper.checkout.ViewerMatcher;
import java.util.ArrayList;
import java.util.List;

/** Local attendee matching preferences. Saving never selects a Damai attendee or starts a task. */
public final class ViewerSetupActivity extends Activity {
    private static final int BG = Color.rgb(247, 248, 252), INK = Color.rgb(24, 33, 58);
    private static final int MUTED = Color.rgb(102, 113, 138), BLUE = Color.rgb(61, 90, 254);
    private static final int BORDER = Color.rgb(228, 232, 242), TINT = Color.rgb(238, 241, 255);
    private final List<EditText> names = new ArrayList<>(), suffixes = new ArrayList<>();
    private AppStore store;
    private int quantity;
    private long configRevision;
    private TextView error;
    private Button save;
    private boolean saving;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new AppStore(this);
        MonitorService monitor = MonitorService.instance;
        if (monitor != null) monitor.pause("正在设置观演人，任务保持暂停。");
        store.setArmed(false);
        quantity = store.loadConfig().quantity;
        configRevision = store.getConfigRevision();
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout shell = column(); shell.setBackgroundColor(BG);
        shell.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        shell.setSaveEnabled(false);
        LinearLayout heading = row(); heading.setPadding(dp(18), dp(5), dp(18), dp(5));
        Button back = link("‹ 返回", v -> finish()); heading.addView(back);
        TextView title = text("观演人", 17, INK, true); title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); shell.addView(heading);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(); body.setPadding(dp(22), dp(12), dp(22), dp(24)); scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        add(body, "这次为谁买票？", 27, INK, true, 8);
        add(body, "姓名须与大麦中已经登记的观演人完全一致。这里仅保存匹配信息，不会替你新增大麦观演人。", 14, MUTED, false, 12);
        boolean validQuantity = quantity >= 1 && quantity <= 2;
        add(body, validQuantity ? "当前计划：" + quantity + " 张票，需要 " + quantity + " 位观演人。" : "请先将购票计划数量设置为 1 或 2 张。", 14, BLUE, true, 16);

        List<ViewerMatcher.RequestedViewer> existing = store.getViewerBindings();
        if (validQuantity) for (int index = 0; index < quantity; index++) {
            LinearLayout card = column(); card.setPadding(dp(16), dp(16), dp(16), dp(18));
            card.setBackground(shape(Color.WHITE, BORDER)); body.addView(card, spacing(18));
            add(card, "观演人 " + (index + 1), 17, INK, true, 0);
            add(card, "大麦登记姓名（必填）", 13, MUTED, false, 16);
            EditText name = input("请输入完整姓名", true);
            name.setContentDescription("观演人 " + (index + 1) + "，大麦登记姓名，必填");
            name.setFilters(new InputFilter[]{limit(name, 80, false)});
            card.addView(name, spacing(7)); names.add(name);
            add(card, "证件末 2–6 位（选填）", 13, MUTED, false, 16);
            EditText suffix = input("同名时可填末几位，勿填完整号码", false);
            suffix.setContentDescription("观演人 " + (index + 1) + "，证件末 2 至 6 位，选填");
            suffix.setFilters(new InputFilter[]{limit(suffix, 6, true)});
            card.addView(suffix, spacing(7)); suffixes.add(suffix);
            add(card, "只用于区分同名观演人；大麦页面也需能看到对应尾号。", 11, MUTED, false, 8);
            if (index < existing.size()) {
                ViewerMatcher.RequestedViewer viewer = existing.get(index);
                name.setText(viewer.displayName); suffix.setText(viewer.documentSuffix);
            }
        }
        LinearLayout privacy = column(); privacy.setPadding(dp(14), dp(12), dp(14), dp(12)); privacy.setBackground(shape(TINT, TINT));
        add(privacy, "只保存在这台手机", 13, BLUE, true, 0);
        add(privacy, "姓名与尾号不会发送给模型，也不写入运行日志或电脑交接包。保存后仍需在大麦核对观演人。", 12, MUTED, false, 6);
        body.addView(privacy, spacing(18));
        error = text("", 13, Color.rgb(172, 53, 53), false); error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE); body.addView(error, spacing(12));

        LinearLayout footer = column(); footer.setPadding(dp(22), dp(12), dp(22), dp(10)); footer.setBackgroundColor(Color.WHITE);
        save = button("保存观演人", true, v -> save()); save.setEnabled(validQuantity); footer.addView(save);
        footer.addView(link("取消，保留原设置", v -> finish()), spacing(3)); shell.addView(footer);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom)); return insets;
        });
        setContentView(shell);
        for (int index = 0; index < suffixes.size(); index++) {
            boolean last = index == suffixes.size() - 1;
            suffixes.get(index).setImeOptions((last ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT)
                    | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            if (last) suffixes.get(index).setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_DONE) return false;
                InputMethodManager keyboard = getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
                return true;
            });
        }
    }

    private void save() {
        if (saving || !hasWindowFocus()) return;
        if (store.getConfigRevision() != configRevision || store.loadConfig().quantity != quantity) {
            showError("购票计划已变化，请返回后重新打开观演人设置。"); return;
        }
        List<ViewerMatcher.RequestedViewer> requested = new ArrayList<>();
        for (int index = 0; index < quantity; index++) {
            String name = names.get(index).getText().toString().trim();
            String suffix = suffixes.get(index).getText().toString().trim();
            if (name.isEmpty()) {
                names.get(index).setError("请填写大麦中登记的完整姓名"); names.get(index).requestFocus(); return;
            }
            if (!suffix.isEmpty() && !suffix.matches("[0-9A-Za-z]{2,6}")) {
                suffixes.get(index).setError("请填末 2–6 位字母或数字，或留空"); suffixes.get(index).requestFocus(); return;
            }
            requested.add(new ViewerMatcher.RequestedViewer(name, suffix));
        }
        saving = true; save.setEnabled(false);
        try {
            store.saveViewerBindings(requested);
            Toast.makeText(this, "观演人已保存在本机，任务仍暂停", Toast.LENGTH_SHORT).show();
            finish();
        } catch (RuntimeException invalid) {
            // Storage owns duplicate-identity and exact local binding validation; never echo an identity.
            showError("观演人信息未保存。请检查姓名、尾号及是否重复；若仍失败，请稍后重试。");
            saving = false; save.setEnabled(true);
        }
    }

    /** Reject an overlong paste as a whole, instead of silently turning an ID into a wrong suffix. */
    private InputFilter limit(EditText field, int maximum, boolean suffix) {
        return (source, start, end, dest, dstart, dend) -> {
            if (dest.length() - (dend - dstart) + end - start > maximum) {
                field.setError(suffix ? "这里只填写末 2–6 位，请勿输入完整证件号" : "姓名过长，请按大麦登记信息填写");
                return "";
            }
            if (suffix) for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'))) {
                    field.setError("尾号仅填写字母或数字"); return "";
                }
            }
            return null;
        };
    }

    private EditText input(String placeholder, boolean name) {
        EditText field = new EditText(this); field.setTextSize(16); field.setTextColor(INK); field.setHintTextColor(MUTED);
        field.setHint(placeholder); field.setSingleLine(true); field.setMinHeight(dp(50)); field.setPadding(dp(12), dp(9), dp(12), dp(9));
        field.setBackground(shape(BG, BORDER)); field.setSaveEnabled(false);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (name ? InputType.TYPE_TEXT_VARIATION_PERSON_NAME : InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS));
        field.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING); return field;
    }
    private void showError(String message) { error.setText(message); error.setVisibility(View.VISIBLE); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view;
    }
    private void add(LinearLayout parent, String value, int size, int color, boolean bold, int margin) { parent.addView(text(value, size, color, bold), spacing(margin)); }
    private Button button(String title, boolean primary, View.OnClickListener listener) {
        Button view = new Button(this); view.setText(title); view.setAllCaps(false); view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); view.setTextColor(primary ? Color.WHITE : BLUE);
        view.setPadding(dp(10), dp(8), dp(10), dp(8)); view.setMinHeight(dp(50));
        view.setBackground(shape(primary ? BLUE : Color.WHITE, primary ? BLUE : Color.WHITE));
        view.setStateListAnimator(null); view.setOnClickListener(listener); return view;
    }
    private Button link(String title, View.OnClickListener listener) {
        Button view = button(title, false, listener); view.setBackgroundColor(Color.TRANSPARENT); view.setMinHeight(dp(44)); return view;
    }
    private GradientDrawable shape(int fill, int stroke) { GradientDrawable value = new GradientDrawable(); value.setColor(fill); value.setCornerRadius(dp(12)); value.setStroke(dp(1), stroke); return value; }
    private LinearLayout.LayoutParams spacing(int top) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(top); return p; }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
