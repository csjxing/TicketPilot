package cn.local.tickethelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.local.tickethelper.cloud.ApkUpdateVerifier;
import cn.local.tickethelper.cloud.CloudRelease;
import cn.local.tickethelper.cloud.GitHubUpdates;
import cn.local.tickethelper.sync.DiagnosticReport;
import cn.local.tickethelper.sync.SyncSecretsStore;
import java.io.File;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A single place to receive builds and send private diagnostic facts from the phone. */
public final class CloudActivity extends Activity {
    private static final int EXPORT = 72;
    private static final int INK = Color.rgb(24,33,58), MUTED = Color.rgb(102,113,138), BLUE = Color.rgb(61,90,254);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout body;
    private CloudDiagnostics diagnostics;
    private CloudRelease release;
    private File downloaded;
    private boolean busy, preview = true, destroyed;
    private String updateStatus = "点击检查，获取已发布的安装包。", notice = "";
    private GitHubUpdates.Cancellation updateCancellation;
    private DiagnosticReport export;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); diagnostics = CloudDiagnostics.get(this); render();
    }
    @Override public void onResume() { super.onResume(); if (body != null && !busy) render(); }
    @Override public void onDestroy() {
        destroyed = true; if (updateCancellation != null) updateCancellation.cancel();
        main.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy();
    }
    private void render() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        body = column(); body.setPadding(dp(24), dp(24), dp(24), dp(32)); scroll.addView(body);
        scroll.setBackgroundColor(Color.rgb(247,248,252)); setContentView(scroll);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        getWindow().getDecorView().requestApplyInsets();
        button(body, "‹ 返回", false, this::finish);
        text(body, "云端协作", 30, INK, true);
        text(body, "手机接收更新，电脑接收诊断。离开电脑也能继续改进。", 15, MUTED, false);
        if (!notice.isEmpty()) text(body, notice, 14, BLUE, true);

        LinearLayout relay = card(); text(relay, "文件中转站 · 远程诊断", 20, INK, true);
        text(relay, "把脱敏诊断发给电脑，从同一个中转站下载新版 APK。首次连接一次即可。", 15, MUTED, false);
        button(relay, "打开远程诊断", true, () -> startActivity(new Intent(this, RelayActivity.class)));

        LinearLayout update = card(); text(update, "应用更新", 20, INK, true);
        text(update, "当前版本 " + ownVersion(), 14, MUTED, false);
        text(update, updateStatus, 15, INK, false);
        CheckBox previews = new CheckBox(this); previews.setText("接收开发预览版"); previews.setTextSize(14);
        previews.setChecked(preview); previews.setEnabled(!busy);
        previews.setOnCheckedChangeListener((view, checked) -> { preview = checked; release = null; downloaded = null;
            updateStatus = "更新频道已切换，请重新检查。"; render(); }); update.addView(previews);
        button(update, busy ? "处理中…" : "检查更新", false, this::checkUpdate);
        if (release != null) {
            text(update, release.versionName + " · " + String.format(java.util.Locale.ROOT, "%.1f MB", release.apkSize / 1048576.0), 17, INK, true);
            if (!release.notes.isEmpty()) text(update, release.notes, 14, MUTED, false);
            button(update, downloaded == null ? "下载并更新" : "继续安装更新", true,
                    downloaded == null ? this::downloadUpdate : this::installUpdate);
        }
        text(update, "来自 GitHub · 同签名覆盖安装，保留本机资料。系统会要求你确认安装。", 12, MUTED, false);
        button(update, "打开版本下载页 ↗", false, () -> openUrl("https://github.com/csjxing/TicketPilot/releases"));

        LinearLayout sync = card(); text(sync, "独立诊断服务器（高级）", 20, INK, true);
        SyncSecretsStore.Connection connection = connection();
        text(sync, connection == null ? "尚未连接私有诊断云端" : connection.expired(System.currentTimeMillis()) ? "云端连接已过期，请重新配对" : "已连接私有云端", 15, INK, true);
        if (connection != null) text(sync, connection.endpoint.baseUrl, 12, MUTED, false);
        text(sync, diagnostics.status(), 14, MUTED, false);
        if (diagnostics.lastSuccessAtMs() > 0) text(sync, "最近云端回执 " + java.time.Instant.ofEpochMilli(diagnostics.lastSuccessAtMs())
                .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")), 12, MUTED, false);
        text(sync, "仅发送版本、权限、运行状态和固定失败码。不会发送实名、订单正文、密钥、截图或原始页面。", 13, MUTED, false);
        button(sync, "查看诊断内容", false, this::previewReport);
        if (connection == null || connection.expired(System.currentTimeMillis())) button(sync, "连接云端", true, this::pairDialog);
        else {
            button(sync, "立即上传诊断", true, this::upload);
            CheckBox automatic = new CheckBox(this); automatic.setText("状态变化时自动同步诊断"); automatic.setTextSize(14);
            automatic.setChecked(connection.autoUpload); automatic.setEnabled(!busy);
            automatic.setOnCheckedChangeListener((view, checked) -> execute("正在保存同步设置…", () -> {
                diagnostics.setAutoUpload(checked); return checked ? "自动同步已开启。状态变化会合并上报，不占用每轮抢票请求。" : "自动同步已关闭。";
            })); sync.addView(automatic);
            button(sync, "更换或断开云端", false, () -> new AlertDialog.Builder(this).setTitle("断开诊断云端？")
                    .setMessage("停止本机自动同步并移除本机连接。云端已有记录由服务器保留策略清理；不会删除订单防重记录。")
                    .setNegativeButton("取消", null).setPositiveButton("断开", (d, w) -> execute("正在断开…", () -> { diagnostics.disconnect(); return "云端已断开。"; })).show());
        }
        button(sync, "保存诊断文件", false, this::exportReport);
        button(sync, "处理积压的诊断", false, () -> new AlertDialog.Builder(this).setTitle("清理本机待发送诊断？")
                .setMessage("遇到诊断过期或序号冲突时，可仅清理这份诊断队列。不会清理购票配置、观演人、密钥或订单防重记录。清理前可先保存诊断文件。")
                .setNegativeButton("保留", null).setPositiveButton("清理诊断队列", (d, w) -> execute("正在清理诊断队列…", () -> {
                    diagnostics.clearPendingReports(); return "待发送诊断已清理，可重新上传当前状态。";
                })).show());
        text(body, "云端不能替你完成手机解锁、系统授权或验证码。更新后需要你在手机检查权限与大麦页面。", 12, MUTED, false);
    }
    private SyncSecretsStore.Connection connection() {
        try { return diagnostics.connection(); } catch (Exception error) { return null; }
    }
    private void checkUpdate() {
        execute("正在检查 GitHub 更新…", () -> {
            updateCancellation = new GitHubUpdates.Cancellation();
            GitHubUpdates.Check checked = new GitHubUpdates().check(preview, (int)getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(), updateCancellation);
            release = checked.status == GitHubUpdates.Status.UPDATE_AVAILABLE ? checked.release : null; downloaded = null;
            updateStatus = checked.status == GitHubUpdates.Status.UPDATE_AVAILABLE ? "发现可安装的新版。"
                    : checked.status == GitHubUpdates.Status.UP_TO_DATE ? "当前版本已是最新。" : "尚未发布可用更新。";
            return updateStatus;
        });
    }
    private void downloadUpdate() {
        if (release == null) return;
        CloudRelease selected = release;
        execute("正在下载并核对更新…", () -> {
            updateCancellation = new GitHubUpdates.Cancellation();
            File apk = new GitHubUpdates().download(selected, getCacheDir(), updateCancellation);
            ApkUpdateVerifier.Result verified = ApkUpdateVerifier.verify(this, apk, selected);
            if (!verified.allowed) { downloaded = null; return verified.message; }
            downloaded = apk; return "下载与签名核对完成。点击“继续安装更新”。";
        });
    }
    private void installUpdate() {
        if (release == null || downloaded == null) return;
        MonitorService service = MonitorService.instance;
        if (service != null) service.pause("正在更新票务助手，监控已暂停；安装后请主动重新开始。");
        new AppStore(this).setArmed(false);
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("允许安装 TicketPilot 更新")
                    .setMessage("接下来在系统页面允许票务助手安装应用，返回后再点“继续安装更新”。")
                    .setNegativeButton("取消", null).setPositiveButton("打开设置", (d, w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                        catch (RuntimeException error) { notice = "请在系统设置允许票务助手安装应用，然后返回。"; render(); }
                    }).show(); return;
        }
        CloudRelease selected = release; File apk = downloaded;
        busy = true; notice = "正在重新核对安装包…"; render();
        worker.execute(() -> {
            ApkUpdateVerifier.Result result = ApkUpdateVerifier.verify(this, apk, selected);
            main.post(() -> {
                if (destroyed) return; busy = false;
                if (!result.allowed) { notice = result.message; render(); return; }
                try {
                    Uri uri = UpdateApkProvider.uri(this, apk);
                    Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    install.setClipData(android.content.ClipData.newRawUri("TicketPilot update", uri));
                    startActivity(install); notice = "已打开系统安装器，请在手机确认。是否安装成功以返回后的版本号为准。";
                } catch (Exception error) { notice = "无法打开系统安装器；可在版本下载页下载安装。"; }
                render();
            });
        });
    }
    private void pairDialog() {
        LinearLayout form = column(); form.setPadding(dp(24), dp(4), dp(24), dp(8));
        text(form, "从私有云端获取一次性配对码。更换地址必须重新配对。", 14, MUTED, false);
        EditText endpoint = input(form, "https://你的诊断服务地址", false);
        EditText code = input(form, "一次性配对码", true);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接私有云端").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("连接", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String address = endpoint.getText().toString().trim(), pairing = code.getText().toString().trim();
            try { new cn.local.tickethelper.sync.SyncEndpoint(address); }
            catch (IllegalArgumentException error) { endpoint.setError("请输入有效的 HTTPS 地址"); return; }
            if (!pairing.matches("[A-Za-z0-9_-]{22}")) { code.setError("请输入完整的一次性配对码"); return; }
            code.setText(""); dialog.dismiss();
            execute("正在配对私有云端…", () -> { diagnostics.pair(address, pairing); return "云端已连接。可上传当前诊断，或自行开启自动同步。"; });
        })); dialog.show();
    }
    private void upload() { execute("正在上传诊断…", () -> { int count = diagnostics.uploadManual(); return "云端已确认接收 " + count + " 份诊断。"; }); }
    private void previewReport() {
        execute("正在生成诊断预览…", () -> {
            DiagnosticReport report = diagnostics.captureManual();
            main.post(() -> { if (!destroyed) {
                TextView text = new TextView(this); text.setText(report.toJson().toString()); text.setTextIsSelectable(true); text.setTextSize(12); text.setPadding(dp(20),dp(12),dp(20),dp(12));
                ScrollView scroll = new ScrollView(this); scroll.addView(text);
                new AlertDialog.Builder(this).setTitle("将同步的诊断内容").setView(scroll).setPositiveButton("知道了", null).show();
            }}); return "诊断内容仅在本机预览，尚未上传。";
        });
    }
    private void exportReport() {
        execute("正在准备诊断文件…", () -> {
            export = diagnostics.captureManual();
            main.post(() -> { if (!destroyed) startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "ticketpilot-diagnostic.json"), EXPORT); });
            return "选择诊断文件的保存位置。";
        });
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != EXPORT || result != RESULT_OK || data == null || data.getData() == null || export == null) return;
        Uri destination = data.getData(); DiagnosticReport report = export; export = null;
        execute("正在保存诊断…", () -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new java.io.IOException(); out.write(report.bytes()); out.flush();
            } return "诊断文件已保存。";
        });
    }
    private interface Work { String run() throws Exception; }
    private void execute(String message, Work work) {
        if (busy || destroyed) return; busy = true; notice = message; render();
        worker.execute(() -> {
            String result;
            try { result = work.run(); }
            catch (Exception error) {
                result = error instanceof GitHubUpdates.Failure || error instanceof cn.local.tickethelper.sync.DiagnosticSyncClient.Failure
                        ? error.getMessage() : "本次操作未完成。请检查网络、云端连接或稍后重试；原有配置保留。";
            }
            String text = result;
            main.post(() -> { if (!destroyed) { busy = false; notice = text; render(); } });
        });
    }
    private String ownVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception error) { return "读取失败"; }
    }
    private void openUrl(String url) { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException error) { notice = "没有可用浏览器，请稍后重试。"; render(); } }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout card() {
        LinearLayout card = column(); card.setPadding(dp(18),dp(14),dp(18),dp(18));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.WHITE); bg.setCornerRadius(dp(18)); card.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(22); body.addView(card,p); return card;
    }
    private void text(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(dp(3),1);
        if (bold) v.setTypeface(null,Typeface.BOLD); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(10); parent.addView(v,p);
    }
    private void button(LinearLayout parent, String label, boolean primary, Runnable click) {
        Button b = new Button(this); b.setText(label); b.setTextSize(15); b.setAllCaps(false); b.setMinHeight(dp(48)); b.setEnabled(!busy);
        b.setTextColor(primary?Color.WHITE:BLUE); GradientDrawable bg=new GradientDrawable(); bg.setColor(primary?BLUE:Color.rgb(238,241,255)); bg.setCornerRadius(dp(12)); b.setBackground(bg);
        b.setOnClickListener(v -> click.run()); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(12); parent.addView(b,p);
    }
    private EditText input(LinearLayout parent, String hint, boolean secret) {
        EditText field=new EditText(this); field.setHint(hint); field.setSingleLine(true); field.setTextSize(15);
        field.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        field.setSaveEnabled(false); parent.addView(field,new LinearLayout.LayoutParams(-1,dp(56))); return field;
    }
    private int dp(float n) { return Math.round(n*getResources().getDisplayMetrics().density); }
}
