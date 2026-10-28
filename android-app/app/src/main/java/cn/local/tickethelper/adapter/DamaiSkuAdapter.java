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
    /** Closed local diagnostic only; it contains no captured text, price or page identifiers. */
    public enum TotalCheckCode {
        NOT_CHECKED, BOTTOM_MISSING, AMOUNT_MISSING, CURRENCY_MISSING,
        NATIVE_SHAPE_INVALID, NATIVE_AMOUNT_INVALID,
        OCR_NO_OVERLAP, OCR_GEOMETRY_MISMATCH, OCR_NUMBER_MISSING,
        OCR_NUMBER_INVALID, OCR_VALUE_MISMATCH, OCR_MULTIPLE_VALUES, VERIFIED
    }
    public static final class Result {
        /** OCR items only; caller combines these with the original, same-frame node objects. */
        public final List<UiItem> items;
        public final Observation.VerifiedSelection evidence;
        /** Catalog-only identity evidence; it never changes the monitor's stricter stock/selection gate. */
        public final CatalogSelection catalogSelection;
        public final TotalCheckCode totalCheckCode;
        Result(List<UiItem> items, Observation.VerifiedSelection evidence) { this(items, evidence, null); }
        Result(List<UiItem> items, Observation.VerifiedSelection evidence, CatalogSelection catalogSelection) {
            this(items, evidence, catalogSelection, TotalCheckCode.NOT_CHECKED);
        }
        Result(List<UiItem> items, Observation.VerifiedSelection evidence, CatalogSelection catalogSelection,
               TotalCheckCode totalCheckCode) {
            this.items = items; this.evidence = evidence; this.catalogSelection = catalogSelection;
            this.totalCheckCode = totalCheckCode;
        }
    }
    private static final class Cell {
        final UiItem bounds; final List<UiItem> lines; final String text; final boolean selected;
        Cell(UiItem bounds, List<UiItem> lines, String text, boolean selected) {
            this.bounds = bounds; this.lines = lines; this.text = text; this.selected = selected;
        }
    }

    public static Result adapt(List<UiItem> nodes, List<UiItem> ocr, Pixels pixels, long observedAtMs) {
        return adapt(nodes, ocr, null, pixels, observedAtMs);
    }

    /**
     * Debug-local geometry only. This does not participate in evidence or action decisions.
     * The caller must gate logging to a debug build with a selected price and unknown total.
     * No input text or amount is copied into the returned fixed-field description.
     */
    public static String totalGeometryDiagnostic(List<UiItem> nodes, List<UiItem> elements, int screenWidth) {
        UiItem bottom = unique(nodes, "bottom_layout");
        UiItem amount = bottom == null ? null : uniqueInside(nodes, "tv_price", bottom);
        UiItem currency = bottom == null ? null : uniqueInside(nodes, "tv_sku_price_prefix", bottom);
        StringBuilder out = new StringBuilder("geometry amount=").append(diagnosticBox(amount))
                .append(" currency=").append(diagnosticBox(currency));
        if (amount == null || elements == null || screenWidth <= 0 || screenWidth > 20000) return out.toString();
        Integer nativeValue = parseWholeAmount(amount.text);
        List<UiItem> nearby = new ArrayList<>();
        for (UiItem element : elements) {
            if (element.source != UiItem.Source.OCR || !element.hasBounds()
                    || element.left >= screenWidth / 2 || element.right > screenWidth) continue;
            int overlap = Math.min(amount.bottom, element.bottom) - Math.max(amount.top, element.top);
            if (overlap >= Math.min(amount.bottom - amount.top, element.bottom - element.top) / 2)
                nearby.add(element);
        }
        nearby.sort(Comparator.comparingInt((UiItem item) -> item.left).thenComparingInt(item -> item.top));
        for (int i = 0; i < Math.min(4, nearby.size()); i++) {
            UiItem element = nearby.get(i);
            Matcher full = Pattern.compile("^[¥￥]?\\s*(\\d{1,6}(?:\\.\\d{1,2})?)$").matcher(element.text.trim());
            boolean fullNumber = full.matches();
            Integer parsed = fullNumber ? parseWholeAmount(full.group(1)) : null;
            boolean hasDigit = Pattern.compile("[0-9]").matcher(element.text).find();
            String category = fullNumber ? "COMPLETE_NUMBER" : element.text.trim().matches("[¥￥]") ? "CURRENCY"
                    : hasDigit ? "MIXED_NUMBER" : "OTHER";
            out.append(" e").append(i).append('=').append(diagnosticBox(element))
                    .append(" chars=").append(Math.min(64, element.text.codePointCount(0, element.text.length())))
                    .append(" full=").append(fullNumber)
                    .append(" equal=").append(parsed != null && parsed.equals(nativeValue))
                    .append(" kind=").append(category);
        }
        return out.toString();
    }

    private static String diagnosticBox(UiItem item) {
        return item == null ? "missing" : "[" + item.left + ',' + item.top + ',' + item.right + ',' + item.bottom + ']';
    }

    /**
     * amountElements are separate word tokens from the same OCR result/frame. They are used only
     * for native-scoped total verification and never added to semantic lines or returned items.
     * Null retains the legacy line-only API; the live reader always supplies its token collection.
     */
    public static Result adapt(List<UiItem> nodes, List<UiItem> ocr, List<UiItem> amountElements,
                               Pixels pixels, long observedAtMs) {
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
        if (sessionRegion == null || (priceRegion != null && sessionRegion.bottom > priceRegion.top))
            return new Result(original, null);
        // A true session-only selector has no price cells. A price cell without its named scope
        // remains a malformed/ambiguous page, rather than becoming new unscoped price authority.
        if (priceRegion == null && nodes.stream().anyMatch(cell -> cell.source == UiItem.Source.NODE
                && cell.resourceId.equals(ID + "ll_perform_item") && !encloses(sessionRegion, cell, 0)
                && ocr.stream().anyMatch(line -> encloses(cell, line, 3) && parsePrice(line.text) != null)))
            return new Result(original, null);
        List<Cell> sessions = cells(nodes, ocr, pixels, sessionRegion, true);
        // The official selector initially contains only dates; prices appear after one is chosen.
        List<Cell> prices = priceRegion == null ? new ArrayList<>() : cells(nodes, ocr, pixels, priceRegion, false);
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
        if (NEGATIVE.matcher(selectedSession.text).find()) {
            // A selected date can remain visually selected while all its stock is unavailable.
            // Preserve only its same-frame identity and negative signal, never purchase evidence.
            return new Result(adapted, new Observation.VerifiedSelection(observedAtMs,
                    session[0], session[1], null, null, null, null, null, true), catalogSelection);
        }
        if (priceRegion == null) return new Result(adapted, new Observation.VerifiedSelection(observedAtMs,
                session[0], session[1], null, null, null, null), catalogSelection);
        Cell selectedPrice = onlySelected(prices);
        Integer price = selectedPrice == null || NEGATIVE.matcher(selectedPrice.text).find() ? null : parsePrice(selectedPrice.text);
        UiItem bottom = unique(nodes, "bottom_layout");
        Integer quantity = null, total = null;
        TotalCheckCode totalCheckCode = TotalCheckCode.BOTTOM_MISSING;
        UiItem confirm = null, increase = null;
        if (bottom != null) {
            UiItem numberArea = uniqueInside(nodes, "layout_num", bottom);
            UiItem count = numberArea == null ? null : uniqueInside(nodes, "tv_num", numberArea);
            UiItem numberTitle = numberArea == null ? null : uniqueInside(nodes, "tv_limit_num_title", numberArea);
            if (count != null && numberTitle != null && numberTitle.text.trim().equals("数量")) {
                Matcher match = Pattern.compile("^(\\d{1,3})\\s*张$").matcher(count.text.trim());
                if (match.matches() && ocrQuantityAgrees(count, ocr)) quantity = Integer.parseInt(match.group(1));
            }
            // Real historical native captures use this exact stepper: img_jian / tv_num / img_jia.
            // The service retains these empty nodes only after checking their native class/parent path.
            UiItem stepper = numberArea == null ? null : uniqueInside(nodes, "layout_sku_num_ll", numberArea);
            UiItem plus = stepper == null ? null : uniqueInside(nodes, "img_jia", stepper);
            UiItem minus = stepper == null ? null : uniqueInside(nodes, "img_jian", stepper);
            if (quantity != null && quantity == 1 && count != null && stepper != null
                    && encloses(stepper, count, 0) && plus != null && minus != null
                    && plus.enabled && plus.clickable && plus.text.isEmpty() && minus.text.isEmpty()
                    && !minus.enabled && minus.right <= count.left && count.right <= plus.left
                    && Math.abs(plus.centerY() - count.centerY()) <= Math.max(plus.bottom - plus.top, count.bottom - count.top) / 2
                    && Math.abs(minus.centerY() - count.centerY()) <= Math.max(minus.bottom - minus.top, count.bottom - count.top) / 2)
                increase = plus;
            UiItem amount = uniqueInside(nodes, "tv_price", bottom);
            UiItem currency = uniqueInside(nodes, "tv_sku_price_prefix", bottom);
            totalCheckCode = amount == null ? TotalCheckCode.AMOUNT_MISSING
                    : currency == null ? TotalCheckCode.CURRENCY_MISSING : TotalCheckCode.NATIVE_SHAPE_INVALID;
            if (amount != null && currency != null && currency.text.matches("[¥￥]")
                    && currency.right <= amount.left + 4 && Math.abs(currency.centerY() - amount.centerY()) <= Math.max(10, amount.bottom - amount.top)) {
                Integer value = parseWholeAmount(amount.text);
                totalCheckCode = value == null ? TotalCheckCode.NATIVE_AMOUNT_INVALID
                        : amountElements == null ? ocrTotalCheck(amount, currency, ocr, value)
                        : ocrAmountElementsCheck(amount, currency, amountElements, value);
                if (totalCheckCode == TotalCheckCode.VERIFIED) total = value;
            }
            UiItem button = uniqueInside(nodes, "btn_buy_view", bottom);
            if (button != null && button.enabled && button.clickable && ocr.stream().anyMatch(line ->
                    line.text.trim().equals("确定") && encloses(button, line, 2))) confirm = button;
        }
        if (price == null || quantity == null || quantity != 1 || total == null || !total.equals(price) || confirm == null) increase = null;
        return new Result(adapted, new Observation.VerifiedSelection(observedAtMs, session[0], session[1], price, quantity, total, confirm, increase), catalogSelection, totalCheckCode);
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

    private static TotalCheckCode ocrTotalCheck(UiItem amount, UiItem currency, List<UiItem> lines, int value) {
        int height = amount.bottom - amount.top;
        boolean overlapSeen = false, geometrySeen = false, numberSeen = false;
        boolean invalidNumber = false, mismatch = false, multiple = false;
        for (UiItem line : lines) {
            int overlap = Math.min(amount.bottom, line.bottom) - Math.max(amount.top, line.top);
            if (overlap < Math.min(height, line.bottom - line.top) / 2) continue;
            overlapSeen = true;
            if (line.left < currency.left - 8 || line.left > amount.left + height / 2
                    || line.right < amount.right - height / 2 || line.right > amount.right + height * 4) continue;
            geometrySeen = true;
            Matcher digits = Pattern.compile("(?<![\\d.])(\\d{1,6}(?:\\.\\d{1,2})?)(?![\\d.])").matcher(line.text);
            if (!digits.find()) continue;
            numberSeen = true;
            Integer seen = parseWholeAmount(digits.group(1));
            boolean extraNumber = digits.find();
            if (seen != null && seen == value && !extraNumber) return TotalCheckCode.VERIFIED;
            invalidNumber |= seen == null;
            mismatch |= seen != null && seen != value;
            multiple |= extraNumber;
        }
        return !overlapSeen ? TotalCheckCode.OCR_NO_OVERLAP
                : !geometrySeen ? TotalCheckCode.OCR_GEOMETRY_MISMATCH
                : !numberSeen ? TotalCheckCode.OCR_NUMBER_MISSING
                : multiple ? TotalCheckCode.OCR_MULTIPLE_VALUES
                : invalidNumber ? TotalCheckCode.OCR_NUMBER_INVALID
                : mismatch ? TotalCheckCode.OCR_VALUE_MISMATCH : TotalCheckCode.NOT_CHECKED;
    }

    private static TotalCheckCode ocrAmountElementsCheck(UiItem amount, UiItem currency,
                                                         List<UiItem> elements, int value) {
        int height = amount.bottom - amount.top;
        List<UiItem> candidates = new ArrayList<>();
        boolean overlapSeen = false, invalidGeometry = false, unexpectedText = false;
        for (UiItem element : elements) {
            if (element.source != UiItem.Source.OCR || !element.hasBounds()) continue;
            int overlapY = Math.min(amount.bottom, element.bottom) - Math.max(amount.top, element.top);
            int overlapX = Math.min(amount.right, element.right) - Math.max(amount.left, element.left);
            if (overlapX <= 0 || overlapY < Math.min(height, element.bottom - element.top) / 2) continue;
            overlapSeen = true;
            if (element.text.trim().matches("[¥￥]")) continue;
            // Only the currency/amount boxes, plus a small fixed OCR edge tolerance, belong here.
            // A row spanning price details or the confirmation button is never an amount token.
            if (element.left < currency.left - 6 || element.right > amount.right + 6
                    || element.top < Math.min(amount.top, currency.top) - 6
                    || element.bottom > Math.max(amount.bottom, currency.bottom) + 6) {
                invalidGeometry = true; continue;
            }
            if (!Pattern.compile("[0-9]").matcher(element.text).find()) {
                unexpectedText = true; continue;
            }
            candidates.add(element);
        }
        if (invalidGeometry) return TotalCheckCode.OCR_GEOMETRY_MISMATCH;
        if (candidates.size() > 1) return TotalCheckCode.OCR_MULTIPLE_VALUES;
        if (unexpectedText) return TotalCheckCode.OCR_NUMBER_INVALID;
        if (candidates.isEmpty()) return overlapSeen ? TotalCheckCode.OCR_NUMBER_MISSING : TotalCheckCode.OCR_NO_OVERLAP;
        UiItem token = candidates.get(0);
        if (token.left > amount.left + height / 2 || token.right < amount.right - height / 2)
            return TotalCheckCode.OCR_GEOMETRY_MISMATCH;
        Matcher numbers = Pattern.compile("(?<![\\d.])(\\d{1,6}(?:\\.\\d{1,2})?)(?![\\d.])").matcher(token.text);
        if (!numbers.find()) return TotalCheckCode.OCR_NUMBER_INVALID;
        if (numbers.find()) return TotalCheckCode.OCR_MULTIPLE_VALUES;
        Matcher whole = Pattern.compile("^[¥￥]?\\s*(\\d{1,6}(?:\\.\\d{1,2})?)$").matcher(token.text.trim());
        if (!whole.matches()) return TotalCheckCode.OCR_NUMBER_INVALID;
        Integer seen = parseWholeAmount(whole.group(1));
        return seen == null ? TotalCheckCode.OCR_NUMBER_INVALID
                : seen == value ? TotalCheckCode.VERIFIED : TotalCheckCode.OCR_VALUE_MISMATCH;
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
