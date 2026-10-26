package cn.local.tickethelper.adapter;

import cn.local.tickethelper.core.Observation;
import cn.local.tickethelper.core.PageFailureSignals;
import cn.local.tickethelper.core.UiItem;
import cn.local.tickethelper.catalog.CatalogSelection;
import cn.local.tickethelper.catalog.ReservationSessionParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Visible SKU adapter: node-scoped OCR and same-frame colour evidence, never inventory data. */
public final class DamaiSkuAdapter {
    private static final String ID = "cn.damai:id/";
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(20\\d{2})[-./年](\\d{1,2})[-./月](\\d{1,2})(?:日)?(?!\\d)");
    private static final Pattern TIME = Pattern.compile("(?<!\\d)([0-2]\\d):([0-5]\\d)(?!\\d)");
    private static final Pattern PRICE = Pattern.compile("(?<![\\d.])(\\d{2,6})\\s*元");
    private static final Pattern NEGATIVE = Pattern.compile("缺货|售罄|无票|已售完|暂不可购|不可选");

    /** Coordinates are original screen pixels, not OCR-crop-relative coordinates. */
    public interface Pixels { int width(); int height(); int argb(int x, int y); }
    public static final class Result {
        /** OCR items only; caller combines these with the original, same-frame node objects. */
        public final List<UiItem> items;
        public final Observation.VerifiedSelection evidence;
        /** Catalog-only identity evidence; it never changes the monitor's stricter stock/selection gate. */
        public final CatalogSelection catalogSelection;
        Result(List<UiItem> items, Observation.VerifiedSelection evidence) { this(items, evidence, null); }
        Result(List<UiItem> items, Observation.VerifiedSelection evidence, CatalogSelection catalogSelection) {
            this.items = items; this.evidence = evidence; this.catalogSelection = catalogSelection;
        }
    }
    private static final class Cell {
        final UiItem bounds; final List<UiItem> lines; final String text; final boolean selected;
        Cell(UiItem bounds, List<UiItem> lines, String text, boolean selected) {
            this.bounds = bounds; this.lines = lines; this.text = text; this.selected = selected;
        }
    }

    public static Result adapt(List<UiItem> nodes, List<UiItem> ocr, Pixels pixels, long observedAtMs) {
        List<UiItem> original = new ArrayList<>(ocr);
        List<UiItem> visible = new ArrayList<>(nodes); visible.addAll(ocr);
        if (PageFailureSignals.blocksSelection(visible)) return new Result(original, null);
        if (pixels == null || observedAtMs <= 0) return new Result(original, null);
        if (ReservationSessionParser.isReservationPage(visible)) {
            UiItem selectedBounds = null;
            for (UiItem bounds : ReservationSessionParser.nativeSessionCells(visible)) {
                if (!orangeBorder(pixels, bounds)) continue;
                // Count candidates before parsing; an unreadable second selection is still ambiguous.
                if (selectedBounds != null) return new Result(original, null);
                selectedBounds = bounds;
            }
            ReservationSessionParser.SessionCell selected = selectedBounds == null ? null
                    : ReservationSessionParser.matching(ReservationSessionParser.sessions(visible), selectedBounds);
            CatalogSelection selection = selected == null || !orangeSemanticText(pixels, selected.semanticLines, true)
                    ? null : new CatalogSelection(observedAtMs, selected.date, selected.time);
            // Reservation pages never enter the monitor's purchase/confirmation evidence path.
            return new Result(original, null, selection);
        }
        UiItem sessionRegion = unique(nodes, "project_detail_perform_flowlayout");
        UiItem priceRegion = unique(nodes, "project_detail_perform_price_flowlayout");
        if (sessionRegion == null || priceRegion == null || sessionRegion.bottom > priceRegion.top)
            return new Result(original, null);
        List<Cell> sessions = cells(nodes, ocr, pixels, sessionRegion, true);
        List<Cell> prices = cells(nodes, ocr, pixels, priceRegion, false);
        List<UiItem> adapted = new ArrayList<>(original);
        for (Cell cell : concat(sessions, prices)) {
            adapted.removeAll(cell.lines);
            // Visual selection stays in typed evidence; never masquerade OCR as a selected node.
            adapted.add(new UiItem(cell.text, cell.bounds.left, cell.bounds.top, cell.bounds.right, cell.bounds.bottom,
                    cell.bounds.enabled, false, false, UiItem.Source.OCR));
        }
        Cell selectedSession = onlySelected(sessions);
        if (selectedSession == null) return new Result(adapted, null);
        String[] session = parseSession(selectedSession.text);
        if (session == null) return new Result(adapted, null);
        CatalogSelection catalogSelection = new CatalogSelection(observedAtMs, session[0], session[1]);
        if (NEGATIVE.matcher(selectedSession.text).find()) return new Result(adapted, null, catalogSelection);
        Cell selectedPrice = onlySelected(prices);
        Integer price = selectedPrice == null || NEGATIVE.matcher(selectedPrice.text).find() ? null : parsePrice(selectedPrice.text);
        UiItem bottom = unique(nodes, "bottom_layout");
        Integer quantity = null, total = null;
        UiItem confirm = null;
        if (bottom != null) {
            UiItem numberArea = uniqueInside(nodes, "layout_num", bottom);
            UiItem count = numberArea == null ? null : uniqueInside(nodes, "tv_num", numberArea);
            UiItem numberTitle = numberArea == null ? null : uniqueInside(nodes, "tv_limit_num_title", numberArea);
            if (count != null && numberTitle != null && numberTitle.text.trim().equals("数量")) {
                Matcher match = Pattern.compile("^(\\d{1,3})\\s*张$").matcher(count.text.trim());
                if (match.matches() && ocrQuantityAgrees(count, ocr)) quantity = Integer.parseInt(match.group(1));
            }
            UiItem amount = uniqueInside(nodes, "tv_price", bottom);
            UiItem currency = uniqueInside(nodes, "tv_sku_price_prefix", bottom);
            if (amount != null && currency != null && currency.text.matches("[¥￥]")
                    && currency.right <= amount.left + 4 && Math.abs(currency.centerY() - amount.centerY()) <= Math.max(10, amount.bottom - amount.top)) {
                Integer value = parseWholeAmount(amount.text);
                if (value != null && ocrTotalAgrees(amount, currency, ocr, value)) total = value;
            }
            UiItem button = uniqueInside(nodes, "btn_buy_view", bottom);
            if (button != null && button.enabled && button.clickable && ocr.stream().anyMatch(line ->
                    line.text.trim().equals("确定") && encloses(button, line, 2))) confirm = button;
        }
        return new Result(adapted, new Observation.VerifiedSelection(observedAtMs, session[0], session[1], price, quantity, total, confirm), catalogSelection);
    }

