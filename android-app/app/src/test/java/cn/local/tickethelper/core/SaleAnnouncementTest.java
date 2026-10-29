package cn.local.tickethelper.core;

import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

/** Synthetic regression cases for the observed reservation structure; not a production UI profile. */
public final class SaleAnnouncementTest {
    private static final String ID = "cn.damai:id/";
    private final long now = Instant.parse("2026-10-30T00:00:00Z").toEpochMilli();
    private final MonitorConfig target = new MonitorConfig("测试歌手", "长沙", "2026-11-06", "19:00", List.of(1680), 2, 3360,
            Instant.parse("2026-11-06T11:00:00Z"), 500, 1500, MonitorConfig.Mode.PREPARE);
    private UiItem item(String text, String id, int top, int bottom, UiItem.Source source) {
        return new UiItem(text, 40, top, 560, bottom, true, false, false, source, id.isEmpty() ? "" : ID + id);
    }
    private UiItem node(String text, String id, int top, int bottom) { return item(text, id, top, bottom, UiItem.Source.NODE); }
    private List<UiItem> page() {
        return new ArrayList<>(List.of(node("[长沙]测试歌手2026巡回演唱会", "title_tv", 50, 110),
                node("预约想看场次", "tv_perform_name", 130, 160),
                node("", "project_detail_perform_flowlayout", 180, 1000)));
    }
    private void cell(List<UiItem> rows, String performance, String sale, int offset, UiItem.Source source) {
        rows.add(node("", "ll_perform_item", 200 + offset, 400 + offset));
        rows.add(node("", "item_flowlayout", 220 + offset, 290 + offset));
        rows.add(node("", "layout_time", 310 + offset, 370 + offset));
        rows.add(item(performance, "", 230 + offset, 270 + offset, source));
        if (!sale.isEmpty()) rows.add(item(sale, "", 320 + offset, 360 + offset, source));
    }
    private Instant read(List<UiItem> rows) { return SaleAnnouncement.read(new Observation("cn.damai", rows, now), target, now); }
    private List<UiItem> normal(UiItem.Source source) {
        List<UiItem> rows = page(); cell(rows, "2026-11-06 周五 19:00", "10月31日15:00开抢", 0, source); return rows;
    }
    @Test public void nativeTargetAndExplicitFutureOpeningAreBoundToSameCell() {
        assertEquals(Instant.parse("2026-10-31T07:00:00Z"), read(normal(UiItem.Source.NODE)));
    }
    @Test public void observedReservationGeometrySupportsLocalOcrAndSplitSaleRow() {
        List<UiItem> rows = normal(UiItem.Source.OCR);
        assertEquals(Instant.parse("2026-10-31T07:00:00Z"), read(rows));
        rows.remove(rows.size() - 1);
        rows.add(item("10月31日", "", 315, 335, UiItem.Source.OCR));
        rows.add(item("15:00 开抢", "", 340, 365, UiItem.Source.OCR));
        assertEquals(Instant.parse("2026-10-31T07:00:00Z"), read(rows));
    }
    @Test public void nativeSplitRowsAndDuplicateTextDoNotRequireManualInput() {
        List<UiItem> rows = normal(UiItem.Source.NODE); rows.remove(rows.size() - 1);
        rows.add(node("开售时间：", "", 312, 325)); rows.add(node("2026年10月31日", "", 326, 340));
        rows.add(node("15：00：00", "", 341, 360)); rows.add(node("15：00：00", "", 341, 360));
        assertEquals(Instant.parse("2026-10-31T07:00:00Z"), read(rows));
    }
    @Test public void differentSessionOpeningNeverOverridesTargetSession() {
        List<UiItem> rows = normal(UiItem.Source.OCR);
        cell(rows, "2026-11-07 周六 19:00", "11月1日12:00开抢", 250, UiItem.Source.OCR);
        assertEquals(Instant.parse("2026-10-31T07:00:00Z"), read(rows));
        rows.removeIf(i -> i.text.equals("10月31日15:00开抢"));
        assertNull(read(rows));
    }
    @Test public void targetDateAndTimeMustBothMatch() {
        for (String performance : List.of("2026-11-07 19:00", "2026-11-06 20:00", "11-06 19:00")) {
            List<UiItem> rows = page(); cell(rows, performance, "10月31日15:00开抢", 0, UiItem.Source.NODE);
            assertNull(performance, read(rows));
        }
    }
    @Test public void searchAndTourPagesCannotBorrowTargetIdentity() {
        for (String marker : List.of("search_v2_container", "header_search_v2_input", "ll_search_item", "tour_recyclerView", "ll_city_tour")) {
            List<UiItem> rows = normal(UiItem.Source.NODE); rows.add(node("", marker, 10, 40));
            assertNull(marker, read(rows));
        }
        List<UiItem> rows = page(); rows.add(node("另一歌手 10月31日15:00开抢", "", 300, 350));
        assertNull(read(rows));
    }
    @Test public void otherProjectOrCityAndAmbiguousHeaderAreRejected() {
        for (String title : List.of("[长沙]另一歌手演唱会", "[杭州]测试歌手演唱会")) {
            List<UiItem> rows = normal(UiItem.Source.NODE); rows.set(0, node(title, "title_tv", 50, 110)); assertNull(read(rows));
        }
        List<UiItem> rows = normal(UiItem.Source.NODE); rows.add(node("[长沙]另一演唱会", "tv_project_name", 1050, 1100));
        assertNull(read(rows));
    }
    @Test public void repeatedTargetCellsAndUnscopedSaleLabelsAreRejected() {
        List<UiItem> rows = normal(UiItem.Source.NODE);
        cell(rows, "2026-11-06 19:00", "10月31日15:00开抢", 250, UiItem.Source.NODE); assertNull(read(rows));
        rows = normal(UiItem.Source.NODE); rows.removeIf(i -> i.resourceId.equals(ID + "layout_time")); assertNull(read(rows));
        rows = normal(UiItem.Source.NODE); rows.removeIf(i -> i.text.contains("开抢"));
        rows.add(node("10月31日15:00开抢", "", 1050, 1100)); assertNull(read(rows));
    }
    @Test public void originalObservationPackageAndAgeAreRequired() {
        List<UiItem> rows = normal(UiItem.Source.NODE);
        assertNull(SaleAnnouncement.read(new Observation("other", rows, now), target, now));
        assertNull(SaleAnnouncement.read(new Observation("cn.damai", rows, now - 5001), target, now));
        assertNull(SaleAnnouncement.read(new Observation("cn.damai", rows, now + 1), target, now));
    }
    @Test public void restrictedPagesAndOcrOnlyInventedLayoutAreRejected() {
        for (String blocker : List.of("确认购买", "验证码", "请完成验证", "待付款")) {
            List<UiItem> rows = normal(UiItem.Source.NODE); rows.add(node(blocker, "", 1050, 1100)); assertNull(blocker, read(rows));
        }
        List<UiItem> rows = new ArrayList<>();
        for (UiItem i : normal(UiItem.Source.OCR)) rows.add(new UiItem(i.text, i.left, i.top, i.right, i.bottom,
                i.enabled, i.clickable, i.selected, UiItem.Source.OCR, i.resourceId));
        assertNull(read(rows));
    }
    @Test public void contradictoryNativeAndOcrEvidenceCannotChooseConvenientTime() {
        List<UiItem> rows = normal(UiItem.Source.OCR); rows.add(node("10月31日16:00开抢", "", 320, 360)); assertNull(read(rows));
        rows = normal(UiItem.Source.OCR); rows.add(node("2026-11-07 19:00", "", 230, 270)); assertNull(read(rows));
    }
    @Test public void pastInvalidAmbiguousOrPostPerformanceDatesNeverBecomeOpening() {
        for (String sale : List.of("10月29日15:00开抢", "11月31日15:00开抢", "11月6日19:00开抢",
                "10月31日15:00开抢11月1日15:00开抢", "明天15:00开抢", "10月31日15:00", "2026年10月31日25:00开抢")) {
            List<UiItem> rows = page(); cell(rows, "2026-11-06 19:00", sale, 0, UiItem.Source.NODE); assertNull(sale, read(rows));
        }
    }
}
