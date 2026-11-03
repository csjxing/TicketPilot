package cn.local.tickethelper.checkout;

import java.time.ZoneId;
import static cn.local.tickethelper.checkout.CheckoutProfile.Role.*;

/**
 * Pending-payment page profile for Damai 9.0.37#109003700.
 * Best-effort selectors; refined during device verification.
 * Does not assert the order was created by this app session.
 */
public final class Damai937PendingPayment {
    public static final String VERSION = "9.0.37.5#109003705";
    private static final String ID = "cn.damai:id/";
    private Damai937PendingPayment() { }

    public static CheckoutProfile profile() {
        return new CheckoutProfile.Builder("cn.damai", VERSION, ZoneId.of("Asia/Shanghai"))
                .page(CheckoutObservation.Kind.PAYMENT_PENDING, ID + "action_bar_root",
                        new CheckoutProfile.Selector(ID + "toolbar", ID + "action_bar_root", "订单详情"))
                .field(ORDER_REFERENCE, CheckoutProfile.Selector.anchoredValue(
                        ID + "action_bar_root", "0/1/0", "[0-9A-Za-z]{8,64}"))
                .field(PAYMENT_COUNTDOWN, CheckoutProfile.Selector.anchoredPublicLabel(
                        ID + "bottom_layout", "0/1", "付款剩余"))
                .build();
    }
}