    private static List<Cell> cells(List<UiItem> nodes, List<UiItem> ocr, Pixels pixels, UiItem region, boolean sessions) {
        List<Cell> cells = new ArrayList<>();
        for (UiItem node : nodes) {
            if (node.source != UiItem.Source.NODE || !node.resourceId.equals(ID + "ll_perform_item")
                    || !node.hasBounds() || !encloses(region, node, 0)) continue;
            List<UiItem> lines = new ArrayList<>();
            for (UiItem line : ocr) if (line.source == UiItem.Source.OCR && line.hasBounds() && encloses(node, line, 3)) lines.add(line);
            lines.sort(Comparator.comparingInt((UiItem item) -> item.top).thenComparingInt(item -> item.left));
            StringBuilder label = new StringBuilder();
            for (UiItem line : lines) { if (label.length() > 0) label.append(' '); label.append(line.text); }
            for (UiItem tag : nodes) {
                if (tag.resourceId.equals(ID + "tv_tag") && encloses(node, tag, 0) && !label.toString().contains(tag.text))
                    label.append(' ').append(tag.text);
            }
            String text = label.toString().trim();
            if (sessions ? parseSession(text) == null : parsePrice(text) == null) continue;
            boolean selected = orangeBorder(pixels, node) && orangeSemanticText(pixels, lines, sessions);
            cells.add(new Cell(node, lines, text, selected));
        }
        return cells;
    }

    private static boolean ocrQuantityAgrees(UiItem count, List<UiItem> lines) {
        for (UiItem line : lines) if (encloses(count, line, 6)
                && line.text.replaceAll("\\s+", "").equals(count.text.replaceAll("\\s+", ""))) return true;
        return false;
    }

    private static boolean ocrTotalAgrees(UiItem amount, UiItem currency, List<UiItem> lines, int value) {
        int height = amount.bottom - amount.top;
        for (UiItem line : lines) {
            int overlap = Math.min(amount.bottom, line.bottom) - Math.max(amount.top, line.top);
            if (overlap < Math.min(height, line.bottom - line.top) / 2
                    || line.left < currency.left - 8 || line.left > amount.left + height / 2
                    || line.right < amount.right - height / 2 || line.right > amount.right + height * 4) continue;
            Matcher digits = Pattern.compile("(?<![\\d.])(\\d{1,6}(?:\\.\\d{1,2})?)(?![\\d.])").matcher(line.text);
            if (!digits.find()) continue;
            Integer seen = parseWholeAmount(digits.group(1));
            if (seen != null && seen == value && !digits.find()) return true;
        }
        return false;
    }

    private static boolean orangeSemanticText(Pixels pixels, List<UiItem> lines, boolean session) {
        boolean hasSemanticText = false;
        for (UiItem line : lines) {
            boolean semantic = session ? DATE.matcher(line.text).find() || TIME.matcher(line.text).find() : PRICE.matcher(line.text).find();
            if (!semantic) continue;
            hasSemanticText = true;
            int orange = 0, dark = 0;
            for (int y = Math.max(0, line.top); y < Math.min(pixels.height(), line.bottom); y++) {
                for (int x = Math.max(0, line.left); x < Math.min(pixels.width(), line.right); x++) {
                    int color = pixels.argb(x, y);
                    if (orange(color)) orange++;
                    else if (darkInk(color)) dark++;
                }
            }
            // A small orange badge beside a black date must never count as orange selected text.
            if (orange < 18 || orange < (orange + dark) * .70) return false;
        }
        return hasSemanticText;
    }

