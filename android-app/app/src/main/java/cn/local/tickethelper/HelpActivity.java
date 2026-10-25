package cn.local.tickethelper;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.local.tickethelper.knowledge.KnowledgeMarkdown;
import cn.local.tickethelper.knowledge.KnowledgeRepository;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Offline, read-only help. Opening an article cannot start monitoring or change a setting. */
public final class HelpActivity extends Activity {
    public static final String EXTRA_TOPIC = "topic";
    private static final int BG = Color.rgb(245, 246, 248), CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(23, 38, 53), MUTED = Color.rgb(88, 101, 117);
    private static final int ACCENT = Color.rgb(23, 107, 91), BORDER = Color.rgb(222, 228, 231);
    private KnowledgeRepository knowledge;
    private LinearLayout page, list;
    private ScrollView scroll;
    private TextView resultLabel;
    private String query = "", category = "全部", topic = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (state != null) {
            query = state.getString("query", ""); category = state.getString("category", "全部");
            topic = state.getString("topic", "");
        } else {
            String requested = getIntent().getStringExtra(EXTRA_TOPIC); topic = requested == null ? "" : requested;
        }
        try { knowledge = KnowledgeRepository.load(path -> getAssets().open(path)); }
        catch (IOException | RuntimeException e) { showUnavailable(); return; }
        KnowledgeRepository.Article article = knowledge.find(topic);
        if (article == null) showDirectory(); else showArticle(article);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", query); state.putString("category", category); state.putString("topic", topic);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (!topic.isEmpty() && knowledge != null) { showDirectory(); return; }
        super.onBackPressed();
    }

    private void shell(String backLabel, View.OnClickListener back) {
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        page = column(); page.setPadding(dp(20), dp(12), dp(20), dp(32)); scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll);
        Button backButton = button(backLabel, false, back);
        page.addView(backButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void showDirectory() {
        topic = "";
        shell("‹ 返回助手", v -> finish());
        page.addView(text("使用指南", 28, TEXT, true), spaced(18));
        page.addView(text("在手机里就能查，换机后也能接着看。", 16, MUTED, false), spaced(6));
        page.addView(text("离线资料 · " + knowledge.updatedOn + " 更新 · v" + knowledge.edition, 12, MUTED, false), spaced(10));
        EditText search = new EditText(this);
        search.setSingleLine(true); search.setTextColor(TEXT); search.setHintTextColor(MUTED);
        search.setTextSize(16); search.setHint("搜索问题，例如：换手机、没反应");
        search.setContentDescription("搜索使用指南"); search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        search.setBackground(background(CARD, BORDER)); search.setPadding(dp(14), dp(12), dp(14), dp(12));
        search.setMinHeight(dp(52)); search.setText(query);
        page.addView(search, spaced(22));
        HorizontalScrollView categories = new HorizontalScrollView(this); categories.setHorizontalScrollBarEnabled(false);
        LinearLayout categoryRow = new LinearLayout(this); categoryRow.setOrientation(LinearLayout.HORIZONTAL);
        categories.addView(categoryRow); page.addView(categories, spaced(10));
        Set<String> names = new LinkedHashSet<>(); names.add("全部");
        for (KnowledgeRepository.Article article : knowledge.articles()) names.add(article.category);
        if (!names.contains(category)) category = "全部";
        for (String name : names) {
            Button chip = button(name, name.equals(category), v -> {
                category = name;
                for (int i = 0; i < categoryRow.getChildCount(); i++) {
                    Button child = (Button) categoryRow.getChildAt(i); boolean selected = name.contentEquals(child.getText());
                    styleButton(child, selected); child.setSelected(selected);
                }
                renderResults();
            });
            chip.setSelected(name.equals(category));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8)); categoryRow.addView(chip, params);
        }
        resultLabel = text("", 13, MUTED, false); page.addView(resultLabel, spaced(18));
        list = column(); page.addView(list, spaced(4));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString(); renderResults(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        renderResults();
    }

    private void renderResults() {
        if (list == null) return;
        list.removeAllViews(); List<KnowledgeRepository.Article> matches = knowledge.search(query); int count = 0;
        for (KnowledgeRepository.Article article : matches) {
            if (!category.equals("全部") && !category.equals(article.category)) continue;
            count++;
            LinearLayout card = card();
            card.addView(text(article.category, 12, ACCENT, true));
            card.addView(text(article.title, 18, TEXT, true), spaced(8));
            card.addView(text(article.summary, 15, MUTED, false), spaced(6));
            Button open = button("阅读说明  ›", false, v -> openArticle(article));
            open.setContentDescription("阅读：" + article.title); card.addView(open, spaced(12));
            list.addView(card, spaced(12));
        }
        resultLabel.setText(query.trim().isEmpty() ? count + " 篇说明" : "找到 " + count + " 篇相关说明");
        if (count == 0) {
            LinearLayout empty = card();
            empty.addView(text("暂时没有匹配的说明", 18, TEXT, true));
            empty.addView(text("试试简短词语，例如“权限”“换机”或“排队”；也可以切到“全部”分类。", 16, MUTED, false), spaced(8));
            list.addView(empty, spaced(12));
        }
    }

    private void openArticle(KnowledgeRepository.Article article) {
        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus(); if (keyboard != null && focus != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        showArticle(article);
    }

    private void showArticle(KnowledgeRepository.Article article) {
        topic = article.id;
        shell("‹ 全部说明", v -> showDirectory());
        page.addView(text(article.category, 13, ACCENT, true), spaced(20));
        page.addView(text(article.title, 26, TEXT, true), spaced(8));
        page.addView(text(article.summary, 16, MUTED, false), spaced(12));
        page.addView(text("离线资料 · " + knowledge.updatedOn + " 更新", 12, MUTED, false), spaced(12));
        for (KnowledgeMarkdown.Block block : KnowledgeMarkdown.blocks(article.body)) {
            boolean heading = block.kind == KnowledgeMarkdown.Kind.HEADING || block.kind == KnowledgeMarkdown.Kind.SUBHEADING;
            TextView view = text(block.text, block.kind == KnowledgeMarkdown.Kind.HEADING ? 21
                    : block.kind == KnowledgeMarkdown.Kind.SUBHEADING ? 18 : block.kind == KnowledgeMarkdown.Kind.CODE ? 14 : 16,
                    TEXT, heading);
            view.setTextIsSelectable(true);
            if (heading) view.setAccessibilityHeading(true);
            if (block.kind == KnowledgeMarkdown.Kind.CODE) {
                view.setTypeface(Typeface.MONOSPACE); view.setBackground(background(CARD, BORDER));
                view.setPadding(dp(14), dp(14), dp(14), dp(14));
            }
            page.addView(view, spaced(heading ? 26 : block.kind == KnowledgeMarkdown.Kind.BULLET ? 10 : 14));
        }
        page.addView(text("继续了解", 18, TEXT, true), spaced(30));
        int related = 0;
        for (KnowledgeRepository.Article other : knowledge.articles()) {
            if (other.id.equals(article.id) || !other.category.equals(article.category)) continue;
            page.addView(button(other.title + "  ›", false, v -> openArticle(other)), spaced(10));
            if (++related >= 2) break;
        }
        if (related == 0 && !article.id.equals("capabilities")) {
            KnowledgeRepository.Article capability = knowledge.find("capabilities");
            if (capability != null) page.addView(button(capability.title + "  ›", false, v -> openArticle(capability)), spaced(10));
        }
        page.addView(button("回到说明目录", false, v -> showDirectory()), spaced(14));
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void showUnavailable() {
        topic = ""; shell("‹ 返回助手", v -> finish());
        page.addView(text("内置资料暂时无法打开", 24, TEXT, true), spaced(24));
        page.addView(text("请重新打开助手。如果仍然失败，请使用完整安装包更新应用。这个问题不会启动监控或改变你的目标。", 16, MUTED, false), spaced(14));
    }

    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout card() { LinearLayout result = column(); result.setPadding(dp(18), dp(18), dp(18), dp(18)); result.setBackground(background(CARD, BORDER)); return result; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(this); result.setText(value); result.setTextColor(color); result.setTextSize(size);
        result.setLineSpacing(dp(2), 1.15f); if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return result;
    }
    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button result = new Button(this); result.setText(label); result.setAllCaps(false); result.setTextSize(15);
        result.setMinHeight(dp(48)); result.setMinWidth(0); result.setPadding(dp(14), dp(10), dp(14), dp(10));
        result.setStateListAnimator(null); styleButton(result, primary); result.setOnClickListener(action); return result;
    }
    private void styleButton(Button button, boolean primary) { button.setTextColor(primary ? CARD : ACCENT); button.setBackground(background(primary ? ACCENT : CARD, primary ? ACCENT : BORDER)); }
    private GradientDrawable background(int fill, int stroke) { GradientDrawable result = new GradientDrawable(); result.setColor(fill); result.setCornerRadius(dp(14)); result.setStroke(dp(1), stroke); return result; }
    private LinearLayout.LayoutParams spaced(int top) { LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); result.topMargin = dp(top); return result; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
