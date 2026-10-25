package cn.local.tickethelper.knowledge;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class KnowledgeMarkdownTest {
    @Test public void readerOmitsDuplicateTitleAndRendersHeadingsListsAndInlineFormatting() {
        List<KnowledgeMarkdown.Block> blocks = KnowledgeMarkdown.blocks("# 帮助\n\n## 开始\n\n这是 **正文** 和 `标签`。\n\n- 第一步\n2. 第二步\n\n### 后续\n[文件](README.md)");
        assertEquals(6,blocks.size()); assertEquals(KnowledgeMarkdown.Kind.HEADING,blocks.get(0).kind);
        assertEquals("开始",blocks.get(0).text); assertEquals("这是 正文 和 标签。",blocks.get(1).text);
        assertEquals("• 第一步",blocks.get(2).text); assertEquals("2. 第二步",blocks.get(3).text);
        assertEquals(KnowledgeMarkdown.Kind.SUBHEADING,blocks.get(4).kind);
        assertEquals("文件（README.md）",blocks.get(5).text);
    }

    @Test public void codeIsPlainTextAndBlankLinesSplitParagraphs() {
        List<KnowledgeMarkdown.Block> blocks = KnowledgeMarkdown.blocks("前段\n续行\n\n```sh\n./gradlew test\n# literal\n```\n\n后段");
        assertEquals(3,blocks.size()); assertEquals("前段\n续行",blocks.get(0).text);
        assertEquals(KnowledgeMarkdown.Kind.CODE,blocks.get(1).kind); assertEquals("./gradlew test\n# literal",blocks.get(1).text);
        assertEquals("后段",blocks.get(2).text);
    }

    @Test public void emptyInputAndUnclosedCodeRemainReadable() {
        assertTrue(KnowledgeMarkdown.blocks(null).isEmpty()); assertTrue(KnowledgeMarkdown.blocks("# 标题\n\n").isEmpty());
        List<KnowledgeMarkdown.Block> blocks = KnowledgeMarkdown.blocks("```\nnot executed");
        assertEquals(1,blocks.size()); assertEquals(KnowledgeMarkdown.Kind.CODE,blocks.get(0).kind);
        assertEquals("not executed",blocks.get(0).text);
    }
}
