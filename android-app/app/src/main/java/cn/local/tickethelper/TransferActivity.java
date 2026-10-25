package cn.local.tickethelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import cn.local.tickethelper.core.MonitorConfig;
import cn.local.tickethelper.knowledge.KnowledgeRepository;
import cn.local.tickethelper.transfer.TransferBundle;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SAF-only portable documents. Import always previews first and never grants monitoring or input. */
public final class TransferActivity extends Activity {
    private static final int BG = Color.rgb(245, 246, 248), INK = Color.rgb(23, 38, 53);
    private static final int MUTED = Color.rgb(88, 101, 117), GREEN = Color.rgb(23, 107, 91);
    private static final int EXPORT_BUNDLE = 71, IMPORT_BUNDLE = 72, EXPORT_APK = 73, EXPORT_SOURCE = 74;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Button> fileButtons = new ArrayList<>();
    private TextView status;
    private LinearLayout previewCard;
    private TransferBundle.Preview pending;
    private MonitorConfig exportConfig;
    private boolean working;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        LinearLayout page = column(); page.setPadding(dp(20), dp(16), dp(20), dp(30)); scroll.addView(page); setContentView(scroll);
        Button back = button("‹ 返回", false, v -> finish()); page.addView(back, gap(0));
        add(page, "电脑接手与备份", 28, INK, true, 18);
        add(page, "手机负责日常运行，电脑负责排查和增强。", 15, MUTED, false, 8);
        status = add(page, "换电脑时连接同一台 vivo 即可，不需要向手机重新导入配置。", 13, MUTED, false, 12);

        LinearLayout source = card(page);
        add(source, "1  让另一台电脑快速接手", 19, INK, true, 0);
        add(source, "导出后解压，用 Codex 或其他模型工具打开工程文件夹。先读接手说明，再检查环境；源码、离线知识和测试都在包里。", 14, MUTED, false, 10);
        fileButton(source, "导出电脑交接包（ZIP）", true, v -> document(Intent.ACTION_CREATE_DOCUMENT,
                "application/zip", "票务助手-电脑交接-" + version() + ".zip", EXPORT_SOURCE));
        source.addView(button("复制给模型的接手说明", false, v -> copyComputerPrompt()), gap(10));
        add(source, "不包含账号、实名、密钥和原始运行记录。换电脑后的覆盖安装需要相同签名；不要为解决签名冲突卸载助手。", 13, MUTED, false, 10);

        LinearLayout local = card(page);
        add(local, "2  日常直接在这台手机用", 19, INK, true, 0);
        add(local, "在手机完成设置、选演出、开始和暂停。Shizuku 重启后需重新启动；完整拔线选票仍待实测。", 14, MUTED, false, 10);
        local.addView(button("查看单机使用步骤", false, v -> startActivity(new Intent(this, HelpActivity.class)
                .putExtra("topic", "move-phone"))), gap(10));

        LinearLayout apk = card(page);
        add(apk, "留存当前安装包", 19, INK, true, 0);
        add(apk, "保存这台手机当前安装的版本，方便交接和核对。模型增强后仍需构建并在这台手机验证。", 14, MUTED, false, 10);
        fileButton(apk, "导出当前安装包（APK）", false, v -> selectApkExport());

