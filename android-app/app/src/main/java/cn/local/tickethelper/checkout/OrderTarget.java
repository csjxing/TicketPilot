package cn.local.tickethelper.checkout;

/** Public event facts plus an opaque local viewer binding. All money is integer fen. */
public final class OrderTarget {
    public final String eventTitle, city, date, time, viewerBindingHash;
    public final long unitPriceFen, ticketSubtotalFen, payableFen;
    public final int quantity;
    public OrderTarget(String eventTitle, String city, String date, String time, long unitPriceFen, int quantity,
                       long ticketSubtotalFen, long payableFen, String viewerBindingHash) {
        this.eventTitle = CheckoutIdentity.clean(eventTitle); this.city = CheckoutIdentity.clean(city);
        this.date = CheckoutIdentity.clean(date); this.time = CheckoutIdentity.clean(time);
        this.unitPriceFen = unitPriceFen; this.quantity = quantity; this.ticketSubtotalFen = ticketSubtotalFen;
        this.payableFen = payableFen; this.viewerBindingHash = CheckoutIdentity.clean(viewerBindingHash);
    }
    public String fingerprint() {
        return CheckoutIdentity.hash(CheckoutIdentity.part(CheckoutIdentity.title(eventTitle,city)) + CheckoutIdentity.part(city)
                + CheckoutIdentity.part(date) + CheckoutIdentity.part(time) + "|" + unitPriceFen + "|" + quantity
                + "|" + ticketSubtotalFen + "|" + payableFen + "|" + viewerBindingHash);
    }
}
