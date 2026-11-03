package cn.local.tickethelper.checkout;

import java.time.ZoneId;
import static cn.local.tickethelper.checkout.CheckoutProfile.Role.*;

/** Native confirmation contract reviewed against the paired device on 2026-10-31.
 * This is a confirmation reader only. It does not register a pending-payment page or enable orders.
 * No names, phone numbers, document numbers, event names or screen coordinates are embedded.
 */
public final class Damai937Confirmation {
    public static final String VERSION = "9.0.37.5#109003705";
    private static final String ID = "cn.damai:id/";
    private Damai937Confirmation() { }
    public static CheckoutProfile profile() {
        String list = ID + "recycler_view", viewers = ID + "recycler_main", row = ID + "layout_main";
        return new CheckoutProfile.Builder("cn.damai", VERSION, ZoneId.of("Asia/Shanghai"))
                .page(CheckoutObservation.Kind.CONFIRMATION, ID + "action_bar_root",
                        new CheckoutProfile.Selector(ID + "order_activity_title", ID + "action_bar_root", "确认购买"))
                .field(EVENT_TITLE, CheckoutProfile.Selector.anchoredValue(list, "0/1", "(.{5,300})"))
                .field(CITY, CheckoutProfile.Selector.anchoredValue(list, "0/2", "([\\p{IsHan}]{2,8}) \\| .{2,180}"))
                .field(SESSION_DATETIME, CheckoutProfile.Selector.anchoredValue(list, "0/3", "([0-9]{4}\\.[0-9]{2}\\.[0-9]{2} [0-9]{2}:[0-9]{2})"))
                .field(UNIT_PRICE, CheckoutProfile.Selector.anchoredValue(list, "0/5", "[￥¥]([0-9]{1,6}\\.[0-9]{2})票档"))
                .field(QUANTITY, CheckoutProfile.Selector.anchoredValue(list, "0/6", "×([12])张"))
                .field(PAYABLE, CheckoutProfile.Selector.anchoredValue(ID + "bottom_layout", "0/2/0", "[￥¥] ([0-9]{1,6}\\.[0-9]{2})"))
                .field(VIEWER_ROW, CheckoutProfile.Selector.node(row, viewers))
                .field(VIEWER_NAME, CheckoutProfile.Selector.value(ID + "text_name", row, "(.{1,60})"))
                .field(VIEWER_DOCUMENT, CheckoutProfile.Selector.value(ID + "text_num", row, "([0-9A-Z*＊●•·×]{2,64})"))
                .field(VIEWER_SELECTION, CheckoutProfile.Selector.node(ID + "checkbox", row))
                // This native layout has no agreement checkbox. Keep its observed refund notice
                // explicit, and reject any newly introduced checkable control outside attendees.
                .field(AGREEMENT, CheckoutProfile.Selector.anchoredPublicLabel(list, "0/14", "条件退"))
                .field(SUBMIT, CheckoutProfile.Selector.anchoredSubmit(ID + "bottom_layout", "0/4"))
                .native937Confirmation().build();
    }
}
