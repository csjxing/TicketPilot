package cn.local.tickethelper.transfer;

import cn.local.tickethelper.core.MonitorConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the actual public file format; no Android permissions, account data or live input. */
public final class TransferBundleTest {
    private static final long NOW = Instant.parse("2026-10-25T10:00:00Z").toEpochMilli();
    private static MonitorConfig config(MonitorConfig.Mode mode) {
        return new MonitorConfig("测试演出", "杭州", "2026-11-15", "19:00", List.of(980, 1380), 1, 1380,
                Instant.parse("2026-11-15T11:00:00Z"), 5000, 10000, mode);
    }
    private static Map<String, byte[]> knowledge() {
        return Map.of("knowledge/index.tsv", utf8("tickethelper-knowledge-v1\t2026-10-25\t0.1.9\n"),
                "knowledge/move-phone.md", utf8("# 换机\n重新设置权限并核对目标。\n"));
    }
    private static byte[] bundle(MonitorConfig c) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TransferBundle.write(out, c, "0.1.9-preview", knowledge(), null, NOW); return out.toByteArray();
    }
    private static TransferBundle.Preview read(byte[] bytes) throws IOException { return TransferBundle.read(new ByteArrayInputStream(bytes)); }
    private static void rejected(byte[] bytes) {
        try { read(bytes); fail("Rejected input must never produce an applicable draft"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }

    @Test public void roundTripContainsPublicTargetAndDoesNotStartOrGrantAnything() throws Exception {
        byte[] bytes = bundle(config(MonitorConfig.Mode.PREPARE)); TransferBundle.Preview p = read(bytes);
        assertEquals("测试演出", p.config.eventKeyword); assertEquals(List.of(980, 1380), p.config.prices);
        assertEquals(1, p.config.quantity); assertEquals(1380, p.config.maxTotal); assertEquals(5000, p.config.minIntervalMs);
        assertEquals(MonitorConfig.Mode.PREPARE, p.config.mode); assertFalse(p.modeDowngraded); assertFalse(p.expired(NOW));
        assertEquals(2, p.knowledgeFiles); assertFalse(p.includesSource);
        String raw = new String(unzip(bytes).get("configuration.properties"), StandardCharsets.UTF_8);
        assertFalse(raw.contains("armed")); assertFalse(raw.contains("viewer")); assertFalse(raw.contains("token"));
        assertFalse(raw.contains("input_mode")); assertFalse(raw.contains("order")); assertFalse(raw.contains("title"));
    }
    @Test public void lockModeIsAlwaysDowngradedBeforeImportPreview() throws Exception {
        TransferBundle.Preview p = read(bundle(config(MonitorConfig.Mode.LOCK_ORDER)));
        assertTrue(p.modeDowngraded); assertEquals(MonitorConfig.Mode.PREPARE, p.config.mode);
    }
    @Test public void reminderModeSurvivesRoundTrip() throws Exception {
        assertEquals(MonitorConfig.Mode.ALERT, read(bundle(config(MonitorConfig.Mode.ALERT))).config.mode);
    }
    @Test public void noTargetStillExportsUsefulKnowledge() throws Exception {
        TransferBundle.Preview p = read(bundle(null)); assertNull(p.config); assertEquals(2, p.knowledgeFiles);
        assertFalse(p.expired(NOW)); assertTrue(unzip(bundle(null)).containsKey("HANDOFF.md"));
    }
    @Test public void invalidTargetDoesNotProducePartialImportableConfiguration() throws Exception {
        MonitorConfig invalid = new MonitorConfig("", "", "", "", List.of(), 1, 0, null, 5000, 10000, MonitorConfig.Mode.ALERT);
        assertNull(read(bundle(invalid)).config);
        assertFalse(unzip(bundle(invalid)).containsKey("configuration.properties"));
    }
    @Test public void expiredTargetRemainsDraftAndIsExplicitlyReportedExpired() throws Exception {
        TransferBundle.Preview p = read(bundle(config(MonitorConfig.Mode.PREPARE)));
        assertTrue(p.expired(Instant.parse("2026-11-16T00:00:00Z").toEpochMilli()));
        assertEquals("2026-11-15", p.config.date);
    }
    @Test public void preservesChineseAndLiteralBackslashWithoutFieldInjection() throws Exception {
        MonitorConfig c = new MonitorConfig("演出\\n价格=介绍", "杭州", "2026-11-15", "19:00", List.of(980), 1, 980,
                Instant.parse("2026-11-15T11:00:00Z"), 5000, 10000, MonitorConfig.Mode.ALERT);
        assertEquals(c.eventKeyword, read(bundle(c)).config.eventKeyword);
    }
    @Test public void futureSchemaRejectedWithoutReturningAnyDraft() throws Exception {
        Map<String, byte[]> files = unzip(bundle(config(MonitorConfig.Mode.PREPARE)));
        replace(files, "manifest.properties", "schema=1", "schema=99"); rejected(zip(files));
    }
    @Test public void outOfRangeOrMalformedCreationTimeCannotReachPreviewFormatting() throws Exception {
        for (String invalid : List.of(Instant.MAX.toString(), Instant.MIN.toString(),
                "+300000000-01-01T00:00:00Z", "2026-99-25T10:00:00Z", "not-a-time")) {
            Map<String, byte[]> files = unzip(bundle(config(MonitorConfig.Mode.PREPARE)));
            replace(files, "manifest.properties", "created_at=2026-10-25T10:00:00Z", "created_at=" + invalid);
            rejected(zip(files));
        }
    }
    @Test public void damagedPayloadRejectedByHash() throws Exception {
        Map<String, byte[]> files = unzip(bundle(config(MonitorConfig.Mode.PREPARE)));
        replace(files, "configuration.properties", "quantity=1", "quantity=2"); rejected(zip(files));
    }
    @Test public void forbiddenPersonalOrArmingFieldsRejectedEvenWithCorrectHash() throws Exception {
        for (String forbidden : List.of("armed=true", "viewer_display_name=PRIVATE_NAME", "viewer_document_suffix=1234",
                "input_mode=SHIZUKU", "authorization=anything", "cookie=anything", "order_permit=anything")) {
            Map<String, byte[]> files = unzip(bundle(config(MonitorConfig.Mode.PREPARE)));
            files.put("configuration.properties", utf8(new String(files.get("configuration.properties"), StandardCharsets.UTF_8) + forbidden + "\n"));
            rehash(files, "configuration.properties"); rejected(zip(files));
        }
    }
    @Test public void duplicateConfigurationKeyRejectedInsteadOfLastValueWinning() throws Exception {
        Map<String, byte[]> files = unzip(bundle(config(MonitorConfig.Mode.PREPARE)));
        files.put("configuration.properties", utf8(new String(files.get("configuration.properties"), StandardCharsets.UTF_8) + "quantity=1\n"));
        rehash(files, "configuration.properties"); rejected(zip(files));
    }
    @Test public void noPathTraversalAbsolutePathOrUnlistedPrivateData() throws Exception {
        for (String path : List.of("../configuration.properties", "/configuration.properties", "knowledge/../secret", "shared_prefs/ticket_helper.xml",
                "poll-history.jsonl", "knowledge/unknown.md", "handoff/../../secret")) {
            Map<String, byte[]> files = unzip(bundle(config(MonitorConfig.Mode.PREPARE))); files.put(path, utf8("must not extract")); rejected(zip(files));
        }
    }
    @Test public void duplicateZipNameRejected() throws Exception {
        Map<String, byte[]> files = unzip(bundle(null)); files.put("HANDOFX.md", utf8("duplicate payload"));
        byte[] zip = zip(files), before = utf8("HANDOFX.md"), after = utf8("HANDOFF.md");
        for (int i = 0; i <= zip.length - before.length; i++) {
            boolean match = true; for (int j = 0; j < before.length; j++) if (zip[i + j] != before[j]) match = false;
            if (match) System.arraycopy(after, 0, zip, i, after.length);
        }
        rejected(zip);
    }
    @Test public void compressedOversizedTextRejectedBeforeAnyConfigurationIsApplied() throws Exception {
        Map<String, byte[]> files = unzip(bundle(null)); files.put("knowledge/move-phone.md", new byte[TransferBundle.MAX_TEXT_BYTES + 1]); rejected(zip(files));
    }
    @Test public void sourceIsBoundedOpaqueDataAndIsNeverExecutedOrUsedAsConfiguration() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] source = utf8("opaque bundled source archive");
        TransferBundle.write(out, null, "0.1.9-preview", knowledge(), source, NOW);
        TransferBundle.Preview p = read(out.toByteArray()); assertTrue(p.includesSource); assertNull(p.config);
        assertArrayEquals(source, unzip(out.toByteArray()).get("handoff/source.zip"));
    }
    @Test public void malformedUtf8RejectedEvenWithUpdatedDigest() throws Exception {
        Map<String, byte[]> files = unzip(bundle(null)); files.put("knowledge/move-phone.md", new byte[]{(byte) 0xc3, 0x28});
        rehash(files, "knowledge/move-phone.md"); rejected(zip(files));
    }
    @Test public void truncatedCentralDirectoryRejected() throws Exception {
        byte[] valid = bundle(config(MonitorConfig.Mode.PREPARE)); rejected(Arrays.copyOf(valid, valid.length - 22));
    }
    @Test public void missingPayloadRejected() throws Exception {
        Map<String, byte[]> files = unzip(bundle(config(MonitorConfig.Mode.PREPARE))); files.remove("HANDOFF.md"); rejected(zip(files));
    }
    @Test public void uncheckedDatesQuantitiesBudgetsAndIntervalsRejectedEvenWithCorrectHash() throws Exception {
        for (String[] change : List.of(new String[]{"quantity=1", "quantity=2"}, new String[]{"max_total=1380", "max_total=1"},
                new String[]{"date=2026-11-15", "date=2026-99-15"}, new String[]{"min_interval_ms=5000", "min_interval_ms=1"},
                new String[]{"mode=PREPARE", "mode=UNKNOWN"})) {
            Map<String, byte[]> files = unzip(bundle(config(MonitorConfig.Mode.PREPARE)));
            replace(files, "configuration.properties", change[0], change[1]); rehash(files, "configuration.properties"); rejected(zip(files));
        }
    }
    @Test public void cannotExportArbitraryAssets() throws Exception {
        try { TransferBundle.write(new ByteArrayOutputStream(), config(MonitorConfig.Mode.ALERT), "0.1.9-preview",
                Map.of("shared_prefs/ticket_helper.xml", utf8("private")), null, NOW); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("名称")); }
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void replace(Map<String, byte[]> files, String path, String oldText, String next) {
        String text = new String(files.get(path), StandardCharsets.UTF_8); assertTrue(text.contains(oldText)); files.put(path, utf8(text.replace(oldText, next)));
    }
    private static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) map.put(entry.getName(), zip.readAllBytes());
        }
        return map;
    }
    private static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    private static void rehash(Map<String, byte[]> files, String changed) throws Exception {
        String manifest = new String(files.get("manifest.properties"), StandardCharsets.UTF_8);
        String[] lines = manifest.split("\n"); String index = null;
        for (String line : lines) if (line.endsWith(".path=" + changed)) index = line.substring(0, line.indexOf(".path="));
        assertNotNull(index); StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(files.get(changed))) digest.append(String.format(java.util.Locale.ROOT, "%02x", b));
        manifest = manifest.replaceAll(java.util.regex.Pattern.quote(index + ".sha256=") + "[a-f0-9]{64}", index + ".sha256=" + digest);
        files.put("manifest.properties", utf8(manifest));
    }
}
