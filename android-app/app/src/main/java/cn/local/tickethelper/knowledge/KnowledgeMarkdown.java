package cn.local.tickethelper.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small text renderer for our bundled Markdown subset. No HTML, network or script execution. */
public final class KnowledgeMarkdown {
    public enum Kind { HEADING, SUBHEADING, PARAGRAPH, BULLET, CODE }
    public static final class Block {
        public final Kind kind;
        public final String text;
        private Block(Kind kind, String text) { this.kind = kind; this.text = text; }
    }
    private KnowledgeMarkdown() { }

    public static List<Block> blocks(String markdown) {
        List<Block> result = new ArrayList<>(); StringBuilder paragraph = new StringBuilder(), code = new StringBuilder();
        boolean inCode = false;
        for (String raw : (markdown == null ? "" : markdown).split("\\r?\\n", -1)) {
            String line = raw.trim();
            if (line.startsWith("```")) {
                flush(result, paragraph, Kind.PARAGRAPH);
                if (inCode) flush(result, code, Kind.CODE);
                inCode = !inCode; continue;
            }
            if (inCode) { if (code.length() > 0) code.append('\n'); code.append(raw); continue; }
            if (line.isEmpty()) { flush(result, paragraph, Kind.PARAGRAPH); continue; }
            if (line.startsWith("# ")) { flush(result, paragraph, Kind.PARAGRAPH); continue; }
            if (line.startsWith("## ") || line.startsWith("### ")) {
                flush(result, paragraph, Kind.PARAGRAPH);
                result.add(new Block(line.startsWith("### ") ? Kind.SUBHEADING : Kind.HEADING,
                        plain(line.replaceFirst("^#{2,3}\\s+", ""))));
            } else if (line.startsWith("- ") || line.matches("\\d+[.)]\\s+.*")) {
                flush(result, paragraph, Kind.PARAGRAPH);
                result.add(new Block(Kind.BULLET, line.startsWith("- ") ? "• " + plain(line.substring(2)) : plain(line)));
            } else {
                if (paragraph.length() > 0) paragraph.append('\n');
                paragraph.append(plain(line));
            }
        }
        flush(result, paragraph, Kind.PARAGRAPH); flush(result, code, Kind.CODE);
        return Collections.unmodifiableList(result);
    }
    private static void flush(List<Block> out, StringBuilder value, Kind kind) {
        if (value.length() > 0) { out.add(new Block(kind, value.toString())); value.setLength(0); }
    }
    private static String plain(String value) {
        return value.replace("**", "").replace("`", "")
                .replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1（$2）");
    }
}