        LinearLayout backup = card(page);
        add(backup, "配置与资料备份（可选）", 19, INK, true, 0);
        add(backup, "备份已保存的目标、节奏和资料；尚未保存的编辑不会进入备份。换电脑接手不需要恢复备份。", 14, MUTED, false, 10);
        fileButton(backup, "导出资料与目标备份", false, v -> selectExport());
        add(backup, "只有需要恢复配置时才使用下面的入口。恢复先预览，保存后需重新核对目标；不恢复账号或授权，也不清除本机已有提交记录。", 13, MUTED, false, 10);
        fileButton(backup, "选择备份并预览", false, v -> selectImport());
        previewCard = card(page); previewCard.setVisibility(View.GONE);
        add(page, "普通检查记录在“使用与维护 → 检查记录”单独导出。资料只在本机处理，导入不会替换或执行代码。", 13, MUTED, false, 16);
    }

    private void copyComputerPrompt() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) { message("暂时无法复制", "请打开内置开发交接说明查看接手步骤。"); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("票务助手电脑接手", TransferBundle.computerPrompt()));
        Toast.makeText(this, "接手说明已复制，可粘贴给模型工具", Toast.LENGTH_SHORT).show();
    }

    private void selectExport() {
        exportConfig = new AppStore(this).loadConfig();
        document(Intent.ACTION_CREATE_DOCUMENT, "application/zip", "票务助手-资料备份-" + date() + ".zip", EXPORT_BUNDLE);
    }
    private void selectApkExport() {
        if (getApplicationInfo().splitSourceDirs != null && getApplicationInfo().splitSourceDirs.length > 0) {
            message("此安装方式包含多个分包", "请使用该版本的完整安装包。当前页面不会导出一个无法独立安装的分包。"); return;
        }
        document(Intent.ACTION_CREATE_DOCUMENT, "application/vnd.android.package-archive",
                "票务助手-" + version() + ".apk", EXPORT_APK);
    }
    private void selectImport() {
        pending = null; previewCard.removeAllViews(); previewCard.setVisibility(View.GONE);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try { startActivityForResult(Intent.createChooser(intent, "选择备份使用的文件应用"), IMPORT_BUNDLE); }
        catch (RuntimeException e) { message("文件选择器未打开", "请确认手机有可用的系统文件应用，然后重试。"); }
    }
    private void document(String action, String type, String name, int request) {
        Intent intent = new Intent(action).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name);
        try { startActivityForResult(intent, request); }
        catch (RuntimeException e) { message("文件选择器未打开", "请确认手机有可用的系统文件应用，然后重试。"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            if (request == EXPORT_BUNDLE || request == IMPORT_BUNDLE || request == EXPORT_APK || request == EXPORT_SOURCE) status.setText("已取消，没有应用新的配置。");
            return;
        }
        Uri uri = data.getData();
        // External activity output is untrusted; only use the document provider selected by the user.
        if (!"content".equals(uri.getScheme())) { message("文件地址不受支持", "请使用系统文件选择器选择本地文件。"); return; }
        if (working) return;
        // The system document picker can recreate this Activity. Recover the saved public draft
        // rather than silently turning a target-bearing export into a knowledge-only package.
        if (request == EXPORT_BUNDLE && exportConfig == null) exportConfig = new AppStore(this).loadConfig();
        setWorking(true, request == IMPORT_BUNDLE ? "正在读取和校验，尚未应用配置…" : "正在保存，请稍候…");
        worker.execute(() -> {
            try {
                if (request == EXPORT_BUNDLE) exportBundle(uri);
                else if (request == EXPORT_APK) exportApk(uri);
                else if (request == EXPORT_SOURCE) exportSource(uri);
                else if (request == IMPORT_BUNDLE) {
                    TransferBundle.Preview read;
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        if (input == null) throw new IOException("无法读取所选文件"); read = TransferBundle.read(input);
                    }
                    post(() -> { setWorking(false, "校验完成，请核对下面的预览。"); showPreview(read); }); return;
                } else throw new IOException("不支持的文件操作");
                post(() -> setWorking(false, request == EXPORT_APK ? "安装包已保存到你选择的位置。"
                        : request == EXPORT_SOURCE ? "电脑交接包已保存。解压后让模型先读 AGENTS.md 和 handoff/START_HERE.md。"
                        : "资料与目标备份已保存到你选择的位置。"));
            } catch (IOException | RuntimeException failure) {
                post(() -> {
                    setWorking(false, request == IMPORT_BUNDLE ? "未应用导入配置。" : "文件未完整保存，请删除不完整文件后重试。");
                    message("这次操作没有完成", request == IMPORT_BUNDLE ? safeImportReason(failure)
                            : "请检查存储空间与文件权限，再重新选择保存位置。");
                });
            }
        });
    }

    private void exportBundle(Uri uri) throws IOException {
        Map<String, byte[]> knowledge = KnowledgeRepository.load(path -> getAssets().open(path)).exportFiles();
        byte[] source = null;
        // Optional build-generated, allowlisted source snapshot. Never inspect arbitrary private files.
        try (InputStream input = getAssets().open("handoff/source.zip")) {
            source = TransferBundle.readBounded(input, TransferBundle.MAX_SOURCE_BYTES);
        } catch (java.io.FileNotFoundException absent) { /* Older builds can still export settings and knowledge. */ }
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("无法写入文件");
            TransferBundle.write(output, exportConfig, version(), knowledge, source, System.currentTimeMillis());
        }
    }
    private void exportSource(Uri uri) throws IOException {
        // This exact build-generated archive contains only allowlisted project sources.
        byte[] bytes;
        try (InputStream input = getAssets().open("handoff/source.zip")) {
            bytes = TransferBundle.readBounded(input, TransferBundle.MAX_SOURCE_BYTES);
        }
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("无法写入文件");
            output.write(bytes); output.flush();
        }
    }

    private void exportApk(Uri uri) throws IOException {
        try (InputStream input = new FileInputStream(getApplicationInfo().sourceDir);
             OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IOException("无法写入文件");
            byte[] buffer = new byte[32768]; int size;
            while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            output.flush();
        }
    }

    private void showPreview(TransferBundle.Preview preview) {
        pending = preview; previewCard.removeAllViews(); previewCard.setVisibility(View.VISIBLE);
        add(previewCard, "导入预览", 21, INK, true, 0);
        add(previewCard, "来自版本 " + preview.appVersion + " · " + displayDate(preview.createdAt), 13, MUTED, false, 8);
        add(previewCard, "含 " + preview.knowledgeFiles + " 份知识文件" + (preview.includesSource ? "和源码资料" : "")
                + "。资料留在 ZIP 中，可用文件应用阅读；不会覆盖本机内置知识或运行代码。", 13, MUTED, false, 8);
        if (preview.config == null) {
            add(previewCard, "这是资料包，没有需要恢复的演出目标。当前设置保持不变。", 15, INK, true, 14); return;
        }
        MonitorConfig c = preview.config;
        add(previewCard, c.eventKeyword + "\n" + c.city + " · " + c.date + " " + c.time, 18, INK, true, 16);
        add(previewCard, "接受票价 " + c.prices.toString().replace("[", "").replace("]", "") + " 元\n"
                + c.quantity + " 张 · 实付上限 " + c.maxTotal + " 元\n"
                + "检查间隔 " + c.minIntervalMs / 1000 + "–" + c.maxIntervalMs / 1000 + " 秒\n"
                + "模式：" + (c.mode == MonitorConfig.Mode.PREPARE ? "选票后到确认购买页暂停" : "有票时提醒")
                + "\n停止时间：" + c.deadline.atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                15, INK, false, 10);
        if (preview.expired(System.currentTimeMillis())) add(previewCard, "这个目标已经过期，仅供参考。导入后必须重新选择当前演出。", 14, GREEN, true, 12);
        if (preview.modeDowngraded) add(previewCard, "原包的自动下单模式将改为“到确认购买页暂停”，不会导入下单授权。", 14, GREEN, true, 10);
        add(previewCard, "应用后：暂停当前监控、清除本机观演人匹配资料，保存为待核对草稿。权限、输入方式和已有订单提交记录保持本机状态。", 14, MUTED, false, 12);
        Button apply = button("保存为待核对草稿", true, v -> applyPreview(preview)); previewCard.addView(apply, gap(16));
        previewCard.addView(button("取消导入", false, v -> { pending = null; previewCard.setVisibility(View.GONE); status.setText("已取消，当前配置没有改变。"); }), gap(8));
        previewCard.post(() -> previewCard.requestRectangleOnScreen(new android.graphics.Rect(0, 0, previewCard.getWidth(), dp(280)), false));
    }

    private void applyPreview(TransferBundle.Preview preview) {
        if (working || pending != preview || preview.config == null) return;
        // The preview button is the concrete user confirmation. No auto-start after import.
        MonitorService service = MonitorService.instance;
        if (service != null) service.pause("正在恢复目标备份，监控已暂停。");
        AppStore store = new AppStore(this); store.setArmed(false);
        if (getSystemService(android.app.NotificationManager.class) != null) {
            // Clear only our prepared-start notification; imported state can never reuse it.
            getSystemService(android.app.NotificationManager.class).cancel(12);
        }
        boolean saved = store.importDraft(preview.config);
        if (!saved) { message("草稿未能完成保存", "手机存储写入失败，监控保持暂停。请检查存储空间，重新打开助手核对后再试。"); return; }
        store.setStatus("备份目标已保存为草稿。请重新选择演出并完成权限设置，再主动开始。");
        store.appendEvent("PAUSED", "imported_draft_requires_review", 0);
        pending = null; previewCard.removeAllViews();
        add(previewCard, "草稿已保存，尚未开始监控", 19, INK, true, 0);
        add(previewCard, "下一步：回到首页，在真实大麦重新选择这场演出，再检查这台手机的权限与点击方式。", 15, MUTED, false, 10);
        previewCard.addView(button("回首页继续设置", true, v -> {
            startActivity(new Intent(this, MainActivity.class).putExtra("show_task", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish();
        }), gap(14));
        status.setText("导入完成。没有启动监控，也没有创建订单。");
    }

    private void post(Runnable action) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); }); }
    private void setWorking(boolean active, String message) {
        working = active; status.setText(message); for (Button button : fileButtons) button.setEnabled(!active);
    }
    private void message(String title, String body) { new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("知道了", null).show(); }
    private static String safeImportReason(Exception error) {
        // Do not echo third-party provider paths or raw parser exception data into the UI.
        String reason = error.getMessage();
        return error instanceof IOException && reason != null && reason.length() < 80 && reason.matches("[\\p{IsHan}，。；、（） A-ZZIP]+")
                ? reason : "文件损坏、版本不兼容或无法读取。请重新选择票务助手导出的完整资料备份。";
    }
    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "unknown"; }
    }
    private String date() { return java.time.LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(); }
    private String displayDate(String iso) {
        return java.time.Instant.parse(iso).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card(LinearLayout page) {
        LinearLayout c = column(); c.setPadding(dp(18), dp(18), dp(18), dp(18)); c.setBackground(shape(Color.WHITE, 20)); page.addView(c, gap(16)); return c;
    }
    private TextView add(LinearLayout parent, String value, int size, int color, boolean bold, int margin) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(dp(3), 1.08f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); parent.addView(t, gap(margin)); return t;
    }
    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button b = new Button(this); b.setText(label); b.setTextSize(15); b.setAllCaps(false); b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setMinHeight(dp(52)); b.setPadding(dp(12), dp(8), dp(12), dp(8)); b.setBackground(shape(primary ? GREEN : Color.rgb(234, 243, 240), 12));
        b.setOnClickListener(click); return b;
    }
    private void fileButton(LinearLayout parent, String label, boolean primary, View.OnClickListener click) {
        Button b = button(label, primary, click); fileButtons.add(b); parent.addView(b, gap(14));
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout.LayoutParams gap(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(margin); return p;
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
