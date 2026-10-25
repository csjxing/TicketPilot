package cn.local.tickethelper.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only, bounded bundled documentation. Never reads account data, preferences or live logs. */
public final class KnowledgeRepository {
    public interface AssetSource { InputStream open(String assetPath) throws IOException; }
    private static final String INDEX = "knowledge/index.tsv";
    private static final int MAX_FILE_BYTES = 64 * 1024, MAX_TOTAL_BYTES = 512 * 1024, MAX_ARTICLES = 32;

    public static final class Article {
        public final String id, category, title, summary, keywords, body;
        private Article(String id, String category, String title, String summary, String keywords, String body) {
            this.id = id; this.category = category; this.title = title; this.summary = summary;
            this.keywords = keywords; this.body = body;
        }
    }

    private final List<Article> articles;
    private final Map<String, byte[]> files;
    public final String updatedOn, edition;

    private KnowledgeRepository(List<Article> articles, Map<String, byte[]> files, String updatedOn, String edition) {
        this.articles = Collections.unmodifiableList(new ArrayList<>(articles));
        this.files = files; this.updatedOn = updatedOn; this.edition = edition;
    }

    public static KnowledgeRepository load(AssetSource source) throws IOException {
        if (source == null) throw new IOException("Missing bundled knowledge source");
        byte[] indexBytes = read(source, INDEX);
        String[] lines = utf8(indexBytes).split("\\r?\\n");
        if (lines.length < 2) throw new IOException("Knowledge index is empty");
        String[] header = lines[0].split("\\t", -1);
        if (header.length != 3 || !"tickethelper-knowledge-v1".equals(header[0])
                || !header[1].matches("\\d{4}-\\d{2}-\\d{2}") || !header[2].matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IOException("Unsupported knowledge index");
        List<Article> articles = new ArrayList<>();
        Map<String, byte[]> files = new LinkedHashMap<>(); files.put(INDEX, indexBytes);
        int total = indexBytes.length;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().isEmpty()) continue;
            String[] parts = lines[i].split("\\t", -1);
            if (parts.length != 6 || !parts[0].matches("[a-z][a-z0-9-]{0,63}")
                    || !parts[5].equals(parts[0] + ".md") || articles.size() >= MAX_ARTICLES)
                throw new IOException("Invalid knowledge entry");
            for (int j = 1; j < 5; j++)
                if (parts[j].trim().isEmpty() || parts[j].length() > 240 || hasControl(parts[j]))
                    throw new IOException("Invalid knowledge metadata");
            String path = "knowledge/" + parts[5];
            if (files.containsKey(path)) throw new IOException("Duplicate knowledge entry");
            byte[] bytes = read(source, path); total += bytes.length;
            if (total > MAX_TOTAL_BYTES) throw new IOException("Bundled knowledge exceeds size limit");
            String body = utf8(bytes);
            if (body.trim().isEmpty()) throw new IOException("Knowledge article is empty");
            files.put(path, bytes);
            articles.add(new Article(parts[0], parts[1], parts[2], parts[3], parts[4], body));
        }
        if (articles.isEmpty()) throw new IOException("Knowledge index is empty");
        return new KnowledgeRepository(articles, files, header[1], header[2]);
    }

    public List<Article> articles() { return articles; }
    public Article find(String id) {
        if (id == null) return null;
        for (Article article : articles) if (article.id.equals(id)) return article;
        return null;
    }

    /** Every search term must match. Titles and problem keywords rank above body-only matches. */
    public List<Article> search(String query) {
        String normalized = normalized(query);
        if (normalized.isEmpty()) return articles;
        if (normalized.length() > 160) return Collections.emptyList();
        String[] terms = normalized.split("\\s+");
        Map<Article, Integer> scores = new LinkedHashMap<>();
        for (Article article : articles) {
            int score = 0;
            String title = normalized(article.title), keys = normalized(article.keywords);
            String summary = normalized(article.summary + " " + article.category), body = normalized(article.body);
            for (String term : terms) {
                if (title.contains(term)) score += 12;
                else if (keys.contains(term)) score += 8;
                else if (summary.contains(term)) score += 4;
                else if (body.contains(term)) score += 1;
                else { score = 0; break; }
            }
            if (score > 0) scores.put(article, score);
        }
        List<Article> result = new ArrayList<>(scores.keySet());
        result.sort(Comparator.comparingInt((Article a) -> scores.get(a)).reversed());
        return Collections.unmodifiableList(result);
    }

    /** Independent byte arrays, with a fixed knowledge/ prefix suitable for a portable ZIP. */
    public Map<String, byte[]> exportFiles() {
        Map<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) copy.put(file.getKey(), file.getValue().clone());
        return Collections.unmodifiableMap(copy);
    }

    private static byte[] read(AssetSource source, String path) throws IOException {
        try (InputStream in = source.open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IOException("Missing bundled knowledge file");
            byte[] buffer = new byte[4096]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (count == 0) continue;
                if (out.size() + count > MAX_FILE_BYTES) throw new IOException("Bundled knowledge file too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }
    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) { throw new IOException("Knowledge must be UTF-8", e); }
    }
    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
    private static String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();
    }
}
