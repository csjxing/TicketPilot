package cn.local.tickethelper.catalog;

/** A public project label read from Damai's visible page; key is a label identity, not a server ID. */
public final class CatalogProject {
    public final String key, label, city, yearHint;
    public CatalogProject(String key, String label, String city, String yearHint) {
        this.key = key; this.label = label; this.city = city; this.yearHint = yearHint;
    }
}
