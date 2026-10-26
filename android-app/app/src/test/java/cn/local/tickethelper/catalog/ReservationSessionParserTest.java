package cn.local.tickethelper.catalog;

import cn.local.tickethelper.adapter.DamaiSkuAdapter;
import cn.local.tickethelper.core.UiItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic public labels and geometry, without captured device/account data. */
public final class ReservationSessionParserTest {
    private static final String ID = "cn.damai:id/";
    private static final int ORANGE = 0xffff5500, BLACK = 0xff222222;
    private List<UiItem> nodes, ocr;
    private Pixels pixels;
    private UiItem first, second;
    @Before public void setup() {
        nodes = new ArrayList<>(); ocr = new ArrayList<>(); pixels = new Pixels();
        nodes.add(node("title_tv", "温州·EvolutionNicLive谢霆锋进化演唱会-温州站", 20, 20, 580, 60));
        nodes.add(node("tv_perform_name", "预约想看场次", 20, 85, 280, 115));
        nodes.add(node("project_detail_perform_flowlayout", "", 20, 150, 580, 390));
        first = session(150, "2026-10-31周六19:00", ORANGE);
        second = session(260, "2026-11-01周日19:00", BLACK);
        pixels.border(first, ORANGE);
        nodes.add(node("project_detail_perform_price_flowlayout", "", 20, 430, 580, 700));
        nodes.add(node("ll_perform_item", "", 20, 450, 300, 540));
        nodes.add(node("tv_tag", "缺货登记", 200, 470, 290, 500));
        text("看台980元", 40, 470, 190, 500, BLACK);
        nodes.add(node("bottom_layout", "", 0, 800, 600, 980));
        nodes.add(node("btn_buy_view", "", 20, 850, 580, 950));
        text("提交抢票预约", 160, 875, 430, 915, ORANGE);
    }
    private UiItem session(int top, String label, int ink) {
        UiItem cell = node("ll_perform_item", "", 20, top, 460, top + 95); nodes.add(cell);
        nodes.add(node("item_flowlayout", "", 40, top + 15, 440, top + 50));
        nodes.add(node("layout_time", "", 40, top + 50, 440, top + 85));
        nodes.add(node("tv_tag", "无票", 375, top + 20, 430, top + 45));
        text(label, 45, top + 18, 365, top + 45, ink);
        text("10月27日15:00开抢", 45, top + 56, 360, top + 80, ink);
        return cell;
    }
    private List<UiItem> all() { List<UiItem> all = new ArrayList<>(nodes); all.addAll(ocr); return all; }
    private List<CatalogParser.Entry<CatalogSession>> sessions() {
        List<UiItem> items = all(); return CatalogParser.sessions(items, CatalogParser.projectFromPage(items, "谢霆锋"));
    }
    private DamaiSkuAdapter.Result adapt() { return DamaiSkuAdapter.adapt(nodes, ocr, pixels, 1234); }

