package cn.local.tickethelper.catalog;

import cn.local.tickethelper.core.UiItem;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catalog-only interpretation of the observed reservation SKU, never purchase evidence. */
public final class ReservationSessionParser {
    private static final String ID = "cn.damai:id/";
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(20\\d{2})[-./年](\\d{1,2})[-./月](\\d{1,2})(?:日)?(?!\\d)");
    private static final Pattern TIME = Pattern.compile("(?<!\\d)([0-2]\\d):([0-5]\\d)(?!\\d)");
    private static final Pattern SHORT_DATE = Pattern.compile("(?<![\\d./-])(\\d{1,2})[./月-](\\d{1,2})(?:日)?(?!\\d)");
    private static final Pattern NATIVE_TIME = Pattern.compile("(?<!\\d)([0-2]?\\d)[:：]([0-5]\\d)(?!\\d)");
    private static final Pattern SALE = Pattern.compile("(?:20\\d{2}[-./年])?(?:0?[1-9]|1[0-2])[月./-](?:0?[1-9]|[12]\\d|3[01])日?(?:[01]?\\d|2[0-3])[:：][0-5]\\d(?:开抢|开售)");

    public static final class SessionCell {
        public final UiItem bounds;
        public final List<UiItem> semanticLines;
        public final String date, time;
        private SessionCell(UiItem bounds, List<UiItem> lines, String date, String time) {
            this.bounds = bounds; this.semanticLines = List.copyOf(lines); this.date = date; this.time = time;
        }
    }
    private ReservationSessionParser() { }

    /** Exact native marker: OCR text or a sale timestamp alone cannot enable this exception. */
    public static boolean isReservationPage(List<UiItem> items) {
        UiItem heading = unique(items, "tv_perform_name", null);
        UiItem region = unique(items, "project_detail_perform_flowlayout", null);
        UiItem title = unique(items, "title_tv", null);
        return heading != null && heading.text.equals("预约想看场次") && region != null
                && title != null && title.text.length() >= 5 && !CatalogParser.city(title.text).isEmpty()
                && title.bottom <= heading.top && heading.bottom <= region.top;
    }

    /** Unfiltered native cells let selection reject a second orange cell even if its text is unreadable. */
    public static List<UiItem> nativeSessionCells(List<UiItem> items) {
        if (!isReservationPage(items) || !CatalogParser.blockedReason(items).isEmpty()) return List.of();
        UiItem region = unique(items, "project_detail_perform_flowlayout", null);
        List<UiItem> result = new ArrayList<>();
        for (UiItem item : items) if (nativeId(item, "ll_perform_item") && inside(region, item, 0)) result.add(item);
        return List.copyOf(result);
    }

