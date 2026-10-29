package cn.local.tickethelper.core;
import org.junit.Test;
import static org.junit.Assert.*;
public final class DetailNavigationGeometryTest {
    private UiItem label(int shift) { return new UiItem("缺货登记",200+shift,900,500+shift,940,true,false,false,UiItem.Source.OCR); }
    @Test public void smallOcrEdgeJitterRetainsSameInteriorClick() {
        assertTrue(DetailNavigationRead.sameFooterLabel(label(0),label(0)));
        assertTrue(DetailNavigationRead.sameFooterLabel(label(0),label(4)));
    }
    @Test public void movedOrDifferentLabelFailsClosed() {
        assertFalse(DetailNavigationRead.sameFooterLabel(label(0),label(5)));
        assertFalse(DetailNavigationRead.sameFooterLabel(label(0),label(400)));
        assertFalse(DetailNavigationRead.sameFooterLabel(label(0),new UiItem("确认登记",200,900,500,940,true,false,false,UiItem.Source.OCR)));
    }
}
