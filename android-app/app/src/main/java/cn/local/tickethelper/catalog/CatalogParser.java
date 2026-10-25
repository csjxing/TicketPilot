package cn.local.tickethelper.catalog;

import cn.local.tickethelper.core.UiItem;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict interpretation of public labels. No guessed year, hidden item ID or inferred availability. */
public final class CatalogParser {
    private static final String ID = "cn.damai:id/";
    private static final Pattern FULL_DATE = Pattern.compile("(?<!\\d)(20\\d{2})[-./年](\\d{1,2})[-./月](\\d{1,2})(?:日)?(?!\\d)");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<![\\d./-])(\\d{1,2})[./月-](\\d{1,2})(?:日)?(?!\\d)");
    private static final Pattern TIME = Pattern.compile("(?<!\\d)([0-2]?\\d)[:：]([0-5]\\d)(?!\\d)");
    private static final Pattern PRICE = Pattern.compile("(?<![\\d.])(\\d{1,6})\\s*元(?!起)");
    private static final Pattern BLOCKED = Pattern.compile("滑动验证|请完成验证|访问异常|操作频繁|安全验证|短信验证码|请输入手机号|账号密码登录|同意协议并登录|网络异常|网络连接失败|网络开小差");
    private static final Pattern PRIVATE = Pattern.compile("^(?:确认购买|确认订单|提交订单|立即提交|立即支付|确认支付|去支付|实付款|待付款|付款剩余.*|支付成功|选择观演人|添加观演人|编辑观演人|选择收货地址|添加收货地址|身份证号|证件号码|证件类型|真实姓名|实名信息)$");
    private static final Pattern NEGATIVE = Pattern.compile("缺货|售罄|无票|已售完|暂不可购|不可选");
    public static final class Entry<T> {
        public final T value;
        /** Only used in the same fresh frame by the controller. Never stored in CatalogStore. */
        public final UiItem target;
        Entry(T value, UiItem target) { this.value = value; this.target = target; }
    }
    private CatalogParser() { }

    public static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\s·・•\\-—_【】\\[\\]（）()]", "");
    }
    public static String blockedReason(List<UiItem> items) {
        for (UiItem item : items) {
            if (PRIVATE.matcher(item.text.replaceAll("\\s+", "")).matches()) return "private_or_checkout_page";
            if (BLOCKED.matcher(item.text).find()) return "login_verification_or_network";
        }
        return "";
    }
    /** Observed public homepage controls; empty text is supported only by this exact native identity. */
    public static UiItem searchEntry(List<UiItem> items) {
        if (!blockedReason(items).isEmpty()) return null;
        for (String id : List.of("pioneer_homepage_header_search_btn", "homepage_header_search_layout")) {
            List<UiItem> matches = items.stream().filter(item -> item.source == UiItem.Source.NODE
                    && item.resourceId.equals(ID + id) && item.hasBounds())
                    .collect(java.util.stream.Collectors.toList());
            if (!matches.isEmpty()) return matches.size() == 1 && matches.get(0).enabled ? matches.get(0) : null;
        }
        return null;
    }

    /** A suggestion is a search action, never a project identity. */
    public static final class SearchSuggestion {
        public final UiItem scope, list, editor, target;
        private SearchSuggestion(UiItem scope, UiItem list, UiItem editor, UiItem target) {
            this.scope = scope; this.list = list; this.editor = editor; this.target = target;
        }
    }
    public static boolean hasSearchSuggestions(List<UiItem> items) {
        return items.stream().anyMatch(item -> item.source == UiItem.Source.NODE && item.hasBounds()
                && (item.resourceId.equals(ID + "search_v2_word_suggest_layout")
                || item.resourceId.equals(ID + "search_v2_suggest_recycler")));
    }
    /** Only the exact entered query may be submitted through a uniquely scoped native suggestion. */
    public static SearchSuggestion searchSuggestion(List<UiItem> items, String query) {
        String wanted = query == null ? "" : query.trim();
        if (wanted.length() < 2 || wanted.length() > 60 || !blockedReason(items).isEmpty()) return null;
        UiItem scope = uniqueRegion(items, "search_v2_word_suggest_layout");
        UiItem list = uniqueRegion(items, "search_v2_suggest_recycler");
        UiItem editor = uniqueRegion(items, "header_search_v2_input");
        if (scope == null || list == null || editor == null || !scope.enabled || !list.enabled || !editor.enabled
                || !inside(scope, list, 0) || !editor.text.trim().equals(wanted)) return null;
        List<UiItem> matches = items.stream().filter(item -> item.source == UiItem.Source.NODE
                && item.resourceId.equals(ID + "tv_word") && inside(list, item, 0)
                && item.text.trim().equals(wanted)).collect(java.util.stream.Collectors.toList());
        return matches.size() == 1 && matches.get(0).enabled
                ? new SearchSuggestion(scope, list, editor, matches.get(0)) : null;
    }

    public static List<Entry<CatalogProject>> projects(List<UiItem> items, String query) {
        String wanted = normalize(query);
        if (wanted.length() < 2 || !blockedReason(items).isEmpty() || hasSearchSuggestions(items)) return List.of();
        Map<String, Entry<CatalogProject>> found = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        boolean structuredSearch = items.stream().anyMatch(item -> item.resourceId.equals(ID + "search_v2_container")
                || item.resourceId.equals(ID + "header_search_v2_input"));
        for (Entry<CatalogProject> entry : structuredSearch ? tourProjects(items) : List.<Entry<CatalogProject>>of()) {
            Entry<CatalogProject> old = found.putIfAbsent(entry.value.key, entry);
            if (old != null && !samePlace(old.target, entry.target)) ambiguous.add(entry.value.key);
        }
        for (UiItem item : items) {
            String label = item.text.trim();
            if (!item.hasBounds() || label.length() > 180 || label.length() < 5) continue;
            if (label.matches(".*(?:荧光棒|周边|纪念品|应援物|衍生品).*")) continue;
            if (label.contains("搜索") || label.contains("大家都在搜") || label.contains("搜索历史")) continue;
            if (item.resourceId.equals(ID + "tv_project_tourName")) continue;
            // Search's article titles, artist cards, recommendations and tour headings are not project entries.
            UiItem card = standardCard(items, item);
            if (structuredSearch && !(item.source == UiItem.Source.NODE && item.resourceId.equals(ID + "tv_project_name") && card != null)) continue;
            // Native result cards are already the results of Damai's visible search. A natural query
            // need not be a contiguous substring of a title; the user explicitly chooses the project.
            if (!structuredSearch && !normalize(label).contains(wanted)) continue;
            String city = city(label);
            if (city.isEmpty() && card != null) city = cardCity(items, card);
            boolean projectTitle = label.matches(".*(?:演唱会|音乐会|演出|音乐节|舞台剧|话剧|巡演|Live|LIVE|live).*" );
            boolean titleNode = item.resourceId.matches(".*(?:project.*(?:name|title)|item.*(?:name|title)|tv_(?:name|title)|title_tv)$");
            if (item.source == UiItem.Source.OCR && items.stream().anyMatch(nativeItem -> nativeItem.source == UiItem.Source.NODE
                    && nativeItem.resourceId.matches(".*(?:project.*(?:name|title)|item.*(?:name|title)|tv_(?:name|title)|title_tv)$")
                    && normalize(nativeItem.text).contains(wanted) && (inside(nativeItem, item, 8) || samePlace(nativeItem, item)))) continue;
            if (city.isEmpty() && !projectTitle && !titleNode) continue;
            // Search chips consisting solely of the user's query are not project cards.
            if (normalize(label).equals(wanted) && !titleNode) continue;
            String key = "project:" + normalize(label);
            CatalogProject value = new CatalogProject(key, label, city, explicitYear(label));
            Entry<CatalogProject> prior = found.get(key);
            if (prior != null && !samePlace(prior.target, item)) ambiguous.add(key);
            else if (prior == null || prefer(item, prior.target)) found.put(key, new Entry<>(value, item));
        }
        for (String key : ambiguous) found.remove(key);
        return bounded(found.values().stream().collect(java.util.stream.Collectors.toList()));
    }

    /** Detail/SKU title identity must be established independently of the search query. */
    public static CatalogProject projectFromPage(List<UiItem> items, String query) {
        if (!blockedReason(items).isEmpty() || hasSearchSuggestions(items)) return null;
        if (items.stream().anyMatch(item -> item.resourceId.equals(ID + "search_v2_container")
                || item.resourceId.equals(ID + "header_search_v2_input"))) return null;
        List<UiItem> titles = new ArrayList<>(), nativeTitles = new ArrayList<>();
        for (UiItem item : items) {
            boolean knownTitle = item.resourceId.equals(ID + "title_tv")
                    || infoV2Title(item)
                    || (item.resourceId.equals(ID + "tv_title") && !city(item.text).isEmpty())
                    || item.resourceId.matches(".*(?:project_name|project_title|tv_project_name|project_detail_title|project_detail_project_name)$");
            boolean boundedPublicTitle = !city(item.text).isEmpty()
                    && item.text.matches(".*(?:演唱会|音乐会|音乐节|巡演|话剧|舞台剧).*" );
            if ((knownTitle || boundedPublicTitle) && item.text.length() >= 5 && item.text.length() <= 180
                    && item.hasBounds() && (knownTitle || query.isEmpty() || normalize(item.text).contains(normalize(query)))) {
                titles.add(item);
                if (knownTitle && item.source == UiItem.Source.NODE) nativeTitles.add(item);
            }
        }
        // Native title text can be complete even when the visible single line is ellipsized.
        // OCR fragments from that same title are not separate candidate projects.
        if (!nativeTitles.isEmpty()) titles = joinInfoV2Title(nativeTitles);
        Map<String, UiItem> distinct = new LinkedHashMap<>();
        for (UiItem item : titles) {
            UiItem prior = distinct.putIfAbsent(normalize(item.text), item);
            // The observed detail header may expose two overlapping renderings. Same
            // text elsewhere on the screen must not silently become that same header.
            if (prior != null && infoV2Title(prior) && infoV2Title(item)
                    && !samePlace(prior, item) && !inside(prior, item, 0) && !inside(item, prior, 0)) return null;
        }
        if (distinct.size() != 1) return null;
        UiItem title = distinct.values().iterator().next();
        String year = explicitYear(title.text);
        if (year.isEmpty()) {
            Set<String> years = new LinkedHashSet<>();
            // Only the target's native session region can supply a missing year. Dates in
            // descriptions, nearby recommendations or notices must never determine a session.
            UiItem sessionRegion = uniqueRegion(items, "project_detail_perform_flowlayout");
            for (UiItem item : items) {
                if (sessionRegion == null || !inside(sessionRegion, item, 0)) continue;
                Matcher match = FULL_DATE.matcher(item.text);
                while (match.find()) years.add(match.group(1));
            }
            if (years.size() == 1) year = years.iterator().next();
        }
        return new CatalogProject("project:" + normalize(title.text), title.text, city(title.text), year);
    }
    private static boolean infoV2Title(UiItem item) {
        return item.source == UiItem.Source.NODE && (item.resourceId.equals(ID + "info_v2_title_tv1")
                || item.resourceId.equals(ID + "info_v2_title_tv2"));
    }
    /** The observed detail header uses consecutive native lines, not two separate project names. */
    private static List<UiItem> joinInfoV2Title(List<UiItem> titles) {
        List<UiItem> first = titles.stream().filter(item -> item.resourceId.equals(ID + "info_v2_title_tv1"))
                .collect(java.util.stream.Collectors.toList());
        List<UiItem> second = titles.stream().filter(item -> item.resourceId.equals(ID + "info_v2_title_tv2"))
                .collect(java.util.stream.Collectors.toList());
        if (first.size() != 1 || second.size() != 1) return titles;
        UiItem top = first.get(0), bottom = second.get(0);
        if (normalize(top.text).equals(normalize(bottom.text))) return titles;
        if (top.left != bottom.left || top.right != bottom.right || top.bottom != bottom.top) return titles;
        String firstCity = city(top.text), secondCity = city(bottom.text);
        if (firstCity.isEmpty() || (!secondCity.isEmpty() && !firstCity.equals(secondCity))) return titles;
        if (bottom.text.matches("^(?:【|\\[|[\\p{IsHan}]{2,8}[·・•]).*")) return titles;
        String label = top.text.trim() + bottom.text.trim();
        if (label.length() > 180) return titles;
        List<UiItem> joined = new ArrayList<>(titles);
        joined.remove(top); joined.remove(bottom);
        // This joined interpretation remains local to projectFromPage and is never a click target.
        joined.add(new UiItem(label, top.left, top.top, bottom.right, bottom.bottom, top.enabled && bottom.enabled,
                false, false, UiItem.Source.NODE, ""));
        return joined;
    }

    public static String city(String title) {
        if (title == null) return "";
        for (Pattern pattern : List.of(Pattern.compile("^[【\\[]([\\p{IsHan}]{2,8})[】\\]]"),
                Pattern.compile("^([\\p{IsHan}]{2,8})[·・•]"),
                Pattern.compile("[-—]([\\p{IsHan}]{2,8})站(?:[（(].*)?$"))) {
            Matcher match = pattern.matcher(title.trim());
            if (match.find()) return match.group(1);
        }
        return "";
    }
    /** Only known city decorations may differ between a search card and the detail/SKU title. */
    public static boolean sameProject(CatalogProject expected, CatalogProject current) {
        if (expected == null || current == null || !expected.city.equals(current.city)) return false;
        return canonicalTitle(expected).equals(canonicalTitle(current));
    }
    private static String canonicalTitle(CatalogProject project) {
        String title = project.label.trim(), city = Pattern.quote(project.city);
        if (!project.city.isEmpty()) {
            title = title.replaceFirst("^(?:【" + city + "】|\\[" + city + "\\]|" + city + "[·・•])", "");
            title = title.replaceFirst("[-—]" + city + "站$", "");
        }
        return normalize(title);
    }

    /** A city tile belongs to exactly one native tour group; no positional association across groups. */
    private static List<Entry<CatalogProject>> tourProjects(List<UiItem> items) {
        List<Entry<CatalogProject>> result = new ArrayList<>();
        for (UiItem group : items) {
            if (group.source != UiItem.Source.NODE || !group.resourceId.equals(ID + "contentLayout") || !group.hasBounds()) continue;
            List<UiItem> names = items.stream().filter(item -> item.source == UiItem.Source.NODE
                    && item.resourceId.equals(ID + "tv_project_tourName") && inside(group, item, 0)).collect(java.util.stream.Collectors.toList());
            List<UiItem> grids = items.stream().filter(item -> item.source == UiItem.Source.NODE
                    && item.resourceId.equals(ID + "tour_recyclerView") && inside(group, item, 0)).collect(java.util.stream.Collectors.toList());
            if (names.size() != 1 || grids.size() != 1) continue;
            String title = names.get(0).text.trim();
            if (title.length() < 5 || title.length() > 150) continue;
            if (title.matches(".*(?:荧光棒|周边|纪念品|应援物|衍生品).*")) continue;
            UiItem grid = grids.get(0);
            for (UiItem cell : items) {
                if (cell.source != UiItem.Source.NODE || !cell.resourceId.equals(ID + "ll_city_tour") || !inside(grid, cell, 0)) continue;
                List<UiItem> cities = items.stream().filter(item -> item.source == UiItem.Source.NODE
                        && item.resourceId.equals(ID + "tv_city") && inside(cell, item, 0)
                        && item.text.matches("[\\p{IsHan}]{2,8}")).collect(java.util.stream.Collectors.toList());
                if (cities.size() != 1) continue;
                String city = cities.get(0).text, label = title + "-" + city + "站";
                String key = "project:" + normalize(label);
                result.add(new Entry<>(new CatalogProject(key, label, city, ""), cell));
            }
        }
        return result;
    }
    private static UiItem standardCard(List<UiItem> items, UiItem title) {
        List<UiItem> cards = items.stream().filter(item -> item.source == UiItem.Source.NODE
                && item.resourceId.equals(ID + "ll_search_item") && inside(item, title, 0))
                .collect(java.util.stream.Collectors.toList());
        return cards.size() == 1 ? cards.get(0) : null;
    }
    private static String cardCity(List<UiItem> items, UiItem card) {
        List<UiItem> cities = items.stream().filter(item -> item.source == UiItem.Source.NODE
                && item.resourceId.equals(ID + "tv_project_city") && inside(card, item, 0))
                .collect(java.util.stream.Collectors.toList());
        if (cities.size() != 1) return "";
        String text = cities.get(0).text.trim();
        if (text.matches(".*(?:待定|全国|多城).*")) return "";
        Matcher match = Pattern.compile("^([\\p{IsHan}]{2,8})\\s*(?:[|｜].*)?$").matcher(text);
        return match.matches() ? match.group(1) : "";
    }

    public static List<Entry<CatalogSession>> sessions(List<UiItem> items, CatalogProject project) {
        if (project == null || !blockedReason(items).isEmpty()) return List.of();
        UiItem region = uniqueRegion(items, "project_detail_perform_flowlayout");
        if (region == null) return List.of();
        Map<String, Entry<CatalogSession>> result = new LinkedHashMap<>();
        for (UiItem cell : cells(items, region)) {
            String label = cell.text.trim();
            if (!FULL_DATE.matcher(label).find() && !SHORT_DATE.matcher(label).find()) continue;
            String date = date(label, project.yearHint), time = time(label);
            String key = "session:" + normalize(label);
            result.put(key, new Entry<>(new CatalogSession(key, label, date, time), cell));
        }
        return bounded(result.values().stream().collect(java.util.stream.Collectors.toList()));
    }

    public static List<Entry<CatalogTier>> tiers(List<UiItem> items) {
        if (!blockedReason(items).isEmpty()) return List.of();
        UiItem region = uniqueRegion(items, "project_detail_perform_price_flowlayout");
        if (region == null) return List.of();
        List<Entry<CatalogTier>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (UiItem cell : cells(items, region)) {
            Matcher match = PRICE.matcher(cell.text);
            if (!match.find()) continue;
            int price;
            try { price = Integer.parseInt(match.group(1)); } catch (NumberFormatException ignored) { continue; }
            if (price <= 0 || price > 100000 || match.find() || cell.text.matches(".*(?:套票|双人|三人|家庭|儿童|学生|优惠|\\d\\s*张).*")) continue;
            String status = NEGATIVE.matcher(cell.text).find() ? "OUT_OF_STOCK"
                    : !cell.text.matches(".*(?:有票提醒|有货提醒|到货提醒|开售提醒).*" )
                    && cell.text.matches(".*(?:有票|有货).*" ) ? "IN_STOCK_SIGNAL" : "UNKNOWN";
            String key = "tier:" + normalize(cell.text);
            if (seen.add(key)) result.add(new Entry<>(new CatalogTier(key, cell.text, price, status), cell));
        }
        return bounded(result);
    }

    /** Cells come from actual visible native region bounds, never arbitrary date/price text elsewhere. */
    private static List<UiItem> cells(List<UiItem> items, UiItem region) {
        List<UiItem> result = new ArrayList<>();
        Set<String> seenBounds = new LinkedHashSet<>();
        for (UiItem item : items) {
            if (!item.resourceId.equals(ID + "ll_perform_item") || !inside(region, item, 0)) continue;
            String boundsKey = item.left + ":" + item.top + ":" + item.right + ":" + item.bottom;
            if (!seenBounds.add(boundsKey)) continue;
            List<UiItem> labels = new ArrayList<>();
            for (UiItem text : items) if (!text.text.isEmpty() && text.hasBounds() && inside(item, text, 3)) labels.add(text);
            labels.sort(Comparator.comparingInt((UiItem text) -> text.top).thenComparingInt(text -> text.left));
            // The existing SKU OCR adapter may already have joined a full cell. Prefer that exact box.
            UiItem adapted = labels.stream().filter(text -> text.source == UiItem.Source.OCR
                    && text.left == item.left && text.top == item.top && text.right == item.right && text.bottom == item.bottom)
                    .findFirst().orElse(null);
            StringBuilder label = new StringBuilder();
            if (adapted != null) label.append(adapted.text);
            else for (UiItem text : labels) {
                if (label.indexOf(text.text) >= 0) continue;
                if (label.length() > 0) label.append(' ');
                label.append(text.text);
            }
            if (label.length() > 0 && label.length() <= 180) result.add(new UiItem(label.toString(), item.left, item.top,
                    item.right, item.bottom, item.enabled, item.clickable, item.selected, UiItem.Source.OCR, item.resourceId));
        }
        return result;
    }
    private static UiItem uniqueRegion(List<UiItem> items, String id) {
        List<UiItem> matches = items.stream().filter(item -> item.source == UiItem.Source.NODE
                && item.resourceId.equals(ID + id) && item.hasBounds()).collect(java.util.stream.Collectors.toList());
        return matches.size() == 1 ? matches.get(0) : null;
    }
    private static boolean inside(UiItem outer, UiItem inner, int padding) {
        return inner.hasBounds() && inner.left >= outer.left - padding && inner.top >= outer.top - padding
                && inner.right <= outer.right + padding && inner.bottom <= outer.bottom + padding;
    }
    private static boolean samePlace(UiItem a, UiItem b) {
        return Math.abs(a.centerY() - b.centerY()) <= Math.max(20, (a.bottom - a.top) / 2)
                && Math.abs(a.centerX() - b.centerX()) < Math.max(50, (a.right - a.left) / 2);
    }
    private static boolean prefer(UiItem a, UiItem b) { return a.source == UiItem.Source.NODE && b.source != UiItem.Source.NODE; }
    private static <T> List<T> bounded(List<T> items) { return List.copyOf(items.subList(0, Math.min(30, items.size()))); }
    private static String explicitYear(String text) {
        // A tour name such as “2026 巡演” does not establish the year of a displayed 01/02 session.
        Matcher match = FULL_DATE.matcher(text);
        Set<String> years = new LinkedHashSet<>();
        while (match.find()) years.add(match.group(1));
        return years.size() == 1 ? years.iterator().next() : "";
    }
    private static String date(String text, String year) {
        Matcher match = FULL_DATE.matcher(text);
        try {
            if (match.find()) {
                String date = LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))).toString();
                return match.find() ? "" : date;
            }
            if (!year.matches("20\\d{2}")) return "";
            match = SHORT_DATE.matcher(text);
            if (!match.find()) return "";
            String date = LocalDate.of(Integer.parseInt(year), Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))).toString();
            return match.find() ? "" : date;
        } catch (RuntimeException ignored) { return ""; }
    }
    private static String time(String text) {
        Matcher match = TIME.matcher(text);
        try {
            if (!match.find()) return "";
            String time = LocalTime.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2))).toString();
            return match.find() ? "" : time;
        } catch (RuntimeException ignored) { return ""; }
    }
}
