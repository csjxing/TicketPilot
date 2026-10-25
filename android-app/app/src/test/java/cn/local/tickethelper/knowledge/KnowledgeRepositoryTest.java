package cn.local.tickethelper.knowledge;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class KnowledgeRepositoryTest {
    private static final String HEADER = "tickethelper-knowledge-v1\t2026-10-25\t0.1.9\n";
    private static String entry(String id, String title, String keywords) {
        return id + "\t入门\t" + title + "\t说明摘要\t" + keywords + "\t" + id + ".md\n";
    }
    private static KnowledgeRepository load(Map<String, byte[]> files) throws IOException {
        return KnowledgeRepository.load(path -> {
            byte[] bytes = files.get(path); if (bytes == null) throw new IOException("missing");
            return new ByteArrayInputStream(bytes);
        });
    }
    private static Map<String, byte[]> fixture(String index) {
        Map<String, byte[]> result = new LinkedHashMap<>(); result.put("knowledge/index.tsv", index.getBytes(StandardCharsets.UTF_8)); return result;
    }
    private static void body(Map<String, byte[]> files, String id, String body) { files.put("knowledge/" + id + ".md", body.getBytes(StandardCharsets.UTF_8)); }

    @Test public void searchRanksTitleBeforeKeywordsAndBodyAndRequiresEveryTerm() throws Exception {
        Map<String, byte[]> files = fixture(HEADER + entry("one", "换机指南", "备份") + entry("two", "本地资料", "换机") + entry("three", "其他", "帮助"));
        body(files,"one","# 换机指南\n\n先保存备份，再换手机。"); body(files,"two","# 资料\n换机后权限重设。"); body(files,"three","# 其他\n换机资料，仅供开发。");
        KnowledgeRepository repo = load(files);
        assertEquals("one", repo.search("换机").get(0).id);
        assertEquals("two", repo.search("换机").get(1).id);
        assertEquals("two", repo.search("换机 权限").get(0).id);
        assertTrue(repo.search("换机 不存在的词").isEmpty());
    }

    @Test public void searchUnderstandsCaseAndFullWidthLatinAndEmptyQuery() throws Exception {
        Map<String, byte[]> files = fixture(HEADER + entry("input", "点击方式", "Shizuku")); body(files,"input","# 输入\n保持本机连接。");
        KnowledgeRepository repo = load(files);
        assertEquals("input", repo.search(" ＳＨＩＺＵＫＵ ").get(0).id);
        assertEquals(1, repo.search("  ").size()); assertNull(repo.find("../../private"));
        assertTrue(repo.search("a".repeat(161)).isEmpty());
    }

    @Test public void exportContainsOnlyIndexedFilesAndCannotMutateRepository() throws Exception {
        Map<String, byte[]> files = fixture(HEADER + entry("help", "帮助", "开始")); body(files,"help","# 帮助\n正文。");
        files.put("private/account.json", "not part of knowledge".getBytes(StandardCharsets.UTF_8));
        KnowledgeRepository repo = load(files); Map<String, byte[]> exported = repo.exportFiles();
        assertEquals(2, exported.size()); assertFalse(exported.containsKey("private/account.json"));
        exported.get("knowledge/help.md")[0] = '!';
        assertEquals('#', repo.exportFiles().get("knowledge/help.md")[0]);
        try { exported.put("x", new byte[0]); fail("mutable export map"); } catch (UnsupportedOperationException expected) { }
        try { repo.articles().clear(); fail("mutable articles"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void traversalIsRejectedBeforeAnUnlistedAssetCanBeOpened() throws Exception {
        List<String> opened = new ArrayList<>();
        String index = HEADER + "help\t入门\t帮助\t摘要\t关键字\t../secret.md\n";
        try {
            KnowledgeRepository.load(path -> { opened.add(path); return new ByteArrayInputStream(index.getBytes(StandardCharsets.UTF_8)); });
            fail("accepted traversal");
        } catch (IOException expected) { assertEquals(List.of("knowledge/index.tsv"), opened); }
    }

    @Test public void duplicateIdsAreRejected() throws Exception {
        Map<String, byte[]> files = fixture(HEADER + entry("help", "帮助", "开始") + entry("help", "重复", "关键字")); body(files,"help","正文");
        try { load(files); fail("accepted duplicate"); } catch (IOException expected) { }
    }

    @Test public void unknownSchemaAndIncompleteMetadataAreRejected() throws Exception {
        for (String index : List.of("future-v2\t2026-10-25\t0.1.9\n" + entry("help", "帮助", "开始"),
                HEADER + "help\t分类\t标题\t\t关键词\thelp.md\n", HEADER)) {
            try { load(fixture(index)); fail("accepted malformed index"); } catch (IOException expected) { }
        }
    }

    @Test public void missingAndInvalidUtf8ArticlesAreNotSilentlyDropped() throws Exception {
        Map<String, byte[]> files = fixture(HEADER + entry("help", "帮助", "开始"));
        try { load(files); fail("accepted missing article"); } catch (IOException expected) { }
        files.put("knowledge/help.md", new byte[]{(byte) 0xc3, (byte) 0x28});
        try { load(files); fail("accepted invalid UTF-8"); } catch (IOException expected) { }
    }

    @Test public void oversizedArticleAndOversizedTotalAreRejected() throws Exception {
        Map<String, byte[]> files = fixture(HEADER + entry("help", "帮助", "开始")); body(files,"help","a".repeat(65537));
        try { load(files); fail("accepted oversized article"); } catch (IOException expected) { }
        StringBuilder index = new StringBuilder(HEADER);
        for (int i=0;i<9;i++) index.append(entry("item-"+i,"资料","说明"));
        files = fixture(index.toString());
        for (int i=0;i<9;i++) body(files,"item-"+i,"a".repeat(65536));
        try { load(files); fail("accepted oversized total"); } catch (IOException expected) { }
    }

    @Test public void bundledKnowledgeIncludesUserGuideMigrationAndAccurateDevelopmentBoundary() throws Exception {
        Path assets = Path.of("src/main/assets");
        if (!Files.exists(assets)) assets = Path.of("android-app/app/src/main/assets");
        final Path base = assets;
        KnowledgeRepository repo = KnowledgeRepository.load(path -> Files.newInputStream(base.resolve(path)));
        assertEquals(8,repo.articles().size()); assertEquals(9,repo.exportFiles().size());
        assertNotNull(repo.find("quick-start")); assertNotNull(repo.find("move-phone")); assertNotNull(repo.find("developer-handoff"));
        assertTrue(repo.find("capabilities").body.contains("自动提交订单"));
        assertTrue(repo.find("capabilities").body.contains("入口仍未开放"));
        assertTrue(repo.find("capabilities").body.contains("USB 物理拔线"));
        assertTrue(repo.find("move-phone").body.contains("任务保持暂停"));
        assertTrue(repo.find("developer-handoff").body.contains("VERIFIED_PROFILES 仍为空"));
        for (Map.Entry<String, byte[]> file : repo.exportFiles().entrySet()) {
            assertTrue(file.getKey().startsWith("knowledge/"));
            String body = new String(file.getValue(), StandardCharsets.UTF_8);
            assertFalse("Unexpected unredacted long identifier in " + file.getKey(), body.matches("(?s).*(?<!\\d)\\d{17}[0-9xX](?!\\d).*"));
            assertFalse(body.contains("/Users/"));
        }
    }
}
