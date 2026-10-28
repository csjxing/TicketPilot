package cn.local.tickethelper.core;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fixed relative gesture/frame policy only. These synthetic checks do not prove a live scroll. */
public final class DetailScrollPolicyTest {
    private UiItem nativeItem(String text, boolean selected) {
        return new UiItem(text, 0, 0, 100, 50, true, false, selected, UiItem.Source.NODE, "public-id");
    }
    @Test public void commandIsOnlyDefaultDisplayFixedRelativeUpwardScroll() {
        DetailScrollPolicy.Plan plan = DetailScrollPolicy.forRoot(0, 0, 1200, 2700, 1200, 2700);
        assertNotNull(plan); assertEquals(600, plan.x); assertEquals(1890, plan.startY); assertEquals(1026, plan.endY);
        assertArrayEquals(new String[]{"/system/bin/input", "-d", "0", "swipe", "600", "1890", "600", "1026", "280"}, plan.command());
        assertFalse(Arrays.asList(plan.command()).contains("tap"));
        assertFalse(Arrays.asList(plan.command()).contains("keyevent"));
    }
    @Test public void liveInsetsStillUseRootRelativeGeometry() {
        DetailScrollPolicy.Plan plan = DetailScrollPolicy.forRoot(0, 100, 1200, 2600, 1200, 2700);
        assertNotNull(plan); assertEquals(600, plan.x); assertEquals(1850, plan.startY); assertEquals(1050, plan.endY);
    }
    @Test public void invalidSmallOrModalFramesCannotSupplyArbitrarySwipeCoordinates() {
        for (int[] box : List.of(new int[]{-1,0,1200,2700}, new int[]{0,0,1201,2700}, new int[]{0,0,1200,2701},
                new int[]{0,0,1200,1000}, new int[]{0,0,500,2700}, new int[]{100,100,500,500}, new int[]{0,0,0,0}))
            assertNull(DetailScrollPolicy.forRoot(box[0], box[1], box[2], box[3], 1200, 2700));
        assertNull(DetailScrollPolicy.forRoot(0,0,20000,20000,20000,20000));
        assertNull(DetailScrollPolicy.forRoot(0,0,1200,2700,0,2700));
    }
    private boolean may(long page, long boundary, int window, long age, List<UiItem> current) {
        return DetailScrollPolicy.mayPrepareFrame(10,page,5,boundary,3,window,age,List.of(nativeItem("public",false)),current);
    }
    @Test public void onlyContentEventsMayRebaseUnchangedNativeWindowForScroll() {
        List<UiItem> same = List.of(nativeItem("public",false));
        assertTrue(may(10,5,3,100,same)); assertTrue(may(20,5,3,2999,same));
        assertFalse(may(9,5,3,100,same)); assertFalse(may(20,6,3,100,same));
        assertFalse(may(20,5,4,100,same)); assertFalse(may(20,5,-1,100,same));
        assertFalse(may(20,5,3,3000,same)); assertFalse(may(20,5,3,-1,same));
        assertFalse(may(20,5,3,100,List.of(nativeItem("changed",false))));
        assertFalse(may(20,5,3,100,List.of(nativeItem("public",true))));
        assertFalse(may(20,5,3,100,List.of()));
    }
    @Test public void modalAndPurchaseFieldsAreNotDetailSettlingInput() {
        for (String label : List.of("取消","确认登记","请登录","验证码","提交订单","待付款","选择场次","票档","填写信息"))
            assertTrue(DetailScrollPolicy.unsafePage(List.of(nativeItem(label,false))));
        assertTrue(DetailScrollPolicy.unsafePage(List.of(new UiItem("",0,0,100,50,true,false,false,
                UiItem.Source.NODE,"cn.damai:id/unknown_dialog"))));
        assertFalse(DetailScrollPolicy.unsafePage(List.of(nativeItem("长沙 公开演唱会",false),nativeItem("缺货登记",false))));
    }
}
