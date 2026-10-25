package cn.local.tickethelper.transfer;

import cn.local.tickethelper.core.MonitorConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable public drafts only. No arbitrary files are extracted and no imported code is executed. */
public final class TransferBundle {
    public static final int SCHEMA = 1;
    public static final int MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_TOTAL_BYTES = 12 * 1024 * 1024;
    public static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 256 * 1024;
    public static final int MAX_CONFIG_BYTES = 16 * 1024;
    public static final int MAX_ENTRIES = 20;
    private static final String MANIFEST = "manifest.properties", CONFIG = "configuration.properties";
    private static final Set<String> CONFIG_KEYS = Set.of("event", "city", "date", "time", "prices", "quantity",
            "max_total", "deadline", "min_interval_ms", "max_interval_ms", "mode");
    private static final Set<String> KNOWLEDGE = Set.of("index.tsv", "quick-start.md", "permissions.md", "select-event.md",
            "monitoring.md", "troubleshooting.md", "move-phone.md", "capabilities.md", "developer-handoff.md");

    private TransferBundle() { }

    public static final class Preview {
        public final String appVersion, createdAt;
        /** Null is a valid knowledge-only package; it never overwrites the current target. */
        public final MonitorConfig config;
        public final boolean modeDowngraded;
        public final int knowledgeFiles;
        public final boolean includesSource;
        private Preview(String version, String created, MonitorConfig config, boolean downgraded,
                        int knowledgeFiles, boolean includesSource) {
            this.appVersion = version; this.createdAt = created; this.config = config;
            this.modeDowngraded = downgraded; this.knowledgeFiles = knowledgeFiles; this.includesSource = includesSource;
        }
        public boolean expired(long nowMs) {
            return config != null && (config.deadline.toEpochMilli() <= nowMs
                    || LocalDate.parse(config.date).isBefore(Instant.ofEpochMilli(nowMs)
                    .atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()));
        }
    }

