package cn.local.tickethelper.catalog;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Extracts only an exact query fragment that is also visible in the chosen title. No alias guessing. */
public final class CatalogKeyword {
    private static final String[] GENERIC = {
            "世界巡回演唱会", "巡回演唱会", "全国巡回演出", "巡回演出", "演唱会", "音乐节", "音乐会",
            "全国巡演", "回流票", "演出票", "演出", "巡演", "巡回", "门票", "票务", "抢票", "购票",
            "买票", "回流", "售票", "预售", "预订", "开票", "有票", "缺货", "现票", "票档", "票价", "测试"
    };
    private CatalogKeyword() { }

    public static String fromQuery(String title, String query, String city) {
        String realTitle = title == null ? "" : title.trim();
        String rawQuery = query == null ? "" : query.trim();
        if (realTitle.isEmpty() || realTitle.length() > 500 || rawQuery.length() > 60) return null;
        String prepared = fold(rawQuery), place = fold(city).trim();
        if (!place.isEmpty()) prepared = prepared.replace(place, " ");
        // Dates, times and amounts are not useful identities for later page verification.
        prepared = prepared.replaceAll("\\d{1,4}年(?:\\d{1,2}月)?(?:\\d{1,2}[日号])?|\\d{1,2}月(?:\\d{1,2}[日号])?|\\d{1,2}[日号]|\\d{1,2}:\\d{2}|(?<!\\d)(?:19|20)\\d{2}(?!\\d)|\\d+(?:元|张)", " ");
        for (String generic : GENERIC) prepared = prepared.replace(generic, " ");
        String normalizedCity = normalize(city), normalizedTitle = normalize(realTitle);
        LinkedHashSet<String> fragments = new LinkedHashSet<>();
        // Keep hyphenated names as a whole first, while allowing comma-separated queries too.
        for (String part : prepared.split("[\\p{Z}\\s]+")) fragments.add(part);
        for (String part : prepared.split("[\\p{P}\\p{Z}\\s]+")) fragments.add(part);
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        for (String part : fragments) {
            String candidate = normalize(part);
            if (candidate.codePointCount(0, candidate.length()) < 2
                    || candidate.matches("[\\d年月日号时分]+")
                    || (!normalizedCity.isEmpty() && normalizedCity.contains(candidate))) continue;
            if (normalizedTitle.contains(candidate)) parts.add(candidate);
        }
        List<String> candidates = new ArrayList<>(parts);
        // Stable sorting prefers the first query fragment when two have the same length.
        candidates.sort(Comparator.comparingInt(String::length).reversed());
        for (String candidate : candidates) {
            String match = originalSubstring(realTitle, normalizedTitle, candidate);
            if (match != null) return match;
        }
        return null;
    }

    private static String originalSubstring(String title, String normalizedTitle, String expected) {
        String[] prefixes = new String[title.length() + 1];
        for (int i = 0; i <= title.length(); i++) {
            if (i < title.length() && Character.isLowSurrogate(title.charAt(i))) continue;
            prefixes[i] = normalize(title.substring(0, i));
        }
        String best = null;
        for (int at = normalizedTitle.indexOf(expected); at >= 0; at = normalizedTitle.indexOf(expected, at + 1)) {
            String before = normalizedTitle.substring(0, at), through = normalizedTitle.substring(0, at + expected.length());
            for (int start = 0; start < prefixes.length; start++) {
                if (!before.equals(prefixes[start])) continue;
                for (int end = start + 2; end < prefixes.length; end++) {
                    if (best != null && end - start >= best.length()) break;
                    if (!through.equals(prefixes[end])) continue;
                    String original = title.substring(start, end);
                    if (normalize(original).equals(expected)) { best = original; break; }
                }
            }
        }
        return best;
    }

    private static String fold(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    private static String normalize(String value) { return fold(value).replaceAll("[\\p{P}\\p{Z}\\s]+", ""); }
}
