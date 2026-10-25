package cn.local.tickethelper.catalog;

/** Same-frame visual session selection, independent of whether that session has any stock. */
public final class CatalogSelection {
    public final long observedAtMs;
    public final String date, time;
    public CatalogSelection(long observedAtMs, String date, String time) {
        this.observedAtMs = observedAtMs; this.date = date; this.time = time;
    }
}