    /** Callers supply bundled allowlisted documents, never app private directories or raw log files. */
    public static void write(OutputStream destination, MonitorConfig config, String appVersion,
                             Map<String, byte[]> bundledFiles, byte[] source, long nowMs) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        boolean hasConfig = config != null && config.validate().isEmpty();
        if (hasConfig) files.put(CONFIG, encodeConfig(config));
        for (Map.Entry<String, byte[]> item : bundledFiles.entrySet()) {
            if (!knowledgePath(item.getKey())) throw invalid("知识文件名称不受支持");
            if (item.getValue() == null || item.getValue().length > MAX_TEXT_BYTES) throw invalid("知识文件过大");
            strictUtf8(item.getValue()); files.put(item.getKey(), item.getValue().clone());
        }
        if (source != null) {
            if (source.length == 0 || source.length > MAX_SOURCE_BYTES) throw invalid("源码资料大小不受支持");
            files.put("handoff/source.zip", source.clone());
        }
        files.put("HANDOFF.md", handoff(hasConfig).getBytes(StandardCharsets.UTF_8));
        Map<String, String> manifest = new TreeMap<>();
        manifest.put("format", "ticket-helper-portable"); manifest.put("schema", String.valueOf(SCHEMA));
        manifest.put("app_version", bounded(appVersion, 80));
        manifest.put("created_at", Instant.ofEpochMilli(nowMs).toString());
        manifest.put("config_present", String.valueOf(hasConfig)); manifest.put("file_count", String.valueOf(files.size()));
        int index = 0, total = 0;
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            manifest.put("file." + index + ".path", file.getKey());
            manifest.put("file." + index + ".sha256", sha256(file.getValue()));
            total += file.getValue().length; index++;
        }
        if (files.size() + 1 > MAX_ENTRIES || total > MAX_TOTAL_BYTES) throw invalid("资料包过大");
        // Do not close the caller's SAF stream; they own reporting and closing it.
        ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8);
        put(zip, MANIFEST, encodeProperties(manifest));
        for (Map.Entry<String, byte[]> file : files.entrySet()) put(zip, file.getKey(), file.getValue());
        zip.finish(); zip.flush();
    }

    public static Preview read(InputStream source) throws IOException {
        // Bound compressed bytes as well as decompressed bytes; ZIPs are read in memory, never extracted.
        byte[] archive = readBounded(source, MAX_ARCHIVE_BYTES);
        if (archive.length < 4 || archive[0] != 'P' || archive[1] != 'K' || archive[2] != 3 || archive[3] != 4)
            throw invalid("请选择票务助手导出的 ZIP 资料备份");
        Map<String, byte[]> files = new LinkedHashMap<>(); int total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !allowedPath(name)) throw invalid("包内包含不支持的文件");
                if (files.containsKey(name) || files.size() >= MAX_ENTRIES) throw invalid("资料包有重复文件或文件过多");
                int limit = name.equals("handoff/source.zip") ? MAX_SOURCE_BYTES
                        : name.equals(CONFIG) || name.equals(MANIFEST) ? MAX_CONFIG_BYTES : MAX_TEXT_BYTES;
                if (entry.getSize() > limit) throw invalid("包内文件过大");
                byte[] value = readBounded(zip, Math.min(limit, MAX_TOTAL_BYTES - total));
                total += value.length; files.put(name, value); zip.closeEntry();
            }
        } catch (IllegalArgumentException e) { throw invalid("资料包格式不正确"); }
        Map<String, String> manifest = parseProperties(required(files, MANIFEST));
        if (!"ticket-helper-portable".equals(manifest.get("format"))) throw invalid("不是票务助手资料备份");
        if (!String.valueOf(SCHEMA).equals(manifest.get("schema"))) throw invalid("备份版本不受支持，请使用匹配版本的助手");
        String version = bounded(manifest.get("app_version"), 80), created = bounded(manifest.get("created_at"), 60);
        try {
            Instant instant = Instant.parse(created);
            // A syntactically valid Instant can exceed the epoch-millisecond / local-calendar
            // ranges used by Android and the preview. Reject it before handing data to the UI.
            instant.toEpochMilli();
            instant.atZone(ZoneId.of("Asia/Shanghai"));
        } catch (RuntimeException e) { throw invalid("导出时间不正确"); }
        boolean hasConfig = booleanValue(manifest.get("config_present"));
        int count = integer(manifest.get("file_count"), 1, MAX_ENTRIES - 1);
        if (manifest.size() != 6 + count * 2 || files.size() != count + 1) throw invalid("资料包文件清单不完整");
        Set<String> fixed = Set.of("format", "schema", "app_version", "created_at", "config_present", "file_count");
        Map<String, Boolean> listed = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String path = manifest.get("file." + index + ".path"), digest = manifest.get("file." + index + ".sha256");
            if (path == null || path.equals(MANIFEST) || !allowedPath(path) || listed.put(path, true) != null)
                throw invalid("资料包文件清单不正确");
            if (digest == null || !digest.matches("[a-f0-9]{64}") || !sha256(required(files, path)).equals(digest))
                throw invalid("资料包校验失败，可能未传输完整");
        }
        for (String key : manifest.keySet())
            if (!fixed.contains(key) && !key.matches("file\\.(?:0|[1-9]\\d*)\\.(?:path|sha256)"))
                throw invalid("资料包含未知配置");
        if (hasConfig != files.containsKey(CONFIG) || !files.containsKey("HANDOFF.md")) throw invalid("资料包内容不完整");
        for (Map.Entry<String, byte[]> item : files.entrySet())
            if (!item.getKey().equals("handoff/source.zip")) strictUtf8(item.getValue());
        verifyZipDirectory(archive, files.size());
        ConfigResult parsed = hasConfig ? decodeConfig(files.get(CONFIG)) : null;
        int knowledge = (int) files.keySet().stream().filter(TransferBundle::knowledgePath).count();
        return new Preview(version, created, parsed == null ? null : parsed.config,
                parsed != null && parsed.downgraded, knowledge, files.containsKey("handoff/source.zip"));
    }

    public static byte[] readBounded(InputStream source, int maximum) throws IOException {
        if (maximum < 0) throw invalid("资料包过大");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int length;
        while ((length = source.read(buffer)) != -1) {
            if (length == 0) continue;
            if (length > maximum - out.size()) throw invalid("资料包超过大小限制");
            out.write(buffer, 0, length);
        }
        return out.toByteArray();
    }

    private static byte[] encodeConfig(MonitorConfig c) throws IOException {
        Map<String, String> p = new TreeMap<>();
        p.put("event", bounded(c.eventKeyword, 160)); p.put("city", bounded(c.city, 80));
        p.put("date", c.date); p.put("time", c.time); p.put("quantity", String.valueOf(c.quantity));
        p.put("prices", String.join(",", c.prices.stream().map(String::valueOf).toArray(String[]::new)));
        p.put("max_total", String.valueOf(c.maxTotal)); p.put("deadline", c.deadline.toString());
        p.put("min_interval_ms", String.valueOf(c.minIntervalMs)); p.put("max_interval_ms", String.valueOf(c.maxIntervalMs));
        p.put("mode", c.mode.name());
        return encodeProperties(p);
    }

    private static final class ConfigResult {
        final MonitorConfig config; final boolean downgraded;
        ConfigResult(MonitorConfig config, boolean downgraded) { this.config = config; this.downgraded = downgraded; }
    }

    private static ConfigResult decodeConfig(byte[] bytes) throws IOException {
        Map<String, String> p = parseProperties(bytes);
        if (!p.keySet().equals(CONFIG_KEYS)) throw invalid("配置字段不正确，未应用任何目标");
        String pricesText = bounded(p.get("prices"), 200);
        if (!pricesText.matches("[1-9]\\d{0,5}(?:,[1-9]\\d{0,5}){0,19}")) throw invalid("票价配置不正确");
        List<Integer> prices = new ArrayList<>();
        for (String price : pricesText.split(",")) prices.add(integer(price, 1, 100000));
        MonitorConfig.Mode mode;
        try { mode = MonitorConfig.Mode.valueOf(p.get("mode")); }
        catch (RuntimeException e) { throw invalid("监控方式不受支持"); }
        boolean downgraded = mode == MonitorConfig.Mode.LOCK_ORDER;
        if (downgraded) mode = MonitorConfig.Mode.PREPARE;
        Instant deadline;
        try { deadline = Instant.parse(bounded(p.get("deadline"), 60)); }
        catch (RuntimeException e) { throw invalid("停止时间不正确"); }
        MonitorConfig config = new MonitorConfig(bounded(p.get("event"), 160), bounded(p.get("city"), 80),
                bounded(p.get("date"), 10), bounded(p.get("time"), 5), prices,
                integer(p.get("quantity"), 1, 1), integer(p.get("max_total"), 1, 100000), deadline,
                integer(p.get("min_interval_ms"), 3000, 600000), integer(p.get("max_interval_ms"), 3000, 600000), mode);
        if (!config.validate().isEmpty()) throw invalid("配置内容不正确，未应用任何目标");
        return new ConfigResult(config, downgraded);
    }

    private static String bounded(String value, int maximum) throws IOException {
        if (value == null || value.isEmpty() || value.length() > maximum || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) throw invalid("配置文字不正确");
        return value;
    }
    private static int integer(String text, int min, int max) throws IOException {
        if (text == null || !text.matches("0|[1-9]\\d{0,8}")) throw invalid("配置数字不正确");
        try { int n = Integer.parseInt(text); if (n >= min && n <= max) return n; }
        catch (NumberFormatException ignored) { }
        throw invalid("配置数字超出范围");
    }
    private static boolean booleanValue(String text) throws IOException {
        if ("true".equals(text)) return true;
        if ("false".equals(text)) return false;
        throw invalid("资料包标记不正确");
    }
    private static boolean knowledgePath(String path) {
        return path != null && path.startsWith("knowledge/") && KNOWLEDGE.contains(path.substring("knowledge/".length()));
    }
    private static boolean allowedPath(String path) {
        return MANIFEST.equals(path) || CONFIG.equals(path) || "HANDOFF.md".equals(path)
                || "handoff/source.zip".equals(path) || knowledgePath(path);
    }
    private static byte[] required(Map<String, byte[]> files, String key) throws IOException {
        byte[] value = files.get(key); if (value == null) throw invalid("资料包缺少必要文件"); return value;
    }
    private static void put(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(path); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
    private static Map<String, String> parseProperties(byte[] bytes) throws IOException {
        if (bytes.length > MAX_CONFIG_BYTES) throw invalid("配置文件过大");
        Properties p = new Properties() {
            @Override public synchronized Object put(Object key, Object value) {
                if (containsKey(key)) throw new IllegalArgumentException("duplicate_key");
                return super.put(key, value);
            }
        };
        try { p.load(new StringReader(strictUtf8(bytes))); }
        catch (IllegalArgumentException e) { throw invalid("配置包含重复或错误字段"); }
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : p.stringPropertyNames()) result.put(key, p.getProperty(key));
        return result;
    }
    private static byte[] encodeProperties(Map<String, String> properties) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> property : new TreeMap<>(properties).entrySet())
            text.append(property.getKey()).append('=').append(escape(property.getValue())).append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
    private static String strictUtf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw invalid("文件文字编码不正确"); }
    }
    private static void verifyZipDirectory(byte[] archive, int entryCount) throws IOException {
        // Our format is small and single-volume; reject truncation, appended data and ZIP64 instead
        // of accepting local entries from an incomplete or structurally different archive.
        int lower = Math.max(0, archive.length - 22 - 65535);
        for (int p = archive.length - 22; p >= lower; p--) {
            if (archive[p] != 'P' || archive[p + 1] != 'K' || archive[p + 2] != 5 || archive[p + 3] != 6) continue;
            int comment = le16(archive, p + 20);
            if (p + 22 + comment != archive.length) continue;
            if (le16(archive, p + 4) != 0 || le16(archive, p + 6) != 0
                    || le16(archive, p + 8) != entryCount || le16(archive, p + 10) != entryCount)
                throw invalid("资料包目录不正确");
            long size = le32(archive, p + 12), offset = le32(archive, p + 16);
            if (offset + size != p || offset < 0 || size < 46L * entryCount)
                throw invalid("资料包目录不完整");
            return;
        }
        throw invalid("资料包未传输完整");
    }
    private static int le16(byte[] a, int p) { return (a[p] & 255) | ((a[p + 1] & 255) << 8); }
    private static long le32(byte[] a, int p) {
        return (a[p] & 255L) | ((a[p + 1] & 255L) << 8) | ((a[p + 2] & 255L) << 16) | ((a[p + 3] & 255L) << 24);
    }
    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(); for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) { throw new IOException("校验算法不可用"); }
    }
    private static IOException invalid(String reason) { return new IOException(reason); }
    public static String computerPrompt() {
        return "请接手票务助手 Android 工程。目标是在同一台 vivo 上独立运行，并增强真实大麦的界面读取、选票和订单能力。"
                + "先读工程根 AGENTS.md、handoff/START_HERE.md 和 VALIDATION.md，运行 handoff/doctor.py 做只读环境检查。"
                + "区分历史证据与当前状态；确认当前手机、版本、解锁和目标。先排查本机运行与页面适配，再做小范围测试。"
                + "不要把构建通过当作成功抢票。当前自动锁单未开放、物理拔线仍待验收；旧测试演出不是实单授权。"
                + "未获得当前目标和观演人等授权前不创建订单；不付款、不绕过验证码或平台限制。"
                + "不要读取或输出账号、证件、密钥及原始订单；覆盖安装先核对签名，不要卸载或清数据。"
                + "完成后更新验收记录与手机内置知识，构建交接包，让下一台电脑继续接手。";
    }

    private static String handoff(boolean hasTarget) {
        return "# 票务助手资料与目标备份\n\n本包包含离线知识、工程源码和"
                + (hasTarget ? "公开目标草稿。" : "使用资料（没有可恢复的目标）。")
                + "\n\n主要用途是在同一台 vivo 使用助手，并让另一台电脑继续维护。换电脑不需要向手机导入配置。"
                + "解压 handoff/source.zip，用模型工具打开工程根目录，先读 AGENTS.md 与 handoff/START_HERE.md。\n\n"
                + computerPrompt()
                + "\n\n只有需要恢复配置时，才在手机“使用与维护 → 电脑接手与备份 → 选择备份并预览”操作。"
                + "预览后主动保存只恢复草稿与节奏，不会启动；必须从真实大麦重新选择核对。"
                + "不包含账号、登录、实名、密钥、授权、订单许可、截图和原始记录。已有提交记录不会被清除。"
                + "导入不会安装或执行代码；损坏校验不代表可信数字签名。\n";
    }
}
