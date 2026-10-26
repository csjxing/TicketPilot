package cn.local.tickethelper.catalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Revision-bound preferences for the in-place chooser; never inventory or purchase authorization. */
public final class CatalogOverlayDraft {
    public static final long MAX_AGE_MS = 300_000;
    private final LinkedHashSet<String> allowed = new LinkedHashSet<>(), selected = new LinkedHashSet<>();
    private long revision = -1, readAtMs;
    private boolean reviewing;
    private int quantity;

    public CatalogOverlayDraft(int initialQuantity) { quantity = initialQuantity == 2 ? 2 : 1; }

    public void replace(long nextRevision, long nextReadAtMs, List<String> keys) {
        if (nextRevision != revision) { selected.clear(); reviewing = false; }
        revision = nextRevision; readAtMs = nextReadAtMs;
        allowed.clear();
        for (String key : keys) if (key != null && !key.isEmpty()) allowed.add(key);
        if (selected.retainAll(allowed)) reviewing = false;
    }

    public void select(String key, boolean checked) {
        if (!allowed.contains(key)) return;
        if (checked) selected.add(key); else selected.remove(key);
        reviewing = false;
    }

    public void setQuantity(int value) {
        if (value < 1 || value > 2) throw new IllegalArgumentException("Quantity must be one or two");
        if (quantity != value) reviewing = false;
        quantity = value;
    }

    public int quantity() { return quantity; }
    public Set<String> keys() { return Set.copyOf(selected); }
    public boolean contains(String key) { return selected.contains(key); }
    public boolean fresh(long nowMs, long currentRevision) {
        return revision >= 0 && revision == currentRevision && readAtMs > 0
                && nowMs >= readAtMs && nowMs - readAtMs <= MAX_AGE_MS;
    }
    public boolean beginReview(long nowMs, long currentRevision) {
        reviewing = !selected.isEmpty() && fresh(nowMs, currentRevision);
        return reviewing;
    }
    public boolean canSave(long nowMs, long currentRevision) {
        return reviewing && !selected.isEmpty() && fresh(nowMs, currentRevision);
    }
    public void edit() { reviewing = false; }
    public void clear() { revision = -1; readAtMs = 0; selected.clear(); allowed.clear(); reviewing = false; }
}
