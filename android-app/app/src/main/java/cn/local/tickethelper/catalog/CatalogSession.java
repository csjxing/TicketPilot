package cn.local.tickethelper.catalog;

/** Empty date/time means the visible page did not establish a complete, valid session. */
public final class CatalogSession {
    public final String key, label, date, time;
    public CatalogSession(String key, String label, String date, String time) {
        this.key = key; this.label = label; this.date = date; this.time = time;
    }
}