    @Test public void performanceAndSaleTimesAreSeparatedByObservedNativeRows() {
        assertTrue(ReservationSessionParser.isReservationPage(all()));
        var sessions = sessions(); assertEquals(2, sessions.size());
        assertEquals("2026-10-31", sessions.get(0).value.date); assertEquals("19:00", sessions.get(0).value.time);
        assertEquals("2026-11-01", sessions.get(1).value.date); assertEquals("19:00", sessions.get(1).value.time);
        assertTrue(sessions.get(0).value.label.contains("15:00开抢"));
    }
    @Test public void sameFrameSelectionIsCatalogOnlyEvenWithSubmissionButton() {
        var result = adapt(); assertNotNull(result.catalogSelection);
        assertEquals("2026-10-31", result.catalogSelection.date); assertEquals("19:00", result.catalogSelection.time);
        assertEquals(1234, result.catalogSelection.observedAtMs); assertNull(result.evidence);
        assertEquals(ocr, result.items);
        assertTrue(result.items.stream().noneMatch(item -> item.selected));
        var tiers = CatalogParser.tiers(all()); assertEquals(1, tiers.size());
        assertEquals(980, tiers.get(0).value.price); assertEquals("OUT_OF_STOCK", tiers.get(0).value.availability);
    }
    @Test public void noNativeReservationContextRetainsOrdinaryAmbiguityRejection() {
        nodes.removeIf(item -> item.resourceId.equals(ID + "tv_perform_name"));
        text("预约想看场次", 20, 85, 280, 115, BLACK);
        assertFalse(ReservationSessionParser.isReservationPage(all()));
        assertEquals("", sessions().get(0).value.time);
        assertNull(adapt().catalogSelection); assertNull(adapt().evidence);
    }
    @Test public void duplicateOrMissingLayoutCannotInferFirstTime() {
        nodes.add(node("item_flowlayout", "", 40, 165, 440, 200));
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
        nodes.removeIf(item -> item.resourceId.equals(ID + "layout_time"));
        assertTrue(sessions().isEmpty());
    }
    @Test public void twoPerformanceTimesInIdentityRowRemainAmbiguous() {
        replaceText("2026-10-31周六19:00", "2026-10-31 19:00 / 20:00");
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
    }
    @Test public void secondPerformanceInsteadOfExplicitSaleLineIsRejected() {
        replaceText("10月27日15:00开抢", "2026-11-01 15:00");
        assertTrue(sessions().isEmpty()); assertNull(adapt().catalogSelection);
    }
    @Test public void saleTextInPerformanceRegionCannotEnableException() {
        replaceText("2026-10-31周六19:00", "2026-10-31 19:00 10月27日15:00开抢");
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
    }
    @Test public void selectionStillNeedsOrangeBorderAndSemanticText() {
        pixels.clear(first); pixels.text(ocr.get(0), BLACK);
        assertNull(adapt().catalogSelection);
        pixels.border(first, ORANGE); assertNull(adapt().catalogSelection);
        pixels.text(ocr.get(0), ORANGE); assertNotNull(adapt().catalogSelection);
    }
    @Test public void twoSelectedSessionsOrInvalidTimestampCannotProduceSelection() {
        pixels.border(second, ORANGE); pixels.text(ocr.get(2), ORANGE);
        assertNull(adapt().catalogSelection);
        assertNull(DamaiSkuAdapter.adapt(nodes, ocr, pixels, 0).catalogSelection);
    }
    @Test public void malformedSelectedCellCannotBeFilteredOutToPickAnotherSession() {
        replaceText("2026-10-31周六19:00", "2026-10-31 19:00 / 20:00");
        pixels.border(second, ORANGE); pixels.text(ocr.get(2), ORANGE);
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
    }
    @Test public void missingIdentityRowInSecondOrangeCellStillMakesSelectionAmbiguous() {
        nodes.removeIf(item -> item.resourceId.equals(ID + "item_flowlayout") && item.top < 200);
        pixels.border(second, ORANGE); pixels.text(ocr.get(2), ORANGE);
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
    }
    @Test public void contradictoryNativeIdentityCannotBeOverriddenByOcr() {
        nodes.add(node("future_date", "2026-11-01 19:00", 45, 168, 365, 195));
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
        nodes.remove(nodes.size() - 1);
        nodes.add(node("future_time", "20：00", 300, 168, 365, 195));
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
        nodes.remove(nodes.size() - 1);
        nodes.add(node("future_short_date", "11/01", 300, 168, 365, 195));
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
    }
    @Test public void matchingNativeIdentityDoesNotCreateAnotherTimeCandidate() {
        nodes.add(node("future_date", "2026-10-31 19:00", 45, 168, 365, 195));
        assertEquals(2, sessions().size()); assertNotNull(adapt().catalogSelection); assertNull(adapt().evidence);
    }
    @Test public void restrictedPageCannotProduceReservationSelection() {
        nodes.add(node("error", "请完成验证", 20, 740, 400, 780));
        assertTrue(sessions().isEmpty()); assertNull(adapt().catalogSelection); assertNull(adapt().evidence);
    }
    @Test public void observedLeftOverhangStaysInCellAndKeepsActualOcrPixels() {
        observedGeometry();
        var parsed = sessions(); assertEquals(2, parsed.size());
        assertEquals("2026-10-31", parsed.get(0).value.date); assertEquals("19:00", parsed.get(0).value.time);
        var identity = ReservationSessionParser.sessions(all()).get(0);
        assertSame(ocr.get(0), identity.semanticLines.get(0)); assertEquals(100, identity.semanticLines.get(0).left);
        var selected = adapt(); assertNotNull(selected.catalogSelection); assertNull(selected.evidence);
        assertEquals("2026-10-31", selected.catalogSelection.date);
        pixels.clear(ocr.get(0)); pixels.text(ocr.get(0), BLACK);
        assertNull(adapt().catalogSelection);
    }
    @Test public void observedLeftOverhangCannotCrossSaleRowOrTicketCell() {
        observedGeometry(); moveFirstOcr(100, 877, 744, 950);
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
        observedGeometry(); moveFirstOcr(60, 877, 744, 925);
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
    }
    @Test public void expandedHorizontalBandDoesNotHideAnotherTimeOrNativeConflict() {
        observedGeometry(); text("20:00", 80, 877, 110, 925, BLACK);
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
        observedGeometry(); nodes.add(node("future_time", "20:00", 80, 877, 110, 925));
        assertEquals(1, sessions().size()); assertNull(adapt().catalogSelection);
    }
    private void moveFirstOcr(int l, int t, int r, int b) {
        UiItem previous = ocr.get(0);
        ocr.set(0, new UiItem(previous.text, l, t, r, b, true, false, false, UiItem.Source.OCR));
    }
    /** Exact observed geometry, synthetic public labels and pixels; no captured image is stored. */
    private void observedGeometry() {
        nodes = new ArrayList<>(); ocr = new ArrayList<>(); pixels = new Pixels(1260, 2772);
        nodes.add(node("title_tv", "温州·示例演唱会-温州站", 193, 192, 1186, 249));
        nodes.add(node("tv_perform_name", "预约想看场次", 74, 733, 410, 799));
        nodes.add(node("project_detail_perform_flowlayout", "", 74, 845, 1186, 1245));
        first = node("ll_perform_item", "", 74, 845, 893, 1013); nodes.add(first);
        nodes.add(node("item_flowlayout", "", 116, 871, 851, 938));
        nodes.add(node("layout_time", "", 116, 938, 851, 987));
        text("2026-10-31 周六19:00 无票", 100, 877, 744, 925, ORANGE);
        text("10月27日15:00开抢", 119, 943, 484, 981, ORANGE);
        second = node("ll_perform_item", "", 74, 1045, 874, 1213); nodes.add(second);
        nodes.add(node("item_flowlayout", "", 116, 1071, 832, 1138));
        nodes.add(node("layout_time", "", 116, 1138, 832, 1187));
        text("2026-11-01周日19:00", 119, 1084, 600, 1126, BLACK);
        text("10月27日15:00开抢", 119, 1143, 484, 1181, BLACK);
        text("无票", 659, 1089, 723, 1118, BLACK);
        pixels.border(first, ORANGE);
    }
    private void replaceText(String old, String value) {
        for (int i = 0; i < ocr.size(); i++) { UiItem item = ocr.get(i); if (item.text.equals(old))
            ocr.set(i, new UiItem(value, item.left, item.top, item.right, item.bottom, true, false, false, UiItem.Source.OCR)); }
    }
    private static UiItem node(String id, String text, int l, int t, int r, int b) {
        return new UiItem(text,l,t,r,b,true,false,false,UiItem.Source.NODE,ID+id);
    }
    private void text(String value,int l,int t,int r,int b,int color) {
        UiItem line = new UiItem(value,l,t,r,b,true,false,false,UiItem.Source.OCR); ocr.add(line); pixels.text(line,color);
    }
    private static final class Pixels implements DamaiSkuAdapter.Pixels {
        final int width, height; final int[] data;
        Pixels(){this(600,1000);} Pixels(int width,int height){this.width=width;this.height=height;data=new int[width*height];Arrays.fill(data,0xffffffff);}
        public int width(){return width;} public int height(){return height;} public int argb(int x,int y){return data[y*width+x];}
        void fill(int l,int t,int r,int b,int color){for(int y=t;y<b;y++)for(int x=l;x<r;x++)data[y*width+x]=color;}
        void clear(UiItem b){fill(b.left,b.top,b.right,b.bottom,0xffffffff);}
        void border(UiItem b,int color){fill(b.left,b.top,b.right,b.top+3,color);fill(b.left,b.bottom-3,b.right,b.bottom,color);fill(b.left,b.top,b.left+3,b.bottom,color);fill(b.right-3,b.top,b.right,b.bottom,color);}
        void text(UiItem b,int color){for(int x=b.left+3;x<b.right-3;x+=8)fill(x,b.top+5,x+2,b.bottom-5,color);}
    }
}
