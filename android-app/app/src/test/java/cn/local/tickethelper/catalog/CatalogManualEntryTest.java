package cn.local.tickethelper.catalog;

import cn.local.tickethelper.core.UiItem;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CatalogManualEntryTest {
    private static UiItem node(String text, String id, int top, int bottom) {
        return new UiItem(text,40,top,1000,bottom,true,false,false,UiItem.Source.NODE,"cn.damai:id/" + id);
    }
    private static CatalogProject expected() {
        return new CatalogProject("selected","EvolutionNicLive谢霆锋进化演唱会-杭州站","杭州","");
    }
    private static List<UiItem> detail() {
        return new ArrayList<>(List.of(node("杭州·EvolutionNicLive谢霆锋","info_v2_title_tv1",800,950),
                node("进化演唱会-杭州站","info_v2_title_tv2",950,1016)));
    }
    @Test public void manualWaitIsAtLeastThirtySecondsAndNeverResetsOperationLimit() {
        assertEquals(46_000,CatalogManualEntry.deadline(1_000,1_000));
        assertEquals(46_000,CatalogManualEntry.deadline(1_000,16_000));
        assertEquals(-1,CatalogManualEntry.deadline(1_000,16_001));
        assertEquals(-1,CatalogManualEntry.deadline(1_000,999));
    }
    @Test public void publicDetailWaitsUntilKnownNativeSkuRegionAndMatchingTitle() {
        var page = detail();
        assertEquals(CatalogManualEntry.State.WAIT,CatalogManualEntry.inspect(page,expected()));
        page.add(node("","project_detail_perform_flowlayout",1300,1800));
        assertEquals(CatalogManualEntry.State.READY,CatalogManualEntry.inspect(page,expected()));
        page.add(node("","project_detail_perform_flowlayout",1900,2200));
        assertEquals(CatalogManualEntry.State.WAIT,CatalogManualEntry.inspect(page,expected()));
    }
    @Test public void unrecognizedOrOcrRegionDoesNotResumeStrictRead() {
        var page = detail();
        page.add(new UiItem("",40,1300,1000,1800,true,false,false,UiItem.Source.OCR,
                "cn.damai:id/project_detail_perform_flowlayout"));
        assertEquals(CatalogManualEntry.State.WAIT,CatalogManualEntry.inspect(page,expected()));
        assertEquals(CatalogManualEntry.State.WAIT,CatalogManualEntry.inspect(
                List.of(node("","project_detail_perform_flowlayout",1300,1800)),expected()));
    }
    @Test public void changedProjectAndRestrictedPageStopWaiting() {
        var page = List.of(node("温州·EvolutionNicLive谢霆锋进化演唱会-温州站","title_tv",800,950),
                node("","project_detail_perform_flowlayout",1300,1800));
        assertEquals(CatalogManualEntry.State.WRONG_PROJECT,CatalogManualEntry.inspect(page,expected()));
        var restricted = detail(); restricted.add(node("确认购买","title",100,200));
        assertEquals(CatalogManualEntry.State.RESTRICTED,CatalogManualEntry.inspect(restricted,expected()));
    }
}
