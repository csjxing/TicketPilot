package cn.local.tickethelper.catalog;

/** A displayed denomination, never a promise of purchasable stock. */
public final class CatalogTier {
    public final String key, label, availability;
    public final int price;
    public CatalogTier(String key, String label, int price, String availability) {
        this.key = key; this.label = label; this.price = price; this.availability = availability;
    }
}
