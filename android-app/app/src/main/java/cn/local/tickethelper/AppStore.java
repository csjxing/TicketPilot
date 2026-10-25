package cn.local.tickethelper;

import android.content.Context;
import android.content.SharedPreferences;
import cn.local.tickethelper.core.MonitorConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Private, bounded local configuration and audit storage. Never stores screenshots. */
public final class AppStore {
    public static final String INPUT_ACCESSIBILITY = "ACCESSIBILITY";
    public static final String INPUT_SHIZUKU = "SHIZUKU";
    private static final int INPUT_MODE_VERSION = 1;
    private static final int CONFIG_VERSION = 2;
    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 2000;
    private static final int COMPACT_AT = 2200;
    private final SharedPreferences prefs;
    private final SharedPreferences catalogDisplay;
    private final File history;

    public AppStore(Context context) {
        Context app = context.getApplicationContext();
        prefs = app.getSharedPreferences("ticket_helper", Context.MODE_PRIVATE);
        catalogDisplay = app.getSharedPreferences("catalog_target_display", Context.MODE_PRIVATE);
        history = new File(app.getFilesDir(), "poll-history.jsonl");
        synchronized (LOCK) {
            if (prefs.getInt("config_version", 0) != CONFIG_VERSION) {
                String oldMode = prefs.getString("mode", "ALERT");
                // An upgrade preserves earlier reminder/prepare preferences, never a purchase grant.
                prefs.edit().putInt("config_version", CONFIG_VERSION)
                        .putString("mode", "PREPARE".equals(oldMode) ? "PREPARE" : "ALERT")
                        .putBoolean("armed", false)
                        .putLong("config_revision", prefs.getLong("config_revision", 0) + 1).apply();
            }
        }
    }