    public static boolean orangeBorder(Pixels pixels, UiItem bounds) {
        if (bounds == null || !bounds.hasBounds() || bounds.left < 0 || bounds.top < 0
                || bounds.right > pixels.width() || bounds.bottom > pixels.height()) return false;
        int w = bounds.right - bounds.left, h = bounds.bottom - bounds.top;
        if (w < 70 || h < 35 || w > pixels.width() * .95 || h > pixels.height() * .25) return false;
        int band = Math.max(2, Math.min(8, h / 25));
        int xInset = Math.max(band + 1, Math.min(w / 5, h / 3));
        int yInset = Math.max(band + 1, h / 5);
        return horizontalCoverage(pixels, bounds.left + xInset, bounds.right - xInset, bounds.top, band) >= .80
                && horizontalCoverage(pixels, bounds.left + xInset, bounds.right - xInset, bounds.bottom - band, band) >= .80
                && verticalCoverage(pixels, bounds.top + yInset, bounds.bottom - yInset, bounds.left, band) >= .80
                && verticalCoverage(pixels, bounds.top + yInset, bounds.bottom - yInset, bounds.right - band, band) >= .80
                && interiorOrangeRatio(pixels, bounds, band) < .12;
    }

    private static double horizontalCoverage(Pixels p, int from, int to, int top, int band) {
        int count = 0;
        for (int x = from; x < to; x++) { for (int y = top; y < top + band; y++) if (orange(p.argb(x, y))) { count++; break; } }
        return count / (double) Math.max(1, to - from);
    }
    private static double verticalCoverage(Pixels p, int from, int to, int left, int band) {
        int count = 0;
        for (int y = from; y < to; y++) { for (int x = left; x < left + band; x++) if (orange(p.argb(x, y))) { count++; break; } }
        return count / (double) Math.max(1, to - from);
    }
    private static double interiorOrangeRatio(Pixels p, UiItem b, int band) {
        int total = 0, count = 0, gap = Math.max(2, band * 2);
        for (int y = b.top + gap; y < b.bottom - gap; y += 3) for (int x = b.left + gap; x < b.right - gap; x += 3) {
            total++; if (orange(p.argb(x, y))) count++;
        }
        return count / (double) Math.max(1, total);
    }
    private static boolean orange(int color) {
        int a = color >>> 24, r = color >>> 16 & 255, g = color >>> 8 & 255, b = color & 255;
        return a >= 200 && r >= 200 && g >= 40 && g <= 185 && b <= 105 && r - g >= 45 && g - b >= 20;
    }
    private static boolean darkInk(int color) {
        int a = color >>> 24, r = color >>> 16 & 255, g = color >>> 8 & 255, b = color & 255;
        return a >= 200 && Math.max(r, Math.max(g, b)) < 190;
    }
    private static String[] parseSession(String text) {
        Matcher date = DATE.matcher(text), time = TIME.matcher(text);
        if (!date.find() || !time.find()) return null;
        try {
            LocalDate d = LocalDate.of(Integer.parseInt(date.group(1)), Integer.parseInt(date.group(2)), Integer.parseInt(date.group(3)));
            LocalTime t = LocalTime.of(Integer.parseInt(time.group(1)), Integer.parseInt(time.group(2)));
            if (date.find() || time.find()) return null;
            return new String[]{d.toString(), t.toString()};
        } catch (RuntimeException invalid) { return null; }
    }
    private static Integer parsePrice(String text) {
        Matcher matcher = PRICE.matcher(text);
        if (!matcher.find()) return null;
        try { int value = Integer.parseInt(matcher.group(1)); return matcher.find() ? null : value; }
        catch (NumberFormatException invalid) { return null; }
    }
    private static Integer parseWholeAmount(String text) {
        if (!text.matches("\\d{1,6}(?:\\.0{1,2})?")) return null;
        try { return new BigDecimal(text).intValueExact(); } catch (RuntimeException invalid) { return null; }
    }
    private static UiItem unique(List<UiItem> nodes, String id) {
        UiItem found = null;
        for (UiItem item : nodes) if (item.source == UiItem.Source.NODE && item.hasBounds() && item.resourceId.equals(ID + id)) {
            if (found != null) return null; found = item;
        }
        return found;
    }
    private static UiItem uniqueInside(List<UiItem> nodes, String id, UiItem parent) {
        UiItem item = unique(nodes, id); return item != null && encloses(parent, item, 0) ? item : null;
    }
    private static boolean encloses(UiItem outer, UiItem inner, int tolerance) {
        return inner.hasBounds() && outer.left - tolerance <= inner.left && outer.top - tolerance <= inner.top
                && outer.right + tolerance >= inner.right && outer.bottom + tolerance >= inner.bottom;
    }
    private static Cell onlySelected(List<Cell> cells) {
        Cell selected = null;
        for (Cell cell : cells) if (cell.selected) { if (selected != null) return null; selected = cell; }
        return selected;
    }
    private static List<Cell> concat(List<Cell> first, List<Cell> second) { List<Cell> all = new ArrayList<>(first); all.addAll(second); return all; }
}