    public static List<SessionCell> sessions(List<UiItem> items) {
        List<SessionCell> result = new ArrayList<>();
        for (UiItem cell : nativeSessionCells(items)) {
            UiItem performance = unique(items, "item_flowlayout", cell);
            UiItem sale = unique(items, "layout_time", cell);
            if (performance == null || sale == null || performance.bottom > sale.top) continue;
            List<UiItem> identity = performanceLines(items, cell, performance, sale), saleLines = ocrInside(items, sale);
            String text = joined(identity), saleText = joined(saleLines).replaceAll("\\s+", "");
            // Only the named lower sale row may contain the second date/time. Do not pick the first match.
            if (!SALE.matcher(saleText).matches() || text.contains("开抢") || text.contains("开售")) continue;
            boolean strayDate = false;
            for (UiItem item : items) {
                if (item.source != UiItem.Source.OCR || !inside(cell, item, 3)
                        || inPerformanceBand(cell, performance, sale, item) || inside(sale, item, 3)) continue;
                if (DATE.matcher(item.text).find() || TIME.matcher(item.text).find()) { strayDate = true; break; }
            }
            if (strayDate) continue;
            Matcher date = DATE.matcher(text), time = TIME.matcher(text);
            if (!date.find() || !time.find()) continue;
            try {
                String d = LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3))).toString();
                String t = LocalTime.of(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2))).toString();
                if (date.find() || time.find() || !nativeIdentityAgrees(items, cell, performance, sale, d, t)) continue;
                result.add(new SessionCell(cell, identity, d, t));
            } catch (RuntimeException invalid) { /* Ambiguous or invalid dates stay unreadable. */ }
        }
        return List.copyOf(result);
    }

    private static boolean nativeIdentityAgrees(List<UiItem> items, UiItem cell, UiItem performance, UiItem sale, String date, String time) {
        LocalDate expected = LocalDate.parse(date);
        for (UiItem item : items) {
            if (item.source != UiItem.Source.NODE || item.text.isEmpty() || !inPerformanceBand(cell, performance, sale, item)) continue;
            if (item.text.contains("开抢") || item.text.contains("开售")) return false;
            Matcher dates = DATE.matcher(item.text), times = NATIVE_TIME.matcher(item.text), shortDates = SHORT_DATE.matcher(item.text);
            while (dates.find()) {
                try {
                    if (!LocalDate.of(Integer.parseInt(dates.group(1)), Integer.parseInt(dates.group(2)),
                            Integer.parseInt(dates.group(3))).toString().equals(date)) return false;
                } catch (RuntimeException invalid) { return false; }
            }
            while (shortDates.find()) {
                if (Integer.parseInt(shortDates.group(1)) != expected.getMonthValue()
                        || Integer.parseInt(shortDates.group(2)) != expected.getDayOfMonth()) return false;
            }
            while (times.find()) {
                try {
                    if (!LocalTime.of(Integer.parseInt(times.group(1)), Integer.parseInt(times.group(2))).toString().equals(time)) return false;
                } catch (RuntimeException invalid) { return false; }
            }
        }
        return true;
    }

    public static SessionCell matching(List<SessionCell> sessions, UiItem bounds) {
        SessionCell found = null;
        for (SessionCell session : sessions) if (session.bounds.left == bounds.left && session.bounds.top == bounds.top
                && session.bounds.right == bounds.right && session.bounds.bottom == bounds.bottom) {
            if (found != null) return null;
            found = session;
        }
        return found;
    }
    private static List<UiItem> performanceLines(List<UiItem> items, UiItem cell, UiItem performance, UiItem sale) {
        List<UiItem> lines = new ArrayList<>();
        for (UiItem item : items) if (item.source == UiItem.Source.OCR && !item.text.isEmpty()
                && inPerformanceBand(cell, performance, sale, item)) lines.add(item);
        lines.sort(Comparator.comparingInt((UiItem item) -> item.top).thenComparingInt(item -> item.left));
        return lines;
    }
    /** OCR may overhang the inner row horizontally, but never its ticket cell or the sale row below. */
    private static boolean inPerformanceBand(UiItem cell, UiItem performance, UiItem sale, UiItem line) {
        return inside(cell, line, 0) && line.top >= performance.top - 3
                && line.bottom <= Math.min(performance.bottom + 3, sale.top);
    }

    private static List<UiItem> ocrInside(List<UiItem> items, UiItem bounds) {
        List<UiItem> lines = new ArrayList<>();
        for (UiItem item : items) if (item.source == UiItem.Source.OCR && !item.text.isEmpty() && inside(bounds, item, 3)) lines.add(item);
        lines.sort(Comparator.comparingInt((UiItem item) -> item.top).thenComparingInt(item -> item.left));
        return lines;
    }
    private static String joined(List<UiItem> lines) {
        StringBuilder text = new StringBuilder();
        for (UiItem line : lines) { if (text.length() > 0) text.append(' '); text.append(line.text); }
        return text.toString();
    }
    private static UiItem unique(List<UiItem> items, String id, UiItem parent) {
        UiItem match = null;
        for (UiItem item : items) if (nativeId(item, id) && (parent == null || inside(parent, item, 0))) {
            if (match != null) return null;
            match = item;
        }
        return match;
    }
    private static boolean nativeId(UiItem item, String id) { return item.source == UiItem.Source.NODE && item.hasBounds() && item.resourceId.equals(ID + id); }
    private static boolean inside(UiItem parent, UiItem child, int tolerance) {
        return parent != null && child.hasBounds() && child.left >= parent.left - tolerance && child.top >= parent.top - tolerance
                && child.right <= parent.right + tolerance && child.bottom <= parent.bottom + tolerance;
    }
}
