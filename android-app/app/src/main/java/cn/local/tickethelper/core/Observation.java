package cn.local.tickethelper.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Observation {
    public final String packageName;
    public final List<UiItem> items;
    public final long timestampMs;
    public final VerifiedSelection verifiedSelection;
    public Observation(String packageName, List<UiItem> items, long timestampMs) {
        this(packageName, items, timestampMs, null);
    }
    public Observation(String packageName, List<UiItem> items, long timestampMs, VerifiedSelection verifiedSelection) {
        this.packageName = packageName == null ? "" : packageName;
        List<UiItem> clean = new ArrayList<>();
        if (items != null) for (UiItem i : items) if (i != null) clean.add(i);
        this.items = Collections.unmodifiableList(clean);
        this.timestampMs = timestampMs;
        this.verifiedSelection = verifiedSelection;
    }

    /** Same-frame facts supplied only by a strict app UI adapter, never inferred inventory. */
    public static final class VerifiedSelection {
        public final long observedAtMs;
        public final String date, time;
        public final Integer selectedPrice, quantity, totalYuan;
        public final UiItem confirmTarget;
        public VerifiedSelection(long observedAtMs, String date, String time, Integer selectedPrice,
                                 Integer quantity, Integer totalYuan, UiItem confirmTarget) {
            this.observedAtMs = observedAtMs;
            this.date = date == null ? "" : date;
            this.time = time == null ? "" : time;
            this.selectedPrice = selectedPrice;
            this.quantity = quantity;
            this.totalYuan = totalYuan;
            this.confirmTarget = confirmTarget;
        }
    }
}