    public MonitorConfig loadConfig() {
        List<Integer> prices = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("prices", "[]"));
            for (int i = 0; i < array.length(); i++) prices.add(array.getInt(i));
        } catch (Exception ignored) { prices.clear(); }
        Instant deadline = null;
        try { deadline = Instant.parse(prefs.getString("deadline", "")); }
        catch (Exception ignored) { /* Blank is deliberately not a valid monitoring deadline. */ }
        MonitorConfig.Mode mode;
        try { mode = MonitorConfig.Mode.valueOf(prefs.getString("mode", "ALERT")); }
        catch (Exception ignored) { mode = MonitorConfig.Mode.ALERT; }
        return new MonitorConfig(prefs.getString("event", ""), prefs.getString("city", ""),
                prefs.getString("date", ""), prefs.getString("time", ""), prices,
                prefs.getInt("quantity", 1), prefs.getInt("maxTotal", 0), deadline,
                prefs.getLong("minIntervalMs", 5000L), prefs.getLong("maxIntervalMs", 10000L), mode);
    }

    public void saveConfig(MonitorConfig config) {
        JSONArray prices = new JSONArray();
        for (Integer price : config.prices) prices.put(price);
        synchronized (LOCK) { prefs.edit().putString("event", config.eventKeyword).putString("city", config.city)
                .putString("date", config.date).putString("time", config.time)
                .putString("prices", prices.toString()).putInt("quantity", config.quantity)
                .putInt("maxTotal", config.maxTotal)
                .putString("deadline", config.deadline == null ? "" : config.deadline.toString())
                .putLong("minIntervalMs", config.minIntervalMs).putLong("maxIntervalMs", config.maxIntervalMs)
                .putString("mode", config.mode.name()).putBoolean("armed", false)
                .putLong("config_revision", prefs.getLong("config_revision", 0) + 1).apply(); }
    }

    public long getConfigRevision() { return prefs.getLong("config_revision", 0); }
    /** Imported targets remain drafts until selected again from the real app on this phone. */
    public boolean isImportedTargetPendingReview() { return prefs.getBoolean("imported_target_pending_review", false); }
    /** Only call after applying a freshly read Catalog target, never after merely editing fields. */
    public void markImportedTargetReviewed() {
        synchronized (LOCK) {
            prefs.edit().putBoolean("imported_target_pending_review", false).apply();
        }
    }

    /**
     * One preference transaction for an explicitly accepted public draft. The caller pauses the live
     * service first. No device input setting, permissions, history or order journal is imported.
     */
    public boolean importDraft(MonitorConfig config) {
        if (config == null || !config.validate().isEmpty() || config.mode == MonitorConfig.Mode.LOCK_ORDER)
            throw new IllegalArgumentException("Invalid imported public draft");
        JSONArray prices = new JSONArray(); for (Integer price : config.prices) prices.put(price);
        synchronized (LOCK) {
            boolean saved = prefs.edit().putString("event", config.eventKeyword).putString("city", config.city)
                    .putString("date", config.date).putString("time", config.time)
                    .putString("prices", prices.toString()).putInt("quantity", config.quantity)
                    .putInt("maxTotal", config.maxTotal).putString("deadline", config.deadline.toString())
                    .putLong("minIntervalMs", config.minIntervalMs).putLong("maxIntervalMs", config.maxIntervalMs)
                    .putString("mode", config.mode.name()).putBoolean("armed", false)
                    .putString("viewer_display_name", "").putString("viewer_document_suffix", "")
                    .putBoolean("imported_target_pending_review", true)
                    .putLong("config_revision", prefs.getLong("config_revision", 0) + 1).commit();
            if (saved) {
                // The durable pending-review flag already blocks starts. Drop earlier read evidence
                // too, so an open Catalog page cannot reapply an old snapshot after this import.
                catalogDisplay.edit().clear().apply();
                CatalogStore.update("IDLE", "换机目标已作为草稿保存，请在大麦重新选择核对。", "", null, null,
                        List.of(), List.of(), List.of(), 0);
            }
            return saved;
        }
    }
    /** Local matching data only: never pass these values to status, history, notifications or export. */
    public String getViewerDisplayName() { return prefs.getString("viewer_display_name", ""); }
    public String getViewerDocumentSuffix() { return prefs.getString("viewer_document_suffix", ""); }
    public void saveViewerBinding(String displayName, String documentSuffix) {
        String name = displayName == null ? "" : displayName.trim();
        String suffix = documentSuffix == null ? "" : documentSuffix.trim().toUpperCase(Locale.ROOT);
        if (name.length() > 80 || name.chars().anyMatch(Character::isISOControl)
                || java.util.regex.Pattern.compile("\\d{8,}").matcher(name).find()
                || (!suffix.isEmpty() && !suffix.matches("[A-Z0-9]{2,6}")))
            throw new IllegalArgumentException("Invalid local viewer binding");
        synchronized (LOCK) {
            if (name.equals(getViewerDisplayName()) && suffix.equals(getViewerDocumentSuffix())) return;
            prefs.edit().putString("viewer_display_name", name).putString("viewer_document_suffix", suffix)
                    .putBoolean("armed", false)
                    .putLong("config_revision", prefs.getLong("config_revision", 0) + 1).apply();
        }
    }

    public void setStatus(String status) { prefs.edit().putString("status", clean(status, 240)).apply(); }
    public String getStatus() { return prefs.getString("status", "尚未启动；请配置目标演出并开启无障碍服务。"); }
    public boolean isArmed() { return prefs.getBoolean("armed", false); }
    public void setArmed(boolean armed) { prefs.edit().putBoolean("armed", armed).apply(); }
    /** Old installs and unrecognized settings always use the default accessibility input. */
    public String getInputMode() {
        if (prefs.getInt("input_mode_version", 0) != INPUT_MODE_VERSION) return INPUT_ACCESSIBILITY;
        String mode = prefs.getString("input_mode", INPUT_ACCESSIBILITY);
        return INPUT_SHIZUKU.equals(mode) ? INPUT_SHIZUKU : INPUT_ACCESSIBILITY;
    }

    /** The caller must pause the live service before changing its input route. */
    public void setInputMode(String mode) {
        if (!INPUT_ACCESSIBILITY.equals(mode) && !INPUT_SHIZUKU.equals(mode)) {
            throw new IllegalArgumentException("Unsupported input mode");
        }
        prefs.edit().putInt("input_mode_version", INPUT_MODE_VERSION)
                .putString("input_mode", mode).putBoolean("armed", false).apply();
    }
    public int historyCount() { return prefs.getInt("historyCount", 0); }
    public String lastEventSummary() { return prefs.getString("lastEvent", "暂无检查记录"); }

    /** detail must be a generic outcome, never raw OCR text, a name, or an identity number. */
    public void appendEvent(String state, String detail, long elapsedMs) {
        appendRecord(state, detail, elapsedMs, -1, -1, null);
    }

    /** Numeric capture evidence only; OCR count is adapted text items before merging with nodes. */
    public void appendObservation(String state, String detail, long elapsedMs,
                                  int nodeCount, int ocrCount, boolean usedOcr) {
        appendRecord(state, detail, elapsedMs, Math.max(0, nodeCount), Math.max(0, ocrCount),
                usedOcr ? "nodes_ocr" : "nodes");
    }

    private void appendRecord(String state, String detail, long elapsedMs,
                              int nodeCount, int ocrCount, String source) {
        synchronized (LOCK) {
            try {
                String timestamp = Instant.now().toString();
                JSONObject event = new JSONObject();
                event.put("timestamp", timestamp);
                event.put("state", clean(state, 80));
                event.put("detail", clean(detail, 180));
                event.put("elapsed_ms", Math.max(0, elapsedMs));
                if (source != null) {
                    event.put("node_count", nodeCount);
                    event.put("ocr_count", ocrCount);
                    event.put("source", source);
                }
                int count = history.exists() ? prefs.getInt("historyCount", 0) : 0;
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(history, true), StandardCharsets.UTF_8))) {
                    writer.write(event.toString());
                    writer.newLine();
                }
                count++;
                if (count >= COMPACT_AT || history.length() > 1_048_576L) count = compactHistory();
                prefs.edit().putInt("historyCount", count)
                        .putString("lastEvent", stateLabel(state) + " · " + Math.max(0, elapsedMs) + " ms\n最近检查 " + Instant.parse(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")))
                        .apply();
            } catch (Exception e) {
                setStatus("本地记录写入失败，请检查手机存储空间。");
            }
        }
    }

    private int compactHistory() throws Exception {
        ArrayDeque<String> lines = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(history), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= MAX_EVENTS) lines.removeFirst();
                lines.addLast(line);
            }
        }
        File temp = new File(history.getParentFile(), "poll-history.tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            for (String line : lines) { writer.write(line); writer.newLine(); }
        }
        if (!temp.renameTo(history)) throw new java.io.IOException("History rotation failed");
        return lines.size();
    }

    /** Caller chooses an export destination with Android's document picker. */
    public void exportHistory(OutputStream destination) throws java.io.IOException {
        synchronized (LOCK) {
            if (!history.exists()) { destination.flush(); return; }
            try (FileInputStream source = new FileInputStream(history)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = source.read(buffer)) != -1) destination.write(buffer, 0, length);
            }
            destination.flush();
        }
    }

    private static String stateLabel(String state) {
        if (state == null) return "未知";
        switch (state) {
            case "START_RECEIVED": return "收到开始操作";
            case "ARMED": return "已开始监控";
            case "PAUSED": return "已暂停";
            case "PROBING": return "正在试选票档";
            case "ACTION_REQUEST": return "准备操作";
            case "ACTION_ROUTE": return "已选择点击方式";
            case "LOCAL_INPUT_RESULT": return "点击执行结果";
            case "ACTION": return "已执行操作";
            case "START_FAILED": return "启动未完成";
            case "OUT_OF_STOCK": return "缺货";
            case "IN_STOCK": return "发现有票信号";
            case "TIER_SELECTED": return "已选票档";
            case "CHECKOUT": return "到达确认购买页";
            case "BLOCKED": return "遇到阻塞";
            case "UNKNOWN": return "无法确认页面状态";
            case "UNRELATED": return "当前页面不匹配";
            case "CONFIG_INVALID": return "配置待修正";
            case "EXPIRED": return "已到停止时间";
            case "READY": return "页面就绪";
            default: return "已记录检查结果";
        }
    }

    private static String clean(String value, int length) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ")
                .replaceAll("(?i)https?://\\S+", "[链接已隐藏]")
                .replaceAll("(?<!\\d)\\d{8,}(?:[xX])?(?!\\d)", "[长号码已隐藏]");
        return clean.length() > length ? clean.substring(0, length) : clean;
    }
}
