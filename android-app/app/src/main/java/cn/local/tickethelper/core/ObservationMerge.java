package cn.local.tickethelper.core;

import java.util.ArrayList;
import java.util.List;

/** Keep independent OCR provenance for the narrowly verified public-detail footer. */
public final class ObservationMerge {
    private ObservationMerge() {}
    public static List<UiItem> merge(List<UiItem> nodes, List<UiItem> items) {
        List<UiItem> merged = new ArrayList<>(nodes);
        for (UiItem item : items) {
            boolean footerProof = item.source == UiItem.Source.OCR && "缺货登记".equals(item.text);
            boolean duplicate = nodes.stream().anyMatch(n -> n.text.equals(item.text)
                    && Math.abs(n.centerY() - item.centerY()) < 30
                    && Math.abs(n.centerX() - item.centerX()) < 50);
            if (footerProof || !duplicate) merged.add(item);
        }
        return merged;
    }
}
