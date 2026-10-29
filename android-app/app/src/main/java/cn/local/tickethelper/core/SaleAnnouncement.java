package cn.local.tickethelper.core;

import cn.local.tickethelper.catalog.CatalogParser;
import cn.local.tickethelper.catalog.CatalogProject;
import cn.local.tickethelper.catalog.ReservationSessionParser;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Local public opening draft. A timestamp never authorizes starting or submitting an order. */
public final class SaleAnnouncement {
    private static final String ID = "cn.damai:id/";
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final String DATE_TIME = "(?:(20[0-9]{2})[年./-])?([0-9]{1,2})[月./-]([0-9]{1,2})日?([0-9]{1,2})[:：]([0-9]{2})(?:[:：]([0-9]{2}))?";
    private static final Pattern SALE = Pattern.compile("(?:(?:开售时间|开抢时间|开售|开抢)[:：]?)?" + DATE_TIME + "(?:开售|开抢)?");
    private static final Pattern PERFORMANCE = Pattern.compile("(20[0-9]{2})[年./-]([0-9]{1,2})[月./-]([0-9]{1,2})日?(?:周[一二三四五六日天])?([0-9]{1,2})[:：]([0-9]{2})");
    private SaleAnnouncement() { }

    public static Instant read(Observation page, MonitorConfig target, long now) {
        if (page == null || target == null || !"cn.damai".equals(page.packageName) || now <= 0
                || page.timestampMs > now || now - page.timestampMs > 5000
                || target.eventKeyword.length() < 2 || target.city.isEmpty() || target.eventStartsAt() == null
                || !CatalogParser.blockedReason(page.items).isEmpty()
                || page.items.stream().anyMatch(i -> i.text.contains("验证码") || i.text.contains("支付密码"))) return null;
        List<UiItem> nativeItems = page.items.stream().filter(i -> i.source == UiItem.Source.NODE).collect(Collectors.toList());
        if (nativeItems.stream().anyMatch(SaleAnnouncement::searchOrTour)) return null;
        CatalogProject project = CatalogParser.projectFromPage(nativeItems, target.eventKeyword);
        if (project == null || !target.city.equals(project.city)
                || !CatalogParser.normalize(project.label).contains(CatalogParser.normalize(target.eventKeyword))) return null;
        // The observed reservation layout explicitly separates each performance from its sale row.
        // A free-standing detail/date label cannot establish which performance will open then.
        if (!ReservationSessionParser.isReservationPage(nativeItems)) return null;
        List<ReservationSessionParser.SessionCell> parsed = ReservationSessionParser.sessions(page.items);
        UiItem matching = null;
        for (UiItem cell : ReservationSessionParser.nativeSessionCells(nativeItems)) {
            UiItem performance = unique(nativeItems, "item_flowlayout", cell);
            UiItem sale = unique(nativeItems, "layout_time", cell);
            if (performance == null || sale == null || performance.bottom > sale.top) continue;
            ReservationSessionParser.SessionCell identity = ReservationSessionParser.matching(parsed, cell);
            boolean hasPerformanceOcr = page.items.stream().anyMatch(i -> i.source == UiItem.Source.OCR
                    && inside(cell, i, 0) && i.top >= performance.top - 3 && i.bottom <= Math.min(performance.bottom + 3, sale.top));
            boolean matches = identity != null ? target.date.equals(identity.date) && target.time.equals(identity.time)
                    : !hasPerformanceOcr && nativePerformanceMatches(nativeItems, performance, target);
            if (!matches) continue;
            if (matching != null) return null;
            matching = sale;
        }
        if (matching == null) return null;
        List<UiItem> nativeSale = lines(page.items, matching, UiItem.Source.NODE);
        List<UiItem> ocrSale = lines(page.items, matching, UiItem.Source.OCR);
        String raw = joined(ocrSale.isEmpty() ? nativeSale : ocrSale);
        // Native text can supplement an OCR row, but contradictory native evidence must reject it.
        if (!ocrSale.isEmpty()) for (UiItem item : nativeSale)
            if (!raw.contains(compact(item.text))) return null;
        if (!(raw.contains("开售") || raw.contains("开抢"))) return null;
        Matcher m = SALE.matcher(raw);
        if (!m.matches()) return null;
        try {
            int year = m.group(1) == null ? Instant.ofEpochMilli(now).atZone(CHINA).getYear() : Integer.parseInt(m.group(1));
            Instant at = LocalDateTime.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)))
                    .atZone(CHINA).toInstant();
            // Missing years never silently roll forward. The event date is not an opening date.
            return at.toEpochMilli() >= now && at.isBefore(target.eventStartsAt()) ? at : null;
        } catch (RuntimeException invalid) { return null; }
    }
    private static boolean nativePerformanceMatches(List<UiItem> items, UiItem region, MonitorConfig target) {
        Matcher m = PERFORMANCE.matcher(joined(lines(items, region, UiItem.Source.NODE)));
        if (!m.matches()) return false;
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))).toString().equals(target.date)
                    && LocalTime.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5))).toString().equals(target.time);
        } catch (RuntimeException invalid) { return false; }
    }
    private static boolean searchOrTour(UiItem i) {
        String id = i.resourceId;
        return id.startsWith(ID + "search_") || id.equals(ID + "header_search_v2_input")
                || id.equals(ID + "ll_search_item") || id.equals(ID + "tour_recyclerView")
                || id.equals(ID + "tour_project_container") || id.equals(ID + "ll_city_tour")
                || id.equals(ID + "tv_project_tourName");
    }
    private static UiItem unique(List<UiItem> items, String id, UiItem region) {
        UiItem found = null;
        for (UiItem i : items) if (i.source == UiItem.Source.NODE && i.resourceId.equals(ID + id) && inside(region, i, 0)) {
            if (found != null) return null;
            found = i;
        }
        return found;
    }
    private static List<UiItem> lines(List<UiItem> items, UiItem region, UiItem.Source source) {
        return items.stream().filter(i -> i.source == source && !i.text.isEmpty() && inside(region, i, source == UiItem.Source.OCR ? 3 : 0))
                .sorted(Comparator.comparingInt((UiItem i) -> i.top).thenComparingInt(i -> i.left)).collect(Collectors.toList());
    }
    private static String joined(List<UiItem> items) {
        Set<String> parts = new LinkedHashSet<>();
        for (UiItem i : items) parts.add(compact(i.text));
        return String.join("", parts);
    }
    private static String compact(String text) { return text.replaceAll("\\s+", ""); }
    private static boolean inside(UiItem region, UiItem item, int padding) {
        return region != null && item.hasBounds() && item.left >= region.left - padding && item.right <= region.right + padding
                && item.top >= region.top - padding && item.bottom <= region.bottom + padding;
    }
}
